package com.aurevia.clinical.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * CLN-04 acceptance for the four BUILD-33 story families with synthetic data:
 * a full note validates and saves, a partial note is rejected, a historical
 * version keeps the exact field meanings it was saved with, and a required
 * field blocks saving a note for signing. The domain service is exercised
 * through its normal public entry point.
 */
class TemplateNoteAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private static final Map<String, List<String>> SITE_DICTIONARIES = Map.of(
            "obstetric_history", List.of("G0P0", "G1P0", "G2P0"),
            "milestone_domain", List.of("gross_motor", "fine_motor", "language"));

    private static final List<TemplateKind> STORY_FAMILIES = List.of(
            TemplateKind.OPERATIVE, TemplateKind.OBGYN,
            TemplateKind.PERINATAL, TemplateKind.PEDIATRIC_DEVELOPMENT);

    private final TemplateActor admin = TemplateActor.of(ORG, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG, "clin-a", TemplateService.CLINICIAN);

    private InMemoryTemplateStore store;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    /** Define the approved baseline as version one of one family. */
    private void approveBaseline(TemplateKind kind) {
        Set<String> defined = new HashSet<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
            if (field.type() == FieldType.CODE && defined.add(field.dictionaryKey())) {
                List<String> entries = SITE_DICTIONARIES.get(field.dictionaryKey());
                assertNotNull(entries, "no synthetic entries for " + field.dictionaryKey());
                service.defineDictionary(admin, field.dictionaryKey(), entries);
                service.approveDictionary(governor, field.dictionaryKey());
            }
        }
        service.defineVersion(admin, kind, BaselineTemplates.fieldsFor(kind));
        service.approveVersion(governor, kind, 1);
    }

    private static Map<String, String> fullValues(TemplateKind kind) {
        Map<String, String> values = new HashMap<>();
        switch (kind) {
            case OPERATIVE -> {
                values.put("procedure", "APPENDICECTOMY");
                values.put("procedureDate", "2026-10-02");
                values.put("team", "surgery team");
                values.put("findings", "acute appendicitis");
                values.put("complications", "none");
                values.put("followUp", "review in two weeks");
            }
            case OBGYN -> {
                values.put("lmp", "2026-10-01");
                values.put("obstetricHistory", "G1P0");
            }
            case PERINATAL -> {
                values.put("birthHistory", "term vaginal birth");
                values.put("neonatalHistory", "well newborn");
            }
            case PEDIATRIC_DEVELOPMENT -> {
                values.put("milestoneDomain", "gross_motor");
                values.put("observation", "sits unsupported");
                values.put("date", "2026-10-03");
            }
            case SOAP -> throw new IllegalArgumentException("SOAP is not a story family");
        }
        return values;
    }

    private static TemplateNoteRevision note(TemplateKind kind, int version, Map<String, String> values) {
        List<FieldValue> authored = new ArrayList<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
            String value = values.get(field.key());
            if (value != null) {
                authored.add(new FieldValue(field.key(), value,
                        field.type() == FieldType.FREE_TEXT ? "en" : null));
            }
        }
        return new TemplateNoteRevision(kind, version, "PAT-001", "clin-a", authored);
    }

    @Test
    void everyStoryFamilyValidatesAndSavesAFullNote() {
        for (TemplateKind kind : STORY_FAMILIES) {
            approveBaseline(kind);
            TemplateNoteRevision full = note(kind, 1, fullValues(kind));

            ValidationReport report = service.validate(clinician, full);
            assertTrue(report.valid(), kind + " full note should be valid: " + report.violations());
            assertTrue(report.violations().isEmpty());

            SavedNoteRevision saved = service.saveNote(clinician, "NOTE-" + kind.name(), full);
            assertEquals(kind, saved.note().kind());
            assertEquals(BaselineTemplates.fieldsFor(kind), saved.fieldMeanings());
        }
    }

    @Test
    void everyStoryFamilyRejectsAPartialNoteAndBlocksSigning() {
        for (TemplateKind kind : STORY_FAMILIES) {
            approveBaseline(kind);
            List<FieldDefinition> fields = BaselineTemplates.fieldsFor(kind);
            String omitted = fields.get(fields.size() - 1).key();
            Map<String, String> partial = new HashMap<>(fullValues(kind));
            partial.remove(omitted);
            TemplateNoteRevision incomplete = note(kind, 1, partial);

            ValidationReport report = service.validate(clinician, incomplete);
            assertFalse(report.valid(), kind + " partial note must be invalid");
            assertTrue(report.violations().contains("missing:" + omitted),
                    kind + " must name the required field: " + report.violations());

            ApiException denied = assertThrows(ApiException.class,
                    () -> service.saveNote(clinician, "PARTIAL-" + kind.name(), incomplete));
            assertEquals(422, denied.status(), "a required field blocks saving a note for signing");
            assertTrue(store.noteRevision(ORG, "PARTIAL-" + kind.name()).isEmpty());
        }
    }

    @Test
    void aFreeTextFieldWithoutALanguageIsRejectedForEveryStoryFamilyThatHasOne() {
        for (TemplateKind kind : STORY_FAMILIES) {
            approveBaseline(kind);
            FieldDefinition freeText = BaselineTemplates.fieldsFor(kind).stream()
                    .filter(field -> field.type() == FieldType.FREE_TEXT)
                    .findFirst().orElse(null);
            if (freeText == null) {
                continue;
            }
            TemplateNoteRevision note = note(kind, 1, fullValues(kind));
            List<FieldValue> values = new ArrayList<>();
            for (FieldValue value : note.values()) {
                if (freeText.key().equals(value.fieldKey())) {
                    values.add(new FieldValue(value.fieldKey(), value.value(), null));
                } else {
                    values.add(value);
                }
            }
            ValidationReport report = service.validate(clinician,
                    new TemplateNoteRevision(kind, 1, "PAT-001", "clin-a", values));
            assertFalse(report.valid());
            assertTrue(report.violations().contains("language:" + freeText.key()));
        }
    }

    @Test
    void aHistoricalVersionKeepsItsFieldMeaningsAndRequiredness() {
        approveBaseline(TemplateKind.OPERATIVE);
        TemplateNoteRevision full = note(TemplateKind.OPERATIVE, 1, fullValues(TemplateKind.OPERATIVE));
        SavedNoteRevision saved = service.saveNote(clinician, "NOTE-HISTORY", full);
        assertEquals("procedure", field(saved.fieldMeanings(), "procedure").label());

        // Governance relaxes a required field and renames another in version two.
        List<FieldDefinition> changed = new ArrayList<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE)) {
            if ("procedure".equals(field.key())) {
                changed.add(new FieldDefinition(field.key(), field.label(), field.type(), false,
                        field.dictionaryKey(), field.language()));
            } else if ("findings".equals(field.key())) {
                changed.add(new FieldDefinition(field.key(), "operative findings", field.type(),
                        field.required(), field.dictionaryKey(), field.language()));
            } else {
                changed.add(field);
            }
        }
        service.defineVersion(admin, TemplateKind.OPERATIVE, changed);
        service.approveVersion(governor, TemplateKind.OPERATIVE, 2);

        SavedNoteRevision reloaded = service.savedNote(clinician, "NOTE-HISTORY");
        assertEquals("procedure", field(reloaded.fieldMeanings(), "procedure").label());
        assertEquals("findings", field(reloaded.fieldMeanings(), "findings").label());
        assertTrue(field(reloaded.fieldMeanings(), "procedure").required());

        Map<String, String> withoutProcedure = new HashMap<>(fullValues(TemplateKind.OPERATIVE));
        withoutProcedure.remove("procedure");
        assertFalse(service.validate(clinician, note(TemplateKind.OPERATIVE, 1, withoutProcedure)).valid());
        assertTrue(service.validate(clinician, note(TemplateKind.OPERATIVE, 2, withoutProcedure)).valid());
    }

    @Test
    void malformedDatesAndOutOfDictionaryCodesAreReported() {
        approveBaseline(TemplateKind.OBGYN);
        TemplateNoteRevision note = new TemplateNoteRevision(TemplateKind.OBGYN, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("lmp", "01/10/2026", null),
                        new FieldValue("obstetricHistory", "G9P9", null)));

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("format:lmp"), report.violations().toString());
        assertTrue(report.violations().contains("dictionary:obstetricHistory"), report.violations().toString());
    }

    @Test
    void aBlankFreeTextValueIsRejected() {
        approveBaseline(TemplateKind.PERINATAL);
        TemplateNoteRevision note = new TemplateNoteRevision(TemplateKind.PERINATAL, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("birthHistory", "   ", "en"),
                        new FieldValue("neonatalHistory", "well newborn", "en")));

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("blank:birthHistory"), report.violations().toString());
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }
}
