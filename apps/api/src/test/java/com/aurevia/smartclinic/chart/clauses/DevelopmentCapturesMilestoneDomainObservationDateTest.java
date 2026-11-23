package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import com.aurevia.smartclinic.clinical.templates.TemplateVersion;
import com.aurevia.smartclinic.clinical.templates.ValidationReport;

/**
 * CLN-04 clause 5 acceptance: the pediatric development template captures a
 * milestone domain, an observation and a date. The clause is exercised through
 * the public template domain entry points with the same synthetic fixtures and
 * in-memory store used by the neighbouring CLN-04 tests. One positive test
 * proves the three fields are defined, validated and saved; negative tests show
 * a note is refused when a required field is missing, a milestone domain code is
 * outside the approved dictionary, the observation lacks a language tag, the date
 * is malformed, or the author is not a clinician.
 */
class DevelopmentCapturesMilestoneDomainObservationDateTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DICTIONARY = "milestone_domain";
    private static final List<String> CODES = List.of("gross_motor", "fine_motor", "language");

    private final TemplateActor admin = TemplateActor.of(ORG, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG, "clin-a", TemplateService.CLINICIAN);

    private InMemoryTemplateStore store;
    private TemplateService service;

    /** Fresh tenant-scoped store and service for each test. */
    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    /** Define and approve the synthetic milestone dictionary and development baseline. */
    private void approveDevelopmentBaseline() {
        service.defineDictionary(admin, DICTIONARY, CODES);
        service.approveDictionary(governor, DICTIONARY);
        service.defineVersion(admin, TemplateKind.PEDIATRIC_DEVELOPMENT,
                BaselineTemplates.fieldsFor(TemplateKind.PEDIATRIC_DEVELOPMENT));
        service.approveVersion(governor, TemplateKind.PEDIATRIC_DEVELOPMENT, 1);
    }

    /**
     * Build a pediatric development note from the three clause fields. A null
     * value or language leaves the field out, which lets the negative tests
     * exercise the missing and untagged paths.
     */
    private static TemplateNoteRevision developmentNote(String domain, String observation,
                                                        String observationLanguage, String date) {
        List<FieldValue> values = new ArrayList<>();
        if (domain != null) {
            values.add(new FieldValue("milestoneDomain", domain, null));
        }
        if (observation != null) {
            values.add(new FieldValue("observation", observation, observationLanguage));
        }
        if (date != null) {
            values.add(new FieldValue("date", date, null));
        }
        return new TemplateNoteRevision(TemplateKind.PEDIATRIC_DEVELOPMENT, 1, "PAT-001", "clin-a", values);
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }

    /** Assert that a refused command carries the expected HTTP-style status. */
    private static int statusOf(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertNotNull(exception.code());
        return exception.status();
    }

    /** Runnable that may throw for the status assertion helper. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    /** The development baseline defines exactly a milestone domain, observation and date. */
    @Test
    void developmentTemplateDefinesExactlyMilestoneDomainObservationAndDate() {
        approveDevelopmentBaseline();

        TemplateVersion effective = service.effective(clinician, TemplateKind.PEDIATRIC_DEVELOPMENT);

        List<FieldDefinition> fields = effective.fields();
        assertEquals(List.of("milestoneDomain", "observation", "date"),
                fields.stream().map(FieldDefinition::key).toList());
        assertEquals(FieldType.CODE, field(fields, "milestoneDomain").type());
        assertEquals(DICTIONARY, field(fields, "milestoneDomain").dictionaryKey());
        assertEquals(FieldType.FREE_TEXT, field(fields, "observation").type());
        assertEquals("en", field(fields, "observation").language());
        assertEquals(FieldType.DATE, field(fields, "date").type());
    }

    /** A complete development note validates and saves all three clause fields. */
    @Test
    void completeDevelopmentNoteValidatesAndSavesAllThreeFields() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote("gross_motor", "sits unsupported", "en", "2026-10-03");

        ValidationReport report = service.validate(clinician, note);
        assertTrue(report.valid(), report.violations().toString());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, "DEV-NOTE-1", note);
        assertEquals(List.of("milestoneDomain", "observation", "date"),
                saved.fieldMeanings().stream().map(FieldDefinition::key).toList());
        assertEquals("gross_motor", valueOf(saved.note(), "milestoneDomain"));
        assertEquals("sits unsupported", valueOf(saved.note(), "observation"));
        assertEquals("2026-10-03", valueOf(saved.note(), "date"));
        assertEquals(CODES, saved.dictionaries().get(DICTIONARY));

        SavedNoteRevision reloaded = service.savedNote(clinician, "DEV-NOTE-1");
        assertEquals(List.of("milestoneDomain", "observation", "date"),
                reloaded.fieldMeanings().stream().map(FieldDefinition::key).toList());
    }

    /** A note missing the required date is refused and never saved. */
    @Test
    void developmentNoteMissingDateIsRefused() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote("gross_motor", "sits unsupported", "en", null);

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("missing:date"), report.violations().toString());
        assertEquals(422, statusOf(() -> service.saveNote(clinician, "DEV-NOTE-NO-DATE", note)));
        assertTrue(store.noteRevision(ORG, "DEV-NOTE-NO-DATE").isEmpty());
    }

    /** A milestone domain code outside the approved dictionary is refused. */
    @Test
    void developmentNoteWithUnknownMilestoneDomainIsRefused() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote("unapproved_domain", "sits unsupported", "en", "2026-10-03");

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("dictionary:milestoneDomain"), report.violations().toString());
        assertEquals(422, statusOf(() -> service.saveNote(clinician, "DEV-NOTE-BAD-DOMAIN", note)));
    }

    /** An observation without a language tag is refused. */
    @Test
    void developmentObservationWithoutLanguageIsRefused() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote("gross_motor", "sits unsupported", null, "2026-10-03");

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("language:observation"), report.violations().toString());
    }

    /** A non-ISO date is refused as a malformed clinical fact. */
    @Test
    void developmentNoteWithMalformedDateIsRefused() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote("gross_motor", "sits unsupported", "en", "03/10/2026");

        ValidationReport report = service.validate(clinician, note);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("format:date"), report.violations().toString());
    }

    /** Only a clinician may save a development note. */
    @Test
    void developmentNoteCannotBeSavedByNonClinician() {
        approveDevelopmentBaseline();
        TemplateNoteRevision note = developmentNote("gross_motor", "sits unsupported", "en", "2026-10-03");

        assertEquals(403, statusOf(() -> service.saveNote(admin, "DEV-NOTE-WRONG-ROLE", note)));
        assertTrue(store.noteRevision(ORG, "DEV-NOTE-WRONG-ROLE").isEmpty());
    }

    private static String valueOf(TemplateNoteRevision note, String key) {
        return note.values().stream()
                .filter(value -> key.equals(value.fieldKey()))
                .findFirst().orElseThrow().value();
    }
}
