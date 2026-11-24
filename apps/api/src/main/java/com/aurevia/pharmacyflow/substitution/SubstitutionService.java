package com.aurevia.pharmacyflow.substitution;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.pharmacyflow.substitution.SubstitutionCommands.DecideCommand;
import com.aurevia.pharmacyflow.substitution.SubstitutionCommands.ProposeCommand;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.ProposalView;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Reason;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.State;
import com.aurevia.prescription.AllergyChecker;
import com.aurevia.prescription.DrugCatalog;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.tenant.TenantContext;

/**
 * RX-01 / RX-02 pharmacy substitution domain kernel over durable storage.
 *
 * <p>Proposals, their frozen alternatives and signed revisions live in the V94
 * tenant-guarded tables, never in process memory, so any service instance sees
 * the same decision after a restart. Every command runs in one transaction: the
 * idempotency claim, version and entitlement recheck through the prescription
 * workflow, the durable decision and its provenance, the authoritative
 * prescription change and the audit event commit together or not at all. A
 * decline records no revision and leaves the prescribed medicine untouched.</p>
 *
 * <p>The kernel reads only through the narrow, pharmacy-authorized
 * {@link com.aurevia.pharmacyflow.substitution.SubstitutionCatalog} port
 * and writes only through {@link com.aurevia.pharmacyflow.substitution.SubstitutionStore}
 * and {@link com.aurevia.pharmacyflow.substitution.SubstitutionApplication}.
 * Callers supply verified membership roles, the active tenant, an {@code If-Match}
 * precondition and an {@code Idempotency-Key}, all of which are re-derived on
 * every command.</p>
 */
@Service
public final class SubstitutionService {

    private static final String IDEM_SEPARATOR = "|";
    private static final String COMMAND_PROPOSE = "propose";
    private static final String COMMAND_APPROVE = "approve";
    private static final String COMMAND_DECLINE = "decline";
    private static final String UNSPECIFIED_ROUTE = "unspecified";
    private static final String AUDIT_PURPOSE = "PHARMACY_SUBSTITUTION";
    private static final String AUDIT_ACTION = "SUBSTITUTION_DECIDED";
    private static final String AUDIT_RESOURCE_TYPE = "PHARMACY_SUBSTITUTION";

