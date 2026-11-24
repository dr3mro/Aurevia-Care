package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** CLN-01 state machine: every transition, reason and terminal rule. */
class AppointmentStateTest {

    @Test
    void exposesWireFormAndParsesIt() {
        for (AppointmentState state : AppointmentState.values()) {
            assertEquals(state, AppointmentState.from(state.wire()));
            assertEquals(state.wire(), state.name().toLowerCase(java.util.Locale.ROOT));
        }
        assertEquals("checked_in", AppointmentState.CHECKED_IN.wire());
        assertThrows(IllegalArgumentException.class, () -> AppointmentState.from("unknown"));
    }

    @Test
    void occupyingAndTerminalStatesAreComplementary() {
        for (AppointmentState state : AppointmentState.values()) {
            boolean occupying = switch (state) {
                case HELD, BOOKED, CHECKED_IN, IN_PROGRESS -> true;
                case COMPLETED, CANCELLED, NO_SHOW, EXPIRED -> false;
            };
            assertEquals(occupying, state.occupiesSlot(), state.name());
            assertEquals(!occupying, state.isTerminal(), state.name());
        }
    }

    @Test
    void onlyCancelledAndNoShowRequireAReason() {
        for (AppointmentState state : AppointmentState.values()) {
            assertEquals(state == AppointmentState.CANCELLED || state == AppointmentState.NO_SHOW,
                    state.requiresReason(), state.name());
        }
    }

    @Test
    void historyActionNamesAreStable() {
        assertEquals("started", AppointmentState.IN_PROGRESS.eventAction());
        assertEquals("checked_in", AppointmentState.CHECKED_IN.eventAction());
        assertEquals("no_show", AppointmentState.NO_SHOW.eventAction());
        assertEquals("expired", AppointmentState.EXPIRED.eventAction());
    }

    @Test
    void transitionMatrixMatchesCln01() {
        // Every ordered pair is asserted, so a widened or narrowed rule fails here.
        assertTrue(AppointmentState.HELD.canTransitionTo(AppointmentState.BOOKED));
        assertTrue(AppointmentState.HELD.canTransitionTo(AppointmentState.EXPIRED));
        assertTrue(AppointmentState.HELD.canTransitionTo(AppointmentState.CANCELLED));
        assertTrue(AppointmentState.BOOKED.canTransitionTo(AppointmentState.CHECKED_IN));
        assertTrue(AppointmentState.BOOKED.canTransitionTo(AppointmentState.CANCELLED));
        assertTrue(AppointmentState.BOOKED.canTransitionTo(AppointmentState.NO_SHOW));
        assertTrue(AppointmentState.CHECKED_IN.canTransitionTo(AppointmentState.IN_PROGRESS));
        assertTrue(AppointmentState.CHECKED_IN.canTransitionTo(AppointmentState.CANCELLED));
        assertTrue(AppointmentState.IN_PROGRESS.canTransitionTo(AppointmentState.COMPLETED));
        for (AppointmentState from : AppointmentState.values()) {
            for (AppointmentState to : AppointmentState.values()) {
                boolean expected = (from == AppointmentState.HELD
                        && (to == AppointmentState.BOOKED || to == AppointmentState.EXPIRED
                        || to == AppointmentState.CANCELLED))
                        || (from == AppointmentState.BOOKED
                        && (to == AppointmentState.CHECKED_IN || to == AppointmentState.CANCELLED
                        || to == AppointmentState.NO_SHOW))
                        || (from == AppointmentState.CHECKED_IN
                        && (to == AppointmentState.IN_PROGRESS || to == AppointmentState.CANCELLED))
                        || (from == AppointmentState.IN_PROGRESS
                        && to == AppointmentState.COMPLETED);
                assertEquals(expected, from.canTransitionTo(to), from + "->" + to);
                assertFalse(from.canTransitionTo(from) && !expected);
            }
        }
    }
}
