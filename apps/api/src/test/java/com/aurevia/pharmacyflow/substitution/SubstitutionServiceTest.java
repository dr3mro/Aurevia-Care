package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.substitution.SubstitutionCommands.DecideCommand;
import com.aurevia.pharmacyflow.substitution.SubstitutionCommands.ProposeCommand;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.ProposalView;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Reason;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.State;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.Draft;

/** RX-01/RX-02 substitution kernel: safety gates, proposal, approval, decline and isolation. */
class SubstitutionServiceTest {

    private static final Set<String> PHARMACY_ROLES = Set.of(SpecialtyRoles.PHARMACY_STAFF);
    private static final Set<String> CLINICIAN_ROLES = Set.of(SpecialtyRoles.CLINICIAN);

    private FakeSubstitutionCatalog catalog;
    private FakeSubstitutionStore store;
    private FakeSubstitutionApplication application;
    private SubstitutionService service;

    @BeforeEach
    void setUp() {
        catalog = new FakeSubstitutionCatalog();
        store = new FakeSubstitutionStore();
        application = new FakeSubstitutionApplication();
        TransactionTemplate transactions = org.mockito.Mockito.mock(TransactionTemplate.class);
        org.mockito.Mockito.when(transactions.execute(org.mockito.ArgumentMatchers.any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<?> callback = invocation.getArgument(0);
                    return callback.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
                });
        service = new SubstitutionService(Clock.fixed(SubstitutionFixtures.AT, ZoneOffset.UTC), catalog,
                store, application, transactions, org.mockito.Mockito.mock(AuditAppender.class));
    }

    private ProposalView propose(String proposedId, String reason, String subject, Set<String> roles,
                                 String key, String overrideReason) {
        return service.propose(new ProposeCommand(SubstitutionFixtures.ORG, subject, roles,
                SubstitutionFixtures.PRESCRIPTION, SubstitutionFixtures.ORIGINAL_ID, proposedId,
                reason, "out of stock", overrideReason, key));
    }

