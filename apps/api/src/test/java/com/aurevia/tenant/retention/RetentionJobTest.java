package com.aurevia.tenant.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.tenant.retention.RetentionContract.ActiveRecord;
import com.aurevia.tenant.retention.RetentionContract.BackupExpiration;
import com.aurevia.tenant.retention.RetentionContract.RetentionRun;
import com.aurevia.tenant.retention.RetentionContract.RetentionSnapshot;

/**
 * Unit acceptance for the retention job's deletion, hold and backup-expiry
 * decisions. All records are synthetic identifiers and instants; no clinical
 * payload is present.
 */
class RetentionJobTest {

    private static final String ORGANIZATION = "33333333-3333-3333-3333-333333333333";
    private static final String APPROVER = "privacy-owner";
    private static final String REASON = "approved retention policy";
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RetentionJob job = new RetentionJob(CLOCK);

    private static ActiveRecord oldRecord() {
        return new ActiveRecord("chart-1", "conversation", NOW.minus(40, ChronoUnit.DAYS));
    }

    private static Map<String, RetentionPolicy> policy() {
        return Map.of("conversation", new RetentionPolicy(ORGANIZATION, "conversation", 30));
    }

    private static LegalHold activeHold(String scope) {
        return new LegalHold("hold-active", ORGANIZATION, scope, "dispute", "privacy-owner",
                NOW.minus(10, ChronoUnit.DAYS), null, null, null);
    }

    private static LegalHold releasedHold(String scope) {
        return new LegalHold("hold-released", ORGANIZATION, scope, "dispute", "privacy-owner",
                NOW.minus(10, ChronoUnit.DAYS), "privacy-owner", NOW.minus(1, ChronoUnit.DAYS), "resolved");
    }

    private static BackupExpiration backup(boolean approved, Instant expiresAt) {
        return new BackupExpiration(ORGANIZATION, "backup-set-1", "conversation", APPROVER, approved, expiresAt);
    }

    private static RetentionSnapshot snapshot(List<ActiveRecord> records, Map<String, RetentionPolicy> policies,
                                              List<LegalHold> holds, List<BackupExpiration> backups) {
        return new RetentionSnapshot(ORGANIZATION, records, policies, holds, backups, APPROVER, REASON);
    }

