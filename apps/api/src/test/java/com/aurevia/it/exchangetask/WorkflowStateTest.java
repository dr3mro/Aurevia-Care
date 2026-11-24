package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.task.WorkflowState;

/** XCH-02 workflow dimension: the boundary of requested → accepted → processing → completed. */
class WorkflowStateTest {

    @Test
    void exposesWireFormAndParsesIt() {
        for (WorkflowState state : WorkflowState.values()) {
            assertEquals(state, WorkflowState.from(state.wire()));
            assertEquals(state.wire(), state.name().toLowerCase(Locale.ROOT));
        }
        assertEquals("requested", WorkflowState.REQUESTED.wire());
        assertThrows(IllegalArgumentException.class, () -> WorkflowState.from("unknown"));
    }

    @Test
    void terminalStatesAreExactlyCompletedRejectedAndCancelled() {
        for (WorkflowState state : WorkflowState.values()) {
            boolean expected = state == WorkflowState.COMPLETED
                    || state == WorkflowState.REJECTED || state == WorkflowState.CANCELLED;
            assertEquals(expected, state.isTerminal(), state.name());
        }
    }

    @Test
    void transitionMatrixMatchesXch02() {
        for (WorkflowState from : WorkflowState.values()) {
            for (WorkflowState to : WorkflowState.values()) {
                boolean expected = switch (from) {
                    case REQUESTED -> to == WorkflowState.ACCEPTED || to == WorkflowState.REJECTED
                            || to == WorkflowState.CANCELLED;
                    case ACCEPTED -> to == WorkflowState.PROCESSING || to == WorkflowState.CANCELLED;
                    case PROCESSING -> to == WorkflowState.COMPLETED || to == WorkflowState.CANCELLED;
                    default -> false;
                };
                assertEquals(expected, from.canTransitionTo(to), from + "->" + to);
            }
        }
        assertTrue(WorkflowState.REQUESTED.canTransitionTo(WorkflowState.ACCEPTED));
        assertFalse(WorkflowState.COMPLETED.canTransitionTo(WorkflowState.PROCESSING));
    }
}
