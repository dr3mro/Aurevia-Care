package com.aurevia.clinical.notes;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of one immutable clinical note revision (CLN-05).
 *
 * <p>Drafts and amendment drafts are editable. Signing produces an immutable
 * signed revision; a signed revision may be amended into a separate draft, whose
 * signature becomes an addendum. Entered-in-error is terminal. The legal
 * transitions are also enforced in V15 by an append-only ledger and a successor
 * guard, so a stray write cannot mutate a signed revision or fork a chain.</p>
 */
public enum NoteState {

    /** Editable original draft. */
    DRAFT,
    /** Immutable signed original. */
    SIGNED,
    /** Editable draft that amends a signed revision. */
    AMENDMENT_DRAFT,
    /** Immutable signed addendum to a prior signed revision. */
    ADDENDUM,
    /** Immutable correction that supersedes a signed revision while preserving it. */
    ENTERED_IN_ERROR;

    /**
     * Return the lower-case wire form used by the API and the database.
     *
     * @return wire form such as {@code amendment_draft}
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse the wire form.
     *
     * @param value lower-case state name
     * @return the matching state
     * @throws IllegalArgumentException when the value is unknown
     */
    public static NoteState from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Whether this revision may be edited in place by a new version.
     *
     * @return {@code true} only for draft and amendment draft
     */
    public boolean editable() {
        return this == DRAFT || this == AMENDMENT_DRAFT;
    }

    /**
     * Whether this revision carries a clinical signature.
     *
     * @return {@code true} for signed and addendum
     */
    public boolean signatureBearing() {
        return this == SIGNED || this == ADDENDUM;
    }
}
