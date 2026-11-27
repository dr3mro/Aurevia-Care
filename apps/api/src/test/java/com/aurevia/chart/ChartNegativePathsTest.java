package com.aurevia.chart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.audit.AuditAppender;
import com.aurevia.chart.record.ChartActionPolicy;
import com.aurevia.chart.record.ChartController;
import com.aurevia.chart.record.ChartEntry;
import com.aurevia.chart.record.ChartRepository;
import com.aurevia.chart.record.ChartService;
import com.aurevia.chart.record.EntrySource;
import com.aurevia.chart.record.EntryType;
import com.aurevia.chart.record.PresenceState;
import com.aurevia.chart.record.VerificationState;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * Negative paths and tenant isolation at the chart HTTP entry point (CLN-03, ARC-02, ARC-03, ARC-04).
 *
 * <p>Every case drives the real {@link com.aurevia.chart.record.ChartController}, the real
 * {@link com.aurevia.chart.record.ChartService} and the real role matrix through the Spring MVC
 * pipeline, with a tenant-scoped repository fixture. The assertions are on the HTTP status and
 * the machine-readable error code or body, so a behavior that exists only in a domain kernel
 * cannot pass. The repository is a mock because forced row-level security is proven by the
 * database integration suite; here {@code Optional.empty()} and an empty list model the fold a
 * peer tenant sees. All identifiers, subjects, organizations and text are synthetic.</p>
 *
 * <p>The scope of this class is test-only: no production code, contract or migration is changed.</p>
 */
class ChartNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNKNOWN_PATIENT = "99999999-9999-9999-9999-999999999999";
    private static final String ENTRY_ID = "33333333-3333-3333-3333-333333333333";
    private static final String CLINICIAN_A = "clinician-a";
    private static final String CLINICIAN_B = "clinician-b";
    private static final String ASSISTANT_A = "assistant-a";
    private static final String RECEPTION_A = "reception-a";
    private static final String ADMIN_A = "admin-a";
    private static final String AUDITOR_A = "auditor-a";
    private static final String INACTIVE_A = "deactivated-a";
    private static final String FOREIGN_TEXT = "peer-tenant-chart-secret";
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private ChartRepository entries;
    private AuditAppender audit;
    private MockMvc mvc;

    /** Wire the real controller and domain service behind the tenant-aware MVC pipeline. */
    @BeforeEach
    void setUp() {
        entries = mock(ChartRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        ChartService service = new ChartService(entries, new ChartActionPolicy(), transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new ChartController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Release the thread-bound context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant an active membership and a role set to one synthetic subject. */
    private void active(String subject, String... roles) {
        when(entries.hasActiveMembership(subject)).thenReturn(true);
        when(entries.activeRoles(subject)).thenReturn(List.of(roles));
    }

    /** Build a deterministic synthetic chart entry for a given assertion. */
    private static ChartEntry entry(String text, VerificationState state, int version) {
        return new ChartEntry(ENTRY_ID, PATIENT_A, null, EntryType.PROBLEM, text, "R51", "diagnosis",
                null, null, null, EntrySource.PATIENT_REPORTED, PresenceState.PRESENT, null,
                state, null, null, null, null, null, null, null, CLINICIAN_A, CLINICIAN_A, version, NOW);
    }

    /** Post one command body as the given synthetic subject and organization. */
    private ResultActions recordEntry(String body, String subject, String organization) throws Exception {
        return recordEntry(body, subject, organization, null);
    }

    /** Post one command body with an optional idempotency header. */
    private ResultActions recordEntry(String body, String subject, String organization, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/chart/records/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    /** Verify one entry with an optional If-Match precondition. */
    private ResultActions verifyEntry(String id, String version, String subject, String organization)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/chart/records/entries/{id}/verify", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject);
        if (version != null) {
            request.header("If-Match", "\"" + version + "\"");
        }
        return mvc.perform(request);
    }

    /** Read one entry as the given synthetic subject and organization. */
    private ResultActions readEntry(String id, String subject, String organization) throws Exception {
        return mvc.perform(get("/api/v1/chart/records/entries/{id}", id)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject));
    }

    /** List a patient's chart as the given synthetic subject and organization. */
    private ResultActions listEntries(String patientId, String subject, String organization) throws Exception {
        return mvc.perform(get("/api/v1/chart/records/patients/{patientId}/entries", patientId)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-" + subject));
    }

    /** Missing, blank, oversized and unreadable input fails before any repository contact. */
    @Test
    void missingAndMalformedInputIsRejectedWithMachineCodes() throws Exception {
        recordEntry("{}", CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
        recordEntry(new CommandBody().patient("not-a-uuid").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PATIENT_ID"));
        recordEntry(new CommandBody().type(null).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TYPE"));
        recordEntry(new CommandBody().text(null).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEXT"));
        recordEntry(new CommandBody().text("  ").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEXT"));
        recordEntry(new CommandBody().text("x".repeat(2001)).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TEXT"));
        recordEntry(new CommandBody().source(null).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCE"));
        recordEntry(new CommandBody().presence(null).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_PRESENCE"));
        recordEntry("{not json", CLINICIAN_A, ORG_A)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));

        verify(entries, never()).hasActiveMembership(anyString());
        verify(entries, never()).insert(any());
    }

    /** Optional trust-boundary text is bounded before authorization or persistence. */
    @Test
    void oversizedOptionalTextIsBoundedBeforeAuthorization() throws Exception {
        recordEntry(new CommandBody().extra("\"encounterId\":\"" + "e".repeat(121) + "\"").json(),
                CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ENCOUNTER_ID"));
        recordEntry(new CommandBody().extra("\"originalCode\":\"" + "c".repeat(121) + "\"").json(),
                CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        recordEntry(new CommandBody().extra("\"originalCode\":\"R51\",\"codeSystem\":\""
                + "s".repeat(121) + "\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        recordEntry(new CommandBody().presence("UNKNOWN")
                .extra("\"unknownReason\":\"" + "r".repeat(501) + "\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNKNOWN_REASON"));
        recordEntry(new CommandBody().type("VITAL")
                .extra("\"value\":\"" + "v".repeat(501) + "\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        recordEntry(new CommandBody().type("VITAL")
                .extra("\"unit\":\"" + "u".repeat(121) + "\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNIT"));
        recordEntry(new CommandBody().author("a".repeat(121)).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CLINICAL_AUTHOR"));

        verify(entries, never()).insert(any());
    }

    /** An unknown assertion, a coded pair and a dated assertion are validated before any write. */
    @Test
    void unknownAssertionCodePairingAndPeriodAreValidated() throws Exception {
        recordEntry(new CommandBody().presence("UNKNOWN").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNKNOWN_REASON"));
        recordEntry(new CommandBody().presence("UNKNOWN")
                .extra("\"unknownReason\":\" \"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNKNOWN_REASON"));
        recordEntry(new CommandBody().extra("\"unknownReason\":\"not-asked\"").json(),
                CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNKNOWN_REASON"));
        recordEntry(new CommandBody().extra("\"originalCode\":\"R51\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        recordEntry(new CommandBody().extra("\"codeSystem\":\"diagnosis\"").json(),
                CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CODE"));
        recordEntry(new CommandBody().extra("\"effectiveFrom\":\"2026-12-02T00:00:00Z\","
                + "\"effectiveTo\":\"2026-12-01T00:00:00Z\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_EFFECTIVE_PERIOD"));

        verify(entries, never()).insert(any());
    }

    /** A vital needs its measurement triple and clinical authorship cannot be forged. */
    @Test
    void incompleteVitalAndForeignClinicalAuthorAreRejected() throws Exception {
        String measured = "\"measuredAt\":\"2026-11-27T00:00:00Z\"";
        recordEntry(new CommandBody().type("VITAL")
                .extra("\"unit\":\"Celsius\"," + measured).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VITAL"));
        recordEntry(new CommandBody().type("VITAL")
                .extra("\"value\":\"37.1\"," + measured).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VITAL"));
        recordEntry(new CommandBody().type("VITAL")
                .extra("\"value\":\"37.1\",\"unit\":\"Celsius\"").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VITAL"));
        recordEntry(new CommandBody().type("VITAL")
                .extra("\"value\":\" \",\"unit\":\"Celsius\"," + measured).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VITAL"));
        recordEntry(new CommandBody().author(" ").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CLINICAL_AUTHOR"));
        recordEntry(new CommandBody().author("another-clinician").json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CLINICAL_AUTHOR"));

        verify(entries, never()).insert(any());
    }

    /** A caller without a permitted chart role is denied on record, verify and both reads. */
    @Test
    void wrongRoleIsForbiddenOnEveryChartRoute() throws Exception {
        for (String subject : List.of(RECEPTION_A, ADMIN_A, AUDITOR_A)) {
            active(subject, switch (subject) {
                case RECEPTION_A -> "reception";
                case ADMIN_A -> "tenant_admin";
                default -> "privacy_auditor";
            });
            // validate() runs before authorize(), so the body must be otherwise valid and must
            // attribute authorship to the caller itself rather than to a clinician.
            recordEntry(new CommandBody().author(subject).json(), subject, ORG_A)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            verifyEntry(ENTRY_ID, "1", subject, ORG_A)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            readEntry(ENTRY_ID, subject, ORG_A)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            listEntries(PATIENT_A, subject, ORG_A)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        // A clinical assistant may record and read but may not verify.
        active(ASSISTANT_A, "clinical_assistant");
        verifyEntry(ENTRY_ID, "1", ASSISTANT_A, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(audit, atLeastOnce()).append(any(), eq("CHART_RECORD"), eq("CHART_RECORD"),
                eq("CHART_ENTRY"), eq("CHART"), anyString(), eq("DENIED"));
        verify(audit, atLeastOnce()).append(any(), eq("CHART_VERIFY"), eq("CHART_VERIFY"),
                eq("CHART_ENTRY"), eq("CHART"), anyString(), eq("DENIED"));
        verify(audit, atLeastOnce()).append(any(), eq("CHART_READ"), eq("CHART_READ"),
                eq("CHART_ENTRY"), eq("CHART"), anyString(), eq("DENIED"));
        verify(entries, never()).find(anyString());
        verify(entries, never()).chart(anyString());
    }

    /** A subject without an active membership fails closed on every protected route. */
    @Test
    void inactiveMembershipFailsClosedOnEveryRoute() throws Exception {
        // No membership fold is stubbed for this subject, so the repository reports inactive.
        recordEntry(new CommandBody().author(INACTIVE_A).json(), INACTIVE_A, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyEntry(ENTRY_ID, "1", INACTIVE_A, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        readEntry(ENTRY_ID, INACTIVE_A, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        listEntries(PATIENT_A, INACTIVE_A, ORG_A)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(entries, never()).find(anyString());
        verify(entries, never()).chart(anyString());
        verify(audit, atLeastOnce()).append(any(), anyString(), anyString(), eq("CHART_ENTRY"),
                eq("CHART"), anyString(), eq("DENIED"));
    }

    /** A patient outside the active tenant fold is concealed as a generic not-found. */
    @Test
    void unknownPatientIsConcealedAsNotFound() throws Exception {
        active(CLINICIAN_A, "clinician");
        when(entries.patientExists(UNKNOWN_PATIENT)).thenReturn(false);

        String body = recordEntry(new CommandBody().patient(UNKNOWN_PATIENT).json(), CLINICIAN_A, ORG_A)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("patient not found"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(PATIENT_A));
        verify(audit).append(any(), eq("CHART_RECORD"), eq("CHART_RECORD"), eq("CHART_ENTRY"),
                eq("CHART"), eq(UNKNOWN_PATIENT), eq("NOT_FOUND"));
        verify(entries, never()).insert(any());
    }

    /** A stale read version and a lost compare-and-set both yield 412 STALE_VERSION. */
    @Test
    void staleVersionAndFailedCompareAndSetAreRejected() throws Exception {
        active(CLINICIAN_A, "clinician");
        when(entries.find(ENTRY_ID)).thenReturn(Optional.of(entry(FOREIGN_TEXT,
                VerificationState.UNVERIFIED, 1)));
        when(entries.verify(eq(ENTRY_ID), eq(1), eq(CLINICIAN_A), any(Instant.class))).thenReturn(false);

        verifyEntry(ENTRY_ID, "9", CLINICIAN_A, ORG_A)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        verifyEntry(ENTRY_ID, "1", CLINICIAN_A, ORG_A)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        verify(entries).verify(eq(ENTRY_ID), eq(1), eq(CLINICIAN_A), any(Instant.class));
        verify(audit, times(2)).append(any(), eq("CHART_VERIFY"), eq("CHART_VERIFY"),
                eq("CHART_ENTRY"), eq("CHART"), eq(ENTRY_ID), eq("CONFLICT"));
    }

    /** An already verified entry is a 409, but a stale precondition still wins as a 412. */
    @Test
    void alreadyVerifiedAndStaleVerifiedEntryKeepPrecedence() throws Exception {
        active(CLINICIAN_A, "clinician");
        when(entries.find(ENTRY_ID)).thenReturn(Optional.of(entry(FOREIGN_TEXT,
                VerificationState.VERIFIED, 2)));

        verifyEntry(ENTRY_ID, "2", CLINICIAN_A, ORG_A)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VERIFIED"));
        verifyEntry(ENTRY_ID, "1", CLINICIAN_A, ORG_A)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        verify(entries, never()).verify(anyString(), anyInt(), anyString(), any(Instant.class));
        verify(audit, times(2)).append(any(), eq("CHART_VERIFY"), eq("CHART_VERIFY"),
                eq("CHART_ENTRY"), eq("CHART"), eq(ENTRY_ID), eq("CONFLICT"));
    }

    /** Missing entries, malformed identifiers and missing or invalid preconditions fail uniformly. */
    @Test
    void missingEntryAndPreconditionFailuresAreRejected() throws Exception {
        active(CLINICIAN_A, "clinician");
        when(entries.find(ENTRY_ID)).thenReturn(Optional.empty());

        readEntry(ENTRY_ID, CLINICIAN_A, ORG_A)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyEntry(ENTRY_ID, "1", CLINICIAN_A, ORG_A)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verifyEntry(ENTRY_ID, null, CLINICIAN_A, ORG_A)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        verifyEntry(ENTRY_ID, "abc", CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        verifyEntry(ENTRY_ID, "0", CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));
        readEntry("not-a-uuid", CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ENTRY_ID"));
        verifyEntry("not-a-uuid", "1", CLINICIAN_A, ORG_A)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ENTRY_ID"));

        verify(entries, never()).verify(anyString(), anyInt(), anyString(), any(Instant.class));
        verify(audit, times(2)).append(any(), anyString(), anyString(), eq("CHART_ENTRY"),
                eq("CHART"), eq(ENTRY_ID), eq("NOT_FOUND"));
    }

    /**
     * A peer tenant receives not-found or an empty chart, never the owner's record.
     *
     * <p>The repository answers are keyed on the context bound by the interceptor: the owner
     * sees the synthetic row, while the peer gets the empty fold forced RLS produces for the
     * same identifiers. The peer's error body carries neither the clinical text nor the
     * owning organization.</p>
     */
    @Test
    void foreignTenantIdentifierIsConcealedAsNotFound() throws Exception {
        active(CLINICIAN_A, "clinician");
        active(CLINICIAN_B, "clinician");
        ChartEntry owned = entry(FOREIGN_TEXT, VerificationState.UNVERIFIED, 1);
        when(entries.find(ENTRY_ID)).thenAnswer(invocation ->
                ORG_A.equals(TenantContext.current().organizationId())
                        ? Optional.of(owned) : Optional.empty());
        when(entries.chart(PATIENT_A)).thenAnswer(invocation ->
                ORG_A.equals(TenantContext.current().organizationId())
                        ? List.of(owned) : List.of());

        String ownerBody = readEntry(ENTRY_ID, CLINICIAN_A, ORG_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalText").value(FOREIGN_TEXT))
                .andReturn().getResponse().getContentAsString();
        assertTrue(ownerBody.contains(FOREIGN_TEXT));

        String peerGetBody = readEntry(ENTRY_ID, CLINICIAN_B, ORG_B)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(peerGetBody.contains(FOREIGN_TEXT));
        assertFalse(peerGetBody.contains(ORG_A));

        String peerVerifyBody = verifyEntry(ENTRY_ID, "1", CLINICIAN_B, ORG_B)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(peerVerifyBody.contains(FOREIGN_TEXT));
        assertFalse(peerVerifyBody.contains(ORG_A));

        listEntries(PATIENT_A, CLINICIAN_B, ORG_B)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(0))
                .andExpect(jsonPath("$.entries[?(@.originalText == '" + FOREIGN_TEXT + "')]").doesNotExist());
        listEntries(PATIENT_A, CLINICIAN_A, ORG_A)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].originalText").value(FOREIGN_TEXT));

        verify(audit, atLeastOnce()).append(any(), eq("CHART_READ"), eq("CHART_READ"),
                eq("CHART_ENTRY"), eq("CHART"), eq(ENTRY_ID), eq("NOT_FOUND"));
        verify(audit, atLeastOnce()).append(any(), eq("CHART_VERIFY"), eq("CHART_VERIFY"),
                eq("CHART_ENTRY"), eq("CHART"), eq(ENTRY_ID), eq("NOT_FOUND"));
    }

    /**
     * Two identical keyed POSTs append two entries in the standalone pipeline.
     *
     * <p>This records the behavior the chart route has today: the standalone MVC pipeline used
     * here has no {@code IdempotencyFilter}, and the controller reads no key header. A retry
     * therefore allocates a new identifier and inserts a second row. A replay ledger with
     * tenant, caller and command scoping is a separate contract change and is not asserted here.</p>
     */
    @Test
    void repeatedKeyedRequestsAppendTwoEntriesInStandalonePipeline() throws Exception {
        active(CLINICIAN_A, "clinician");
        when(entries.patientExists(PATIENT_A)).thenReturn(true);

        String command = new CommandBody().json();
        String first = recordEntry(command, CLINICIAN_A, ORG_A, "chart-retry-key")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String second = recordEntry(command, CLINICIAN_A, ORG_A, "chart-retry-key")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();

        assertNotEquals(first, second);
        String firstId = mapper.readTree(first).get("id").asText();
        String secondId = mapper.readTree(second).get("id").asText();
        assertNotEquals(firstId, secondId);
        verify(entries, times(2)).insert(any());
        verify(audit, times(2)).append(any(), eq("CHART_RECORD"), eq("CHART_RECORD"),
                eq("CHART_ENTRY"), eq("CHART"), anyString(), eq("SUCCESS"));
    }

    /** Minimal JSON command builder: each case can corrupt one field without disturbing the rest. */
    private static final class CommandBody {
        private String patientId = PATIENT_A;
        private String type = "PROBLEM";
        private String text = "headache";
        private String source = "CLINICIAN_OBSERVED";
        private String presence = "PRESENT";
        private String author = CLINICIAN_A;
        private String extra = "";

        CommandBody patient(String value) {
            this.patientId = value;
            return this;
        }

        CommandBody type(String value) {
            this.type = value;
            return this;
        }

        CommandBody text(String value) {
            this.text = value;
            return this;
        }

        CommandBody source(String value) {
            this.source = value;
            return this;
        }

        CommandBody presence(String value) {
            this.presence = value;
            return this;
        }

        CommandBody author(String value) {
            this.author = value;
            return this;
        }

        CommandBody extra(String fragment) {
            this.extra = "," + fragment;
            return this;
        }

        String json() {
            return "{\"patientId\":" + quoted(patientId)
                    + ",\"type\":" + quoted(type)
                    + ",\"originalText\":" + quoted(text)
                    + ",\"source\":" + quoted(source)
                    + ",\"presence\":" + quoted(presence)
                    + ",\"clinicalAuthor\":" + quoted(author)
                    + extra + "}";
        }

        private static String quoted(String value) {
            return value == null ? "null" : "\"" + value + "\"";
        }
    }
}
