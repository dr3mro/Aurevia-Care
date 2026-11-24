package com.aurevia.radiology.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.radiology.ImagingOrder;
import com.aurevia.radiology.ImagingService;
import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadCommands.AddendumCommand;
import com.aurevia.radiology.RadCommands.AccessionCommand;
import com.aurevia.radiology.RadCommands.AssociateStudyCommand;
import com.aurevia.radiology.RadCommands.CriticalCommunicationCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.ScheduleCommand;
import com.aurevia.radiology.RadCommands.SignCommand;
import com.aurevia.radiology.RadEvent;
import com.aurevia.radiology.RadPolicyCatalog;
import com.aurevia.radiology.RadPolicyCatalog.InterpreterPrivilege;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * RAD-01 clause 4 (API test): an addendum links to the prior signed interpretation
 * without rewriting it, and a critical communication keeps its finding and its
 * confirmed read-back as two separate, digested facts.
 *
 * <p>All fixtures are synthetic. The service is exercised at the domain entry point
 * with a fixed clock and an effective dated interpreter privilege, so digests and
 * event order are deterministic. Each refusal asserts both the HTTP status and the
 * stable error code so a wrong failure cannot masquerade as the intended guard.</p>
 */
class LinkAddendaCriticalCommunicationsTest {

    private static final String ORG = "org-a";
    private static final String OTHER_ORG = "org-b";
    private static final String ORDER = "order-a";
    private static final String PATIENT = "patient-a";
    private static final String OTHER_PATIENT = "patient-b";
    private static final String ACC = "ACC-1001";
    private static final String ISSUER = "ISSUER-A";
    private static final String UID = "1.2.840.113619.2.55.3.12345";
    private static final String OTHER_UID = "1.2.840.113619.2.55.3.99999";
    private static final String WORKSTATION = "pacs-workstation-1";
    private static final String ACTOR = "tech-1";
    private static final String INTERPRETER = "dr-x";
    private static final String REFERRER = "dr-referrer";
    private static final String OWNER = "dr-owner";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T10:00:00Z");
    private static final Instant SCHEDULED = AT.plus(Duration.ofDays(1));

    private static final Set<String> STAFF = Set.of("radiology_staff");
    private static final Set<String> INTERPRETER_ROLE = Set.of("radiology_interpreter");

    private static final String SIGN_FINDINGS = "findings";
    private static final String ADDENDUM_FINDINGS = "additional findings";
    private static final String ADDENDUM_IMPRESSION = "additional impression";
    private static final String ADDENDUM_REASON = "late result";
    private static final String FINDING = "critical finding";
    private static final String RECIPIENT = "referrer";
    private static final String READ_BACK = "read back confirmed";

    private ImagingService service;

    @BeforeEach
    void setUp() {
        RadPolicyCatalog policies = new RadPolicyCatalog(
                List.of(Modality.CT, Modality.MRI),
                List.of(new InterpreterPrivilege(INTERPRETER, Modality.CT, true, FROM, null)));
        service = new ImagingService(policies, Clock.fixed(AT, ZoneOffset.UTC));
    }

    private static IntakeCommand intake(String org, String orderId, String patient, Set<String> roles) {
        return new IntakeCommand(org, ACTOR, roles, orderId, patient, "enc-1", "CT-CHEST",
                Urgency.ROUTINE, "rule out pulmonary embolism", "referring-clinic", "radiology",
                OWNER, "rad-work-queue", REFERRER);
    }

