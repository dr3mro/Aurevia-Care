package com.aurevia.smartclinic.messaging.units;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the MSG-01 / ARC-07 care-unit directory and patient-unit
 * connections.
 *
 * <p>The types here carry no persistence or transport concerns. A connection is
 * always bound to one verified patient, one recipient care unit and one eligible
 * upstream relationship; it is never inferred from demographics or from a
 * directory listing alone.</p>
 */
public final class MessagingContract {

    private MessagingContract() {
    }

    /**
     * The verified upstream relationship that makes a connection eligible.
     *
     * <p>A shared phone number, a matching name or a date of birth is never a
     * connection context: every value here is an independently verified care or
     * ordering relationship with a stable reference.</p>
     */
    public enum ConnectionContext {
        /** An established, verified care relationship with the unit. */
        CARE_RELATIONSHIP("care_relationship"),
        /** An order released to the unit. */
        ORDER("order"),
        /** A referral accepted by the unit. */
        REFERRAL("referral"),
        /** A prescription routed to the unit. */
        PRESCRIPTION("prescription"),
        /** A single-use invitation issued by the unit for one of the above. */
        INVITATION("invitation");

        private final String wire;

        /**
         * Create a context with its stable wire key.
         *
         * @param wire lowercase context key
         */
        ConnectionContext(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase context key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied context key.
         *
         * @param value stored key
         * @return the matching context
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ConnectionContext from(String value) {
            for (ConnectionContext candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown connection context: " + value);
        }
    }

    /**
     * Lifecycle of a patient-unit connection. Revocation stops future messaging
     * through the connection and is the only state move.
     */
    public enum ConnectionState {
        /** Active and usable for protected messaging actions. */
        ACTIVE("active"),
        /** Revoked; no future access is granted through this connection. */
        REVOKED("revoked");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         * @param wire lowercase state key
         */
        ConnectionState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored state key.
         *
         * @param value stored key
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ConnectionState from(String value) {
            for (ConnectionState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown connection state: " + value);
        }
    }

    /**
     * One staffed inbox a verified patient may connect to (MSG-01, MSG-06).
     *
     * @param id             opaque unit identifier in this tenant
     * @param organizationId owning organization
     * @param facilityId     facility the unit belongs to
     * @param name           display name for the patient app
     * @param topics         supported topic keys shown before sending
     * @param active         whether the unit currently accepts connections
     * @return the immutable value
     */
    public record CareUnit(String id, String organizationId, String facilityId, String name,
                           List<String> topics, boolean active) {
        /**
         * Defensively copy the topic list.
         *
         * @param id             opaque unit identifier
         * @param organizationId owning organization
         * @param facilityId     owning facility
         * @param name           display name
         * @param topics         supported topics
         * @param active         availability flag
         */
        public CareUnit {
            topics = List.copyOf(topics);
        }
    }

    /**
     * An independently verified relationship that can authorize one connection.
     *
     * @param id             opaque relationship identifier
     * @param organizationId organization that verified the relationship
     * @param patientId patient the relationship was verified for
     * @param unitId    recipient unit the relationship was issued with
     * @param context   verified relationship kind
     * @param reference stable source reference (order, referral, prescription or invitation)
     * @param active    whether the relationship is still in force
     * @param expiresAt invitation expiry instant, or {@code null} for a standing relationship
     * @param consumed  whether a single-use invitation was already consumed
     * @return the immutable value
     */
    public record EligibleRelationship(String id, String organizationId, String patientId, String unitId,
                                       ConnectionContext context, String reference, boolean active,
                                       Instant expiresAt, boolean consumed) {
    }

    /**
     * A verified, connection-scoped patient-unit relationship (ARC-07).
     *
     * @param id               opaque connection identifier
     * @param organizationId   owning organization
     * @param patientId        verified linked chart
     * @param unitId           single recipient unit
     * @param context          verified relationship kind used to connect
     * @param contextReference source reference that was checked
     * @param relationshipId   eligible relationship this connection was granted from
     * @param state            current lifecycle state
     * @param verifiedBy       authenticated subject that verified the link
     * @param verifiedAt       server verification instant
     * @param createdAt        server creation instant
     * @param revokedAt        revocation instant, or {@code null} while active
     * @return the immutable value
     */
    public record PatientUnitConnection(String id, String organizationId, String patientId,
                                        String unitId, ConnectionContext context, String contextReference,
                                        String relationshipId, ConnectionState state, String verifiedBy,
                                        Instant verifiedAt, Instant createdAt, Instant revokedAt) {
    }

    /**
     * The active care units a verified patient may see in the app.
     *
     * @param units visible units
     * @return the immutable value
     */
    public record UnitDirectory(List<CareUnit> units) {
        /**
         * Defensively copy the unit list.
         *
         * @param units visible units
         */
        public UnitDirectory {
            units = List.copyOf(units);
        }
    }

    /**
     * The connections visible for one verified patient chart.
     *
     * @param connections visible connections
     * @return the immutable value
     */
    public record ConnectionList(List<PatientUnitConnection> connections) {
        /**
         * Defensively copy the connection list.
         *
         * @param connections visible connections
         */
        public ConnectionList {
            connections = List.copyOf(connections);
        }
    }

    /**
     * Connection command. Organization, verifier and timestamps are derived by
     * the server and never accepted from the client.
     *
     * @param patientId        verified linked chart
     * @param unitId           recipient care unit
     * @param context          relationship kind wire key
     * @param contextReference verified source reference
     * @return the immutable command
     */
    public record ConnectRequest(String patientId, String unitId, String context, String contextReference) {
    }

    /**
     * One recorded, non-sensitive messaging authorization fact.
     *
     * @param organizationId owning organization
     * @param subject        authenticated actor
     * @param action         action key
     * @param resourceId     affected resource
     * @param outcome        {@code SUCCESS}, {@code DENIED}, {@code NOT_FOUND} or {@code CONFLICT}
     * @param occurredAt     server timestamp
     * @return the immutable fact
     */
    public record MessagingAudit(String organizationId, String subject, String action,
                                 String resourceId, String outcome, Instant occurredAt) {
    }
}
