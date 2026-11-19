package com.aurevia.smartclinic.staff;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.authz.ActionPolicy;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Staff lifecycle invariants: membership, permission and audit in one transaction.
 *
 * <p>Authorization is re-derived from the authoritative store on every command,
 * so a deactivation or expired grant denies the next protected request without
 * waiting for a token or session to expire. Denials are audited and committed by
 * returning the failure from the transaction rather than throwing inside it.</p>
 */
@Service
public class StaffService {

    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
    private static final int MAX_DISPLAY_NAME = 200;
    private static final List<String> ROLES = List.of("tenant_admin", "reception", "privacy_auditor");

    private final StaffRepository repository;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded staff repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public StaffService(StaffRepository repository, ActionPolicy policy, TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Invite a staff member with one bounded role grant.
     *
     * @param subject     subject to invite
     * @param displayName display name
     * @param role        bounded role key
     * @return the newly created member
     * @throws ApiException on invalid input, denied access or a duplicate subject
     */
    public StaffMember invite(String subject, String displayName, String role) {
        validateSubject(subject);
        if (displayName == null || displayName.isBlank() || displayName.length() > MAX_DISPLAY_NAME) {
            throw new ApiException(422, "INVALID_DISPLAY_NAME", "display name must be 1-200 characters");
        }
        if (role == null || !ROLES.contains(role)) {
            throw new ApiException(422, "INVALID_ROLE", "role must be a bounded G0 role key");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffMember member = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_INVITE", subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findUserIdBySubject(subject).isPresent()) {
                repository.appendAudit(context, "STAFF_INVITE", subject, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "staff subject already exists"));
                return null;
            }
            String actorId = repository.findUserIdBySubject(context.subject()).orElse(null);
            if (actorId == null) {
                repository.appendAudit(context, "STAFF_INVITE", subject, "DENIED");
                failure.set(new ApiException(403, "FORBIDDEN", "actor has no user identity"));
                return null;
            }
            String userId;
            try {
                userId = repository.insertIdentity(context.organizationId(), subject, displayName);
            } catch (DuplicateKeyException exception) {
                repository.appendAudit(context, "STAFF_INVITE", subject, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "staff subject already exists"));
                return null;
            }
            String membershipId = repository.insertMembership(context.organizationId(), userId, "active");
            repository.insertRoleGrant(context.organizationId(), membershipId, role, actorId);
            repository.appendAudit(context, "STAFF_INVITE", subject, "SUCCESS");
            return repository.findBySubject(subject).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return member;
    }

    /**
     * Deactivate a staff member by appending a revoked membership event.
     *
     * @param subject subject to deactivate
     * @return the deactivated member projection
     * @throws ApiException on invalid input, denied access or an unknown or already deactivated member
     */
    public StaffMember deactivate(String subject) {
        validateSubject(subject);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffMember member = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_DEACTIVATE", subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Optional<String> current = repository.currentStatus(subject);
            if (current.isEmpty()) {
                repository.appendAudit(context, "STAFF_DEACTIVATE", subject, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "staff not found"));
                return null;
            }
            if (!"active".equals(current.get())) {
                repository.appendAudit(context, "STAFF_DEACTIVATE", subject, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "staff already deactivated"));
                return null;
            }
            String userId = repository.findUserIdBySubject(subject).orElse(null);
            if (userId == null) {
                repository.appendAudit(context, "STAFF_DEACTIVATE", subject, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "staff not found"));
                return null;
            }
            repository.insertMembership(context.organizationId(), userId, "revoked");
            repository.appendAudit(context, "STAFF_DEACTIVATE", subject, "SUCCESS");
            return repository.findBySubject(subject).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return member;
    }

    /**
     * Read the effective projection of a staff member.
     *
     * @param subject subject to read
     * @return the visible member
     * @throws ApiException on invalid input, denied access or an unknown member
     */
    public StaffMember get(String subject) {
        validateSubject(subject);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffMember member = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_READ", subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            StaffMember found = repository.findBySubject(subject).orElse(null);
            if (found == null) {
                repository.appendAudit(context, "STAFF_READ", subject, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "staff not found"));
                return null;
            }
            repository.appendAudit(context, "STAFF_READ", subject, "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return member;
    }

    /**
     * Read the immutable membership and role history of a staff member.
     *
     * @param subject subject whose history is requested
     * @return the visible history
     * @throws ApiException on invalid input, denied access or an unknown member
     */
    public StaffHistory history(String subject) {
        validateSubject(subject);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffHistory history = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_HISTORY", subject);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findBySubject(subject).isEmpty()) {
                repository.appendAudit(context, "STAFF_HISTORY", subject, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "staff not found"));
                return null;
            }
            StaffHistory result = new StaffHistory(subject, repository.findMembershipEvents(subject),
                    repository.findRoleGrants(subject));
            repository.appendAudit(context, "STAFF_HISTORY", subject, "SUCCESS");
            return result;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return history;
    }

    private void validateSubject(String subject) {
        if (subject == null || !SUBJECT.matcher(subject).matches()) {
            throw new ApiException(422, "INVALID_SUBJECT", "subject must be 2-64 lowercase letters, digits, . _ or -");
        }
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            repository.appendAudit(context, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing action permission");
        }
        return null;
    }
}
