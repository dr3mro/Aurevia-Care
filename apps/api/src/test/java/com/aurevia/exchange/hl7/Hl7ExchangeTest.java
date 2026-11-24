package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.hl7.Hl7Exchange.ProcessResult;

/** End-to-end processing of inbound order and result messages. */
class Hl7ExchangeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-21T12:00:00Z"),
            ZoneOffset.UTC);

    private final Hl7Exchange exchange = new Hl7Exchange(CLOCK);
    private final Hl7SenderContext context =
            new Hl7SenderContext("SENDER", "LAB-QUEUE", "DIAGNOSIS", "AUTH-1", 60);

    private String order(String controlId, String priority) {
        return Hl7Writer.writeOrder(new Hl7Writer.OrderLine("PAT-1", "VIS-1", "ORD-1", priority,
                "GLUC", "Glucose", "Rule | out diabetes", "Smith^Ada"), Hl7Delimiters.standard(),
                controlId, "20261121120000", "SENDER", "RECVFAC");
    }

    private String result(String controlId, String status, Integer supersedes) {
        return Hl7Writer.writeResult(new Hl7Writer.ResultLine("PAT-1", "ORD-1", "ACC-1", status,
                supersedes, "GLUC", "Glucose", List.of("7.1", "7.3"), "mg/dL", "A"),
                Hl7Delimiters.standard(), controlId, "20261121120000", "SENDER", "RECVFAC");
    }

    @Test
    void acceptsAnOrderAndReturnsAPositiveAck() {
        ProcessResult outcome = exchange.process(order("MSG-1", "U"), context);

        assertTrue(outcome.applied());
        assertNotNull(outcome.order());
        assertNull(outcome.result());
        assertEquals("", outcome.reason());
        assertEquals(Hl7Ack.ACCEPT, outcome.ack().code());
        assertEquals("ACK-MSG-1", outcome.ack().controlId());
        assertEquals("MSG-1", outcome.ack().originalControlId());
        assertEquals("20261121120000", outcome.ack().timestamp());
        assertTrue(outcome.ack().positive());

        Hl7Message ack = Hl7Parser.parse(Hl7Writer.writeAck(outcome.ack(), Hl7Delimiters.standard()));
        assertEquals("AA", ack.first("MSA").orElseThrow().field(2));
        assertFalse(ack.has("ERR"));
    }

    @Test
    void acceptsAResultMessage() {
        ProcessResult outcome = exchange.process(result("MSG-2", "F", null), context);

        assertTrue(outcome.applied());
        assertNull(outcome.order());
        assertNotNull(outcome.result());
        assertTrue(outcome.ack().positive());
    }

    @Test
    void rejectsADuplicateMessageWithoutACommand() {
        String message = order("MSG-3", "R");
        assertTrue(exchange.process(message, context).applied());

        ProcessResult duplicate = exchange.process(message, context);
        assertFalse(duplicate.applied());
        assertEquals(Hl7Ack.REJECT, duplicate.ack().code());
        assertEquals("DUPLICATE_MESSAGE", duplicate.reason());
        assertEquals("DUPLICATE_MESSAGE",
                duplicate.ack().reason());
        Hl7Message ack = Hl7Parser.parse(Hl7Writer.writeAck(duplicate.ack(), Hl7Delimiters.standard()));
        assertEquals("AR", ack.first("MSA").orElseThrow().field(2));
        assertTrue(ack.has("ERR"));
    }

    @Test
    void rejectsMalformedTextWithNoParsedHeader() {
        ProcessResult outcome = exchange.process("garbage", context);

        assertFalse(outcome.applied());
        assertEquals(Hl7Ack.ERROR, outcome.ack().code());
        assertEquals("INVALID_MSH", outcome.reason());
        assertEquals("", outcome.ack().originalControlId());
    }

    @Test
    void rejectsAnUnsupportedMessageType() {
        String message = order("MSG-4", "R").replace("ORM^O01", "ADT^A01");
        ProcessResult outcome = exchange.process(message, context);

        assertFalse(outcome.applied());
        assertEquals("UNSUPPORTED_MESSAGE", outcome.reason());
        assertEquals("MSG-4", outcome.ack().originalControlId());
    }

    @Test
    void rejectsATruncatedSegment() {
        ProcessResult outcome = exchange.process(order("MSG-5", "R") + "AB\r", context);

        assertFalse(outcome.applied());
        assertEquals("TRUNCATED_SEGMENT", outcome.reason());
    }

    @Test
    void rejectsAPartialOrderWithNoCommand() {
        StringBuilder partial = new StringBuilder();
        for (String line : order("MSG-6", "R").split("\r")) {
            if (!line.startsWith("PV1|")) {
                partial.append(line).append('\r');
            }
        }
        ProcessResult outcome = exchange.process(partial.toString(), context);

        assertFalse(outcome.applied());
        assertEquals("MISSING_SEGMENT", outcome.reason());
        assertNull(outcome.order());
        assertNull(outcome.result());
    }

    @Test
    void releasesTheControlIdAfterAMappingFailureSoACorrectedRetrySucceeds() {
        ProcessResult bad = exchange.process(order("MSG-7", "X"), context);
        assertFalse(bad.applied());
        assertEquals("INVALID_PRIORITY", bad.reason());

        ProcessResult retry = exchange.process(order("MSG-7", "S"), context);
        assertTrue(retry.applied());
        assertEquals("stat", retry.order().priority());
    }

    @Test
    void rejectsAMessageWithoutAControlId() {
        ProcessResult outcome = exchange.process(result("", "F", null), context);

        assertFalse(outcome.applied());
        assertEquals("MISSING_CONTROL_ID", outcome.reason());
    }

    @Test
    void acceptsACorrectedResultAsANewRevision() {
        exchange.process(result("MSG-8", "F", null), context);
        ProcessResult correction = exchange.process(result("MSG-9", "C", 1), context);

        assertTrue(correction.applied());
        assertEquals("CORRECTED", correction.result().kind());
        assertEquals(1, correction.result().supersedesVersion());
    }

    @Test
    void supportsTheDefaultSystemClock() {
        ProcessResult outcome = new Hl7Exchange().process(result("MSG-10", "F", null), context);
        assertTrue(outcome.applied());
        assertNotNull(outcome.ack().timestamp());
        assertEquals(14, outcome.ack().timestamp().length());
    }
}
