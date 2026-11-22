package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * CLN-04 clause 3 acceptance: the OB/GYN template captures the last menstrual
 * period and a structured obstetric history whose codes come from the approved
 * site dictionary. All fixtures are synthetic and tenant scoped. The clause is
 * exercised through the public {@link com.aurevia.smartclinic.clinical.templates.TemplateService}
 * domain entry point, which is where this rule lives today — there is no HTTP
 * controller for template capture, so the service is the normal entry point.
 *
 * <p>The positive test proves a complete note validates, saves and keeps its
 * frozen field meanings. The negative tests prove the rule refuses a code
 * outside the approved dictionary, a missing or malformed LMP, a template whose
 * dictionary was never approved, and a non-clinician author.</p>
 */
class ObGynCapturesLmpStructuredObstetricHistoryTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final List<String> SITE_ENTRIES = List.of("G0P0", "G1P0", "G2P0");

    private final TemplateActor admin = TemplateActor.of(ORG, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG, "clin-a", TemplateService.CLINICIAN);

    private TemplateService service;

    @BeforeEach
    void setUp() {
        service = new TemplateService(new InMemoryTemplateStore());
    }

    /** Define, approve and activate the OB/GYN baseline with its site dictionary. */
    private void approveObgynBaseline() {
        service.defineDictionary(admin, "obstetric_history", SITE_ENTRIES);
        service.approveDictionary(governor, "obstetric_history");
        service.defineVersion(admin, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governor, TemplateKind.OBGYN, 1);
    }

    /** Build a synthetic OB/GYN note bound to baseline version one. */
    private static TemplateNoteRevision obgynNote(String lmp, String history) {
        return new TemplateNoteRevision(TemplateKind.OBGYN, 1, "PAT-SYNTH-1", "clin-a",
                List.of(new FieldValue("lmp", lmp, null),
                        new FieldValue("obstetricHistory", history, null)));
    }

    @Test
    void obgynNoteCapturesLmpAndDictionaryBackedObstetricHistory() {
        approveObgynBaseline();
        List<FieldDefinition> fields = BaselineTemplates.fieldsFor(TemplateKind.OBGYN);
        FieldDefinition lmp = field(fields, "lmp");
        FieldDefinition history = field(fields, "obstetricHistory");
        assertEquals(FieldType.DATE, lmp.type());
        assertTrue(lmp.required());
        assertEquals(FieldType.CODE, history.type());
        assertTrue(history.required());
        assertEquals("obstetric_history", history.dictionaryKey());

        TemplateNoteRevision note = obgynNote("2026-10-01", "G1P0");
        ValidationReport report = service.validate(clinician, note);
        assertTrue(report.valid(), report.violations().toString());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, "OBGYN-CAPTURE", note);
        assertEquals(TemplateKind.OBGYN, saved.note().kind());
        assertEquals(fields, saved.fieldMeanings());
        assertEquals(SITE_ENTRIES, saved.dictionaries().get("obstetric_history"));
        assertEquals("2026-10-01", fieldValue(saved.note(), "lmp"));
        assertEquals("G1P0", fieldValue(saved.note(), "obstetricHistory"));
        assertEquals(saved, service.savedNote(clinician, "OBGYN-CAPTURE"));
    }

    @Test
    void obstetricCodeOutsideTheApprovedSiteDictionaryIsRefused() {
        approveObgynBaseline();
        TemplateNoteRevision note = obgynNote("2026-10-01", "G9P9");

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("dictionary:obstetricHistory"), report.violations().toString());

        assertEquals(422, status(() -> service.saveNote(clinician, "OBGYN-BAD-CODE", note)));
    }

    @Test
    void obgynNoteWithoutLmpIsRefused() {
        approveObgynBaseline();
        TemplateNoteRevision note = new TemplateNoteRevision(TemplateKind.OBGYN, 1, "PAT-SYNTH-1", "clin-a",
                List.of(new FieldValue("obstetricHistory", "G1P0", null)));

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("missing:lmp"), report.violations().toString());
        assertEquals(422, status(() -> service.saveNote(clinician, "OBGYN-NO-LMP", note)));
    }

    @Test
    void obgynNoteWithMalformedLmpIsRefused() {
        approveObgynBaseline();
        TemplateNoteRevision note = obgynNote("01/10/2026", "G1P0");

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("format:lmp"), report.violations().toString());
    }

    @Test
    void obgynTemplateCannotBeApprovedBeforeItsDictionaryIsApproved() {
        service.defineDictionary(admin, "obstetric_history", SITE_ENTRIES);
        service.defineVersion(admin, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));

        assertEquals(422, status(() -> service.approveVersion(governor, TemplateKind.OBGYN, 1)));
    }

    @Test
    void nonClinicianCannotSaveAnObgynNote() {
        approveObgynBaseline();
        TemplateNoteRevision note = obgynNote("2026-10-01", "G1P0");

        assertEquals(403, status(() -> service.saveNote(admin, "OBGYN-WRONG-ROLE", note)));
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }

    private static String fieldValue(TemplateNoteRevision note, String key) {
        return note.values().stream()
                .filter(value -> key.equals(value.fieldKey()))
                .findFirst().orElseThrow().value();
    }

    private static int status(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertNotNull(exception.code());
        return exception.status();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
