package com.aurevia.smartclinic.catalog.dictionary;

import java.util.List;

/**
 * Versioned clinical field dictionaries (CLN-03, CLN-04).
 *
 * <p>A thin domain service over the shared version store. It exposes the
 * dictionary-specific entry type while reusing the common define, approve,
 * effective-fold and history rules. Governance approval is recorded as named
 * data; tenant and role enforcement belong to layers outside this slice.</p>
 */
public final class ClinicalDictionaryService {

    private final VersionStore<DictionaryField> versions = new VersionStore<>(DictionaryField::code);

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
