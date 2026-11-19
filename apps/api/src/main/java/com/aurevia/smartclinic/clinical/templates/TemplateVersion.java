package com.aurevia.smartclinic.clinical.templates;

import java.util.List;

/**
 * One immutable version of a template family in a tenant (CLN-04).
 *
 * @param kind       template family
 * @param version    monotonically increasing tenant-local version number
 * @param approved   whether clinical governance has approved this version
 * @param fields     complete field definition set, preserved with this version
 * @param approvedBy governance approver subject, or {@code null} for a draft
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
