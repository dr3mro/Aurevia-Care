package com.aurevia.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.result.LabPolicyCatalog;
import com.aurevia.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.lab.result.LabResultService;
import com.aurevia.lab.result.LabRules;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.labflow.results.LabResultEntryController;
import com.aurevia.labflow.results.ResultEntryService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * LAB-02 clause 3: HTTP proof that verification re-checks the specimen, the patient and
 * accession labels, and every stored observation before issuing a final report.
 *
 * <p>The entry point is the real {@link LabResultEntryController} driven through MockMvc
 * under the synthetic-auth profile. The report kernel is the real
 * {@link LabResultService} with an approved reference range, so the verified revision's
 * flags are genuinely re-derived; only the accession store, critical-result workflow,
 * audit appender and transaction boundary are mocked. All fixtures are synthetic.</p>
 */
class VerificationChecksSpecimenPatientObservationsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "44444444-4444-4444-4444-444444444444";
    private static final String COLLECTOR = "lab-collector-a";
    private static final String VERIFIER = "lab-verifier-a";
    private static final String BOTH = "lab-both-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String SPECIMEN = "33333333-3333-3333-3333-333333333333";
    private static final String NUMBER = "ACC-1";
    private static final String ISSUER = "LAB-A";
    private static final String ORDER = "ORD/1";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final String PURPOSE = ResultEntryService.PURPOSE;
    private static final String RESOURCE = "LAB_REPORT";
    private static final String SCOPE = "LAB";

    private LabAccessionRepository accessions;
    private CriticalResultService criticalResults;
    private AuditAppender audit;
    private LabResultService reports;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        criticalResults = mock(CriticalResultService.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());

        // Approved ranges turn captured NONE flags into LOW and HIGH at verification.
        // Without ranges evaluated at verification, the re-check would be invisible.
        ReferenceRange glucoseRange = new ReferenceRange("GLU", "mmol/L", new BigDecimal("4.0"),
                new BigDecimal("6.0"), 1, true, AT.minusSeconds(3600), null);
        ReferenceRange sodiumRange = new ReferenceRange("NA", "mmol/L", new BigDecimal("135"),
                new BigDecimal("145"), 1, true, AT.minusSeconds(3600), null);
        Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
        reports = new LabResultService(
                new LabPolicyCatalog(List.of(glucoseRange, sodiumRange), List.of()), clock);
        ResultEntryService service = new ResultEntryService(accessions, reports, criticalResults,
                transactions, audit, clock);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new LabResultEntryController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant a subject a membership and a fixed role set inside the active organization. */
    private void roles(String subject, String... values) {
        when(accessions.hasActiveMembership(subject)).thenReturn(true);
        when(accessions.activeRoles(subject)).thenReturn(List.of(values));
    }

    private void specimenIsFound() {
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen()));
    }

    private static LabAccession specimen() {
        return new LabAccession(SPECIMEN, 1, NUMBER, ISSUER, PATIENT, ORDER,
                AccessionState.COLLECTED, "PLASMA", "SITE-A", "Sam Collector",
                "2026-11-21T09:00:00Z", null, "acceptable", null, null, null);
    }

    /**
     * Capture two observations with no caller-supplied reference bounds, so both preliminary
     * flags are NONE and only the approved catalog can change them at verification.
     */
    private static String entryBody() {
        return "{\"patientId\":\"" + PATIENT + "\",\"accessionNo\":\"" + NUMBER + "\","
                + "\"accessionIssuer\":\"" + ISSUER + "\",\"expectedVersion\":1,"
                + "\"owner\":\"clinician-a\",\"queue\":\"critical-queue\",\"dueAfter\":\"PT30M\","
                + "\"escalation\":\"PHONE\",\"observations\":["
                + "{\"code\":\"GLU\",\"kind\":\"numeric\",\"presence\":\"present\","
                + "\"value\":\"3.5\",\"unit\":\"mmol/L\",\"method\":\"photometry\","
                + "\"instrument\":\"analyser-1\"},"
                + "{\"code\":\"NA\",\"kind\":\"numeric\",\"presence\":\"present\","
                + "\"value\":\"150\",\"unit\":\"mmol/L\",\"method\":\"photometry\","
                + "\"instrument\":\"analyser-1\"}]}";
    }

    private static String decision(int version) {
        return decision(PATIENT, NUMBER, ISSUER, version);
    }

    private static String decision(String patient, String number, String issuer, int version) {
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"" + issuer + "\",\"expectedVersion\":" + version + ","
                + "\"owner\":\"clinician-a\",\"queue\":\"critical-queue\","
                + "\"reason\":\"a documented correction\"}";
    }

    private ResultActions capture(String subject) throws Exception {
        return mvc.perform(post("/api/v1/lab/results/" + SPECIMEN)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG)
                .contentType(MediaType.APPLICATION_JSON)
                .content(entryBody()));
    }

    private ResultActions postVerify(String subject, String body, String organization) throws Exception {
        return mvc.perform(post("/api/v1/lab/results/" + SPECIMEN + "/verify")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void verificationChecksSpecimenPatientAndObservations() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        roles(VERIFIER, LabRules.VERIFIER);

        capture(COLLECTOR)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("preliminary"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.specimenId").value(SPECIMEN))
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.observations[0].abnormalFlag").value("none"))
                .andExpect(jsonPath("$.observations[1].abnormalFlag").value("none"));

        postVerify(VERIFIER, decision(1), ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("final"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.supersedesVersion").value(1))
                .andExpect(jsonPath("$.verifier").value(VERIFIER))
                .andExpect(jsonPath("$.actor").value(VERIFIER))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.specimenId").value(SPECIMEN))
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.observations[0].abnormalFlag").value("low"))
                .andExpect(jsonPath("$.observations[1].abnormalFlag").value("high"));

        // Every verified flag is re-derived, while the append-only preliminary revision is untouched.
        assertEquals(AbnormalFlag.NONE.wire(),
                reports.revisions(ORG, SPECIMEN).get(0).observations().get(0).abnormalFlag().wire());
        assertEquals(AbnormalFlag.NONE.wire(),
                reports.revisions(ORG, SPECIMEN).get(0).observations().get(1).abnormalFlag().wire());
        verify(criticalResults, never()).register(any());
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.ENTER),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));
        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("SUCCESS"));
    }

    @Test
    void wrongPatientAtVerificationIsRefused() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        roles(VERIFIER, LabRules.VERIFIER);
        capture(COLLECTOR).andExpect(status().isOk());

        postVerify(VERIFIER, decision(OTHER_PATIENT, NUMBER, ISSUER, 1), ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESSION_MISMATCH"));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("MISMATCH"));
    }

    @Test
    void wrongAccessionOrIssuerAtVerificationIsRefused() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        roles(VERIFIER, LabRules.VERIFIER);
        capture(COLLECTOR).andExpect(status().isOk());

        postVerify(VERIFIER, decision(PATIENT, "ACC-9", ISSUER, 1), ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESSION_MISMATCH"));
        postVerify(VERIFIER, decision(PATIENT, NUMBER, "LAB-B", 1), ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PATIENT_ACCESSION_MISMATCH"));

        verify(audit, times(2)).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("MISMATCH"));
    }

    @Test
    void unknownSpecimenAtVerificationIsConcealed() throws Exception {
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.empty());
        roles(VERIFIER, LabRules.VERIFIER);

        postVerify(VERIFIER, decision(1), ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("NOT_FOUND"));
    }

    @Test
    void collectorCannotVerifyReport() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        capture(COLLECTOR).andExpect(status().isOk());

        // The collector is a real member but lacks the verifier role, so authorization refuses it.
        postVerify(COLLECTOR, decision(1), ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("DENIED"));
    }

    @Test
    void entererCannotVerifyOwnReport() throws Exception {
        specimenIsFound();
        // One subject holding both roles reaches the segregation check; the actor is still the enterer.
        roles(BOTH, LabRules.COLLECTOR, LabRules.VERIFIER);
        capture(BOTH).andExpect(status().isOk());

        postVerify(BOTH, decision(1), ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SELF_VERIFICATION"));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("CONFLICT"));
    }

    @Test
    void staleVersionCannotVerifyReport() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        roles(VERIFIER, LabRules.VERIFIER);
        capture(COLLECTOR).andExpect(status().isOk());

        postVerify(VERIFIER, decision(2), ORG)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // The staleness throw happens inside the guarded callback, so no refusal row is written.
        verify(audit, never()).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), anyString());
    }

    @Test
    void alreadyFinalReportCannotBeVerifiedAgain() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        roles(VERIFIER, LabRules.VERIFIER);
        capture(COLLECTOR).andExpect(status().isOk());
        postVerify(VERIFIER, decision(1), ORG).andExpect(status().isOk());

        postVerify(VERIFIER, decision(2), ORG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        verify(audit).append(any(), eq(PURPOSE), eq(ResultEntryService.VERIFY),
                eq(RESOURCE), eq(SCOPE), eq(SPECIMEN), eq("CONFLICT"));
    }

    @Test
    void otherTenantCannotSeeTheReport() throws Exception {
        specimenIsFound();
        roles(COLLECTOR, LabRules.COLLECTOR);
        roles(VERIFIER, LabRules.VERIFIER);
        capture(COLLECTOR).andExpect(status().isOk());

        // A different active organization has no such report in its tenant-scoped ledger.
        mvc.perform(get("/api/v1/lab/results/" + SPECIMEN)
                        .header("X-Synthetic-Subject", VERIFIER)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
