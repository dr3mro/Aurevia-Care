package com.aurevia.exchange.order;

import java.time.Instant;

/**
 * Immutable view of a sender's approved exchange authorization.
 *
 * @param id                  opaque authorization identifier
 * @param organizationId      sending organization that owns the authorization
 * @param orderCode           order code covered by the authorization
 * @param destinationOrg      receiving organization covered by the authorization
 * @param purpose             business purpose covered by the authorization
 * @param maxDurationMinutes  maximum validity of a release under the authorization
 * @param effectiveFrom       instant from which the authorization applies
 * @param expiresAt           instant after which the authorization no longer applies, or {@code null}
 * @param active              whether the authorization has not been revoked
 * @return this record
 */
public record AuthorizationView(String id, String organizationId, String orderCode,
                                String destinationOrg, String purpose, int maxDurationMinutes,
                                Instant effectiveFrom, Instant expiresAt, boolean active) {
}
