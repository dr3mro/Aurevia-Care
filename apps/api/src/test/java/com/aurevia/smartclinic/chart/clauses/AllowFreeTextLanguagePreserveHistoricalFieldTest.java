package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.clinical.templates.BaselineTemplates;
import com.aurevia.smartclinic.clinical.templates.FieldDefinition;
import com.aurevia.smartclinic.clinical.templates.FieldDictionary;
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
 * CLN-04 clause 7 proof at the domain entry point the specification's template
 * slice actually provides: a free-text value carries an explicit language, and
 * a saved note revision keeps the exact field meanings of the template version
 * it was written against even after governance publishes a changed version.
 *
 * <p>Synthetic fixtures only. No production code is added by this suite. The
 * clause is implemented by {@code clinical.templates.TemplateService} and
 * persisted by the V80 template-store migration; no HTTP controller currently
 * publishes template notes, so the missing reachability is recorded in
 * {@code docs/spec/clauses/chart/allow-free-text-language-preserve-historical-field.md}
 * rather than hidden behind a disabled test.</p>
 */
class AllowFreeTextLanguagePreserveHistoricalFieldTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";

    private final TemplateActor admin = TemplateActor.of(ORG, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG, "clin-a", TemplateService.CLINICIAN);
    private final TemplateActor reception = TemplateActor.of(ORG, "reception-a", "reception");
    private final TemplateActor otherTenantClinician =
            TemplateActor.of(OTHER_ORG, "clin-b", TemplateService.CLINICIAN);

    private InMemoryTemplateStore store;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    /** Define and approve the OPERATIVE baseline as version one in the caller's tenant. */
    private void approveOperativeBaseline() {
        service.defineVersion(admin, TemplateKind.OPERATIVE,
                BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE));
        service.approveVersion(governor, TemplateKind.OPERATIVE, 1);
    }

    /** Define an approved code dictionary and the OBGYN baseline that references it. */
    private void approveObgynBaseline() {
        service.defineDictionary(admin, "obstetric_history", List.of("G0P0"));
        service.approveDictionary(governor, "obstetric_history");
        service.defineVersion(admin, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governor, TemplateKind.OBGYN, 1);
    }

    /**
     * Build a full OPERATIVE note, stamping every free-text value with the given
     * language. The non-free-text fields are deliberately given no language.
     */
    private static TemplateNoteRevision operativeNote(int version, String language) {
        Map<String, String> values = Map.of(
                "procedure", "APPENDICECTOMY",
                "procedureDate", "2026-10-02",
                "team", "surgery team",
                "findings", "acute appendicitis",
                "complications", "none",
                "followUp", "review in two weeks");
        List<FieldValue> authored = new ArrayList<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE)) {
            authored.add(new FieldValue(field.key(), values.get(field.key()),
                    field.type() == FieldType.FREE_TEXT ? language : null));
        }
        return new TemplateNoteRevision(TemplateKind.OPERATIVE, version, "PAT-001", "clin-a", authored);
    }

    /** Build an OBGYN note with one coded value so the dictionary snapshot is non-empty. */
    private static TemplateNoteRevision obgynNote(int version, String code) {
        return new TemplateNoteRevision(TemplateKind.OBGYN, version, "PAT-001", "clin-a",
                List.of(new FieldValue("lmp", "2026-10-01", null),
                        new FieldValue("obstetricHistory", code, null)));
    }

    @Test
    void freeTextWithLanguageIsAcceptedAndSavedWithFrozenFieldMeanings() {
        approveOperativeBaseline();
        List<FieldDefinition> original = BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE);
        TemplateNoteRevision arabicNote = operativeNote(1, "ar");

        ValidationReport report = service.validate(clinician, arabicNote);
        assertTrue(report.valid(), "free text with an explicit language must validate: " + report.violations());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, "NOTE-AR", arabicNote);
        assertEquals(original, saved.fieldMeanings());
        assertEquals("ar", languageOf(saved.note(), "findings"));
        assertEquals("acute appendicitis", valueOf(saved.note(), "findings"));

        // Governance renames the free-text field and relaxes a required field in
        // version two. The already-saved note must still mean what version one meant.
        List<FieldDefinition> changed = new ArrayList<>();
        for (FieldDefinition field : original) {
            if ("findings".equals(field.key())) {
                changed.add(new FieldDefinition(field.key(), "operative findings (v2)", field.type(),
                        field.required(), field.dictionaryKey(), field.language()));
            } else if ("procedure".equals(field.key())) {
                changed.add(new FieldDefinition(field.key(), field.label(), field.type(),
                        false, field.dictionaryKey(), field.language()));
            } else {
                changed.add(field);
            }
        }
        service.defineVersion(admin, TemplateKind.OPERATIVE, changed);
        service.approveVersion(governor, TemplateKind.OPERATIVE, 2);
        assertFalse(original.equals(changed), "the changed version must differ for this proof to mean anything");

        SavedNoteRevision reloaded = service.savedNote(clinician, "NOTE-AR");
        assertEquals(original, reloaded.fieldMeanings(), "the saved note must keep v1 meanings, not adopt v2");
        assertEquals("findings", field(reloaded.fieldMeanings(), "findings").label());
        assertTrue(field(reloaded.fieldMeanings(), "procedure").required());
        assertEquals("ar", languageOf(reloaded.note(), "findings"));
        assertThrows(UnsupportedOperationException.class, () -> reloaded.fieldMeanings().clear());
        assertThrows(UnsupportedOperationException.class, () -> reloaded.dictionaries().clear());
    }

    @Test
    void dictionarySnapshotSurvivesAfterTheSourceDictionaryChanges() {
        approveObgynBaseline();
        SavedNoteRevision saved = service.saveNote(clinician, "NOTE-OBGYN", obgynNote(1, "G0P0"));
        assertEquals(List.of("G0P0"), saved.dictionaries().get("obstetric_history"));

        // The site dictionary is replaced with a different permitted code set.
        store.putDictionary(ORG,
                new FieldDictionary("obstetric_history", List.of("NEW_CODE"), true, "gov-a"));
        assertEquals(List.of("NEW_CODE"), service.dictionary(admin, "obstetric_history").entries());

        SavedNoteRevision reloaded = service.savedNote(clinician, "NOTE-OBGYN");
        assertEquals(List.of("G0P0"), reloaded.dictionaries().get("obstetric_history"),
                "the saved dictionary snapshot must not follow the live dictionary");
        assertEquals("last menstrual period", field(reloaded.fieldMeanings(), "lmp").label());
        assertThrows(UnsupportedOperationException.class, () -> reloaded.fieldMeanings().clear());
        assertThrows(UnsupportedOperationException.class, () -> reloaded.dictionaries().clear());

        // The constructor copies caller-owned collections too: clearing the
        // sources afterwards cannot rewrite a frozen historical meaning.
        List<FieldDefinition> sourceFields = new ArrayList<>(
                BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE));
        List<String> sourceEntries = new ArrayList<>(List.of("CPT"));
        Map<String, List<String>> sourceDictionaries = new HashMap<>();
        sourceDictionaries.put("procedure_code", sourceEntries);
        SavedNoteRevision directlyBuilt = new SavedNoteRevision(
                ORG, "NOTE-DIRECT", operativeNote(1, "ar"), sourceFields, sourceDictionaries);
        sourceFields.clear();
        sourceDictionaries.clear();
        sourceEntries.clear();
        assertEquals(BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE), directlyBuilt.fieldMeanings());
        assertEquals(Map.of("procedure_code", List.of("CPT")), directlyBuilt.dictionaries());
    }

    @Test
    void freeTextWithoutLanguageIsRejectedAndNotSaved() {
        approveOperativeBaseline();
        for (String missing : new String[] {null, "   "}) {
            TemplateNoteRevision note = operativeNote(1, missing);
            ValidationReport report = service.validate(clinician, note);
            assertFalse(report.valid(), "a free-text value with no language must be invalid");
            assertTrue(report.violations().contains("language:findings"), report.violations().toString());

            ApiException denied = assertThrows(ApiException.class,
                    () -> service.saveNote(clinician, "NOTE-NO-LANG", note));
            assertEquals(422, denied.status(), "an invalid note must not be saved");
            assertTrue(store.noteRevision(ORG, "NOTE-NO-LANG").isEmpty());
        }
    }

    @Test
    void nonClinicianCannotSaveAndSavedNoteIsConcealedAcrossTenants() {
        approveOperativeBaseline();
        TemplateNoteRevision note = operativeNote(1, "ar");

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(reception, "NOTE-DENIED", note));
        assertEquals(403, denied.status(), "only a clinician may save a note revision");
        assertTrue(store.noteRevision(ORG, "NOTE-DENIED").isEmpty());

        service.saveNote(clinician, "NOTE-SCOPED", note);
        assertEquals(ORG, service.savedNote(clinician, "NOTE-SCOPED").organizationId());
        ApiException concealed = assertThrows(ApiException.class,
                () -> service.savedNote(otherTenantClinician, "NOTE-SCOPED"));
        assertEquals(404, concealed.status(), "a saved note must be concealed from another tenant");
    }

    @Test
    void staleTemplateVersionIsNotFoundOnSave() {
        approveOperativeBaseline();
        ApiException stale = assertThrows(ApiException.class,
                () -> service.saveNote(clinician, "NOTE-STALE", operativeNote(99, "ar")));
        assertEquals(404, stale.status(), "a stale template version must not accept a new note");
        assertTrue(store.noteRevision(ORG, "NOTE-STALE").isEmpty());
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }

    private static String languageOf(TemplateNoteRevision note, String key) {
        return note.values().stream().filter(value -> key.equals(value.fieldKey()))
                .findFirst().orElseThrow().language();
    }

    private static String valueOf(TemplateNoteRevision note, String key) {
        return note.values().stream().filter(value -> key.equals(value.fieldKey()))
                .findFirst().orElseThrow().value();
    }
}
