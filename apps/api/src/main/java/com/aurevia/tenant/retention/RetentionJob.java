package com.aurevia.tenant.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.tenant.retention.RetentionContract.ActiveRecord;
import com.aurevia.tenant.retention.RetentionContract.BackupExpiration;
import com.aurevia.tenant.retention.RetentionContract.Deletion;
import com.aurevia.tenant.retention.RetentionContract.RetentionRun;
import com.aurevia.tenant.retention.RetentionContract.RetentionSnapshot;

/**
 * Retention job for active tenant data and approved backup expirations (OPS-07).
 *
 * <p>The job is a pure domain kernel: it consumes a tenant-scoped snapshot and
 * returns ledgers of deletions, expirations and explicit negative decisions for
 * the caller to persist. Every refusal is recorded rather than silently
 * dropped, and a legal hold is evaluated before any retention window so held
 * data is always explained as such. The injected clock makes boundary checks
 * deterministic; scheduling and durable storage belong to the wiring layer.</p>
 */
public final class RetentionJob {

    private final Clock clock;

    /**
     * Create the job with an injectable clock.
     *
     * @param clock application clock used for every decision instant
     */
    public RetentionJob(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluate one tenant snapshot and produce the retention ledgers.
     *
     * @param snapshot tenant-scoped records, policies, holds and backup candidates
     * @return immutable run result with approvals and explicit skips
     */
    public RetentionRun run(RetentionSnapshot snapshot) {
        Instant now = clock.instant();
        List<LegalHold> holds = snapshot.holds();
        List<Deletion> deletions = new ArrayList<>();
        List<BackupExpiration> expirations = new ArrayList<>();
        List<RetentionDecision> skipped = new ArrayList<>();

        for (ActiveRecord resource : snapshot.resources()) {
            if (HoldScope.blocks(holds, resource.id())) {
                skipped.add(new RetentionDecision(resource.id(), false, "LEGAL_HOLD"));
                continue;
            }
            RetentionPolicy policy = snapshot.policies().get(resource.resourceType());
            // A policy from another tenant is no policy at all: never let a
            // mixed-tenant snapshot authorize a deletion in this organization.
            if (policy == null || !snapshot.organizationId().equals(policy.organizationId())) {
                skipped.add(new RetentionDecision(resource.id(), false, "NO_POLICY"));
                continue;
            }
            Instant eligibleAt = resource.createdAt().plus(policy.retentionDays(), ChronoUnit.DAYS);
            if (eligibleAt.isAfter(now)) {
                skipped.add(new RetentionDecision(resource.id(), false, "RETENTION_WINDOW"));
                continue;
            }
            deletions.add(new Deletion(snapshot.organizationId(), resource.id(),
                    snapshot.deletionReason(), snapshot.approvedBy(), true, now));
        }

        for (BackupExpiration backup : snapshot.backups()) {
            if (!snapshot.organizationId().equals(backup.organizationId())) {
                skipped.add(new RetentionDecision(backup.backupSet(), false, "WRONG_TENANT"));
                continue;
            }
            if (!backup.approved()) {
                skipped.add(new RetentionDecision(backup.backupSet(), false, "UNAPPROVED"));
                continue;
            }
            if (backup.expiresAt().isAfter(now)) {
                skipped.add(new RetentionDecision(backup.backupSet(), false, "NOT_DUE"));
                continue;
            }
            if (HoldScope.blocksBackup(holds, backup.backupSet(), backup.scope())) {
                skipped.add(new RetentionDecision(backup.backupSet(), false, "LEGAL_HOLD"));
                continue;
            }
            expirations.add(backup);
        }

        return new RetentionRun(now, deletions, expirations, skipped);
    }
}
