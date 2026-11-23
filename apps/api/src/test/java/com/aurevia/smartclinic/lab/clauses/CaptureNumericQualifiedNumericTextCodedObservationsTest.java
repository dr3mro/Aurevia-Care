package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.critical.CriticalResultService;
import com.aurevia.smartclinic.lab.accession.AccessionState;
import com.aurevia.smartclinic.lab.accession.LabAccession;
import com.aurevia.smartclinic.lab.accession.LabAccessionRepository;
import com.aurevia.smartclinic.lab.result.LabPolicyCatalog;
import com.aurevia.smartclinic.lab.result.LabResultService;
import com.aurevia.smartclinic.lab.result.LabTypes.VerificationState;
import com.aurevia.smartclinic.labflow.results.ObservationInput;
import com.aurevia.smartclinic.labflow.results.ReportView;
import com.aurevia.smartclinic.labflow.results.ResultDecisionRequest;
import com.aurevia.smartclinic.labflow.results.ResultEntryRequest;
import com.aurevia.smartclinic.labflow.results.ResultEntryService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * LAB-02 clause 1 (API test): a collector captures numeric, qualified numeric,
 * text and coded observations with their unit, approved range derivation, method,
 * instrument and source; a different verifier is recorded when the report is
 * issued, so provenance survives the transition from a preliminary revision.
 *
 * <p>Every case enters through {@link com.aurevia.smartclinic.labflow.results.ResultEntryService#enter},
 * the command invoked by {@code POST /api/v1/lab/results/{id}}, and the verifier
 * is proven through {@link com.aurevia.smartclinic.labflow.results.ResultEntryService#verify}.
 * The synthetic specimen is COLLECTED at version one so a refusal isolates the
 * clause rule rather than a state or concurrency precondition. Values use only
 * synthetic test identifiers; no real patient data or credentials are present.</p>
 */
class CaptureNumericQualifiedNumericTextCodedObservationsTest {

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
    private AuditAppender audit;
    private ResultEntryService service;

    /** Build the real entry service with mocked boundaries and a fixed clock. */
    @BeforeEach
    void setUp() {
        LabAccessionRepository accessionRepository = mock(LabAccessionRepository.class);
        CriticalResultService criticalService = mock(CriticalResultService.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        AuditAppender auditAppender = mock(AuditAppender.class);
        Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
        LabResultService reports = new LabResultService(
                new LabPolicyCatalog(List.of(), List.of()), clock);
        accessions = accessionRepository;
        criticalResults = criticalService;
        audit = auditAppender;
        service = new ResultEntryService(accessionRepository, reports, criticalService,
                transactions, auditAppender, clock);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, COLLECTOR, PURPOSE, "corr-lab-capture-1"));
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant the current caller active membership and one active role. */
    private void authorized(String role) {
        when(accessions.hasActiveMembership(anyString())).thenReturn(true);
        when(accessions.activeRoles(anyString())).thenReturn(List.of(role));
    }

    /** Synthetic collected accession at the version the caller read. */
    private static LabAccession specimen(AccessionState state, int version) {
        return new LabAccession(SPECIMEN, version, NUMBER, ISSUER, PATIENT, ORDER, state,
                "PLASMA", "SITE-A", "Sam Collector", "2026-11-21T09:00:00Z", null,
                "acceptable", null, null, null);
    }

    /** Exact numeric observation carrying an approved reference range. */
    private static ObservationInput numeric(String code, String value, String unit,
                                            String low, String high) {
        return new ObservationInput(code, "numeric", "present", value, null, unit, null, null,
                "photometry", "analyser-1", low, high, null, null);
    }

    /** Capture command for the synthetic matched specimen. */
    private static ResultEntryRequest entry(List<ObservationInput> observations) {
        return new ResultEntryRequest(PATIENT, NUMBER, ISSUER, 1, "clinician-a", "critical-queue",
                "PT30M", "PHONE", observations);
    }

    /** Verification command for the first preliminary revision. */
    private static ResultDecisionRequest decision(int version) {
        return new ResultDecisionRequest(PATIENT, NUMBER, ISSUER, version, "clinician-a",
                "critical-queue", null);
    }

    /** Assert that a refused command reports the exact machine-readable status and code. */
    private static void assertRefused(int status, String code, Runnable command) {
        ApiException failure = assertThrows(ApiException.class, command::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    /** Assert that no capture was recorded as a success. */
    private void assertNoSuccessfulCapture() {
        verify(audit, never()).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));
    }

    /** All four observation kinds keep their units, range flags, method, instrument and verifier. */
    @Test
    void capturesAllFourObservationKindsWithUnitsRangeMethodFlagsInstrumentAndVerifier() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)));

        ReportView report = service.enter(SPECIMEN, entry(List.of(
                numeric("GLU", "5.5", "mmol/L", "4.0", "6.0"),
                numeric("NA", "120", "mmol/L", "135", "145"),
                numeric("CA", "150", "mmol/L", "135", "145"),
                new ObservationInput("AST", "qualified_numeric", "present", "120", "lt", "U/L",
                        null, null, "kinetic", "analyser-1", null, null, null, null),
                new ObservationInput("URINE", "text", "present", null, null, null,
                        "clear", null, "visual", null, null, null, null, null),
                new ObservationInput("HIV", "coded", "present", null, null, null,
                        null, "NEGATIVE", "assay", null, null, null, null, null))));

        assertEquals(VerificationState.PRELIMINARY.wire(), report.state());
        assertEquals(COLLECTOR, report.actor());
        assertNull(report.verifier());
        assertEquals(6, report.observations().size());

        ReportView.ObservationView exact = report.observations().get(0);
        assertEquals("numeric", exact.kind());
        assertEquals("present", exact.presence());
        assertEquals("5.5", exact.value());
        assertEquals("mmol/L", exact.unit());
        assertEquals("", exact.qualifier());
        assertEquals("photometry", exact.method());
        assertEquals("analyser-1", exact.instrument());
        assertEquals("none", exact.abnormalFlag());
        assertEquals("5.5 mmol/L (none)", exact.display());
        assertNull(exact.textValue());
        assertNull(exact.codedValue());

        ReportView.ObservationView low = report.observations().get(1);
        assertEquals("numeric", low.kind());
        assertEquals("120", low.value());
        assertEquals("mmol/L", low.unit());
        assertEquals("low", low.abnormalFlag());
        assertEquals("120 mmol/L (low)", low.display());

        ReportView.ObservationView high = report.observations().get(2);
        assertEquals("numeric", high.kind());
        assertEquals("150", high.value());
        assertEquals("mmol/L", high.unit());
        assertEquals("high", high.abnormalFlag());
        assertEquals("150 mmol/L (high)", high.display());

        ReportView.ObservationView qualified = report.observations().get(3);
        assertEquals("qualified_numeric", qualified.kind());
        assertEquals("120", qualified.value());
        assertEquals("<", qualified.qualifier());
        assertEquals("U/L", qualified.unit());
        assertEquals("kinetic", qualified.method());
        assertEquals("analyser-1", qualified.instrument());
        assertEquals("none", qualified.abnormalFlag());
        assertEquals("<120 U/L", qualified.display());

        ReportView.ObservationView text = report.observations().get(4);
        assertEquals("text", text.kind());
        assertEquals("clear", text.textValue());
        assertEquals("clear", text.display());
        assertEquals("visual", text.method());
        assertEquals("none", text.abnormalFlag());
        assertNull(text.value());
        assertNull(text.unit());

        ReportView.ObservationView coded = report.observations().get(5);
        assertEquals("coded", coded.kind());
        assertEquals("NEGATIVE", coded.codedValue());
        assertEquals("NEGATIVE", coded.display());
        assertEquals("assay", coded.method());
        assertEquals("none", coded.abnormalFlag());
        assertNull(coded.value());

        TenantContext.set(new TenantContext(ORG, VERIFIER, PURPOSE, "corr-lab-capture-2"));
        authorized("lab_verifier");
        ReportView issued = service.verify(SPECIMEN, decision(1));

        assertEquals(VerificationState.FINAL.wire(), issued.state());
        assertEquals(VERIFIER, issued.verifier());
        assertEquals(6, issued.observations().size());
        assertEquals("5.5", issued.observations().get(0).value());
        assertEquals("low", issued.observations().get(1).abnormalFlag());
        assertEquals("high", issued.observations().get(2).abnormalFlag());
        assertEquals("<", issued.observations().get(3).qualifier());
        assertEquals("clear", issued.observations().get(4).textValue());
        assertEquals("NEGATIVE", issued.observations().get(5).codedValue());
        verify(criticalResults, never()).register(any());
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));
    }

    /** A caller without the collector role cannot capture any observation. */
    @Test
    void captureIsRefusedForWrongRole() {
        authorized("reception");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)));

        assertRefused(403, "FORBIDDEN",
                () -> service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null)))));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("DENIED"));
        verify(accessions, never()).findById(SPECIMEN);
        assertNoSuccessfulCapture();
    }

    /** Observations cannot be filed against another patient's specimen. */
    @Test
    void captureIsRefusedForMismatchedPatient() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)));
        ResultEntryRequest mismatched = new ResultEntryRequest(OTHER_PATIENT, NUMBER, ISSUER, 1,
                "clinician-a", "critical-queue", null, "PHONE",
                List.of(numeric("GLU", "5", "mmol/L", null, null)));

        assertRefused(422, "PATIENT_ACCESSION_MISMATCH",
                () -> service.enter(SPECIMEN, mismatched));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("MISMATCH"));
        assertNoSuccessfulCapture();
    }

    /** A version changed since the specimen was read is refused before any write. */
    @Test
    void captureIsRefusedForStaleVersion() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 2)));

        assertRefused(412, "STALE_VERSION",
                () -> service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null)))));

        assertNoSuccessfulCapture();
    }

    /** Only a collected specimen may receive observations. */
    @Test
    void captureIsRefusedForNonCollectedSpecimen() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.ACCESSIONED, 1)));

        assertRefused(409, "INVALID_STATE",
                () -> service.enter(SPECIMEN, entry(List.of(numeric("GLU", "5", "mmol/L", null, null)))));

        assertNoSuccessfulCapture();
    }

    /** Missing or malformed observations are refused at the boundary before a transaction opens. */
    @Test
    void captureIsRefusedForMissingOrMalformedObservation() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(
                Optional.of(specimen(AccessionState.COLLECTED, 1)));

        assertRefused(422, "NO_OBSERVATIONS", () -> service.enter(SPECIMEN, entry(List.of())));
        assertRefused(422, "INVALID_OBSERVATION_KIND", () -> service.enter(SPECIMEN, entry(List.of(
                new ObservationInput("GLU", "unknown_kind", "present", "5", null, "mmol/L",
                        null, null, null, null, null, null, null, null)))));
        assertRefused(422, "INVALID_VALUE", () -> service.enter(SPECIMEN, entry(List.of(
                numeric("GLU", "not-a-number", "mmol/L", null, null)))));
        assertRefused(422, "INVALID_OBSERVATION_CODE", () -> service.enter(SPECIMEN, entry(List.of(
                numeric("!", "5", "mmol/L", null, null)))));
        assertRefused(422, "INVALID_OBSERVATION", () -> service.enter(SPECIMEN, entry(List.of(
                numeric("GLU", null, "mmol/L", null, null)))));
        assertNoSuccessfulCapture();
    }
}