    @Test
    void eligibleRecordIsDeletedAndLedgerRecordsApproval() {
        RetentionRun result = job.run(snapshot(List.of(oldRecord()), policy(), List.of(), List.of()));

        assertEquals(1, result.deletions().size());
        assertEquals(ORGANIZATION, result.deletions().get(0).organizationId());
        assertEquals("chart-1", result.deletions().get(0).recordId());
        assertEquals(REASON, result.deletions().get(0).reason());
        assertEquals(APPROVER, result.deletions().get(0).approvedBy());
        assertTrue(result.deletions().get(0).activeData());
        assertEquals(NOW, result.deletions().get(0).deletedAt());
        assertEquals(NOW, result.executedAt());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void legalHoldBlocksDeletion() {
        RetentionRun result = job.run(snapshot(List.of(oldRecord()), policy(),
                List.of(activeHold("chart-1")), List.of()));

        assertTrue(result.deletions().isEmpty());
        assertEquals(List.of(new RetentionDecision("chart-1", false, "LEGAL_HOLD")), result.skipped());
    }

    @Test
    void releasedHoldLetsTheNextRunDelete() {
        RetentionSnapshot held = snapshot(List.of(oldRecord()), policy(), List.of(activeHold("chart-1")), List.of());
        RetentionSnapshot lifted = snapshot(List.of(oldRecord()), policy(), List.of(releasedHold("chart-1")), List.of());

        assertTrue(job.run(held).deletions().isEmpty());
        assertEquals("chart-1", job.run(lifted).deletions().get(0).recordId());
    }

    @Test
    void holdOnDescendantBlocksDeletionOfItsAncestor() {
        RetentionRun result = job.run(snapshot(List.of(oldRecord()), policy(),
                List.of(activeHold("chart-1/note-2")), List.of()));

        assertTrue(result.deletions().isEmpty());
        assertEquals("LEGAL_HOLD", result.skipped().get(0).reason());
    }

    @Test
    void recordInsideRetentionWindowIsSkipped() {
        ActiveRecord recent = new ActiveRecord("chart-2", "conversation", NOW.minus(10, ChronoUnit.DAYS));
        RetentionRun result = job.run(snapshot(List.of(recent), policy(), List.of(), List.of()));

        assertTrue(result.deletions().isEmpty());
        assertEquals(List.of(new RetentionDecision("chart-2", false, "RETENTION_WINDOW")), result.skipped());
    }

    @Test
    void recordExactlyAtRetentionBoundaryIsDeleted() {
        ActiveRecord due = new ActiveRecord("chart-3", "conversation", NOW.minus(30, ChronoUnit.DAYS));
        RetentionRun result = job.run(snapshot(List.of(due), policy(), List.of(), List.of()));

        assertEquals(1, result.deletions().size());
        assertEquals("chart-3", result.deletions().get(0).recordId());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void recordWithoutApprovedPolicyIsSkipped() {
        RetentionRun result = job.run(snapshot(List.of(oldRecord()), Map.of(), List.of(), List.of()));

        assertTrue(result.deletions().isEmpty());
        assertEquals(List.of(new RetentionDecision("chart-1", false, "NO_POLICY")), result.skipped());
    }

    @Test
    void policyFromAnotherTenantIsSkipped() {
        Map<String, RetentionPolicy> foreign = Map.of("conversation",
                new RetentionPolicy("55555555-5555-5555-5555-555555555555", "conversation", 30));
        RetentionRun result = job.run(snapshot(List.of(oldRecord()), foreign, List.of(), List.of()));

        assertTrue(result.deletions().isEmpty());
        assertEquals(List.of(new RetentionDecision("chart-1", false, "NO_POLICY")), result.skipped());
    }

    @Test
    void backupFromAnotherTenantIsSkipped() {
        BackupExpiration foreign = new BackupExpiration("55555555-5555-5555-5555-555555555555",
                "backup-set-1", "conversation", APPROVER, true, NOW.minus(1, ChronoUnit.DAYS));
        RetentionRun result = job.run(snapshot(List.of(), Map.of(), List.of(), List.of(foreign)));

        assertTrue(result.expirations().isEmpty());
        assertEquals(List.of(new RetentionDecision("backup-set-1", false, "WRONG_TENANT")), result.skipped());
    }

    @Test
    void approvedDueBackupIsExpiredAndRecorded() {
        BackupExpiration due = backup(true, NOW.minus(1, ChronoUnit.DAYS));
        RetentionRun result = job.run(snapshot(List.of(), Map.of(), List.of(), List.of(due)));

        assertEquals(List.of(due), result.expirations());
        assertTrue(result.skipped().isEmpty());
    }

    @Test
    void unapprovedBackupIsSkipped() {
        RetentionRun result = job.run(snapshot(List.of(), Map.of(), List.of(),
                List.of(backup(false, NOW.minus(1, ChronoUnit.DAYS)))));

        assertTrue(result.expirations().isEmpty());
        assertEquals(List.of(new RetentionDecision("backup-set-1", false, "UNAPPROVED")), result.skipped());
    }

    @Test
    void backupThatIsNotYetDueIsSkipped() {
        RetentionRun result = job.run(snapshot(List.of(), Map.of(), List.of(),
                List.of(backup(true, NOW.plus(1, ChronoUnit.DAYS)))));

        assertTrue(result.expirations().isEmpty());
        assertEquals(List.of(new RetentionDecision("backup-set-1", false, "NOT_DUE")), result.skipped());
    }

    @Test
    void backupUnderHoldIsSkippedBySetAndByScope() {
        BackupExpiration due = backup(true, NOW.minus(1, ChronoUnit.DAYS));
        RetentionRun bySet = job.run(snapshot(List.of(), Map.of(), List.of(activeHold("backup-set-1")), List.of(due)));
        RetentionRun byScope = job.run(snapshot(List.of(), Map.of(), List.of(activeHold("conversation")), List.of(due)));

        assertTrue(bySet.expirations().isEmpty());
        assertEquals("LEGAL_HOLD", bySet.skipped().get(0).reason());
        assertTrue(byScope.expirations().isEmpty());
        assertEquals("LEGAL_HOLD", byScope.skipped().get(0).reason());
    }

    @Test
    void emptySnapshotProducesAnEmptyRun() {
        RetentionRun result = job.run(snapshot(List.of(), Map.of(), List.of(), List.of()));

        assertTrue(result.deletions().isEmpty());
        assertTrue(result.expirations().isEmpty());
        assertTrue(result.skipped().isEmpty());
        assertEquals(NOW, result.executedAt());
    }

    @Test
    void returnedLedgersAreImmutable() {
        RetentionRun result = job.run(snapshot(List.of(oldRecord()), policy(), List.of(),
                List.of(backup(true, NOW.minus(1, ChronoUnit.DAYS)))));

        assertThrows(UnsupportedOperationException.class, () -> result.deletions().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.expirations().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.skipped().clear());
        assertFalse(result.deletions().isEmpty());
    }
}
