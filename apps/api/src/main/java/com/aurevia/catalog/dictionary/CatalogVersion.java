package com.aurevia.catalog.dictionary;

import java.util.List;

/**
 * One immutable version of a clinical dictionary or catalogue (CLN-04, ADM-01).
 *
 * <p>A draft has no approval and is never effective. Approving replaces the
 * version with an approved copy; earlier versions remain in the history so a
 * historical field meaning or mapping is never rewritten.</p>
 *
 */
public record CatalogVersion<T>(int version, List<T> entries, String author, Approval approval) {

    /**
     * Validate the version and defensively copy its entries.
     *
     * @throws IllegalArgumentException when the version is not positive, the author is
     *                                  missing or the entries are null
     */
    public CatalogVersion {
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author is required");
        }
        if (entries == null) {
            throw new IllegalArgumentException("entries are required");
        }
        entries = List.copyOf(entries);
    }

    /**
     * Return an immutable copy of the entries.
     *
     * @return the entries of this version
     */
    @Override
    public List<T> entries() {
        return List.copyOf(entries);
    }

    /**
     * Whether this version has been approved.
     *
     * @return {@code true} when a named approval is attached
     */
    public boolean isApproved() {
        return approval != null;
    }

    /**
     * Attach an approval, returning a new version.
     *
     * <p>A missing approval fails closed: returning a copy with no approval would
     * let a draft look approved without any named governance decision.</p>
     *
     * @param approval named approval
     * @return the approved version; this version is unchanged
     * @throws IllegalArgumentException when the approval is missing
     */
    public CatalogVersion<T> approve(Approval approval) {
        if (approval == null) {
            throw new IllegalArgumentException("approval is required");
        }
        return new CatalogVersion<>(version, entries, author, approval);
    }
}
