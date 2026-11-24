package com.aurevia.clinical.templates;

import java.util.List;

/**
 * One immutable version of a template family in a tenant (CLN-04).
 *
 * @return this record
 */
public record TemplateVersion(TemplateKind kind, int version, boolean approved,
                              List<FieldDefinition> fields, String approvedBy) {

    /**
     * Freeze the field definition set so an approved version cannot change.
     *
     * @throws NullPointerException when fields is null
     */
    public TemplateVersion {
        fields = List.copyOf(fields);
    }

    /**
     * Return the immutable field definitions of this exact version.
     *
     * @return field definitions
     */
    @Override
    public List<FieldDefinition> fields() {
        return List.copyOf(fields);
    }
}