    private ProposalView propose(String key) {
        return propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock", SubstitutionFixtures.PHARMACIST,
                PHARMACY_ROLES, key, null);
    }

    private String newProposalId() {
        return propose("new-proposal-" + UUID.randomUUID()).proposal().id();
    }

    private DecideCommand approve(String proposalId, int version, String subject, Set<String> roles, String key) {
        return new DecideCommand(SubstitutionFixtures.ORG, subject, roles, proposalId, version, true,
                SubstitutionFixtures.ATTESTATION, null, key);
    }

    private DecideCommand decline(String proposalId, int version, String note, String key) {
        return new DecideCommand(SubstitutionFixtures.ORG, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES,
                proposalId, version, false, null, note, key);
    }

    @Test
    void proposalDoesNotChangeThePrescriptionUntilApproval() {
        ProposalView view = propose("propose-pending");

        assertEquals(State.PENDING, view.proposal().state());
        assertNull(view.appliedRevision());
        assertEquals(SubstitutionFixtures.ORIGINAL_ID, view.proposal().original().entryId());
        assertEquals(SubstitutionFixtures.ALTERNATIVE_ID, view.proposal().proposed().entryId());
        assertEquals(CheckOutcome.NO_CONFLICT, view.proposal().allergyCheck().outcome());
        assertEquals(1, view.alternatives().size());

        ProposalView readBack = service.view(SubstitutionFixtures.ORG, view.proposal().id(),
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES);
        assertEquals(State.PENDING, readBack.proposal().state());
        assertNull(readBack.appliedRevision());
    }

    @Test
    void approvalCreatesSignedRevisionWithProvenance() {
        ProposalView pending = propose("propose-approve");

        ProposalView approved = service.decide(approve(pending.proposal().id(), 1,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "approve-1"));

        assertEquals(State.APPROVED, approved.proposal().state());
        assertEquals(2, approved.proposal().version());
        assertNotNull(approved.appliedRevision());
        assertEquals(pending.proposal().id(), approved.appliedRevision().proposalId());
        assertEquals(SubstitutionFixtures.PRESCRIPTION, approved.appliedRevision().prescriptionId());
        assertEquals(1, approved.appliedRevision().revisionNumber());
        assertEquals(SubstitutionFixtures.ORIGINAL_ID, approved.appliedRevision().replaced().entryId());
        assertEquals(SubstitutionFixtures.ALTERNATIVE_ID, approved.appliedRevision().replacement().entryId());
        assertEquals(Reason.STOCK, approved.appliedRevision().reason());
        assertEquals(SubstitutionFixtures.PRESCRIBER, approved.appliedRevision().approvedBy());
        assertEquals(SubstitutionFixtures.ATTESTATION, approved.appliedRevision().attestation());
        assertEquals("catalog-v3-approved", approved.appliedRevision().catalogProvenance());
        assertEquals(SubstitutionFixtures.AT, approved.appliedRevision().signedAt());
        assertEquals(CheckOutcome.NO_CONFLICT, approved.appliedRevision().allergyCheck().outcome());
    }

    @Test
    void approvalVersionNumbersIncreasePerPrescription() {
        String first = newProposalId();
        ProposalView firstApproved = service.decide(approve(first, 1, SubstitutionFixtures.PRESCRIBER,
                CLINICIAN_ROLES, "approve-first"));
        String second = newProposalId();
        ProposalView secondApproved = service.decide(approve(second, 1, SubstitutionFixtures.PRESCRIBER,
                CLINICIAN_ROLES, "approve-second"));
        assertEquals(1, firstApproved.appliedRevision().revisionNumber());
        assertEquals(2, secondApproved.appliedRevision().revisionNumber());
    }

    @Test
    void declineKeepsOriginalAndCreatesNoRevision() {
        ProposalView pending = propose(SubstitutionFixtures.ALTERNATIVE_ID, "brand",
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "propose-decline", null);

        ProposalView declined = service.decide(decline(pending.proposal().id(), 1, "keep the current brand",
                "decline-1"));

        assertEquals(State.DECLINED, declined.proposal().state());
        assertEquals("keep the current brand", declined.proposal().note());
        assertNull(declined.appliedRevision());
        assertEquals(SubstitutionFixtures.ORIGINAL_ID, declined.proposal().original().entryId());
        assertEquals(SubstitutionFixtures.ALTERNATIVE_ID, declined.proposal().proposed().entryId());
    }

    @Test
    void declineWithoutNoteIsAllowed() {
        ProposalView pending = propose(SubstitutionFixtures.ALTERNATIVE_ID, "formulation",
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "propose-decline-no-note", null);
        ProposalView declined = service.decide(decline(pending.proposal().id(), 1, null, "decline-no-note"));
        assertNull(declined.proposal().note());
        assertNull(declined.appliedRevision());
    }

    @Test
    void onlyTaggedCatalogAlternativesCanBeProposed() {
        ApiException failure = assertThrows(ApiException.class, () -> propose(SubstitutionFixtures.OTHER_DRUG_ID,
                "stock", SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "propose-not-alternative", null));
        assertEquals(422, failure.status());
        assertEquals("NOT_CATALOG_ALTERNATIVE", failure.code());
    }

    @Test
    void currentEntryMustBeTheOneSelectedOnTheDraft() {
        catalog.withDraft(new Draft(SubstitutionFixtures.PRESCRIPTION, 1, SubstitutionFixtures.PATIENT,
                "draft", "another-entry", 1, "Paracetamol", "500", "mg", "oral", "twice daily", "60",
                "tablet", null, "water", "pharmacy-a", null, SubstitutionFixtures.PRESCRIBER,
                SubstitutionFixtures.FROM));
        ApiException failure = assertThrows(ApiException.class, () -> propose("propose-unbound"));
        assertEquals(422, failure.status());
        assertEquals("CURRENT_ENTRY_NOT_PRESCRIBED", failure.code());
    }

    @Test
    void unknownDraftIsConcealed() {
        catalog.withDraft(null);
        ApiException failure = assertThrows(ApiException.class, () -> propose("propose-missing-draft"));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
    }

    @Test
    void catalogWithoutTheSelectedEntryIsRejected() {
        catalog.withEntries(List.of());
        ApiException failure = assertThrows(ApiException.class, () -> propose("propose-empty-catalog"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_CATALOG_ENTRY", failure.code());
    }

    @Test
    void strengthChangeIsRejected() {
        catalog.withEntries(List.of(SubstitutionFixtures.original(), SubstitutionFixtures.strongerAlternative()));
        ApiException failure = assertThrows(ApiException.class, () -> propose("propose-strength"));
        assertEquals(422, failure.status());
        assertEquals("NOT_EQUIVALENT_STRENGTH", failure.code());

        catalog.withEntries(List.of(SubstitutionFixtures.original(),
                SubstitutionFixtures.alternativeWithDifferentUnit()));
        assertEquals("NOT_EQUIVALENT_STRENGTH",
                assertThrows(ApiException.class, () -> propose("propose-unit")).code());
    }

    @Test
    void routeIsValidatedAndLegacySentinelIsAccepted() {
        catalog.withRoute("injectable");
        ApiException failure = assertThrows(ApiException.class, () -> propose("propose-route"));
        assertEquals(422, failure.status());
        assertEquals("ROUTE_MISMATCH", failure.code());

        catalog.withRoute("unspecified");
        assertEquals(State.PENDING, propose("propose-legacy-route").proposal().state());
    }

    @Test
    void verifiedAllergyConflictNeedsADocumentedOverride() {
        catalog.withAllergies(SubstitutionFixtures.conflictingAllergies());
        ApiException failure = assertThrows(ApiException.class, () -> propose("propose-allergy"));
        assertEquals(422, failure.status());
        assertEquals("ALLERGY_OVERRIDE_REQUIRED", failure.code());

        ProposalView withOverride = propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock",
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "propose-allergy-override",
                "reviewed with patient; benefit outweighs risk");
        assertEquals(CheckOutcome.CONFLICT, withOverride.proposal().allergyCheck().outcome());
        assertEquals("reviewed with patient; benefit outweighs risk", withOverride.proposal().overrideReason());
    }

    @Test
    void invalidReasonIsRejected() {
        ApiException failure = assertThrows(ApiException.class, () -> propose(SubstitutionFixtures.ALTERNATIVE_ID,
                "colour", SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "propose-bad-reason", null));
        assertEquals(422, failure.status());
        assertEquals("INVALID_SUBSTITUTION_REASON", failure.code());
    }

    @Test
    void wrongRolesAreRejected() {
        assertEquals(403, assertThrows(ApiException.class, () -> propose(SubstitutionFixtures.ALTERNATIVE_ID,
                "stock", SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "propose-wrong-role", null)).status());
        String id = newProposalId();
        assertEquals(403, assertThrows(ApiException.class, () -> service.decide(approve(id, 1,
                SubstitutionFixtures.PRESCRIBER, PHARMACY_ROLES, "decide-wrong-role"))).status());
    }

    @Test
    void proposeRejectsEveryBlankBoundaryFieldAndOverlongNotes() {
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand("  ",
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.ORIGINAL_ID, SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null, "k")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                "  ", PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION, SubstitutionFixtures.ORIGINAL_ID,
                SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null, "k")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "  ", SubstitutionFixtures.ORIGINAL_ID,
                SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null, "k")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                "  ", SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null, "k")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.ORIGINAL_ID, "  ", "stock", null, null, "k")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.ORIGINAL_ID, SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null, "  ")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.ORIGINAL_ID, SubstitutionFixtures.ALTERNATIVE_ID, "stock",
                "x".repeat(501), null, "k")));
        assertThrows(ApiException.class, () -> service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.ORIGINAL_ID, SubstitutionFixtures.ALTERNATIVE_ID, "stock",
                null, "x".repeat(501), "k")));
    }

    @Test
    void decideRejectsBlankBoundaryFieldsAndInvalidVersion() {
        String id = newProposalId();
        assertThrows(ApiException.class, () -> service.decide(new DecideCommand("  ",
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, id, 1, true,
                SubstitutionFixtures.ATTESTATION, null, "k")));
        assertThrows(ApiException.class, () -> service.decide(new DecideCommand(SubstitutionFixtures.ORG,
                "  ", CLINICIAN_ROLES, id, 1, true, SubstitutionFixtures.ATTESTATION, null, "k")));
        assertThrows(ApiException.class, () -> service.decide(new DecideCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "  ", 1, true,
                SubstitutionFixtures.ATTESTATION, null, "k")));
        assertThrows(ApiException.class, () -> service.decide(new DecideCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, id, 0, true,
                SubstitutionFixtures.ATTESTATION, null, "k")));
        assertThrows(ApiException.class, () -> service.decide(new DecideCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, id, 1, true,
                SubstitutionFixtures.ATTESTATION, null, "  ")));
        assertThrows(ApiException.class, () -> service.decide(new DecideCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, id, 1, true,
                SubstitutionFixtures.ATTESTATION, "x".repeat(501), "k")));
    }

    @Test
    void unknownAndWrongTenantProposalsAreConcealed() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.ORG,
                "missing", SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES)).status());
        String id = newProposalId();
        assertEquals(404, assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.OTHER_ORG,
                id, SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.decide(new DecideCommand(
                SubstitutionFixtures.OTHER_ORG, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES,
                id, 1, true, SubstitutionFixtures.ATTESTATION, null, "k"))).status());
    }

    @Test
    void onlyTheNamedPrescriberMayDecide() {
        String id = newProposalId();
        ApiException failure = assertThrows(ApiException.class, () -> service.decide(approve(id, 1,
                SubstitutionFixtures.OTHER_CLINICIAN, CLINICIAN_ROLES, "decide-other")));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
    }

    @Test
    void staleVersionAndClosedProposalAreRejected() {
        String id = newProposalId();
        assertEquals(412, assertThrows(ApiException.class, () -> service.decide(approve(id, 2,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "decide-stale"))).status());
        service.decide(decline(id, 1, null, "decide-first"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.decide(approve(id, 2,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "decide-closed"))).status());
    }

    @Test
    void attestationIsRequiredAtApproval() {
        String id = newProposalId();
        ApiException failure = assertThrows(ApiException.class, () -> service.decide(new DecideCommand(
                SubstitutionFixtures.ORG, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES,
                id, 1, true, "  ", null, "approve-no-attestation")));
        assertEquals(422, failure.status());
        assertEquals("ATTESTATION_REQUIRED", failure.code());
    }

    @Test
    void approvalRevalidatesTheCatalog() {
        String currentMissing = newProposalId();
        String proposedMissing = newProposalId();
        String noLongerAlternative = newProposalId();
        String differentIdentity = newProposalId();
        catalog.withEntries(List.of());
        assertEquals("CATALOG_CHANGED", assertThrows(ApiException.class, () -> service.decide(
                approve(currentMissing, 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "appr-changed-1")))
                .code());

        catalog.withEntries(List.of(SubstitutionFixtures.original()));
        assertEquals("CATALOG_CHANGED", assertThrows(ApiException.class, () -> service.decide(
                approve(proposedMissing, 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "appr-changed-2")))
                .code());

        catalog.withEntries(List.of(SubstitutionFixtures.original(), SubstitutionFixtures.unrelated()));
        assertEquals("CATALOG_CHANGED", assertThrows(ApiException.class, () -> service.decide(
                approve(noLongerAlternative, 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "appr-changed-3")))
                .code());

        catalog.withEntries(List.of(SubstitutionFixtures.original(),
                SubstitutionFixtures.differentIdentityAlternative()));
        assertEquals("CATALOG_CHANGED", assertThrows(ApiException.class, () -> service.decide(
                approve(differentIdentity, 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "appr-changed-4")))
                .code());
    }

    @Test
    void approvalRevalidatesAllergies() {
        String newConflict = newProposalId();
        catalog.withAllergies(SubstitutionFixtures.conflictingAllergies());
        ApiException failure = assertThrows(ApiException.class, () -> service.decide(approve(newConflict, 1,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "appr-allergy")));
        assertEquals(422, failure.status());
        assertEquals("ALLERGY_CONFLICT_AT_APPROVAL", failure.code());

        catalog.withAllergies(SubstitutionFixtures.conflictingAllergies());
        String reviewed = propose(SubstitutionFixtures.ALTERNATIVE_ID, "stock",
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "propose-reviewed-conflict", "reviewed override")
                .proposal().id();
        assertEquals(State.APPROVED, service.decide(approve(reviewed, 1, SubstitutionFixtures.PRESCRIBER,
                CLINICIAN_ROLES, "appr-reviewed-conflict")).proposal().state());
    }

    @Test
    void replayedCommandsRecordOnce() {
        ProposalView first = propose("replay-propose");
        ProposalView replay = propose("replay-propose");
        assertEquals(first.proposal().id(), replay.proposal().id());

        ProposalView otherPrescription = service.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, "other-prescription",
                SubstitutionFixtures.ORIGINAL_ID, SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null,
                "replay-propose"));
        assertEquals(SubstitutionFixtures.PRESCRIPTION, first.proposal().prescriptionId());
        assertEquals("other-prescription", otherPrescription.proposal().prescriptionId());

        service.decide(approve(first.proposal().id(), 1, SubstitutionFixtures.PRESCRIBER,
                CLINICIAN_ROLES, "replay-decide"));
        ProposalView decidedAgain = service.decide(approve(first.proposal().id(), 1,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "replay-decide"));
        assertEquals(2, decidedAgain.proposal().version());
        assertEquals(1, decidedAgain.appliedRevision().revisionNumber());
    }

    @Test
    void viewEnforcesPartyAndRoleIsolation() {
        String id = newProposalId();
        assertEquals(SubstitutionFixtures.PHARMACIST, service.view(SubstitutionFixtures.ORG, id,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES).proposal().proposedBy());
        assertEquals(SubstitutionFixtures.PRESCRIBER, service.view(SubstitutionFixtures.ORG, id,
                SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES).proposal().prescriberSubject());
        assertEquals(404, assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.ORG,
                id, SubstitutionFixtures.OTHER_CLINICIAN, CLINICIAN_ROLES)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.ORG,
                id, SubstitutionFixtures.PHARMACIST, CLINICIAN_ROLES)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.ORG,
                id, SubstitutionFixtures.PRESCRIBER, PHARMACY_ROLES)).status());
        assertThrows(ApiException.class, () -> service.view("  ", id,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES));
        assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.ORG, "  ",
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES));
        assertThrows(ApiException.class, () -> service.view(SubstitutionFixtures.ORG, id,
                "  ", PHARMACY_ROLES));
    }

    @Test
    void approvalAppliesTheSignedReplacementToTheAuthoritativePrescription() {
        String id = newProposalId();
        service.decide(approve(id, 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, "apply-approve"));

        assertEquals(1, application.calls());
        assertEquals(SubstitutionFixtures.ALTERNATIVE_ID, application.lastReplacement().entryId());
    }

    @Test
    void declineNeverAppliesAPrescriptionChange() {
        String id = newProposalId();
        service.decide(decline(id, 1, "not now", "apply-decline"));

        assertEquals(0, application.calls());
        assertEquals(State.DECLINED, store.findProposal(SubstitutionFixtures.ORG, id).state());
    }

    @Test
    void aFailedApplicationPreventsTheDecisionFromBeingClosed() {
        String id = newProposalId();
        application.failWith(new ApiException(422, "NO_ENTITLEMENT", "the prescriber is not entitled"));
        ApiException failure = assertThrows(ApiException.class,
                () -> service.decide(approve(id, 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES,
                        "apply-failure")));

        assertEquals("NO_ENTITLEMENT", failure.code());
        assertEquals(0, application.calls());
        assertEquals(State.PENDING, store.findProposal(SubstitutionFixtures.ORG, id).state());
        assertNull(store.findRevision(SubstitutionFixtures.ORG, id));
    }

    @Test
    void anIdempotencyClaimWithoutAWinnerFailsClosed() {
        String id = newProposalId();
        String composedKey = SubstitutionFixtures.ORG + "|" + id + "|" + SubstitutionFixtures.PRESCRIBER
                + "|approve|orphan-claim";
        store.claim(SubstitutionFixtures.ORG, "approve", composedKey, "missing-proposal");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.decide(new DecideCommand(SubstitutionFixtures.ORG,
                        SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES, id, 1, true,
                        SubstitutionFixtures.ATTESTATION, null, "orphan-claim")));
        assertEquals(409, failure.status());
        assertEquals("DECISION_CONFLICT", failure.code());
    }

    @Test
    void aReplayWithoutARecordedWinnerFailsClosed() {
        FakeSubstitutionStore inconsistent = new FakeSubstitutionStore() {
            private boolean firstClaim = true;

            @Override
            public boolean claim(String organizationId, String command, String idempotencyKey, String proposalId) {
                if (firstClaim) {
                    firstClaim = false;
                    return true;
                }
                return false;
            }

            @Override
            public String findReplayProposalId(String organizationId, String command, String idempotencyKey) {
                return null;
            }
        };
        org.springframework.transaction.support.TransactionTemplate transactions =
                org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
        org.mockito.Mockito.when(transactions.execute(
                org.mockito.ArgumentMatchers.any(org.springframework.transaction.support.TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    org.springframework.transaction.support.TransactionCallback<?> callback =
                            invocation.getArgument(0);
                    return callback.doInTransaction(
                            org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class));
                });
        SubstitutionService inconsistentService = new SubstitutionService(
                Clock.fixed(SubstitutionFixtures.AT, ZoneOffset.UTC), catalog, inconsistent, application,
                transactions, org.mockito.Mockito.mock(AuditAppender.class));
        ProposalView pending = inconsistentService.propose(new ProposeCommand(SubstitutionFixtures.ORG,
                SubstitutionFixtures.PHARMACIST, PHARMACY_ROLES, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.ORIGINAL_ID, SubstitutionFixtures.ALTERNATIVE_ID, "stock", null, null,
                "missing-winner"));

        ApiException failure = assertThrows(ApiException.class, () -> inconsistentService.decide(
                approve(pending.proposal().id(), 1, SubstitutionFixtures.PRESCRIBER, CLINICIAN_ROLES,
                        "approve-no-winner")));
        assertEquals("DECISION_CONFLICT", failure.code());
    }

    @Test
    void decisionProjectionIsImmutable() {
        ProposalView view = propose("immutable-view");
        assertThrows(UnsupportedOperationException.class, () -> view.alternatives().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.proposal().original().ingredients().clear());
    }
}
