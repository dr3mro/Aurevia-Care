package com.aurevia.exchange.task;

/**
 * Optional body of a transition command.
 *
 * <p>The only caller-supplied field is a reason for reject and cancel. There is
 * no actor, timestamp, revision or clinical state field, so a client cannot
 * forge the human acknowledgment binding (XCH-02).</p>
 *
 * @param reason recorded reason, or {@code null}
 * @return this record
 */
public record TransitionRequest(String reason) {
}
