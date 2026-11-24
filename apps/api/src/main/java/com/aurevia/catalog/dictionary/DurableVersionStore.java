package com.aurevia.catalog.dictionary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.aurevia.api.ApiException;
import com.aurevia.configuration.ConfigEntry;
import com.aurevia.configuration.ConfigHistory;
import com.aurevia.configuration.ConfigurationService;
import com.aurevia.tenant.TenantContext;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable version persistence for the clinical dictionary and diagnosis catalogue.
 *
 * <p>Versions are stored as opaque tenant configuration entries. The existing
 * configuration domain already owns append-only drafts, named approvals, the
 * effective fold, authorization and forced row-level security, so this adapter
 * adds no schema and duplicates no SQL. Tenancy comes only from the request
 * context; the approver identity is resolved from the database through the
 * repository rather than trusted from a client payload.</p>
 *
 * @param <T> entry value type
 */
public final class DurableVersionStore<T> implements VersionPersistence<T> {

    private final ConfigurationService configurations;
    private final ObjectMapper json;
    private final String namespace;
    private final Function<T, String> identity;
    private final TypeReference<StoredVersion<T>> storedType;

    /**
     * Create the adapter.
     *
     * @param configurations configuration domain service for durable drafts and approvals
     * @param json           shared JSON mapper
     * @param namespace      key namespace that separates dictionaries from catalogues
     * @param identity       extracts the unique code of an entry
     * @param storedType     concrete stored-snapshot type for deserialization
     */
    public DurableVersionStore(ConfigurationService configurations, ObjectMapper json, String namespace,
                                     Function<T, String> identity, TypeReference<StoredVersion<T>> storedType) {
        this.configurations = configurations;
        this.json = json;
        this.namespace = namespace;
        this.identity = identity;
        this.storedType = storedType;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validation is shared with the deterministic kernel so a draft cannot be
     * written with a null entry or duplicate codes.</p>
     */
    @Override
    public int define(String key, List<T> entries, String author) {
        VersionStore.requireKey(key);
        VersionStore.requireEntries(entries, author, identity);
        ConfigEntry created = configurations.create(configKey(key),
                encode(new StoredVersion<>(author, entries)), null);
        return created.version();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The author check compares the subject stored with the draft against the
     * authenticated subject of the approver. Both are actor subjects, so a caller
     * cannot approve its own draft by supplying a different approval payload; the
     * named approver recorded by the configuration domain is derived from the
     * actor inside its own tenant-guarded transaction.</p>
     */
    @Override
    public CatalogVersion<T> approve(String key, int version, Approval approval) {
        VersionStore.requireKey(key);
        VersionStore.requireApproval(approval);
        String configKey = configKey(key);
        ConfigEntry draft = configurations.history(configKey).entries().stream()
                .filter(entry -> entry.version() == version)
                .findFirst()
                .orElse(null);
        if (draft == null) {
            throw new IllegalArgumentException("unknown version");
        }
        String author = decode(draft.value()).author();
        if (author.equals(TenantContext.current().subject())) {
            throw new IllegalArgumentException("approver must differ from author");
        }
        return toVersion(configurations.approve(configKey, version, approval.note()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A missing effective entry folds to {@code null}; an authorization or
     * transport failure is never disguised as a missing version.</p>
     */
    @Override
    public CatalogVersion<T> effective(String key) {
        VersionStore.requireKey(key);
        ConfigEntry entry;
        try {
            entry = configurations.get(configKey(key));
        } catch (ApiException absent) {
            if (absent.status() == 404) {
                return null;
            }
            throw absent;
        }
        return toVersion(entry);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An absent key folds to an empty history; other failures propagate.</p>
     */
    @Override
    public List<CatalogVersion<T>> history(String key) {
        VersionStore.requireKey(key);
        ConfigHistory history;
        try {
            history = configurations.history(configKey(key));
        } catch (ApiException absent) {
            if (absent.status() == 404) {
                return List.of();
            }
            throw absent;
        }
        List<CatalogVersion<T>> versions = new ArrayList<>();
        for (ConfigEntry entry : history.entries()) {
            versions.add(toVersion(entry));
        }
        return List.copyOf(versions);
    }

    private String configKey(String key) {
        return namespace + "." + key;
    }

    private CatalogVersion<T> toVersion(ConfigEntry entry) {
        StoredVersion<T> stored = decode(entry.value());
        Approval approval = "approved".equals(entry.status())
                ? new Approval(entry.approvedBy(), entry.approvedAt(), entry.note())
                : null;
        return new CatalogVersion<>(entry.version(), stored.entries(), stored.author(), approval);
    }

    private String encode(StoredVersion<T> stored) {
        try {
            return json.writeValueAsString(stored);
        } catch (JacksonException failure) {
            throw new ApiException(422, "INVALID_CATALOG_DATA", "cannot encode version entries");
        }
    }

    private StoredVersion<T> decode(String value) {
        try {
            return json.readValue(value, storedType);
        } catch (JacksonException failure) {
            throw new ApiException(422, "INVALID_CATALOG_DATA", "corrupt version entries");
        }
    }
}