    private final Clock clock;
    private final SubstitutionCatalog catalog;
    private final SubstitutionStore store;
    private final SubstitutionApplication application;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the substitution kernel over the pharmacy read, decision and application ports.
     *
     * @param clock       application clock for proposal and revision times
     * @param catalog     tenant-guarded catalog, draft and allergy read port
     * @param store       durable tenant-guarded decision store
     * @param application authoritative prescription application port
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public SubstitutionService(Clock clock, SubstitutionCatalog catalog, SubstitutionStore store,
                               SubstitutionApplication application, TransactionTemplate transactions,
                               AuditAppender audit) {
        this.clock = clock;
        this.catalog = catalog;
        this.store = store;
        this.application = application;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Propose a tagged catalog alternative for the prescription's medication.
     *
     * <p>The entry must be the one selected on the draft, and the proposed entry
     * must be one of its tagged alternatives with the same strength and approved
     * route. A verified allergy conflict needs a documented override reason. A
     * retry with the same idempotency key returns the original proposal without
     * writing a second one.</p>
     *
     * @param command propose command with verified tenant, actor and roles
     * @return the pending proposal with its side-by-side entries and allergy check
     * @throws ApiException on invalid input, missing permission, denial, an
     *         unbound or non-equivalent entry, an unreviewed allergy conflict or a replay
     */
    public ProposalView propose(ProposeCommand command) {
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
        return transactions.execute(status -> proposeInside(command, note, overrideReason, reason, key));
    }

    private ProposalView proposeInside(ProposeCommand command, String note, String overrideReason,
                                       Reason reason, String key) {
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
        List<Alternative> alternatives = drugCatalog.alternativesFor(currentEntry, now);
        if (!store.claim(command.organizationId(), COMMAND_PROPOSE, key, proposal.id())) {
            return replay(command.organizationId(), COMMAND_PROPOSE, key);
        }
        store.insertProposal(proposal, alternatives);
        return view(proposal);
    }

    /**
     * Approve or decline a pending proposal as its named prescriber.
     *
     * <p>Approval re-resolves the catalog and the allergy, requires an explicit
     * attestation, rechecks the authoritative prescription version and prescribing
     * entitlement, records an immutable signed revision, applies the replacement
     * through the prescription workflow and appends the audit event. A decline
     * records no revision. Both close the proposal with a fresh version so a stale
     * or replayed decision cannot act twice. Any failure rolls the transaction
     * back, leaving the proposal, prescription and audit unchanged.</p>
     *
     * @param command decision command with the version read by the caller
     * @return the closed proposal and, on approval, its signed revision
     * @throws ApiException on invalid input, missing permission, an invisible
     *         proposal, a stale version, a closed proposal, a changed catalog, a
     *         new allergy conflict, a missing attestation, missing entitlement or a replay
     */
    public ProposalView decide(DecideCommand command) {
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
        String decision = command.approved() ? COMMAND_APPROVE : COMMAND_DECLINE;
        String key = key(command.organizationId(), command.proposalId(), command.subject(),
                decision, command.idempotencyKey());
        return transactions.execute(status -> decideInside(command, note, decision, key));
    }

    private ProposalView decideInside(DecideCommand command, String note, String decision, String key) {
        Proposal current = store.findProposal(command.organizationId(), command.proposalId());
        if (current == null || !command.subject().equals(current.prescriberSubject())) {
            throw new ApiException(404, "NOT_FOUND", "substitution proposal not found");
        }
        if (!store.claim(command.organizationId(), decision, key, current.id())) {
            return replay(command.organizationId(), decision, key);
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
            int revisionNumber = store.nextRevisionNumber(command.organizationId(), current.prescriptionId());
            SignedRevision revision = new SignedRevision(SubstitutionInputs.newId(), current.prescriptionId(),
                    current.id(), revisionNumber, freshCurrent, freshProposed, current.reason(),
                    command.subject(), freshCheck, command.attestation(),
                    freshProposed.approvalProvenance(), now);
            application.apply(command.organizationId(), command.subject(), current.prescriptionId(),
                    current.version(), freshProposed);
            store.insertRevision(command.organizationId(), revision);
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
        store.closeProposal(updated, now);
        audit.append(TenantContext.current(), AUDIT_PURPOSE, AUDIT_ACTION, AUDIT_RESOURCE_TYPE,
                AUDIT_PURPOSE, current.id(), "SUCCESS");
        return view(updated);
    }

    /**
     * Read one proposal visible to its pharmacy proposer or named prescriber.
     *
     * @param organizationId active tenant
     * @param proposalId     tenant-local proposal identifier
     * @param subject        authenticated subject
     * @param roles          active roles in the tenant
     * @return the persisted proposal with its side-by-side entries, revision and alternatives
     * @throws ApiException on invalid input, denial or an invisible proposal
     */
    public ProposalView view(String organizationId, String proposalId, String subject, Set<String> roles) {
        SubstitutionInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        SubstitutionInputs.requireText(proposalId, "INVALID_PROPOSAL", "proposal identifier is required");
        SubstitutionInputs.requireText(subject, "INVALID_ACTOR", "actor is required");
        return transactions.execute(status -> {
            Proposal current = store.findProposal(organizationId, proposalId);
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
        });
    }

    private ProposalView replay(String organizationId, String command, String key) {
        String proposalId = store.findReplayProposalId(organizationId, command, key);
        Proposal decided = proposalId == null ? null : store.findProposal(organizationId, proposalId);
        if (decided == null) {
            // A claim without a winning proposal can only come from corrupted
            // storage; fail closed rather than returning an empty decision.
            throw new ApiException(409, "DECISION_CONFLICT", "the idempotency key has no matching decision");
        }
        return view(decided);
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
        return new ProposalView(proposal,
                store.findRevision(proposal.organizationId(), proposal.id()),
                store.findAlternatives(proposal.organizationId(), proposal.id()));
    }

    private static String key(String organizationId, String resourceId, String subject, String command,
                              String idempotencyKey) {
        return organizationId + IDEM_SEPARATOR + resourceId + IDEM_SEPARATOR + subject + IDEM_SEPARATOR
                + command + IDEM_SEPARATOR + idempotencyKey;
    }
}
