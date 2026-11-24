package com.aurevia.exchange.task;

import java.util.Locale;

/**
 * Explicit POST commands of the XCH-02 slice (ARC-03, ARC-04).
 *
 * <p>Every command maps to exactly one
 * {@link com.aurevia.exchange.task.ExchangeDimension} and one target
 * state. No command accepts an actor, a timestamp or a clinical field from the
 * caller: the human binding is derived inside the guarded transaction.</p>
 */
public enum ExchangeCommand {

    /** Workflow: requested → accepted. */
    ACCEPT("accept", ExchangeDimension.WORKFLOW, WorkflowState.ACCEPTED.wire(), false),
    /** Workflow: accepted → processing. */
    PROCESS("process", ExchangeDimension.WORKFLOW, WorkflowState.PROCESSING.wire(), false),
    /** Workflow: processing → completed. */
    COMPLETE("complete", ExchangeDimension.WORKFLOW, WorkflowState.COMPLETED.wire(), false),
    /** Workflow: requested → rejected, with reason. */
    REJECT("reject", ExchangeDimension.WORKFLOW, WorkflowState.REJECTED.wire(), true),
    /** Workflow: requested or accepted → cancelled, with reason. */
    CANCEL("cancel", ExchangeDimension.WORKFLOW, WorkflowState.CANCELLED.wire(), true),
    /** Transmission: pending → sent. */
    SEND("send", ExchangeDimension.TRANSMISSION, TransmissionState.SENT.wire(), false),
    /** Transmission: sent → delivered. This is a delivery receipt, not clinical acknowledgment. */
    DELIVER("deliver", ExchangeDimension.TRANSMISSION, TransmissionState.DELIVERED.wire(), false),
    /** Transmission: pending or sent → failed. */
    FAIL("fail", ExchangeDimension.TRANSMISSION, TransmissionState.FAILED.wire(), false),
    /** Clinical review: unreviewed → acknowledged by an authorized human. */
    ACKNOWLEDGE("acknowledge", ExchangeDimension.CLINICAL_REVIEW,
            ClinicalReviewState.ACKNOWLEDGED.wire(), false),
    /** Clinical review: acknowledged → action pending. */
    REQUEST_ACTION("request-action", ExchangeDimension.CLINICAL_REVIEW,
            ClinicalReviewState.ACTION_PENDING.wire(), false),
    /** Clinical review: action pending → resolved. */
    RESOLVE("resolve", ExchangeDimension.CLINICAL_REVIEW, ClinicalReviewState.RESOLVED.wire(), false);

    private final String wire;
    private final ExchangeDimension dimension;
    private final String targetState;
    private final boolean requiresReason;

    /**
     * @param wire
     * @param dimension
     * @param targetState
     * @param requiresReason
     */
    ExchangeCommand(String wire, ExchangeDimension dimension, String targetState, boolean requiresReason) {
        this.wire = wire;
        this.dimension = dimension;
        this.targetState = targetState;
        this.requiresReason = requiresReason;
    }

    /**
     * URL path segment of the command.
     *
     * @return lower-case command name
     */
    public String wire() {
        return wire;
    }

    /**
     * The single state channel this command writes.
     *
     * @return affected dimension
     */
    public ExchangeDimension dimension() {
        return dimension;
    }

    /**
     * Target state in the affected channel.
     *
     * @return lower-case target state
     */
    public String targetState() {
        return targetState;
    }

    /**
     * Whether a recorded reason is mandatory for this command.
     *
     * @return {@code true} for reject and cancel
     */
    public boolean requiresReason() {
        return requiresReason;
    }

    /**
     * Stable audit action key for this command.
     *
     * @return audit action such as {@code EXCHANGE_ACKNOWLEDGE}
     */
    public String auditAction() {
        return "EXCHANGE_" + name();
    }

    /**
     * Parse a URL command name.
     *
     * @param value path segment, lower case
     * @return the matching command
     * @throws IllegalArgumentException when the value is unknown
     */
    public static ExchangeCommand from(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (ExchangeCommand command : values()) {
            if (command.wire.equals(normalized)) {
                return command;
            }
        }
        throw new IllegalArgumentException("unknown exchange command");
    }
}
