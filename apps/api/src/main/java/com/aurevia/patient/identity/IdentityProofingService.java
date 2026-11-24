package com.aurevia.patient.identity;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Patient identity proofing invariants (IAM-05).
 *
 * <p>A site-verified account is created by staff; a single-use, expiring
 * invitation binds it to an independently verified patient chart. Links are
 * append-only events, so unlinking takes effect on the next protected request
 * without editing the earlier linked event. Account ownership follows the
 * immutable chart identifier, never mutable contact data. Every command and
 * denial is audited inside the same tenant-guarded transaction.</p>
 */
@Service
public class IdentityProofingService {

    /**
     * The bounded lowercase subject key mirrored from the product's request
     * boundaries. An alphanumeric start and the exclusion of {@code :} keep the
     * value safe as a routing/log token; dot and underscore stay valid because
     * staff and authz subjects already use them. This mirrors the loose rule
     * pending the canonical {@code subject(...)} primitive from the request
     * boundary unification; replace this pattern with a call to that primitive
     * when it becomes available rather than keeping a second copy of the rule.
     */
    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
    private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{L}\\p{M} '.-]{2,120}");
    private static final Pattern CHART_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{8,128}");
    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9 ._.,:-]{2,120}");
    private static final Set<String> PURPOSES = Set.of("ENROLL", "LINK", "REVERIFY");
    /** Audit action for invitations retired as a side effect of another identity command (OPS-02). */
    private static final String INVITATION_RETIRE = "INVITATION_RETIRE";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityProofingRepository repository;
    private final IdentityActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded identity repository
     * @param policy       package-local staff action policy
     * @param transactions tenant-guarded transaction template
     */
    public IdentityProofingService(IdentityProofingRepository repository, IdentityActionPolicy policy,
                                   TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Enroll a site-verified patient account.
     *
     * @param subject     future login subject of the patient account
     * @param displayName human-readable account label
     * @return the verified account
     * @throws ApiException on invalid input, denial or an existing account
     */
    public PatientAccount enroll(String subject, String displayName) {
        require(SUBJECT, subject, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        require(DISPLAY_NAME, displayName, "INVALID_NAME", "name must be 2-120 letters, spaces or ' . -");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        PatientAccount account = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = denyUnlessStaff(context, IdentityActionPolicy.ACCOUNT_ENROLL,
                    "ACCOUNT_ENROLL", subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.accountExists(subject)) {
                repository.appendAudit(context, "ACCOUNT_ENROLL", subject, "CONFLICT");
                failure.set(new ApiException(409, "ACCOUNT_EXISTS", "account already exists for this subject"));
                return null;
            }
            repository.insertAccount(context.organizationId(), UUID.randomUUID().toString(), subject, displayName);
            repository.appendAudit(context, "ACCOUNT_ENROLL", subject, "SUCCESS");
            return repository.findAccount(subject).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return account;
    }

    /**
     * Issue a single-use, expiring invitation for a verified chart.
     *
     * @param subject     account the invitation is bound to
     * @param chartId     patient chart identifier
     * @param purpose     bounded purpose key
     * @param ttlSeconds  lifetime in seconds, between 60 and 86400
     * @return the issue response containing the one-time raw token
     * @throws ApiException on invalid input, denial or an unknown account or chart
     */
    public InvitationIssue issueInvitation(String subject, String chartId, String purpose, long ttlSeconds) {
        require(SUBJECT, subject, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        require(CHART_ID, chartId, "INVALID_CHART_ID", "chartId must be a UUID");
        if (purpose == null || !PURPOSES.contains(purpose)) {
            throw new ApiException(422, "INVALID_PURPOSE", "purpose must be ENROLL, LINK or REVERIFY");
        }
        if (ttlSeconds < 60 || ttlSeconds > 86_400) {
            throw new ApiException(422, "INVALID_TTL", "ttlSeconds must be between 60 and 86400");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        InvitationIssue issue = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = denyUnlessStaff(context, IdentityActionPolicy.INVITATION_ISSUE,
                    "INVITATION_ISSUE", chartId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.accountExists(subject)) {
                repository.appendAudit(context, "INVITATION_ISSUE", chartId, "NOT_FOUND");
                failure.set(new ApiException(404, "ACCOUNT_NOT_FOUND", "account not found"));
                return null;
            }
            if (!repository.chartExists(chartId)) {
                repository.appendAudit(context, "INVITATION_ISSUE", chartId, "NOT_FOUND");
                failure.set(new ApiException(404, "CHART_NOT_FOUND", "chart not found"));
                return null;
            }
            String token = newToken();
            repository.insertInvitation(context.organizationId(), UUID.randomUUID().toString(), subject,
                    chartId, purpose, token, ttlSeconds);
            repository.appendAudit(context, "INVITATION_ISSUE", chartId, "SUCCESS");
            return new InvitationIssue(subject, chartId, purpose, token,
                    Instant.now().plusSeconds(ttlSeconds).toString());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return issue;
    }

    /**
     * Redeem an invitation and create a verified link for the authenticated account.
     *
     * @param token one-time raw token supplied by the patient
     * @return the current link event
     * @throws ApiException on invalid input, denial, an unknown or unusable invitation
     */
    public ChartLink linkWithInvitation(String token) {
        require(TOKEN, token, "INVALID_TOKEN", "token must be 8-128 URL or punctuation characters");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ChartLink link = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String subject = context.subject();
            if (!repository.accountExists(subject)) {
                repository.appendAudit(context, "CHART_LINK", "ME", "DENIED");
                failure.set(new ApiException(403, "FORBIDDEN", "no verified patient account"));
                return null;
            }
            InvitationRecord invitation = repository.findInvitation(token).orElse(null);
            if (invitation == null) {
                repository.appendAudit(context, "CHART_LINK", "ME", "NOT_FOUND");
                failure.set(new ApiException(404, "INVITATION_NOT_FOUND", "invitation not found"));
                return null;
            }
            if (!subject.equals(invitation.accountSubject())) {
                repository.appendAudit(context, "CHART_LINK", "ME", "DENIED");
                failure.set(new ApiException(403, "FORBIDDEN", "invitation belongs to another account"));
                return null;
            }
            if (invitation.consumed() || !repository.consumeInvitation(invitation.id())) {
                repository.appendAudit(context, "CHART_LINK", "ME", "CONFLICT");
                failure.set(new ApiException(409, "INVITATION_UNAVAILABLE", "invitation expired or already used"));
                return null;
            }
            String chartId = invitation.chartId();
            repository.insertLink(context.organizationId(), UUID.randomUUID().toString(), subject,
                    chartId, "linked", "INVITATION", null);
            repository.appendAudit(context, "CHART_LINK", chartId, "SUCCESS");
            return repository.currentLink(subject, chartId).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return link;
    }

    /**
     * Unlink an account from a chart; the caller is either the account itself or staff.
     *
     * <p>Outstanding invitations for the relationship are retired before the
     * link precondition is evaluated, so even a rejected unlink (no current
     * link) cannot leave a stale token that would silently restore access. The
     * revocation and the command audit commit in the same guarded transaction.</p>
     *
     * @param subject account subject owning the link
     * @param chartId patient chart identifier
     * @param reason  reason recorded on the unlink event
     * @return the current link event, now {@code unlinked}
     * @throws ApiException on invalid input, denial or an unknown link
     */
    public ChartLink unlink(String subject, String chartId, String reason) {
        require(SUBJECT, subject, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        require(CHART_ID, chartId, "INVALID_CHART_ID", "chartId must be a UUID");
        require(REASON, reason, "INVALID_REASON", "reason must be 2-120 characters");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ChartLink link = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String method;
            if (context.subject().equals(subject)) {
                if (!repository.accountExists(subject)) {
                    repository.appendAudit(context, "CHART_UNLINK", chartId, "DENIED");
                    failure.set(new ApiException(403, "FORBIDDEN", "no verified patient account"));
                    return null;
                }
                method = "PATIENT_REQUEST";
            } else {
                ApiException denial = denyUnlessStaff(context, IdentityActionPolicy.LINK_UNLINK,
                        "CHART_UNLINK", chartId);
                if (denial != null) {
                    failure.set(denial);
                    return null;
                }
                method = "STAFF_ACTION";
            }
            // Retire unspent invitations before the link precondition. When no
            // link exists the command still returns 404, but a token issued for
            // this relationship must never outlive an unlink attempt: a dispute
            // can be raised before the patient has redeemed the invitation.
            retireOutstandingInvitations(context, subject, chartId);
            if (repository.currentLink(subject, chartId).isEmpty()) {
                repository.appendAudit(context, "CHART_UNLINK", chartId, "NOT_FOUND");
                failure.set(new ApiException(404, "LINK_NOT_FOUND", "no current link for this chart"));
                return null;
            }
            repository.insertLink(context.organizationId(), UUID.randomUUID().toString(), subject,
                    chartId, "unlinked", method, reason);
            repository.appendAudit(context, "CHART_UNLINK", chartId, "SUCCESS");
            return repository.currentLink(subject, chartId).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return link;
    }

    /**
     * Re-verify a relationship after lost access or a disputed identity.
     *
     * <p>Atomically unlinks the current link and issues a fresh invitation; the
     * account regains access only after the new invitation is redeemed. Prior
     * invitations are retired before the link precondition is evaluated, so a
     * rejected re-verification cannot leave a stale token valid.</p>
     *
     * @param subject account subject owning the link
     * @param chartId patient chart identifier
     * @param reason  reason for re-verification
     * @return the fresh invitation with its one-time token
     * @throws ApiException on invalid input, denial or an unknown link or account
     */
    public InvitationIssue reverify(String subject, String chartId, String reason) {
        require(SUBJECT, subject, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        require(CHART_ID, chartId, "INVALID_CHART_ID", "chartId must be a UUID");
        require(REASON, reason, "INVALID_REASON", "reason must be 2-120 characters");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        InvitationIssue issue = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = denyUnlessStaff(context, IdentityActionPolicy.LINK_REVERIFY,
                    "LINK_REVERIFY", chartId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.accountExists(subject)) {
                repository.appendAudit(context, "LINK_REVERIFY", chartId, "NOT_FOUND");
                failure.set(new ApiException(404, "ACCOUNT_NOT_FOUND", "account not found"));
                return null;
            }
            if (!repository.chartExists(chartId)) {
                repository.appendAudit(context, "LINK_REVERIFY", chartId, "NOT_FOUND");
                failure.set(new ApiException(404, "CHART_NOT_FOUND", "chart not found"));
                return null;
            }
            // Retire old invitations before the link precondition so a rejected
            // re-verification cannot leave a stale token valid. Only a fresh
            // invitation issued by a successful re-verification may restore access.
            retireOutstandingInvitations(context, subject, chartId);
            if (repository.currentLink(subject, chartId).isEmpty()) {
                repository.appendAudit(context, "LINK_REVERIFY", chartId, "NOT_FOUND");
                failure.set(new ApiException(404, "LINK_NOT_FOUND", "no current link for this chart"));
                return null;
            }
            repository.insertLink(context.organizationId(), UUID.randomUUID().toString(), subject,
                    chartId, "unlinked", "REVERIFICATION", reason);
            String token = newToken();
            repository.insertInvitation(context.organizationId(), UUID.randomUUID().toString(), subject,
                    chartId, "REVERIFY", token, 600L);
            repository.appendAudit(context, "LINK_REVERIFY", chartId, "SUCCESS");
            return new InvitationIssue(subject, chartId, "REVERIFY", token,
                    Instant.now().plusSeconds(600).toString());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return issue;
    }

    /**
     * Check whether an account currently has verified access to a chart.
     *
     * @param subject account subject owning the link
     * @param chartId patient chart identifier
     * @return {@code true} when the current link event is a verified link
     * @throws ApiException on invalid input or denial
     */
    public boolean hasAccess(String subject, String chartId) {
        require(SUBJECT, subject, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        require(CHART_ID, chartId, "INVALID_CHART_ID", "chartId must be a UUID");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Boolean access = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = denyUnlessSelfOrStaff(context, subject, chartId);
            if (denial != null) {
                failure.set(denial);
                // A non-null value keeps the Boolean-returning lambda total; the
                // recorded denial is still thrown after the transaction ends.
                return Boolean.FALSE;
            }
            return repository.hasAccess(subject, chartId);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return Boolean.TRUE.equals(access);
    }

    /**
     * List one current link row per chart for an account.
     *
     * @param subject account subject owning the links
     * @return current link events ordered by chart
     * @throws ApiException on invalid input or denial
     */
    public List<ChartLink> links(String subject) {
        require(SUBJECT, subject, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<ChartLink> links = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = denyUnlessSelfOrStaff(context, subject, "ME");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            return repository.links(subject);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return links;
    }

    /**
     * Generate a fresh one-time token from a cryptographically strong source.
     *
     * @return base64url token without padding
     */
    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Retire unspent invitations for one account and chart, auditing the
     * side effect when invitations were actually invalidated (OPS-02).
     *
     * <p>A 404 that retires a credential must be distinguishable in the audit
     * trail from a 404 that changed nothing. The count is returned by the
     * repository; no new public command or HTTP contract is introduced.</p>
     *
     * @param context  active tenant context for the audit stamp
     * @param subject  account subject owning the invitations
     * @param chartId  chart whose invitations are retired
     */
    private void retireOutstandingInvitations(TenantContext context, String subject, String chartId) {
        int retired = repository.consumeOutstandingInvitations(subject, chartId);
        if (retired > 0) {
            repository.appendAudit(context, INVITATION_RETIRE, chartId, "SUCCESS");
        }
    }

    private ApiException denyUnlessStaff(TenantContext context, String action, String auditAction, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, auditAction, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            repository.appendAudit(context, auditAction, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing identity permission");
        }
        return null;
    }

    private ApiException denyUnlessSelfOrStaff(TenantContext context, String subject, String resourceId) {
        if (context.subject().equals(subject)) {
            if (!repository.accountExists(subject)) {
                repository.appendAudit(context, "LINK_READ", resourceId, "DENIED");
                return new ApiException(403, "FORBIDDEN", "no verified patient account");
            }
            return null;
        }
        return denyUnlessStaff(context, IdentityActionPolicy.LINK_READ, "LINK_READ", resourceId);
    }

    private static void require(Pattern pattern, String value, String code, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new ApiException(422, code, message);
        }
    }
}
