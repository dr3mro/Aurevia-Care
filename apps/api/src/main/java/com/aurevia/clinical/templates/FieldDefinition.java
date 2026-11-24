package com.aurevia.clinical.templates;

/**
 * Immutable definition of one field in a saved template version (CLN-04).
 *
 * <p>Storing the label, type, requiredness, dictionary reference and language
 * inside each version preserves the historical meaning of a field even after a
 * later version renames or redefines it.</p>
 *
 * @param key           stable field identifier within the version
 * @param label         localized label shown to the clinical author
 * @param type          value kind that selects the validation rule
 * @param required      whether a note must carry a value for this field
 * @param dictionaryKey approved dictionary key, when {@code type} is CODE
 * @param language      language tag for the canonical free text, or {@code null}
 * @return this record
 */
public record FieldDefinition(String key, String label, FieldType type, boolean required,
                              String dictionaryKey, String language) {
}
