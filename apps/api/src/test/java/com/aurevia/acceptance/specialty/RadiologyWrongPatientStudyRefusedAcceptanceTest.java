package com.aurevia.acceptance.specialty;

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
import com.aurevia.radiology.StudyLinkReview;

/**
 * Gate G4 acceptance for the radiology study-link boundary.
 *
 * <p>Requirements proved: RAD-01 (link an external Study Instance UID only after
 * verifying patient and accession) and G4 (a wrong-patient study association is
 * refused). A mismatch must not mutate the order, must open a review and must
 * never emit a {@code STUDY_ASSOCIATED} fact. All identifiers are synthetic;
 * the kernel carries org, actor and roles explicitly on each command.</p>
 */
class RadiologyWrongPatientStudyRefusedAcceptanceTest {

    private static final String ORG = "33333333-3333-3333-3333-333333333333";
    private static final String OTHER_ORG = "44444444-4444-4444-4444-444444444444";
    private static final String ORDER = "order-g4-rad";
    private static final String PATIENT = "patient-g4-rad";
    private static final String OTHER_PATIENT = "patient-g4-other";
    private static final String ACC = "ACC-G4-1";
    private static final String OTHER_ACC = "ACC-G4-OTHER";
    private static final String ISSUER = "ISSUER-G4";
    private static final String UID = "1.2.840.113619.2.55.3.g4.one";
    private static final String OTHER_UID = "1.2.840.113619.2.55.3.g4.two";
    private static final String WORKSTATION = "pacs-workstation-g4";
    private static final String ACTOR = "tech-g4";
    private static final String OWNER = "owner-g4";
    private static final String REFERRER = "referrer-g4";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant SCHEDULED = AT.plus(Duration.ofDays(1));
    private static final Set<String> STAFF = Set.of("radiology_staff");

    private ImagingService service;

    @BeforeEach
    void setUp() {
        service = new ImagingService(new RadPolicyCatalog(List.of(Modality.CT), List.of()),
                Clock.fixed(AT, ZoneOffset.UTC));
    }

    @Test
    void wrongPatientStudyIsRefusedBeforeAssociation() {
        // RAD-01 / G4: the study carries another patient's label, so association
        // is refused with 422 LABEL_MISMATCH and an OPEN review is recorded.
        ImagingOrder accessioned = accessioned();

        ApiException refused = assertThrows(ApiException.class,
                () -> service.associateStudy(new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER,
                        accessioned.version(), UID, OTHER_PATIENT, ACC, WORKSTATION)));
        assertEquals(422, refused.status());
        assertEquals("LABEL_MISMATCH", refused.code());

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
        assertEquals(UID, item.studyInstanceUid());
        assertEquals(OTHER_PATIENT, item.attemptedPatientId());
        assertEquals(ACC, item.attemptedAccession());
        assertEquals(PATIENT, item.expectedPatientId());
        assertEquals("open", item.status().wire());

        RadEvent opened = service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_LINK_REVIEW_OPENED)
                .findFirst().orElseThrow();
        assertEquals(UID, opened.destination());
        assertEquals(PATIENT, opened.patientId());
    }

    @Test
    void wrongAccessionStaleVersionAndOtherTenantAreRefusedThenCorrectLabelsLink() {
        // RAD-01: the accession is part of the same verification; a stale version
        // or a different tenant is also refused before a correct link succeeds.
        ImagingOrder accessioned = accessioned();

        assertEquals("LABEL_MISMATCH", assertThrows(ApiException.class,
                () -> service.associateStudy(new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER,
                        accessioned.version(), OTHER_UID, PATIENT, OTHER_ACC, WORKSTATION))).code());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.associateStudy(new AssociateStudyCommand(ORG, ACTOR, Set.of("finance"),
                        ORDER, accessioned.version(), OTHER_UID, PATIENT, ACC, WORKSTATION))).status());
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.associateStudy(new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER,
                        accessioned.version() + 1, OTHER_UID, PATIENT, ACC, WORKSTATION))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.associateStudy(new AssociateStudyCommand(OTHER_ORG, ACTOR, STAFF, ORDER,
                        accessioned.version(), OTHER_UID, PATIENT, ACC, WORKSTATION))).status());

        ImagingOrder linked = service.associateStudy(new AssociateStudyCommand(ORG, ACTOR, STAFF,
                ORDER, accessioned.version(), UID, PATIENT, ACC, WORKSTATION));
        assertEquals(OrderState.STUDY_ASSOCIATED, linked.state());
        assertEquals(4, linked.version());
        assertEquals(UID, linked.studyInstanceUid());
        assertEquals(PATIENT, linked.verifiedPatientId());
        assertEquals(ACC, linked.verifiedAccession());
    }

    private ImagingOrder accessioned() {
        ImagingOrder order = service.intake(new IntakeCommand(ORG, ACTOR, STAFF, ORDER, PATIENT,
                "enc-g4", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism", "clinic",
                "radiology", OWNER, "rad-queue", REFERRER, Modality.CT));
        order = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                SCHEDULED, Modality.CT));
        return service.accession(new AccessionCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                ACC, ISSUER));
    }
}
