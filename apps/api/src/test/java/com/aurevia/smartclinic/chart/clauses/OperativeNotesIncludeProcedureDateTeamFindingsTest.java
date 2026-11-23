package com.aurevia.smartclinic.chart.clauses;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
 * CLN-04 clause 2 evidence: a versioned operative-note template carries procedure,
 * procedure date, team, findings, complications and follow-up, all required by the
 * approved version it references, and the template service accepts a note that
 * documents all six while refusing a note that omits, blanks or is not authored by
 * an entitled role for any of them.
 *
 * <p>The tests prove the domain half of the rule with synthetic data through the
 * public {@link com.aurevia.smartclinic.clinical.templates.TemplateService} entry
 * point: {@link com.aurevia.smartclinic.clinical.templates.BaselineTemplates}
 * defines the six fields as required and the service validates and saves a note
 * with frozen field meanings. The HTTP half is a documented gap — no controller,
 * route or contract entry exists for {@code TemplateKind.OPERATIVE} — and is
 * tracked in the spec-clause note and the follow-up tasks, not claimed here.</p>
 *
 * <p>All fixtures are synthetic; no production code is added by this suite.</p>
 */
@Tag("integration")
class OperativeNotesIncludeProcedureDateTeamFindingsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "PAT-OPERATIVE-CLAUSE-TWO";
    private static final String AUTHOR = "clause2-clinician";
    private static final String NOTE_ID = "operative-note-c02";
    private static final List<String> REQUIRED_FIELDS = List.of(
            "procedure", "procedureDate", "team", "findings", "complications", "followUp");

    private final TemplateActor admin = TemplateActor.of(ORG, "clause2-admin", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG, "clause2-governor",
            TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG, AUTHOR, TemplateService.CLINICIAN);
    private final TemplateActor reception = TemplateActor.of(ORG, "clause2-reception", "reception");

    private InMemoryTemplateStore store;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    /** Seed the governed operative baseline as the tenant's version one. */
    private void approveOperativeBaseline() {
        service.defineVersion(admin, TemplateKind.OPERATIVE,
                BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE));
        service.approveVersion(governor, TemplateKind.OPERATIVE, 1);
    }

    /** Synthetic operative values covering every governed field. */
    private static Map<String, String> completeValues() {
        Map<String, String> values = new HashMap<>();
        values.put("procedure", "APPENDICECTOMY");
        values.put("procedureDate", "2026-10-02");
        values.put("team", "surgery team");
        values.put("findings", "acute appendicitis");
        values.put("complications", "none");
        values.put("followUp", "review in two weeks");
        return values;
    }

    /** The same synthetic operative values, named as in the domain-half evidence. */
    private static Map<String, String> fullValues() {
        return completeValues();
    }

    /** Build an operative note from the given values, tagging free text with a language. */
    private static TemplateNoteRevision note(Map<String, String> values) {
        List<FieldValue> authored = new ArrayList<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE)) {
            String value = values.get(field.key());
            if (value != null) {
                authored.add(new FieldValue(field.key(), value,
                        field.type() == FieldType.FREE_TEXT ? "en" : null));
            }
        }
        return new TemplateNoteRevision(TemplateKind.OPERATIVE, 1, PATIENT, AUTHOR, authored);
    }

    @Test
    void operativeTemplateDeclaresAllSixFieldsAsGovernanceRequired() {
        List<FieldDefinition> fields = BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE);

        assertEquals(new HashSet<>(REQUIRED_FIELDS),
                new HashSet<>(fields.stream().map(FieldDefinition::key).toList()));
        assertTrue(fields.stream().allMatch(FieldDefinition::required),
                "each operative field must be required so a note cannot omit it");
        assertEquals(FieldType.DATE,
                fields.stream().filter(f -> "procedureDate".equals(f.key())).findFirst().orElseThrow().type());
        FieldDefinition findings = fields.stream()
                .filter(f -> "findings".equals(f.key())).findFirst().orElseThrow();
        assertEquals(FieldType.FREE_TEXT, findings.type());
        assertEquals("en", findings.language());
    }

    @Test
    void validatesAndSavesAnOperativeNoteWithAllSixRequiredFieldsInTheDomain() {
        approveOperativeBaseline();
        TemplateNoteRevision full = note(fullValues());

        ValidationReport report = service.validate(clinician, full);
        assertTrue(report.valid(), report.violations().toString());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, NOTE_ID, full);
        assertEquals(TemplateKind.OPERATIVE, saved.note().kind());
        Map<String, String> byKey = new HashMap<>();
        for (FieldValue value : saved.note().values()) {
            byKey.put(value.fieldKey(), value.value());
        }
        assertEquals(fullValues(), byKey);
        assertEquals(BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE), saved.fieldMeanings());
        assertEquals(6, saved.fieldMeanings().size());
        assertTrue(BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE).stream()
                .allMatch(FieldDefinition::required), "all six operative fields must be required");

        // Read the note back through the domain so the saved field meanings are
        // proven to survive a reload, not just the in-memory save call.
        assertEquals(saved, service.savedNote(clinician, NOTE_ID));
    }

    @Test
    void operativeNoteWithAllSixFieldsValidatesAndSaves() {
        approveOperativeBaseline();

        ValidationReport report = service.validate(clinician, note(completeValues()));
        assertTrue(report.valid(), report.violations().toString());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, "NOTE-OPERATIVE-COMPLETE", note(completeValues()));
        assertEquals(TemplateKind.OPERATIVE, saved.note().kind());
        assertEquals(new HashSet<>(REQUIRED_FIELDS),
                new HashSet<>(saved.fieldMeanings().stream().map(FieldDefinition::key).toList()));
        assertNotNull(service.savedNote(clinician, "NOTE-OPERATIVE-COMPLETE"));
    }

    @Test
    void operativeNoteOmittingAnyRequiredFieldIsRefused() {
        approveOperativeBaseline();

        for (String omitted : REQUIRED_FIELDS) {
            Map<String, String> partial = completeValues();
            partial.remove(omitted);

            ValidationReport report = service.validate(clinician, note(partial));
            assertFalse(report.valid(), omitted + " is required by clause 2");
            assertTrue(report.violations().contains("missing:" + omitted),
                    omitted + " must be named as missing: " + report.violations());

            ApiException denied = assertThrows(ApiException.class,
                    () -> service.saveNote(clinician, "PARTIAL-" + omitted, note(partial)));
            assertEquals(422, denied.status());
            assertTrue(store.noteRevision(ORG, "PARTIAL-" + omitted).isEmpty());
        }
    }

    @Test
    void refusesAnOperativeNoteMissingARequiredFieldInTheDomain() {
        approveOperativeBaseline();
        Map<String, String> partial = fullValues();
        partial.remove("complications");

        ValidationReport report = service.validate(clinician, note(partial));
        assertFalse(report.valid());
        assertTrue(report.violations().contains("missing:complications"), report.violations().toString());
        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(clinician, "operative-partial-c02", note(partial)));
        assertEquals(422, denied.status());
        ApiException absent = assertThrows(ApiException.class,
                () -> service.savedNote(clinician, "operative-partial-c02"));
        assertEquals(404, absent.status(), "a refused note must not be persisted");
    }

    @Test
    void operativeNoteWithABlankRequiredFieldIsRefused() {
        approveOperativeBaseline();
        Map<String, String> blankComplications = completeValues();
        blankComplications.put("complications", "   ");

        ValidationReport report = service.validate(clinician, note(blankComplications));
        assertFalse(report.valid());
        assertTrue(report.violations().contains("blank:complications"), report.violations().toString());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(clinician, "NOTE-BLANK-COMPLICATIONS", note(blankComplications)));
        assertEquals(422, denied.status());
        assertEquals("INVALID_NOTE", denied.code());
    }

    @Test
    void aRoleThatCannotAuthorClinicalNotesIsRefusedAnOperativeNote() {
        approveOperativeBaseline();
        TemplateNoteRevision complete = note(completeValues());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(reception, "NOTE-WRONG-ROLE", complete));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertTrue(denied.getMessage().contains("TEMPLATE_NOTE_SAVE"));
    }
}
