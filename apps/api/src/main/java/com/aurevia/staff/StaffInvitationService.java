package com.aurevia.staff;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.Subject;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Staff invitation invariants: bounded issuance, concealment and single-use acceptance (IAM-02).
 *
 * <p>Administration commands are authorized against the authoritative membership
 * fold on every call, so a deactivation denies the next request without waiting
 * for a session to expire. Acceptance is authorized by the one-time token inside
 * an active tenant context; the database consumes the token, creates the
 * membership and records the inviter as grantor in one atomic transaction. Every
 * outcome, including denials, is audited in that same transaction (OPS-02).</p>
 */
@Service
public class StaffInvitationService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{8,128}");
    private static final Pattern UUID_KEY = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_DISPLAY_NAME = 200;
    private static final int MIN_TTL_SECONDS = 60;
    private static final int MAX_TTL_SECONDS = 86_400;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StaffInvitationStore store;
    private final StaffRepository staff;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store        tenant-bounded invitation store
     * @param staff        authoritative staff membership and audit boundary
     * @param policy       bounded action policy
     * @param transactions tenant-guarded transaction template
     * @param clock        injected clock used to compute the expiry instant
     */
    public StaffInvitationService(StaffInvitationStore store, StaffRepository staff, ActionPolicy policy,
                                  TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.staff = staff;
        this.policy = policy;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Issue a single-use, expiring invitation with bounded roles.
     *
     * @param subject     invited subject
     * @param displayName invited display name
     * @param facilityId  facility identifier
     * @param roles       bounded role keys
     * @param ttlSeconds  lifetime between 60 and 86400 seconds
     * @return the issue response containing the one-time raw token
     * @throws ApiException on invalid input, denial or a duplicate subject
     */
    public StaffInvitationIssue create(String subject, String displayName, String facilityId, List<String> roles,
                                       long ttlSeconds) {
        validateSubject(subject);
        if (displayName == null || displayName.isBlank() || displayName.length() > MAX_DISPLAY_NAME) {
            throw new ApiException(422, "INVALID_DISPLAY_NAME", "display name must be 1-200 characters");
        }
        if (facilityId == null || !UUID_KEY.matcher(facilityId).matches()) {
            throw new ApiException(422, "INVALID_FACILITY", "facilityId must be a UUID");
        }
        if (roles == null || roles.isEmpty() || !StaffService.ROLES.containsAll(roles)) {
            throw new ApiException(422, "INVALID_ROLES", "roles must be a non-empty set of bounded role keys");
        }
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new ApiException(422, "INVALID_TTL", "ttlSeconds must be between 60 and 86400");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffInvitationIssue issue = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_INVITATION_CREATE", "STAFF_INVITE", subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (staff.findUserIdBySubject(subject).isPresent()) {
                staff.appendAudit(context, "STAFF_INVITATION_CREATE", subject, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "staff subject already exists"));
                return null;
            }
            String id = UUID.randomUUID().toString();
            String token = newToken();
            Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
            try {
                store.insert(context.organizationId(), id, subject, displayName, facilityId, roles,
                        context.subject(), token, expiresAt);
            } catch (DuplicateKeyException exception) {
                staff.appendAudit(context, "STAFF_INVITATION_CREATE", subject, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "staff subject already exists"));
                return null;
            }
            staff.appendAudit(context, "STAFF_INVITATION_CREATE", subject, "SUCCESS");
            return new StaffInvitationIssue(id, subject, token, expiresAt.toString(), roles);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return issue;
    }

    /**
     * List invitations that are still pending in the active organization.
     *
     * @return pending invitation projections
     * @throws ApiException on invalid input or denied access
     */
    public List<StaffInvitation> listPending() {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<StaffInvitation> pending = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_INVITATION_LIST", "STAFF_READ", "ALL");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<StaffInvitation> result = store.findPending();
            staff.appendAudit(context, "STAFF_INVITATION_LIST", "ALL", "SUCCESS");
            return result;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return pending;
    }

    /**
     * Revoke an unused invitation.
     *
     * @param id opaque invitation identifier
     * @return the revoked projection
     * @throws ApiException on invalid input, denial or an unknown or unusable invitation
     */
    public StaffInvitation revoke(String id) {
        if (id == null || !UUID_KEY.matcher(id).matches()) {
            throw new ApiException(422, "INVALID_INVITATION_ID", "invitation id must be a UUID");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffInvitation revoked = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_INVITATION_REVOKE", "STAFF_INVITE", id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Optional<StaffInvitation> result = store.revoke(id);
            if (result.isEmpty()) {
                staff.appendAudit(context, "STAFF_INVITATION_REVOKE", id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "invitation not found"));
                return null;
            }
            staff.appendAudit(context, "STAFF_INVITATION_REVOKE", id, "SUCCESS");
            return result.get();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return revoked;
    }

    /**
     * Accept an invitation using its one-time token inside an active tenant context.
     *
     * <p>All unusable tokens produce the same outcome, so neither expiry, reuse,
     * revocation nor a wrong tenant can be distinguished by the caller. The
     * database writes the membership, role assignments and token consumption
     * atomically; a failure rolls every one of them back.</p>
     *
     * @param token one-time raw token
     * @return the created membership projection
     * @throws ApiException on invalid input or any unusable token
     */
    public StaffInvitationAcceptance accept(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new ApiException(422, "INVALID_TOKEN", "token must be 8-128 URL or punctuation characters");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffInvitationAcceptance acceptance = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            Optional<String> subject = store.accept(token);
            if (subject.isEmpty()) {
                staff.appendAudit(context, "STAFF_INVITATION_ACCEPT", "ME", "CONFLICT");
                failure.set(new ApiException(409, "INVITATION_UNAVAILABLE",
                        "invitation expired, used, revoked or not found"));
                return null;
            }
            staff.appendAudit(context, "STAFF_INVITATION_ACCEPT", subject.get(), "SUCCESS");
            return new StaffInvitationAcceptance(subject.get(), "active");
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return acceptance;
    }

    private ApiException authorize(TenantContext context, String auditAction, String policyAction,
                                   String resourceId) {
        if (!staff.hasActiveMembership(context.subject())) {
            staff.appendAudit(context, auditAction, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (staff.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(policyAction, role))) {
            staff.appendAudit(context, auditAction, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing action permission");
        }
        return null;
    }

    private static void validateSubject(String subject) {
        try {
            Subject.subject(subject);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        }
    }

    /**
     * Generate a fresh one-time token from a cryptographically strong source.
     *
     * @return base64url token without padding
     */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
