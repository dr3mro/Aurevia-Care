package com.aurevia.catalog.dictionary;

/**
 * Export payload that preserves a record's language and unit symbols (UX-01, PRD-05).
 *
 * @return this record
 */
public record LocalizedExport(String languageTag, String text, String unit) {

    /**
     * Validate the export metadata.
     *
     * @throws IllegalArgumentException when the language or text is missing
     */
    public LocalizedExport {
        if (languageTag == null || languageTag.isBlank()) {
            throw new IllegalArgumentException("language tag is required");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
    }
}
