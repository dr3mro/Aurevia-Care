package com.aurevia.exchange.hl7;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;

/**
 * Maps a validated HL7 v2 message to the existing internal order and result
 * commands (XCH-01, XCH-04, LAB-02).
 *
 * <p>The mapper is pure: it neither writes to a store nor mutates the message.
 * Segment order and every required field are checked before a command is
 * created, so a malformed or partial message never yields a partial command.
 * The internal command types are reused rather than duplicated.</p>
 */
public final class Hl7Mapper {

    /** Segment sequence required by an ORM^O01 order. */
    private static final List<String> ORDER_SEQUENCE = List.of("MSH", "PID", "ORC", "OBR");

    /** Segment sequence required by an ORU^R01 result. */
    private static final List<String> RESULT_SEQUENCE = List.of("MSH", "PID", "OBR", "OBX");

    /** HL7 order priority codes mapped to the approved internal priorities. */
    private static final Map<String, String> PRIORITIES = Map.of(
            "R", "routine", "U", "urgent", "S", "stat");

    /** OBX-11 result status codes mapped to internal result dispositions. */
    private static final Map<String, String> DISPOSITIONS = Map.of(
            "F", "FINAL", "C", "CORRECTED", "A", "CRITICAL");

    private Hl7Mapper() {
    }

    /**
     * Validate the segment sequence and message type of an ORM^O01 order.
     *
     * @param message parsed message
     * @throws Hl7ParseException when a required segment is missing or out of order
     */
    public static void validateOrder(Hl7Message message) {
        requireSequence(message, ORDER_SEQUENCE);
        if (!message.first("PV1").isPresent()) {
            throw new Hl7ParseException("MISSING_SEGMENT", "PV1 encounter segment is required");
        }
    }

    /**
     * Validate the segment sequence of an ORU^R01 result.
     *
     * @param message parsed message
     * @throws Hl7ParseException when a required segment is missing or out of order
     */
    public static void validateResult(Hl7Message message) {
        requireSequence(message, RESULT_SEQUENCE);
    }

    /**
     * Map a validated ORM^O01 message to an internal order command.
     *
     * @param message parsed order message
     * @param context sender-side authorization facts
     * @return the internal order command
     * @throws Hl7ParseException when a required field or authorization fact is missing
     */
    public static OrderRequest mapOrder(Hl7Message message, Hl7SenderContext context) {
        Hl7Segment pid = message.first("PID").orElseThrow();
        Hl7Segment pv1 = message.first("PV1").orElseThrow();
        Hl7Segment orc = message.first("ORC").orElseThrow();
        Hl7Segment obr = message.first("OBR").orElseThrow();
        Hl7Segment msh = message.first("MSH").orElseThrow();

        String patientId = required(pid.field(4), "patientId");
        String encounterRef = required(pv1.field(20), "encounterRef");
        String orderCode = required(obr.component(5, 1, message.delimiters().component()), "orderCode");
        String clinicalQuestion = required(obr.field(32), "clinicalQuestion");
        String destinationOrg = required(msh.field(6), "destinationOrg");
        String responsibleClinician = required(
                orc.component(13, 1, message.delimiters().component()), "responsibleClinician");

        String priorityCode = required(orc.field(8), "priority").toUpperCase(Locale.ROOT);
        String priority = PRIORITIES.get(priorityCode);
        if (priority == null) {
            throw new Hl7ParseException("INVALID_PRIORITY", "unsupported order priority");
        }

        String purpose = required(context.purpose(), "purpose");
        String authorizationId = required(context.authorizationId(), "authorizationId");
        String workQueue = required(context.workQueue(), "workQueue");
        if (context.durationMinutes() <= 0) {
            throw new Hl7ParseException("INVALID_DURATION", "durationMinutes must be positive");
        }
        return new OrderRequest(patientId, encounterRef, orderCode, priority, clinicalQuestion,
                destinationOrg, purpose, authorizationId, responsibleClinician, workQueue,
                context.durationMinutes());
    }

    /**
     * Map a validated ORU^R01 message to an internal result import command.
     *
     * @param message parsed result message
     * @return the internal result command
     * @throws Hl7ParseException when a required field is missing or malformed
     */
    public static ImportResultRequest mapResult(Hl7Message message) {
        Hl7Segment pid = message.first("PID").orElseThrow();
        Hl7Segment obr = message.first("OBR").orElseThrow();
        Hl7Segment obx = message.first("OBX").orElseThrow();
        Hl7Segment msh = message.first("MSH").orElseThrow();

        String patientId = required(pid.field(4), "patientId");
        String orderId = required(obr.field(3), "orderId");
        String accessionId = required(obr.field(4), "accessionId");
        String sourceId = required(msh.field(4), "sourceId");
        String destinationId = required(msh.field(6), "destinationId");

        String status = required(obx.field(12), "status").toUpperCase(Locale.ROOT);
        String kind = DISPOSITIONS.get(status);
        if (kind == null) {
            throw new Hl7ParseException("INVALID_STATUS", "unsupported result status");
        }

        Integer supersedesVersion = readSupersedesVersion(message);
        String effectiveAt = Hl7Timestamp.parse(required(msh.field(7), "timestamp")).toString();
        String checksum = fingerprint(message.rawText());
        return new ImportResultRequest(orderId, patientId, sourceId, accessionId, destinationId,
                kind, supersedesVersion, effectiveAt, checksum);
    }

    /**
     * Read the optional superseded revision from a ZSV local extension segment.
     *
     * @param message parsed result message
     * @return the prior revision number, or null for an initial report
     * @throws Hl7ParseException when the segment is present but its value is malformed
     */
    private static Integer readSupersedesVersion(Hl7Message message) {
        Optional<Hl7Segment> segment = message.first("ZSV");
        if (segment.isEmpty()) {
            return null;
        }
        String value = required(segment.get().field(2), "supersedesVersion");
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new Hl7ParseException("INVALID_VERSION", "supersedesVersion is not a number");
        }
    }

    /**
     * Check the required segments appear exactly once each in wire order.
     *
     * @param message parsed message
     * @param types   required segment types in order
     * @throws Hl7ParseException when a segment is missing or out of order
     */
    private static void requireSequence(Hl7Message message, List<String> types) {
        int previous = -1;
        for (String type : types) {
            int index = indexOfType(message, type);
            if (index < 0) {
                throw new Hl7ParseException("MISSING_SEGMENT", "missing segment " + type);
            }
            if (index <= previous) {
                throw new Hl7ParseException("SEGMENT_ORDER", "segments are out of order at " + type);
            }
            previous = index;
        }
    }

    /**
     * Find the first segment of a type.
     *
     * @param message parsed message
     * @param type    segment type
     * @return zero-based segment index, or -1 when absent
     */
    private static int indexOfType(Hl7Message message, String type) {
        List<Hl7Segment> segments = message.segments();
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index).type().equals(type)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Reject a missing or blank required value.
     *
     * @param value raw value
     * @param field field name used in the reason detail
     * @return the non-blank value
     * @throws Hl7ParseException when the value is null or blank
     */
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new Hl7ParseException("MISSING_FIELD", "required field " + field + " is missing");
        }
        return value;
    }

    /**
     * Compute a deterministic non-cryptographic fingerprint of the received
     * payload for correlation and duplicate detection. It is not a signature.
     *
     * @param text raw message text
     * @return sixteen hexadecimal characters
     */
    private static String fingerprint(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < text.length(); index++) {
            hash ^= text.charAt(index);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }
}
