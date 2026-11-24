package com.aurevia.clinical.templates;

import java.util.List;

/**
 * Synthetic baseline field sets for the five CLN-04 template families.
 *
 * <p>These are the starting definitions a tenant defines as version one and
 * clinical governance then approves. Site-specific dictionaries and
 * requiredness are supplied through the approval workflow, not hard-coded
 * clinical thresholds.</p>
 */
public final class BaselineTemplates {

    private BaselineTemplates() {
    }

    /**
     * Return the baseline field definitions for one template family.
     *
     * @param kind template family
     * @return immutable baseline field set
     */
    public static List<FieldDefinition> fieldsFor(TemplateKind kind) {
        return switch (kind) {
            case SOAP -> List.of(
                    new FieldDefinition("chiefConcern", "chief concern", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("history", "history", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("exam", "examination", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("assessment", "assessment", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("plan", "plan", FieldType.FREE_TEXT, true, null, "en"));
            case OPERATIVE -> List.of(
                    new FieldDefinition("procedure", "procedure", FieldType.TEXT, true, null, null),
                    new FieldDefinition("procedureDate", "procedure date", FieldType.DATE, true, null, null),
                    new FieldDefinition("team", "team", FieldType.TEXT, true, null, null),
                    new FieldDefinition("findings", "findings", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("complications", "complications", FieldType.TEXT, true, null, null),
                    new FieldDefinition("followUp", "follow up", FieldType.TEXT, true, null, null));
            case OBGYN -> List.of(
                    new FieldDefinition("lmp", "last menstrual period", FieldType.DATE, true, null, null),
                    new FieldDefinition("obstetricHistory", "obstetric history", FieldType.CODE, true,
                            "obstetric_history", null));
            case PERINATAL -> List.of(
                    new FieldDefinition("birthHistory", "birth history", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("neonatalHistory", "neonatal history", FieldType.FREE_TEXT, true,
                            null, "en"));
            case PEDIATRIC_DEVELOPMENT -> List.of(
                    new FieldDefinition("milestoneDomain", "milestone domain", FieldType.CODE, true,
                            "milestone_domain", null),
                    new FieldDefinition("observation", "observation", FieldType.FREE_TEXT, true, null, "en"),
                    new FieldDefinition("date", "date", FieldType.DATE, true, null, null));
        };
    }
}
