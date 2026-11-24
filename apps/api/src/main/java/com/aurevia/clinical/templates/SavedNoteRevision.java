package com.aurevia.clinical.templates;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A saved clinical note together with the field meanings it was saved with (CLN-04).
 *
 * <p>The authored values and the exact {@link com.aurevia.clinical.templates.TemplateVersion}
 * field set are captured at save time, along with a snapshot of the entries of every
 * referenced field dictionary. A later dictionary or template change therefore cannot
 * rewrite what an old note meant when it was saved. Every collection is defensively
 * copied so a caller cannot mutate the historical meaning after the fact.</p>
 *
 * @param organizationId tenant that owns the note
 * @param noteId         tenant-local opaque note identifier
 * @param note           the authored note revision bound to one exact template version
 * @param fieldMeanings  exact field definitions of that version at save time
 * @param dictionaries   dictionary key to permitted entries at save time
 * @return this record
 */
public record SavedNoteRevision(String organizationId, String noteId, TemplateNoteRevision note,
                                List<FieldDefinition> fieldMeanings,
                                Map<String, List<String>> dictionaries) {

    /**
     * Freeze the historical snapshots so no caller can rewrite a saved meaning.
     *
     * @throws NullPointerException when a snapshot collection is null
     */
    public SavedNoteRevision {
        fieldMeanings = List.copyOf(fieldMeanings);
        dictionaries = freeze(dictionaries);
    }

    /**
     * Return the immutable field meanings captured at save time.
     *
     * @return field definitions of the exact saved version
     */
    @Override
    public List<FieldDefinition> fieldMeanings() {
        return List.copyOf(fieldMeanings);
    }

    /**
     * Return the immutable dictionary-entry snapshot captured at save time.
     *
     * @return dictionary key to permitted entries
     */
    @Override
    public Map<String, List<String>> dictionaries() {
        return freeze(dictionaries);
    }

    private static Map<String, List<String>> freeze(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new HashMap<>();
        source.forEach((key, entries) -> copy.put(key, List.copyOf(entries)));
        return Map.copyOf(copy);
    }
}
