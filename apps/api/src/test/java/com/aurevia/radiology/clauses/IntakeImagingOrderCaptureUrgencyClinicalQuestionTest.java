package com.aurevia.radiology.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.aurevia.radiology.ImagingOrder;
import com.aurevia.radiology.ImagingService;
import com.aurevia.radiology.RadCommands.AccessionCommand;
import com.aurevia.radiology.RadCommands.AssociateStudyCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.ScheduleCommand;
import com.aurevia.radiology.RadEvent;
import com.aurevia.radiology.RadPolicyCatalog;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * RAD-01 clause 1 proof at the imaging domain entry point: an order captures urgency and a
 * clinical question at intake, scheduling records a time and an approved modality, accession
 * records an issuer-scoped identifier, and an external Study Instance UID is linked only when
 * the patient and accession labels match the order.
 *
 * <p>The rule is exercised through the public
 * {@code ImagingService(RadPolicyCatalog, Clock)} constructor because the controller's
 * service-taking constructor is package-private and it exposes no HTTP route for scheduling or
 * accession. Persistence, HTTP publication and the full end-to-end chain are deliberately out of
 * scope for this proof and are recorded as a gap in the clause note. All facts are synthetic.</p>
 */
class IntakeImagingOrderCaptureUrgencyClinicalQuestionTest {

    private static final String ORG = "org-rad-clause-one";
    private static final String OTHER_ORG = "org-rad-other";
    private static final String ORDER = "order-rad-clause-one";
    private static final String PATIENT = "patient-rad-clause-one";
    private static final String OTHER_PATIENT = "patient-rad-other";
    private static final String ACC = "ACC-3001";
    private static final String OTHER_ACC = "ACC-3999";
    private static final String ISSUER = "ISSUER-RAD";
    private static final String UID = "1.2.840.113619.2.55.3.3001";
    private static final String WORKSTATION = "pacs-workstation-rad";
    private static final String ACTOR = "tech-rad";
    private static final String REFERRER = "referrer-rad";
    private static final String OWNER = "owner-rad";
    private static final String QUESTION = "rule out pulmonary embolism";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant SCHEDULED = AT.plus(Duration.ofDays(1));

    private static final Set<String> STAFF = Set.of("radiology_staff");
    private static final Set<String> NO_ROLE = Set.of("finance");

    private ImagingService service;

    /** Build a deterministic domain kernel with CT and MRI approved and a fixed clock. */
    @BeforeEach
    void setUp() {
        service = new ImagingService(
                new RadPolicyCatalog(List.of(Modality.CT, Modality.MRI), List.of()),
                Clock.fixed(AT, ZoneOffset.UTC));
    }

    private static IntakeCommand intake(Urgency urgency, String clinicalQuestion, String owner,
                                        Set<String> roles) {
        return new IntakeCommand(ORG, ACTOR, roles, ORDER, PATIENT, "enc-1", "CT-CHEST", urgency,
                clinicalQuestion, "clinic", "radiology", owner, "rad-queue", REFERRER);
    }

