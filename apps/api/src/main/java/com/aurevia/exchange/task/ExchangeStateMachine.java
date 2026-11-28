package com.aurevia.exchange.task;

import java.time.Instant;

import com.aurevia.api.ApiException;

/**
 * Pure XCH-02 transition authority (ARC-01, ARC-04).
 *
 * <p>Each command writes exactly one channel. In particular the transmission
 * branch copies the clinical review channel and its evidence unchanged, which
 * is the domain-level form of the rule that a transmission acknowledgment
 * cannot close a clinical task. The database guard enforces single-channel
 * updates and evidence binding for any writer that bypasses this class; role
 * authorization remains a service responsibility.</p>
 */
public final class ExchangeStateMachine {

    private ExchangeStateMachine() {
    }

    /**
     * Advance one channel with no reason to record.
     *
     * @param current current state
     * @param command command to apply
     * @param now     clock instant for clinical review evidence
     * @return the next immutable state
     * @throws ApiException {@code 409} when the transition is not legal
     */
    public static ExchangeTask next(ExchangeTask current, ExchangeCommand command, Instant now) {
        return next(current, command, null, now);
    }

    /**
     * Advance one channel.
     *
     * @param current current state
     * @param command command to apply
     * @param reason  reason to record for reject or cancel, otherwise {@code null}
     * @param now     clock instant for clinical review evidence
     * @return the next immutable state
     * @throws ApiException {@code 409} when the transition is not legal
     */
    public static ExchangeTask next(ExchangeTask current, ExchangeCommand command, String reason,
                                    Instant now) {
        int version = current.version() + 1;
        String recordedReason = reason == null ? current.reason() : reason;
        return switch (command.dimension()) {
            case WORKFLOW -> {
                WorkflowState target = WorkflowState.from(command.targetState());
                if (!current.workflowState().canTransitionTo(target)) {
                    throw conflict();
                }
                yield new ExchangeTask(current.id(), version, current.orderRef(), target,
                        current.transmissionState(), current.clinicalReviewState(), recordedReason,
                        current.reviewedBy(), current.reviewedAt(), current.reviewedRevision(),
                        current.createdAt());
            }
            case TRANSMISSION -> {
                TransmissionState target = TransmissionState.from(command.targetState());
                if (!current.transmissionState().canTransitionTo(target)) {
                    throw conflict();
                }
                yield new ExchangeTask(current.id(), version, current.orderRef(),
                        current.workflowState(), target, current.clinicalReviewState(),
                        current.reason(), current.reviewedBy(), current.reviewedAt(),
                        current.reviewedRevision(), current.createdAt());
            }
            case CLINICAL_REVIEW -> {
                ClinicalReviewState target = ClinicalReviewState.from(command.targetState());
                if (!current.clinicalReviewState().canTransitionTo(target)) {
                    throw conflict();
                }
                // Actor is intentionally absent: the database derives it from the
                // transaction's authenticated subject. Revision and time are bound
                // to this exact command and version.
                // The revision is the version the clinician actually reviewed,
                // which is the version carried by the caller's If-Match precondition.
                yield new ExchangeTask(current.id(), version, current.orderRef(),
                        current.workflowState(), current.transmissionState(), target,
                        current.reason(), null, now, current.version(), current.createdAt());
            }
        };
    }

    /**
     * Read the previous state of the channel a command is about to change.
     *
     * @param current   current state
     * @param dimension channel to read
     * @return the current wire state of that channel
     */
    public static String fromState(ExchangeTask current, ExchangeDimension dimension) {
        return switch (dimension) {
            case WORKFLOW -> current.workflowState().wire();
            case TRANSMISSION -> current.transmissionState().wire();
            case CLINICAL_REVIEW -> current.clinicalReviewState().wire();
        };
    }

    private static ApiException conflict() {
        return new ApiException(409, "INVALID_TRANSITION", "the state channel cannot make that transition");
    }
}
