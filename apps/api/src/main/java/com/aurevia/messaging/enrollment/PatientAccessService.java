package com.aurevia.messaging.enrollment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessDecision;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessEvent;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountView;
import com.aurevia.messaging.enrollment.EnrollmentContract.CaseState;
import com.aurevia.messaging.enrollment.EnrollmentContract.ChangeContactRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.EventType;
import com.aurevia.messaging.enrollment.EnrollmentContract.GenericNotification;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RecoverRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveAction;
import com.aurevia.messaging.enrollment.EnrollmentContract.SensitiveChangeRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind;

/**
 * PAT-03 privacy-safe patient access workflow.
 *
 * <p>Enrollment requires a site-verified, single-use, expiring invitation bound
 * to a care relationship; no demographic field can link a chart or an account
 * on its own (IAM-05). Recovery requires a fresh single-use code plus
 * re-verification. Repeated verification failures lock the account until a
 * privacy officer clears the lock or a successful recovery restores it.
 * Sensitive changes require short-lived, purpose-bound renewed authentication.
 * A staff contact edit never changes account ownership, and unlinking a chart
 * ends future access to that chart immediately.</p>
 *
 * <p>Every command authorizes against the supplied actor snapshot, writes its
 * state change and append-only access fact in one store call, and emits only a
 * generic notification that cannot carry clinical detail (PAT-01, OPS-02).</p>
 */
@Service
public final class PatientAccessService {

    /** Maximum consecutive failed verification attempts before a lockout. */
    public static final int MAX_FAILURES = 5;

    private static final long DEFAULT_TTL_SECONDS = 3600L;
    private static final long MIN_TTL_SECONDS = 60L;
    private static final long MAX_TTL_SECONDS = 86_400L;
    private static final long RECOVERY_TTL_SECONDS = 900L;
    private static final long REAUTH_TTL_SECONDS = 300L;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final String RECOVERY_PURPOSE = "recovery";
    private static final String RESOURCE_ACCOUNT = "PATIENT_ACCOUNT";
    private static final String RESOURCE_INVITATION = "INVITATION";
    private static final String RESOURCE_CASE = "SUPPORT_CASE";

    /**
     * Digest factory used to avoid persisting raw identity facts.
     */
    @FunctionalInterface
    public interface DigestFactory {
        /**
         * Create a message digest.
         *
         * @return a fresh digest instance
         * @throws NoSuchAlgorithmException when the algorithm is unavailable
         */
        MessageDigest create() throws NoSuchAlgorithmException;
    }

    private final PatientAccessStore store;
    private final EnrollmentActions actions;
    private final Clock clock;
    private final DigestFactory digestFactory;

    /**
     * Create the service over a tenant-scoped store with the platform SHA-256 digest.
     *
     * @param store   tenant-bounded persistence boundary
     * @param actions bounded action matrix
     * @param clock   application clock for deterministic expiry and lockout
     */
    @Autowired
    public PatientAccessService(PatientAccessStore store, EnrollmentActions actions, Clock clock) {
        this(store, actions, clock, () -> MessageDigest.getInstance("SHA-256"));
    }

