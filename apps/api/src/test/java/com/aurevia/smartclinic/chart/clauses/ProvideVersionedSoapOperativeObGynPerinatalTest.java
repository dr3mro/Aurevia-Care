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
import java.util.Set;

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
 * CLN-04 clause 1 evidence: versioned SOAP, operative, OB/GYN, perinatal and
 * pediatric development templates with governance-approved field dictionaries
 * and historical field meanings.
 *
 * <p>These tests exercise the normal public entry point of the
 * {@code clinical.templates} domain with an {@link com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore}
 * and synthetic dictionaries only. They prove what the domain does today; they
 * do not claim an HTTP route, because no template controller or contract route
 * exists yet (see {@code docs/spec/clauses/chart/provide-versioned-soap-operative-ob-gyn-perinatal.md}).</p>
 */
class ProvideVersionedSoapOperativeObGynPerinatalTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";

    /** Synthetic approved site dictionaries; no real clinical code set is used. */
    private static final Map<String, List<String>> SITE_DICTIONARIES = Map.of(
            "obstetric_history", List.of("G0P0", "G1P0", "G2P0"),
            "milestone_domain", List.of("gross_motor", "fine_motor", "language"));

    /** The field names the clause names for each of the five template families. */
    private static final Map<TemplateKind, List<String>> CLAUSE_FIELDS = Map.of(
            TemplateKind.SOAP, List.of("chiefConcern", "history", "exam", "assessment", "plan"),
            TemplateKind.OPERATIVE,
            List.of("procedure", "procedureDate", "team", "findings", "complications", "followUp"),
            TemplateKind.OBGYN, List.of("lmp", "obstetricHistory"),
            TemplateKind.PERINATAL, List.of("birthHistory", "neonatalHistory"),
            TemplateKind.PEDIATRIC_DEVELOPMENT, List.of("milestoneDomain", "observation", "date"));

    private final TemplateActor adminA = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governorA = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinicianA = TemplateActor.of(ORG_A, "clin-a", TemplateService.CLINICIAN);
    private final TemplateActor clinicianB = TemplateActor.of(ORG_B, "clin-b", TemplateService.CLINICIAN);

    private InMemoryTemplateStore store;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    /** Define the approved site dictionaries and approve baseline version one of one family. */
    private void approveBaseline(String organizationId, TemplateActor admin, TemplateActor governor,
                                 TemplateKind kind) {
        Set<String> defined = new HashSet<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
            if (field.type() == FieldType.CODE && defined.add(field.dictionaryKey())) {
                List<String> entries = SITE_DICTIONARIES.get(field.dictionaryKey());
                assertNotNull(entries, "no synthetic dictionary for " + field.dictionaryKey());
                service.defineDictionary(admin, field.dictionaryKey(), entries);
                service.approveDictionary(governor, field.dictionaryKey());
            }
        }
        service.defineVersion(admin, kind, BaselineTemplates.fieldsFor(kind));
        service.approveVersion(governor, kind, 1);
        assertNotNull(store.version(organizationId, kind, 1).orElse(null));
    }

    /** One valid synthetic value per baseline field, by value kind. */
    private static Map<String, String> syntheticValues(TemplateKind kind) {
        Map<String, String> values = new HashMap<>();
        for (FieldDefinition field : BaselineTemplates.fieldsFor(kind)) {
            String value = switch (field.type()) {
                case DATE -> "2026-10-02";
                case CODE -> SITE_DICTIONARIES.get(field.dictionaryKey()).get(0);
                case FREE_TEXT -> "synthetic " + field.key();
                case TEXT -> "synthetic " + field.key();
            };
            values.put(field.key(), value);
        }
        return values;
    }

    /** Bind synthetic values to the exact baseline fields, carrying a language on free text. */
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

    /**
     * All five clause families define and approve a version, then validate and
     * save a note carrying each family's named fields.
     */
    @Test
    void definesAndApprovesVersionedTemplatesForAllFiveFamilies() {
        for (TemplateKind kind : TemplateKind.values()) {
            approveBaseline(ORG_A, adminA, governorA, kind);
            TemplateVersion effective = service.effective(clinicianA, kind);

            assertEquals(1, effective.version(), kind + " must adopt version one");
            assertTrue(effective.approved(), kind + " must be governed before use");
            assertEquals(BaselineTemplates.fieldsFor(kind), effective.fields());
            assertEquals(CLAUSE_FIELDS.get(kind), effective.fields().stream().map(FieldDefinition::key).toList(),
                    kind + " must carry the clause's named fields");

            TemplateNoteRevision full = note(kind, 1, syntheticValues(kind));
            ValidationReport report = service.validate(clinicianA, full);
            assertTrue(report.valid(), kind + " full note should validate: " + report.violations());
            assertTrue(report.violations().isEmpty(), kind + " must report no violations");

            String noteId = "NOTE-" + kind.name();
            SavedNoteRevision saved = service.saveNote(clinicianA, noteId, full);
            assertEquals(kind, saved.note().kind());
            assertEquals(ORG_A, saved.organizationId());
            assertEquals(effective.fields(), saved.fieldMeanings(),
                    kind + " must freeze the exact version's field meanings");
            assertEquals(saved, service.savedNote(clinicianA, noteId));
        }

        // A newer approved version does not rewrite the meaning of an older note.
        List<FieldDefinition> later = List.of(new FieldDefinition("chiefConcern", "presenting concern",
                FieldType.FREE_TEXT, true, null, "en"));
        service.defineVersion(adminA, TemplateKind.SOAP, later);
        service.approveVersion(governorA, TemplateKind.SOAP, 2);
        assertEquals(2, service.history(clinicianA, TemplateKind.SOAP).size());
        assertTrue(service.validate(clinicianA,
                note(TemplateKind.SOAP, 1, syntheticValues(TemplateKind.SOAP))).valid(),
                "the exact version one stays valid after version two is approved");
    }

    /** A clinician may author notes but may not define the template or its dictionary. */
    @Test
    void refusesTemplateDefinitionByClinician() {
        assertEquals(403, status(() -> service.defineDictionary(clinicianA, "obstetric_history",
                SITE_DICTIONARIES.get("obstetric_history"))));
        ApiException denied = assertThrows(ApiException.class, () -> service.defineVersion(clinicianA,
                TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP)));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
    }

    /** A template version approved in one tenant is invisible to a clinician in another. */
    @Test
    void doesNotReadAnotherTenantsTemplateVersion() {
        approveBaseline(ORG_A, adminA, governorA, TemplateKind.OBGYN);
        assertEquals(404, status(() -> service.effective(clinicianB, TemplateKind.OBGYN)));
        assertEquals(404, status(() -> service.validate(clinicianB,
                note(TemplateKind.OBGYN, 1, syntheticValues(TemplateKind.OBGYN)))));
    }

    /** A CODE field's site dictionary must itself be governed before its version can be approved. */
    @Test
    void refusesApprovalWithoutAnApprovedSiteDictionary() {
        service.defineDictionary(adminA, "obstetric_history", SITE_DICTIONARIES.get("obstetric_history"));
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        ApiException denied = assertThrows(ApiException.class,
                () -> service.approveVersion(governorA, TemplateKind.OBGYN, 1));
        assertEquals(422, denied.status());
        assertEquals("DICTIONARY_NOT_APPROVED", denied.code());
    }

    /** A note must bind to a version that exists; an unknown version is concealed. */
    @Test
    void refusesNoteAgainstUnknownVersion() {
        approveBaseline(ORG_A, adminA, governorA, TemplateKind.OBGYN);
        assertTrue(service.validate(clinicianA,
                note(TemplateKind.OBGYN, 1, syntheticValues(TemplateKind.OBGYN))).valid());
        TemplateNoteRevision unknown = note(TemplateKind.OBGYN, 99, syntheticValues(TemplateKind.OBGYN));
        assertEquals(404, status(() -> service.validate(clinicianA, unknown)));
        assertEquals(404, status(() -> service.saveNote(clinicianA, "NOTE-UNKNOWN", unknown)));
    }

    /** Every field the governed version marks required must be present to save the note. */
    @Test
    void refusesNoteMissingARequiredField() {
        approveBaseline(ORG_A, adminA, governorA, TemplateKind.OPERATIVE);
        Map<String, String> partial = syntheticValues(TemplateKind.OPERATIVE);
        partial.remove("findings");
        TemplateNoteRevision incomplete = note(TemplateKind.OPERATIVE, 1, partial);

        ValidationReport report = service.validate(clinicianA, incomplete);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("missing:findings"), report.violations().toString());
        assertEquals(422, status(() -> service.saveNote(clinicianA, "NOTE-INCOMPLETE", incomplete)));
    }

    /** Assert the domain status of a refused command. */
    private static int status(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertNotNull(exception.code());
        return exception.status();
    }

    /** A command that may throw the domain exception under test. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
