package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.task.TransmissionState;

/** XCH-02 transmission dimension: pending → sent → delivered or failed. */
class TransmissionStateTest {

    @Test
    void exposesWireFormAndParsesIt() {
        for (TransmissionState state : TransmissionState.values()) {
            assertEquals(state, TransmissionState.from(state.wire()));
            assertEquals(state.wire(), state.name().toLowerCase(Locale.ROOT));
        }
        assertEquals("pending", TransmissionState.PENDING.wire());
        assertThrows(IllegalArgumentException.class, () -> TransmissionState.from("unknown"));
    }

    @Test
    void transitionMatrixMatchesXch02() {
        for (TransmissionState from : TransmissionState.values()) {
            for (TransmissionState to : TransmissionState.values()) {
                boolean expected = switch (from) {
                    case PENDING -> to == TransmissionState.SENT || to == TransmissionState.FAILED;
                    case SENT -> to == TransmissionState.DELIVERED || to == TransmissionState.FAILED;
                    default -> false;
                };
                assertEquals(expected, from.canTransitionTo(to), from + "->" + to);
            }
        }
        assertTrue(TransmissionState.SENT.canTransitionTo(TransmissionState.DELIVERED));
        assertFalse(TransmissionState.DELIVERED.canTransitionTo(TransmissionState.SENT));
        assertFalse(TransmissionState.FAILED.canTransitionTo(TransmissionState.SENT));
    }
}
