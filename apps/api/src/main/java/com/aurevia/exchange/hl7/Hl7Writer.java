package com.aurevia.exchange.hl7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dependency-free writer for the bounded HL7 v2 subset used by order and result
 * exchange (XCH-01, XCH-04).
 *
 * <p>Field values are escaped before being joined, and the repetition separator
 * is inserted as structure rather than escaped data. Segment terminators are
 * carriage returns, so an escaped CR or LF inside a value cannot create an
 * extra segment.</p>
 */
public final class Hl7Writer {

    private Hl7Writer() {
    }

    /**
     * Wire values needed to render one ORM^O01 order.
     *
     * @param patientId            local patient identifier
     * @param encounterRef         local encounter reference
     * @param orderId              placer order number
     * @param priorityCode         HL7 priority code (R, U or S)
     * @param orderCode            universal service identifier
     * @param orderCodeText        display text of the service
     * @param clinicalQuestion     clinical question released with the order
     * @param responsibleClinician ordering provider name
     */
    public record OrderLine(String patientId, String encounterRef, String orderId,
                            String priorityCode, String orderCode, String orderCodeText,
                            String clinicalQuestion, String responsibleClinician) {
    }

    /**
     * Wire values needed to render one ORU^R01 result.
     *
     * @param patientId       local patient identifier
     * @param orderId         placer order number
     * @param accessionId     filler order number (accession)
     * @param statusCode      OBX-11 result status (F, C or A)
     * @param supersedesVersion prior revision corrected by this report, or null
     * @param observationCode observation identifier
     * @param observationText display text of the observation
     * @param values          one or more observation values written as repetitions
     * @param units           observation units
     * @param abnormalFlag    OBX-8 abnormal flag
     */
    public record ResultLine(String patientId, String orderId, String accessionId,
                             String statusCode, Integer supersedesVersion,
                             String observationCode, String observationText, List<String> values,
                             String units, String abnormalFlag) {

        /**
         * Freeze the observation value list.
         */
        public ResultLine {
            values = List.copyOf(values);
        }

        /**
         * Return an immutable view of the observation values.
         *
         * @return observation repetitions
         */
        @Override
        public List<String> values() {
            return List.copyOf(values);
        }
    }

    /**
     * Render an acknowledgment message with an MSA and, for a negative
     * acknowledgment, an ERR segment carrying the reason.
     *
     * @param ack        acknowledgment to render
     * @param delimiters delimiters to use
     * @return wire message ending with a carriage return
     */
    public static String writeAck(Hl7Ack ack, Hl7Delimiters delimiters) {
        StringBuilder out = new StringBuilder(msh(delimiters, "ACK^R01", ack.controlId(),
                ack.timestamp(), "OREVIA", "PARTNER"));
        out.append(line(delimiters, "MSA", List.of(escaped(ack.code(), delimiters),
                escaped(ack.originalControlId(), delimiters), escaped(ack.reason(), delimiters))));
        if (!ack.reason().isEmpty()) {
            out.append(line(delimiters, "ERR", List.of(escaped(ack.code(), delimiters),
                    escaped(ack.reason(), delimiters))));
        }
        return out.toString();
    }

    /**
     * Render one ORM^O01 order message.
     *
     * @param order      order wire values
     * @param delimiters delimiters to use
     * @param controlId  message control identifier
     * @param timestamp  message timestamp
     * @param sender     sending facility
     * @param receiver   receiving facility
     * @return wire message ending with a carriage return
     */
    public static String writeOrder(OrderLine order, Hl7Delimiters delimiters, String controlId,
                                    String timestamp, String sender, String receiver) {
        StringBuilder out = new StringBuilder(msh(delimiters, "ORM^O01", controlId, timestamp,
                sender, receiver));
        out.append(line(delimiters, "PID", List.of("1", "",
                escaped(order.patientId(), delimiters))));

        List<String> pv1 = new ArrayList<>(Collections.nCopies(19, ""));
        pv1.set(0, "1");
        pv1.set(18, escaped(order.encounterRef(), delimiters));
        out.append(line(delimiters, "PV1", pv1));

        List<String> orc = new ArrayList<>(Collections.nCopies(12, ""));
        orc.set(1, escaped(order.orderId(), delimiters));
        orc.set(6, escaped(order.priorityCode(), delimiters));
        orc.set(11, escaped(order.responsibleClinician(), delimiters));
        out.append(line(delimiters, "ORC", orc));

        List<String> obr = new ArrayList<>(Collections.nCopies(31, ""));
        obr.set(1, escaped(order.orderId(), delimiters));
        obr.set(3, escaped(order.orderCode(), delimiters) + delimiters.component()
                + escaped(order.orderCodeText(), delimiters));
        obr.set(30, escaped(order.clinicalQuestion(), delimiters));
        out.append(line(delimiters, "OBR", obr));
        return out.toString();
    }

