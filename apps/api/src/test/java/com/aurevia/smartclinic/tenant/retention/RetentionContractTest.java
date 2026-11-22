package com.aurevia.smartclinic.tenant.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.ActiveRecord;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.BackupExpiration;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.Deletion;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.Offboarding;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.OffboardingStatus;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.OffboardingStep;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.RetentionRun;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.RetentionSnapshot;

/**
 * Unit tests for the OPS-07 retention vocabulary's immutability and the fixed
 * offboarding step order. All identifiers are synthetic.
 */
class RetentionContractTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void governedOffboardingStepsAreCompleteAndOrdered() {
        assertEquals(List.of(OffboardingStep.SUSPEND_ACCESS, OffboardingStep.EXPORT,
                OffboardingStep.DELETE_ACTIVE_DATA, OffboardingStep.EXPIRE_BACKUPS, OffboardingStep.AUDIT),
                OffboardingStep.ordered());
        assertEquals("suspend_access", OffboardingStep.SUSPEND_ACCESS.wire());
        assertEquals("delete_active_data", OffboardingStep.DELETE_ACTIVE_DATA.wire());
        assertEquals("audit", OffboardingStep.AUDIT.wire());
    }

    @Test
    void snapshotCopiesItsCollectionsDefensively() {
        List<ActiveRecord> resources = new ArrayList<>();
        Map<String, RetentionPolicy> policies = new HashMap<>();
        List<LegalHold> holds = new ArrayList<>();
        List<BackupExpiration> backups = new ArrayList<>();
        RetentionSnapshot snapshot = new RetentionSnapshot("org-a", resources, policies, holds,
                backups, "approver", "reason");
        resources.add(new ActiveRecord("chart-1", "conversation", NOW));
        policies.put("conversation", new RetentionPolicy("org-a", "conversation", 30));

        assertEquals(List.of(), snapshot.resources());
        assertEquals(Map.of(), snapshot.policies());
        assertEquals(List.of(), snapshot.holds());
        assertEquals(List.of(), snapshot.backups());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.holds().clear());
    }

    @Test
    void snapshotRejectsBlankApprovalFacts() {
        assertThrows(IllegalArgumentException.class, () -> new RetentionSnapshot("org-a", List.of(), Map.of(),
                List.of(), List.of(), null, "reason"));
        assertThrows(IllegalArgumentException.class, () -> new RetentionSnapshot("org-a", List.of(), Map.of(),
                List.of(), List.of(), "  ", "reason"));
        assertThrows(IllegalArgumentException.class, () -> new RetentionSnapshot("org-a", List.of(), Map.of(),
                List.of(), List.of(), "approver", null));
        assertThrows(IllegalArgumentException.class, () -> new RetentionSnapshot("org-a", List.of(), Map.of(),
                List.of(), List.of(), "approver", "  "));
    }

    @Test
    void runCopiesItsLedgersDefensively() {
        List<Deletion> deletions = new ArrayList<>();
        List<BackupExpiration> expirations = new ArrayList<>();
        List<RetentionDecision> skipped = new ArrayList<>();
        RetentionRun run = new RetentionRun(NOW, deletions, expirations, skipped);
        deletions.add(new Deletion("org-a", "chart-1", "reason", "approver", true, NOW));

        assertEquals(List.of(), run.deletions());
        assertEquals(List.of(), run.expirations());
        assertEquals(List.of(), run.skipped());
        assertThrows(UnsupportedOperationException.class, () -> run.deletions().clear());
        assertThrows(UnsupportedOperationException.class, () -> run.skipped().clear());
    }

    @Test
    void offboardingCopiesItsStepsDefensively() {
        List<OffboardingStep> steps = new ArrayList<>(OffboardingStep.ordered());
        Offboarding plan = new Offboarding("org-a", "approver", "audit-ref-1", "export-1", steps,
                OffboardingStatus.PENDING_REVIEW, NOW, NOW.plusSeconds(30));
        steps.clear();

        assertEquals(OffboardingStep.ordered(), plan.steps());
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
    }
}
