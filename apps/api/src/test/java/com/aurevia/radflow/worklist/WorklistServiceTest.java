package com.aurevia.radflow.worklist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.radflow.worklist.WorklistCommands.LinkStudyCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ReceiveCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ReportCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ScheduleCommand;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistEventType;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistStatus;

/** RAD-01 performing-site worklist kernel: forward-only status, site scope and guarded study link. */
class WorklistServiceTest {

    private static final String ORG = "org-radflow";
    private static final String OTHER_ORG = "org-other";
    private static final String SITE_A = "site-a";
    private static final String SITE_B = "site-b";
    private static final String ORDER = "order-radflow";
    private static final String PATIENT = "patient-radflow";
    private static final String OTHER_PATIENT = "patient-other";
    private static final String ACC = "ACC-3001";
    private static final String OTHER_ACC = "ACC-9999";
    private static final String ISSUER = "ISSUER-RADFLOW";
    private static final String UID = "1.2.840.113619.2.55.3.3001";
    private static final String WORKSTATION = "pacs-workstation-radflow";
    private static final String ACTOR = "tech-radflow";
    private static final String REFERRER = "referrer-radflow";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant SCHEDULED = AT.plus(Duration.ofDays(1));

    private static final Set<String> STAFF = Set.of("radiology_staff");
    private static final Set<String> RECEPTION = Set.of("reception");
    private static final Set<String> INTERPRETER = Set.of("radiology_interpreter");
    private static final Set<String> NO_ROLE = Set.of("finance");

    private WorklistService service;

    @BeforeEach
    void setUp() {
        service = new WorklistService(Clock.fixed(AT, ZoneOffset.UTC));
    }

    private ReceiveCommand receive(String org, String orderId, String site, Set<String> roles) {
        return new ReceiveCommand(org, ACTOR, roles, orderId, site, PATIENT, "CT-CHEST",
                Urgency.ROUTINE, "rule out pulmonary embolism", REFERRER);
    }

    private WorklistOrder received() {
        return service.receive(receive(ORG, ORDER, SITE_A, STAFF));
    }

