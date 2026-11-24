package com.aurevia.audit;

/**
 * One append-only audit event in the active organization (OPS-02).
 *
 * @param id            event identifier
 * @param occurredAt    event instant
 * @param actorId       acting user identifier, or {@code null}
 * @param action        action key such as {@code FACILITY_CREATE}
 * @param resourceType  audited resource type
 * @param resourceId    audited resource identifier
 * @param purpose       recorded business purpose
 * @param scope         recorded scope
 * @param correlationId request correlation identifier
 * @param outcome       success, denied, not-found or conflict outcome
 * @param seq           per-tenant monotonic sequence used for cursor paging
 * @return this record
 */
public record AuditEvent(String id, String occurredAt, String actorId, String action, String resourceType,
                         String resourceId, String purpose, String scope, String correlationId,
                         String outcome, long seq) {
}
