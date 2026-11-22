package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

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
 * CLN-04 clause 4 (API evidence): perinatal captures birth and neonatal history.
 *
 * <p>The clause is realised by the perinatal template family: clinical governance
 * adopts version one of an approved field set that requires a birth history and a
 * neonatal history, and a clinician saves both together against that exact version.
 * The enabled tests below prove that behaviour through the normal public entry point
 * of the template domain, using the same synthetic fixtures and service construction
 * as {@code TemplateNoteAcceptanceTest}.</p>
 *
 * <p>The clause is only <em>partly</em> exposed as an API: there is no HTTP controller
 * or OpenAPI operation over {@link com.aurevia.smartclinic.clinical.templates.TemplateService},
 * so an end-to-end HTTP proof cannot be enabled yet. The disabled test at the bottom
 * records that missing surface for a later task; no production code is added here.</p>
 */
class PerinatalCapturesBirthNeonatalHistoryTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

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

    /**
     * Adopt the synthetic perinatal baseline as version one and have clinical
     * governance approve it. Perinatal has no CODE fields, so no dictionary is needed.
     */
    private void approvePerinatalBaseline() {
        service.defineVersion(admin, TemplateKind.PERINATAL, BaselineTemplates.fieldsFor(TemplateKind.PERINATAL));
        service.approveVersion(governor, TemplateKind.PERINATAL, 1);
    }

    /** Build a perinatal note carrying the two required histories with their language tags. */
    private static TemplateNoteRevision note(String birth, String neonatal) {
        return new TemplateNoteRevision(TemplateKind.PERINATAL, 1, PATIENT, "clin-a", List.of(
                new FieldValue("birthHistory", birth, "en"),
                new FieldValue("neonatalHistory", neonatal, "en")));
    }

    @Test
    void approvedPerinatalTemplateRequiresBirthAndNeonatalHistory() {
        approvePerinatalBaseline();

        TemplateVersion effective = service.effective(clinician, TemplateKind.PERINATAL);
        assertTrue(effective.approved(), "the perinatal baseline must be approved before it can be used");
        assertEquals(1, effective.version());

        List<FieldDefinition> fields = effective.fields();
        assertEquals(2, fields.size(), "perinatal captures exactly birth and neonatal history");
        FieldDefinition birth = field(fields, "birthHistory");
        FieldDefinition neonatal = field(fields, "neonatalHistory");
        assertEquals("birth history", birth.label());
        assertEquals(FieldType.FREE_TEXT, birth.type());
        assertTrue(birth.required());
        assertEquals("en", birth.language());
        assertEquals("neonatal history", neonatal.label());
        assertEquals(FieldType.FREE_TEXT, neonatal.type());
        assertTrue(neonatal.required());
        assertEquals("en", neonatal.language());
    }

    @Test
    void clinicianSavesBothHistoriesAndReloadsTheirFieldMeanings() {
        approvePerinatalBaseline();
        TemplateNoteRevision authored = note("term vaginal birth", "well newborn, Apgar 9 and 9");

        ValidationReport report = service.validate(clinician, authored);
        assertTrue(report.valid(), "a note with both histories must validate: " + report.violations());
        assertTrue(report.violations().isEmpty());

        SavedNoteRevision saved = service.saveNote(clinician, "PERINATAL-NOTE-1", authored);
        assertEquals(ORG, saved.organizationId());
        assertEquals("PERINATAL-NOTE-1", saved.noteId());
        assertEquals(TemplateKind.PERINATAL, saved.note().kind());
        assertEquals(1, saved.note().templateVersion());
        assertEquals("term vaginal birth", valueOf(saved.note(), "birthHistory"));
        assertEquals("well newborn, Apgar 9 and 9", valueOf(saved.note(), "neonatalHistory"));
        assertEquals("birth history", field(saved.fieldMeanings(), "birthHistory").label());
        assertEquals("neonatal history", field(saved.fieldMeanings(), "neonatalHistory").label());
        assertEquals("en", field(saved.fieldMeanings(), "neonatalHistory").language());

        SavedNoteRevision reloaded = service.savedNote(clinician, "PERINATAL-NOTE-1");
        assertEquals(saved, reloaded, "the saved histories and their meanings must survive a reload");
        assertEquals("well newborn, Apgar 9 and 9", valueOf(reloaded.note(), "neonatalHistory"));
    }

    @Test
    void perinatalNoteMissingNeonatalHistoryIsRefused() {
        approvePerinatalBaseline();
        TemplateNoteRevision partial = new TemplateNoteRevision(TemplateKind.PERINATAL, 1, PATIENT, "clin-a",
                List.of(new FieldValue("birthHistory", "term vaginal birth", "en")));

        ValidationReport report = service.validate(clinician, partial);
        assertFalse(report.valid(), "a note with only the birth history must be refused");
        assertTrue(report.violations().contains("missing:neonatalHistory"),
                "the missing neonatal history must be named: " + report.violations());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(clinician, "PERINATAL-PARTIAL", partial));
        assertEquals(422, denied.status());
        assertTrue(store.noteRevision(ORG, "PERINATAL-PARTIAL").isEmpty(),
                "a refused note must never be saved");
    }

    @Test
    void perinatalNoteMissingBirthHistoryIsRefused() {
        approvePerinatalBaseline();
        TemplateNoteRevision partial = new TemplateNoteRevision(TemplateKind.PERINATAL, 1, PATIENT, "clin-a",
                List.of(new FieldValue("neonatalHistory", "well newborn", "en")));

        ValidationReport report = service.validate(clinician, partial);
        assertFalse(report.valid(), "a note with only the neonatal history must be refused");
        assertTrue(report.violations().contains("missing:birthHistory"),
                "the missing birth history must be named: " + report.violations());

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(clinician, "PERINATAL-NO-BIRTH", partial));
        assertEquals(422, denied.status());
        assertTrue(store.noteRevision(ORG, "PERINATAL-NO-BIRTH").isEmpty(),
                "a refused note must never be saved");
    }

    @Test
    void perinatalNoteWithBlankNeonatalHistoryIsRefused() {
        approvePerinatalBaseline();
        TemplateNoteRevision blank = note("term vaginal birth", "   ");

        ValidationReport report = service.validate(clinician, blank);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("blank:neonatalHistory"), report.violations().toString());
        assertThrows(ApiException.class, () -> service.saveNote(clinician, "PERINATAL-BLANK", blank));
        assertTrue(store.noteRevision(ORG, "PERINATAL-BLANK").isEmpty());
    }

    @Test
    void nonClinicianCannotSavePerinatalHistory() {
        approvePerinatalBaseline();
        TemplateNoteRevision authored = note("term vaginal birth", "well newborn");

        ApiException denied = assertThrows(ApiException.class,
                () -> service.saveNote(admin, "PERINATAL-ADMIN", authored));
        assertEquals(403, denied.status(), "only a clinician may author a perinatal note");
        assertTrue(store.noteRevision(ORG, "PERINATAL-ADMIN").isEmpty());
    }

    /**
     * End-to-end API proof that cannot be enabled today. The rule has a domain
     * service and a durable PostgreSQL store, but no HTTP controller and no OpenAPI
     * operation expose it, so a client cannot capture a perinatal note through the
     * normal API entry point. A later task adds a thin controller over the service
     * and the contract entry, wires the server port, then removes {@code @Disabled}.
     */
    @Test
    @Disabled("GAP: CLN-04 perinatal capture has no HTTP controller or OpenAPI entry; "
            + "the rule is reachable only through the TemplateService domain entry point. "
            + "No production code is added by this test.")
    void perinatalHistoryCanBeCapturedThroughTheHttpApi() {
        RestClient rest = RestClient.builder().baseUrl("http://localhost:8080").build();
        String body = "{\"kind\":\"PERINATAL\",\"templateVersion\":1,\"patientId\":\"" + PATIENT + "\","
                + "\"values\":["
                + "{\"fieldKey\":\"birthHistory\",\"value\":\"term vaginal birth\",\"language\":\"en\"},"
                + "{\"fieldKey\":\"neonatalHistory\",\"value\":\"well newborn\",\"language\":\"en\"}]}";

        var created = rest.post().uri("/api/v1/clinical/templates/notes")
                .header("X-Synthetic-Subject", "clin-a")
                .header("X-Active-Organization", ORG)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);

        assertEquals(201, created.getStatusCode().value(), created.getBody());
        assertNotNull(created.getBody());
        assertTrue(created.getBody().contains("birthHistory"), created.getBody());
        assertTrue(created.getBody().contains("neonatalHistory"), created.getBody());
    }

    /** Return one frozen field definition by its stable key. */
    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }

    /** Return the authored value of one field in a note. */
    private static String valueOf(TemplateNoteRevision noteRevision, String key) {
        return noteRevision.values().stream()
                .filter(value -> key.equals(value.fieldKey()))
                .findFirst().orElseThrow().value();
    }
}
