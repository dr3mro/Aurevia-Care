package com.aurevia.catalog.dictionary;

import java.util.List;

/**
 * Version storage port for the clinical dictionary and diagnosis catalogue kernels.
 *
 * <p>The domain services depend on this small interface instead of a concrete map
 * so the same define, approve, effective and history rules can run against a
 * deterministic in-memory implementation in unit tests and a durable, tenant-bound
 * implementation in production. Implementations must keep drafts invisible until a
 * named approval is recorded and must preserve an immutable version history.</p>
 *
 * @param <T> entry value type
 */
interface VersionPersistence<T> {

    /**
     * Append a new draft version.
     *
     * @param key    dictionary or catalogue key
     * @param entries version entries
     * @param author defining actor
     * @return the allocated version number
     */
    int define(String key, List<T> entries, String author);

    /**
     * Record a named approval for one draft version.
     *
     * @param key      dictionary or catalogue key
     * @param version  version to approve
     * @param approval named approval
     * @return the approved version
     */
    CatalogVersion<T> approve(String key, int version, Approval approval);

    /**
     * Read the most recent approved version.
     *
     * @param key dictionary or catalogue key
     * @return the effective version, or {@code null} when none is effective
     */
    CatalogVersion<T> effective(String key);

    /**
     * Read the immutable version history, oldest first.
     *
     * @param key dictionary or catalogue key
     * @return all versions
     */
    List<CatalogVersion<T>> history(String key);
}
