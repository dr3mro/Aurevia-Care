package com.aurevia.tenant.retention;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.tenant.retention.RetentionContract.Offboarding;
import com.aurevia.tenant.retention.RetentionContract.OffboardingRequest;
import com.aurevia.tenant.retention.RetentionContract.OffboardingStatus;
import com.aurevia.tenant.retention.RetentionContract.OffboardingStep;

/**
 * Tenant offboarding command (OPS-07).
 *
 * <p>Offboarding is fail-closed: the verified export must exist in the active
 * tenant, and deletion may only be marked after a named review period. The
 * command returns a plan with all five governed steps in order; applying the
 * plan's data steps is a separate, audited execution boundary. No approval is
 * inferred from the request body alone — the export is required as proof.</p>
 */
public final class OffboardingCommand {

    private final Clock clock;

    /**
     * Create the command with an injectable clock.
     *
     * @param clock application clock used for review-window boundaries
     */
    public OffboardingCommand(Clock clock) {
        this.clock = clock;
    }

    /**
     * Accept an offboarding request only when a live tenant export exists.
     *
     * @param request validated offboarding command
     * @param export  verified tenant export, or {@code null} when none exists
     * @return pending plan with the governed steps and the deletion boundary
     * @throws ApiException when a field is blank, the review period is invalid
     *                      or the export is absent, foreign, mismatched or expired
     */
    public Offboarding begin(OffboardingRequest request, ExportRecord export) {
        String organizationId = requireText(request.organizationId(), "organizationId");
        String authorizedBy = requireText(request.authorizedBy(), "authorizedBy");
        String auditReference = requireText(request.auditReference(), "auditReference");
        String exportId = requireText(request.exportId(), "exportId");
        if (request.reviewPeriodDays() <= 0) {
            throw new ApiException(422, "INVALID_REVIEW_PERIOD", "reviewPeriodDays must be positive");
        }
        Instant now = clock.instant();
        // The export is the proof that data left the tenant before deletion;
        // without a live, tenant-owned export the request is refused.
        if (export == null || !organizationId.equals(export.organizationId())
                || !exportId.equals(export.id()) || export.expiresAt().isBefore(now)) {
            throw new ApiException(409, "EXPORT_REQUIRED",
                    "a live export of this tenant is required before offboarding");
        }
        return new Offboarding(organizationId, authorizedBy, auditReference, export.id(),
                OffboardingStep.ordered(), OffboardingStatus.PENDING_REVIEW, now,
                now.plus(request.reviewPeriodDays(), ChronoUnit.DAYS));
    }

    /**
     * Mark a reviewed plan for deletion once its review period has elapsed.
     *
     * @param current pending offboarding plan
     * @param asOf    instant at which the marker is applied
     * @return plan marked for deletion with all other facts preserved
     * @throws ApiException when the plan is already marked or review is not over
     */
    public Offboarding markForDeletion(Offboarding current, Instant asOf) {
        if (current.status() != OffboardingStatus.PENDING_REVIEW) {
            throw new ApiException(409, "ALREADY_SCHEDULED", "tenant is already marked for deletion");
        }
        if (asOf.isBefore(current.deleteAfter())) {
            throw new ApiException(409, "REVIEW_PERIOD_ACTIVE", "the offboarding review period has not elapsed");
        }
        return new Offboarding(current.organizationId(), current.authorizedBy(), current.auditReference(),
                current.exportId(), current.steps(), OffboardingStatus.DELETION_SCHEDULED,
                current.startedAt(), current.deleteAfter());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_OFFBOARDING", field + " must be non-blank");
        }
        return value;
    }
}
