package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.task.ExchangeCommand;
import com.aurevia.exchange.task.ExchangeDimension;

/** Every explicit POST command maps to exactly one state dimension and target. */
class ExchangeCommandTest {

    @Test
    void parsesEveryCommandAndRejectsUnknown() {
        for (ExchangeCommand command : ExchangeCommand.values()) {
            assertEquals(command, ExchangeCommand.from(command.wire()));
        }
        assertEquals(ExchangeDimension.WORKFLOW, ExchangeCommand.ACCEPT.dimension());
        assertEquals(ExchangeDimension.TRANSMISSION, ExchangeCommand.DELIVER.dimension());
        assertEquals(ExchangeDimension.CLINICAL_REVIEW, ExchangeCommand.ACKNOWLEDGE.dimension());
        assertThrows(IllegalArgumentException.class, () -> ExchangeCommand.from("unknown"));
    }

    @Test
    void rejectAndCancelRequireAReasonAndOnlyTheyDo() {
        for (ExchangeCommand command : ExchangeCommand.values()) {
            boolean expected = command == ExchangeCommand.REJECT || command == ExchangeCommand.CANCEL;
            assertEquals(expected, command.requiresReason(), command.name());
        }
        assertTrue(ExchangeCommand.REJECT.requiresReason());
        assertFalse(ExchangeCommand.SEND.requiresReason());
    }

    @Test
    void auditActionIsStableAndDimensionPolicyActionsAreDistinct() {
        assertEquals("EXCHANGE_ACCEPT", ExchangeCommand.ACCEPT.auditAction());
        assertEquals("EXCHANGE_ACKNOWLEDGE", ExchangeCommand.ACKNOWLEDGE.auditAction());
        for (ExchangeDimension dimension : ExchangeDimension.values()) {
            assertEquals(dimension, ExchangeDimension.from(dimension.wire()));
        }
        assertEquals("EXCHANGE_WORKFLOW", ExchangeDimension.WORKFLOW.policyAction());
        assertEquals("EXCHANGE_TRANSMISSION", ExchangeDimension.TRANSMISSION.policyAction());
        assertEquals("EXCHANGE_REVIEW", ExchangeDimension.CLINICAL_REVIEW.policyAction());
        assertThrows(IllegalArgumentException.class, () -> ExchangeDimension.from("unknown"));
    }
}
