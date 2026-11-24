package com.aurevia.catalog.dictionary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * In-memory version store shared by the dictionary and catalogue domain services.
 *
 * <p>This is a deterministic domain kernel: it owns version allocation, the
 * draft-to-approved fold and immutable history. It deliberately does not own
 * tenancy, authorization, audit or persistence; those belong to the controller,
 * policy and database layers that are outside this story's scope.</p>
 *
 * @param <T> entry value type
 */
final class VersionStore<T> implements VersionPersistence<T> {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{1,31}");

    private final Map<String, List<CatalogVersion<T>>> versions = new HashMap<>();
    private final Function<T, String> identity;

    /**
     * Create a store with a rule for extracting an entry's unique code.
     *
     * @param identity extracts the tenant-local code of an entry
     */
    VersionStore(Function<T, String> identity) {
        this.identity = identity;
    }

    /**
     * Append a new draft version.
     *
     * @param key     catalogue or dictionary key
     * @param entries version entries
     * @param author  defining actor
     * @return the allocated version number
     * @throws IllegalArgumentException on invalid input, a null entry or duplicate entry codes
     */
    @Override
    public int define(String key, List<T> entries, String author) {
        requireKey(key);
        requireEntries(entries, author, identity);
        List<CatalogVersion<T>> list = versions.computeIfAbsent(key, k -> new ArrayList<>());
        int version = list.size() + 1;
        list.add(new CatalogVersion<>(version, entries, author, null));
        return version;
    }

    /**
     * Approve one draft version with a named reviewer distinct from its author.
     *
     * @param key      catalogue or dictionary key
     * @param version  version to approve
     * @param approval named approval
     * @return the approved version
     * @throws IllegalArgumentException on invalid input or a reused author
     * @throws IllegalStateException    when the version is already approved
     */
    @Override
    public CatalogVersion<T> approve(String key, int version, Approval approval) {
        requireKey(key);
        requireApproval(approval);
        List<CatalogVersion<T>> list = versions.get(key);
        if (version < 1 || list == null || version > list.size()) {
            throw new IllegalArgumentException("unknown version");
        }
        CatalogVersion<T> current = list.get(version - 1);
        if (current.isApproved()) {
            throw new IllegalStateException("version already approved");
        }
        if (current.author().equals(approval.approver())) {
            throw new IllegalArgumentException("approver must differ from author");
        }
        CatalogVersion<T> approved = current.approve(approval);
        list.set(version - 1, approved);
        return approved;
    }

    /**
     * Return the most recent approved version, or {@code null} when none is effective.
     *
     * @param key catalogue or dictionary key
     * @return the effective version, or {@code null}
     */
    @Override
    public CatalogVersion<T> effective(String key) {
        requireKey(key);
        List<CatalogVersion<T>> list = versions.get(key);
        if (list == null) {
            return null;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).isApproved()) {
                return list.get(i);
            }
        }
        return null;
    }

    /**
     * Return an immutable, ordered history of all versions.
     *
     * @param key catalogue or dictionary key
     * @return all versions, oldest first
     */
    @Override
    public List<CatalogVersion<T>> history(String key) {
        requireKey(key);
        List<CatalogVersion<T>> list = versions.get(key);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Validate a bounded catalogue key shared by both persistence implementations.
     *
     * @param key key supplied by a caller
     */
    static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("invalid catalogue key");
        }
    }

    /**
     * Validate a draft's actor, entries and unique codes before a version is allocated.
     *
     * @param entries version entries
     * @param author  defining actor
     * @param identity extracts the unique code of an entry
     * @param <E>      entry value type
     */
    static <E> void requireEntries(List<E> entries, String author, Function<E, String> identity) {
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author is required");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries are required");
        }
        Set<String> seen = new HashSet<>();
        for (E entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("entry must not be null");
            }
            if (!seen.add(identity.apply(entry))) {
                throw new IllegalArgumentException("duplicate entry code");
            }
        }
    }

    /**
     * Require a named approval before a version can become effective.
     *
     * @param approval approval supplied by a caller
     */
    static void requireApproval(Approval approval) {
        if (approval == null) {
            throw new IllegalArgumentException("approval is required");
        }
    }
}