    private ImagingOrder accessioned() {
        ImagingOrder order = service.intake(intake(Urgency.ROUTINE, QUESTION, OWNER, STAFF));
        order = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                SCHEDULED, Modality.CT));
        return service.accession(new AccessionCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                ACC, ISSUER));
    }

    private static AssociateStudyCommand associate(int version, String patient, String accession) {
        return new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER, version, UID, patient, accession,
                WORKSTATION);
    }

    /**
     * The four steps append one revision each, preserve urgency and the clinical question, and
     * emit the four domain facts in lifecycle order; the UID is linked only with matching labels.
     */
    @Test
    void intakeSchedulesAccessionsAndAssociatesOnlyWithMatchingLabels() {
        ImagingOrder received = service.intake(intake(Urgency.ROUTINE, QUESTION, OWNER, STAFF));
        assertEquals(OrderState.INTAKE, received.state());
        assertEquals(1, received.version());
        assertEquals(Urgency.ROUTINE, received.urgency());
        assertEquals(QUESTION, received.clinicalQuestion());
        assertNull(received.modality());
        assertNull(received.scheduledAt());

        ImagingOrder scheduled = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER,
                received.version(), SCHEDULED, Modality.CT));
        assertEquals(OrderState.SCHEDULED, scheduled.state());
        assertEquals(2, scheduled.version());
        assertEquals(SCHEDULED, scheduled.scheduledAt());
        assertEquals(Modality.CT, scheduled.modality());
        assertEquals(Urgency.ROUTINE, scheduled.urgency());
        assertEquals(QUESTION, scheduled.clinicalQuestion());

        ImagingOrder accessioned = service.accession(new AccessionCommand(ORG, ACTOR, STAFF, ORDER,
                scheduled.version(), ACC, ISSUER));
        assertEquals(OrderState.ACCESSIONED, accessioned.state());
        assertEquals(3, accessioned.version());
        assertEquals(ACC, accessioned.accession());
        assertEquals(ISSUER, accessioned.accessionIssuer());
        assertEquals(Modality.CT, accessioned.modality());
        assertEquals(Urgency.ROUTINE, accessioned.urgency());
        assertEquals(QUESTION, accessioned.clinicalQuestion());

        ImagingOrder associated = service.associateStudy(
                associate(accessioned.version(), PATIENT, ACC));
        assertEquals(OrderState.STUDY_ASSOCIATED, associated.state());
        assertEquals(4, associated.version());
        assertEquals(UID, associated.studyInstanceUid());
        assertEquals(WORKSTATION, associated.workstationRef());
        assertEquals(PATIENT, associated.verifiedPatientId());
        assertEquals(ACC, associated.verifiedAccession());
        assertEquals(Modality.CT, associated.modality());
        assertEquals(Urgency.ROUTINE, associated.urgency());
        assertEquals(QUESTION, associated.clinicalQuestion());

        List<ImagingOrder> history = service.history(ORG, ORDER);
        assertEquals(List.of(OrderState.INTAKE, OrderState.SCHEDULED, OrderState.ACCESSIONED,
                OrderState.STUDY_ASSOCIATED), history.stream().map(ImagingOrder::state).toList());
        assertEquals(List.of(1, 2, 3, 4), history.stream().map(ImagingOrder::version).toList());

        List<RadEvent> events = service.events(ORG).stream()
                .filter(event -> ORDER.equals(event.orderId())).toList();
        assertEquals(List.of(RadEventType.ORDER_INTAKED, RadEventType.ORDER_SCHEDULED,
                RadEventType.ACCESSION_RECORDED, RadEventType.STUDY_ASSOCIATED),
                events.stream().map(RadEvent::type).toList());
        assertEquals(List.of(1, 2, 3, 4), events.stream().map(RadEvent::orderVersion).toList());
        assertNull(events.get(0).destination());
        assertNull(events.get(0).payloadDigest());
        assertEquals(Modality.CT.wire(), events.get(1).destination());
        assertEquals(ISSUER + "|" + ACC, events.get(2).destination());
        assertEquals(UID, events.get(3).destination());
    }

    /** A blank clinical question or a missing urgency is refused before any order is appended. */
    @Test
    void intakeRefusesMissingClinicalQuestionOrUrgency() {
        ApiException noQuestion = assertThrows(ApiException.class,
                () -> service.intake(intake(Urgency.ROUTINE, "  ", OWNER, STAFF)));
        assertEquals(422, noQuestion.status());
        assertEquals("NO_CLINICAL_QUESTION", noQuestion.code());

        ApiException noUrgency = assertThrows(ApiException.class,
                () -> service.intake(intake(null, QUESTION, OWNER, STAFF)));
        assertEquals(422, noUrgency.status());
        assertEquals("INVALID_URGENCY", noUrgency.code());

        assertTrue(service.events(ORG).isEmpty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.read(ORG, ORDER)).status());
    }

    /** STAT urgency requires a named owner; the guard is inactive for a routine order. */
    @Test
    void statIntakeWithoutNamedOwnerIsRefused() {
        ApiException refused = assertThrows(ApiException.class,
                () -> service.intake(intake(Urgency.STAT, QUESTION, "  ", STAFF)));
        assertEquals(422, refused.status());
        assertEquals("OWNER_REQUIRED", refused.code());

        ImagingOrder routine = service.intake(intake(Urgency.ROUTINE, QUESTION, OWNER, STAFF));
        assertEquals(OrderState.INTAKE, routine.state());
        assertEquals(1, routine.version());
        assertEquals(OWNER, routine.responsibleClinician());
    }

    /** A mismatched patient or accession opens one review and leaves the order accessioned. */
    @Test
    void associationRefusesMismatchedPatientAndAccession() {
        ImagingOrder accessioned = accessioned();

        ApiException wrongPatient = assertThrows(ApiException.class,
                () -> service.associateStudy(associate(accessioned.version(), OTHER_PATIENT, ACC)));
        assertEquals(422, wrongPatient.status());
        assertEquals("LABEL_MISMATCH", wrongPatient.code());

        ApiException wrongAccession = assertThrows(ApiException.class,
                () -> service.associateStudy(associate(accessioned.version(), PATIENT, OTHER_ACC)));
        assertEquals(422, wrongAccession.status());
        assertEquals("LABEL_MISMATCH", wrongAccession.code());

        ImagingOrder unchanged = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, unchanged.state());
        assertEquals(3, unchanged.version());
        assertNull(unchanged.studyInstanceUid());
        assertNull(unchanged.verifiedPatientId());
        assertNull(unchanged.verifiedAccession());
        assertTrue(service.events(ORG).stream()
                .noneMatch(event -> event.type() == RadEventType.STUDY_ASSOCIATED));
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_LINK_REVIEW_OPENED).count());
        assertEquals(1, service.studyLinkReviews(ORG).size());
    }

    /** An unprivileged role cannot intake and a stale version cannot schedule over a newer revision. */
    @Test
    void wrongRoleAndStaleVersionCannotAdvanceTheOrder() {
        ApiException forbidden = assertThrows(ApiException.class,
                () -> service.intake(intake(Urgency.ROUTINE, QUESTION, OWNER, NO_ROLE)));
        assertEquals(403, forbidden.status());
        assertEquals("FORBIDDEN", forbidden.code());

        ImagingOrder received = service.intake(intake(Urgency.ROUTINE, QUESTION, OWNER, STAFF));
        ApiException stale = assertThrows(ApiException.class,
                () -> service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER,
                        received.version() + 1, SCHEDULED, Modality.CT)));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());

        ImagingOrder unchanged = service.read(ORG, ORDER);
        assertEquals(OrderState.INTAKE, unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /** An order is concealed from a different tenant even when the local identifier is known. */
    @Test
    void otherTenantCannotReadTheOrder() {
        ImagingOrder received = service.intake(intake(Urgency.ROUTINE, QUESTION, OWNER, STAFF));
        ApiException concealed = assertThrows(ApiException.class,
                () -> service.read(OTHER_ORG, received.orderId()));
        assertEquals(404, concealed.status());
        assertEquals("NOT_FOUND", concealed.code());
    }
}
