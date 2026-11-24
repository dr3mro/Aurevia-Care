package com.aurevia.it.chart.record;

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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.chart.record.ChartActionPolicy;
import com.aurevia.chart.record.ChartController;
import com.aurevia.chart.record.ChartEntry;
import com.aurevia.chart.record.ChartRepository;
import com.aurevia.chart.record.ChartRepository.CatalogHit;
import com.aurevia.chart.record.ChartService;
import com.aurevia.chart.record.ChartView;
import com.aurevia.chart.record.EntrySource;
import com.aurevia.chart.record.EntryType;
import com.aurevia.chart.record.PresenceState;
import com.aurevia.chart.record.RecordEntryCommand;
import com.aurevia.chart.record.VerificationState;
import com.aurevia.tenant.TenantContext;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Fast branch coverage of the CLN-03 domain service. */
class ChartRecordServiceTest {

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
    void validationBoundsOptionalTextBeforeAnyRepositoryContact() {
        failure(422, "INVALID_ENCOUNTER_ID", () -> service.record(new RecordEntryCommand(
                PATIENT, "e".repeat(121), EntryType.PROBLEM, "x", null, null,
                EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null, null, null, null,
                null, null, null)));
        failure(422, "INVALID_CODE", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.PROBLEM, "x", "c".repeat(121), null,
                EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null, null, null, null,
                null, null, null)));
        failure(422, "INVALID_VALUE", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.VITAL, "temp", null, null, EntrySource.DEVICE,
                PresenceState.PRESENT, null, null, null, NOW, "v".repeat(501), "C", null)));
        failure(422, "INVALID_UNIT", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.VITAL, "temp", null, null, EntrySource.DEVICE,
                PresenceState.PRESENT, null, null, null, NOW, "37.1", "u".repeat(121), null)));
        failure(422, "INVALID_UNKNOWN_REASON", () -> service.record(new RecordEntryCommand(
                PATIENT, null, EntryType.ALLERGY, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.UNKNOWN, "r".repeat(501), null, null, null, null, null, null)));
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
    void verifyDeniedForActiveNonClinicianAndAudited() {
        activeAssistant();
        when(entries.find(ID)).thenReturn(Optional.of(entry(VerificationState.UNVERIFIED, 1)));
        ApiException denial = failure(403, "FORBIDDEN", () -> service.verify(ID, 1));
        assertEquals(403, denial.status());
        assertAudit("CHART_VERIFY", "DENIED");
        verify(entries, never()).verify(anyString(), anyInt(), anyString(), any());
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
    void verifyAssignsAccountableClinicalAuthorWhenRecorderLeftItOpen() {
        activeClinician();
        ChartEntry openAuthor = new ChartEntry(ID, PATIENT, null, EntryType.VITAL, "temperature",
                null, null, null, null, null, EntrySource.DEVICE, PresenceState.PRESENT, null,
                VerificationState.UNVERIFIED, null, null, null, null, NOW, "37.1", "Celsius",
                null, "assistant-a", 1, NOW);
        when(entries.find(ID)).thenReturn(Optional.of(openAuthor));
        when(entries.verify(ID, 1, ACTOR, NOW)).thenReturn(true);

        ChartEntry verified = service.verify(ID, 1);
        assertEquals("assistant-a", verified.dataEntryActor());
        assertEquals(ACTOR, verified.clinicalAuthor());
        assertNotEquals(verified.dataEntryActor(), verified.clinicalAuthor());
        assertEquals(ACTOR, verified.verifiedBy());
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
        // A blank reason on a non-unknown assertion is normalized away, never stored.
        ChartEntry absent = service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.ALLERGY, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.ABSENT, " ", null, null, null, null, null, null));
        assertEquals(PresenceState.ABSENT, absent.presence());
        assertNull(absent.unknownReason());

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

    @Test
    void blankUnknownReasonOnNonUnknownEntryIsNormalizedToNull() {
        activeClinician();
        when(entries.patientExists(PATIENT)).thenReturn(true);
        ChartEntry created = service.record(new RecordEntryCommand(PATIENT, null,
                EntryType.MEDICATION, "x", null, null, EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, "  ", null, null, null, null, null, null));
        assertNull(created.unknownReason());
        verify(entries).insert(created);
    }

    private static DataIntegrityViolationException integrity(String sqlState) {
        return new DataIntegrityViolationException("constraint",
                new SQLException("constraint", sqlState));
    }
}

