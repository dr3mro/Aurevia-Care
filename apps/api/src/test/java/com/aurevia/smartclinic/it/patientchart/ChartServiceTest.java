package com.aurevia.smartclinic.it.patientchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.patient.chart.ChartActionPolicy;
import com.aurevia.smartclinic.patient.chart.ChartController;
import com.aurevia.smartclinic.patient.chart.ChartEntry;
import com.aurevia.smartclinic.patient.chart.ChartRepository;
import com.aurevia.smartclinic.patient.chart.ChartRepository.CatalogHit;
import com.aurevia.smartclinic.patient.chart.ChartService;
import com.aurevia.smartclinic.patient.chart.ChartView;
import com.aurevia.smartclinic.patient.chart.EntrySource;
import com.aurevia.smartclinic.patient.chart.EntryType;
import com.aurevia.smartclinic.patient.chart.PresenceState;
import com.aurevia.smartclinic.patient.chart.RecordEntryCommand;
import com.aurevia.smartclinic.patient.chart.VerificationState;
import com.aurevia.smartclinic.tenant.TenantContext;

/** Fast branch coverage of the CLN-03 domain service. */
class ChartServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private ChartRepository entries;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private ChartService service;
    private TenantContext context;

    @BeforeEach
    void setUp() {
        entries = mock(ChartRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        service = new ChartService(entries, new ChartActionPolicy(), transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        context = new TenantContext(ORG, ACTOR, "CHART", "corr-1");
        TenantContext.set(context);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void activeClinician() {
        when(entries.hasActiveMembership(ACTOR)).thenReturn(true);
        when(entries.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
    }

    private void activeAssistant() {
        when(entries.hasActiveMembership(ACTOR)).thenReturn(true);
        when(entries.activeRoles(ACTOR)).thenReturn(List.of("clinical_assistant"));
    }

    private void assertAudit(String action, String outcome) {
        verify(audit, atLeastOnce()).append(eq(context), eq(action), eq(action),
                eq("CHART_ENTRY"), eq("CHART"), anyString(), eq(outcome));
    }

    private static RecordEntryCommand command() {
        return new RecordEntryCommand(PATIENT, null, EntryType.PROBLEM, "headache", "R51",
                "diagnosis", EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null, null, null,
                null, null, null, ACTOR);
    }

    private static ChartEntry entry(VerificationState state, int version) {
        return new ChartEntry(ID, PATIENT, null, EntryType.PROBLEM, "headache", "R51", "diagnosis",
                null, null, null, EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null,
                state, null, null, null, null, null, null, null, ACTOR, ACTOR, version, NOW);
    }

    private static ApiException failure(int status, String code, Runnable action) {
        return assertThrows(ApiException.class, () -> {
            action.run();
        });
    }

    @Test
    void validationRejectsNullCommandAndPatientBeforeAnyRepositoryContact() {
        ApiException nullCommand = failure(422, "INVALID_COMMAND", () -> service.record(null));
        assertEquals(422, nullCommand.status());
        assertEquals("INVALID_COMMAND", nullCommand.code());
        failure(422, "INVALID_PATIENT_ID", () -> service.record(
                new RecordEntryCommand(null, null, EntryType.PROBLEM, "x", null, null,
                        EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null, null, null, null,
                        null, null, null)));
        failure(422, "INVALID_PATIENT_ID", () -> service.record(
                new RecordEntryCommand("not-a-uuid", null, EntryType.PROBLEM, "x", null, null,
                        EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null, null, null, null,
                        null, null, null)));
        verifyNoInteractions(entries, audit, transactions);
    }

    @Test
    void validationRejectsMissingTypeTextSourceAndPresence() {
        failure(422, "INVALID_TYPE", () -> service.record(new RecordEntryCommand(PATIENT, null,
                null, "x", null, null, EntrySource.PATIENT_REPORTED, PresenceState.PRESENT,
                null, null, null, null, null, null, null)));
        failure(422, "INVALID_TEXT", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, null, null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, null)));
        failure(422, "INVALID_TEXT", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, "  ", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, null)));
        failure(422, "INVALID_TEXT", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, "x".repeat(2001), null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, null)));
        failure(422, "INVALID_SOURCE", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, "x", null, null, null, PresenceState.PRESENT,
                null, null, null, null, null, null, null)));
        failure(422, "INVALID_PRESENCE", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, "x", null, null, EntrySource.PATIENT_REPORTED, null,
                null, null, null, null, null, null, null)));
        verifyNoInteractions(entries);
    }

    @Test
    void validationRejectsUnknownReasonMismatchAndUnpairedCode() {
        failure(422, "INVALID_UNKNOWN_REASON", () -> service.record(new RecordEntryCommand(PATIENT,
                null, EntryType.ALLERGY, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.UNKNOWN, null, null, null, null, null, null, null)));
        failure(422, "INVALID_UNKNOWN_REASON", () -> service.record(new RecordEntryCommand(PATIENT,
                null, EntryType.ALLERGY, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.ABSENT, "reason", null, null, null, null, null, null)));
        failure(422, "INVALID_CODE", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, "x", "R51", null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, null)));
        failure(422, "INVALID_CODE", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.PROBLEM, "x", null, "diagnosis", EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, null)));
        verifyNoInteractions(entries);
    }

    @Test
    void validationRejectsReversedPeriodAndIncompleteVitalAndForgedAuthor() {
        failure(422, "INVALID_EFFECTIVE_PERIOD", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.PROBLEM, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, NOW, NOW.minusSeconds(1), null, null, null, null)));
        failure(422, "INVALID_VITAL", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.VITAL, "temp", null, null, EntrySource.DEVICE, PresenceState.PRESENT,
                null, null, null, null, null, "C", null)));
        failure(422, "INVALID_VITAL", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.VITAL, "temp", null, null, EntrySource.DEVICE, PresenceState.PRESENT,
                null, null, null, NOW, "37.1", null, null)));
        failure(422, "INVALID_VITAL", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.VITAL, "temp", null, null, EntrySource.DEVICE, PresenceState.PRESENT,
                null, null, null, null, "37.1", "C", null)));
        failure(422, "INVALID_CLINICAL_AUTHOR", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.PROBLEM, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, " ")));
        failure(422, "INVALID_CLINICAL_AUTHOR", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.PROBLEM, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, "x".repeat(121))));
        failure(422, "INVALID_CLINICAL_AUTHOR", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.PROBLEM, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, null, null, null, null, null, "someone-else")));
        verifyNoInteractions(entries);
    }

    @Test
    void recordDeniedForInactiveMembershipAndWrongRole() {
        when(entries.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException inactive = failure(403, "FORBIDDEN", () -> service.record(command()));
        assertEquals(403, inactive.status());
        assertAudit("CHART_RECORD", "DENIED");
        verify(entries, never()).insert(any());

        when(entries.hasActiveMembership(ACTOR)).thenReturn(true);
        when(entries.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        failure(403, "FORBIDDEN", () -> service.record(command()));
        assertAudit("CHART_RECORD", "DENIED");
        verify(entries, never()).insert(any());
    }

    @Test
    void recordRejectsUnknownPatient() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(false);
        ApiException missing = failure(404, "NOT_FOUND", () -> service.record(command()));
        assertEquals(404, missing.status());
        assertAudit("CHART_RECORD", "NOT_FOUND");
        verify(entries, never()).insert(any());
    }

    @Test
    void recordSnapshotsCatalogMappingAndPreservesOriginals() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(true);
        when(entries.lookupCatalog("diagnosis", "R51"))
                .thenReturn(Optional.of(new CatalogHit("R51", "Headache", 4)));
        ChartEntry created = service.record(command());
        assertEquals("R51", created.originalCode());
        assertEquals("headache", created.originalText());
        assertEquals("R51", created.mappedCode());
        assertEquals("Headache", created.mappedText());
        assertEquals(Integer.valueOf(4), created.catalogVersion());
        assertEquals(VerificationState.UNVERIFIED, created.verification());
        assertEquals(1, created.version());
        assertEquals(ACTOR, created.dataEntryActor());
        assertNotNull(created.id());
        assertEquals(NOW, created.recordedAt());
        verify(entries).insert(created);
        assertAudit("CHART_RECORD", "SUCCESS");
    }

    @Test
    void recordWithoutMappingKeepsOriginalsAndNullMapping() {
        activeAssistant();
        when(entries.patientExists(PATIENT)).thenReturn(true);
        when(entries.lookupCatalog("diagnosis", "R51")).thenReturn(Optional.empty());
        RecordEntryCommand assistantCommand = new RecordEntryCommand(PATIENT, null, EntryType.PROBLEM,
                "headache", "R51", "diagnosis", EntrySource.PATIENT_REPORTED, PresenceState.PRESENT,
                null, null, null, null, null, null, null);
        ChartEntry created = service.record(assistantCommand);
        assertNull(created.mappedCode());
        assertNull(created.mappedText());
        assertNull(created.catalogVersion());
        assertEquals("R51", created.originalCode());
        assertAudit("CHART_RECORD", "SUCCESS");
    }

    @Test
    void recordDoesNotLookUpMappingForOtherTypes() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(true);
        RecordEntryCommand medication = new RecordEntryCommand(PATIENT, null, EntryType.MEDICATION,
                "aspirin", "A01", "local-drug", EntrySource.CLINICIAN_OBSERVED,
                PresenceState.PRESENT, null, null, null, null, null, null, null);
        service.record(medication);
        verify(entries, never()).lookupCatalog(anyString(), anyString());
        assertAudit("CHART_RECORD", "SUCCESS");
    }

    @Test
    void recordTranslatesForeignKeyAndGenericIntegrityFailures() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(true);
        org.mockito.Mockito.doThrow(integrity("23503")).when(entries).insert(any());
        failure(404, "NOT_FOUND", () -> service.record(command()));
        assertAudit("CHART_RECORD", "NOT_FOUND");

        org.mockito.Mockito.doThrow(integrity("23505")).when(entries).insert(any());
        failure(409, "CONFLICT", () -> service.record(command()));
        assertAudit("CHART_RECORD", "CONFLICT");
    }

    @Test
    void verifyRejectsInvalidIdDenialMissingStaleAndAlreadyVerified() {
        failure(422, "INVALID_ENTRY_ID", () -> service.verify("bad", 1));
        when(entries.hasActiveMembership(ACTOR)).thenReturn(false);
        failure(403, "FORBIDDEN", () -> service.verify(ID, 1));
        assertAudit("CHART_VERIFY", "DENIED");

        activeClinician();
        when(entries.find(ID)).thenReturn(Optional.empty());
        failure(404, "NOT_FOUND", () -> service.verify(ID, 1));
        assertAudit("CHART_VERIFY", "NOT_FOUND");

        when(entries.find(ID)).thenReturn(Optional.of(entry(VerificationState.VERIFIED, 2)));
        ApiException already = failure(409, "ALREADY_VERIFIED", () -> service.verify(ID, 2));
        assertEquals(409, already.status());
        assertAudit("CHART_VERIFY", "CONFLICT");

        when(entries.find(ID)).thenReturn(Optional.of(entry(VerificationState.UNVERIFIED, 3)));
        failure(412, "STALE_VERSION", () -> service.verify(ID, 1));
        assertAudit("CHART_VERIFY", "CONFLICT");
    }

    @Test
    void verifyReturnsStaleWhenConcurrentUpdateWins() {
        activeClinician();
        when(entries.find(ID)).thenReturn(Optional.of(entry(VerificationState.UNVERIFIED, 1)));
        when(entries.verify(ID, 1, ACTOR, NOW)).thenReturn(false);
        failure(412, "STALE_VERSION", () -> service.verify(ID, 1));
        assertAudit("CHART_VERIFY", "CONFLICT");
    }

    @Test
    void verifySuccessReturnsVerifiedEntryAtNextVersion() {
        activeClinician();
        when(entries.find(ID)).thenReturn(Optional.of(entry(VerificationState.UNVERIFIED, 1)));
        when(entries.verify(ID, 1, ACTOR, NOW)).thenReturn(true);
        ChartEntry verified = service.verify(ID, 1);
        assertEquals(VerificationState.VERIFIED, verified.verification());
        assertEquals(ACTOR, verified.verifiedBy());
        assertEquals(NOW, verified.verifiedAt());
        assertEquals(2, verified.version());
        assertEquals("headache", verified.originalText());
        verify(entries).verify(ID, 1, ACTOR, NOW);
        assertAudit("CHART_VERIFY", "SUCCESS");
    }

    @Test
    void readsHandleInvalidDeniedAndMissing() {
        failure(422, "INVALID_ENTRY_ID", () -> service.get("bad"));
        failure(422, "INVALID_PATIENT_ID", () -> service.listForPatient("bad"));

        when(entries.hasActiveMembership(ACTOR)).thenReturn(false);
        failure(403, "FORBIDDEN", () -> service.get(ID));
        failure(403, "FORBIDDEN", () -> service.listForPatient(PATIENT));
        assertAudit("CHART_READ", "DENIED");

        activeClinician();
        when(entries.find(ID)).thenReturn(Optional.empty());
        failure(404, "NOT_FOUND", () -> service.get(ID));
        assertAudit("CHART_READ", "NOT_FOUND");
    }

    @Test
    void readsReturnEntriesAndAnImmutableView() {
        activeClinician();
        when(entries.find(ID)).thenReturn(Optional.of(entry(VerificationState.UNVERIFIED, 1)));
        assertEquals(ID, service.get(ID).id());
        assertAudit("CHART_READ", "SUCCESS");

        when(entries.chart(PATIENT)).thenReturn(new java.util.ArrayList<>(
                List.of(entry(VerificationState.UNVERIFIED, 1))));
        ChartView view = service.listForPatient(PATIENT);
        assertEquals(1, view.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
        assertAudit("CHART_READ", "SUCCESS");
    }

    @Test
    void unauthenticatedContextFailsClosed() {
        TenantContext.clear();
        ApiException failure = failure(401, "UNAUTHENTICATED", () -> service.record(command()));
        assertEquals(401, failure.status());
        assertThrows(ApiException.class, () -> service.get(ID));
        assertThrows(ApiException.class, () -> service.verify(ID, 1));
        assertThrows(ApiException.class, () -> service.listForPatient(PATIENT));
        verifyNoInteractions(entries);
    }

    @Test
    void unknownAssertionWithReasonIsAcceptedAndVitalWithAllFieldsIsAccepted() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(true);
        RecordEntryCommand allergy = new RecordEntryCommand(PATIENT, null, EntryType.ALLERGY,
                "drug allergy", null, null, EntrySource.PATIENT_REPORTED, PresenceState.UNKNOWN,
                "not-asked", null, null, null, null, null, null);
        assertEquals(PresenceState.UNKNOWN, service.record(allergy).presence());

        RecordEntryCommand vital = new RecordEntryCommand(PATIENT, null, EntryType.VITAL, "temp",
                null, null, EntrySource.DEVICE, PresenceState.PRESENT, null, null, null, NOW,
                "37.1", "C", null);
        assertEquals("37.1", service.record(vital).value());
        assertTrue(service.record(vital).version() == 1);
        verify(entries, atLeastOnce()).insert(any());
    }

    @Test
    void remainingValidationShortCircuitBranches() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(true);

        // PROBLEM without an original code must not consult the catalog.
        ChartEntry plain = service.record(new RecordEntryCommand(PATIENT, null, EntryType.PROBLEM,
                "plain", null, null, EntrySource.CLINICIAN_OBSERVED, PresenceState.PRESENT,
                null, null, null, null, null, null, null));
        assertEquals("plain", plain.originalText());
        verify(entries, never()).lookupCatalog(anyString(), anyString());

        // Blank unknown reason is still an unknown reason.
        failure(422, "INVALID_UNKNOWN_REASON", () -> service.record(new RecordEntryCommand(PATIENT,
                null, EntryType.ALLERGY, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.UNKNOWN, " ", null, null, null, null, null, null)));
        // A blank reason on a non-unknown assertion is ignored.
        assertEquals(PresenceState.ABSENT, service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.ALLERGY, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.ABSENT, " ", null, null, null, null, null, null)).presence());

        // Effective window: only a start, a valid ordered window.
        assertEquals(null, service.record(new RecordEntryCommand(PATIENT, null, EntryType.PROBLEM,
                "x", null, null, EntrySource.CLINICIAN_OBSERVED, PresenceState.PRESENT,
                null, NOW, null, null, null, null, null)).effectiveTo());
        assertEquals(NOW, service.record(new RecordEntryCommand(PATIENT, null, EntryType.PROBLEM,
                "x", null, null, EntrySource.CLINICIAN_OBSERVED, PresenceState.PRESENT,
                null, NOW, NOW.plusSeconds(60), null, null, null, null)).effectiveFrom());

        // Blank vital value and blank vital unit are invalid.
        failure(422, "INVALID_VITAL", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.VITAL, "temp", null, null, EntrySource.DEVICE, PresenceState.PRESENT,
                null, null, null, NOW, " ", "C", null)));
        failure(422, "INVALID_VITAL", () -> service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.VITAL, "temp", null, null, EntrySource.DEVICE, PresenceState.PRESENT,
                null, null, null, NOW, "37.1", " ", null)));
    }

    private static DataIntegrityViolationException integrity(String sqlState) {
        return new DataIntegrityViolationException("constraint",
                new SQLException("constraint", sqlState));
    }
}

