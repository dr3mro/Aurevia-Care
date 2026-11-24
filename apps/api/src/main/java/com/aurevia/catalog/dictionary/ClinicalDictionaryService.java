package com.aurevia.catalog.dictionary;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.configuration.ConfigurationService;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned clinical field dictionaries (CLN-03, CLN-04).
 *
 * <p>A thin domain service over the version persistence port. It exposes the
 * dictionary-specific entry type while reusing the common define, approve,
 * effective-fold and history rules. Governance approval is recorded as named
 * data; tenant and role enforcement belong to the surrounding HTTP, policy and
 * database layers.</p>
 *
 * <p>The no-argument constructor builds a deterministic in-memory kernel for
 * unit-level domain tests. The injected constructor is the production bean and
 * stores versions durably in tenant configuration entries.</p>
 */
@Service
public final class ClinicalDictionaryService {

    private final VersionPersistence<DictionaryField> versions;

    /**
     * Build the deterministic in-memory kernel used by unit tests.
     */
    public ClinicalDictionaryService() {
        this(new VersionStore<>(DictionaryField::code));
    }

    /**
     * Build the durable production service.
     *
     * @param configurations configuration domain service for durable drafts and approvals
     * @param json           shared JSON mapper
     */
    @Autowired
    public ClinicalDictionaryService(ConfigurationService configurations, ObjectMapper json) {
        this(new DurableVersionStore<>(configurations, json, "cln_dictionary",
                DictionaryField::code, new TypeReference<StoredVersion<DictionaryField>>() { }));
    }

    private ClinicalDictionaryService(VersionPersistence<DictionaryField> versions) {
        this.versions = versions;
    }

    /**
     * Define a new draft dictionary version.
     *
     * @param key    dictionary key
     * @param fields field definitions
     * @param author defining actor
     * @return the allocated version number
     */
    public int define(String key, List<DictionaryField> fields, String author) {
        return versions.define(key, fields, author);
    }

    /**
     * Approve one draft version.
     *
     * @param key      dictionary key
     * @param version  version to approve
     * @param approval named approval
     * @return the approved version
     */
    public CatalogVersion<DictionaryField> approve(String key, int version, Approval approval) {
        return versions.approve(key, version, approval);
    }

    /**
     * Return the effective approved dictionary version.
     *
     * @param key dictionary key
     * @return the effective version, or {@code null}
     */
    public CatalogVersion<DictionaryField> effective(String key) {
        return versions.effective(key);
    }

    /**
     * Return the immutable version history.
     *
     * @param key dictionary key
     * @return all versions, oldest first
     */
    public List<CatalogVersion<DictionaryField>> history(String key) {
        return versions.history(key);
    }
}
