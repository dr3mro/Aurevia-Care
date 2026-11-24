package com.aurevia.it.radiology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
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

/** RAD-01 imaging order lifecycle, authorization, verification and isolation. */
class ImagingServiceTest {

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
    private static final Set<String> RECEPTION = Set.of("reception");
    private static final Set<String> INTERPRETER_ROLE = Set.of("radiology_interpreter");
    private static final Set<String> NO_ROLE = Set.of("finance");

    private ImagingService service;

    @BeforeEach
    void setUp() {
        RadPolicyCatalog policies = new RadPolicyCatalog(
                List.of(Modality.CT, Modality.MRI),
                List.of(new InterpreterPrivilege(INTERPRETER, Modality.CT, true, FROM, null)));
        service = new ImagingService(policies, Clock.fixed(AT, ZoneOffset.UTC));
    }

    private static IntakeCommand intake(String org, String orderId, String patient, Set<String> roles,
                                        Urgency urgency, String owner) {
        return new IntakeCommand(org, ACTOR, roles, orderId, patient, "enc-1", "CT-CHEST", urgency,
                "rule out pulmonary embolism", "referring-clinic", "radiology", owner,
                "rad-work-queue", REFERRER);
    }

    private static ScheduleCommand schedule(int version, Modality modality) {
        return new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, version, SCHEDULED, modality);
    }

    private static AccessionCommand accession(int version, String accession) {
        return new AccessionCommand(ORG, ACTOR, STAFF, ORDER, version, accession, ISSUER);
    }

    private static AssociateStudyCommand associate(int version, String patient, String accession,
                                                    String studyUid) {
        return new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER, version, studyUid, patient,
                accession, WORKSTATION);
    }

    private static SignCommand sign(int version, String patient, String accession, String studyUid) {
        return sign(version, patient, accession, studyUid, "findings");
    }

    private static SignCommand sign(int version, String patient, String accession, String studyUid,
                                    String findings) {
        return new SignCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, version, patient, accession,
                studyUid, "technique", "comparison", findings, "impression", "follow-up",
                "tpl-1", "I attest to this interpretation");
    }

    private static AddendumCommand addendum(int version, String patient, String studyUid) {
        return new AddendumCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, version, patient,
                studyUid, "additional findings", "additional impression", "late result", "attest");
    }

    private static CriticalCommunicationCommand critical(int version, String patient, String studyUid) {
        return new CriticalCommunicationCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, version,
                patient, studyUid, "critical finding", "referrer", "read back confirmed");
    }

    private ImagingOrder accessioned() {
        ImagingOrder order = service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        order = service.schedule(schedule(order.version(), Modality.CT));
        return service.accession(accession(order.version(), ACC));
    }

    private ImagingOrder associated() {
        ImagingOrder order = accessioned();
        return service.associateStudy(associate(order.version(), PATIENT, ACC, UID));
    }

    @Test
    void fullLifecycleProducesImmutableSignedInterpretationAndReferrerNotifications() {
        ImagingOrder associated = associated();
        assertEquals(OrderState.STUDY_ASSOCIATED, associated.state());
        assertEquals(4, associated.version());
        assertEquals(PATIENT, associated.verifiedPatientId());
        assertEquals(ACC, associated.verifiedAccession());
        assertEquals(UID, associated.studyInstanceUid());
        assertEquals(WORKSTATION, associated.workstationRef());

        Interpretation initial = service.sign(sign(associated.version(), PATIENT, ACC, UID));
        assertEquals(InterpretationKind.INITIAL, initial.kind());
        assertEquals(1, initial.version());
        assertNull(initial.supersedesVersion());
        assertEquals(INTERPRETER, initial.signer());
        assertEquals(AT, initial.signedAt());
        assertTrue(!initial.checksum().isEmpty());

        ImagingOrder signedOrder = service.read(ORG, ORDER);
        assertEquals(OrderState.SIGNED, signedOrder.state());
        assertEquals(5, signedOrder.version());

        String initialChecksum = initial.checksum();
        Interpretation addendum = service.addAddendum(addendum(signedOrder.version(), PATIENT, UID));
        assertEquals(InterpretationKind.ADDENDUM, addendum.kind());
        assertEquals(2, addendum.version());
        assertEquals(1, addendum.supersedesVersion());
        assertEquals("late result", addendum.reason());
        assertEquals(initialChecksum, service.interpretations(ORG, ORDER).get(0).checksum());
        assertEquals(addendum.checksum(), service.interpretation(ORG, ORDER).checksum());
        assertEquals(OrderState.ADDENDED, service.read(ORG, ORDER).state());
        assertEquals(6, service.read(ORG, ORDER).version());

        List<ImagingOrder> history = service.history(ORG, ORDER);
        assertEquals(6, history.size());
        assertEquals(OrderState.INTAKE, history.get(0).state());
        assertEquals(OrderState.ADDENDED, history.get(5).state());

        List<RadEvent> emitted = service.events(ORG);
        assertEquals(8, emitted.size());
        assertEquals(List.of(RadEventType.ORDER_INTAKED, RadEventType.ORDER_SCHEDULED,
                RadEventType.ACCESSION_RECORDED, RadEventType.STUDY_ASSOCIATED,
                RadEventType.INTERPRETATION_SIGNED, RadEventType.REFERRER_NOTIFIED,
                RadEventType.ADDENDUM_LINKED, RadEventType.REFERRER_NOTIFIED),
                emitted.stream().map(RadEvent::type).toList());
        assertEquals(ACTOR, emitted.get(0).actor());
        assertEquals(INTERPRETER, emitted.get(4).actor());
        assertEquals(REFERRER, emitted.get(4).destination());
        assertEquals(initialChecksum, emitted.get(4).payloadDigest());
        assertEquals(REFERRER, emitted.get(7).destination());
        assertEquals(addendum.checksum(), emitted.get(6).payloadDigest());

        RadEvent criticalEvent = service.recordCriticalCommunication(critical(6, PATIENT, UID));
        assertEquals(RadEventType.CRITICAL_COMMUNICATION, criticalEvent.type());
        assertEquals(10, service.events(ORG).size());
        assertEquals(List.of(RadEventType.CRITICAL_COMMUNICATION, RadEventType.READ_BACK),
                service.events(ORG).subList(8, 10).stream().map(RadEvent::type).toList());
    }

    @Test
    void wrongPatientOrAccessionStudyAssociationIsRefused() {
        ImagingOrder accessioned = accessioned();
        int version = accessioned.version();

        ApiException wrongPatient = assertThrows(ApiException.class,
                () -> service.associateStudy(associate(version, OTHER_PATIENT, ACC, UID)));
        assertEquals(422, wrongPatient.status());
        assertEquals("LABEL_MISMATCH", wrongPatient.code());
        ApiException wrongAccession = assertThrows(ApiException.class,
                () -> service.associateStudy(associate(version, PATIENT, "ACC-9999", UID)));
        assertEquals(422, wrongAccession.status());

        ImagingOrder unchanged = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, unchanged.state());
        assertEquals(3, unchanged.version());
        assertTrue(service.events(ORG).stream().noneMatch(e -> e.type() == RadEventType.STUDY_ASSOCIATED));
    }

    @Test
    void intakeRequiresUrgencyClinicalQuestionAndNamedStatOwner() {
        assertEquals(422, codeOf(() -> service.intake(new IntakeCommand(ORG, ACTOR, STAFF, ORDER,
                PATIENT, "enc", "CT", Urgency.ROUTINE, " ", "src", "dest", OWNER, "queue", REFERRER))));
        assertEquals(422, codeOf(() -> service.intake(new IntakeCommand(ORG, ACTOR, STAFF, ORDER,
                PATIENT, "enc", "CT", Urgency.ROUTINE, null, "src", "dest", OWNER, "queue", REFERRER))));
        assertEquals(422, codeOf(() -> service.intake(intake(ORG, ORDER, PATIENT, STAFF, null, OWNER))));
        assertEquals("OWNER_REQUIRED", codeOfCode(() -> service.intake(
                intake(ORG, ORDER, PATIENT, STAFF, Urgency.STAT, " "))));
        assertEquals(Urgency.STAT,
                service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.STAT, OWNER)).urgency());
        ApiException denied = assertThrows(ApiException.class,
                () -> service.intake(intake(ORG, ORDER, PATIENT, NO_ROLE, Urgency.ROUTINE, OWNER)));
        assertEquals(403, denied.status());
        assertEquals(422, codeOf(() -> service.intake(intake(" ", ORDER, PATIENT, STAFF,
                Urgency.ROUTINE, OWNER))));
    }

    @Test
    void duplicateOrderAndAccessionIdentifiersAreRejectedInOneTenant() {
        service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        ApiException duplicateOrder = assertThrows(ApiException.class,
                () -> service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER)));
        assertEquals(409, duplicateOrder.status());
        assertEquals("DUPLICATE_ORDER", duplicateOrder.code());

        ImagingOrder first = service.read(ORG, ORDER);
        first = service.schedule(schedule(first.version(), Modality.CT));
        first = service.accession(accession(first.version(), ACC));
        assertEquals(OrderState.ACCESSIONED, first.state());
        ImagingOrder second = service.intake(intake(ORG, "order-b", PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        second = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, "order-b", second.version(),
                SCHEDULED, Modality.CT));
        final int secondVersion = second.version();
        ApiException duplicateAccession = assertThrows(ApiException.class,
                () -> service.accession(new AccessionCommand(ORG, ACTOR, STAFF, "order-b",
                        secondVersion, ACC, ISSUER)));
        assertEquals(409, duplicateAccession.status());
        assertEquals("DUPLICATE_ACCESSION", duplicateAccession.code());
    }

    @Test
    void schedulingEnforcesApprovalVersionAndState() {
        ImagingOrder intake = service.intake(intake(ORG, ORDER, PATIENT, RECEPTION, Urgency.ROUTINE, OWNER));
        assertEquals(403, codeOf(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, NO_ROLE,
                ORDER, intake.version(), SCHEDULED, Modality.CT))));
        assertEquals(422, codeOf(() -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF,
                ORDER, intake.version(), null, Modality.CT))));
        assertEquals(422, codeOf(() -> service.schedule(schedule(intake.version(), Modality.PET))));
        assertEquals(412, codeOf(() -> service.schedule(schedule(intake.version() + 1, Modality.CT))));

        ImagingOrder scheduled = service.schedule(schedule(intake.version(), Modality.CT));
        assertEquals(OrderState.SCHEDULED, scheduled.state());
        assertEquals(Modality.CT, scheduled.modality());
        assertEquals(SCHEDULED, scheduled.scheduledAt());
        assertEquals(409, codeOf(() -> service.schedule(schedule(scheduled.version(), Modality.CT))));
    }

    @Test
    void signingEnforcesAssociationVerificationPrivilegeAndRequiredFields() {
        ImagingOrder accessioned = accessioned();
        assertEquals(409, codeOf(() -> service.sign(sign(accessioned.version(), PATIENT, ACC, UID))));

        ImagingOrder associated = service.associateStudy(associate(accessioned.version(), PATIENT, ACC, UID));
        assertEquals(422, codeOf(() -> service.sign(sign(associated.version(), OTHER_PATIENT, ACC, UID))));
        assertEquals(422, codeOf(() -> service.sign(sign(associated.version(), PATIENT, "ACC-9", UID))));
        assertEquals(422, codeOf(() -> service.sign(sign(associated.version(), PATIENT, ACC, OTHER_UID))));
        assertEquals(422, codeOf(() -> service.sign(sign(associated.version(), PATIENT, ACC, UID, " "))));
        assertEquals(412, codeOf(() -> service.sign(sign(associated.version() + 1, PATIENT, ACC, UID))));
        assertEquals(403, codeOf(() -> service.sign(new SignCommand(ORG, INTERPRETER, STAFF, ORDER,
                associated.version(), PATIENT, ACC, UID, "technique", "comparison", "findings",
                "impression", "follow-up", "tpl-1", "attest"))));
        assertEquals(403, codeOf(() -> service.sign(new SignCommand(ORG, "dr-no-priv", INTERPRETER_ROLE,
                ORDER, associated.version(), PATIENT, ACC, UID, "technique", "comparison", "findings",
                "impression", "follow-up", "tpl-1", "attest"))));
    }

    @Test
    void addendaRequireReasonAndCanChainWithoutEditingSignedContent() {
        ImagingOrder associated = associated();
        assertEquals(409, codeOf(() -> service.addAddendum(addendum(associated.version(), PATIENT, UID))));

        Interpretation initial = service.sign(sign(associated.version(), PATIENT, ACC, UID));
        ImagingOrder signed = service.read(ORG, ORDER);
        assertEquals(422, codeOf(() -> service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                INTERPRETER_ROLE, ORDER, signed.version(), PATIENT, UID, "findings", "impression",
                " ", "attest"))));
        assertEquals(422, codeOf(() -> service.addAddendum(addendum(signed.version(), OTHER_PATIENT, UID))));
        assertEquals(412, codeOf(() -> service.addAddendum(addendum(signed.version() + 1, PATIENT, UID))));

        Interpretation firstAddendum = service.addAddendum(addendum(signed.version(), PATIENT, UID));
        ImagingOrder onceAdded = service.read(ORG, ORDER);
        Interpretation secondAddendum = service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                INTERPRETER_ROLE, ORDER, onceAdded.version(), PATIENT, UID, "more", "more",
                "second reason", "attest"));
        assertEquals(3, secondAddendum.version());
        assertEquals(2, secondAddendum.supersedesVersion());
        assertEquals(2, firstAddendum.version());
        assertEquals(1, firstAddendum.supersedesVersion());
        assertEquals(initial.checksum(), service.interpretations(ORG, ORDER).get(0).checksum());
        assertEquals(7, service.read(ORG, ORDER).version());
        assertEquals(10, service.events(ORG).size());
    }

    @Test
    void criticalCommunicationRequiresAssociationAndReadBack() throws Exception {
        ImagingOrder accessioned = accessioned();
        assertEquals(409, codeOf(() -> service.recordCriticalCommunication(
                critical(accessioned.version(), PATIENT, UID))));
        ImagingOrder associated = service.associateStudy(associate(accessioned.version(), PATIENT, ACC, UID));
        assertEquals(422, codeOf(() -> service.recordCriticalCommunication(new CriticalCommunicationCommand(
                ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, associated.version(), PATIENT, UID,
                "finding", "recipient", " "))));
        assertEquals(422, codeOf(() -> service.recordCriticalCommunication(
                critical(associated.version(), OTHER_PATIENT, UID))));
        assertEquals(403, codeOf(() -> service.recordCriticalCommunication(
                new CriticalCommunicationCommand(ORG, ACTOR, NO_ROLE, ORDER, associated.version(),
                        PATIENT, UID, "finding", "recipient", "read back"))));
        RadEvent event = service.recordCriticalCommunication(critical(associated.version(), PATIENT, UID));
        assertEquals(RadEventType.CRITICAL_COMMUNICATION, event.type());
        assertEquals(sha256("critical finding"), event.payloadDigest());
        assertEquals("referrer", event.destination());
        assertEquals(event.eventId(), service.events(ORG).stream()
                .filter(e -> e.type() == RadEventType.CRITICAL_COMMUNICATION).findFirst().orElseThrow()
                .eventId());

        // Read-back is a distinct fact with its own digest; no clinical narrative enters an event.
        RadEvent readBack = service.events(ORG).stream()
                .filter(e -> e.type() == RadEventType.READ_BACK).findFirst().orElseThrow();
        assertEquals(sha256("read back confirmed"), readBack.payloadDigest());
        assertEquals("referrer", readBack.destination());
        assertEquals(PATIENT, readBack.patientId());
        assertTrue(service.events(ORG).stream().noneMatch(e -> "critical finding".equals(e.payloadDigest())
                || "read back confirmed".equals(e.payloadDigest())));
    }

    @Test
    void twoTenantsWithIdenticalLocalIdentifiersRemainIsolated() {
        ImagingOrder first = service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        ImagingOrder second = service.intake(intake(OTHER_ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        assertEquals(ORG, first.organizationId());
        assertEquals(OTHER_ORG, second.organizationId());
        assertEquals(ORDER, second.orderId());
        assertNotEquals(first, second);

        ImagingOrder firstScheduled = service.schedule(schedule(first.version(), Modality.CT));
        ImagingOrder secondScheduled = service.schedule(new ScheduleCommand(OTHER_ORG, ACTOR, STAFF,
                ORDER, second.version(), SCHEDULED, Modality.MRI));
        service.accession(accession(firstScheduled.version(), ACC));
        service.accession(new AccessionCommand(OTHER_ORG, ACTOR, STAFF, ORDER,
                secondScheduled.version(), ACC, ISSUER));

        assertEquals(Modality.CT, service.read(ORG, ORDER).modality());
        assertEquals(Modality.MRI, service.read(OTHER_ORG, ORDER).modality());
        assertEquals(3, service.history(ORG, ORDER).size());
        assertEquals(3, service.history(OTHER_ORG, ORDER).size());
        assertEquals(3, service.events(ORG).size());
        assertEquals(3, service.events(OTHER_ORG).size());

        ApiException concealed = assertThrows(ApiException.class,
                () -> service.accession(new AccessionCommand(OTHER_ORG, ACTOR, STAFF, "missing", 1,
                        "ACC-X", ISSUER)));
        assertEquals(404, concealed.status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.read("org-missing", ORDER)).status());
    }

    @Test
    void concealedAndPendingReadsFailClosed() {
        assertEquals(404, codeOf(() -> service.read(ORG, "missing")));
        assertEquals(404, codeOf(() -> service.history(ORG, "missing")));
        assertEquals(404, codeOf(() -> service.interpretation(ORG, "missing")));
        assertTrue(service.events("org-empty").isEmpty());

        service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        assertEquals(404, codeOf(() -> service.interpretations(ORG, ORDER)));
    }

    @Test
    void studyCommandsDenyUnauthorizedAndUnprivilegedActors() {
        ImagingOrder intake = service.intake(intake(ORG, ORDER, PATIENT, STAFF, Urgency.ROUTINE, OWNER));
        assertEquals(403, codeOf(() -> service.accession(new AccessionCommand(ORG, ACTOR, NO_ROLE,
                ORDER, intake.version(), ACC, ISSUER))));
        ImagingOrder scheduled = service.schedule(schedule(intake.version(), Modality.CT));
        assertEquals(403, codeOf(() -> service.associateStudy(new AssociateStudyCommand(ORG, ACTOR,
                NO_ROLE, ORDER, scheduled.version(), UID, PATIENT, ACC, WORKSTATION))));
        ImagingOrder accessioned = service.accession(accession(scheduled.version(), ACC));
        ImagingOrder associated = service.associateStudy(associate(accessioned.version(), PATIENT, ACC, UID));
        service.sign(sign(associated.version(), PATIENT, ACC, UID));
        int signedVersion = service.read(ORG, ORDER).version();
        assertEquals(403, codeOf(() -> service.addAddendum(new AddendumCommand(ORG, INTERPRETER, STAFF,
                ORDER, signedVersion, PATIENT, UID, "f", "i", "r", "a"))));
        assertEquals(403, codeOf(() -> service.addAddendum(new AddendumCommand(ORG, "dr-no-priv",
                INTERPRETER_ROLE, ORDER, signedVersion, PATIENT, UID, "f", "i", "r", "a"))));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static int codeOf(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    private static String codeOfCode(Runnable action) {
        return assertThrows(ApiException.class, action::run).code();
    }
}
