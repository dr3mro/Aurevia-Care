package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.task.ClinicalReviewState;

/** XCH-02 clinical review dimension: unreviewed → acknowledged → action pending → resolved. */
class ClinicalReviewStateTest {

    @Test
    void exposesWireFormAndParsesIt() {
        for (ClinicalReviewState state : ClinicalReviewState.values()) {
            assertEquals(state, ClinicalReviewState.from(state.wire()));
            assertEquals(state.wire(), state.name().toLowerCase(Locale.ROOT));
        }
        assertEquals("unreviewed", ClinicalReviewState.UNREVIEWED.wire());
        assertThrows(IllegalArgumentException.class, () -> ClinicalReviewState.from("unknown"));
    }

    @Test
    void transitionMatrixIsTheStrictHumanChain() {
        for (ClinicalReviewState from : ClinicalReviewState.values()) {
            for (ClinicalReviewState to : ClinicalReviewState.values()) {
                boolean expected = switch (from) {
                    case UNREVIEWED -> to == ClinicalReviewState.ACKNOWLEDGED;
                    case ACKNOWLEDGED -> to == ClinicalReviewState.ACTION_PENDING;
                    case ACTION_PENDING -> to == ClinicalReviewState.RESOLVED;
                    default -> false;
                };
                assertEquals(expected, from.canTransitionTo(to), from + "->" + to);
            }
        }
        assertTrue(ClinicalReviewState.UNREVIEWED.canTransitionTo(ClinicalReviewState.ACKNOWLEDGED));
        assertFalse(ClinicalReviewState.UNREVIEWED.canTransitionTo(ClinicalReviewState.RESOLVED));
        assertFalse(ClinicalReviewState.ACKNOWLEDGED.canTransitionTo(ClinicalReviewState.RESOLVED));
        assertFalse(ClinicalReviewState.RESOLVED.canTransitionTo(ClinicalReviewState.UNREVIEWED));
    }
}
