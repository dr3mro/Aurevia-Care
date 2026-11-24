package com.aurevia.radiology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.aurevia.radiology.RadCommands.AccessionCommand;
import com.aurevia.radiology.RadCommands.AssociateStudyCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.ScheduleCommand;
import com.aurevia.radiology.RadCommands.StudyReviewCommand;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.StudyLinkReviewStatus;
import com.aurevia.radiology.RadTypes.Urgency;

/** RAD-01 study-link verification: mismatches queue a review and never link; re-links keep history. */
class StudyLinkReviewTest {

    private static final String ORG = "org-rad";
    private static final String OTHER_ORG = "org-other";
    private static final String ORDER = "order-rad";
    private static final String PATIENT = "patient-rad";
    private static final String OTHER_PATIENT = "patient-other";
    private static final String ACC = "ACC-2001";
    private static final String OTHER_ACC = "ACC-9999";
    private static final String ISSUER = "ISSUER-RAD";
    private static final String UID = "1.2.840.113619.2.55.3.2001";
    private static final String OTHER_UID = "1.2.840.113619.2.55.3.9999";
    private static final String WORKSTATION = "pacs-workstation-rad";
    private static final String ACTOR = "tech-rad";
    private static final String REVIEWER = "radiologist-reviewer";
    private static final String REFERRER = "referrer-rad";
    private static final String OWNER = "owner-rad";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant SCHEDULED = AT.plus(Duration.ofDays(1));

    private static final Set<String> STAFF = Set.of("radiology_staff");
    private static final Set<String> NO_ROLE = Set.of("finance");

    private ImagingService service;

    @BeforeEach
    void setUp() {
        service = new ImagingService(
                new RadPolicyCatalog(List.of(Modality.CT, Modality.MRI), List.of()),
                Clock.fixed(AT, ZoneOffset.UTC));
    }

