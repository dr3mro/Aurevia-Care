package com.aurevia.smartclinic.staffmsg.policy;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentContract.MediaType;

/**
 * Value contract for effective-dated staff messaging policy (MSG-07).
 *
 * <p>Every type here is a non-sensitive configuration fact. The contract owns
 * only validation and immutability; the authoritative consumers of attachment
 * types, cross-facility permission and retention remain in the attachment and
 * directory slices until a follow-up migrates them to read this policy. The
 * allowed attachment types are the same PDF, JPEG and PNG vocabulary as the
 * document domain, and retention is expressed in the same day units as the
 * messaging audit retention policy.</p>
 */
public final class MessagingPolicyContract {

    private MessagingPolicyContract() {
    }

    /**
     * The scope a policy revision governs.
     */
    public enum PolicyScope {
        /** A tenant-wide policy that applies to every facility. */
        TENANT,
        /** A policy that applies to one facility inside the tenant. */
        FACILITY
    }

    /**
     * The stipulated audience relation for a message under this policy.
     *
     * <p>The kernel stores and serves the rule; conversation services remain
     * responsible for enforcing it against tenant-scoped membership data.</p>
     */
    public enum MessageRule {
        /** Any active staff member in scope may address any other. */
        ALL_STAFF,
        /** Only staff who share a care assignment may address one another. */
        CARE_TEAM
    }

    /**
     * One immutable, effective-dated set of messaging policy settings.
     *
     * <p>At least one allowed attachment type is required because a policy that
     * permits no file type is indistinguishable from a missing or corrupt
     * configuration; callers that want attachments closed must leave them out
     * of the message instead. Retention is bounded so a misconfigured value
     * cannot keep or purge clinical material for an unbounded period.</p>
     *
     * @param messageRule          stipulated sender-to-recipient rule
     * @param allowedTypes         non-empty set of permitted attachment media types
     * @param retentionDays        retention window in days, between 1 and 3650
     * @param crossFacilityEnabled whether an approved cross-facility message is permitted
     * @param effectiveFrom        instant from which this version applies
     * @param version              monotonically increasing version within its scope
     * @return the immutable settings
     */
    public record MessagingPolicySettings(MessageRule messageRule, Set<MediaType> allowedTypes,
                                          long retentionDays, boolean crossFacilityEnabled,
                                          Instant effectiveFrom, long version) {

        /** Smallest accepted retention window in days. */
        public static final long MIN_RETENTION_DAYS = 1L;
        /** Largest accepted retention window in days, ten years. */
        public static final long MAX_RETENTION_DAYS = 3650L;
        /** Fail-closed retention used when no approved revision is effective. */
        public static final long DEFAULT_RETENTION_DAYS = 90L;

        /**
         * Validate the settings and freeze the attachment-type snapshot.
         *
         * @throws NullPointerException when the rule or effective instant is absent
         * @throws ApiException with status 422 when types are absent/empty or retention is out of bounds
         */
        public MessagingPolicySettings {
            Objects.requireNonNull(messageRule, "messageRule");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            allowedTypes = requireAllowedTypes(allowedTypes);
            if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
                throw new ApiException(422, "INVALID_RETENTION_DAYS",
                        "retentionDays must be " + MIN_RETENTION_DAYS + "-" + MAX_RETENTION_DAYS);
            }
        }

        /**
         * Require a non-empty attachment-type set and copy it.
         *
         * @param types caller-supplied types, possibly absent
         * @return an immutable snapshot of the types
         * @throws ApiException with status 422 when the set is absent or empty
         */
        private static Set<MediaType> requireAllowedTypes(Set<MediaType> types) {
            if (types == null || types.isEmpty()) {
                throw new ApiException(422, "INVALID_ALLOWED_TYPES",
                        "at least one allowed attachment type is required");
            }
            return Set.copyOf(types);
        }

        /**
         * Return the immutable attachment-type snapshot.
         *
         * @return the permitted media types
         */
        @Override
        public Set<MediaType> allowedTypes() {
            return Set.copyOf(allowedTypes);
        }
    }

    /**
     * One append-only revision of a tenant or facility policy.
     *
     * <p>A revision is never mutated: a change appends a new version with a new
     * effective instant, so an in-flight message can keep the settings that
     * were effective when it was created.</p>
     *
     * @param id             opaque revision identifier
     * @param organizationId owning organization
     * @param scope          tenant or facility scope
     * @param facilityCode   tenant-local facility code for facility scope, otherwise {@code null}
     * @param settings       effective-dated settings snapshot
     * @param changedBy      authenticated actor who appended the revision
     * @param recordedAt     server instant at which the revision was appended
     * @return the immutable revision
     */
    public record PolicyRevision(String id, String organizationId, PolicyScope scope, String facilityCode,
                                 MessagingPolicySettings settings, String changedBy, Instant recordedAt) {
    }
}
