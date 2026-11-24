package com.aurevia.clinical.templates;

/**
 * One authored value for a template field, with its own language for free text.
 *
 * @param fieldKey the field this value belongs to
 * @param value    ISO date, dictionary code or text, as appropriate for the field
 * @param language language tag, required for FREE_TEXT values and optional otherwise
 * @return this record
 */
public record FieldValue(String fieldKey, String value, String language) {
}
