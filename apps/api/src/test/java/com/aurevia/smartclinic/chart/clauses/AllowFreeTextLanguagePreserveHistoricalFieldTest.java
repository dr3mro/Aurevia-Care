package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.clinical.templates.BaselineTemplates;
import com.aurevia.smartclinic.clinical.templates.FieldDefinition;
import com.aurevia.smartclinic.clinical.templates.FieldType;
import com.aurevia.smartclinic.clinical.templates.FieldValue;
import com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore;
import com.aurevia.smartclinic.clinical.templates.SavedNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateActor;
import com.aurevia.smartclinic.clinical.templates.TemplateKind;
import com.aurevia.smartclinic.clinical.templates.TemplateNoteRevision;
import com.aurevia.smartclinic.clinical.templates.TemplateService;
import com.aurevia.smartclinic.clinical.templates.ValidationReport;

/**
 * CLN-04 clause 7 proof: an authored free-text field carrying its own language
 * tag validates and saves, and the saved revision keeps the exact field
 * meanings of the template version it was bound to even after a later version
 * renames or redefines those fields. Two negative cases show the rule refusing
 * free text without a language and concealing a saved note from another tenant.
 *
 * <p>The domain is exercised through its public
 * {@link com.aurevia.smartclinic.clinical.templates.TemplateService} entry point
 * over the synthetic in-memory store, the same fixture as the neighbouring
 * CLN-04 acceptance tests. All identifiers and text are synthetic.</p>
 */
class AllowFreeTextLanguagePreserveHistoricalFieldTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";

    private final TemplateActor admin = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG_A, "clin-a", TemplateService.CLINICIAN);

    private TemplateService service;
    private InMemoryTemplateStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    /** Approve the synthetic SOAP baseline as version one; every SOAP field is free text. */
    private void approveSoapBaseline() {
        service.defineVersion(admin, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP));
        service.approveVersion(governor, TemplateKind.SOAP, 1);
    }

    /**
     * Build a complete SOAP note whose chief concern is free text in the language
     * under test and whose remaining free-text fields are in English.
     */
    private static TemplateNoteRevision soapNote(int version, String chiefConcernLanguage) {
        return new TemplateNoteRevision(TemplateKind.SOAP, version, "PAT-001", "clin-a", List.of(
                new FieldValue("chiefConcern", "ألم في البطن", chiefConcernLanguage),
                new FieldValue("history", "no prior abdominal surgery", "en"),
                new FieldValue("exam", "abdomen soft", "en"),
                new FieldValue("assessment", "suspected appendicitis", "en"),
                new FieldValue("plan", "admit for observation", "en")));
    }

    @Test
    void freeTextWithLanguageValidatesAndTheSavedRevisionKeepsItsVersionFieldMeanings() {
        approveSoapBaseline();
        TemplateNoteRevision note = soapNote(1, "ar");

        ValidationReport report = service.validate(clinician, note);
        assertTrue(report.valid(), report.violations().toString());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, "NOTE-CLAUSE-7", note);
        FieldValue arabic = value(saved, "chiefConcern");
        assertEquals("ألم في البطن", arabic.value());
        assertEquals("ar", arabic.language());
        assertEquals("chief concern", field(saved.fieldMeanings(), "chiefConcern").label());
        assertEquals("en", field(saved.fieldMeanings(), "chiefConcern").language());
        assertEquals(FieldType.FREE_TEXT, field(saved.fieldMeanings(), "chiefConcern").type());

        // Governance changes the field's label and canonical language in a later
        // version. A note saved against version one must not be reinterpreted.
        List<FieldDefinition> changed = new ArrayList<>();
        for (FieldDefinition definition : BaselineTemplates.fieldsFor(TemplateKind.SOAP)) {
            if ("chiefConcern".equals(definition.key())) {
                changed.add(new FieldDefinition(definition.key(), "presenting complaint", definition.type(),
                        definition.required(), definition.dictionaryKey(), "ar"));
            } else {
                changed.add(definition);
            }
        }
        service.defineVersion(admin, TemplateKind.SOAP, changed);
        service.approveVersion(governor, TemplateKind.SOAP, 2);

        SavedNoteRevision reloaded = service.savedNote(clinician, "NOTE-CLAUSE-7");
        assertEquals(1, reloaded.note().templateVersion());
        assertEquals("chief concern", field(reloaded.fieldMeanings(), "chiefConcern").label());
        assertEquals("en", field(reloaded.fieldMeanings(), "chiefConcern").language());
        assertEquals("ar", value(reloaded, "chiefConcern").language());
        // The captured snapshot is immutable: a caller cannot rewrite history.
        assertThrows(UnsupportedOperationException.class,
                () -> reloaded.fieldMeanings().set(0, changed.get(0)));
        assertEquals("chief concern", field(service.savedNote(clinician, "NOTE-CLAUSE-7").fieldMeanings(),
                "chiefConcern").label());
    }

    @Test
    void freeTextWithoutALanguageIsRejectedAndCannotBeSaved() {
        approveSoapBaseline();
        TemplateNoteRevision noteWithoutLanguage = soapNote(1, null);

        ValidationReport report = service.validate(clinician, noteWithoutLanguage);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("language:chiefConcern"), report.violations().toString());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(clinician, "NOTE-NO-LANGUAGE", noteWithoutLanguage));
        assertEquals(422, denied.status());
        assertTrue(store.noteRevision(ORG_A, "NOTE-NO-LANGUAGE").isEmpty());
    }

    @Test
    void aSavedHistoricalNoteIsConcealedFromAnotherTenant() {
        approveSoapBaseline();
        service.saveNote(clinician, "NOTE-CLAUSE-7", soapNote(1, "ar"));

        TemplateActor otherTenantClinician = TemplateActor.of(ORG_B, "clin-b", TemplateService.CLINICIAN);
        ApiException concealed = assertThrows(ApiException.class,
                () -> service.savedNote(otherTenantClinician, "NOTE-CLAUSE-7"));
        assertEquals(404, concealed.status());
        assertEquals("NOT_FOUND", concealed.code());
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }

    private static FieldValue value(SavedNoteRevision saved, String key) {
        return saved.note().values().stream()
                .filter(candidate -> key.equals(candidate.fieldKey())).findFirst().orElseThrow();
    }
}
