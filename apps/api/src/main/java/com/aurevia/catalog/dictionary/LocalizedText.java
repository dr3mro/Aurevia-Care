package com.aurevia.catalog.dictionary;

/**
 * Clinical free text stored as written with its own language tag (CLN-04, UX-01).
 *
 * <p>The exact code points are preserved: no transliteration, case folding,
 * diacritic stripping or reformatting is performed. The content language is
 * independent of the interface locale.</p>
 *
 * @return this record
 */
public record LocalizedText(String text, String languageTag) {

    /**
     * Validate the value object.
     *
     * @throws IllegalArgumentException when the text or language tag is missing
     */
    public LocalizedText {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (languageTag == null || languageTag.isBlank()) {
            throw new IllegalArgumentException("language tag is required");
        }
    }
}
