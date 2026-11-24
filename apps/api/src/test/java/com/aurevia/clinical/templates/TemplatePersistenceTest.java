package com.aurevia.clinical.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * BUILD-26 acceptance for CLN-04 and ARC-02 with synthetic data only: a published
 * template version is immutable, a saved note keeps the field meanings it was saved
 * with after the dictionary changes, and tenants with identical local keys stay
 * isolated. The domain service is exercised through its normal public entry point.
 */
class TemplatePersistenceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";

    private final TemplateActor adminA = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governorA = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinicianA = TemplateActor.of(ORG_A, "clin-a", TemplateService.CLINICIAN);

    private InMemoryTemplateStore store;
    private TemplateService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryTemplateStore();
        service = new TemplateService(store);
    }

    private void defineAndApproveObstetricDictionary(String organizationId, String subject, List<String> entries) {
        TemplateActor admin = TemplateActor.of(organizationId, "admin-" + subject, TemplateService.TENANT_ADMIN);
        TemplateActor governor = TemplateActor.of(organizationId, "gov-" + subject, TemplateService.CLINICAL_GOVERNANCE);
        service.defineDictionary(admin, "obstetric_history", entries);
        service.approveDictionary(governor, "obstetric_history");
    }

    private static TemplateNoteRevision obstetricNote(String value) {
        return new TemplateNoteRevision(TemplateKind.OBGYN, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("lmp", "2026-10-01", null),
                        new FieldValue("obstetricHistory", value, null)));
    }

    @Test
    void publishedVersionIsImmutableAndNewVersionsKeepTheirOwnFields() {
        defineAndApproveObstetricDictionary(ORG_A, "a", List.of("G0P0"));
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        TemplateVersion published = service.approveVersion(governorA, TemplateKind.OBGYN, 1);

        assertEquals(409, status(() -> service.approveVersion(governorA, TemplateKind.OBGYN, 1)));
        assertEquals(published, store.version(ORG_A, TemplateKind.OBGYN, 1).orElseThrow());

        List<FieldDefinition> changed = List.of(
                new FieldDefinition("lmp", "LMP renamed", FieldType.DATE, true, null, null),
                new FieldDefinition("obstetricHistory", "history renamed", FieldType.CODE, true,
                        "obstetric_history", null));
        service.defineVersion(adminA, TemplateKind.OBGYN, changed);
        service.approveVersion(governorA, TemplateKind.OBGYN, 2);

        List<TemplateVersion> history = service.history(clinicianA, TemplateKind.OBGYN);
        assertEquals(2, history.size());
        assertEquals("last menstrual period", field(history.get(0).fields(), "lmp").label());
        assertEquals("LMP renamed", field(history.get(1).fields(), "lmp").label());
        assertEquals("last menstrual period", field(published.fields(), "lmp").label());
    }

    @Test
    void oldNoteKeepsItsOriginalFieldMeaningsAfterTheDictionaryChanges() {
        defineAndApproveObstetricDictionary(ORG_A, "a", List.of("G0P0"));
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governorA, TemplateKind.OBGYN, 1);

        SavedNoteRevision saved = service.saveNote(clinicianA, "NOTE-1", obstetricNote("G0P0"));
        assertEquals(List.of("G0P0"), saved.dictionaries().get("obstetric_history"));
        assertEquals("last menstrual period", field(saved.fieldMeanings(), "lmp").label());

        // The site dictionary is replaced with a different permitted code set.
        store.putDictionary(ORG_A, new FieldDictionary("obstetric_history", List.of("NEW_CODE"), true, "gov-a"));
        assertEquals(List.of("NEW_CODE"), service.dictionary(adminA, "obstetric_history").entries());

        SavedNoteRevision loaded = service.savedNote(clinicianA, "NOTE-1");
        assertEquals(List.of("G0P0"), loaded.dictionaries().get("obstetric_history"));
        assertEquals("last menstrual period", field(loaded.fieldMeanings(), "lmp").label());
        assertEquals("G0P0", fieldValue(loaded.note(), "obstetricHistory"));
    }

    @Test
    void tenantsWithIdenticalLocalKeysAndNoteIdsStayIsolated() {
        defineAndApproveObstetricDictionary(ORG_A, "a", List.of("G0P0"));
        defineAndApproveObstetricDictionary(ORG_B, "b", List.of("G9P9"));
        List<FieldDefinition> fieldsA = BaselineTemplates.fieldsFor(TemplateKind.OBGYN);
        List<FieldDefinition> fieldsB = List.of(
                new FieldDefinition("lmp", "LMP-B", FieldType.DATE, true, null, null),
                new FieldDefinition("obstetricHistory", "HX-B", FieldType.CODE, true, "obstetric_history", null));
        service.defineVersion(adminA, TemplateKind.OBGYN, fieldsA);
        service.defineVersion(TemplateActor.of(ORG_B, "admin-b", TemplateService.TENANT_ADMIN),
                TemplateKind.OBGYN, fieldsB);

        service.saveNote(clinicianA, "NOTE-SAME", obstetricNote("G0P0"));
        service.saveNote(TemplateActor.of(ORG_B, "clin-b", TemplateService.CLINICIAN), "NOTE-SAME",
                obstetricNote("G9P9"));

        SavedNoteRevision noteA = service.savedNote(clinicianA, "NOTE-SAME");
        SavedNoteRevision noteB = service.savedNote(
                TemplateActor.of(ORG_B, "clin-b", TemplateService.CLINICIAN), "NOTE-SAME");
        assertEquals(List.of("G0P0"), noteA.dictionaries().get("obstetric_history"));
        assertEquals(List.of("G9P9"), noteB.dictionaries().get("obstetric_history"));
        assertEquals("last menstrual period", field(noteA.fieldMeanings(), "lmp").label());
        assertEquals("LMP-B", field(noteB.fieldMeanings(), "lmp").label());
    }

    @Test
    void saveNoteRejectsInvalidInputsAndUnauthorizedCallers() {
        // A note can only be saved by a clinician.
        assertEquals(403, status(() -> service.saveNote(adminA, "N", obstetricNote("G0P0"))));
        // Identifier and note are validated at the trust boundary.
        assertEquals(422, status(() -> service.saveNote(clinicianA, null, obstetricNote("G0P0"))));
        assertEquals(422, status(() -> service.saveNote(clinicianA, "  ", obstetricNote("G0P0"))));
        assertEquals(422, status(() -> service.saveNote(clinicianA, "N", null)));
        // An unknown version is concealed like a read of an unknown template.
        assertEquals(404, status(() -> service.saveNote(clinicianA, "N",
                new TemplateNoteRevision(TemplateKind.SOAP, 9, "PAT", "clin-a", List.of()))));
        // A note that fails its exact version cannot be saved.
        defineAndApproveObstetricDictionary(ORG_A, "a", List.of("G0P0"));
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governorA, TemplateKind.OBGYN, 1);
        assertEquals(422, status(() -> service.saveNote(clinicianA, "N", obstetricNote("UNKNOWN"))));
    }

    @Test
    void savedNoteIsConcealedWhenAbsent() {
        assertEquals(404, status(() -> service.savedNote(clinicianA, "absent")));
    }

    @Test
    void missingDictionaryIsTreatedAsAnEmptySnapshot() {
        // Defensive path: the version is valid but the referenced dictionary read
        // is unexpectedly empty; the snapshot must not fail the whole save.
        TemplateStore stub = mock(TemplateStore.class);
        FieldDefinition codeField = new FieldDefinition("obstetricHistory", "history", FieldType.CODE, true,
                "obstetric_history", null);
        TemplateVersion version = new TemplateVersion(TemplateKind.OBGYN, 1, true, List.of(codeField), "gov-a");
        FieldDictionary present = new FieldDictionary("obstetric_history", List.of("G1P0"), true, "gov-a");
        when(stub.version(ORG_A, TemplateKind.OBGYN, 1)).thenReturn(Optional.of(version));
        when(stub.dictionary(ORG_A, "obstetric_history"))
                .thenReturn(Optional.of(present), Optional.empty());
        TemplateService stubbed = new TemplateService(stub);

        TemplateNoteRevision codeOnly = new TemplateNoteRevision(TemplateKind.OBGYN, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("obstetricHistory", "G1P0", null)));
        SavedNoteRevision saved = stubbed.saveNote(clinicianA, "NOTE-M", codeOnly);
        assertEquals(List.of(), saved.dictionaries().get("obstetric_history"));
    }

    @Test
    void frozenSnapshotsCannotBeMutated() {
        defineAndApproveObstetricDictionary(ORG_A, "a", List.of("G0P0"));
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governorA, TemplateKind.OBGYN, 1);
        SavedNoteRevision saved = service.saveNote(clinicianA, "N", obstetricNote("G0P0"));

        assertThrows(UnsupportedOperationException.class, () -> saved.fieldMeanings().clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.dictionaries().clear());
        assertFalse(saved.fieldMeanings().isEmpty());
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    private static String fieldValue(TemplateNoteRevision note, String key) {
        return note.values().stream().filter(v -> key.equals(v.fieldKey())).findFirst().orElseThrow().value();
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
