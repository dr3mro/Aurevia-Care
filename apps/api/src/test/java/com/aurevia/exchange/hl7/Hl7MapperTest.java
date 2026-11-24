package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;

/** Mapping of parsed messages to the internal order and result commands. */
class Hl7MapperTest {

    private final Hl7Delimiters delimiters = Hl7Delimiters.standard();

    private final Hl7SenderContext context =
            new Hl7SenderContext("SENDER", "LAB-QUEUE", "DIAGNOSIS", "AUTH-1", 60);

    private String orderText(String patientId, String priority) {
        return Hl7Writer.writeOrder(new Hl7Writer.OrderLine(patientId, "VIS-1", "ORD-1", priority,
                "GLUC", "Glucose", "Rule out diabetes", "Smith^Ada"), delimiters,
                "MSG-1", "20261121120000", "SENDER", "RECVFAC");
    }

    private String resultText(String status, Integer supersedes, String timestamp) {
        return Hl7Writer.writeResult(new Hl7Writer.ResultLine("PAT-1", "ORD-1", "ACC-1", status,
                supersedes, "GLUC", "Glucose", List.of("7.1", "7.3"), "mg/dL", "A"), delimiters,
                "MSG-2", timestamp, "SENDER", "RECVFAC");
    }

    private static String withoutSegment(String text, String type) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r")) {
            if (!line.startsWith(type + "|")) {
                out.append(line).append('\r');
            }
        }
        return out.toString();
    }

    private static String reversedSegments(String text, String first, String second) {
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r")));
        int firstIndex = indexOfPrefix(lines, first);
        int secondIndex = indexOfPrefix(lines, second);
        Collections.swap(lines, firstIndex, secondIndex);
        return String.join("\r", lines) + "\r";
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith(prefix + "|")) {
                return index;
            }
        }
        throw new IllegalStateException("no segment " + prefix);
    }

    @Test
    void validatesOrderSegmentOrder() {
        assertEquals("MISSING_SEGMENT", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.validateOrder(Hl7Parser.parse(withoutSegment(orderText("PAT-1", "R"), "ORC"))))
                        .reason());
        assertEquals("SEGMENT_ORDER", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.validateOrder(Hl7Parser.parse(
                        reversedSegments(orderText("PAT-1", "R"), "ORC", "OBR")))).reason());
        assertEquals("MISSING_SEGMENT", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.validateOrder(Hl7Parser.parse(withoutSegment(orderText("PAT-1", "R"), "PV1"))))
                        .reason());
    }

    @Test
    void validatesResultSegmentOrder() {
        assertEquals("MISSING_SEGMENT", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.validateResult(Hl7Parser.parse(withoutSegment(resultText("F", null, "20261121120000"), "OBX"))))
                        .reason());
        assertEquals("SEGMENT_ORDER", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.validateResult(Hl7Parser.parse(
                        reversedSegments(resultText("F", null, "20261121120000"), "OBR", "OBX")))).reason());
    }

    @Test
    void mapsAValidOrderToTheInternalCommand() {
        OrderRequest order = Hl7Mapper.mapOrder(
                Hl7Parser.parse(orderText("PAT-1", "r")), context);

        assertEquals("PAT-1", order.patientId());
        assertEquals("VIS-1", order.encounterRef());
        assertEquals("GLUC", order.orderCode());
        assertEquals("routine", order.priority());
        assertEquals("Rule out diabetes", order.clinicalQuestion());
        assertEquals("RECVFAC", order.destinationOrg());
        assertEquals("DIAGNOSIS", order.purpose());
        assertEquals("AUTH-1", order.authorizationId());
        assertEquals("Smith", order.responsibleClinician());
        assertEquals("LAB-QUEUE", order.workQueue());
        assertEquals(60, order.durationMinutes());
    }

    @Test
    void rejectsUnsupportedPriorityAndDuration() {
        assertEquals("INVALID_PRIORITY", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapOrder(Hl7Parser.parse(orderText("PAT-1", "X")), context)).reason());
        Hl7SenderContext zeroDuration =
                new Hl7SenderContext("SENDER", "LAB-QUEUE", "DIAGNOSIS", "AUTH-1", 0);
        assertEquals("INVALID_DURATION", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapOrder(Hl7Parser.parse(orderText("PAT-1", "R")), zeroDuration))
                        .reason());
    }

    @Test
    void rejectsMissingOrderFieldsAndAuthorizationFacts() {
        assertEquals("MISSING_FIELD", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapOrder(Hl7Parser.parse(orderText("", "R")), context)).reason());
        Hl7SenderContext noPurpose = new Hl7SenderContext("SENDER", "LAB-QUEUE", "", "AUTH-1", 60);
        assertEquals("MISSING_FIELD", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapOrder(Hl7Parser.parse(orderText("PAT-1", "R")), noPurpose))
                        .reason());
        Hl7SenderContext nullPurpose = new Hl7SenderContext("SENDER", "LAB-QUEUE", null, "AUTH-1", 60);
        assertEquals("MISSING_FIELD", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapOrder(Hl7Parser.parse(orderText("PAT-1", "R")), nullPurpose))
                        .reason());
    }

    @Test
    void mapsAValidFinalResultToTheInternalCommand() {
        ImportResultRequest result = Hl7Mapper.mapResult(
                Hl7Parser.parse(resultText("f", null, "20261121120000")));

        assertEquals("ORD-1", result.orderId());
        assertEquals("PAT-1", result.patientId());
        assertEquals("SENDER", result.sourceId());
        assertEquals("ACC-1", result.accessionId());
        assertEquals("RECVFAC", result.destinationId());
        assertEquals("FINAL", result.kind());
        assertNull(result.supersedesVersion());
        assertEquals("2026-11-21T12:00:00Z", result.effectiveAt());
        assertNotNull(result.checksum());
        assertEquals(16, result.checksum().length());
        assertTrue(result.checksum().matches("[0-9a-f]{16}"));
    }

    @Test
    void mapsACorrectedResultWithItsSupersededVersion() {
        ImportResultRequest result = Hl7Mapper.mapResult(
                Hl7Parser.parse(resultText("C", 3, "20261121120000")));

        assertEquals("CORRECTED", result.kind());
        assertEquals(3, result.supersedesVersion());
    }

    @Test
    void mapsACriticalResultStatus() {
        ImportResultRequest result = Hl7Mapper.mapResult(
                Hl7Parser.parse(resultText("A", null, "20261121120000")));
        assertEquals("CRITICAL", result.kind());
    }

    @Test
    void rejectsUnsupportedStatusMalformedVersionAndMissingTimestamp() {
        assertEquals("INVALID_STATUS", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapResult(Hl7Parser.parse(resultText("X", null, "20261121120000"))))
                        .reason());
        assertEquals("INVALID_VERSION", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapResult(Hl7Parser.parse(resultText("C", 1, "20261121120000")
                        .replace("ZSV|1\r", "ZSV|abc\r")))).reason());
        assertEquals("MISSING_FIELD", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapResult(Hl7Parser.parse(resultText("F", 1, "")))).reason());
    }

    @Test
    void rejectsABlankSupersededVersion() {
        String text = resultText("C", 1, "20261121120000").replace("ZSV|1\r", "ZSV|\r");
        assertEquals("MISSING_FIELD", assertThrows(Hl7ParseException.class,
                () -> Hl7Mapper.mapResult(Hl7Parser.parse(text))).reason());
    }
}
