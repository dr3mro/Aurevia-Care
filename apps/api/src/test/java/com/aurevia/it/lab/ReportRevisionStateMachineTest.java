package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;
import com.aurevia.lab.result.ReportRevision;
import com.aurevia.lab.result.LabTypes.VerificationState;

/** LAB-02 and CLN-05: issued revisions are append-only and entered-in-error is terminal. */
class ReportRevisionStateMachineTest {

    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static LabObservation observation() {
        return new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal("90"), Qualifier.NONE, "mg/dL", null, null, null,
                AbnormalFlag.NONE, "synthetic-analyser", "synthetic-verifier");
    }

    private static ReportRevision revision(int version, VerificationState state) {
        return new ReportRevision("org-a", "report-a", version, version == 1 ? null : version - 1,
                "patient-a", "specimen-a", "order-a", state, List.of(observation()),
                "synthetic-collector", "synthetic-verifier", AT, null, null, null);
    }

    @Test
    void legalTransitionsFollowTheVerificationStateMachine() {
        assertTrue(VerificationState.PRELIMINARY.canTransitionTo(VerificationState.FINAL));
        assertTrue(VerificationState.PRELIMINARY.canTransitionTo(VerificationState.ENTERED_IN_ERROR));
        assertTrue(VerificationState.FINAL.canTransitionTo(VerificationState.CORRECTED));
        assertTrue(VerificationState.FINAL.canTransitionTo(VerificationState.ENTERED_IN_ERROR));
        assertTrue(VerificationState.CORRECTED.canTransitionTo(VerificationState.CORRECTED));
        assertTrue(VerificationState.CORRECTED.canTransitionTo(VerificationState.ENTERED_IN_ERROR));

        assertFalse(VerificationState.PRELIMINARY.canTransitionTo(VerificationState.CORRECTED));
        assertFalse(VerificationState.PRELIMINARY.canTransitionTo(VerificationState.PRELIMINARY));
        assertFalse(VerificationState.FINAL.canTransitionTo(VerificationState.FINAL));
        assertFalse(VerificationState.FINAL.canTransitionTo(VerificationState.PRELIMINARY));
        assertFalse(VerificationState.CORRECTED.canTransitionTo(VerificationState.FINAL));
        assertFalse(VerificationState.CORRECTED.canTransitionTo(VerificationState.PRELIMINARY));
        for (VerificationState target : VerificationState.values()) {
            assertFalse(VerificationState.ENTERED_IN_ERROR.canTransitionTo(target));
        }
    }

    @Test
    void onlyIssuedRevisionsRouteAndReasonBearingStatesRequireAReason() {
        assertTrue(VerificationState.FINAL.isIssued());
        assertTrue(VerificationState.CORRECTED.isIssued());
        assertFalse(VerificationState.PRELIMINARY.isIssued());
        assertFalse(VerificationState.ENTERED_IN_ERROR.isIssued());
        assertTrue(VerificationState.ENTERED_IN_ERROR.isTerminal());
        assertFalse(VerificationState.FINAL.isTerminal());
        assertTrue(VerificationState.CORRECTED.requiresReason());
        assertTrue(VerificationState.ENTERED_IN_ERROR.requiresReason());
        assertFalse(VerificationState.FINAL.requiresReason());
        assertEquals("preliminary", VerificationState.PRELIMINARY.wire());
        assertEquals("final", VerificationState.FINAL.wire());
        assertEquals("corrected", VerificationState.CORRECTED.wire());
        assertEquals("entered_in_error", VerificationState.ENTERED_IN_ERROR.wire());
    }

    @Test
    void correctionLinksThePriorRevisionWithoutMutatingIt() {
        ReportRevision original = revision(1, VerificationState.FINAL);
        ReportRevision correction = new ReportRevision("org-a", "report-a", 2, 1,
                "patient-a", "specimen-a", "order-a", VerificationState.CORRECTED,
                List.of(observation()), "synthetic-verifier", "synthetic-verifier", AT.plusSeconds(5),
                "transcription fix", 3, null);

        assertEquals(Integer.valueOf(1), correction.supersedesVersion());
        assertEquals(VerificationState.FINAL, original.state());
        assertEquals(1, original.version());
        assertEquals("transcription fix", correction.reason());
        assertEquals(Integer.valueOf(3), correction.rangeVersion());
        assertNotEquals(original, correction);
    }

    @Test
    void revisionDefensivelyCopiesObservationsOnStoreAndRead() {
        List<LabObservation> mutable = new ArrayList<>();
        mutable.add(observation());
        ReportRevision revision = new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                mutable, "synthetic-collector", null, null, null, null, null);
        mutable.clear();
        assertEquals(1, revision.observations().size());

        List<LabObservation> exposed = revision.observations();
        assertThrows(UnsupportedOperationException.class, () -> exposed.add(observation()));
        assertEquals(1, revision.observations().size());
    }

    @Test
    void invalidRevisionInputsAreRejected() {
        assertThrows(ApiException.class, () -> new ReportRevision(null, "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", null, 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", -1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 2, 3,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                null, "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", null, "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", null, VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", " ", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", " ", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), " ", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), null, null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision(" ", "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", " ", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 0, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 2, 2,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", null,
                List.of(), "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                "patient-a", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                null, "actor", null, null, null, null, null));
        assertThrows(ApiException.class, () -> new ReportRevision("org-a", "report-a", 1, null,
                " ", "specimen-a", "order-a", VerificationState.PRELIMINARY,
                List.of(), "actor", null, null, null, null, null));

        ReportRevision first = revision(1, VerificationState.FINAL);
        ReportRevision second = revision(1, VerificationState.FINAL);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(first.toString().contains("report-a"));
    }
}
