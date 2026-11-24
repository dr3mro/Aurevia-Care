package com.aurevia.staff;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.Subject;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

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

    private static final Pattern CURSOR = Pattern.compile(
            "(" + Subject.PATTERN + "):"
            + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int MAX_DISPLAY_NAME = 200;
    /** Canonical bounded G0 role keys shared with the staff invitation flow. */
    static final List<String> ROLES = List.of("tenant_admin", "reception", "privacy_auditor");

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

    /**
     * Read one cursor-paginated page of staff members in the active organization.
     *
     * <p>The single-record read action is reused: a caller who may read one
     * member may list the same tenant's members. Validation happens before any
     * transaction and the read itself is audited inside the guarded transaction.</p>
     *
     * @param cursor opaque {@code <subject>:<id>} of the previous page's last row, or {@code null}
     * @param limit  page size, or {@code null} for the default of 50
     * @param status optional {@code active} or {@code deactivated} filter
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denied access
     */
    public StaffPage list(String cursor, String limit, String status) {
        StaffCursor parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        String parsedStatus = parseStatus(status);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        StaffPage page = transactions.execute(txStatus -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "STAFF_READ", "ALL");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<StaffRepository.StaffRow> rows = repository.query(
                    parsedCursor == null ? null : parsedCursor.subject(),
                    parsedCursor == null ? null : parsedCursor.id(), parsedStatus, parsedLimit + 1);
            boolean hasMore = rows.size() > parsedLimit;
            List<StaffRepository.StaffRow> pageRows = hasMore ? rows.subList(0, parsedLimit) : rows;
            List<StaffMember> members = pageRows.stream().map(StaffRepository.StaffRow::member).toList();
            String next = hasMore ? pageRows.get(pageRows.size() - 1).member().subject() + ":"
                    + pageRows.get(pageRows.size() - 1).id() : null;
            repository.appendAudit(context, "STAFF_READ", "ALL", "SUCCESS");
            return new StaffPage(members, next);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return page;
    }

    private StaffCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw new ApiException(422, "INVALID_CURSOR", "cursor must be <subject>:<id>");
        }
        return new StaffCursor(matcher.group(1), matcher.group(2));
    }

    private int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value < 1 || value > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-200");
        }
    }

    private String parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        if ("active".equals(status)) {
            return "active";
        }
        if ("deactivated".equals(status)) {
            return "revoked";
        }
        throw new ApiException(422, "INVALID_STATUS", "status must be active or deactivated");
    }

    /** Parsed opaque cursor: the subject sort value and the immutable row id. */
    private record StaffCursor(String subject, String id) {
    }

    private void validateSubject(String subject) {
        try {
            Subject.subject(subject);
        } catch (IllegalArgumentException exception) {
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
