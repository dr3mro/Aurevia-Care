package com.aurevia.config.queues;

import java.time.Instant;

/**
 * One immutable operator action recorded on an ADM-02 work item.
 *
 * <p>The actor, reason and instant are supplied by the caller; this value only
 * records them. No request body, clinical detail or patient identifier belongs
 * here. Operator authorization and durable audit references are enforced at the
 * command boundary, which is outside this domain model.</p>
 *
 * @param type   action verb, {@code assign} or {@code resolve}
 * @param actor  subject that performed the action
 * @param reason non-empty operator reason
 * @param at     instant of the action
 * @return the immutable action
 */
public record QueueAction(String type, String actor, String reason, Instant at) {
}
