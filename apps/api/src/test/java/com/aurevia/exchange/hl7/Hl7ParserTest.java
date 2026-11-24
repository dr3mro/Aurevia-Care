package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Parsing of the bounded HL7 v2 subset. */
class Hl7ParserTest {

    private static final String MSH =
            "MSH|^~\\&|SENDER|SENDFAC|RECEIVER|RECVFAC|20261121120000||ORM^O01|MSG-1|P|2.5";

    @Test
    void rejectsNullBlankOrSegmentlessText() {
        assertEquals("EMPTY_MESSAGE",
                assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse(null)).reason());
        assertEquals("EMPTY_MESSAGE",
                assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse("   ")).reason());
        assertEquals("EMPTY_MESSAGE",
                assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse("\r\n\n\r")).reason());
    }

    @Test
    void rejectsANonMshFirstSegment() {
        assertEquals("INVALID_MSH",
                assertThrows(Hl7ParseException.class,
                        () -> Hl7Parser.parse("PID|1||PAT-1\r")).reason());
    }

    @Test
    void rejectsATruncatedSegment() {
        String text = MSH + "\rAB\r";
        assertEquals("TRUNCATED_SEGMENT",
                assertThrows(Hl7ParseException.class, () -> Hl7Parser.parse(text)).reason());
    }

    @Test
    void parsesCrLfSegmentsAndSkipsBlankLines() {
        String text = "\r\n" + MSH + "\r\n\r\nPID|1||PAT-1\r\n";
        Hl7Message message = Hl7Parser.parse(text);

        assertEquals("ORM^O01", message.messageType());
        assertEquals("MSG-1", message.controlId());
        assertEquals(2, message.segments().size());
        assertEquals("PAT-1", message.first("PID").orElseThrow().field(4));
        assertEquals(text, message.rawText());
    }

    @Test
    void parsesLfAndCrTerminators() {
        assertEquals(2, Hl7Parser.parse(MSH + "\nPID|1||PAT-1\n").segments().size());
        assertEquals(2, Hl7Parser.parse(MSH + "\rPID|1||PAT-1\r").segments().size());
    }

    @Test
    void parsesDeclaredNonStandardDelimitersAndRepeatingFields() {
        String text = "MSH*^~\\&*SENDER*SENDFAC*RECEIVER*RECVFAC*20261121120000**ORU^R01*MSG-2*P*2.5\r"
                + "OBX*1*ST**7.1~7.3\r";
        Hl7Message message = Hl7Parser.parse(text);

        assertEquals('*', message.delimiters().field());
        assertEquals("ORU^R01", message.messageType());
        assertEquals("7.1", message.first("OBX").orElseThrow().field(5));
        assertEquals(2, message.first("OBX").orElseThrow().repetitionValues(5).size());
    }
}