    private ImagingOrder accessioned() {
        ImagingOrder order = service.intake(new IntakeCommand(ORG, ACTOR, STAFF, ORDER, PATIENT,
                "enc-1", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism", "clinic",
                "radiology", OWNER, "rad-queue", REFERRER, Modality.CT));
        order = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                SCHEDULED, Modality.CT));
        return service.accession(new AccessionCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                ACC, ISSUER));
    }

    private static AssociateStudyCommand associate(int version, String patient, String accession,
                                                    String studyUid) {
        return new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER, version, studyUid, patient,
                accession, WORKSTATION);
    }

    private static StudyReviewCommand review(String organizationId, String actor, Set<String> roles,
                                             String orderId, String studyUid, String resolution) {
        return new StudyReviewCommand(organizationId, actor, roles, orderId, studyUid, resolution);
    }

    @Test
    void mismatchNeverLinksAndOpensOneOpenReview() {
        ImagingOrder order = accessioned();

        ApiException wrongPatient = assertThrows(ApiException.class,
                () -> service.associateStudy(associate(order.version(), OTHER_PATIENT, ACC, UID)));
        assertEquals(422, wrongPatient.status());
        assertEquals("LABEL_MISMATCH", wrongPatient.code());
        ApiException wrongAccession = assertThrows(ApiException.class,
                () -> service.associateStudy(associate(order.version(), PATIENT, OTHER_ACC, UID)));
        assertEquals(422, wrongAccession.status());

        ImagingOrder unchanged = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, unchanged.state());
        assertEquals(3, unchanged.version());
        assertNull(unchanged.studyInstanceUid());
        assertNull(unchanged.verifiedPatientId());
        assertTrue(service.events(ORG).stream()
                .noneMatch(event -> event.type() == RadEventType.STUDY_ASSOCIATED));

        List<StudyLinkReview> queue = service.studyLinkReviews(ORG);
        assertEquals(1, queue.size());
        StudyLinkReview item = queue.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals(ORDER, item.orderId());
        assertEquals(UID, item.studyInstanceUid());
        assertEquals(OTHER_PATIENT, item.attemptedPatientId());
        assertEquals(ACC, item.attemptedAccession());
        assertEquals(PATIENT, item.expectedPatientId());
        assertEquals(ACC, item.expectedAccession());
        assertEquals(StudyLinkReviewStatus.OPEN, item.status());
        assertNull(item.reviewer());
        assertNull(item.resolution());
        assertNull(item.resolvedAt());
        assertEquals(AT, item.openedAt());

        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_LINK_REVIEW_OPENED).count());
        RadEvent opened = service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_LINK_REVIEW_OPENED)
                .findFirst().orElseThrow();
        assertEquals(UID, opened.destination());
        assertEquals(PATIENT, opened.patientId());
        assertTrue(service.studyLinkReviews(OTHER_ORG).isEmpty());
    }

    @Test
    void reLinkAfterReviewKeepsTheAppendOnlyOrderHistory() {
        ImagingOrder order = accessioned();
        assertThrows(ApiException.class,
                () -> service.associateStudy(associate(order.version(), OTHER_PATIENT, ACC, UID)));

        StudyLinkReview resolved = service.resolveStudyLinkReview(
                review(ORG, REVIEWER, STAFF, ORDER, UID, "labels corrected after PACS verification"));
        assertEquals(StudyLinkReviewStatus.RESOLVED, resolved.status());
        assertEquals(REVIEWER, resolved.reviewer());
        assertEquals("labels corrected after PACS verification", resolved.resolution());
        assertEquals(AT, resolved.resolvedAt());
        assertEquals(AT, resolved.openedAt());
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_LINK_REVIEW_RESOLVED).count());

        ImagingOrder linked = service.associateStudy(associate(order.version(), PATIENT, ACC, UID));
        assertEquals(OrderState.STUDY_ASSOCIATED, linked.state());
        assertEquals(4, linked.version());
        assertEquals(UID, linked.studyInstanceUid());

        List<ImagingOrder> history = service.history(ORG, ORDER);
        assertEquals(4, history.size());
        assertEquals(List.of(OrderState.INTAKE, OrderState.SCHEDULED, OrderState.ACCESSIONED,
                OrderState.STUDY_ASSOCIATED), history.stream().map(ImagingOrder::state).toList());
        assertEquals(List.of(1, 2, 3, 4), history.stream().map(ImagingOrder::version).toList());
        assertNull(history.get(0).studyInstanceUid());
        assertNull(history.get(2).studyInstanceUid());
        assertEquals(Modality.CT, history.get(1).modality());
        assertEquals("rule out pulmonary embolism", history.get(0).clinicalQuestion());
        assertEquals(Urgency.ROUTINE, history.get(0).urgency());

        List<StudyLinkReview> queue = service.studyLinkReviews(ORG);
        assertEquals(1, queue.size());
        assertEquals(StudyLinkReviewStatus.RESOLVED, queue.get(0).status());
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_ASSOCIATED).count());
    }

    @Test
    void intakeRecordsModalityUrgencyAndClinicalQuestion() {
        ImagingOrder order = service.intake(new IntakeCommand(ORG, ACTOR, STAFF, ORDER, PATIENT,
                "enc-2", "MRI-BRAIN", Urgency.STAT, "assess acute stroke", "clinic", "radiology",
                OWNER, "rad-queue", REFERRER, Modality.MRI));
        assertEquals(Modality.MRI, order.modality());
        assertEquals(Urgency.STAT, order.urgency());
        assertEquals("assess acute stroke", order.clinicalQuestion());
        assertEquals(OrderState.INTAKE, order.state());

        ImagingOrder scheduled = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER,
                order.version(), SCHEDULED, Modality.MRI));
        assertEquals(Modality.MRI, scheduled.modality());

        ImagingService narrow = new ImagingService(
                new RadPolicyCatalog(List.of(Modality.CT), List.of()), Clock.fixed(AT, ZoneOffset.UTC));
        ApiException unsupported = assertThrows(ApiException.class,
                () -> narrow.intake(new IntakeCommand(ORG, ACTOR, STAFF, "order-narrow", PATIENT,
                        "enc-3", "MRI-BRAIN", Urgency.ROUTINE, "question", "clinic", "radiology",
                        OWNER, "rad-queue", REFERRER, Modality.MRI)));
        assertEquals(422, unsupported.status());
        assertEquals("UNSUPPORTED_MODALITY", unsupported.code());

        ImagingOrder legacy = service.intake(new IntakeCommand(ORG, ACTOR, STAFF, "order-legacy",
                PATIENT, "enc-4", "XRAY-CHEST", Urgency.ROUTINE, "question", "clinic", "radiology",
                OWNER, "rad-queue", REFERRER));
        assertNull(legacy.modality());
    }

    @Test
    void reviewResolutionValidatesIdentityRoleAndState() {
        ImagingOrder order = accessioned();
        assertThrows(ApiException.class,
                () -> service.associateStudy(associate(order.version(), OTHER_PATIENT, ACC, UID)));

        assertEquals(422, code(() -> service.resolveStudyLinkReview(
                review(" ", REVIEWER, STAFF, ORDER, UID, "resolution"))));
        assertEquals(422, code(() -> service.resolveStudyLinkReview(
                review(ORG, " ", STAFF, ORDER, UID, "resolution"))));
        assertEquals(422, code(() -> service.resolveStudyLinkReview(
                review(ORG, REVIEWER, STAFF, " ", UID, "resolution"))));
        assertEquals(422, code(() -> service.resolveStudyLinkReview(
                review(ORG, REVIEWER, STAFF, ORDER, " ", "resolution"))));
        assertEquals(422, code(() -> service.resolveStudyLinkReview(
                review(ORG, REVIEWER, STAFF, ORDER, UID, " "))));
        assertEquals(403, code(() -> service.resolveStudyLinkReview(
                review(ORG, REVIEWER, NO_ROLE, ORDER, UID, "resolution"))));
        assertEquals(404, code(() -> service.resolveStudyLinkReview(
                review(OTHER_ORG, REVIEWER, STAFF, ORDER, UID, "resolution"))));
        assertEquals(404, code(() -> service.resolveStudyLinkReview(
                review(ORG, REVIEWER, STAFF, ORDER, OTHER_UID, "resolution"))));

        service.resolveStudyLinkReview(review(ORG, REVIEWER, STAFF, ORDER, UID, "first resolution"));
        ApiException already = assertThrows(ApiException.class, () -> service.resolveStudyLinkReview(
                review(ORG, REVIEWER, STAFF, ORDER, UID, "second resolution")));
        assertEquals(409, already.status());
        assertEquals("REVIEW_ALREADY_RESOLVED", already.code());
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_LINK_REVIEW_RESOLVED).count());
    }

    @Test
    void studyLinkReviewRejectsInvalidFactsAndExposesWireForms() {
        StudyLinkReview valid = new StudyLinkReview(ORG, ORDER, UID, OTHER_PATIENT, ACC, PATIENT,
                ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null);
        assertEquals(ORG, valid.organizationId());
        assertEquals(StudyLinkReviewStatus.OPEN, valid.status());
        StudyLinkReview resolved = valid.resolve(REVIEWER, "resolution", AT);
        assertEquals(StudyLinkReviewStatus.RESOLVED, resolved.status());
        assertEquals(REVIEWER, resolved.reviewer());
        assertNotNull(resolved.resolvedAt());

        assertEquals(422, codeOf(() -> new StudyLinkReview(" ", ORDER, UID, OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(null, ORDER, UID, OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(ORG, " ", UID, OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(ORG, null, UID, OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(ORG, ORDER, " ", OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(ORG, ORDER, null, OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(ORG, ORDER, UID, OTHER_PATIENT, ACC,
                PATIENT, ACC, null, null, null, AT, null)));
        assertEquals(422, codeOf(() -> new StudyLinkReview(ORG, ORDER, UID, OTHER_PATIENT, ACC,
                PATIENT, ACC, StudyLinkReviewStatus.OPEN, null, null, null, null)));

        assertEquals("open", StudyLinkReviewStatus.OPEN.wire());
        assertEquals("resolved", StudyLinkReviewStatus.RESOLVED.wire());
    }

    private static int code(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    private static int codeOf(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }
}
