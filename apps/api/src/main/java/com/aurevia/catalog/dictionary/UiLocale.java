package com.aurevia.catalog.dictionary;

import java.util.Locale;

/**
 * The two V1 interface locales and their derived document direction (PRD-05, UX-01).
 *
 * <p>This is an interface locale, not a content language: clinical free text keeps
 * its own language tag and is never re-spelled to match the interface.</p>
 */
public enum UiLocale {

    /** English interface. */
    EN("en", Direction.LTR),
    /** Arabic interface. */
    AR("ar", Direction.RTL);

    private final String tag;
    private final Direction direction;

    /**
     * @param tag
     * @param direction
     */
    UiLocale(String tag, Direction direction) {
        this.tag = tag;
        this.direction = direction;
    }

    /**
     * Return the BCP-47 base tag of the interface locale.
     *
     * @return {@code en} or {@code ar}
     */
    public String tag() {
        return tag;
    }

    /**
     * Return the document direction for this locale.
     *
     * @return the derived direction
     */
    public Direction direction() {
        return direction;
    }

    /**
     * Resolve a language tag to a supported interface locale.
     *
     * <p>Only the base subtag is significant; regional variants such as
     * {@code ar-EG} resolve to the same interface locale.</p>
     *
     * @param tag language tag, for example {@code en} or {@code ar-EG}
     * @return the matching interface locale
     * @throws IllegalArgumentException when the tag is missing or unsupported
     */
    public static UiLocale fromTag(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("language tag is required");
        }
        String base = tag.toLowerCase(Locale.ROOT).split("-", 2)[0];
        return switch (base) {
            case "en" -> EN;
            case "ar" -> AR;
            default -> throw new IllegalArgumentException("unsupported language tag: " + tag);
        };
    }
}
