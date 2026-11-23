package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * CLN-04 clause 2 domain evidence: an operative note carries procedure, procedure
 * date, team, findings, complications and follow-up, all required by the approved
 * version it references.
 *
 * <p>The tests prove the domain half of the rule with synthetic data through the
 * public {@link com.aurevia.smartclinic.clinical.templates.TemplateService} entry
 * point: {@link com.aurevia.smartclinic.clinical.templates.BaselineTemplates}
 * defines the six fields as required and the service validates and saves a note
 * with frozen field meanings. The HTTP half is a documented gap — no controller,
 * route or contract entry exists for {@code TemplateKind.OPERATIVE} — and is
 * tracked in the spec-clause note and the follow-up tasks, not claimed here.</p>
 */
@Tag("integration")
class OperativeNotesIncludeProcedureDateTeamFindingsTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String NOTE_ID = "operative-note-c02";

    /** The same synthetic operative values used by the template acceptance suite. */
    private static Map<String, String> fullValues() {
        Map<String, String> values = new HashMap<>();
        values.put("procedure", "APPENDICECTOMY");
        values.put("procedureDate", "2026-10-02");
        values.put("team", "surgery team");
        values.put("findings", "acute appendicitis");
        values.put("complications", "none");
        values.put("followUp", "review in two weeks");
        return values;
    }

    /** Build a note revision from the supplied values over the OPERATIVE baseline. */
    private static TemplateNoteRevision note(Map<String, String> values) {
        List<FieldValue> authored = new ArrayList<>();
        for (FieldDefinition definition : BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE)) {
            String value = values.get(definition.key());
            if (value != null) {
                authored.add(new FieldValue(definition.key(), value,
                        definition.type() == FieldType.FREE_TEXT ? "en" : null));
            }
        }
        return new TemplateNoteRevision(TemplateKind.OPERATIVE, 1, PATIENT, "clinician-a", authored);
    }

    /** Approve the synthetic OPERATIVE baseline as tenant version one. */
    private static void approveOperativeBaseline(TemplateService service) {
        TemplateActor admin = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
        TemplateActor governor = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
        service.defineVersion(admin, TemplateKind.OPERATIVE,
                BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE));
        service.approveVersion(governor, TemplateKind.OPERATIVE, 1);
    }

    @Test
    void validatesAndSavesAnOperativeNoteWithAllSixRequiredFieldsInTheDomain() {
        TemplateService service = new TemplateService(new InMemoryTemplateStore());
        approveOperativeBaseline(service);
        TemplateActor clinician = TemplateActor.of(ORG_A, "clinician-a", TemplateService.CLINICIAN);
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
    void refusesAnOperativeNoteMissingARequiredFieldInTheDomain() {
        TemplateService service = new TemplateService(new InMemoryTemplateStore());
        approveOperativeBaseline(service);
        TemplateActor clinician = TemplateActor.of(ORG_A, "clinician-a", TemplateService.CLINICIAN);
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
}