/** Fast coverage of the CLN-03 HTTP adapter. */
class ChartRecordControllerTest {

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
        assertEquals("/api/v1/chart/records/entries/" + ID, response.getHeaders().getLocation().toString());
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

/** Fast coverage of the CLN-03 value types and their contract tokens. */
class ChartRecordValueTest {

    private static final Instant TIME = Instant.parse("2026-11-19T10:00:00Z");

    @Test
    void enumTokensMatchTheDatabaseChecks() {
        assertArrayEquals(new EntryType[] {EntryType.PROBLEM, EntryType.ALLERGY,
                EntryType.MEDICATION, EntryType.VITAL}, EntryType.values());
        assertArrayEquals(new PresenceState[] {PresenceState.PRESENT, PresenceState.ABSENT,
                PresenceState.UNKNOWN}, PresenceState.values());
        assertArrayEquals(new VerificationState[] {VerificationState.UNVERIFIED,
                VerificationState.VERIFIED}, VerificationState.values());
        assertArrayEquals(new EntrySource[] {EntrySource.PATIENT_REPORTED,
                EntrySource.CLINICIAN_OBSERVED, EntrySource.EXTERNAL_RECORD, EntrySource.DEVICE},
                EntrySource.values());
        assertEquals(EntryType.VITAL, EntryType.valueOf("VITAL"));
        assertEquals(PresenceState.ABSENT, PresenceState.valueOf("ABSENT"));
        assertEquals(VerificationState.VERIFIED, VerificationState.valueOf("VERIFIED"));
        assertEquals(EntrySource.DEVICE, EntrySource.valueOf("DEVICE"));
        assertThrows(IllegalArgumentException.class, () -> EntryType.valueOf("NOPE"));
    }

    @Test
    void chartEntryExposesEveryProvenanceField() {
        ChartEntry entry = entry();
        assertEquals("e-1", entry.id());
        assertEquals("p-1", entry.patientId());
        assertEquals("enc-1", entry.encounterId());
        assertEquals(EntryType.VITAL, entry.type());
        assertEquals("temperature", entry.originalText());
        assertEquals("TEMP", entry.originalCode());
        assertEquals("local-vitals", entry.codeSystem());
        assertEquals("C", entry.mappedCode());
        assertEquals("Celsius", entry.mappedText());
        assertEquals(Integer.valueOf(2), entry.catalogVersion());
        assertEquals(EntrySource.DEVICE, entry.source());
        assertEquals(PresenceState.PRESENT, entry.presence());
        assertEquals("reason", entry.unknownReason());
        assertEquals(VerificationState.VERIFIED, entry.verification());
        assertEquals("dr-a", entry.verifiedBy());
        assertEquals(TIME, entry.verifiedAt());
        assertEquals(TIME, entry.effectiveFrom());
        assertEquals(TIME, entry.effectiveTo());
        assertEquals(TIME, entry.measuredAt());
        assertEquals("37.1", entry.value());
        assertEquals("Celsius", entry.unit());
        assertEquals("dr-a", entry.clinicalAuthor());
        assertEquals("assistant-a", entry.dataEntryActor());
        assertEquals(2, entry.version());
        assertEquals(TIME, entry.recordedAt());
        assertEquals(entry, entry());
        assertEquals(entry.hashCode(), entry().hashCode());
        assertNotNull(entry.toString());
        assertNotEquals(entry, new CatalogHit("x", "y", 1));
    }

