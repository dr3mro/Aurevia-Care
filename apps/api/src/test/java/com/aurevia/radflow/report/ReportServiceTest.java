package com.aurevia.radflow.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radflow.report.AddendumReceipt;
import com.aurevia.radflow.report.ReportCommands.AcknowledgeCommand;
import com.aurevia.radflow.report.ReportCommands.AddendumCommand;
import com.aurevia.radflow.report.ReportCommands.PublishCommand;
import com.aurevia.radflow.report.ReportRules;
import com.aurevia.radflow.report.ReportService;
import com.aurevia.radflow.report.ReportTypes.AcknowledgmentState;
import com.aurevia.radflow.report.ReportTypes.ReportEventType;

/** RAD-01 report delivery kernel: signed delivery, addendum acknowledgment and overdue aging. */
class ReportServiceTest {

    private static final String ORG = "org-report";
    private static final String OTHER_ORG = "org-other";
    private static final String ORDER = "order-report";
    private static final String PATIENT = "patient-report";
    private static final String REFERRER = "referrer-report";
    private static final String OTHER_REFERRER = "referrer-other";
    private static final String SIGNER = "interpreter-report";
    private static final String UID = "1.2.840.113619.2.55.3.report";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);

    private static final Set<String> INTERPRETER = Set.of("radiology_interpreter");
    private static final Set<String> STAFF = Set.of("radiology_staff");
    private static final Set<String> NO_ROLE = Set.of("finance");

    /** Deterministic clock whose instant can be advanced to age an unacknowledged addendum. */
    private static final class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private TestClock clock;
    private ReportService service;

    @BeforeEach
    void setUp() {
        clock = new TestClock(AT);
        service = new ReportService(clock, WINDOW);
    }

    private static Interpretation signed(InterpretationKind kind, int version, Integer supersedes,
                                          String patientId) {
        return new Interpretation(ORG, ORDER, version, supersedes, kind, patientId, UID,
                "CT chest", "prior CT", "no pulmonary embolism", "unremarkable", "none",
                SIGNER, "signed by interpreter", AT, "template-1", null);
    }

    private static PublishCommand publish(Set<String> roles) {
        return new PublishCommand(ORG, SIGNER, roles, ORDER, REFERRER, signed(InterpretationKind.INITIAL, 1, null, PATIENT));
    }

    private AddendumCommand addendum(Set<String> roles) {
        return new AddendumCommand(ORG, SIGNER, roles, ORDER,
                signed(InterpretationKind.ADDENDUM, 2, 1, PATIENT));
    }

    private static int status(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    private static String code(Runnable action) {
        return assertThrows(ApiException.class, action::run).code();
    }

    @Test
    void signed_interpretation_reaches_the_referrer_and_stays_unsigned_free() {
        ReportDelivery delivery = service.publish(publish(INTERPRETER));

        assertEquals(ORG, delivery.organizationId());
        assertEquals(ORDER, delivery.orderId());
        assertEquals(PATIENT, delivery.patientId());
        assertEquals(REFERRER, delivery.referrer());
        assertEquals(1, delivery.version());
        assertTrue(delivery.addenda().isEmpty());
        assertFalse(delivery.hasOpenAddendum());
        assertEquals("CT chest", delivery.current().technique());
        assertEquals("prior CT", delivery.current().comparison());
        assertEquals("no pulmonary embolism", delivery.current().findings());
        assertEquals("unremarkable", delivery.current().impression());
        assertEquals(ORDER, service.read(ORG, ORDER).orderId());

        List<ReportEvent> delivered = service.events(ORG).stream()
                .filter(event -> event.type() == ReportEventType.INTERPRETATION_DELIVERED).toList();
        assertEquals(1, delivered.size());
        assertEquals(REFERRER, delivered.get(0).destination());
        assertEquals(REFERRER, delivered.get(0).referrer());
        assertEquals(PATIENT, delivered.get(0).patientId());
        assertEquals(SIGNER, delivered.get(0).actor());
        assertEquals(AT, delivered.get(0).occurredAt());

        List<ReportEvent> notified = service.eventsForReferrer(ORG, REFERRER).stream()
                .filter(event -> event.type() == ReportEventType.REFERRER_NOTIFIED).toList();
        assertEquals(1, notified.size());
        assertEquals(REFERRER, notified.get(0).destination());
        assertTrue(service.eventsForReferrer(ORG, OTHER_REFERRER).isEmpty());
        assertTrue(service.events(OTHER_ORG).isEmpty());
    }

    @Test
    void unsigned_reports_are_never_sent() {
        Interpretation unattested = new Interpretation(ORG, ORDER, 1, null, InterpretationKind.INITIAL,
                PATIENT, UID, "CT", "prior", "findings", "impression", "none", SIGNER, " ", AT,
                "template-1", null);
        Interpretation undated = new Interpretation(ORG, ORDER, 1, null, InterpretationKind.INITIAL,
                PATIENT, UID, "CT", "prior", "findings", "impression", "none", SIGNER, "attested", null,
                "template-1", null);

        assertEquals("UNSIGNED_REPORT", code(() -> service.publish(new PublishCommand(ORG, SIGNER,
                INTERPRETER, ORDER, REFERRER, unattested))));
        assertEquals("UNSIGNED_REPORT", code(() -> service.publish(new PublishCommand(ORG, SIGNER,
                INTERPRETER, ORDER, REFERRER, undated))));
        assertEquals("UNSIGNED_REPORT", code(() -> service.publish(new PublishCommand(ORG, SIGNER,
                INTERPRETER, ORDER, REFERRER, null))));

        assertEquals(3, service.events(ORG).stream()
                .filter(event -> event.type() == ReportEventType.UNSIGNED_REPORT_REFUSED).count());
        assertTrue(service.events(ORG).stream().noneMatch(event ->
                event.type() == ReportEventType.INTERPRETATION_DELIVERED
                        || event.type() == ReportEventType.REFERRER_NOTIFIED));
        assertEquals(404, status(() -> service.read(ORG, ORDER)));
    }

    @Test
    void addendum_creates_a_revision_notifies_the_referrer_and_stays_open_until_acknowledged() {
        service.publish(publish(INTERPRETER));

        ReportDelivery added = service.addendum(addendum(INTERPRETER));
        assertEquals(2, added.version());
        assertEquals(2, added.revisions().size());
        assertEquals(List.of(1, 2), added.revisions().stream().map(Interpretation::version).toList());
        assertEquals(InterpretationKind.ADDENDUM, added.current().kind());
        assertEquals(Integer.valueOf(1), added.current().supersedesVersion());
        assertTrue(added.hasOpenAddendum());
        assertEquals(1, added.addenda().size());

        AddendumReceipt receipt = added.addenda().get(0);
        assertEquals(2, receipt.version());
        assertEquals(REFERRER, receipt.referrer());
        assertEquals(AT, receipt.notifiedAt());
        assertEquals(AcknowledgmentState.OPEN, receipt.state());
        assertTrue(receipt.isOpen());

        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == ReportEventType.ADDENDUM_DELIVERED).count());
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == ReportEventType.REFERRER_NOTIFIED
                        && event.version() == 2).count());
        assertEquals(1, service.openAddenda(ORG, REFERRER).size());
        assertTrue(service.openAddenda(ORG, OTHER_REFERRER).isEmpty());

        ReportDelivery acknowledged = service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, ORDER, 2));
        assertFalse(acknowledged.hasOpenAddendum());
        assertEquals(AcknowledgmentState.ACKNOWLEDGED, acknowledged.addenda().get(0).state());
        assertEquals(REFERRER, acknowledged.addenda().get(0).acknowledgedBy());
        assertEquals(AT, acknowledged.addenda().get(0).acknowledgedAt());
        assertEquals(2, acknowledged.revisions().size());
        assertTrue(service.openAddenda(ORG, REFERRER).isEmpty());
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == ReportEventType.ADDENDUM_ACKNOWLEDGED).count());
    }

    @Test
    void acknowledgment_is_bound_to_the_verified_referrer_and_the_exact_revision() {
        service.publish(publish(INTERPRETER));
        service.addendum(addendum(INTERPRETER));

        assertEquals(403, status(() -> service.acknowledge(
                new AcknowledgeCommand(ORG, OTHER_REFERRER, ORDER, 2))));
        assertEquals(403, status(() -> service.acknowledge(
                new AcknowledgeCommand(ORG, SIGNER, ORDER, 2))));
        assertEquals(404, status(() -> service.acknowledge(
                new AcknowledgeCommand(ORG, REFERRER, ORDER, 99))));

        service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, ORDER, 2));
        assertEquals(409, status(() -> service.acknowledge(
                new AcknowledgeCommand(ORG, REFERRER, ORDER, 2))));
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == ReportEventType.ADDENDUM_ACKNOWLEDGED).count());
    }

    @Test
    void unacknowledged_addenda_age_into_the_overdue_list() {
        service.publish(publish(INTERPRETER));
        service.addendum(addendum(INTERPRETER));

        assertTrue(service.overdueAddenda(ORG, REFERRER).isEmpty());
        clock.advance(WINDOW);
        assertTrue(service.overdueAddenda(ORG, REFERRER).isEmpty());
        clock.advance(java.time.Duration.ofNanos(1));
        assertEquals(1, service.overdueAddenda(ORG, REFERRER).size());
        assertEquals(2, service.overdueAddenda(ORG, REFERRER).get(0).version());
        assertTrue(service.overdueAddenda(ORG, OTHER_REFERRER).isEmpty());
        assertTrue(service.overdueAddenda(OTHER_ORG, REFERRER).isEmpty());
        assertEquals(422, status(() -> service.overdueAddenda(" ", REFERRER)));
        assertEquals(422, status(() -> service.overdueAddenda(ORG, " ")));

        service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, ORDER, 2));
        assertTrue(service.overdueAddenda(ORG, REFERRER).isEmpty());
    }

    @Test
    void receipt_overdue_rule_short_circuits_for_acknowledged_receipts() {
        AddendumReceipt acknowledged = new AddendumReceipt(ORG, ORDER, 2, REFERRER, AT,
                AcknowledgmentState.ACKNOWLEDGED, AT.plus(WINDOW), REFERRER);
        assertFalse(acknowledged.isOpen());
        assertFalse(acknowledged.isOverdue(AT.plus(WINDOW).plus(Duration.ofDays(1)), WINDOW));

        AddendumReceipt open = new AddendumReceipt(ORG, ORDER, 2, REFERRER, AT,
                AcknowledgmentState.OPEN, null, null);
        assertTrue(open.isOpen());
        assertFalse(open.isOverdue(AT, WINDOW));
        assertFalse(open.isOverdue(AT.plus(WINDOW), WINDOW));
        assertTrue(open.isOverdue(AT.plus(WINDOW).plusNanos(1), WINDOW));
    }

    @Test
    void another_tenant_and_another_referrer_cannot_read_the_report() {
        service.publish(publish(INTERPRETER));
        service.addendum(addendum(INTERPRETER));

        assertEquals(404, status(() -> service.read(OTHER_ORG, ORDER)));
        assertEquals(404, status(() -> service.read(ORG, "missing")));
        assertEquals(403, status(() -> service.readForReferrer(ORG, OTHER_REFERRER, ORDER)));
        assertEquals(ORDER, service.readForReferrer(ORG, REFERRER, ORDER).orderId());
        assertEquals(2, service.revisionsForReferrer(ORG, REFERRER, ORDER).size());
        assertEquals(403, status(() -> service.revisionsForReferrer(ORG, OTHER_REFERRER, ORDER)));
        assertEquals(404, status(() -> service.revisionsForReferrer(OTHER_ORG, REFERRER, ORDER)));
    }

    @Test
    void publish_validates_identity_roles_and_duplicates() {
        assertEquals(422, status(() -> service.publish(new PublishCommand(" ", SIGNER, INTERPRETER,
                ORDER, REFERRER, signed(InterpretationKind.INITIAL, 1, null, PATIENT)))));
        assertEquals(422, status(() -> service.publish(new PublishCommand(ORG, " ", INTERPRETER,
                ORDER, REFERRER, signed(InterpretationKind.INITIAL, 1, null, PATIENT)))));
        assertEquals(422, status(() -> service.publish(new PublishCommand(ORG, SIGNER, INTERPRETER,
                " ", REFERRER, signed(InterpretationKind.INITIAL, 1, null, PATIENT)))));
        assertEquals(422, status(() -> service.publish(new PublishCommand(ORG, SIGNER, INTERPRETER,
                ORDER, " ", signed(InterpretationKind.INITIAL, 1, null, PATIENT)))));
        assertEquals(403, status(() -> service.publish(publish(NO_ROLE))));
        assertEquals(403, status(() -> service.publish(publish(STAFF))));
        assertNotNull(service.publish(publish(INTERPRETER)));

        assertEquals(409, status(() -> service.publish(publish(INTERPRETER))));
    }

    @Test
    void publish_rejects_a_wrong_interpretation_identity_and_kind() {
        Interpretation wrongOrg = new Interpretation(OTHER_ORG, ORDER, 1, null, InterpretationKind.INITIAL,
                PATIENT, UID, "CT", "prior", "findings", "impression", "none", SIGNER, "attested", AT,
                "template-1", null);
        Interpretation wrongOrder = new Interpretation(ORG, "other-order", 1, null,
                InterpretationKind.INITIAL, PATIENT, UID, "CT", "prior", "findings", "impression", "none",
                SIGNER, "attested", AT, "template-1", null);
        assertEquals("REPORT_IDENTITY_MISMATCH", code(() -> service.publish(new PublishCommand(ORG,
                SIGNER, INTERPRETER, ORDER, REFERRER, wrongOrg))));
        assertEquals("REPORT_IDENTITY_MISMATCH", code(() -> service.publish(new PublishCommand(ORG,
                SIGNER, INTERPRETER, ORDER, REFERRER, wrongOrder))));
        assertEquals("INVALID_REPORT_KIND", code(() -> service.publish(new PublishCommand(ORG,
                SIGNER, INTERPRETER, ORDER, REFERRER, signed(InterpretationKind.ADDENDUM, 1, null, PATIENT)))));
    }

    @Test
    void addendum_validates_its_revision_patient_role_and_identity() {
        assertEquals(404, status(() -> service.addendum(addendum(INTERPRETER))));
        service.publish(publish(INTERPRETER));

        assertEquals("UNSIGNED_REPORT", code(() -> service.addendum(new AddendumCommand(ORG, SIGNER,
                INTERPRETER, ORDER, null))));
        assertEquals(403, status(() -> service.addendum(addendum(NO_ROLE))));
        assertEquals("INVALID_REPORT_KIND", code(() -> service.addendum(new AddendumCommand(ORG, SIGNER,
                INTERPRETER, ORDER, signed(InterpretationKind.INITIAL, 2, 1, PATIENT)))));
        assertEquals("INVALID_SUPERSEDES", code(() -> service.addendum(new AddendumCommand(ORG, SIGNER,
                INTERPRETER, ORDER, signed(InterpretationKind.ADDENDUM, 2, null, PATIENT)))));
        assertEquals("INVALID_SUPERSEDES", code(() -> service.addendum(new AddendumCommand(ORG, SIGNER,
                INTERPRETER, ORDER, signed(InterpretationKind.ADDENDUM, 100, 99, PATIENT)))));
        assertEquals("INVALID_SUPERSEDES", code(() -> service.addendum(new AddendumCommand(ORG, SIGNER,
                INTERPRETER, ORDER, signed(InterpretationKind.ADDENDUM, 99, 1, PATIENT)))));
        assertEquals("PATIENT_MISMATCH", code(() -> service.addendum(new AddendumCommand(ORG, SIGNER,
                INTERPRETER, ORDER, signed(InterpretationKind.ADDENDUM, 2, 1, "other-patient")))));
        assertEquals("REPORT_IDENTITY_MISMATCH", code(() -> service.addendum(new AddendumCommand(ORG,
                SIGNER, INTERPRETER, ORDER, new Interpretation(OTHER_ORG, ORDER, 2, 1,
                        InterpretationKind.ADDENDUM, PATIENT, UID, "CT", "prior", "findings",
                        "impression", "none", SIGNER, "attested", AT, "template-1", "reason")))));
        assertEquals("REPORT_IDENTITY_MISMATCH", code(() -> service.addendum(new AddendumCommand(ORG,
                SIGNER, INTERPRETER, ORDER, new Interpretation(ORG, "other-order", 2, 1,
                        InterpretationKind.ADDENDUM, PATIENT, UID, "CT", "prior", "findings",
                        "impression", "none", SIGNER, "attested", AT, "template-1", "reason")))));
        assertEquals(422, status(() -> service.addendum(new AddendumCommand(" ", SIGNER, INTERPRETER,
                ORDER, signed(InterpretationKind.ADDENDUM, 2, 1, PATIENT)))));
        assertEquals(422, status(() -> service.addendum(new AddendumCommand(ORG, " ", INTERPRETER,
                ORDER, signed(InterpretationKind.ADDENDUM, 2, 1, PATIENT)))));
        assertEquals(422, status(() -> service.addendum(new AddendumCommand(ORG, SIGNER, INTERPRETER,
                " ", signed(InterpretationKind.ADDENDUM, 2, 1, PATIENT)))));

        assertEquals(1, service.revisionsForReferrer(ORG, REFERRER, ORDER).size());
    }

    @Test
    void acknowledging_one_addendum_leaves_the_other_receipt_untouched() {
        service.publish(publish(INTERPRETER));
        service.addendum(addendum(INTERPRETER));
        service.addendum(new AddendumCommand(ORG, SIGNER, INTERPRETER, ORDER,
                signed(InterpretationKind.ADDENDUM, 3, 2, PATIENT)));

        ReportDelivery updated = service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, ORDER, 2));
        assertEquals(List.of(AcknowledgmentState.ACKNOWLEDGED, AcknowledgmentState.OPEN),
                updated.addenda().stream().map(AddendumReceipt::state).toList());
        assertEquals(1, service.openAddenda(ORG, REFERRER).size());
        assertEquals(3, service.openAddenda(ORG, REFERRER).get(0).version());
    }

    @Test
    void null_boundary_values_are_rejected() {
        assertEquals(422, status(() -> service.publish(new PublishCommand(null, SIGNER, INTERPRETER,
                ORDER, REFERRER, signed(InterpretationKind.INITIAL, 1, null, PATIENT)))));
        assertEquals(422, status(() -> service.publish(new PublishCommand(ORG, null, INTERPRETER,
                ORDER, REFERRER, signed(InterpretationKind.INITIAL, 1, null, PATIENT)))));
        assertEquals(422, status(() -> service.acknowledge(new AcknowledgeCommand(null, REFERRER, ORDER, 1))));
        assertEquals(422, status(() -> service.acknowledge(new AcknowledgeCommand(ORG, null, ORDER, 1))));
        assertEquals(422, status(() -> service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, null, 1))));
        assertEquals(422, status(() -> service.readForReferrer(null, REFERRER, ORDER)));
        assertEquals(422, status(() -> service.readForReferrer(ORG, null, ORDER)));
        assertEquals(422, status(() -> service.openAddenda(null, REFERRER)));
        assertEquals(422, status(() -> service.openAddenda(ORG, null)));
        assertEquals(422, status(() -> service.overdueAddenda(null, REFERRER)));
        assertEquals(422, status(() -> service.overdueAddenda(ORG, null)));
        assertEquals(422, status(() -> service.eventsForReferrer(null, REFERRER)));
        assertEquals(422, status(() -> service.eventsForReferrer(ORG, null)));
    }

    @Test
    void acknowledge_validates_boundary_values_and_tenant_scope() {
        assertEquals(422, status(() -> service.acknowledge(new AcknowledgeCommand(" ", REFERRER, ORDER, 2))));
        assertEquals(422, status(() -> service.acknowledge(new AcknowledgeCommand(ORG, " ", ORDER, 2))));
        assertEquals(422, status(() -> service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, " ", 2))));
        assertEquals(404, status(() -> service.acknowledge(new AcknowledgeCommand(OTHER_ORG, REFERRER, ORDER, 2))));

        service.publish(publish(INTERPRETER));
        assertEquals(404, status(() -> service.acknowledge(new AcknowledgeCommand(ORG, REFERRER, ORDER, 2))));
    }

    @Test
    void rules_and_delivery_carriers_are_pure_and_defensive() {
        assertTrue(ReportRules.mayDeliver(INTERPRETER));
        assertFalse(ReportRules.mayDeliver(STAFF));
        assertFalse(ReportRules.mayDeliver(NO_ROLE));
        assertTrue(ReportRules.isReferrer(REFERRER, REFERRER));
        assertFalse(ReportRules.isReferrer(OTHER_REFERRER, REFERRER));
        assertFalse(ReportRules.isSigned(null));

        Interpretation blankAttestation = new Interpretation(ORG, ORDER, 1, null, InterpretationKind.INITIAL,
                PATIENT, UID, "CT", "prior", "findings", "impression", "none", SIGNER, " ", AT,
                "template-1", null);
        Interpretation undated = new Interpretation(ORG, ORDER, 1, null, InterpretationKind.INITIAL,
                PATIENT, UID, "CT", "prior", "findings", "impression", "none", SIGNER, "attested", null,
                "template-1", null);
        Interpretation unattested = new Interpretation(ORG, ORDER, 1, null, InterpretationKind.INITIAL,
                PATIENT, UID, "CT", "prior", "findings", "impression", "none", SIGNER, null, AT,
                "template-1", null);
        assertFalse(ReportRules.isSigned(blankAttestation));
        assertFalse(ReportRules.isSigned(undated));
        assertFalse(ReportRules.isSigned(unattested));
        assertTrue(ReportRules.isSigned(signed(InterpretationKind.INITIAL, 1, null, PATIENT)));

        ReportDelivery delivery = service.publish(publish(INTERPRETER));
        List<Interpretation> mutable = new ArrayList<>(delivery.revisions());
        ReportDelivery frozen = new ReportDelivery(ORG, ORDER, PATIENT, REFERRER, 1, mutable, List.of());
        mutable.add(signed(InterpretationKind.ADDENDUM, 2, 1, PATIENT));
        assertEquals(1, frozen.revisions().size());
        assertThrows(UnsupportedOperationException.class, () -> frozen.revisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.addenda().clear());
        assertEquals(1, frozen.current().version());
        assertFalse(frozen.hasOpenAddendum());
    }

    @Test
    void event_wire_names_are_stable() {
        assertEquals("interpretation_delivered", ReportEventType.INTERPRETATION_DELIVERED.wire());
        assertEquals("addendum_delivered", ReportEventType.ADDENDUM_DELIVERED.wire());
        assertEquals("referrer_notified", ReportEventType.REFERRER_NOTIFIED.wire());
        assertEquals("addendum_acknowledged", ReportEventType.ADDENDUM_ACKNOWLEDGED.wire());
        assertEquals("unsigned_report_refused", ReportEventType.UNSIGNED_REPORT_REFUSED.wire());
        assertEquals("open", AcknowledgmentState.OPEN.wire());
        assertEquals("acknowledged", AcknowledgmentState.ACKNOWLEDGED.wire());
    }
}
