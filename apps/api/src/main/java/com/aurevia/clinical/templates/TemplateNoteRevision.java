package com.aurevia.clinical.templates;

import java.util.List;

/**
 * A synthetic note revision bound to one exact template version (CLN-04).
 *
 * @return this record
 */
public record TemplateNoteRevision(TemplateKind kind, int templateVersion, String patientRef, String author,
                           List<FieldValue> values) {

    /**
     * Freeze the authored values so a note cannot be mutated after validation.
     *
     * @throws NullPointerException when values is null
     */
    public TemplateNoteRevision {
        values = List.copyOf(values);
    }

    /**
     * Return the immutable authored values.
     *
     * @return field values in authoring order
     */
    @Override
    public List<FieldValue> values() {
        return List.copyOf(values);
    }
}
