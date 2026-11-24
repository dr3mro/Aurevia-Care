package com.aurevia.catalog;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.CatalogCode;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.configuration.ConfigEntry;
import com.aurevia.configuration.ConfigurationService;
import com.aurevia.tenant.TenantContext;

/**
 * Tenant catalog invariants: bounded families and codes, append-only versions.
 *
 * <p>Drafts are created through the configuration domain service and only become
 * effective after that service appends a named approval. This service adds the
 * catalog key namespace and read-side authorization; it never writes the shared
 * configuration tables directly.</p>
 */
@Service
public class CatalogService {

    private static final Pattern FAMILY = Pattern.compile("[a-z][a-z0-9_]{1,31}");
    private static final int MAX_VALUE = 4000;

    private final CatalogRepository repository;
    private final ConfigurationService configurations;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository     RLS-bounded catalog repository
     * @param configurations configuration domain service for append-only drafts and approvals
     * @param policy         action policy
     * @param transactions   tenant-guarded transaction template
     */
    public CatalogService(CatalogRepository repository, ConfigurationService configurations,
                          ActionPolicy policy, TransactionTemplate transactions) {
        this.repository = repository;
        this.configurations = configurations;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * Define a new catalog entry version as a pending draft.
     *
     * @param family        catalog family
     * @param code          tenant-local code
     * @param value         entry value
     * @param effectiveFrom UTC instant, or {@code null} for immediate effect on approval
     * @return the pending configuration draft
     * @throws ApiException on invalid input or denied access
     */
    public ConfigEntry define(String family, String code, String value, String effectiveFrom) {
        validateName(family, "family");
        validateName(code, "code");
        if (value == null || value.isBlank() || value.length() > MAX_VALUE) {
            throw new ApiException(422, "INVALID_VALUE", "value must be 1-4000 characters");
        }
        // The configuration service repeats authorization and audits CONFIG_CREATE.
        return configurations.create(key(family, code), value, effectiveFrom);
    }

    /**
     * Append a named approval for one catalog entry version.
     *
     * @param family  catalog family
     * @param code    tenant-local code
     * @param version version to approve
     * @param note    approval note
     * @return the approved configuration entry
     * @throws ApiException on invalid input or denied access
     */
    public ConfigEntry approve(String family, String code, int version, String note) {
        validateName(family, "family");
        validateName(code, "code");
        if (version < 1) {
            throw new ApiException(422, "INVALID_VERSION", "version must be positive");
        }
        return configurations.approve(key(family, code), version, note);
    }

    /**
     * Read the single effective approved version of a catalog code.
     *
     * @param family catalog family
     * @param code   tenant-local code
     * @return the effective entry
     * @throws ApiException on invalid input, denial or an absent effective entry
     */
    public CatalogEntry effective(String family, String code) {
        validateName(family, "family");
        validateName(code, "code");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        CatalogEntry entry = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, key(family, code));
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            CatalogEntry found = repository.effective(key(family, code)).orElse(null);
            if (found == null) {
                repository.appendAudit(context, "CATALOG_READ", key(family, code), "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "catalog entry not found or not effective"));
                return null;
            }
            repository.appendAudit(context, "CATALOG_READ", key(family, code), "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return entry;
    }

    /**
     * Read all effective approved entries in a catalog family.
     *
     * @param family catalog family
     * @return visible effective entries
     * @throws ApiException on invalid input or denial
     */
    public List<CatalogEntry> family(String family) {
        validateName(family, "family");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<CatalogEntry> entries = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, family);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<CatalogEntry> found = repository.family("catalog." + family + ".");
            repository.appendAudit(context, "CATALOG_LIST", family, "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return entries;
    }

    private String key(String family, String code) {
        return "catalog." + family + "." + code;
    }

    private void validateName(String value, String name) {
        if ("code".equals(name)) {
            try {
                CatalogCode.code(value);
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(422, "INVALID_" + name.toUpperCase(java.util.Locale.ROOT),
                        CatalogCode.INVALID_MESSAGE);
            }
            return;
        }
        if (value == null || !FAMILY.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + name.toUpperCase(java.util.Locale.ROOT),
                    name + " must be 2-64 letters, digits or _ . : -");
        }
    }

    private ApiException authorize(TenantContext context, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, "CATALOG_READ", resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows("CATALOG_READ", role))) {
            repository.appendAudit(context, "CATALOG_READ", resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing catalog permission");
        }
        return null;
    }
}
