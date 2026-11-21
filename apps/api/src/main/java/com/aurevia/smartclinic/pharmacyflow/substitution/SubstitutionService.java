package com.aurevia.smartclinic.pharmacyflow.substitution;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionCommands.DecideCommand;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionCommands.ProposeCommand;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionContract.ProposalView;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionContract.Reason;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionContract.State;
import com.aurevia.smartclinic.prescription.AllergyChecker;
import com.aurevia.smartclinic.prescription.DrugCatalog;
import com.aurevia.smartclinic.prescription.PrescriptionContract.Alternative;
import com.aurevia.smartclinic.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.smartclinic.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.smartclinic.prescription.PrescriptionContract.Draft;
import com.aurevia.smartclinic.prescription.PrescriptionContract.DrugEntry;

/**
 * RX-01 / RX-02 pharmacy substitution domain kernel.
 *
 * <p>The kernel owns tenant-scoped proposals, the tagged alternatives shown
 * with them and the signed revisions created on approval. It has no HTTP or
 * persistence dependency and reads only through the narrow, pharmacy-authorized
 * {@link com.aurevia.smartclinic.pharmacyflow.substitution.SubstitutionCatalog} port,
 * so the propose flow is reachable by pharmacy staff. Callers supply verified
 * membership roles, the active tenant, an {@code If-Match} precondition and an
 * {@code Idempotency-Key}, all of which must be re-derived on every command.</p>
 *
 * <p>Nothing changes the prescription until the named prescriber approves. A
 * proposal may name only a tagged alternative of the entry actually selected on
 * the draft; it must preserve strength and approved route, and it carries the
 * allergy assessment of the proposed medicine. Approval re-validates the catalog
 * and the allergy, requires an explicit prescriber attestation and records a new
 * signed revision with provenance. A decline leaves the original medication in
 * place.</p>
 */
@Service
public final class SubstitutionService {

    private static final String IDEM_SEPARATOR = "|";
    private static final String COMMAND_PROPOSE = "propose";
    private static final String COMMAND_APPROVE = "approve";
    private static final String COMMAND_DECLINE = "decline";
    private static final String UNSPECIFIED_ROUTE = "unspecified";

    private final Clock clock;
    private final SubstitutionCatalog catalog;

    private final Map<String, Map<String, Proposal>> proposals = new HashMap<>();
    private final Map<String, SignedRevision> revisions = new HashMap<>();
    private final Map<String, List<Alternative>> alternatives = new HashMap<>();
    private final Map<String, Proposal> proposeIdempotency = new HashMap<>();
    private final Map<String, Proposal> decideIdempotency = new HashMap<>();
    private final Map<String, Integer> revisionNumbers = new HashMap<>();

    /**
     * Create the substitution kernel over the pharmacy-authorized read port.
     *
     * @param clock   application clock for proposal and revision times
     * @param catalog tenant-guarded catalog, draft and allergy read port
     */
    public SubstitutionService(Clock clock, SubstitutionCatalog catalog) {
        this.clock = clock;
        this.catalog = catalog;
    }

