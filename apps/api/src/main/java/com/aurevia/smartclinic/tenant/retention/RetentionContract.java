package com.aurevia.smartclinic.tenant.retention;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;

/**
 * Fail-closed value vocabulary for OPS-07 tenant retention and offboarding.
 *
 * <p>Every value here is a non-sensitive governance fact: identifiers,
 * instants, reasons and approvers. No clinical payload crosses this boundary.
 * Existing messaging-governance facts are reused instead of copied so a legal
 * hold, a retention policy and a retention decision mean the same thing in
 * both domains. A caller supplies only tenant-scoped inputs and persists the
 * returned ledger entries; this kernel owns the decision, not the transport.</p>
 */
public final class RetentionContract {

    private RetentionContract() {
    }

    /**
     * The governed offboarding steps in their mandatory execution order.
     *
     * <p>The wire keys are the stable OPS-07 step identifiers. Deletion and
     * backup expiry follow the verified export, never precede it.</p>
     */
    public enum OffboardingStep {
        /** Revoke active access before touching data. */
        SUSPEND_ACCESS("suspend_access"),
        /** Produce and verify the tenant export. */
        EXPORT("export"),
        /** Remove active data after the review window. */
        DELETE_ACTIVE_DATA("delete_active_data"),
        /** Expire approved backup sets. */
        EXPIRE_BACKUPS("expire_backups"),
        /** Record the completed offboarding in the audit trail. */
        AUDIT("audit");

        private final String wire;

        OffboardingStep(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire identifier.
         *
         * @return lowercase OPS-07 step identifier
         */
        public String wire() {
            return wire;
        }

        /**
         * Return all governed steps in their mandatory order.
         *
         * @return immutable ordered step list
         */
        public static List<OffboardingStep> ordered() {
            return List.copyOf(Arrays.asList(values()));
        }
    }

    /** Lifecycle of an offboarding plan: review first, deletion marker second. */
    public enum OffboardingStatus {
        /** The export exists; the review window has not elapsed. */
        PENDING_REVIEW,
        /** The review window elapsed; the tenant is marked for deletion. */
        DELETION_SCHEDULED
    }

    /**
     * One synthetic active-data record considered for retention.
     *
     * @param id           record identifier in the active tenant
     * @param resourceType family used to find the approved retention policy
     * @param createdAt    server creation instant
     */
    public record ActiveRecord(String id, String resourceType, Instant createdAt) {
    }

    /**
     * One approved deletion of active data, recorded in the retention ledger.
     *
     * @param organizationId owning organization
     * @param recordId       deleted record identifier
     * @param reason         non-sensitive approved reason
     * @param approvedBy     named privacy approver
     * @param activeData     always {@code true}: only active data is deleted here
     * @param deletedAt      server instant of the deletion decision
     */
    public record Deletion(String organizationId, String recordId, String reason, String approvedBy,
                           boolean activeData, Instant deletedAt) {
    }

    /**
     * One candidate or recorded backup expiration.
     *
     * @param organizationId owning organization
     * @param backupSet      backup set identifier
     * @param scope          resource scope covered by the backup
     * @param approvedBy     named approver of the expiration
     * @param approved       whether expiration was authorized
     * @param expiresAt      instant from which expiration is due
     */
    public record BackupExpiration(String organizationId, String backupSet, String scope,
                                   String approvedBy, boolean approved, Instant expiresAt) {
    }

