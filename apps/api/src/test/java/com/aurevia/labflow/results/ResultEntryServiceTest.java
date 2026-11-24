package com.aurevia.labflow.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabResultService;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.tenant.TenantContext;

/** LAB-02 result entry: ranges, flags, segregation of duties, patient match and critical routing. */
class ResultEntryServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String COLLECTOR = "lab-collector-a";
    private static final String VERIFIER = "lab-verifier-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String SPECIMEN = "33333333-3333-3333-3333-333333333333";
    private static final String NUMBER = "ACC-1";
    private static final String ISSUER = "LAB-A";
    private static final String ORDER = "ORD/1";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final String PURPOSE = "LAB_RESULT_ENTRY";
    private static final String RESOURCE = "LAB_REPORT";
    private static final String SCOPE = "LAB";

    private LabAccessionRepository accessions;
    private CriticalResultService criticalResults;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private ResultEntryService service;

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        criticalResults = mock(CriticalResultService.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
        LabResultService reports = new LabResultService(
                new LabPolicyCatalog(List.of(), List.of()), clock);
        service = new ResultEntryService(accessions, reports, criticalResults, transactions, audit, clock);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, COLLECTOR, PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String role) {
        when(accessions.hasActiveMembership(anyString())).thenReturn(true);
        when(accessions.activeRoles(anyString())).thenReturn(List.of(role));
    }

    private static LabAccession specimen(AccessionState state, int version) {
        return new LabAccession(SPECIMEN, version, NUMBER, ISSUER, PATIENT, ORDER, state,
                "PLASMA", "SITE-A", "Sam Collector", "2026-11-21T09:00:00Z", null,
                "acceptable", null, null, null);
    }

    private static ObservationInput numeric(String code, String value, String unit,
                                            String low, String high) {
        return new ObservationInput(code, "numeric", "present", value, null, unit, null, null,
                "photometry", "analyser-1", low, high, null, null);
    }

    private static ObservationInput critical(String code, String value, String unit) {
        return new ObservationInput(code, "numeric", "present", value, null, unit, null, null,
                "photometry", "analyser-1", "3.5", "5.0", "6.0", "6.4");
    }

    private static ResultEntryRequest entry(List<ObservationInput> observations) {
        return new ResultEntryRequest(PATIENT, NUMBER, ISSUER, 1, "clinician-a", "critical-queue",
                "PT30M", "PHONE", observations);
    }

    private static ResultDecisionRequest decision(int version) {
        return new ResultDecisionRequest(PATIENT, NUMBER, ISSUER, version, "clinician-a",
                "critical-queue", "a documented correction");
    }

    @Test
    void enterComputesRangeAndCriticalFlagsForAllObservationKinds() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ObservationInput qualified = new ObservationInput("AST", "qualified_numeric", "present",
                "120", "lt", "U/L", null, null, "kinetic", "analyser-1", null, null, null, null);
        ObservationInput text = new ObservationInput("URINE", "text", "present", null, null, null,
                "clear", null, "visual", null, null, null, null, null);
        ObservationInput coded = new ObservationInput("HIV", "coded", "present", null, null, null,
                null, "NEGATIVE", "assay", null, null, null, null, null);
        ReportView view = service.enter(SPECIMEN, entry(List.of(
                numeric("GLU", "5.5", "mmol/L", "4.0", "6.0"),
                numeric("NA", "120", "mmol/L", "135", "145"),
                critical("K", "6.5", "mmol/L"), qualified, text, coded)));

        assertEquals(VerificationState.PRELIMINARY.wire(), view.state());
        assertEquals(1, view.version());
        assertEquals(PATIENT, view.patientId());
        assertEquals(SPECIMEN, view.specimenId());
        assertEquals(ORDER, view.orderId());
        assertEquals(COLLECTOR, view.actor());
        assertEquals(6, view.observations().size());
        assertEquals(AbnormalFlag.NONE.wire(), view.observations().get(0).abnormalFlag());
        assertEquals("5.5 mmol/L (none)", view.observations().get(0).display());
        assertEquals(AbnormalFlag.LOW.wire(), view.observations().get(1).abnormalFlag());
        assertEquals(AbnormalFlag.CRITICAL.wire(), view.observations().get(2).abnormalFlag());
        assertEquals(AbnormalFlag.NONE.wire(), view.observations().get(3).abnormalFlag());
        assertEquals("<120 U/L", view.observations().get(3).display());
        assertEquals("clear", view.observations().get(4).textValue());
        assertEquals("clear", view.observations().get(4).display());
        assertEquals("NEGATIVE", view.observations().get(5).codedValue());
        assertEquals("NEGATIVE", view.observations().get(5).display());
        verify(accessions).findById(SPECIMEN);
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));
    }

    @Test
    void enterRefusesWrongPatientWrongAccessionAndWrongIssuer() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ApiException wrongPatient = assertThrows(ApiException.class, () -> service.enter(SPECIMEN,
                new ResultEntryRequest(OTHER_PATIENT, NUMBER, ISSUER, 1, "clinician-a",
                        "critical-queue", null, "PHONE", List.of(numeric("GLU", "5", "mmol/L", null, null)))));
        ApiException wrongNumber = assertThrows(ApiException.class, () -> service.enter(SPECIMEN,
                new ResultEntryRequest(PATIENT, "ACC-9", ISSUER, 1, "clinician-a",
                        "critical-queue", null, "PHONE", List.of(numeric("GLU", "5", "mmol/L", null, null)))));
        ApiException wrongIssuer = assertThrows(ApiException.class, () -> service.enter(SPECIMEN,
                new ResultEntryRequest(PATIENT, NUMBER, "LAB-B", 1, "clinician-a",
                        "critical-queue", null, "PHONE", List.of(numeric("GLU", "5", "mmol/L", null, null)))));

        assertEquals(422, wrongPatient.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", wrongPatient.code());
        assertEquals("PATIENT_ACCESSION_MISMATCH", wrongNumber.code());
        assertEquals("PATIENT_ACCESSION_MISMATCH", wrongIssuer.code());
        verify(audit, times(3)).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("MISMATCH"));
    }

    @Test
    void enterDeniesInactiveMembershipAndWrongRole() {
        when(accessions.hasActiveMembership(COLLECTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(single()))).status());

        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(single()))).status());
        verify(audit, times(2)).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("DENIED"));
    }

    @Test
    void enterConcealsUnknownSpecimenAndRejectsStaleVersionAndIllegalState() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(single()))).status());
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("NOT_FOUND"));

        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 2)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(single()))).status());

        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.ACCESSIONED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(single()))).status());
    }

    @Test
    void enterRequiresObservationsAndRejectsMalformedInputs() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));

        assertEquals(422, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, new ResultEntryRequest(PATIENT, NUMBER, ISSUER, 1,
                        "clinician-a", "critical-queue", null, "PHONE", null))).status());
        assertEquals("INVALID_OBSERVATION_KIND", assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(List.of(new ObservationInput("GLU", "unknown_kind",
                        "present", "5", null, "mmol/L", null, null, null, null, null, null, null, null))))).code());
        assertEquals("INVALID_VALUE", assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(List.of(numeric("GLU", "not-a-number", "mmol/L",
                        null, null))))).code());
        assertEquals("INVALID_OBSERVATION_CODE", assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(List.of(numeric("!", "5", "mmol/L", null, null))))).code());
        assertEquals("INVALID_DUE_AFTER", assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, new ResultEntryRequest(PATIENT, NUMBER, ISSUER, 1,
                        "clinician-a", "critical-queue", "not-a-duration", "PHONE",
                        List.of(critical("K", "6.5", "mmol/L"))))).code());
        assertEquals("INVALID_OBSERVATION", assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(List.of(numeric("GLU", null, "mmol/L", null, null))))).code());
    }

    @Test
    void verifyByADifferentVerifierIssuesFinalAndStartsTheCriticalWorkflow() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(List.of(critical("K", "6.5", "mmol/L"))));
        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-2"));
        authorized("lab_verifier");

        ReportView view = service.verify(SPECIMEN, decision(1));

        assertEquals(VerificationState.FINAL.wire(), view.state());
        assertEquals(2, view.version());
        assertEquals(VERIFIER, view.verifier());
        assertEquals(AbnormalFlag.CRITICAL.wire(), view.observations().get(0).abnormalFlag());
        verify(criticalResults, times(1)).register(any());
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));

        service.correct(SPECIMEN, decision(2));
        // A correction of a critical report must not register a second alert.
        verify(criticalResults, times(1)).register(any());
    }

    @Test
    void verifyDoesNotStartTheCriticalWorkflowForANonCriticalReport() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5.5", "mmol/L", "4.0", "6.0"))));
        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-2"));
        authorized("lab_verifier");

        service.verify(SPECIMEN, decision(1));

        verify(criticalResults, never()).register(any());
    }

    @Test
    void verifyRefusesTheEntererAndAuditsTheConflict() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null))));
        authorized("lab_verifier");

        ApiException failure = assertThrows(ApiException.class, () -> service.verify(SPECIMEN, decision(1)));

        assertEquals(422, failure.status());
        assertEquals("SELF_VERIFICATION", failure.code());
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("CONFLICT"));
        verify(criticalResults, never()).register(any());
    }

    @Test
    void verifyRefusesWrongPatientUnknownSpecimenStaleVersionAndWrongState() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null))));
        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-2"));
        authorized("lab_verifier");

        ResultDecisionRequest wrongPatient = new ResultDecisionRequest(OTHER_PATIENT, NUMBER, ISSUER,
                1, "clinician-a", "critical-queue", null);
        assertEquals("PATIENT_ACCESSION_MISMATCH",
                assertThrows(ApiException.class, () -> service.verify(SPECIMEN, wrongPatient)).code());
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.verify(SPECIMEN, decision(2))).status());

        ReportView issued = service.verify(SPECIMEN, decision(1));
        assertEquals(VerificationState.FINAL.wire(), issued.state());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.verify(SPECIMEN, decision(2))).status());

        when(accessions.findById(SPECIMEN)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.verify(SPECIMEN, decision(1))).status());
    }

    @Test
    void verifyDeniesWrongRoleAndRequiresOwnerAndQueue() {
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.verify(SPECIMEN, decision(1))).status());

        authorized("lab_verifier");
        assertEquals("OWNER_REQUIRED", assertThrows(ApiException.class,
                () -> service.verify(SPECIMEN, new ResultDecisionRequest(PATIENT, NUMBER, ISSUER, 1,
                        null, "critical-queue", null))).code());
        assertEquals("QUEUE_REQUIRED", assertThrows(ApiException.class,
                () -> service.verify(SPECIMEN, new ResultDecisionRequest(PATIENT, NUMBER, ISSUER, 1,
                        "clinician-a", null, null))).code());
    }

    @Test
    void correctionAndEnteredInErrorAppendLinkedRevisions() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null))));
        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-2"));
        authorized("lab_verifier");
        service.verify(SPECIMEN, decision(1));

        ReportView corrected = service.correct(SPECIMEN, decision(2));
        assertEquals(VerificationState.CORRECTED.wire(), corrected.state());
        assertEquals(3, corrected.version());
        assertEquals(2, corrected.supersedesVersion());

        ReportView errored = service.enterInError(SPECIMEN, decision(3));
        assertEquals(VerificationState.ENTERED_IN_ERROR.wire(), errored.state());
        assertEquals(4, errored.version());
        assertEquals("a documented correction", errored.reason());
        assertThrows(ApiException.class, () -> service.enterInError(SPECIMEN, decision(4)));
    }

    @Test
    void correctionRequiresAReasonAndADifferentVerifier() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)),
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null))));
        authorized("lab_verifier");
        assertEquals("SELF_VERIFICATION", assertThrows(ApiException.class,
                () -> service.correct(SPECIMEN, decision(1))).code());

        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-2"));
        authorized("lab_verifier");
        service.verify(SPECIMEN, decision(1));
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> service.correct(SPECIMEN, new ResultDecisionRequest(PATIENT, NUMBER, ISSUER,
                        2, "clinician-a", "critical-queue", null))).code());
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> service.enterInError(SPECIMEN, new ResultDecisionRequest(PATIENT, NUMBER, ISSUER,
                        2, "clinician-a", "critical-queue", " "))).code());
    }

    @Test
    void readReturnsTheCurrentRevisionAndConcealsAnUnknownTenantsReport() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));
        service.enter(SPECIMEN, entry(single()));

        assertEquals(VerificationState.PRELIMINARY.wire(), service.read(SPECIMEN).state());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.read(SPECIMEN.replace('3', '4'))).status());
        verify(audit, times(1)).append(any(), eq(PURPOSE), eq(ResultEntryService.READ),
                eq(RESOURCE), eq(SCOPE), anyString(), anyString());
    }

    @Test
    void enterLeavesUnflagableObservationsAndPartialBoundsUnevaluated() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ObservationInput partial = new ObservationInput("NA", "numeric", "present", "120", null,
                "mmol/L", null, null, "photometry", "analyser-1", "135", null, null, null);
        ObservationInput qualified = new ObservationInput("AST", "qualified_numeric", "present",
                "120", "lt", "U/L", null, null, "kinetic", "analyser-1", "10", "40", "1", "5");
        ObservationInput unknown = new ObservationInput("UNK", "numeric", "unknown", null, null,
                "mmol/L", null, null, null, null, "4", "6", "3", "5");
        ReportView view = service.enter(SPECIMEN, entry(List.of(partial, qualified, unknown)));

        assertEquals(AbnormalFlag.NONE.wire(), view.observations().get(0).abnormalFlag());
        assertEquals(AbnormalFlag.NONE.wire(), view.observations().get(1).abnormalFlag());
        assertEquals("unknown", view.observations().get(2).presence());
        assertEquals(AbnormalFlag.NONE.wire(), view.observations().get(2).abnormalFlag());
    }

    @Test
    void enterOnlyFlagsCriticalWhenTheApprovedBoundIsMet() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ObservationInput inRange = new ObservationInput("K", "numeric", "present", "6.2", null,
                "mmol/L", null, null, "photometry", "analyser-1", "3.5", "7.0", "6.0", "6.4");
        ObservationInput partialCritical = new ObservationInput("NA", "numeric", "present", "150", null,
                "mmol/L", null, null, "photometry", "analyser-1", "135", "145", "180", null);
        ReportView view = service.enter(SPECIMEN, entry(List.of(inRange, partialCritical)));

        assertEquals(AbnormalFlag.NONE.wire(), view.observations().get(0).abnormalFlag());
        assertEquals(AbnormalFlag.HIGH.wire(), view.observations().get(1).abnormalFlag());
    }

    @Test
    void enterRejectsAPresentNumericObservationWithoutAValue() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ObservationInput missing = new ObservationInput("GLU", "numeric", "present", null, null,
                "mmol/L", null, null, null, null, "4", "6", "1", "3");
        assertEquals("INVALID_OBSERVATION", assertThrows(ApiException.class,
                () -> service.enter(SPECIMEN, entry(List.of(missing)))).code());
    }

    @Test
    void enterAcceptsABlankDueWindowByUsingTheApprovedDefault() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ReportView view = service.enter(SPECIMEN, new ResultEntryRequest(PATIENT, NUMBER, ISSUER, 1,
                "clinician-a", "critical-queue", "  ", "PHONE",
                List.of(critical("K", "6.5", "mmol/L"))));

        assertEquals(AbnormalFlag.CRITICAL.wire(), view.observations().get(0).abnormalFlag());
    }

    @Test
    void readDeniesTheWrongRoleAndAllowsAVerifier() {
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.read(SPECIMEN)).status());
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.READ),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("DENIED"));

        authorized("lab_verifier");
        assertEquals(404, assertThrows(ApiException.class, () -> service.read(SPECIMEN)).status());
    }

    private static List<ObservationInput> single() {
        return List.of(numeric("GLU", "5", "mmol/L", null, null));
    }
}
