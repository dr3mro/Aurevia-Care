package com.aurevia.smartclinic.tenant.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.Offboarding;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.OffboardingRequest;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.OffboardingStatus;
import com.aurevia.smartclinic.tenant.retention.RetentionContract.OffboardingStep;

/**
 * Unit acceptance for tenant offboarding: an approved export must exist before a
 * tenant is placed in the review window, and deletion cannot be marked early.
 * All identifiers are synthetic.
 */
class OffboardingCommandTest {

    private static final String ORGANIZATION = "44444444-4444-4444-4444-444444444444";
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int REVIEW_DAYS = 30;

    private final OffboardingCommand command = new OffboardingCommand(CLOCK);

    private static OffboardingRequest request() {
        return request(ORGANIZATION, "authorized-admin", "audit-ref-1", "export-1", REVIEW_DAYS);
    }

    private static OffboardingRequest request(String organization, String authorizedBy, String reference,
                                              String exportId, long reviewDays) {
        return new OffboardingRequest(organization, authorizedBy, reference, exportId, reviewDays);
    }

    private static ExportProvenance provenance() {
        return new ExportProvenance("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "rev-1", "record_access");
    }

    private static ExportRecord export() {
        return new ExportRecord("export-1", ORGANIZATION, "*", "authorized-admin",
                NOW.minusSeconds(60), NOW.plusSeconds(60), provenance());
    }

    private static ApiException refusal(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void blankOrganizationIsRefused() {
        assertEquals(422, refusal(() -> command.begin(request(null, "authorized-admin", "audit-ref-1",
                "export-1", REVIEW_DAYS), export())).status());
        assertEquals(422, refusal(() -> command.begin(request("  ", "authorized-admin", "audit-ref-1",
                "export-1", REVIEW_DAYS), export())).status());
    }

    @Test
    void blankAuthorizerIsRefused() {
        assertEquals(422, refusal(() -> command.begin(request(ORGANIZATION, null, "audit-ref-1",
                "export-1", REVIEW_DAYS), export())).status());
    }

    @Test
    void blankAuditReferenceIsRefused() {
        assertEquals(422, refusal(() -> command.begin(request(ORGANIZATION, "authorized-admin", " ",
                "export-1", REVIEW_DAYS), export())).status());
    }

    @Test
    void blankExportReferenceIsRefused() {
        assertEquals(422, refusal(() -> command.begin(request(ORGANIZATION, "authorized-admin", "audit-ref-1",
                null, REVIEW_DAYS), export())).status());
    }

    @Test
    void nonPositiveReviewPeriodIsRefused() {
        assertEquals(422, refusal(() -> command.begin(request(ORGANIZATION, "authorized-admin", "audit-ref-1",
                "export-1", 0), export())).status());
    }

    @Test
    void offboardingWithoutAnExportIsRefused() {
        ApiException error = refusal(() -> command.begin(request(), null));
        assertEquals(409, error.status());
        assertEquals("EXPORT_REQUIRED", error.code());
    }

    @Test
    void exportFromAnotherTenantIsRefused() {
        ExportRecord foreign = new ExportRecord("export-1", "55555555-5555-5555-5555-555555555555",
                "*", "authorized-admin", NOW.minusSeconds(60), NOW.plusSeconds(60), provenance());
        assertEquals(409, refusal(() -> command.begin(request(), foreign)).status());
    }

    @Test
    void exportWithDifferentIdentifierIsRefused() {
        ExportRecord other = new ExportRecord("export-2", ORGANIZATION, "*", "authorized-admin",
                NOW.minusSeconds(60), NOW.plusSeconds(60), provenance());
        assertEquals(409, refusal(() -> command.begin(request(), other)).status());
    }

    @Test
    void expiredExportIsRefused() {
        ExportRecord expired = new ExportRecord("export-1", ORGANIZATION, "*", "authorized-admin",
                NOW.minusSeconds(120), NOW.minusSeconds(1), provenance());
        assertEquals(409, refusal(() -> command.begin(request(), expired)).status());
    }

    @Test
    void beginRecordsGovernedStepsAndReviewWindow() {
        Offboarding plan = command.begin(request(), export());

        assertEquals(ORGANIZATION, plan.organizationId());
        assertEquals("authorized-admin", plan.authorizedBy());
        assertEquals("audit-ref-1", plan.auditReference());
        assertEquals("export-1", plan.exportId());
        assertEquals(OffboardingStep.ordered(), plan.steps());
        assertEquals(OffboardingStatus.PENDING_REVIEW, plan.status());
        assertEquals(NOW, plan.startedAt());
        assertEquals(NOW.plusSeconds(REVIEW_DAYS * 86_400L), plan.deleteAfter());
    }

    @Test
    void markForDeletionBeforeReviewElapsesIsRefused() {
        Offboarding pending = command.begin(request(), export());
        ApiException error = refusal(() -> command.markForDeletion(pending,
                planDeleteAfter(pending).minusNanos(1)));

        assertEquals(409, error.status());
        assertEquals("REVIEW_PERIOD_ACTIVE", error.code());
    }

    @Test
    void markForDeletionAtReviewBoundaryMarksTheTenant() {
        Offboarding pending = command.begin(request(), export());
        Offboarding marked = command.markForDeletion(pending, planDeleteAfter(pending));

        assertEquals(OffboardingStatus.DELETION_SCHEDULED, marked.status());
        assertEquals(pending.organizationId(), marked.organizationId());
        assertEquals(pending.steps(), marked.steps());
        assertEquals(pending.deleteAfter(), marked.deleteAfter());
        assertEquals(pending.exportId(), marked.exportId());
        assertEquals(pending.startedAt(), marked.startedAt());
    }

    @Test
    void markForDeletionTwiceIsRefused() {
        Offboarding pending = command.begin(request(), export());
        Offboarding marked = command.markForDeletion(pending, planDeleteAfter(pending));

        assertEquals("ALREADY_SCHEDULED",
                refusal(() -> command.markForDeletion(marked, planDeleteAfter(pending))).code());
    }

    private static Instant planDeleteAfter(Offboarding plan) {
        return plan.deleteAfter();
    }
}