    /**
     * Create the service with an explicit digest factory.
     *
     * <p>The factory is an extension point for integrations and for a failure
     * test: a missing digest algorithm fails closed rather than storing raw
     * identity facts.</p>
     *
     * @param store        tenant-bounded persistence boundary
     * @param actions      bounded action matrix
     * @param clock        application clock for deterministic expiry and lockout
     * @param digestFactory factory for the identity-fact digest
     */
    public PatientAccessService(PatientAccessStore store, EnrollmentActions actions, Clock clock,
                                DigestFactory digestFactory) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
        this.digestFactory = digestFactory;
    }

    /**
     * Issue a site-verified, single-use enrollment invitation for one chart.
     *
     * @param actor   authenticated staff member
     * @param request issue command
     * @return the created invitation
     * @throws ApiException on denial, invalid input or a missing chart
     */
    public Invitation issueInvitation(PatientAccessActor actor, IssueInvitationRequest request) {
        authorize(actor, EnrollmentActions.INVITE_ISSUE, RESOURCE_INVITATION);
        String chartId = uuid(request.chartId(), "chartId");
        String factDigest = digest(request.factDigest(), "factDigest");
        long ttl = request.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : request.ttlSeconds();
        if (ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) {
            throw invalid("INVALID_TTL", "invitation TTL must be between 60 and 86400 seconds");
        }
        if (request.relationship() == null || !request.relationship().siteVerified()
                || request.relationship().relationshipId().isBlank()) {
            throw invalid("UNVERIFIED_RELATIONSHIP", "a site-verified care relationship is required");
        }
        if (!store.chartExists(actor.organizationId(), chartId)) {
            throw notFound("chart not found");
        }
        Instant now = clock.instant();
        Invitation invitation = new Invitation(newId(), actor.organizationId(), chartId, factDigest,
                now.plusSeconds(ttl), InvitationState.PENDING, 0, actor.subject(), now);
        store.putInvitation(actor.organizationId(), invitation);
        audit(actor, EventType.INVITATION_ISSUED, invitation.code(), "success", "issued");
        return invitation;
    }

    /**
     * Redeem a single-use invitation into a verified patient account.
     *
     * @param actor   authenticated patient
     * @param request enrollment command
     * @return the verified account
     * @throws ApiException on denial, an invalid or reused invitation, lockout or failed verification
     */
    public PatientAccount enroll(PatientAccessActor actor, EnrollRequest request) {
        authorize(actor, EnrollmentActions.INVITE_REDEEM, RESOURCE_ACCOUNT);
        String code = text(request.code(), "code", 128);
        String presented = digest(request.factDigest(), "factDigest");
        Invitation invitation = store.invitation(actor.organizationId(), code)
                .orElseThrow(() -> notFound("invitation not found"));
        if (invitation.state() != InvitationState.PENDING) {
            throw conflict("INVITATION_REUSED", "the invitation was already redeemed");
        }
        if (!invitation.expiresAt().isAfter(clock.instant())) {
            throw conflict("INVITATION_EXPIRED", "the invitation has expired");
        }
        if (invitation.failedAttempts() >= MAX_FAILURES) {
            throw locked();
        }
        if (!invitation.factDigest().equals(presented)) {
            Invitation failed = new Invitation(invitation.code(), invitation.organizationId(),
                    invitation.chartId(), invitation.factDigest(), invitation.expiresAt(),
                    invitation.state(), invitation.failedAttempts() + 1, invitation.issuedBy(), invitation.issuedAt());
            store.recordInvitationFailure(actor.organizationId(), code, failed.failedAttempts());
            audit(actor, EventType.VERIFICATION_FAILED, invitation.code(), "failed", "invalid_facts");
            throw unauthorized("VERIFICATION_FAILED", "identity facts did not match");
        }
        String accountId = newId();
        PatientAccount account = new PatientAccount(accountId, actor.organizationId(), actor.subject(),
                Set.of(invitation.chartId()), invitation.factDigest(), AccountState.ACTIVE, 0, null, clock.instant());
        // Claim the invitation atomically before creating the account: two
        // simultaneous redemptions must not both create a verified account.
        if (!store.consumeInvitation(actor.organizationId(), code)) {
            throw conflict("INVITATION_REUSED", "the invitation was already redeemed");
        }
        store.putAccount(actor.organizationId(), account);
        audit(actor, EventType.ENROLL_SUCCESS, accountId, "success", "enrolled");
        notify(actor, "enrollment_confirmed");
        return account;
    }

    /**
     * Issue a single-use recovery code to a verified account subject.
     *
     * @param actor     authenticated privacy officer or administrator
     * @param accountId account identifier
     * @return the recovery token
     * @throws ApiException on denial, a missing or locked account
     */
    public AccessToken issueRecovery(PatientAccessActor actor, String accountId) {
        authorize(actor, EnrollmentActions.RECOVERY_ISSUE, RESOURCE_ACCOUNT);
        PatientAccount account = requireAccount(actor, uuid(accountId, "accountId"));
        requireUsable(account);
        // The code is delivered to the verified patient, so it is bound to the
        // account subject rather than to the staff member who issued it.
        AccessToken token = newToken(actor.organizationId(), account.subject(), RECOVERY_PURPOSE,
                RECOVERY_TTL_SECONDS);
        store.putToken(actor.organizationId(), token);
        audit(actor, EventType.RECOVERY_ISSUED, account.id(), "success", "issued");
        notify(actor, "recovery_issued");
        return token;
    }

    /**
     * Restore access with a single-use recovery code and re-verified facts.
     *
     * @param actor   authenticated patient
     * @param request recovery command
     * @return the restored account
     * @throws ApiException on denial, an invalid token, lockout or failed verification
     */
    public PatientAccount recover(PatientAccessActor actor, RecoverRequest request) {
        authorize(actor, EnrollmentActions.RECOVERY_REDEEM, RESOURCE_ACCOUNT);
        PatientAccount account = requireOwnedAccount(actor, uuid(request.accountId(), "accountId"));
        requireUsable(account);
        String code = text(request.code(), "code", 128);
        AccessToken token = store.token(actor.organizationId(), code)
                .orElseThrow(() -> notFound("recovery code not found"));
        requireToken(token, actor, RECOVERY_PURPOSE);
        String presented = digest(request.factDigest(), "factDigest");
        if (!account.factDigest().equals(presented)) {
            PatientAccount failed = new PatientAccount(account.id(), account.organizationId(), account.subject(),
                    account.linkedChartIds(), account.factDigest(),
                    account.failedAttempts() + 1 >= MAX_FAILURES ? AccountState.LOCKED : account.state(),
                    account.failedAttempts() + 1, account.contactDigest(), account.createdAt());
            store.putAccount(actor.organizationId(), failed);
            audit(actor, EventType.VERIFICATION_FAILED, account.id(), "failed", "invalid_facts");
            if (failed.state() == AccountState.LOCKED) {
                audit(actor, EventType.ACCOUNT_LOCKED, account.id(), "locked", "threshold_reached");
            }
            throw unauthorized("VERIFICATION_FAILED", "identity facts did not match");
        }
        store.consumeToken(actor.organizationId(), code);
        PatientAccount restored = new PatientAccount(account.id(), account.organizationId(), account.subject(),
                account.linkedChartIds(), account.factDigest(), AccountState.ACTIVE, 0,
                account.contactDigest(), account.createdAt());
        store.putAccount(actor.organizationId(), restored);
        audit(actor, EventType.RECOVERY_SUCCESS, account.id(), "success", "restored");
        return restored;
    }

    /**
     * Clear a lockout after an authorized review.
     *
     * @param actor     privacy officer or administrator
     * @param accountId account identifier
     * @return the unlocked account
     * @throws ApiException on denial or a missing account
     */
    public PatientAccount unlock(PatientAccessActor actor, String accountId) {
        authorize(actor, EnrollmentActions.ACCOUNT_UNLOCK, RESOURCE_ACCOUNT);
        PatientAccount account = requireAccount(actor, uuid(accountId, "accountId"));
        PatientAccount unlocked = new PatientAccount(account.id(), account.organizationId(), account.subject(),
                account.linkedChartIds(), account.factDigest(), AccountState.ACTIVE, 0,
                account.contactDigest(), account.createdAt());
        store.putAccount(actor.organizationId(), unlocked);
        audit(actor, EventType.ACCOUNT_UNLOCKED, account.id(), "success", "reviewed");
        return unlocked;
    }

    /**
     * Issue a short-lived, purpose-bound renewed-authentication token.
     *
     * @param actor   authenticated patient
     * @param purpose sensitive action the token is bound to
     * @return the re-authentication token
     * @throws ApiException on denial or invalid input
     */
    public AccessToken issueReauth(PatientAccessActor actor, String purpose) {
        authorize(actor, EnrollmentActions.SENSITIVE_CHANGE, RESOURCE_ACCOUNT);
        AccessToken token = newToken(actor, text(purpose, "purpose", 64), REAUTH_TTL_SECONDS);
        store.putToken(actor.organizationId(), token);
        audit(actor, EventType.REAUTH_ISSUED, actor.subject(), "success", purpose);
        return token;
    }

    /**
     * Apply a sensitive change after valid renewed authentication.
     *
     * @param actor   authenticated patient
     * @param request sensitive-change command
     * @return the updated account
     * @throws ApiException on denial, a stale token, lockout or invalid input
     */
    public PatientAccount sensitiveChange(PatientAccessActor actor, SensitiveChangeRequest request) {
        authorize(actor, EnrollmentActions.SENSITIVE_CHANGE, RESOURCE_ACCOUNT);
        if (request.action() == null) {
            throw invalid("INVALID_ACTION", "a sensitive action is required");
        }
        PatientAccount account = requireOwnedAccount(actor, uuid(request.accountId(), "accountId"));
        requireUsable(account);
        String tokenValue = text(request.token(), "token", 128);
        AccessToken token = store.token(actor.organizationId(), tokenValue)
                .orElseThrow(() -> unauthorized("STALE_REAUTH", "renewed authentication is required"));
        requireToken(token, actor, request.action().wire());
        store.consumeToken(actor.organizationId(), tokenValue);
        PatientAccount updated;
        if (request.action() == SensitiveAction.UNLINK_CHART) {
            String chartId = uuid(request.value(), "chartId");
            Set<String> links = new HashSet<>(account.linkedChartIds());
            links.remove(chartId);
            updated = new PatientAccount(account.id(), account.organizationId(), account.subject(), links,
                    account.factDigest(), account.state(), account.failedAttempts(), account.contactDigest(),
                    account.createdAt());
            audit(actor, EventType.CHART_UNLINKED, chartId, "success", "future_access_ended");
        } else {
            String contactDigest = digest(request.value(), "contactDigest");
            updated = new PatientAccount(account.id(), account.organizationId(), account.subject(),
                    account.linkedChartIds(), account.factDigest(), account.state(), account.failedAttempts(),
                    contactDigest, account.createdAt());
            audit(actor, EventType.SENSITIVE_CHANGE, account.id(), "success", "contact_updated");
        }
        store.putAccount(actor.organizationId(), updated);
        return updated;
    }

    /**
     * Replace a recovery contact as staff without changing account ownership.
     *
     * @param actor   authenticated staff member
     * @param request contact-change command
     * @return the account with the new contact digest
     * @throws ApiException on denial or invalid input
     */
    public PatientAccount changeStaffContact(PatientAccessActor actor, ChangeContactRequest request) {
        authorize(actor, EnrollmentActions.CONTACT_EDIT_STAFF, RESOURCE_ACCOUNT);
        PatientAccount account = requireAccount(actor, uuid(request.accountId(), "accountId"));
        PatientAccount updated = new PatientAccount(account.id(), account.organizationId(), account.subject(),
                account.linkedChartIds(), account.factDigest(), account.state(), account.failedAttempts(),
                digest(request.contactDigest(), "contactDigest"), account.createdAt());
        store.putAccount(actor.organizationId(), updated);
        audit(actor, EventType.CONTACT_CHANGED, account.id(), "success", "ownership_unchanged");
        return updated;
    }

    /**
     * Check whether a verified chart link currently permits access.
     *
     * @param actor    authenticated patient
     * @param accountId account identifier
     * @param chartId  chart identifier
     * @return an explicit allow or deny decision
     * @throws ApiException on denial or a missing account
     */
    public AccessDecision checkChartAccess(PatientAccessActor actor, String accountId, String chartId) {
        authorize(actor, EnrollmentActions.ACCESS_CHART, RESOURCE_ACCOUNT);
        PatientAccount account = requireOwnedAccount(actor, uuid(accountId, "accountId"));
        String chart = uuid(chartId, "chartId");
        if (account.state() == AccountState.LOCKED || !account.linkedChartIds().contains(chart)) {
            audit(actor, EventType.ACCESS_DENIED, chart, "denied", "link_inactive");
            return new AccessDecision(false, "LINK_INACTIVE", chart);
        }
        return new AccessDecision(true, "ACTIVE", chart);
    }

    /**
     * Open a support case for an identity dispute, accessibility need or record request.
     *
     * @param actor   authenticated patient or staff member
     * @param request open-case command
     * @return the created support case
     * @throws ApiException on denial or invalid input
     */
    public SupportCase openCase(PatientAccessActor actor, OpenCaseRequest request) {
        authorize(actor, EnrollmentActions.SUPPORT_OPEN, RESOURCE_CASE);
        String patientId = text(request.patientId(), "patientId", 128);
        if (request.kind() == null) {
            throw invalid("INVALID_KIND", "a support kind is required");
        }
        String summary = text(request.summary(), "summary", MAX_SUMMARY_LENGTH);
        if (summary.length() < 3) {
            throw invalid("INVALID_SUMMARY", "summary must be at least three characters");
        }
        SupportCase supportCase = new SupportCase(newId(), actor.organizationId(), patientId, request.kind(),
                summary, CaseState.OPEN, null, clock.instant());
        store.putSupportCase(actor.organizationId(), supportCase);
        audit(actor, EventType.CASE_OPENED, supportCase.id(), "success", request.kind().wire());
        notify(actor, "support_case_opened");
        return supportCase;
    }

    /**
     * Assign an open support case to a named queue owner.
     *
     * @param actor    tenant admin, privacy auditor or support agent
     * @param caseId   case identifier
     * @param assignee named queue owner
     * @return the assigned case
     * @throws ApiException on denial, a missing case or an invalid state
     */
    public SupportCase assignCase(PatientAccessActor actor, String caseId, String assignee) {
        authorize(actor, EnrollmentActions.SUPPORT_ASSIGN, RESOURCE_CASE);
        SupportCase found = requireCase(actor, caseId);
        if (found.state() != CaseState.OPEN) {
            throw conflict("INVALID_STATE", "only an open case can be assigned");
        }
        SupportCase assigned = new SupportCase(found.id(), found.organizationId(), found.patientId(),
                found.kind(), found.summary(), CaseState.IN_REVIEW, text(assignee, "assignee", 128), found.openedAt());
        store.putSupportCase(actor.organizationId(), assigned);
        audit(actor, EventType.CASE_ASSIGNED, caseId, "success", "assigned");
        return assigned;
    }

    /**
     * Resolve an in-review support case.
     *
     * @param actor  privacy auditor or support agent
     * @param caseId case identifier
     * @param note   non-sensitive resolution note
     * @return the resolved case
     * @throws ApiException on denial, a missing case or an invalid state
     */
    public SupportCase resolveCase(PatientAccessActor actor, String caseId, String note) {
        authorize(actor, EnrollmentActions.SUPPORT_RESOLVE, RESOURCE_CASE);
        SupportCase found = requireCase(actor, caseId);
        if (found.state() != CaseState.IN_REVIEW) {
            throw conflict("INVALID_STATE", "only an in-review case can be resolved");
        }
        String resolution = text(note, "note", MAX_SUMMARY_LENGTH);
        SupportCase resolved = new SupportCase(found.id(), found.organizationId(), found.patientId(),
                found.kind(), resolution, CaseState.RESOLVED, found.assignee(), found.openedAt());
        store.putSupportCase(actor.organizationId(), resolved);
        audit(actor, EventType.CASE_RESOLVED, caseId, "success", "resolved");
        return resolved;
    }

    /**
     * Read an account view with its support cases and access facts.
     *
     * @param actor     privacy, support or administrative role
     * @param accountId account identifier
     * @return the account view
     * @throws ApiException on denial or a missing account
     */
    public AccountView accountView(PatientAccessActor actor, String accountId) {
        authorize(actor, EnrollmentActions.SUPPORT_READ, RESOURCE_ACCOUNT);
        PatientAccount account = requireAccount(actor, uuid(accountId, "accountId"));
        List<SupportCase> cases = store.supportCases(actor.organizationId()).stream()
                .filter(supportCase -> supportCase.patientId().equals(account.subject()))
                .toList();
        return new AccountView(account, cases, store.audit(actor.organizationId()));
    }

    /**
     * Read the tenant's append-only access audit.
     *
     * @param actor privacy auditor or tenant administrator
     * @return access facts in insertion order
     * @throws ApiException on denial
     */
    public List<AccessEvent> audit(PatientAccessActor actor) {
        authorize(actor, EnrollmentActions.AUDIT_READ, "AUDIT");
        return store.audit(actor.organizationId());
    }

    /**
     * Read the tenant's generic notifications, which never carry clinical detail.
     *
     * @param actor support, privacy or administrative role
     * @return generic notifications in insertion order
     * @throws ApiException on denial
     */
    public List<GenericNotification> notifications(PatientAccessActor actor) {
        authorize(actor, EnrollmentActions.SUPPORT_READ, "NOTIFICATION");
        return store.notifications(actor.organizationId());
    }

    private void authorize(PatientAccessActor actor, String action, String resourceId) {
        if (!actions.allows(action, actor.roles())) {
            audit(actor, EventType.ACCESS_DENIED, resourceId, "denied", action);
            throw new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private PatientAccount requireAccount(PatientAccessActor actor, String accountId) {
        return store.account(actor.organizationId(), accountId).orElseThrow(() -> notFound("account not found"));
    }

    /**
     * Load an account for a patient self-service command.
     *
     * <p>Role authorization and token subject binding are not object
     * authorization: without this guard a patient holding a valid token could
     * pass a peer patient's account identifier in the same tenant. Conceal the
     * account with 404, as for a cross-tenant lookup (PRD-03, IAM-01, ARC-04).
     * Staff commands that legitimately act on another subject's account keep
     * using {@code requireAccount}.</p>
     *
     * @param actor     authenticated patient
     * @param accountId tenant-local account identifier
     * @return the account owned by that patient
     * @throws ApiException when the account is absent or belongs to another subject
     */
    private PatientAccount requireOwnedAccount(PatientAccessActor actor, String accountId) {
        PatientAccount account = requireAccount(actor, accountId);
        if (!actor.subject().equals(account.subject())) {
            audit(actor, EventType.ACCESS_DENIED, accountId, "denied", "not_owner");
            throw notFound("account not found");
        }
        return account;
    }

    private SupportCase requireCase(PatientAccessActor actor, String caseId) {
        return store.supportCase(actor.organizationId(), uuid(caseId, "caseId"))
                .orElseThrow(() -> notFound("support case not found"));
    }

    private void requireUsable(PatientAccount account) {
        if (account.state() == AccountState.LOCKED) {
            throw locked();
        }
    }

    private void requireToken(AccessToken token, PatientAccessActor actor, String purpose) {
        if (token.used() || !purpose.equals(token.purpose()) || !actor.subject().equals(token.subject())
                || !token.expiresAt().isAfter(clock.instant())) {
            throw unauthorized("STALE_REAUTH", "the single-use credential is no longer valid");
        }
    }

    private AccessToken newToken(PatientAccessActor actor, String purpose, long ttl) {
        return newToken(actor.organizationId(), actor.subject(), purpose, ttl);
    }

    private AccessToken newToken(String organizationId, String subject, String purpose, long ttl) {
        return new AccessToken(newId(), organizationId, subject, purpose, clock.instant().plusSeconds(ttl), false);
    }

    private void audit(PatientAccessActor actor, EventType type, String resourceId, String outcome, String detail) {
        store.appendAudit(new AccessEvent(newId(), actor.organizationId(), actor.subject(), type,
                resourceId, outcome, detail, clock.instant()));
    }

    private void notify(PatientAccessActor actor, String eventType) {
        store.appendNotification(new GenericNotification(actor.subject(), actor.organizationId(), eventType,
                clock.instant()));
    }

    private static String uuid(String value, String name) {
        if (value == null || !value.matches("[0-9a-fA-F-]{36}")) {
            throw invalid("INVALID_" + name.toUpperCase(), name + " must be a UUID");
        }
        return value;
    }

    private static String text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid("INVALID_" + name.toUpperCase(), name + " must be 1-" + max + " characters");
        }
        return value.trim();
    }

    private String digest(String value, String name) {
        String normalized = text(value, name, 1024);
        try {
            byte[] bytes = digestFactory.create().digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(Character.forDigit((current >> 4) & 0xf, 16));
                result.append(Character.forDigit(current & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(422, code, message);
    }

    private static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(401, code, message);
    }

    private static ApiException locked() {
        return new ApiException(423, "ACCOUNT_LOCKED", "the account is locked pending review");
    }
}
