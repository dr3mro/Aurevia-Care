package com.aurevia.it.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.therapy.InMemoryTherapyStore;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.OutcomeRequest;
import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.ScaleRequest;
import com.aurevia.therapy.TherapyService;

/**
 * S-G4-02 acceptance for the versioned outcome scales of PT-01: a reading must
 * bind an approved scale revision and its exact unit, and a new scale version
 * must not rewrite history. All fixtures are synthetic.
 */
class OutcomeScaleTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private final Actor admin = Actor.of(ORG, "admin", TherapyService.TENANT_ADMIN);
    private final Actor governor = Actor.of(ORG, "gov", TherapyService.CLINICAL_GOVERNANCE);
    private final Actor pt = Actor.of(ORG, "pt", TherapyService.PT_CLINICIAN);

    private TherapyService service;
    private Episode episode;

    /** Build the service and a baseline episode with an evaluation. */
    @BeforeEach
    void setUp() {
        service = new TherapyService(new InMemoryTherapyStore(),
                Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));
        episode = service.acceptReferral(pt, new ReferralRequest(
                "REF-1", PATIENT, "org-ref", "ref", "2026-11-18T09:00:00Z", "q"));
        service.recordEvaluation(pt, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(new Measurement("ROM", "90", "degrees", "2026-11-19T09:00:00Z")),
                List.of(new Goal("g", "1", "2026-12-19")), 2, 6), episode.version());
    }

    /** Define and approve a scale version, returning it. */
    private OutcomeScale approved(String key, String unit, List<String> values, String versionLabel) {
        service.defineOutcomeScale(admin, new ScaleRequest(key, versionLabel, unit, values));
        int latest = service.versions(admin, key).size();
        return service.approveOutcomeScale(governor, key, latest);
    }

    @Test
    void versionsAreSequentialAndImmutableAndApprovalRequiresGovernance() {
        OutcomeScale v1 = service.defineOutcomeScale(admin,
                new ScaleRequest("koos", "score v1", "points", List.of("0", "50", "100")));
        assertFalse(v1.approved());
        assertEquals(1, v1.version());
        assertEquals("points", v1.unit());
        assertEquals(List.of("0", "50", "100"), v1.permittedValues());

        assertEquals(403, status(() -> service.approveOutcomeScale(pt, "koos", 1)));
        assertEquals(404, status(() -> service.approveOutcomeScale(governor, "absent", 1)));
        OutcomeScale approved = service.approveOutcomeScale(governor, "koos", 1);
        assertTrue(approved.approved());
        assertEquals("gov", approved.approvedBy());
        assertEquals(409, status(() -> service.approveOutcomeScale(governor, "koos", 1)));

        // A new version of the same clinical scale is expected, not a duplicate.
        OutcomeScale v2 = service.defineOutcomeScale(admin,
                new ScaleRequest("koos", "score v2", "points", List.of("0", "25", "50", "75", "100")));
        assertEquals(2, v2.version());
        assertEquals("score v1", service.versions(admin, "koos").get(0).description());
        assertEquals("score v2", service.versions(admin, "koos").get(1).description());
        assertNotEquals(v1.version(), v2.version());
    }

    @Test
    void readingRequiresAnApprovedScaleAndMatchingUnitAndPermittedValue() {
        final int v = service.get(pt, episode.id()).episode().version();
        // Failed recordings do not advance the episode version, so one precondition serves all.
        assertEquals(422, status(() -> service.recordOutcome(pt, episode.id(),
                new OutcomeRequest("koos", 1, "50", "points", "2026-11-19T10:30:00Z"), v)));

        approved("koos", "points", List.of("0", "50", "100"), "score");

        assertEquals(422, status(() -> service.recordOutcome(pt, episode.id(),
                new OutcomeRequest("koos", 2, "50", "points", "2026-11-19T10:30:00Z"), v)));
        assertEquals(422, status(() -> service.recordOutcome(pt, episode.id(),
                new OutcomeRequest("koos", 1, "37", "points", "2026-11-19T10:30:00Z"), v)));
        assertEquals(422, status(() -> service.recordOutcome(pt, episode.id(),
                new OutcomeRequest("koos", 1, "50", "degrees", "2026-11-19T10:30:00Z"), v)));

        assertEquals("50", service.recordOutcome(pt, episode.id(),
                new OutcomeRequest("koos", 1, "50", "points", "2026-11-19T10:30:00Z"), v).value());
    }

    @Test
    void readingsBindTheExactScaleVersionAndUnitForTrending() {
        OutcomeScale v1 = approved("koos", "points", List.of("0", "50", "100"), "score v1");
        int v = service.get(pt, episode.id()).episode().version();
        service.recordOutcome(pt, episode.id(), new OutcomeRequest(
                "koos", 1, "50", "points", "2026-11-19T10:30:00Z"), v);

        OutcomeScale v2 = service.defineOutcomeScale(admin,
                new ScaleRequest("koos", "score v2", "degrees", List.of("0", "100")));
        service.approveOutcomeScale(governor, "koos", v2.version());

        v = service.get(pt, episode.id()).episode().version();
        service.recordOutcome(pt, episode.id(), new OutcomeRequest(
                "koos", 2, "100", "degrees", "2026-12-19T10:30:00Z"), v);

        var readings = service.get(pt, episode.id()).readings();
        assertEquals(2, readings.size());
        assertEquals(1, readings.get(0).scaleVersion());
        assertEquals("points", readings.get(0).unit());
        assertEquals(2, readings.get(1).scaleVersion());
        assertEquals("degrees", readings.get(1).unit());
        assertNotEquals(readings.get(0).unit(), readings.get(1).unit());
        assertEquals("score v1", v1.description());
    }

    @Test
    void scaleDefinitionsAreDefensivelyCopied() {
        List<String> values = new ArrayList<>(List.of("0", "50"));
        OutcomeScale scale = service.defineOutcomeScale(admin,
                new ScaleRequest("x", "d", "u", values));
        values.clear();
        assertEquals(2, scale.permittedValues().size());
        assertThrows(UnsupportedOperationException.class, () -> scale.permittedValues().add("x"));
    }

    @Test
    void invalidScaleDefinitionsAreRejected() {
        assertEquals(422, status(() -> service.defineOutcomeScale(admin,
                new ScaleRequest(" ", "d", "u", List.of("1")))));
        assertEquals(422, status(() -> service.defineOutcomeScale(admin,
                new ScaleRequest("x", " ", "u", List.of("1")))));
        assertEquals(422, status(() -> service.defineOutcomeScale(admin,
                new ScaleRequest("x", "d", " ", List.of("1")))));
        assertEquals(422, status(() -> service.defineOutcomeScale(admin,
                new ScaleRequest("x", "d", "u", List.of()))));
    }

    /** Extract the HTTP status of an expected domain failure. */
    private static int status(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertTrue(exception.status() >= 400);
        return exception.status();
    }

    /** Allow lambdas that throw to be passed to {@link #status(ThrowingRunnable)}. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