/** Fast coverage of the CLN-03 HTTP adapter. */
class ChartControllerTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";

    private ChartService service;
    private ChartController controller;

    @BeforeEach
    void setUp() {
        service = mock(ChartService.class);
        controller = new ChartController(service);
    }

    @Test
    void recordReturnsCreatedWithLocation() {
        RecordEntryCommand command = command();
        when(service.record(eq(command))).thenReturn(entry());
        var response = controller.record(command);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("/api/v1/chart/entries/" + ID, response.getHeaders().getLocation().toString());
        assertEquals(ID, response.getBody().id());
        verify(service).record(command);
    }

    @Test
    void verifyParsesIfMatchBeforeDelegating() {
        when(service.verify(ID, 3)).thenReturn(entry());
        assertEquals(ID, controller.verify(ID, "W/\"3\"").id());
        verify(service).verify(ID, 3);
    }

    @Test
    void verifyPropagatesMissingPrecondition() {
        ApiException failure = assertThrows(ApiException.class, () -> controller.verify(ID, null));
        assertEquals(428, failure.status());
    }

    @Test
    void chartReturnsTheView() {
        when(service.listForPatient(PATIENT)).thenReturn(new ChartView(List.of(entry())));
        assertEquals(1, controller.chart(PATIENT).entries().size());
        verify(service).listForPatient(PATIENT);
    }

    @Test
    void getReturnsOneEntry() {
        when(service.get(any())).thenReturn(entry());
        assertEquals(ID, controller.get(ID).id());
        verify(service).get(ID);
    }

    private static ChartEntry entry() {
        return new ChartEntry(ID, PATIENT, null, EntryType.PROBLEM, "headache", "R51",
                "diagnosis", null, null, null, EntrySource.PATIENT_REPORTED, PresenceState.PRESENT,
                null, VerificationState.UNVERIFIED, null, null, null, null, null, null, null,
                "dr-a", "assistant-a", 1, Instant.parse("2026-11-19T10:00:00Z"));
    }

    private static RecordEntryCommand command() {
        return new RecordEntryCommand(PATIENT, null, EntryType.PROBLEM, "headache", "R51",
                "diagnosis", EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null, null, null,
                null, null, null, "dr-a");
    }
}