    /**
     * Tenant-scoped input for one retention run.
     *
     * @param organizationId owning organization
     * @param resources      active-data records to evaluate
     * @param policies       approved policies keyed by resource type
     * @param holds          legal holds in force for the tenant
     * @param backups        backup expiration candidates
     * @param approvedBy     named approver stamped on every deletion
     * @param deletionReason non-sensitive reason stamped on every deletion
     */
    public record RetentionSnapshot(String organizationId, List<ActiveRecord> resources,
                                    Map<String, RetentionPolicy> policies, List<LegalHold> holds,
                                    List<BackupExpiration> backups, String approvedBy, String deletionReason) {
        /**
         * Validate approval facts and copy all caller-owned collections so a
         * later mutation cannot change a decision.
         *
         * @throws IllegalArgumentException when the approver or reason is blank,
         *                                  because an unattributed deletion would fail OPS-07
         */
        public RetentionSnapshot {
            if (approvedBy == null || approvedBy.isBlank()) {
                throw new IllegalArgumentException("approvedBy must be non-blank");
            }
            if (deletionReason == null || deletionReason.isBlank()) {
                throw new IllegalArgumentException("deletionReason must be non-blank");
            }
            resources = List.copyOf(resources);
            policies = Map.copyOf(policies);
            holds = List.copyOf(holds);
            backups = List.copyOf(backups);
        }

        /** Return an immutable copy of the active records. */
        @Override
        public List<ActiveRecord> resources() {
            return List.copyOf(resources);
        }

        /** Return an immutable copy of the policies keyed by resource type. */
        @Override
        public Map<String, RetentionPolicy> policies() {
            return Map.copyOf(policies);
        }

        /** Return an immutable copy of the legal holds. */
        @Override
        public List<LegalHold> holds() {
            return List.copyOf(holds);
        }

        /** Return an immutable copy of the backup candidates. */
        @Override
        public List<BackupExpiration> backups() {
            return List.copyOf(backups);
        }
    }

    /**
     * The decision output of one retention run.
     *
     * @param executedAt  server instant the run used as its decision clock
     * @param deletions   approved active-data deletions to apply and record
     * @param expirations approved backup expirations to apply and record
     * @param skipped     explicit negative decisions with a stable reason code
     */
    public record RetentionRun(Instant executedAt, List<Deletion> deletions,
                               List<BackupExpiration> expirations, List<RetentionDecision> skipped) {
        /**
         * Freeze every ledger so the result is safe to hand to another boundary.
         *
         * @throws NullPointerException when any ledger is absent, so a partial
         *                              result cannot be mistaken for a complete run
         */
        public RetentionRun {
            deletions = List.copyOf(deletions);
            expirations = List.copyOf(expirations);
            skipped = List.copyOf(skipped);
        }

        /** Return an immutable copy of the approved deletions. */
        @Override
        public List<Deletion> deletions() {
            return List.copyOf(deletions);
        }

        /** Return an immutable copy of the approved backup expirations. */
        @Override
        public List<BackupExpiration> expirations() {
            return List.copyOf(expirations);
        }

        /** Return an immutable copy of the explicit negative decisions. */
        @Override
        public List<RetentionDecision> skipped() {
            return List.copyOf(skipped);
        }
    }

    /**
     * Command to place a tenant in offboarding review.
     *
     * @param organizationId owning organization
     * @param authorizedBy   named actor authorizing offboarding
     * @param auditReference audit fact proving the authorization
     * @param exportId       identifier of the verified export to prove first
     * @param reviewPeriodDays days to wait before deletion may be marked
     */
    public record OffboardingRequest(String organizationId, String authorizedBy, String auditReference,
                                     String exportId, long reviewPeriodDays) {
    }

    /**
     * Immutable offboarding plan with an explicit review boundary.
     *
     * @param organizationId owning organization
     * @param authorizedBy   named authorizing actor
     * @param auditReference authorization audit reference
     * @param exportId       verified export identifier
     * @param steps          governed steps in mandatory order
     * @param status         current lifecycle status
     * @param startedAt      server instant the plan was accepted
     * @param deleteAfter    earliest instant deletion may be marked
     */
    public record Offboarding(String organizationId, String authorizedBy, String auditReference,
                              String exportId, List<OffboardingStep> steps, OffboardingStatus status,
                              Instant startedAt, Instant deleteAfter) {
        /**
         * Freeze the step list so no caller can reorder the governed workflow.
         *
         * @throws NullPointerException when the step list is absent
         */
        public Offboarding {
            steps = List.copyOf(steps);
        }

        /** Return an immutable copy of the governed steps. */
        @Override
        public List<OffboardingStep> steps() {
            return List.copyOf(steps);
        }
    }
}