    @Test
    void commandCarriesTheSubmittedFields() {
        RecordEntryCommand command = new RecordEntryCommand("p-1", "enc-1", EntryType.PROBLEM,
                "headache", "R51", "diagnosis", EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, TIME, null, null, null, null, "dr-a");
        assertEquals("p-1", command.patientId());
        assertEquals("enc-1", command.encounterId());
        assertEquals(EntryType.PROBLEM, command.type());
        assertEquals("headache", command.originalText());
        assertEquals("R51", command.originalCode());
        assertEquals("diagnosis", command.codeSystem());
        assertEquals(EntrySource.PATIENT_REPORTED, command.source());
        assertEquals(PresenceState.PRESENT, command.presence());
        assertEquals(null, command.unknownReason());
        assertEquals(TIME, command.effectiveFrom());
        assertEquals(null, command.effectiveTo());
        assertEquals(null, command.measuredAt());
        assertEquals(null, command.value());
        assertEquals(null, command.unit());
        assertEquals("dr-a", command.clinicalAuthor());
        assertEquals(command, new RecordEntryCommand("p-1", "enc-1", EntryType.PROBLEM,
                "headache", "R51", "diagnosis", EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, TIME, null, null, null, null, "dr-a"));
        assertEquals(command.hashCode(), new RecordEntryCommand("p-1", "enc-1", EntryType.PROBLEM,
                "headache", "R51", "diagnosis", EntrySource.PATIENT_REPORTED,
                PresenceState.PRESENT, null, TIME, null, null, null, null, "dr-a").hashCode());
        assertNotNull(command.toString());
    }

    @Test
    void catalogHitIsAValueSnapshot() {
        CatalogHit hit = new CatalogHit("R51", "Headache", 3);
        assertEquals("R51", hit.code());
        assertEquals("Headache", hit.text());
        assertEquals(3, hit.version());
        assertEquals(hit, new CatalogHit("R51", "Headache", 3));
        assertEquals(hit.hashCode(), new CatalogHit("R51", "Headache", 3).hashCode());
        assertNotEquals(hit, new CatalogHit("R52", "Headache", 3));
        assertNotNull(hit.toString());
    }

    @Test
    void chartViewCopiesOnStoreAndRead() {
        List<ChartEntry> mutable = new java.util.ArrayList<>(List.of(entry()));
        ChartView view = new ChartView(mutable);
        mutable.clear();
        assertEquals(1, view.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
        ChartView direct = new ChartView(List.of(entry()));
        assertEquals(direct, new ChartView(List.of(entry())));
        assertEquals(direct.hashCode(), new ChartView(List.of(entry())).hashCode());
        assertTrue(direct.toString().contains("ChartEntry"));
        assertNotEquals(direct, new ChartView(List.of()));
        assertThrows(NullPointerException.class, () -> new ChartView(null));
    }

    private static ChartEntry entry() {
        return new ChartEntry("e-1", "p-1", "enc-1", EntryType.VITAL, "temperature", "TEMP",
                "local-vitals", "C", "Celsius", 2, EntrySource.DEVICE, PresenceState.PRESENT,
                "reason", VerificationState.VERIFIED, "dr-a", TIME, TIME, TIME, TIME, "37.1",
                "Celsius", "dr-a", "assistant-a", 2, TIME);
    }
}

/** Fast unit coverage for the CLN-03 chart role matrix. */
class ChartRecordActionPolicyTest {

    private final ChartActionPolicy policy = new ChartActionPolicy();

    @Test
    void clinicianMayReadCreateAndVerify() {
        assertTrue(policy.allows(ChartActionPolicy.CHART_READ, "clinician"));
        assertTrue(policy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, "clinician"));
        assertTrue(policy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, "clinician"));
    }

    @Test
    void assistantMayReadAndCreateButNotVerify() {
        assertTrue(policy.allows(ChartActionPolicy.CHART_READ, "clinical_assistant"));
        assertTrue(policy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, "clinical_assistant"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, "clinical_assistant"));
    }

    @Test
    void nonClinicalAndUnknownRolesAreDenied() {
        assertFalse(policy.allows(ChartActionPolicy.CHART_READ, "reception"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, "tenant_admin"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, "privacy_auditor"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_READ, "unknown_role"));
        assertFalse(policy.allows("UNKNOWN_ACTION", "clinician"));
    }

    @Test
    void nullActionAndRoleFailClosed() {
        assertFalse(policy.allows(null, "clinician"));
        assertFalse(policy.allows(ChartActionPolicy.CHART_READ, null));
        assertFalse(policy.allows(null, null));
    }
}
