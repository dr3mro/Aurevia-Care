package com.aurevia.it.clinical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.clinical.templates.TemplateActor;
import com.aurevia.clinical.templates.BaselineTemplates;
import com.aurevia.clinical.templates.FieldDefinition;
import com.aurevia.clinical.templates.FieldDictionary;
import com.aurevia.clinical.templates.FieldType;
import com.aurevia.clinical.templates.FieldValue;
import com.aurevia.clinical.templates.InMemoryTemplateStore;
import com.aurevia.clinical.templates.TemplateNoteRevision;
import com.aurevia.clinical.templates.TemplateAuditEvent;
import com.aurevia.clinical.templates.TemplateKind;
import com.aurevia.clinical.templates.TemplateService;
import com.aurevia.clinical.templates.TemplateVersion;
import com.aurevia.clinical.templates.ValidationReport;

/**
 * S-G1-04 acceptance for CLN-04: versioned SOAP, operative, OB/GYN, perinatal
 * and pediatric development templates. All fixtures are synthetic and the
 * module is exercised through its public domain-service entry point with no
 * external infrastructure.
 */
class TemplateVersioningTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";

    private final TemplateActor adminA = TemplateActor.of(ORG_A, "admin-a", TemplateService.TENANT_ADMIN);
    private final TemplateActor governorA = TemplateActor.of(ORG_A, "gov-a", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinicianA = TemplateActor.of(ORG_A, "clin-a", TemplateService.CLINICIAN);
    private final TemplateActor auditorA = TemplateActor.of(ORG_A, "aud-a", TemplateService.PRIVACY_AUDITOR);
    private final TemplateActor adminB = TemplateActor.of(ORG_B, "admin-b", TemplateService.TENANT_ADMIN);

    private TemplateService service;

    @BeforeEach
    void setUp() {
        service = new TemplateService(new InMemoryTemplateStore());
    }

    // --- A1, A2, A3, A4, A5: the five provided baseline field sets -------------

    @Test
    void providesAllFiveBaselineKinds() {
        for (TemplateKind kind : TemplateKind.values()) {
            assertFalse(BaselineTemplates.fieldsFor(kind).isEmpty(), kind.name());
        }
        assertEquals(5, TemplateKind.values().length);
    }

    @Test
    void operativeBaselineCoversProcedureDateTeamFindingsComplicationsFollowUp() {
        List<FieldDefinition> fields = BaselineTemplates.fieldsFor(TemplateKind.OPERATIVE);
        assertEquals(Set.of("procedure", "procedureDate", "team", "findings", "complications", "followUp"),
                keys(fields));
        assertTrue(fields.stream().allMatch(FieldDefinition::required));
    }

    @Test
    void obstetricBaselineCapturesLmpAndDictionaryBackedObstetricHistory() {
        List<FieldDefinition> fields = BaselineTemplates.fieldsFor(TemplateKind.OBGYN);
        assertEquals(Set.of("lmp", "obstetricHistory"), keys(fields));
        FieldDefinition lmp = field(fields, "lmp");
        assertEquals(FieldType.DATE, lmp.type());
        assertTrue(lmp.required());
        FieldDefinition history = field(fields, "obstetricHistory");
        assertEquals(FieldType.CODE, history.type());
        assertEquals("obstetric_history", history.dictionaryKey());
        assertTrue(history.required());
    }

    @Test
    void perinatalBaselineCapturesBirthAndNeonatalHistory() {
        List<FieldDefinition> fields = BaselineTemplates.fieldsFor(TemplateKind.PERINATAL);
        assertEquals(Set.of("birthHistory", "neonatalHistory"), keys(fields));
        assertTrue(fields.stream().allMatch(f -> f.type() == FieldType.FREE_TEXT && f.required()));
    }

    @Test
    void developmentBaselineCapturesMilestoneDomainObservationAndDate() {
        List<FieldDefinition> fields = BaselineTemplates.fieldsFor(TemplateKind.PEDIATRIC_DEVELOPMENT);
        assertEquals(Set.of("milestoneDomain", "observation", "date"), keys(fields));
        assertEquals("milestone_domain", field(fields, "milestoneDomain").dictionaryKey());
        assertEquals(FieldType.FREE_TEXT, field(fields, "observation").type());
        assertEquals(FieldType.DATE, field(fields, "date").type());
        assertTrue(fields.stream().allMatch(FieldDefinition::required));
    }

    // --- A6, A7, A8: draft, governance approval, immutability, version history --

    @Test
    void versionIsPendingUntilGovernanceApprovesIt() {
        defineDictionary("obstetric_history", List.of("G0P0", "G1P0"));
        service.approveDictionary(governorA, "obstetric_history");

        TemplateVersion draft = service.defineVersion(
                adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        assertEquals(1, draft.version());
        assertFalse(draft.approved());

        assertEquals(404, status(() -> service.effective(clinicianA, TemplateKind.OBGYN)));

        // A tenant admin may define but may not approve a clinical template.
        assertEquals(403, status(() -> service.approveVersion(adminA, TemplateKind.OBGYN, 1)));

        TemplateVersion approved = service.approveVersion(governorA, TemplateKind.OBGYN, 1);
        assertTrue(approved.approved());
        assertEquals("gov-a", approved.approvedBy());
        assertEquals(1, service.effective(clinicianA, TemplateKind.OBGYN).version());
    }

    @Test
    void duplicateApprovalConflictsAndUnknownVersionIsConcealed() {
        defineDictionary("obstetric_history", List.of("G0P0"));
        service.approveDictionary(governorA, "obstetric_history");
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governorA, TemplateKind.OBGYN, 1);

        assertEquals(409, status(() -> service.approveVersion(governorA, TemplateKind.OBGYN, 1)));
        assertEquals(404, status(() -> service.approveVersion(governorA, TemplateKind.OBGYN, 99)));
        assertEquals(404, status(() -> service.effective(clinicianA, TemplateKind.SOAP)));
        assertEquals(404, status(() -> service.history(clinicianA, TemplateKind.SOAP)));
    }

    @Test
    void newVersionPreservesHistoricalFieldMeanings() {
        List<FieldDefinition> v1Fields = BaselineTemplates.fieldsFor(TemplateKind.SOAP);
        service.defineVersion(adminA, TemplateKind.SOAP, v1Fields);
        service.approveVersion(governorA, TemplateKind.SOAP, 1);

        List<FieldDefinition> v2Fields = List.of(new FieldDefinition(
                "chiefConcern", " presenting concern (v2)", FieldType.FREE_TEXT, true, null, "en"));
        service.defineVersion(adminA, TemplateKind.SOAP, v2Fields);
        service.approveVersion(governorA, TemplateKind.SOAP, 2);

        List<TemplateVersion> history = service.history(clinicianA, TemplateKind.SOAP);
        assertEquals(2, history.size());
        assertEquals("chief concern", field(history.get(0).fields(), "chiefConcern").label());
        assertEquals(" presenting concern (v2)", field(history.get(1).fields(), "chiefConcern").label());
        assertEquals(2, service.effective(clinicianA, TemplateKind.SOAP).version());
    }

    @Test
    void approvingVersionBeforeItsDictionaryIsApprovedFails() {
        defineDictionary("milestone_domain", List.of("MOTOR"));
        service.defineVersion(adminA, TemplateKind.PEDIATRIC_DEVELOPMENT,
                BaselineTemplates.fieldsFor(TemplateKind.PEDIATRIC_DEVELOPMENT));
        assertEquals(422, status(() ->
                service.approveVersion(governorA, TemplateKind.PEDIATRIC_DEVELOPMENT, 1)));
        service.approveDictionary(governorA, "milestone_domain");
        assertTrue(service.approveVersion(governorA, TemplateKind.PEDIATRIC_DEVELOPMENT, 1).approved());
    }

    // --- A9, A10, A11: note validation ---------------------------------------

    @Test
    void freeTextRequiresLanguageAndCodedValuesMustBeInDictionary() {
        defineDictionary("milestone_domain", List.of("MOTOR", "LANGUAGE"));
        service.approveDictionary(governorA, "milestone_domain");
        service.defineVersion(adminA, TemplateKind.PEDIATRIC_DEVELOPMENT,
                BaselineTemplates.fieldsFor(TemplateKind.PEDIATRIC_DEVELOPMENT));
        service.approveVersion(governorA, TemplateKind.PEDIATRIC_DEVELOPMENT, 1);

        TemplateNoteRevision bad = new TemplateNoteRevision(TemplateKind.PEDIATRIC_DEVELOPMENT, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("milestoneDomain", "UNKNOWN_CODE", null),
                        new FieldValue("observation", "sits unaided", null),
                        new FieldValue("date", "2026-11-01", null)));
        ValidationReport report = service.validate(clinicianA, bad);
        assertFalse(report.valid());
        assertTrue(report.violations().stream().anyMatch(v -> v.startsWith("dictionary:milestoneDomain")));
        assertTrue(report.violations().stream().anyMatch(v -> v.startsWith("language:observation")));

        TemplateNoteRevision good = new TemplateNoteRevision(TemplateKind.PEDIATRIC_DEVELOPMENT, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("milestoneDomain", "MOTOR", null),
                        new FieldValue("observation", "sits unaided", "en"),
                        new FieldValue("date", "2026-11-01", null)));
        assertTrue(service.validate(clinicianA, good).valid());
    }

    @Test
    void missingRequiredAndUnknownFieldsAreReported() {
        service.defineVersion(adminA, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP));
        service.approveVersion(governorA, TemplateKind.SOAP, 1);

        TemplateNoteRevision incomplete = new TemplateNoteRevision(TemplateKind.SOAP, 1, "PAT-002", "clin-a",
                List.of(new FieldValue("chiefConcern", "pain", "en"),
                        new FieldValue("bogus", "value", "en")));
        ValidationReport report = service.validate(clinicianA, incomplete);
        assertFalse(report.valid());
        assertTrue(report.violations().contains("unknown:bogus"));
        assertEquals(4, report.violations().stream().filter(v -> v.startsWith("missing:")).count());
    }

    @Test
    void validatingAnUnknownVersionIsConcealedAndNullNoteIsRejected() {
        assertEquals(404, status(() -> service.validate(clinicianA,
                new TemplateNoteRevision(TemplateKind.SOAP, 9, "PAT-003", "clin-a", List.of()))));
        assertEquals(422, status(() -> service.validate(clinicianA, null)));
    }

    // --- A12: tenant isolation ------------------------------------------------

    @Test
    void tenantsWithIdenticalLocalKeysAndVersionsStayIsolated() {
        defineDictionary("obstetric_history", List.of("G0P0"));
        service.approveDictionary(governorA, "obstetric_history");
        service.defineVersion(adminA, TemplateKind.OBGYN, BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
        service.approveVersion(governorA, TemplateKind.OBGYN, 1);

        // Second tenant defines the very same local kind and version.
        service.defineDictionary(adminB, "obstetric_history", List.of("G9P9"));
        service.defineVersion(adminB, TemplateKind.OBGYN,
                List.of(new FieldDefinition("lmp", "LMP-B", FieldType.DATE, true, null, null),
                        new FieldDefinition("obstetricHistory", "HX-B", FieldType.CODE, true,
                                "obstetric_history", null)));

        assertEquals(1, service.effective(clinicianA, TemplateKind.OBGYN).version());
        assertEquals("last menstrual period",
                field(service.effective(clinicianA, TemplateKind.OBGYN).fields(), "lmp").label());
        assertEquals(404, status(() -> service.effective(
                TemplateActor.of(ORG_B, "clin-b", TemplateService.CLINICIAN), TemplateKind.OBGYN)));

        FieldDictionary peer = service.dictionary(adminB, "obstetric_history");
        assertEquals(List.of("G9P9"), peer.entries());
        assertNotEquals(peer.entries(), service.dictionary(adminA, "obstetric_history").entries());
    }

    // --- A13: authorization ---------------------------------------------------

    @Test
    void roleAuthorizationAndAuditAccess() {
        TemplateActor noRoles = TemplateActor.of(ORG_A, "none");
        assertEquals(403, status(() -> service.defineVersion(
                noRoles, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP))));
        assertEquals(403, status(() -> service.effective(noRoles, TemplateKind.SOAP)));
        assertEquals(403, status(() -> service.auditTrail(clinicianA)));
        assertEquals(403, status(() -> service.approveDictionary(clinicianA, "obstetric_history")));

        service.defineVersion(adminA, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP));
        List<TemplateAuditEvent> trail = service.auditTrail(auditorA);
        assertFalse(trail.isEmpty());
        assertTrue(trail.stream().allMatch(e -> ORG_A.equals(e.organizationId())));
        assertTrue(trail.stream().anyMatch(e -> "TEMPLATE_DEFINE".equals(e.action())
                && "SUCCESS".equals(e.outcome())));
        assertTrue(trail.stream().anyMatch(e -> "DENIED".equals(e.outcome())));
    }

    // --- A14, A15: input validation and dictionary lifecycle ------------------

    @Test
    void invalidInputsAreRejected() {
        assertEquals(422, status(() -> service.defineDictionary(adminA, "x", List.of("A"))));
        assertEquals(422, status(() -> service.defineDictionary(adminA, "good_key", List.of())));
        assertEquals(422, status(() -> service.defineDictionary(adminA, "good_key", List.of("A", "A"))));
        assertEquals(422, status(() -> service.defineDictionary(adminA, "good_key", List.of("A", " "))));
        assertEquals(422, status(() -> service.defineVersion(adminA, null, List.of())));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP, List.of())));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", "A", FieldType.TEXT, true, null, null),
                        new FieldDefinition("a", "A2", FieldType.TEXT, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition(" ", "A", FieldType.TEXT, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", " ", FieldType.TEXT, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", "A", null, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", "A", FieldType.CODE, true, "missing_dict", null)))));
    }

    @Test
    void optionalFieldsWithoutValuesAreValid() {
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("requiredOne", "required", FieldType.TEXT, true, null, null),
                new FieldDefinition("optionalOne", "optional", FieldType.TEXT, false, null, null));
        service.defineVersion(adminA, TemplateKind.SOAP, fields);
        service.approveVersion(governorA, TemplateKind.SOAP, 1);

        ValidationReport report = service.validate(clinicianA, new TemplateNoteRevision(
                TemplateKind.SOAP, 1, "PAT-OPT", "clin-a",
                List.of(new FieldValue("requiredOne", "present", null))));
        assertTrue(report.valid());
        assertTrue(report.violations().isEmpty());
    }

    @Test
    void nullAndBoundaryInputsAreRejected() {
        assertEquals(422, status(() -> service.defineDictionary(adminA, null, List.of("A"))));
        assertEquals(422, status(() -> service.defineDictionary(
                adminA, "a".repeat(65), List.of("A"))));
        assertEquals(422, status(() -> service.defineDictionary(adminA, "good_key", null)));
        assertEquals(422, status(() -> service.defineDictionary(
                adminA, "good_key", java.util.Arrays.asList(null, "A"))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP, null)));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition(null, "A", FieldType.TEXT, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", null, FieldType.TEXT, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", "A", FieldType.CODE, true, null, null)))));
        assertEquals(422, status(() -> service.defineVersion(adminA, TemplateKind.SOAP,
                List.of(new FieldDefinition("a", "A", FieldType.CODE, true, " ", null)))));
    }

    @Test
    void presentButBlankAndMalformedValuesAreRejected() {
        List<FieldDefinition> fields = List.of(
                new FieldDefinition("requiredText", "required text", FieldType.TEXT, true, null, null),
                new FieldDefinition("clinicalDate", "clinical date", FieldType.DATE, true, null, null));
        service.defineVersion(adminA, TemplateKind.SOAP, fields);
        service.approveVersion(governorA, TemplateKind.SOAP, 1);

        ValidationReport blank = service.validate(clinicianA, new TemplateNoteRevision(TemplateKind.SOAP, 1,
                "PAT-BLANK", "clin-a", List.of(new FieldValue("requiredText", "  ", null),
                        new FieldValue("clinicalDate", "2026-11-01", null))));
        assertFalse(blank.valid());
        assertTrue(blank.violations().contains("blank:requiredText"));

        ValidationReport malformed = service.validate(clinicianA, new TemplateNoteRevision(TemplateKind.SOAP, 1,
                "PAT-BLANK", "clin-a", List.of(new FieldValue("requiredText", "documented", null),
                        new FieldValue("clinicalDate", "not-a-date", null))));
        assertFalse(malformed.valid());
        assertTrue(malformed.violations().contains("format:clinicalDate"));

        ValidationReport good = service.validate(clinicianA, new TemplateNoteRevision(TemplateKind.SOAP, 1,
                "PAT-BLANK", "clin-a", List.of(new FieldValue("requiredText", "documented", null),
                        new FieldValue("clinicalDate", "2026-11-01", null))));
        assertTrue(good.valid());
    }

    @Test
    void concurrentDefineAllocatesDistinctVersions() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<TemplateVersion>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return service.defineVersion(adminA, TemplateKind.SOAP,
                            List.of(new FieldDefinition("a", "A", FieldType.TEXT, true, null, null)));
                }));
            }
            Set<Integer> versions = new HashSet<>();
            for (Future<TemplateVersion> future : futures) {
                versions.add(future.get().version());
            }
            assertEquals(threads, versions.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void frozenCollectionsCannotBeMutatedByCallers() {
        List<FieldDefinition> input = new ArrayList<>(
                List.of(new FieldDefinition("a", "A", FieldType.TEXT, true, null, null)));
        TemplateVersion version = service.defineVersion(adminA, TemplateKind.SOAP, input);
        input.clear();
        assertEquals(1, version.fields().size());
        assertThrows(UnsupportedOperationException.class, () -> version.fields().clear());

        List<String> entries = new ArrayList<>(List.of("G0P0"));
        FieldDictionary dictionary = service.defineDictionary(adminA, "obstetric_history", entries);
        entries.clear();
        assertEquals(List.of("G0P0"), dictionary.entries());
        assertThrows(UnsupportedOperationException.class, () -> dictionary.entries().add("X"));

        List<FieldValue> values = new ArrayList<>(List.of(new FieldValue("a", "v", null)));
        TemplateNoteRevision note = new TemplateNoteRevision(TemplateKind.SOAP, 1, "PAT", "clin", values);
        values.clear();
        assertEquals(1, note.values().size());
        assertThrows(UnsupportedOperationException.class, () -> note.values().clear());

        TemplateActor actor = TemplateActor.of(ORG_A, "gov", TemplateService.CLINICAL_GOVERNANCE);
        assertThrows(UnsupportedOperationException.class, () -> actor.roles().clear());
    }

    @Test
    void auditFactsCarryActorAndRemainAppendOnly() {
        service.defineVersion(adminA, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP));
        List<TemplateAuditEvent> first = service.auditTrail(auditorA);
        assertThrows(UnsupportedOperationException.class, first::clear);
        TemplateAuditEvent defineEvent = first.stream()
                .filter(e -> "TEMPLATE_DEFINE".equals(e.action()) && "SUCCESS".equals(e.outcome()))
                .findFirst().orElseThrow();
        assertEquals("admin-a", defineEvent.actorSubject());

        service.approveVersion(governorA, TemplateKind.SOAP, 1);
        List<TemplateAuditEvent> second = service.auditTrail(auditorA);
        assertEquals(first.size() + 2, second.size());
        assertTrue(first.stream().allMatch(second::contains));
    }

    @Test
    void crossTenantVersionAndAuditAreConcealed() {
        service.defineVersion(adminA, TemplateKind.SOAP, BaselineTemplates.fieldsFor(TemplateKind.SOAP));
        TemplateActor governorB = TemplateActor.of(ORG_B, "gov-b", TemplateService.CLINICAL_GOVERNANCE);
        assertEquals(404, status(() -> service.approveVersion(governorB, TemplateKind.SOAP, 1)));
        TemplateActor auditorB = TemplateActor.of(ORG_B, "aud-b", TemplateService.PRIVACY_AUDITOR);
        List<TemplateAuditEvent> peerTrail = service.auditTrail(auditorB);
        assertTrue(peerTrail.stream().allMatch(e -> ORG_B.equals(e.organizationId())));
        assertTrue(peerTrail.stream().noneMatch(e -> "admin-a".equals(e.actorSubject())));
        assertFalse(service.auditTrail(auditorA).isEmpty());
    }

    @Test
    void dictionaryLifecycleEnforcesDuplicateAndMissingRules() {
        defineDictionary("obstetric_history", List.of("G0P0"));
        assertEquals(409, status(() -> service.defineDictionary(adminA, "obstetric_history", List.of("X"))));
        assertEquals(404, status(() -> service.approveDictionary(governorA, "absent_dict")));
        service.approveDictionary(governorA, "obstetric_history");
        assertEquals(409, status(() -> service.approveDictionary(governorA, "obstetric_history")));
        assertEquals(404, status(() -> service.dictionary(adminA, "absent_dict")));
        assertTrue(service.dictionary(adminA, "obstetric_history").approved());
    }

    // --- helper methods -------------------------------------------------------

    private void defineDictionary(String key, List<String> entries) {
        service.defineDictionary(adminA, key, entries);
    }

    private static Set<String> keys(List<FieldDefinition> fields) {
        return fields.stream().map(FieldDefinition::key).collect(java.util.stream.Collectors.toSet());
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
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