    private WorklistOrder scheduled() {
        WorklistOrder received = received();
        return service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, SITE_A,
                received.version(), SCHEDULED, Modality.CT, ACC, ISSUER));
    }

    private WorklistOrder performed() {
        WorklistOrder scheduled = scheduled();
        return service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF, ORDER, SITE_A,
                scheduled.version(), UID, PATIENT, ACC, WORKSTATION));
    }

    private static int status(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    private static String code(Runnable action) {
        return assertThrows(ApiException.class, action::run).code();
    }

    @Test
    void statuses_move_forward_only_and_record_the_full_history() {
        WorklistOrder received = received();
        assertEquals(WorklistStatus.RECEIVED, received.status());
        assertEquals(1, received.version());
        assertNull(received.scheduledAt());
        assertNull(received.accession());
        assertNull(received.modality());

        WorklistOrder scheduled = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, received.version(), SCHEDULED, Modality.MRI, ACC, ISSUER));
        assertEquals(WorklistStatus.SCHEDULED, scheduled.status());
        assertEquals(2, scheduled.version());
        assertEquals(SCHEDULED, scheduled.scheduledAt());
        assertEquals(Modality.MRI, scheduled.modality());
        assertEquals(ACC, scheduled.accession());
        assertEquals(ISSUER, scheduled.accessionIssuer());

        WorklistOrder performed = service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, scheduled.version(), UID, PATIENT, ACC, WORKSTATION));
        assertEquals(WorklistStatus.PERFORMED, performed.status());
        assertEquals(3, performed.version());
        assertEquals(UID, performed.studyInstanceUid());
        assertEquals(PATIENT, performed.verifiedPatientId());
        assertEquals(ACC, performed.verifiedAccession());
        assertEquals(AT, performed.performedAt());

        WorklistOrder reported = service.report(new ReportCommand(ORG, ACTOR, INTERPRETER, ORDER,
                SITE_A, performed.version()));
        assertEquals(WorklistStatus.REPORTED, reported.status());
        assertEquals(4, reported.version());
        assertEquals(AT, reported.reportedAt());

        List<WorklistOrder> history = service.history(ORG, ORDER);
        assertEquals(List.of(WorklistStatus.RECEIVED, WorklistStatus.SCHEDULED,
                WorklistStatus.PERFORMED, WorklistStatus.REPORTED),
                history.stream().map(WorklistOrder::status).toList());
        assertEquals(List.of(1, 2, 3, 4), history.stream().map(WorklistOrder::version).toList());
        assertEquals(ORDER, service.read(ORG, ORDER).orderId());

        // No skipped transition is possible from RECEIVED on a fresh order.
        WorklistService fresh = new WorklistService(Clock.fixed(AT, ZoneOffset.UTC));
        fresh.receive(receive(ORG, ORDER, SITE_A, STAFF));
        assertEquals("INVALID_TRANSITION", code(() -> fresh.linkStudy(new LinkStudyCommand(
                ORG, ACTOR, STAFF, ORDER, SITE_A, 1, UID, PATIENT, ACC, WORKSTATION))));
        assertEquals("INVALID_TRANSITION", code(() -> fresh.report(new ReportCommand(
                ORG, ACTOR, INTERPRETER, ORDER, SITE_A, 1))));
        // A repeated receive is a duplicate, never a status move.
        assertEquals("DUPLICATE_ORDER", code(() -> received()));
    }

    @Test
    void status_transition_table_allows_only_the_next_state() {
        assertTrue(WorklistStatus.RECEIVED.canTransitionTo(WorklistStatus.SCHEDULED));
        assertFalse(WorklistStatus.RECEIVED.canTransitionTo(WorklistStatus.PERFORMED));
        assertTrue(WorklistStatus.SCHEDULED.canTransitionTo(WorklistStatus.PERFORMED));
        assertFalse(WorklistStatus.SCHEDULED.canTransitionTo(WorklistStatus.RECEIVED));
        assertTrue(WorklistStatus.PERFORMED.canTransitionTo(WorklistStatus.REPORTED));
        assertFalse(WorklistStatus.PERFORMED.canTransitionTo(WorklistStatus.SCHEDULED));
        assertFalse(WorklistStatus.REPORTED.canTransitionTo(WorklistStatus.REPORTED));
        assertFalse(WorklistStatus.REPORTED.canTransitionTo(WorklistStatus.PERFORMED));
        assertEquals("received", WorklistStatus.RECEIVED.wire());
        assertEquals("scheduled", WorklistStatus.SCHEDULED.wire());
        assertEquals("performed", WorklistStatus.PERFORMED.wire());
        assertEquals("reported", WorklistStatus.REPORTED.wire());
    }

    @Test
    void another_site_cannot_see_or_act_on_the_order() {
        received();
        service.receive(receive(ORG, "order-b", SITE_B, STAFF));

        List<WorklistEntry> siteA = service.worklist(ORG, SITE_A, null);
        assertEquals(1, siteA.size());
        assertEquals(SITE_A, siteA.get(0).performingSiteId());
        assertEquals(ORDER, siteA.get(0).orderId());
        List<WorklistEntry> siteB = service.worklist(ORG, SITE_B, null);
        assertEquals(1, siteB.size());
        assertEquals("order-b", siteB.get(0).orderId());

        // A different tenant sees neither site's order.
        assertTrue(service.worklist(OTHER_ORG, SITE_A, null).isEmpty());
        assertTrue(service.worklist(OTHER_ORG, SITE_B, null).isEmpty());
        assertEquals(404, status(() -> service.read(OTHER_ORG, ORDER)));

        // Facts are scoped to the performing site as well as the tenant.
        assertEquals(1, service.eventsForSite(ORG, SITE_A).size());
        assertEquals(1, service.eventsForSite(ORG, SITE_B).size());
        assertEquals("order-b", service.eventsForSite(ORG, SITE_B).get(0).orderId());
        assertTrue(service.eventsForSite(OTHER_ORG, SITE_A).isEmpty());

        // The HTTP detail entry point also refuses a cross-site read in the same tenant.
        assertEquals(403, status(() -> service.readForSite(ORG, SITE_B, ORDER)));
        assertEquals(ORDER, service.readForSite(ORG, SITE_A, ORDER).orderId());

        // Supplying another site's identifier cannot advance this order.
        assertEquals(403, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_B, 1, SCHEDULED, Modality.CT, ACC, ISSUER))));
        assertEquals(WorklistStatus.RECEIVED, service.read(ORG, ORDER).status());
    }

    @Test
    void linking_a_study_for_the_wrong_patient_is_refused() {
        WorklistOrder scheduled = scheduled();

        assertEquals("LABEL_MISMATCH", code(() -> service.linkStudy(new LinkStudyCommand(
                ORG, ACTOR, STAFF, ORDER, SITE_A, scheduled.version(), UID, OTHER_PATIENT,
                ACC, WORKSTATION))));
        assertEquals("LABEL_MISMATCH", code(() -> service.linkStudy(new LinkStudyCommand(
                ORG, ACTOR, STAFF, ORDER, SITE_A, scheduled.version(), UID, PATIENT,
                OTHER_ACC, WORKSTATION))));

        WorklistOrder unchanged = service.read(ORG, ORDER);
        assertEquals(WorklistStatus.SCHEDULED, unchanged.status());
        assertEquals(2, unchanged.version());
        assertNull(unchanged.studyInstanceUid());
        assertEquals(2, service.events(ORG).stream()
                .filter(event -> event.type() == WorklistEventType.STUDY_LINK_REFUSED).count());
        assertTrue(service.events(ORG).stream()
                .noneMatch(event -> event.type() == WorklistEventType.HANDOFF_RECORDED));

        WorklistEvent refusal = service.events(ORG).stream()
                .filter(event -> event.type() == WorklistEventType.STUDY_LINK_REFUSED)
                .findFirst().orElseThrow();
        assertEquals(ORG, refusal.organizationId());
        assertEquals(UID, refusal.destination());
        assertEquals(ACTOR, refusal.actor());
        assertEquals(2, refusal.orderVersion());
        assertEquals(SITE_A, refusal.performingSiteId());

        WorklistOrder performed = service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, scheduled.version(), UID, PATIENT, ACC, WORKSTATION));
        assertEquals(WorklistStatus.PERFORMED, performed.status());
        List<WorklistEvent> handoffs = service.events(ORG).stream()
                .filter(event -> event.type() == WorklistEventType.HANDOFF_RECORDED).toList();
        assertEquals(1, handoffs.size());
        assertEquals(REFERRER, handoffs.get(0).destination());
        assertEquals(SITE_A, handoffs.get(0).performingSiteId());
        assertEquals(PATIENT, handoffs.get(0).patientId());
        assertEquals(3, handoffs.get(0).orderVersion());
        assertEquals("handoff_recorded", WorklistEventType.HANDOFF_RECORDED.wire());
    }

    @Test
    void scheduling_requires_a_time_an_approved_modality_and_an_accession() {
        WorklistOrder received = received();
        assertEquals(422, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, received.version(), null, Modality.CT, ACC, ISSUER))));
        assertEquals(422, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, received.version(), SCHEDULED, null, ACC, ISSUER))));
        assertEquals(422, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, received.version(), SCHEDULED, Modality.CT, " ", ISSUER))));
        assertEquals(422, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, received.version(), SCHEDULED, Modality.CT, ACC, " "))));
        assertEquals(403, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, NO_ROLE,
                ORDER, SITE_A, received.version(), SCHEDULED, Modality.CT, ACC, ISSUER))));
        assertEquals(412, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, 99, SCHEDULED, Modality.CT, ACC, ISSUER))));
        assertEquals(404, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                "missing", SITE_A, 1, SCHEDULED, Modality.CT, ACC, ISSUER))));
    }

    @Test
    void receive_validates_identity_and_rejects_duplicates_and_wrong_roles() {
        assertEquals(422, status(() -> service.receive(receive(" ", ORDER, SITE_A, STAFF))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, " ", STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, " ",
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                " ", PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, " ", "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, " ", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, " ", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", " "))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, "CT", null, "question", REFERRER))));
        assertEquals(403, status(() -> service.receive(receive(ORG, ORDER, SITE_A, NO_ROLE))));

        // Reception and radiology staff may both receive; the wrong role may not.
        assertNotNull(service.receive(receive(ORG, "order-reception", SITE_A, RECEPTION)));
        assertNotNull(service.receive(receive(ORG, "order-staff", SITE_A, STAFF)));
        assertEquals(422, status(() -> service.receive(receive(" ", "order-blank", " ", STAFF))));
    }

    @Test
    void worklist_filters_by_status_and_returns_entries_not_orders() {
        WorklistOrder received = received();
        service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, SITE_A, received.version(),
                SCHEDULED, Modality.CT, ACC, ISSUER));

        assertTrue(service.worklist(ORG, SITE_A, WorklistStatus.RECEIVED).isEmpty());
        assertEquals(1, service.worklist(ORG, SITE_A, WorklistStatus.SCHEDULED).size());
        assertTrue(service.worklist(ORG, SITE_A, WorklistStatus.PERFORMED).isEmpty());
        assertTrue(service.worklist(ORG, SITE_A, WorklistStatus.REPORTED).isEmpty());
        assertEquals(1, service.worklist(ORG, SITE_A, null).size());
        assertTrue(service.worklist(ORG, "site-empty", null).isEmpty());
        assertTrue(service.worklist(ORG, SITE_A, null).isEmpty() == false);

        WorklistEntry entry = service.worklist(ORG, SITE_A, WorklistStatus.SCHEDULED).get(0);
        assertEquals(ORDER, entry.orderId());
        assertEquals(SITE_A, entry.performingSiteId());
        assertEquals(PATIENT, entry.patientId());
        assertEquals(Urgency.ROUTINE, entry.urgency());
        assertEquals(Modality.CT, entry.modality());
        assertEquals(ACC, entry.accession());
        assertEquals(ISSUER, entry.accessionIssuer());
        assertEquals(SCHEDULED, entry.scheduledAt());
        assertNull(entry.studyInstanceUid());

        assertEquals(422, status(() -> service.worklist(" ", SITE_A, null)));
        assertEquals(422, status(() -> service.worklist(ORG, " ", null)));
    }

    @Test
    void report_requires_a_linked_study_and_an_authorized_role() {
        WorklistOrder performed = performed();
        assertEquals(403, status(() -> service.report(new ReportCommand(ORG, ACTOR, NO_ROLE,
                ORDER, SITE_A, performed.version()))));
        assertEquals(412, status(() -> service.report(new ReportCommand(ORG, ACTOR, INTERPRETER,
                ORDER, SITE_A, 99))));
        assertEquals(404, status(() -> service.report(new ReportCommand(ORG, ACTOR, INTERPRETER,
                "missing", SITE_A, 1))));
        assertEquals(WorklistStatus.REPORTED, service.report(new ReportCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, performed.version())).status());
        assertTrue(service.events(OTHER_ORG).isEmpty());

        WorklistEvent reported = service.events(ORG).stream()
                .filter(event -> event.type() == WorklistEventType.ORDER_REPORTED)
                .findFirst().orElseThrow();
        assertEquals(ORDER, reported.orderId());
        assertEquals(4, reported.orderVersion());
        assertEquals("order_reported", reported.type().wire());
        assertEquals("study_link_refused", WorklistEventType.STUDY_LINK_REFUSED.wire());
    }

    @Test
    void commands_freeze_their_role_sets_and_records_are_immutable_carriers() {
        Set<String> mutable = new HashSet<>(STAFF);
        ReceiveCommand command = new ReceiveCommand(ORG, ACTOR, mutable, ORDER, SITE_A, PATIENT,
                "CT", Urgency.ROUTINE, "question", REFERRER);
        mutable.add("tenant_admin");
        assertEquals(STAFF, command.roles());
        assertThrows(UnsupportedOperationException.class, () -> command.roles().add("x"));

        WorklistOrder order = service.receive(command);
        assertEquals(ORG, order.organizationId());
        assertEquals(ORDER, order.orderId());
        assertEquals(SITE_A, order.performingSiteId());
        assertEquals(PATIENT, order.patientId());
        assertEquals("CT", order.orderCode());
        assertEquals("question", order.clinicalQuestion());
        assertEquals(REFERRER, order.referrer());
        assertEquals(1, order.version());

        List<WorklistOrder> history = service.history(ORG, ORDER);
        assertThrows(UnsupportedOperationException.class, () -> history.add(order));
        assertTrue(new ArrayList<>(history).size() == 1);
    }

    @Test
    void null_boundary_values_are_rejected() {
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(null, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, null, STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, null,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                null, PATIENT, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, null, "CT", Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, null, Urgency.ROUTINE, "question", REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, null, REFERRER))));
        assertEquals(422, status(() -> service.receive(new ReceiveCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, PATIENT, "CT", Urgency.ROUTINE, "question", null))));

        assertEquals(422, status(() -> service.worklist(null, SITE_A, null)));
        assertEquals(422, status(() -> service.worklist(ORG, null, null)));
        assertEquals(422, status(() -> service.eventsForSite(null, SITE_A)));
        assertEquals(422, status(() -> service.eventsForSite(ORG, null)));
        assertEquals(422, status(() -> service.readForSite(null, SITE_A, ORDER)));
        assertEquals(422, status(() -> service.readForSite(ORG, null, ORDER)));

        WorklistOrder received = received();
        assertEquals(422, status(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, received.version(), SCHEDULED, Modality.CT, null, ISSUER))));
        WorklistOrder scheduled = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER,
                SITE_A, received.version(), SCHEDULED, Modality.CT, ACC, ISSUER));
        assertEquals(422, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, scheduled.version(), null, PATIENT, ACC, WORKSTATION))));
        assertEquals(422, status(() -> service.report(new ReportCommand(null, ACTOR, STAFF,
                ORDER, SITE_A, scheduled.version()))));
    }

    @Test
    void link_and_report_validate_their_input_fields() {
        WorklistOrder scheduled = scheduled();
        assertEquals(422, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, scheduled.version(), " ", PATIENT, ACC, WORKSTATION))));
        assertEquals(422, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, scheduled.version(), UID, " ", ACC, WORKSTATION))));
        assertEquals(422, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, scheduled.version(), UID, PATIENT, " ", WORKSTATION))));
        assertEquals(422, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, scheduled.version(), UID, PATIENT, ACC, " "))));
        assertEquals(403, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, NO_ROLE,
                ORDER, SITE_A, scheduled.version(), UID, PATIENT, ACC, WORKSTATION))));
        assertEquals(412, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                ORDER, SITE_A, 99, UID, PATIENT, ACC, WORKSTATION))));
        assertEquals(404, status(() -> service.linkStudy(new LinkStudyCommand(ORG, ACTOR, STAFF,
                "missing", SITE_A, 1, UID, PATIENT, ACC, WORKSTATION))));
        assertEquals(WorklistStatus.SCHEDULED, service.read(ORG, ORDER).status());
    }
}
