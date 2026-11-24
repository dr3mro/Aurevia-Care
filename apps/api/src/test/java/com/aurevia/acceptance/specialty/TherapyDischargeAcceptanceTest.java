package com.aurevia.acceptance.specialty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;
import com.aurevia.therapy.InMemoryTherapyStore;
import com.aurevia.therapy.TherapyContract.AckRequest;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.SummaryRequest;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyService;

/**
 * Gate G4 acceptance for physical-therapy closure.
 *
 * <p>Requirements proved: PT-01 (accept a referral into an episode, sign
 * session and discharge notes, send the approved summary to the referrer and
 * record the return acknowledgment) and G4 (a therapy episode ends with a signed
 * discharge and a closed referral). In this domain "referral closure" is the
 * episode reaching {@code DISCHARGED} after a signed discharge note; there is no
 * separate referral status entity. All identifiers and content are synthetic.</p>
 */
class TherapyDischargeAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "syn-pt-clinician";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String REFERRAL = "referral-g4-therapy";
    private static final String REFERRER_ORG = "referring-org";
    private static final String REFERRER = "referrer-a";
    private static final String CLINICIAN_ROLE = "pt_clinician";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private InMemoryTherapyStore store;
    private TherapyService service;
    private Actor clinician;

    @BeforeEach
    void setUp() {
        store = new InMemoryTherapyStore();
        service = new TherapyService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        clinician = Actor.of(ORG, SUBJECT, CLINICIAN_ROLE);
        TenantContext.set(new TenantContext(ORG, SUBJECT, "PURPOSE", "corr-g4-therapy"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void episodeEndsWithSignedDischargeAndClosedReferral() {
        // PT-01 / G4: accepting a referral opens an ACTIVE episode bound to that
        // referral; evaluation opens treatment; signing a session is the
        // precondition for a signed discharge that closes the episode.
        Episode accepted = service.acceptReferral(clinician, new ReferralRequest(
                REFERRAL, PATIENT, REFERRER_ORG, REFERRER, "2026-11-20T09:00:00Z", "knee pain"));
        String id = accepted.id();
        assertEquals(EpisodeState.ACTIVE, accepted.state());
        assertEquals(REFERRAL, accepted.referralId());

        service.recordEvaluation(clinician, id, new EvaluationRequest(
                List.of("knee pain"),
                List.of(new Measurement("ROM", "60", "degrees", "2026-11-20T09:30:00Z")),
                List.of(new Goal("walk independently", "independent", "2026-12-20")),
                3, 6), currentVersion(id));
        assertEquals(EpisodeState.IN_TREATMENT, service.get(clinician, id).episode().state());

        service.recordNote(clinician, id, new NoteRequest(
                NoteKind.SESSION, "session content", "2026-11-21T10:00:00Z", null), currentVersion(id));
        ClinicalNote session = service.signNote(clinician, id, NoteKind.SESSION, 1,
                "I attest to this session", currentVersion(id));
        assertTrue(session.signed());

        service.recordNote(clinician, id, new NoteRequest(
                NoteKind.DISCHARGE, "discharge content", "2026-11-30T10:00:00Z", null), currentVersion(id));
        ClinicalNote discharge = service.signNote(clinician, id, NoteKind.DISCHARGE, 1,
                "I attest to this discharge", currentVersion(id));
        assertTrue(discharge.signed());
        assertEquals(NoteKind.DISCHARGE, discharge.kind());

        // PT-01: the approved summary reaches the verified referrer and the
        // referrer's return acknowledgment binds the exact summary revision.
        service.sendSummary(clinician, id, new SummaryRequest(
                "approved therapy summary", REFERRER_ORG, REFERRER), currentVersion(id));
        service.acknowledgeReturn(clinician, id, new AckRequest(
                1, "2026-12-01T09:00:00Z", REFERRER, "received"), currentVersion(id));

        TherapyAggregate closed = service.get(clinician, id);
        assertEquals(EpisodeState.DISCHARGED, closed.episode().state());
        assertEquals(REFERRAL, closed.episode().referralId());
        assertEquals(2, closed.notes().size());
        assertTrue(closed.notes().stream().allMatch(ClinicalNote::signed));
        assertNotNull(closed.summary());
        assertEquals(1, closed.summary().version());
        assertNotNull(closed.acknowledgment());
        assertEquals(REFERRER, closed.acknowledgment().receivedBy());
        assertEquals(1, closed.acknowledgment().summaryVersion());

        // The service is stateless: the closed state lives in the store, not in
        // one service instance. Restart durability is proved separately by the
        // JdbcTherapyStore integration test (not by this in-memory fixture).
        assertEquals(EpisodeState.DISCHARGED,
                new TherapyService(store, Clock.fixed(NOW, ZoneOffset.UTC))
                        .get(clinician, id).episode().state());

        // A discharged episode is read-only: a later draft cannot reopen it.
        ApiException denial = assertThrows(ApiException.class, () -> service.recordNote(clinician, id,
                new NoteRequest(NoteKind.PROGRESS, "late note", "2026-12-01T10:00:00Z", null),
                currentVersion(id)));
        assertEquals(409, denial.status());
        assertEquals("INVALID_TRANSITION", denial.code());
    }

    @Test
    void dischargeWithoutASignedSessionIsRefused() {
        // PT-01 negative path: the discharge preconditions keep an episode open
        // until a session note has been signed by the treating clinician.
        Episode accepted = service.acceptReferral(clinician, new ReferralRequest(
                "referral-g4-no-session", PATIENT, REFERRER_ORG, REFERRER,
                "2026-11-20T09:00:00Z", "shoulder pain"));
        String id = accepted.id();
        service.recordEvaluation(clinician, id, new EvaluationRequest(
                List.of("shoulder pain"), List.of(), List.of(), 2, 4), currentVersion(id));
        service.recordNote(clinician, id, new NoteRequest(
                NoteKind.DISCHARGE, "premature discharge", "2026-11-30T10:00:00Z", null),
                currentVersion(id));

        ApiException refused = assertThrows(ApiException.class,
                () -> service.signNote(clinician, id, NoteKind.DISCHARGE, 1,
                        "I attest to this discharge", currentVersion(id)));
        assertEquals(409, refused.status());
        assertEquals(EpisodeState.IN_TREATMENT, service.get(clinician, id).episode().state());
    }

    private int currentVersion(String episodeId) {
        return service.get(clinician, episodeId).episode().version();
    }
}
