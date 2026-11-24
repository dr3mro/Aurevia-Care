package com.aurevia.messaging.acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;

/**
 * Synthetic store boundary acceptance: tenant isolation, append-only
 * uniqueness, overdue candidate filters and idempotency keys (MSG-05).
 */
class InMemoryInstructionFactStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String INSTRUCTION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_INSTRUCTION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String OTHER_UNIT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryInstructionFactStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryInstructionFactStore();
    }

    @Test
    void membershipAndChartLookupsReturnEmptyForUnknownAndInactiveSubjects() {
        store.addMember(ORG, "inactive", "patient", false);
        assertFalse(store.hasActiveMembership(ORG, "inactive"));
        assertTrue(store.activeRoles(ORG, "inactive").isEmpty());
        assertTrue(store.activeRoles(ORG, "ghost").isEmpty());
        assertTrue(store.verifiedPatientCharts(ORG, "ghost").isEmpty());
        store.unlinkPatient(ORG, "ghost", PATIENT);
        store.linkPatient(ORG, "linked", PATIENT);
        assertEquals(Set.of(PATIENT), store.verifiedPatientCharts(ORG, "linked"));
        store.unlinkPatient(ORG, "linked", PATIENT);
        assertTrue(store.verifiedPatientCharts(ORG, "linked").isEmpty());
    }

    @Test
    void connectionAndUnitAssignmentCanBeRevoked() {
        store.addConnection(ORG, PATIENT, UNIT, true);
        assertTrue(store.hasActiveConnection(ORG, PATIENT, UNIT));
        store.addConnection(ORG, PATIENT, UNIT, false);
        assertFalse(store.hasActiveConnection(ORG, PATIENT, UNIT));
        store.assignStaffUnit(ORG, "staff", UNIT);
        assertTrue(store.isStaffAssignedToUnit(ORG, "staff", UNIT));
        assertFalse(store.isStaffAssignedToUnit(ORG, "staff", OTHER_UNIT));
    }

    @Test
    void instructionSeedingValidatesTenantOwnership() {
        Instruction instruction = instruction(ORG, INSTRUCTION, 1, true, NOW.minusSeconds(1), UNIT);
        assertEquals(instruction, store.putInstruction(ORG, instruction));
        assertThrows(IllegalArgumentException.class, () -> store.putInstruction(ORG, null));
        assertThrows(IllegalArgumentException.class,
                () -> store.putInstruction(OTHER_ORG, instruction));
        assertTrue(store.instruction(ORG, INSTRUCTION, 1).isPresent());
        assertTrue(store.instruction(ORG, INSTRUCTION, 2).isEmpty());
        assertTrue(store.latestInstruction(ORG, OTHER_INSTRUCTION).isEmpty());
    }

    @Test
    void instructionsForUnitOnlyIncludesPublishedRowsInTheActiveTenant() {
        store.putInstruction(ORG, instruction(ORG, INSTRUCTION, 1, true, NOW, UNIT));
        assertTrue(store.instructionsForUnit(ORG, UNIT).isEmpty());
        store.putPublication(ORG, new Publication(ORG, INSTRUCTION, 1, "staff", NOW));
        assertEquals(1, store.instructionsForUnit(ORG, UNIT).size());
        assertTrue(store.instructionsForUnit(ORG, OTHER_UNIT).isEmpty());
        assertTrue(store.instructionsForUnit(OTHER_ORG, UNIT).isEmpty());
    }

    @Test
    void duplicatePublicationReturnsTheFirstFact() {
        Publication first = new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW);
        Publication competing = new Publication(ORG, INSTRUCTION, 1, "staff-2", NOW.plusSeconds(1));
        assertEquals(first, store.putPublication(ORG, first));
        assertEquals(first, store.putPublication(ORG, competing));
        assertEquals(List.of(first), store.publications(ORG, INSTRUCTION, 1));
        assertTrue(store.publications(ORG, INSTRUCTION, 2).isEmpty());
    }

    @Test
    void dispatchFactsAreScopedToTenantInstructionAndRevision() {
        Dispatch target = new Dispatch(ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-1", NOW);
        store.appendDispatch(ORG, target);
        store.appendDispatch(ORG, new Dispatch(ORG, INSTRUCTION, 2, "NEW_INSTRUCTION", "corr-2", NOW));
        store.appendDispatch(ORG, new Dispatch(ORG, OTHER_INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-3", NOW));
        store.appendDispatch(OTHER_ORG, new Dispatch(OTHER_ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-4", NOW));
        assertEquals(List.of(target), store.dispatches(ORG, INSTRUCTION, 1));
        assertTrue(store.dispatches(ORG, INSTRUCTION, 3).isEmpty());
    }

    @Test
    void firstViewAndAcknowledgmentAreUniquePerViewerAndRevision() {
        FirstView first = new FirstView(ORG, INSTRUCTION, 1, "patient-1", NOW);
        FirstView competing = new FirstView(ORG, INSTRUCTION, 1, "patient-1", NOW.plusSeconds(1));
        assertEquals(first, store.putFirstViewIfAbsent(ORG, first));
        assertEquals(first, store.putFirstViewIfAbsent(ORG, competing));
        assertEquals(List.of(first), store.firstViews(ORG, INSTRUCTION, 1, "patient-1"));
        assertTrue(store.firstViews(ORG, INSTRUCTION, 1, "patient-2").isEmpty());

        Acknowledgment ack = new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW);
        Acknowledgment replay = new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW.plusSeconds(1));
        Acknowledgment secondViewer = new Acknowledgment(ORG, INSTRUCTION, 1, "patient-2",
                AcknowledgmentScope.RECEIPT, NOW.plusSeconds(2));
        assertEquals(ack, store.putAcknowledgmentIfAbsent(ORG, ack));
        assertEquals(ack, store.putAcknowledgmentIfAbsent(ORG, replay));
        assertEquals(secondViewer, store.putAcknowledgmentIfAbsent(ORG, secondViewer));
        assertEquals(List.of(ack, secondViewer), store.acknowledgments(ORG, INSTRUCTION, 1));
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 2).isEmpty());
    }

    @Test
    void followUpResolutionRequiresAnExistingFact() {
        FollowUp routed = new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW, FollowUpState.ROUTED,
                null, null, null);
        FollowUp competing = new FollowUp(ORG, INSTRUCTION, 1, OTHER_UNIT, NOW.plusSeconds(1),
                FollowUpState.OPEN, null, null, null);
        assertEquals(routed, store.putFollowUpIfAbsent(ORG, routed));
        assertEquals(routed, store.putFollowUpIfAbsent(ORG, competing));
        assertTrue(store.followUps(ORG, INSTRUCTION, 2).isEmpty());
        FollowUp resolved = store.resolveFollowUp(ORG, INSTRUCTION, 1, "staff", "owner contacted", NOW);
        assertEquals(FollowUpState.RESOLVED, resolved.state());
        assertEquals("staff", resolved.resolvedBy());
        assertEquals("owner contacted", resolved.reason());
        assertEquals(NOW, resolved.resolvedAt());
        assertThrows(IllegalStateException.class,
                () -> store.resolveFollowUp(ORG, OTHER_INSTRUCTION, 1, "staff", "x", NOW));
    }

    @Test
    void allFollowUpsAreScopedToTheActiveTenant() {
        store.putFollowUpIfAbsent(ORG, new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW,
                FollowUpState.ROUTED, null, null, null));
        store.putFollowUpIfAbsent(OTHER_ORG, new FollowUp(OTHER_ORG, OTHER_INSTRUCTION, 1, UNIT, NOW,
                FollowUpState.ROUTED, null, null, null));
        assertEquals(1, store.allFollowUps(ORG).size());
        assertEquals(1, store.allFollowUps(OTHER_ORG).size());
    }

    @Test
    void overdueCandidatesExcludeEveryNonOverdueCondition() {
        store.putInstruction(ORG, instruction(ORG, INSTRUCTION, 1, true, NOW.minusSeconds(1), UNIT));
        store.putInstruction(ORG, instruction(ORG, OTHER_INSTRUCTION, 1, false, NOW.minusSeconds(1), UNIT));
        store.putInstruction(ORG, instruction(ORG, "11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1,
                true, null, UNIT));
        store.putInstruction(ORG, instruction(ORG, "22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1,
                true, NOW.plusSeconds(60), UNIT));
        store.putInstruction(ORG, instruction(ORG, "33333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1,
                true, NOW, UNIT));
        store.putInstruction(OTHER_ORG, instruction(OTHER_ORG, INSTRUCTION, 1, true,
                NOW.minusSeconds(1), UNIT));
        assertEquals(1, store.overdueCandidates(ORG, NOW).size());

        store.putAcknowledgmentIfAbsent(ORG, new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW));
        assertTrue(store.overdueCandidates(ORG, NOW).isEmpty());
        store.putInstruction(ORG, instruction(ORG, "44444444-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1,
                true, NOW.minusSeconds(1), UNIT));
        assertEquals(1, store.overdueCandidates(ORG, NOW).size());
        store.putFollowUpIfAbsent(ORG, new FollowUp(ORG, "44444444-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                1, UNIT, NOW, FollowUpState.ROUTED, null, null, null));
        assertTrue(store.overdueCandidates(ORG, NOW).isEmpty());
    }

    @Test
    void idempotencyKeysAreDurableAndScoped() {
        assertTrue(store.putClientDigest(ORG, "staff", "PUBLISH", "key-1", "digest-1"));
        assertFalse(store.putClientDigest(ORG, "staff", "PUBLISH", "key-1", "digest-2"));
        assertEquals("digest-1", store.clientDigest(ORG, "staff", "PUBLISH", "key-1").orElseThrow());
        assertTrue(store.clientDigest(ORG, "staff", "PUBLISH", "key-2").isEmpty());
        assertEquals(1, store.clientDigestCount(ORG, "staff", "PUBLISH"));
        store.putClientDigest(ORG, "staff", "DISPATCH", "key-1", "digest-3");
        assertEquals(1, store.clientDigestCount(ORG, "staff", "PUBLISH"));
        assertEquals(1, store.clientDigestCount(ORG, "staff", "DISPATCH"));
        assertEquals(0, store.clientDigestCount(OTHER_ORG, "staff", "PUBLISH"));
    }

    @Test
    void auditAndFactProjectionAreScopedToTheirTenant() {
        store.appendAudit(new AuditFact(ORG, "staff", "PUBLISH", INSTRUCTION, "SUCCESS",
                "P", "S", "C", NOW));
        assertEquals(1, store.auditEvents(ORG).size());
        assertTrue(store.auditEvents(OTHER_ORG).isEmpty());
        assertTrue(store.allFacts(ORG, INSTRUCTION).isEmpty());

        store.putPublication(ORG, new Publication(ORG, INSTRUCTION, 1, "staff", NOW));
        store.appendDispatch(ORG, new Dispatch(ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr", NOW));
        store.putFirstViewIfAbsent(ORG, new FirstView(ORG, INSTRUCTION, 1, "patient-1", NOW));
        store.putAcknowledgmentIfAbsent(ORG, new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW));
        store.putFollowUpIfAbsent(ORG, new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW,
                FollowUpState.ROUTED, null, null, null));
        assertEquals(5, store.allFacts(ORG, INSTRUCTION).size());
        assertTrue(store.allFacts(OTHER_ORG, INSTRUCTION).isEmpty());
        assertTrue(store.allFacts(ORG, OTHER_INSTRUCTION).isEmpty());
    }

    private static Instruction instruction(String organizationId, String id, int revision,
                                           boolean requiresAck, Instant dueAt, String unitId) {
        return new Instruction(id, organizationId, PATIENT, unitId, revision,
                "artifact-" + revision, requiresAck, dueAt);
    }
}
