package com.aurevia.smartclinic.g1.s_g1_04;

import java.util.List;

/**
 * A synthetic note revision bound to one exact template version (CLN-04).
 *
 * @param kind            template family of the note
 * @param templateVersion exact version whose field definitions govern this note
 * @param patientRef      synthetic patient reference
 * @param author          clinical author subject
 * @param values          authored field values
 */
public record NoteRevision(TemplateKind kind, int templateVersion, String patientRef, String author,
                           List<FieldValue> values) {

    /**
     * Freeze the authored values so a note cannot be mutated after validation.
     *
     * @throws NullPointerException when values is null
     */
    public NoteRevision {
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
