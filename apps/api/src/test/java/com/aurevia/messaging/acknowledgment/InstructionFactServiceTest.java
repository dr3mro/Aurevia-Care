package com.aurevia.messaging.acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FactType;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Timeline;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ResolveCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ViewCommand;
import com.aurevia.tenant.TenantContext;

/**
 * Red-first acceptance for MSG-05 at the domain boundary.
 *
 * <p>Publication, notification dispatch, first in-app view and explicit patient
 * acknowledgment are asserted to be four distinct append-only facts. Every
 * subject, chart, instruction and artifact below is synthetic.</p>
 */
class InstructionFactServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-user";
    private static final String OTHER_SUBJECT = "other-patient-user";
    private static final String STAFF = "unit-staff-1";
    private static final String OTHER_STAFF = "unit-staff-2";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String INSTRUCTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String OTHER_INSTRUCTION = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String CORRELATION = "corr-g3-08";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryInstructionFactStore store;
    private InstructionFactService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryInstructionFactStore();
        service = new InstructionFactService(store, new InstructionFactActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        context(ORG, SUBJECT, "patient");
        store.addMember(ORG, SUBJECT, "patient", true);
        store.addMember(ORG, OTHER_SUBJECT, "patient", true);
        store.addMember(ORG, STAFF, "lab_collector", true);
        store.addMember(ORG, OTHER_STAFF, "lab_collector", true);
        store.addMember(ORG, "tenant-admin", "tenant_admin", true);
        store.linkPatient(ORG, SUBJECT, PATIENT);
        store.linkPatient(ORG, OTHER_SUBJECT, OTHER_PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        store.addConnection(ORG, OTHER_PATIENT, OTHER_UNIT, true);
        store.assignStaffUnit(ORG, STAFF, UNIT);
        store.assignStaffUnit(ORG, OTHER_STAFF, OTHER_UNIT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // A1 — publication is its own fact.
    @Test
    void publishRecordsOnePublicationFactWithOpaqueContentReference() {
        asStaff();
        Publication publication = service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true,
                NOW.toString(), true, "client-1"));
        assertEquals(ORG, publication.organizationId());
        assertEquals(INSTRUCTION, publication.instructionId());
        assertEquals(1, publication.revision());
        assertEquals(STAFF, publication.actor());
        assertEquals(NOW, publication.occurredAt());
        assertEquals(FactType.PUBLICATION, publication.type());
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
        assertEquals("SUCCESS", lastAudit().outcome());
        assertEquals("INSTRUCTION_FACT", lastAudit().purpose());
        assertEquals("PATIENT_MESSAGE", lastAudit().scope());
        assertEquals(CORRELATION, lastAudit().correlationId());
        assertTrue(publication.toString().contains(INSTRUCTION));
    }

    @Test
    void publishWithoutNotificationRecordsPublicationOnly() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
        assertTrue(store.dispatches(ORG, INSTRUCTION, 1).isEmpty());
    }

    @Test
    void notificationDispatchIsAGenericSeparateFact() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, true, "client-1"));
        List<Dispatch> dispatches = store.dispatches(ORG, INSTRUCTION, 1);
        assertEquals(1, dispatches.size());
        Dispatch dispatch = dispatches.get(0);
        assertEquals(FactType.DISPATCH, dispatch.type());
        assertEquals("NEW_INSTRUCTION", dispatch.genericKey());
        assertEquals(CORRELATION, dispatch.correlationId());
        assertEquals(NOW, dispatch.occurredAt());
        // A dispatch fact carries a generic key only: never clinical text or a content reference.
        assertFalse(dispatch.toString().contains("collection"));
        assertFalse(dispatch.toString().contains("artifact-"));
        assertTrue(dispatch.toString().contains("NEW_INSTRUCTION"));
    }

    @Test
    void retryDispatchAddsASecondGenericFactAndIsIdempotentByClientId() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, true, "client-1"));
        Dispatch first = service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1"));
        assertEquals(2, store.dispatches(ORG, INSTRUCTION, 1).size());
        assertEquals(first, service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1")));
        assertEquals(2, store.dispatches(ORG, INSTRUCTION, 1).size());
        assertEquals(1, store.clientDigestCount(ORG, STAFF, "DISPATCH"));
    }

    @Test
    void retryDispatchRejectsADuplicateClientIdWithADifferentRevision() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, true, "client-1"));
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, false, null, true, "client-2"));
        service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1"));
        ApiException error = assertThrows(ApiException.class,
                () -> service.retryDispatch(new RetryCommand(INSTRUCTION, 2, "retry-1")));
        assertEquals(409, error.status());
        assertEquals("IDEMPOTENCY_CONFLICT", error.code());
    }

    @Test
    void retryDispatchRequiresAPublishedRevision() {
        asStaff();
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.retryDispatch(new RetryCommand(OTHER_INSTRUCTION, 1, "retry-0"))).status());
        seedInstruction(rev(1, true, NOW));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1"))).status());
    }

    // A3 — first view is recorded once and is not an acknowledgment.
    @Test
    void firstViewIsRecordedOnceAndNeverAcknowledges() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.plusSeconds(60).toString(),
                true, "client-1"));
        asPatient();
        FirstView first = service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-1"));
        FirstView again = service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-2"));
        assertEquals(first, again);
        assertEquals(NOW, first.occurredAt());
        assertEquals(SUBJECT, first.actor());
        assertEquals(FactType.FIRST_VIEW, first.type());
        assertEquals(1, store.firstViews(ORG, INSTRUCTION, 1, SUBJECT).size());
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 1).isEmpty());
    }

    @Test
    void firstViewIsScopedToViewerAndRevision() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, true, NOW.toString(), true, "client-2"));
        asPatient();
        service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-1"));
        service.markFirstView(new ViewCommand(INSTRUCTION, 2, "view-2"));
        TenantContext.set(new TenantContext(ORG, OTHER_SUBJECT, "PATIENT_MESSAGE", CORRELATION));
        store.addConnection(ORG, OTHER_PATIENT, UNIT, true);
        store.linkPatient(ORG, OTHER_SUBJECT, PATIENT);
        service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-3"));
        assertEquals(1, store.firstViews(ORG, INSTRUCTION, 1, SUBJECT).size());
        assertEquals(1, store.firstViews(ORG, INSTRUCTION, 2, SUBJECT).size());
        assertEquals(1, store.firstViews(ORG, INSTRUCTION, 1, OTHER_SUBJECT).size());
    }

    // A4/A5 — acknowledgment binds to the exact revision and confirms receipt only.
    @Test
    void acknowledgmentBindsToTheExactPublishedRevision() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.plusSeconds(60).toString(),
                true, "client-1"));
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, true, NOW.plusSeconds(60).toString(),
                true, "client-2"));
        asPatient();
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"))).status());
        Acknowledgment acknowledgment = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 2, "ack-2"));
        assertEquals(2, acknowledgment.revision());
        assertEquals(AcknowledgmentScope.RECEIPT, acknowledgment.scope());
        assertEquals(FactType.ACKNOWLEDGMENT, acknowledgment.type());
        assertEquals(SUBJECT, acknowledgment.actor());
        assertEquals(NOW, acknowledgment.occurredAt());
        assertEquals(1, store.acknowledgments(ORG, INSTRUCTION, 2).size());
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 1).isEmpty());
    }

    @Test
    void anAcknowledgmentOfAnOldRevisionDoesNotSatisfyANewOne() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.plusSeconds(60).toString(),
                true, "client-1"));
        asPatient();
        Acknowledgment first = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"));
        assertEquals(1, first.revision());
        assertEquals(1, store.acknowledgments(ORG, INSTRUCTION, 1).size());

        // A later revision requests its own receipt. The receipt recorded while
        // revision 1 was current names exactly revision 1 and must not be
        // carried over to satisfy revision 2 (MSG-05: bind to the exact revision).
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, true, NOW.plusSeconds(60).toString(),
                true, "client-2"));
        asPatient();
        assertEquals(1, store.acknowledgments(ORG, INSTRUCTION, 1).size());
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 2).isEmpty());

        Acknowledgment second = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 2, "ack-2"));
        assertEquals(2, second.revision());
        assertNotEquals(first, second);
        assertEquals(1, store.acknowledgments(ORG, INSTRUCTION, 2).size());
        // Both receipts survive as distinct facts, each naming its own revision.
        assertEquals(2, service.facts(INSTRUCTION).facts().stream()
                .filter(fact -> fact.type() == FactType.ACKNOWLEDGMENT)
                .map(fact -> ((Acknowledgment) fact).revision())
                .distinct().count());
    }

    @Test
    void anAcknowledgmentOfAnOldRevisionDoesNotSuppressTheNewRevisionsOverdueFollowUp() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(120).toString(),
                true, "client-1"));
        asPatient();
        service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"));
        asStaff();
        // Revision 1 is acknowledged and is the latest revision: nothing is overdue yet.
        assertTrue(service.overdueFollowUps(UNIT).isEmpty());

        // A later, unacknowledged revision must be routed on its own revision,
        // not suppressed by the earlier revision's receipt.
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, true, NOW.minusSeconds(60).toString(),
                true, "client-2"));
        List<FollowUp> followUps = service.overdueFollowUps(UNIT);
        assertEquals(1, followUps.size());
        assertEquals(2, followUps.get(0).revision());
        assertEquals(UNIT, followUps.get(0).unitId());
    }

    @Test
    void acknowledgmentIsIdempotentAndConflictsOnClientIdReuse() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        asPatient();
        Acknowledgment first = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"));
        assertEquals(first, service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1")));
        assertEquals(1, store.acknowledgments(ORG, INSTRUCTION, 1).size());
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, true, NOW.toString(), true, "client-2"));
        asPatient();
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 2, "ack-1")));
        assertEquals(409, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        assertTrue(store.acknowledgments(ORG, INSTRUCTION, 2).isEmpty());
    }

    @Test
    void acknowledgmentIsAttributedToTheAuthenticatedAccountOnASharedChart() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        asPatient();
        Acknowledgment first = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-first"));
        assertEquals(SUBJECT, first.actor());

        // IAM-05 permits several verified accounts to link to one chart. A
        // second account must record its own receipt rather than receive the
        // first account's fact with the wrong actor.
        TenantContext.set(new TenantContext(ORG, OTHER_SUBJECT, "PATIENT_MESSAGE", CORRELATION));
        store.linkPatient(ORG, OTHER_SUBJECT, PATIENT);
        Acknowledgment second = service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-second"));
        assertEquals(OTHER_SUBJECT, second.actor());
        assertNotEquals(first, second);
        assertEquals(2, store.acknowledgments(ORG, INSTRUCTION, 1).size());
        assertEquals(second, service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-second")));
    }

    @Test
    void tenantAdminNeedsUnitAssignmentForOwningUnitActions() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        TenantContext.set(new TenantContext(ORG, "tenant-admin", "PATIENT_MESSAGE", CORRELATION));
        // Action permission and object (unit) authorization are separate (IAM-01).
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.overdueFollowUps(UNIT)).status());
        // An unassigned actor cannot even learn that the follow-up exists.
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, "contacted"))).status());
        store.assignStaffUnit(ORG, "tenant-admin", UNIT);
        assertEquals(1, service.overdueFollowUps(UNIT).size());
        assertEquals(FollowUpState.RESOLVED, service.resolveFollowUp(
                new ResolveCommand(INSTRUCTION, 1, "contacted")).state());
    }

    @Test
    void acknowledgmentIsRejectedWhenItWasNotRequested() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        asPatient();
        ApiException error = assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1")));
        assertEquals(422, error.status());
        assertEquals("ACKNOWLEDGMENT_NOT_REQUESTED", error.code());
    }

    // A6 — overdue requested acknowledgments route to the owning unit.
    @Test
    void overdueRequestedAcknowledgmentRoutesToTheOwningUnit() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        List<FollowUp> followUps = service.overdueFollowUps(UNIT);
        assertEquals(1, followUps.size());
        FollowUp followUp = followUps.get(0);
        assertEquals(UNIT, followUp.unitId());
        assertEquals(FollowUpState.ROUTED, followUp.state());
        assertEquals(INSTRUCTION, followUp.instructionId());
        assertEquals(1, followUp.revision());
        // The route is idempotent: a second scan does not create a duplicate follow-up.
        assertEquals(followUp, service.overdueFollowUps(UNIT).get(0));
        assertTrue(store.followUps(ORG, INSTRUCTION, 1).isPresent());
    }

    @Test
    void overdueScanExcludesAcknowledgedFutureNotRequestedAndResolvedItems() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        service.publish(publish(OTHER_INSTRUCTION, PATIENT, UNIT, 1, false, null, true, "client-2"));
        service.publish(publish("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa", PATIENT, UNIT, 1, true,
                NOW.plusSeconds(60).toString(), true, "client-3"));
        service.publish(publish("22222222-aaaa-aaaa-aaaa-aaaaaaaaaaaa", PATIENT, UNIT, 1, true,
                NOW.toString(), true, "client-4"));
        assertEquals(1, service.overdueFollowUps(UNIT).size());

        asPatient();
        service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"));
        asStaff();
        assertTrue(service.overdueFollowUps(UNIT).isEmpty());

        // A resolved follow-up stays resolved and is no longer surfaced as open.
        store.putFollowUpIfAbsent(ORG, new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW.minusSeconds(1),
                FollowUpState.OPEN, null, null, null));
        store.resolveFollowUp(ORG, INSTRUCTION, 1, STAFF, "owner contacted", NOW);
        assertEquals(FollowUpState.RESOLVED, store.followUps(ORG, INSTRUCTION, 1).orElseThrow().state());
        assertTrue(service.overdueFollowUps(UNIT).isEmpty());
    }

    @Test
    void resolveFollowUpRequiresAStaffReasonAndOwningUnit() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        service.overdueFollowUps(UNIT);
        ApiException blankReason = assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, "  ")));
        assertEquals(422, blankReason.status());
        FollowUp resolved = service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, "owner contacted"));
        assertEquals(FollowUpState.RESOLVED, resolved.state());
        assertEquals(STAFF, resolved.resolvedBy());
        assertEquals(NOW, resolved.resolvedAt());
        assertEquals("owner contacted", resolved.reason());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, "again"))).status());

        asPatient();
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, "x"))).status());
        asStaff();
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(OTHER_INSTRUCTION, 1, "x"))).status());
    }

    // A7 — tenant isolation with identical local identifiers.
    @Test
    void identicalLocalIdentifiersStayIsolatedAcrossTenants() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        store.addMember(OTHER_ORG, STAFF, "lab_collector", true);
        store.assignStaffUnit(OTHER_ORG, STAFF, UNIT);
        store.linkPatient(OTHER_ORG, OTHER_SUBJECT, PATIENT);
        store.addConnection(OTHER_ORG, PATIENT, UNIT, true);
        TenantContext.set(new TenantContext(OTHER_ORG, STAFF, "PATIENT_MESSAGE", CORRELATION));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.facts(INSTRUCTION)).status());
        assertTrue(service.overdueFollowUps(UNIT).isEmpty());
        assertTrue(store.auditEvents(OTHER_ORG).stream()
                .allMatch(event -> OTHER_ORG.equals(event.organizationId())));
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_MESSAGE", CORRELATION));
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
        assertTrue(store.publications(OTHER_ORG, INSTRUCTION, 1).isEmpty());
    }

    // A8 — context, membership, role and connection boundaries.
    @Test
    void missingContextFailsClosedBeforeAnyAudit() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"))).status());
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.facts(INSTRUCTION)).status());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void inactiveMembershipAndWrongRoleAreDenied() {
        asStaff();
        store.addMember(ORG, STAFF, "lab_collector", false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false,
                        "client-1"))).status());
        store.addMember(ORG, STAFF, "lab_collector", true);
        asPatient();
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false,
                        "client-1"))).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.overdueFollowUps(UNIT)).status());
        assertEquals("DENIED", lastAudit().outcome());
    }

    @Test
    void patientActionsRequireAVerifiedChartAndActiveConnection() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        asPatient();
        store.unlinkPatient(ORG, SUBJECT, PATIENT);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"))).status());
        store.linkPatient(ORG, SUBJECT, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-1"))).status());
    }

    @Test
    void otherPatientsInstructionIsConcealed() {
        asStaff();
        store.addConnection(ORG, OTHER_PATIENT, UNIT, true);
        service.publish(publish(OTHER_INSTRUCTION, OTHER_PATIENT, UNIT, 1, true, NOW.toString(),
                true, "client-1"));
        asPatient();
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.facts(OTHER_INSTRUCTION)).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(OTHER_INSTRUCTION, 1, "ack-1"))).status());
    }

    @Test
    void staffCannotPublishToAUnitTheyDoNotOwn() {
        asStaff();
        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(publish(OTHER_INSTRUCTION, PATIENT, OTHER_UNIT, 1, false, null,
                        false, "client-1")));
        assertEquals(403, error.status());
    }

    // A9 — validation before any resource access.
    @Test
    void invalidCommandsAreRejectedWithUnprocessableStatus() {
        asStaff();
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.publish(publish("bad", PATIENT, UNIT, 1, false, null, false, "client-1"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 0, false, null, false,
                        "client-1"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, "bad-date", false,
                        "client-1"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, "bad", UNIT, 1, false, null, false,
                        "client-1"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, null))).status());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    // A10 — the four facts remain distinct in one timeline.
    @Test
    void timelineShowsTheFourFactsAsSeparateEntries() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        asPatient();
        service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-1"));
        service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-1"));
        Timeline timeline = service.facts(INSTRUCTION);
        assertEquals(4, timeline.facts().size());
        assertEquals(FactType.PUBLICATION, timeline.facts().get(0).type());
        assertEquals(FactType.DISPATCH, timeline.facts().get(1).type());
        assertEquals(FactType.FIRST_VIEW, timeline.facts().get(2).type());
        assertEquals(FactType.ACKNOWLEDGMENT, timeline.facts().get(3).type());
        assertThrows(UnsupportedOperationException.class, () -> timeline.facts().clear());
    }

    @Test
    void publicationIsIdempotentAndConflictsOnClientIdReuse() {
        asStaff();
        PublishCommand command = publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, true, "client-1");
        Publication first = service.publish(command);
        assertEquals(first, service.publish(command));
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, false, null, true,
                        "client-1")));
        assertEquals(409, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        assertTrue(store.publications(ORG, INSTRUCTION, 2).isEmpty());
        assertTrue(store.dispatches(ORG, INSTRUCTION, 2).isEmpty());
    }

    @Test
    void storeSeedsAndLookupsNeverEscapeTheirOrganization() {
        asStaff();
        seedInstruction(rev(1, true, NOW.minusSeconds(1)));
        assertTrue(store.instruction(ORG, INSTRUCTION, 1).isPresent());
        assertTrue(store.instruction(OTHER_ORG, INSTRUCTION, 1).isEmpty());
        assertTrue(store.instruction(ORG, OTHER_INSTRUCTION, 1).isEmpty());
        assertTrue(store.latestInstruction(ORG, INSTRUCTION).isPresent());
        assertTrue(store.instructionsForUnit(ORG, UNIT).isEmpty());
        assertTrue(store.instructionsForUnit(ORG, OTHER_UNIT).isEmpty());
        assertEquals(0, store.clientDigestCount(ORG, STAFF, "PUBLISH"));
        assertEquals(1, store.overdueCandidates(ORG, NOW).size());
        assertTrue(store.overdueCandidates(OTHER_ORG, NOW).isEmpty());
        assertTrue(store.followUps(ORG, INSTRUCTION, 1).isEmpty());
        store.appendAudit(new AuditFact(ORG, STAFF, "TEST", INSTRUCTION, "SUCCESS", "P", "S", "C", NOW));
        assertEquals(1, store.auditEvents(ORG).size());
        assertTrue(store.auditEvents(OTHER_ORG).isEmpty());
    }

    @Test
    void retryDispatchDeniesAUnitTheActorDoesNotOwn() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, true, "client-1"));
        TenantContext.set(new TenantContext(ORG, OTHER_STAFF, "PATIENT_MESSAGE", CORRELATION));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "retry-1"))).status());
    }

    @Test
    void overdueQueueDeniesAUnitTheActorDoesNotOwn() {
        asStaff();
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.overdueFollowUps(OTHER_UNIT)).status());
    }

    @Test
    void resolveDeniesAFollowUpOwnedByAnotherUnit() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        service.overdueFollowUps(UNIT);
        TenantContext.set(new TenantContext(ORG, OTHER_STAFF, "PATIENT_MESSAGE", CORRELATION));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.resolveFollowUp(new ResolveCommand(INSTRUCTION, 1, "owner contacted"))).status());
    }

    @Test
    void staffMayReadTheTimelineOfTheirOwnUnit() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        assertEquals(2, service.facts(INSTRUCTION).facts().size());
        TenantContext.set(new TenantContext(ORG, OTHER_STAFF, "PATIENT_MESSAGE", CORRELATION));
        assertEquals(404, assertThrows(ApiException.class, () -> service.facts(INSTRUCTION)).status());
    }

    @Test
    void aRevisionCannotChangeTheVerifiedPatientOrOwningUnit() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        store.assignStaffUnit(ORG, STAFF, OTHER_UNIT);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, OTHER_PATIENT, UNIT, 2, false, null, false,
                        "client-2"))).status());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, OTHER_UNIT, 2, false, null, false,
                        "client-3"))).status());
        assertTrue(store.instruction(ORG, INSTRUCTION, 2).isEmpty());
    }

    @Test
    void firstViewDoesNotClearAnOverdueFollowUp() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        assertEquals(1, service.overdueFollowUps(UNIT).size());
        asPatient();
        service.markFirstView(new ViewCommand(INSTRUCTION, 1, "view-1"));
        asStaff();
        assertEquals(1, service.overdueFollowUps(UNIT).size());
    }

    @Test
    void aPublishedRevisionIsImmutableEvenWithADifferentClientKey() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.publish(new PublishCommand(INSTRUCTION, PATIENT, UNIT, 1,
                        "artifact-altered", false, null, false, "client-2")));
        assertEquals(409, conflict.status());
        assertEquals("REVISION_CONFLICT", conflict.code());
        assertEquals("artifact-1",
                store.instruction(ORG, INSTRUCTION, 1).orElseThrow().contentRef());
        assertEquals(1, store.publications(ORG, INSTRUCTION, 1).size());
    }

    @Test
    void publishingARevisionOlderThanTheLatestIsRejected() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, false, null, false, "client-2"));
        ApiException stale = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false,
                        "client-1")));
        assertEquals(409, stale.status());
        assertEquals("STALE_REVISION", stale.code());
        assertTrue(store.instruction(ORG, INSTRUCTION, 1).isEmpty());
    }

    @Test
    void requestedAcknowledgmentRequiresADueInstant() {
        asStaff();
        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, null, false,
                        "client-1")));
        assertEquals(422, error.status());
        assertEquals("ACKNOWLEDGMENT_DUE_DATE_REQUIRED", error.code());
    }

    @Test
    void publishRequiresAnActivePatientUnitConnection() {
        asStaff();
        store.addConnection(ORG, PATIENT, UNIT, false);
        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false,
                        "client-1")));
        assertEquals(403, error.status());
        assertEquals("FORBIDDEN", error.code());
    }

    @Test
    void anIdempotencyKeyCannotReuseAnotherPatientOrUnit() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        store.linkPatient(ORG, "other-account", OTHER_PATIENT);
        store.addConnection(ORG, OTHER_PATIENT, UNIT, true);
        ApiException changedPatient = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, OTHER_PATIENT, UNIT, 1, false, null,
                        false, "client-1")));
        assertEquals(409, changedPatient.status());
        assertEquals("IDEMPOTENCY_CONFLICT", changedPatient.code());
        store.assignStaffUnit(ORG, STAFF, OTHER_UNIT);
        store.addConnection(ORG, PATIENT, OTHER_UNIT, true);
        ApiException changedUnit = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, OTHER_UNIT, 1, false, null,
                        false, "client-1")));
        assertEquals(409, changedUnit.status());
        assertEquals("IDEMPOTENCY_CONFLICT", changedUnit.code());
    }

    @Test
    void aBusinessDenialDoesNotBurnTheIdempotencyKey() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        store.linkPatient(ORG, "other-account", OTHER_PATIENT);
        store.addConnection(ORG, OTHER_PATIENT, UNIT, true);
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, OTHER_PATIENT, UNIT, 2, false, null,
                        false, "client-2"))).status());
        Publication retry = service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, false, null,
                false, "client-2"));
        assertEquals(2, retry.revision());
    }

    @Test
    void replayingADeniedAcknowledgmentFailsClosedWithAConflict() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, true, NOW.toString(), true, "client-2"));
        asPatient();
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-stale"))).status());
        ApiException replay = assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "ack-stale")));
        assertEquals(409, replay.status());
        assertEquals("STALE_REVISION", replay.code());
    }

    @Test
    void aLostIdempotencyReservationFailsClosedWithoutWritingAFact() {
        RaceStore raceStore = new RaceStore();
        InstructionFactService raceService = new InstructionFactService(raceStore,
                new InstructionFactActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        raceStore.addMember(ORG, STAFF, "lab_collector", true);
        raceStore.assignStaffUnit(ORG, STAFF, UNIT);
        raceStore.addConnection(ORG, PATIENT, UNIT, true);
        asStaff();
        ApiException error = assertThrows(ApiException.class,
                () -> raceService.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null,
                        false, "client-race")));
        assertEquals(409, error.status());
        assertEquals("CONFLICT", error.code());
        assertTrue(raceStore.publications(ORG, INSTRUCTION, 1).isEmpty());
    }

    @Test
    void aRecordedKeyWithoutItsFactFailsClosedWithAConflictInsteadOfAServerError() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.toString(), true, "client-1"));
        store.putClientDigest(ORG, SUBJECT, "ACKNOWLEDGE", "orphan-key", INSTRUCTION + "|1");
        asPatient();
        ApiException error = assertThrows(ApiException.class,
                () -> service.acknowledge(new AcknowledgeCommand(INSTRUCTION, 1, "orphan-key")));
        assertEquals(409, error.status());
        assertEquals("CONFLICT", error.code());
    }

    @Test
    void aRecordedPublishKeyWithoutItsFactFailsClosedWithAConflict() {
        asStaff();
        store.putClientDigest(ORG, STAFF, "PUBLISH", "orphan-publish",
                INSTRUCTION + "|" + PATIENT + "|" + UNIT + "|1|artifact-1|false|null|false");
        ApiException error = assertThrows(ApiException.class,
                () -> service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null,
                        false, "orphan-publish")));
        assertEquals(409, error.status());
        assertEquals("CONFLICT", error.code());
    }

    @Test
    void aRecordedDispatchKeyWithoutItsFactFailsClosedWithAConflict() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, false, null, false, "client-1"));
        store.putClientDigest(ORG, STAFF, "DISPATCH", "orphan-dispatch", INSTRUCTION + "|1");
        ApiException error = assertThrows(ApiException.class,
                () -> service.retryDispatch(new RetryCommand(INSTRUCTION, 1, "orphan-dispatch")));
        assertEquals(409, error.status());
        assertEquals("CONFLICT", error.code());
    }

    @Test
    void supersededRevisionIsNotDetectedOrRoutedAsOverdue() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, false, null, false, "client-2"));
        assertTrue(store.overdueCandidates(ORG, NOW).isEmpty());
        assertTrue(service.overdueFollowUps(UNIT).isEmpty());
    }

    @Test
    void aSupersededRevisionWithAnExistingFollowUpIsNotRoutedAgain() {
        asStaff();
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 1, true, NOW.minusSeconds(1).toString(),
                true, "client-1"));
        assertEquals(1, service.overdueFollowUps(UNIT).size());
        service.publish(publish(INSTRUCTION, PATIENT, UNIT, 2, false, null, false, "client-2"));
        assertTrue(service.overdueFollowUps(UNIT).isEmpty());
    }

    private AuditFact lastAudit() {
        List<AuditFact> events = store.auditEvents(ORG);
        return events.get(events.size() - 1);
    }

    private void asStaff() {
        TenantContext.set(new TenantContext(ORG, STAFF, "PATIENT_MESSAGE", CORRELATION));
    }

    private void asPatient() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGE", CORRELATION));
    }

    private void context(String org, String subject, String purpose) {
        TenantContext.set(new TenantContext(org, subject, purpose, CORRELATION));
    }

    private void seedInstruction(Instruction instruction) {
        store.putInstruction(instruction.organizationId(), instruction);
    }

    /** Synthetic store that loses an idempotency reservation race. */
    private static final class RaceStore extends InMemoryInstructionFactStore {
        @Override
        public boolean putClientDigest(String organizationId, String subject, String action,
                                       String clientId, String digest) {
            return false;
        }
    }

    private static Instruction rev(int revision, boolean requiresAck, Instant dueAt) {
        return new Instruction(INSTRUCTION, ORG, PATIENT, UNIT, revision, "artifact-" + revision,
                requiresAck, dueAt);
    }

    private static PublishCommand publish(String instructionId, String patientId, String unitId,
                                          int revision, boolean requiresAck, String dueAt, boolean notify,
                                          String clientId) {
        return new PublishCommand(instructionId, patientId, unitId, revision,
                "artifact-" + revision, requiresAck, dueAt, notify, clientId);
    }
}