    private static ScheduleCommand schedule(int version) {
        return new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, version, SCHEDULED, Modality.CT);
    }

    private static AccessionCommand accession(int version) {
        return new AccessionCommand(ORG, ACTOR, STAFF, ORDER, version, ACC, ISSUER);
    }

    private static AssociateStudyCommand associate(int version, String patient, String studyUid) {
        return new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER, version, studyUid, patient,
                ACC, WORKSTATION);
    }

    private static SignCommand sign(int version, String patient, String studyUid) {
        return new SignCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, version, patient, ACC,
                studyUid, "technique", "comparison", SIGN_FINDINGS, "impression", "follow-up",
                "tpl-1", "I attest to this interpretation");
    }

    private static AddendumCommand addendum(int version, String patient, String studyUid, String reason) {
        return new AddendumCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, version, patient,
                studyUid, ADDENDUM_FINDINGS, ADDENDUM_IMPRESSION, reason, "attest");
    }

    private static CriticalCommunicationCommand critical(int version, String patient, String studyUid,
                                                          String readBack) {
        return new CriticalCommunicationCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, version,
                patient, studyUid, FINDING, RECIPIENT, readBack);
    }

    private ImagingOrder associated() {
        ImagingOrder order = service.intake(intake(ORG, ORDER, PATIENT, STAFF));
        order = service.schedule(schedule(order.version()));
        order = service.accession(accession(order.version()));
        return service.associateStudy(associate(order.version(), PATIENT, UID));
    }

    private ImagingOrder accessioned() {
        ImagingOrder order = service.intake(intake(ORG, ORDER, PATIENT, STAFF));
        order = service.schedule(schedule(order.version()));
        return service.accession(accession(order.version()));
    }

    private ImagingOrder signed() {
        ImagingOrder associated = associated();
        service.sign(sign(associated.version(), PATIENT, UID));
        return service.read(ORG, ORDER);
    }

    /** Assert the exact HTTP status and stable error code of a refused command. */
    private static void assertRefusal(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    @Test
    void addendaLinkAsNewRevisionsWithoutEditingTheSignedReport() {
        ImagingOrder associated = associated();
        Interpretation initial = service.sign(sign(associated.version(), PATIENT, UID));
        String initialChecksum = initial.checksum();
        ImagingOrder signed = service.read(ORG, ORDER);
        assertEquals(OrderState.SIGNED, signed.state());

        Interpretation addendum = service.addAddendum(
                addendum(signed.version(), PATIENT, UID, ADDENDUM_REASON));

        assertEquals(InterpretationKind.ADDENDUM, addendum.kind());
        assertTrue(addendum.isAddendum());
        assertEquals(2, addendum.version());
        assertEquals(Integer.valueOf(1), addendum.supersedesVersion());
        assertEquals(ADDENDUM_REASON, addendum.reason());
        // Linking a new revision never rewrites the already signed content.
        assertEquals(2, service.interpretations(ORG, ORDER).size());
        assertEquals(initialChecksum, service.interpretations(ORG, ORDER).get(0).checksum());
        assertEquals(addendum, service.interpretation(ORG, ORDER));

        ImagingOrder addended = service.read(ORG, ORDER);
        assertEquals(OrderState.ADDENDED, addended.state());
        assertEquals(signed.version() + 1, addended.version());

        List<RadEvent> emitted = service.events(ORG);
        assertEquals(8, emitted.size());
        RadEvent linked = emitted.get(6);
        assertEquals(RadEventType.ADDENDUM_LINKED, linked.type());
        assertEquals(addendum.checksum(), linked.payloadDigest());
        assertEquals(REFERRER, linked.destination());
        assertEquals(INTERPRETER, linked.actor());
        RadEvent notified = emitted.get(7);
        assertEquals(RadEventType.REFERRER_NOTIFIED, notified.type());
        assertEquals(REFERRER, notified.destination());
        // The notification fact carries no clinical payload, not even a digest.
        assertNull(notified.payloadDigest());
    }

    @Test
    void criticalCommunicationKeepsFindingAndReadBackAsSeparateDigestedFacts() {
        ImagingOrder associated = associated();
        int versionBefore = associated.version();
        OrderState stateBefore = associated.state();
        List<RadEvent> before = service.events(ORG);

        RadEvent critical = service.recordCriticalCommunication(
                critical(associated.version(), PATIENT, UID, READ_BACK));

        List<RadEvent> after = service.events(ORG);
        assertEquals(before.size() + 2, after.size());
        assertEquals(RadEventType.CRITICAL_COMMUNICATION, critical.type());
        assertEquals(NoteChecksum.sha256(FINDING), critical.payloadDigest());
        assertEquals(RECIPIENT, critical.destination());
        assertEquals(versionBefore, critical.orderVersion());

        List<RadEvent> added = after.subList(before.size(), after.size());
        assertEquals(List.of(RadEventType.CRITICAL_COMMUNICATION, RadEventType.READ_BACK),
                added.stream().map(RadEvent::type).toList());
        RadEvent readBack = added.get(1);
        assertEquals(NoteChecksum.sha256(READ_BACK), readBack.payloadDigest());
        assertEquals(RECIPIENT, readBack.destination());
        assertEquals(PATIENT, readBack.patientId());
        assertNotEquals(critical.eventId(), readBack.eventId());
        // Only digests are retained; the clinical narratives never enter an event.
        assertTrue(after.stream().noneMatch(e -> FINDING.equals(e.payloadDigest())
                || READ_BACK.equals(e.payloadDigest())));

        ImagingOrder unchanged = service.read(ORG, ORDER);
        assertEquals(versionBefore, unchanged.version());
        assertEquals(stateBefore, unchanged.state());
    }

    @Test
    void addendumWithoutReasonIsRefused() {
        ImagingOrder signed = signed();

        assertRefusal(422, "REASON_REQUIRED", () -> service.addAddendum(
                addendum(signed.version(), PATIENT, UID, " ")));

        assertEquals(1, service.interpretations(ORG, ORDER).size());
        assertEquals(signed.version(), service.read(ORG, ORDER).version());
        assertTrue(service.events(ORG).stream().noneMatch(e -> e.type() == RadEventType.ADDENDUM_LINKED));
    }

    @Test
    void addendumByActorWithoutInterpretationRoleIsForbidden() {
        ImagingOrder signed = signed();

        assertRefusal(403, "FORBIDDEN", () -> service.addAddendum(new AddendumCommand(ORG,
                INTERPRETER, STAFF, ORDER, signed.version(), PATIENT, UID, ADDENDUM_FINDINGS,
                ADDENDUM_IMPRESSION, ADDENDUM_REASON, "attest")));

        assertEquals(1, service.interpretations(ORG, ORDER).size());
        assertEquals(OrderState.SIGNED, service.read(ORG, ORDER).state());
    }

    @Test
    void staleVersionCannotLinkAnAddendum() {
        ImagingOrder signed = signed();

        assertRefusal(412, "STALE_VERSION", () -> service.addAddendum(
                addendum(signed.version() + 1, PATIENT, UID, ADDENDUM_REASON)));

        assertEquals(1, service.interpretations(ORG, ORDER).size());
        assertEquals(signed.version(), service.read(ORG, ORDER).version());
    }

    @Test
    void criticalCommunicationBeforeStudyAssociationIsRefused() {
        ImagingOrder accessioned = accessioned();

        assertRefusal(409, "STUDY_NOT_ASSOCIATED", () -> service.recordCriticalCommunication(
                critical(accessioned.version(), PATIENT, UID, READ_BACK)));

        assertTrue(service.events(ORG).stream().noneMatch(e ->
                e.type() == RadEventType.CRITICAL_COMMUNICATION || e.type() == RadEventType.READ_BACK));
        assertEquals(accessioned.version(), service.read(ORG, ORDER).version());
    }

    @Test
    void criticalCommunicationWithoutReadBackIsRefused() {
        ImagingOrder associated = associated();

        assertRefusal(422, "READ_BACK_REQUIRED", () -> service.recordCriticalCommunication(
                critical(associated.version(), PATIENT, UID, " ")));

        assertTrue(service.events(ORG).stream().noneMatch(e ->
                e.type() == RadEventType.CRITICAL_COMMUNICATION || e.type() == RadEventType.READ_BACK));
    }

    @Test
    void wrongTenantCannotReadTheInterpretation() {
        signed();

        assertRefusal(404, "NOT_FOUND", () -> service.interpretation(OTHER_ORG, ORDER));
        assertRefusal(404, "NOT_FOUND", () -> service.interpretations(OTHER_ORG, ORDER));

        assertEquals(InterpretationKind.INITIAL, service.interpretation(ORG, ORDER).kind());
    }

    @Test
    void wrongPatientOrStudyIsRefused() {
        ImagingOrder associated = associated();
        assertRefusal(422, "LABEL_MISMATCH", () -> service.recordCriticalCommunication(
                critical(associated.version(), OTHER_PATIENT, UID, READ_BACK)));
        assertRefusal(422, "LABEL_MISMATCH", () -> service.recordCriticalCommunication(
                critical(associated.version(), PATIENT, OTHER_UID, READ_BACK)));
        assertTrue(service.events(ORG).stream().noneMatch(e ->
                e.type() == RadEventType.CRITICAL_COMMUNICATION || e.type() == RadEventType.READ_BACK));

        service.sign(sign(associated.version(), PATIENT, UID));
        ImagingOrder signed = service.read(ORG, ORDER);
        assertRefusal(422, "LABEL_MISMATCH", () -> service.addAddendum(
                addendum(signed.version(), OTHER_PATIENT, UID, ADDENDUM_REASON)));
        assertRefusal(422, "LABEL_MISMATCH", () -> service.addAddendum(
                addendum(signed.version(), PATIENT, OTHER_UID, ADDENDUM_REASON)));
        assertEquals(1, service.interpretations(ORG, ORDER).size());
        assertEquals(OrderState.SIGNED, service.read(ORG, ORDER).state());
    }
}
