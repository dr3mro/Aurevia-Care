package com.aurevia.configuration;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Tenant configuration invariants: draft, named approval and immutable history.
 *
 * <p>Authorization is re-derived per command. A draft is never effective until a
 * separate approval is appended, and an approved future-dated version is served
 * only once its effective instant passes. Both facts are folded on read by V5.</p>
 */
@Service
public class ConfigurationService {

    // Keys begin with a lowercase namespace (for example "catalog.") and may
    // contain uppercase tenant-local codes such as a diagnosis code R51.
    private static final Pattern KEY = Pattern.compile("[a-z][A-Za-z0-9._:-]{2,63}");
    private static final int MAX_VALUE = 4000;

    private final ConfigurationRepository repository;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded configuration repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     */
    public ConfigurationService(ConfigurationRepository repository, ActionPolicy policy,
                                TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Append a draft configuration version with a database-allocated version number.
     *
     * @param key           tenant-local configuration key
     * @param value         opaque configuration value
     * @param effectiveFrom UTC instant, or {@code null} to take effect on approval
     * @return the pending draft projection
     * @throws ApiException on invalid input, denied access or a duplicate version
     */
    public ConfigEntry create(String key, String value, String effectiveFrom) {
        validateKey(key);
        if (value == null || value.isBlank() || value.length() > MAX_VALUE) {
            throw new ApiException(422, "INVALID_VALUE", "value must be 1-4000 characters");
        }
        String effective = normalizeEffectiveFrom(effectiveFrom);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ConfigEntry entry = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "CONFIG_CREATE", key);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            try {
                int version = repository.nextVersion(key);
                repository.insertEntry(context.organizationId(), key, version, value, effective);
                repository.appendAudit(context, "CONFIG_CREATE", key, "SUCCESS");
                return new ConfigEntry(key, version, value, "pending", effective, null, null, null, null);
            } catch (DuplicateKeyException exception) {
                repository.appendAudit(context, "CONFIG_CREATE", key, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "configuration version already exists"));
                return null;
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return entry;
    }

    /**
     * Append a named approval for one draft version.
     *
     * @param key     tenant-local configuration key
     * @param version version to approve
     * @param note    optional approval note
     * @return the approved projection with the approver derived from the actor
     * @throws ApiException on invalid input, denial, concealment or a duplicate approval
     */
    public ConfigEntry approve(String key, int version, String note) {
        validateKey(key);
        if (version < 1) {
            throw new ApiException(422, "INVALID_VERSION", "version must be positive");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ConfigEntry entry = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "CONFIG_APPROVE", key);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ConfigEntry found = repository.findEntry(key, version).orElse(null);
            if (found == null) {
                repository.appendAudit(context, "CONFIG_APPROVE", key, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "configuration version not found"));
                return null;
            }
            if ("approved".equals(found.status())) {
                repository.appendAudit(context, "CONFIG_APPROVE", key, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "configuration version already approved"));
                return null;
            }
            String approver = repository.findUserIdBySubject(context.subject()).orElse(null);
            if (approver == null) {
                repository.appendAudit(context, "CONFIG_APPROVE", key, "DENIED");
                failure.set(new ApiException(403, "FORBIDDEN", "approver has no user identity"));
                return null;
            }
            String entryId = repository.findEntryId(key, version).orElse(null);
            if (entryId == null) {
                repository.appendAudit(context, "CONFIG_APPROVE", key, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "configuration version not found"));
                return null;
            }
            try {
                repository.insertApproval(context.organizationId(), entryId, note);
            } catch (DuplicateKeyException exception) {
                repository.appendAudit(context, "CONFIG_APPROVE", key, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "configuration version already approved"));
                return null;
            }
            repository.appendAudit(context, "CONFIG_APPROVE", key, "SUCCESS");
            return new ConfigEntry(found.key(), found.version(), found.value(), "approved",
                    found.effectiveFrom(), found.createdBy(), approver, Instant.now().toString(), note);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return entry;
    }

    /**
     * Read the single effective approved version of a key.
     *
     * @param key tenant-local configuration key
     * @return the effective entry
     * @throws ApiException on invalid input, denial or an absent effective version
     */
    public ConfigEntry get(String key) {
        validateKey(key);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ConfigEntry entry = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "CONFIG_READ", key);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ConfigEntry effective = repository.effective(key).orElse(null);
            if (effective == null) {
                repository.appendAudit(context, "CONFIG_READ", key, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "no effective configuration for key"));
                return null;
            }
            repository.appendAudit(context, "CONFIG_READ", key, "SUCCESS");
            return effective;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return entry;
    }

    /**
     * Read the append-only version and approval history of a key.
     *
     * @param key tenant-local configuration key
     * @return the visible history
     * @throws ApiException on invalid input, denial or an unknown key
     */
    public ConfigHistory history(String key) {
        validateKey(key);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ConfigHistory history = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "CONFIG_HISTORY", key);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            java.util.List<ConfigEntry> entries = repository.findAll(key);
            if (entries.isEmpty()) {
                repository.appendAudit(context, "CONFIG_HISTORY", key, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "configuration key not found"));
                return null;
            }
            ConfigHistory result = new ConfigHistory(key, entries);
            repository.appendAudit(context, "CONFIG_HISTORY", key, "SUCCESS");
            return result;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return history;
    }

    private void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ApiException(422, "INVALID_KEY", "key must be 3-64 lowercase letters, digits, . _ or -");
        }
    }

    private String normalizeEffectiveFrom(String effectiveFrom) {
        if (effectiveFrom == null) {
            return Instant.now().toString();
        }
        try {
            return Instant.parse(effectiveFrom).toString();
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_EFFECTIVE_FROM", "effectiveFrom must be a UTC instant");
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