    /**
     * Propose a tagged catalog alternative for the prescription's medication.
     *
     * <p>The entry must be the one selected on the draft, and the proposed entry
     * must be one of its tagged alternatives with the same strength and approved
     * route. A verified allergy conflict needs a documented override reason. A
     * retry with the same idempotency key returns the original proposal.</p>
     *
     * @param command propose command with verified tenant, actor and roles
     * @return the pending proposal with its side-by-side entries and allergy check
     * @throws ApiException on invalid input, missing permission, denial, an
     *         unbound or non-equivalent entry, an unreviewed allergy conflict or a replay
     */
    public synchronized ProposalView propose(ProposeCommand command) {
        SubstitutionInputs.requireText(command.organizationId(), "INVALID_ORGANIZATION",
                "active organization is required");
        SubstitutionInputs.requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        SubstitutionInputs.requireText(command.prescriptionId(), "INVALID_PRESCRIPTION",
                "prescription identifier is required");
        SubstitutionInputs.requireText(command.currentEntryId(), "INVALID_CATALOG_ENTRY",
                "current catalog entry is required");
        SubstitutionInputs.requireText(command.proposedEntryId(), "INVALID_CATALOG_ENTRY",
                "proposed catalog entry is required");
        String note = SubstitutionInputs.optionalNote(command.note(), "INVALID_NOTE",
                "substitution note is too long");
        String overrideReason = SubstitutionInputs.optionalNote(command.overrideReason(), "INVALID_NOTE",
                "allergy override reason is too long");
        SubstitutionInputs.requireText(command.idempotencyKey(), "INVALID_IDEMPOTENCY_KEY",
                "an idempotency key is required");
        Reason reason = Reason.from(command.reason());
        if (!SubstitutionRules.mayPropose(command.roles())) {
            throw new ApiException(403, "FORBIDDEN", "missing pharmacy substitution permission");
        }
        String key = key(command.organizationId(), command.prescriptionId(), command.subject(),
                COMMAND_PROPOSE, command.idempotencyKey());
        Proposal replay = proposeIdempotency.get(key);
        if (replay != null) {
            return view(replay);
        }

        Draft draft = catalog.draft(command.prescriptionId());
        if (draft == null) {
            throw new ApiException(404, "NOT_FOUND", "prescription draft not found");
        }
        if (!command.currentEntryId().equals(draft.selectedEntryId())) {
            throw new ApiException(422, "CURRENT_ENTRY_NOT_PRESCRIBED",
                    "the current entry is not the medication selected on the prescription");
        }
        Instant now = clock.instant();
        DrugCatalog drugCatalog = new DrugCatalog(catalog.entries());
        DrugEntry currentEntry = drugCatalog.effectiveEntry(command.currentEntryId(), now);
        if (currentEntry == null) {
            throw new ApiException(422, "INVALID_CATALOG_ENTRY",
                    "no approved effective catalog entry for the current medication");
        }
        Alternative chosen = drugCatalog.alternativesFor(currentEntry, now).stream()
                .filter(alternative -> alternative.entry().entryId().equals(command.proposedEntryId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(422, "NOT_CATALOG_ALTERNATIVE",
                        "only tagged catalog alternatives may be proposed"));
        DrugEntry proposed = chosen.entry();
        if (!sameStrength(currentEntry, proposed)) {
            throw new ApiException(422, "NOT_EQUIVALENT_STRENGTH",
                    "the alternative changes strength; re-select the medication instead");
        }
        String approvedRoute = catalog.route(proposed.entryId(), proposed.version());
        if (!UNSPECIFIED_ROUTE.equals(approvedRoute) && !approvedRoute.equalsIgnoreCase(draft.route())) {
            throw new ApiException(422, "ROUTE_MISMATCH",
                    "the alternative's approved route does not match the prescribed route");
        }
        AllergyCheck check = AllergyChecker.check(proposed, catalog.allergies(draft.patientId()));
        if (check.outcome() == CheckOutcome.CONFLICT && overrideReason == null) {
            throw new ApiException(422, "ALLERGY_OVERRIDE_REQUIRED",
                    "a verified allergy conflict needs a documented override reason");
        }

        Proposal proposal = new Proposal(SubstitutionInputs.newId(), command.organizationId(),
                command.prescriptionId(), draft.patientId(), draft.prescriberSubject(), command.subject(),
                currentEntry, proposed, reason, note, overrideReason, check, State.PENDING, 1, now);
        proposals.computeIfAbsent(command.organizationId(), ignored -> new HashMap<>())
                .put(proposal.id(), proposal);
        alternatives.put(proposal.id(), drugCatalog.alternativesFor(currentEntry, now));
        proposeIdempotency.put(key, proposal);
        return view(proposal);
    }

    /**
     * Approve or decline a pending proposal as its named prescriber.
     *
     * <p>Approval re-resolves the catalog and the allergy, requires an explicit
     * attestation and records an immutable signed revision carrying the proposal,
     * the replaced and replacement entries, the approving subject and the catalog
     * provenance. A decline records no revision. Both close the proposal with a
     * fresh version so a stale or replayed decision cannot act twice.</p>
     *
     * @param command decision command with the version read by the caller
     * @return the closed proposal and, on approval, its signed revision
     * @throws ApiException on invalid input, missing permission, an invisible
     *         proposal, a stale version, a closed proposal, a changed catalog, a
     *         new allergy conflict, a missing attestation or a replay
     */
    public synchronized ProposalView decide(DecideCommand command) {
        SubstitutionInputs.requireText(command.organizationId(), "INVALID_ORGANIZATION",
                "active organization is required");
        SubstitutionInputs.requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        SubstitutionInputs.requireText(command.proposalId(), "INVALID_PROPOSAL",
                "proposal identifier is required");
        SubstitutionInputs.requireText(command.idempotencyKey(), "INVALID_IDEMPOTENCY_KEY",
                "an idempotency key is required");
        SubstitutionInputs.requirePositive(command.expectedVersion(), "INVALID_VERSION",
                "version must be a positive integer");
        String note = SubstitutionInputs.optionalNote(command.note(), "INVALID_NOTE",
                "substitution note is too long");
        if (!SubstitutionRules.mayDecide(command.roles())) {
            throw new ApiException(403, "FORBIDDEN", "missing prescriber substitution permission");
        }
        Proposal current = find(command.organizationId(), command.proposalId());
        if (current == null) {
            throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
        }
        if (!command.subject().equals(current.prescriberSubject())) {
            throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
        }
        String key = key(command.organizationId(), command.proposalId(), command.subject(),
                command.approved() ? COMMAND_APPROVE : COMMAND_DECLINE, command.idempotencyKey());
        Proposal replay = decideIdempotency.get(key);
        if (replay != null) {
            return view(replay);
        }
        if (current.version() != command.expectedVersion()) {
            throw new ApiException(412, "STALE_VERSION", "the substitution proposal changed since it was read");
        }
        if (current.state() != State.PENDING) {
            throw new ApiException(409, "CLOSED_PROPOSAL", "a decided substitution proposal is read-only");
        }

        Instant now = clock.instant();
        int nextVersion = current.version() + 1;
        Proposal updated;
        if (command.approved()) {
            SubstitutionInputs.requireText(command.attestation(), "ATTESTATION_REQUIRED",
                    "the prescriber must attest the substitution before signing");
            DrugCatalog fresh = new DrugCatalog(catalog.entries());
            DrugEntry freshCurrent = fresh.effectiveEntry(current.original().entryId(), now);
            DrugEntry freshProposed = fresh.effectiveEntry(current.proposed().entryId(), now);
            if (freshCurrent == null || freshProposed == null
                    || !stillAlternative(fresh, freshCurrent, freshProposed, now)) {
                throw new ApiException(422, "CATALOG_CHANGED",
                        "the proposed alternative is no longer approved and effective in the catalog");
            }
            AllergyCheck freshCheck = AllergyChecker.check(freshProposed, catalog.allergies(current.patientId()));
            if (freshCheck.outcome() == CheckOutcome.CONFLICT
                    && current.allergyCheck().outcome() != CheckOutcome.CONFLICT) {
                throw new ApiException(422, "ALLERGY_CONFLICT_AT_APPROVAL",
                        "a verified allergy conflict appeared after the proposal");
            }
            int revisionNumber = revisionNumbers.merge(current.prescriptionId(), 1, Integer::sum);
            SignedRevision revision = new SignedRevision(SubstitutionInputs.newId(), current.prescriptionId(),
                    current.id(), revisionNumber, freshCurrent, freshProposed, current.reason(),
                    command.subject(), freshCheck, command.attestation(),
                    freshProposed.approvalProvenance(), now);
            revisions.put(current.id(), revision);
            updated = new Proposal(current.id(), current.organizationId(), current.prescriptionId(),
                    current.patientId(), current.prescriberSubject(), current.proposedBy(), current.original(),
                    current.proposed(), current.reason(), note, current.overrideReason(), current.allergyCheck(),
                    State.APPROVED, nextVersion, current.proposedAt());
        } else {
            updated = new Proposal(current.id(), current.organizationId(), current.prescriptionId(),
                    current.patientId(), current.prescriberSubject(), current.proposedBy(), current.original(),
                    current.proposed(), current.reason(), note, current.overrideReason(), current.allergyCheck(),
                    State.DECLINED, nextVersion, current.proposedAt());
        }
        proposals.get(command.organizationId()).put(current.id(), updated);
        decideIdempotency.put(key, updated);
        return view(updated);
    }

    /**
     * Read one proposal visible to its pharmacy proposer or named prescriber.
     *
     * @param organizationId active tenant
     * @param proposalId     tenant-local proposal identifier
     * @param subject        authenticated subject
     * @param roles          active roles in the tenant
     * @return the proposal with its side-by-side entries, revision and alternatives
     * @throws ApiException on invalid input, denial or an invisible proposal
     */
    public synchronized ProposalView view(String organizationId, String proposalId, String subject,
                                          Set<String> roles) {
        SubstitutionInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        SubstitutionInputs.requireText(proposalId, "INVALID_PROPOSAL", "proposal identifier is required");
        SubstitutionInputs.requireText(subject, "INVALID_ACTOR", "actor is required");
        Proposal current = find(organizationId, proposalId);
        if (current == null) {
            throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
        }
        if (!SubstitutionRules.isParty(subject, current)) {
            throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
        }
        if (subject.equals(current.proposedBy())) {
            if (!SubstitutionRules.mayPropose(roles)) {
                throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
            }
        } else if (!SubstitutionRules.mayDecide(roles)) {
            throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
        }
        return view(current);
    }

    private static boolean sameStrength(DrugEntry first, DrugEntry second) {
        return first.strengthValue().equals(second.strengthValue())
                && first.strengthUnit().equalsIgnoreCase(second.strengthUnit());
    }

    private static boolean stillAlternative(DrugCatalog catalog, DrugEntry current, DrugEntry proposed,
                                            Instant at) {
        return catalog.alternativesFor(current, at).stream()
                .anyMatch(alternative -> alternative.entry().entryId().equals(proposed.entryId()));
    }

    private ProposalView view(Proposal proposal) {
        return new ProposalView(proposal, revisions.get(proposal.id()),
                new ArrayList<>(alternatives.getOrDefault(proposal.id(), List.of())));
    }

    private Proposal find(String organizationId, String proposalId) {
        Map<String, Proposal> tenant = proposals.get(organizationId);
        return tenant == null ? null : tenant.get(proposalId);
    }

    private static String key(String organizationId, String prescriptionId, String subject, String command,
                              String idempotencyKey) {
        return organizationId + IDEM_SEPARATOR + prescriptionId + IDEM_SEPARATOR + subject + IDEM_SEPARATOR
                + command + IDEM_SEPARATOR + idempotencyKey;
    }
}
