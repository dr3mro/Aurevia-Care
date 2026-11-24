package com.aurevia.exchange.conformance;

import java.util.regex.Pattern;

import com.aurevia.exchange.hl7.Hl7Delimiters;
import com.aurevia.exchange.hl7.Hl7Escape;

/**
 * HL7 v2 conformance wire adapter for synthetic partner events.
 *
 * <p>The event is carried in a custom {@code ZEV} segment using the standard
 * HL7 delimiters and the same escape codec as the exchange HL7 adapters. Every
 * event field, including the immutable identifier and per-aggregate revision,
 * survives the wire round trip so a replay through this adapter produces the
 * same ledger outcome as a direct apply (ARC-05, XCH-04).</p>
 */
public final class Hl7PartnerEventAdapter implements PartnerEventAdapter {

    private static final Hl7Delimiters DELIMITERS = Hl7Delimiters.standard();
    private static final String FIELD = String.valueOf(DELIMITERS.field());
    private static final Pattern FIELD_PATTERN = Pattern.compile(FIELD, Pattern.LITERAL);

    @Override
    public String name() {
        return "hl7";
    }

    @Override
    public String encode(PartnerEvent event) {
        StringBuilder wire = new StringBuilder();
        wire.append("MSH").append(FIELD).append("PARTNER").append(FIELD)
                .append(escape(event.eventId())).append('\r');
        wire.append("ZEV").append(FIELD)
                .append(escape(event.eventId())).append(FIELD)
                .append(escape(event.sender())).append(FIELD)
                .append(escape(event.kind().wire())).append(FIELD)
                .append(escape(event.aggregateKey())).append(FIELD)
                .append(escape(Long.toString(event.revision()))).append(FIELD)
                .append(escape(event.identifier())).append(FIELD)
                .append(escape(event.unit())).append(FIELD)
                .append(escape(event.payload())).append('\r');
        return wire.toString();
    }

    @Override
    public PartnerEvent decode(String wire) {
        String segment = null;
        for (String line : wire.split("\r", -1)) {
            if (line.startsWith("ZEV" + FIELD)) {
                segment = line;
                break;
            }
        }
        if (segment == null) {
            throw new IllegalArgumentException("missing ZEV segment");
        }
        String[] fields = FIELD_PATTERN.split(segment, -1);
        String unit = Hl7Escape.decode(fields[7], DELIMITERS);
        if ("".equals(unit)) {
            unit = null;
        }
        String identifier = Hl7Escape.decode(fields[6], DELIMITERS);
        if ("".equals(identifier)) {
            identifier = null;
        }
        return new PartnerEvent(
                Hl7Escape.decode(fields[1], DELIMITERS),
                Hl7Escape.decode(fields[2], DELIMITERS),
                EventKind.from(Hl7Escape.decode(fields[3], DELIMITERS)),
                Hl7Escape.decode(fields[4], DELIMITERS),
                Long.parseLong(Hl7Escape.decode(fields[5], DELIMITERS)),
                identifier,
                unit,
                Hl7Escape.decode(fields[8], DELIMITERS));
    }

    private static String escape(String value) {
        return Hl7Escape.encode(value, DELIMITERS);
    }
}
