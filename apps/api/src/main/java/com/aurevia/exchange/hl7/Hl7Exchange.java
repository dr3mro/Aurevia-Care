package com.aurevia.exchange.hl7;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;

/**
 * Entry point that parses, validates, deduplicates and maps one inbound HL7 v2
 * message to an internal command, returning the matching acknowledgment.
 *
 * <p>No command is produced for a malformed, partial or duplicate message; the
 * acknowledgment carries the reason instead. The control identifier is reserved
 * before mapping and released when mapping fails, so a corrected retry of a
 * rejected message is accepted.</p>
 *
 * <p>The duplicate ledger is process-local in this bounded slice; durable
 * replay protection belongs to the exchange store and is outside this scope.</p>
 */
public final class Hl7Exchange {

    /** Wire type of an ORM order. */
    private static final String ORDER_TYPE = "ORM^O01";

    /** Wire type of an ORU result. */
    private static final String RESULT_TYPE = "ORU^R01";

    private final Clock clock;
    private final Set<String> processedControlIds = ConcurrentHashMap.newKeySet();

    /**
     * Create the processor with an explicit clock for deterministic timestamps.
     *
     * @param clock clock used to stamp acknowledgments
     */
    public Hl7Exchange(Clock clock) {
        this.clock = clock;
    }

    /**
     * Create the processor using the system UTC clock.
     */
    public Hl7Exchange() {
        this(Clock.systemUTC());
    }

    /**
     * Process one inbound message.
     *
     * @param message raw HL7 v2 message text
     * @param context sender-side authorization facts for an order
     * @return acknowledgment and, only on acceptance, the internal command
     */
    public ProcessResult process(String message, Hl7SenderContext context) {
        Hl7Message parsed = null;
        try {
            parsed = Hl7Parser.parse(message);
            String type = parsed.messageType();
            if (ORDER_TYPE.equals(type)) {
                Hl7Mapper.validateOrder(parsed);
            } else if (RESULT_TYPE.equals(type)) {
                Hl7Mapper.validateResult(parsed);
            } else {
                throw new Hl7ParseException("UNSUPPORTED_MESSAGE", "unsupported message type " + type);
            }
        } catch (Hl7ParseException exception) {
            return rejected(Hl7Ack.ERROR, exception.reason(),
                    parsed == null ? "" : parsed.controlId());
        }

        String controlId = parsed.controlId();
        if (controlId.isEmpty()) {
            return rejected(Hl7Ack.ERROR, "MISSING_CONTROL_ID", "");
        }
        // Reserve the id before mapping: a rejected message must not poison a
        // corrected retry of the same control id (released below on failure).
        if (!processedControlIds.add(controlId)) {
            return rejected(Hl7Ack.REJECT, "DUPLICATE_MESSAGE", controlId);
        }

        OrderRequest order = null;
        ImportResultRequest result = null;
        try {
            if (ORDER_TYPE.equals(parsed.messageType())) {
                order = Hl7Mapper.mapOrder(parsed, context);
            } else {
                result = Hl7Mapper.mapResult(parsed);
            }
        } catch (Hl7ParseException exception) {
            // Mapping failed after reservation; free the id for a corrected retry.
            processedControlIds.remove(controlId);
            return rejected(Hl7Ack.ERROR, exception.reason(), controlId);
        }
        Hl7Ack ack = new Hl7Ack(Hl7Ack.ACCEPT, "ACK-" + controlId, controlId, "",
                Hl7Timestamp.format(clock.instant()));
        return new ProcessResult(ack, order, result, "");
    }

    /**
     * Build a negative acknowledgment with no internal command.
     *
     * @param code              negative acknowledgment code
     * @param reason            stable reason code
     * @param originalControlId control identifier of the rejected message
     * @return the rejected processing result
     */
    private ProcessResult rejected(String code, String reason, String originalControlId) {
        Hl7Ack ack = new Hl7Ack(code, "ACK-" + originalControlId, originalControlId, reason,
                Hl7Timestamp.format(clock.instant()));
        return new ProcessResult(ack, null, null, reason);
    }

    /**
     * Outcome of processing one message: exactly one command on acceptance, no
     * command and a reason otherwise.
     *
     * @param ack    acknowledgment to return to the partner
     * @param order  internal order command on an accepted ORM, otherwise null
     * @param result internal result command on an accepted ORU, otherwise null
     * @param reason stable reason code on rejection, otherwise empty
     */
    public record ProcessResult(Hl7Ack ack, OrderRequest order, ImportResultRequest result,
                                String reason) {

        /**
         * Whether an internal command was produced.
         *
         * @return true when the message was accepted
         */
        public boolean applied() {
            return order != null || result != null;
        }
    }
}