    /**
     * Render one ORU^R01 result message with repeating OBX-5 values.
     *
     * @param result     result wire values
     * @param delimiters delimiters to use
     * @param controlId  message control identifier
     * @param timestamp  message timestamp
     * @param sender     sending facility
     * @param receiver   receiving facility
     * @return wire message ending with a carriage return
     */
    public static String writeResult(ResultLine result, Hl7Delimiters delimiters, String controlId,
                                     String timestamp, String sender, String receiver) {
        StringBuilder out = new StringBuilder(msh(delimiters, "ORU^R01", controlId, timestamp,
                sender, receiver));
        out.append(line(delimiters, "PID", List.of("1", "",
                escaped(result.patientId(), delimiters))));

        List<String> obr = new ArrayList<>(Collections.nCopies(3, ""));
        obr.set(1, escaped(result.orderId(), delimiters));
        obr.set(2, escaped(result.accessionId(), delimiters));
        out.append(line(delimiters, "OBR", obr));

        List<String> repetitions = new ArrayList<>(result.values().size());
        for (String value : result.values()) {
            repetitions.add(escaped(value, delimiters));
        }
        List<String> obx = new ArrayList<>(Collections.nCopies(11, ""));
        obx.set(2, escaped(result.observationCode(), delimiters) + delimiters.component()
                + escaped(result.observationText(), delimiters));
        obx.set(4, String.join(String.valueOf(delimiters.repetition()), repetitions));
        obx.set(5, escaped(result.units(), delimiters));
        obx.set(7, escaped(result.abnormalFlag(), delimiters));
        obx.set(10, escaped(result.statusCode(), delimiters));
        out.append(line(delimiters, "OBX", obx));

        if (result.supersedesVersion() != null) {
            out.append(line(delimiters, "ZSV",
                    List.of(escaped(String.valueOf(result.supersedesVersion()), delimiters))));
        }
        return out.toString();
    }

    /**
     * Build the MSH segment with a raw MSH-2 encoding declaration and escaped
     * data fields.
     *
     * @param delimiters  delimiters to declare and use
     * @param messageType raw MSH-9 value such as {@code ORM^O01}
     * @param controlId   MSH-10 control identifier
     * @param timestamp   MSH-7 timestamp
     * @param sending     MSH-4 sending facility
     * @param receiving   MSH-6 receiving facility
     * @return MSH segment ending with a carriage return
     */
    private static String msh(Hl7Delimiters delimiters, String messageType, String controlId,
                              String timestamp, String sending, String receiving) {
        StringBuilder out = new StringBuilder("MSH").append(delimiters.field());
        out.append(delimiters.component()).append(delimiters.repetition())
                .append(delimiters.escape()).append(delimiters.subcomponent())
                .append(delimiters.field());
        out.append("EXCHANGE").append(delimiters.field());
        out.append(escaped(sending, delimiters)).append(delimiters.field());
        out.append("EXCHANGE").append(delimiters.field());
        out.append(escaped(receiving, delimiters)).append(delimiters.field());
        out.append(escaped(timestamp, delimiters)).append(delimiters.field());
        out.append(delimiters.field());
        out.append(messageType).append(delimiters.field());
        out.append(escaped(controlId, delimiters)).append(delimiters.field());
        out.append("P").append(delimiters.field()).append("2.5").append('\r');
        return out.toString();
    }

    /**
     * Join an already escaped field list into one segment with a field separator.
     *
     * @param delimiters delimiters to use
     * @param type       segment identifier
     * @param fields     escaped field values after the segment identifier
     * @return segment ending with a carriage return
     */
    private static String line(Hl7Delimiters delimiters, String type, List<String> fields) {
        StringBuilder out = new StringBuilder(type);
        for (String field : fields) {
            out.append(delimiters.field()).append(field);
        }
        return out.append('\r').toString();
    }

    /**
     * Escape a value for a field.
     *
     * @param value      raw value
     * @param delimiters delimiters to use
     * @return escaped value
     */
    private static String escaped(String value, Hl7Delimiters delimiters) {
        return Hl7Escape.encode(value, delimiters);
    }
}
