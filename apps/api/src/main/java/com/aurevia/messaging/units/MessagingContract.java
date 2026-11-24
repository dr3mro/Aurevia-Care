package com.aurevia.messaging.units;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

import com.aurevia.messaging.conversations.ConversationContract.UnitKey;

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
     * <p>{@code unitKey} is the bounded conversation unit category this inbox
     * serves. It is tenant-scoped directory data: a protected messaging command
     * derives the unit of a verified connection by resolving its unit identifier
     * through this record, never from a global or client-supplied mapping. The
     * unit category binds the directory identity to the conversation unit
     * vocabulary so a connection to one kind cannot be used to address another
     * kind. {@link com.aurevia.messaging.units.MessagingContract.CareUnit#kind()} is the conversation-domain alias for the same value.</p>
     *
     * @return the immutable value
     */
    public record CareUnit(String id, String organizationId, String facilityId, String name,
                           List<String> topics, boolean active, String unitKey) {
        /**
         * Create a care unit without a conversation unit category. Kept for
         * directories that only support connection and directory operations; a
         * protected messaging command denies such a unit when it is resolved.
         *
         * @param id             care-unit identifier
         * @param organizationId owning organization
         * @param facilityId     owning facility
         * @param name           display name
         * @param topics         supported topics
         * @param active         whether the unit is staffed and accepting messages
         */
        public CareUnit(String id, String organizationId, String facilityId, String name,
                        List<String> topics, boolean active) {
            this(id, organizationId, facilityId, name, topics, active, null);
        }

        /**
         * Create a care unit with a bounded conversation unit kind. This is the
         * conversation-domain constructor: the kind is validated against the
         * approved {@link com.aurevia.messaging.conversations.ConversationContract.UnitKey} vocabulary at construction so an unknown kind
         * never reaches a directory or a connection.
         *
         * @param id             care-unit identifier
         * @param organizationId owning organization
         * @param facilityId     owning facility
         * @param name           display name
         * @param kind           bounded conversation unit key
         * @param topics         supported topics
         * @param active         whether the unit is staffed and accepting messages
         * @throws IllegalArgumentException when the kind is not an approved care unit key
         */
        public CareUnit(String id, String organizationId, String facilityId, String name,
                        String kind, List<String> topics, boolean active) {
            this(id, organizationId, facilityId, name, topics, active, requireKind(kind));
        }

        /**
         * Validate a conversation unit kind and return its stable wire value.
         *
         * @param value supplied kind
         * @return the canonical wire value
         * @throws IllegalArgumentException when the kind is unknown or absent
         */
        private static String requireKind(String value) {
            try {
                return UnitKey.from(value).wire();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown care unit kind: " + value, exception);
            }
        }

        /**
         * Defensively copy the topic list.
         *
         */
        public CareUnit {
            topics = List.copyOf(topics);
        }

        /**
         * Return the bounded conversation unit category declared by this inbox.
         *
         * <p>This is the conversation-domain alias of the directory's
         * {@link com.aurevia.messaging.units.MessagingContract.CareUnit#unitKey()} and is always the same value.</p>
         *
         * @return the bounded unit key, or {@code null} for a directory-only unit
         */
        public String kind() {
            return unitKey;
        }
    }

    /**
     * An independently verified relationship that can authorize one connection.
     *
     * @return the immutable value
     */
    public record EligibleRelationship(String id, String organizationId, String patientId, String unitId,
                                       ConnectionContext context, String reference, boolean active,
                                       Instant expiresAt, boolean consumed) {
    }

    /**
     * A verified, connection-scoped patient-unit relationship (ARC-07).
     *
     * @return the immutable value
     */
    public record PatientUnitConnection(String id, String organizationId, String patientId,
                                        String unitId, ConnectionContext context, String contextReference,
                                        String relationshipId, ConnectionState state, String verifiedBy,
                                        Instant verifiedAt, Instant createdAt, Instant revokedAt) {
    }

    /**
     * The authoritative result of resolving a client-supplied connection id
     * through the tenant's directory: the active connection and the conversation
     * unit category declared by its recipient care unit.
     *
     * @param connection tenant-resolved active connection
     * @param unitKey    bounded conversation unit key from the tenant directory
     * @return the immutable value
     */
    public record VerifiedConnection(PatientUnitConnection connection, String unitKey) {
    }

    /**
     * The active care units a verified patient may see in the app.
     *
     * @return the immutable value
     */
    public record UnitDirectory(List<CareUnit> units) {
        /**
         * Defensively copy the unit list.
         *
         */
        public UnitDirectory {
            units = List.copyOf(units);
        }
    }

    /**
     * The connections visible for one verified patient chart.
     *
     * @return the immutable value
     */
    public record ConnectionList(List<PatientUnitConnection> connections) {
        /**
         * Defensively copy the connection list.
         *
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
