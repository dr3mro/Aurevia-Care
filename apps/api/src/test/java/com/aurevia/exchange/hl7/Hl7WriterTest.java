package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Writing of ORM, ORU and acknowledgment messages and their round trip. */
class Hl7WriterTest {

    private final Hl7Delimiters delimiters = Hl7Delimiters.standard();

    @Test
    void writesAPositiveAckWithoutAnErrSegment() {
        Hl7Ack ack = new Hl7Ack(Hl7Ack.ACCEPT, "ACK-1", "MSG-1", "", "20261121120000");
        Hl7Message parsed = Hl7Parser.parse(Hl7Writer.writeAck(ack, delimiters));

        assertEquals("ACK^R01", parsed.messageType());
        assertEquals("ACK-1", parsed.controlId());
        assertEquals("AA", parsed.first("MSA").orElseThrow().field(2));
        assertEquals("MSG-1", parsed.first("MSA").orElseThrow().field(3));
        assertFalse(parsed.has("ERR"));
        assertTrue(ack.positive());
    }

    @Test
    void writesANegativeAckWithTheReasonInErr() {
        Hl7Ack ack = new Hl7Ack(Hl7Ack.ERROR, "ACK-2", "MSG-2", "MISSING_FIELD", "20261121120000");
        Hl7Message parsed = Hl7Parser.parse(Hl7Writer.writeAck(ack, delimiters));

        assertEquals("AE", parsed.first("MSA").orElseThrow().field(2));
        assertEquals("MISSING_FIELD", parsed.first("MSA").orElseThrow().field(4));
        assertEquals("MISSING_FIELD", parsed.first("ERR").orElseThrow().field(3));
        assertFalse(ack.positive());
    }

    @Test
    void writesAnOrderWithEscapedSpecialCharacters() {
        String question = "Rule | out \\ confirm & re-check";
        Hl7Writer.OrderLine order = new Hl7Writer.OrderLine("PAT-1", "VIS-1", "ORD-1", "U",
                "GLUC", "Glucose", question, "Smith^Ada");

        Hl7Message parsed = Hl7Parser.parse(Hl7Writer.writeOrder(order, delimiters,
                "MSG-3", "20261121120000", "SENDER", "RECVFAC"));

        assertEquals("ORM^O01", parsed.messageType());
        assertEquals("PAT-1", parsed.first("PID").orElseThrow().field(4));
        assertEquals("VIS-1", parsed.first("PV1").orElseThrow().field(20));
        assertEquals("ORD-1", parsed.first("ORC").orElseThrow().field(3));
        assertEquals("U", parsed.first("ORC").orElseThrow().field(8));
        assertEquals("Smith", parsed.first("ORC").orElseThrow().component(13, 1, '^'));
        assertEquals("GLUC", parsed.first("OBR").orElseThrow().component(5, 1, '^'));
        assertEquals(question, parsed.first("OBR").orElseThrow().field(32));
    }

    @Test
    void writesResultRepeatingValuesAndACorrectionVersion() {
        Hl7Writer.ResultLine result = new Hl7Writer.ResultLine("PAT-1", "ORD-1", "ACC-1", "C", 1,
                "GLUC", "Glucose", List.of("7.1", "7.3"), "mg/dL", "A");

        Hl7Message parsed = Hl7Parser.parse(Hl7Writer.writeResult(result, delimiters,
                "MSG-4", "20261121120000", "SENDER", "RECVFAC"));

        assertEquals("ORU^R01", parsed.messageType());
        assertEquals(List.of("7.1", "7.3"), parsed.first("OBX").orElseThrow().repetitionValues(6));
        assertEquals("mg/dL", parsed.first("OBX").orElseThrow().field(7));
        assertEquals("A", parsed.first("OBX").orElseThrow().field(9));
        assertEquals("C", parsed.first("OBX").orElseThrow().field(12));
        assertEquals("1", parsed.first("ZSV").orElseThrow().field(2));
    }

    @Test
    void omitsTheCorrectionVersionForAnInitialReport() {
        Hl7Writer.ResultLine result = new Hl7Writer.ResultLine("PAT-1", "ORD-1", "ACC-1", "F", null,
                "GLUC", "Glucose", List.of("7.1"), "mg/dL", "");

        Hl7Message parsed = Hl7Parser.parse(Hl7Writer.writeResult(result, delimiters,
                "MSG-5", "20261121120000", "SENDER", "RECVFAC"));

        assertFalse(parsed.has("ZSV"));
    }

    @Test
    void freezesTheObservationValueList() {
        List<String> values = new ArrayList<>(List.of("7.1"));
        Hl7Writer.ResultLine result = new Hl7Writer.ResultLine("PAT-1", "ORD-1", "ACC-1", "F", null,
                "GLUC", "Glucose", values, "mg/dL", "");
        values.add("9.9");

        assertEquals(List.of("7.1"), result.values());
        assertThrows(UnsupportedOperationException.class, () -> result.values().add("8.0"));
    }
}
