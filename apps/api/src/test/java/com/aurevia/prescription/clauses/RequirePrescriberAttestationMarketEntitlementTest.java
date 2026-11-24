package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.aurevia.prescription.rx.RxActions;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxController;
import com.aurevia.prescription.rx.RxRepository;
import com.aurevia.prescription.rx.RxService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * RX-02 clause 1 API proof: a draft is attested only by its own prescribing clinician,
 * and only while an approved market policy and that prescriber's enabled market
 * entitlement are in force. Nothing else in RX-02 is under test here.
 *
 * <p>The real {@link com.aurevia.prescription.rx.RxController} and
 * {@link com.aurevia.prescription.rx.RxService} are driven through MockMvc so
 * the clause is proven at the HTTP entry point as well as in the domain. The repository is
 * a mock and the transaction template runs its callback inline: no database is created,
 * every case is deterministic and all fixtures are synthetic. Tenant identity is bound by
 * the interceptor from synthetic headers, never from a request body. Forced RLS itself is
 * proven on a real database by {@code RxTransmissionFlowTest}; the cross-tenant case here
 * only proves that the service conceals a row that is invisible in the active tenant.</p>
 */
class RequirePrescriberAttestationMarketEntitlementTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PRESCRIBER = "clinician-1111";
    private static final String OTHER_CLINICIAN = "other-1111";
    private static final String PHARMACIST = "pharmacy-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String NOTE = "synthetic attestation";

    private RxRepository repository;
    private AuditAppender audit;
    private MockMvc mvc;

    /**
     * Build the real controller and domain service over mocked persistence.
     */
    @BeforeEach
    void setUp() {
        repository = mock(RxRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        RxService service = new RxService(repository, new RxActions(), transactions, audit);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new RxController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();

        // Defaults describe an entitled prescriber owning a draft. Each test overrides
        // exactly the stub that puts the guard under test, so a false green is visible.
        when(repository.hasActiveMembership(PRESCRIBER)).thenReturn(true);
        when(repository.activeRoles(PRESCRIBER)).thenReturn(List.of("clinician"));
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.DRAFT, 1)));
        when(repository.find(ID)).thenReturn(Optional.of(prescription(State.ATTESTED, 2)));
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(repository.approvedSetting("rx02.entitlement." + PRESCRIBER)).thenReturn(Optional.of("enabled"));
    }

    /** Clear the request-scoped identity even when a denial short-circuits the interceptor. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void entitlesThePrescriberAndRecordsTheAttestation() throws Exception {
        attest(PRESCRIBER, ORG, "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("attested"))
                .andExpect(jsonPath("$.version").value(2));

        ArgumentCaptor<EventType> type = ArgumentCaptor.forClass(EventType.class);
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repository).attest(ID, 1);
        verify(repository).appendEvent(eq(ID), eq(2), type.capture(), isNull(), isNull(),
                outcome.capture(), detail.capture());
        assertEquals(EventType.ATTESTED, type.getValue());
        assertEquals("market=US", outcome.getValue());
        assertEquals(NOTE, detail.getValue());
        verify(audit).append(any(), eq(RxActions.ATTEST), eq(RxActions.ATTEST), any(), any(),
                eq(ID), eq("SUCCESS"));
    }

    @Test
    void refusesAttestationFromAnotherPrescriber() throws Exception {
        // Active clinician with the right role: the 403 can only come from ownership.
        when(repository.hasActiveMembership(OTHER_CLINICIAN)).thenReturn(true);
        when(repository.activeRoles(OTHER_CLINICIAN)).thenReturn(List.of("clinician"));

        attest(OTHER_CLINICIAN, ORG, "1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(repository).loadForUpdate(ID);
        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesAttestationByPharmacyStaff() throws Exception {
        // Active membership but no RX_ATTEST permission: the action gate denies before
        // any row is read, which distinguishes this branch from the ownership denial.
        when(repository.hasActiveMembership(PHARMACIST)).thenReturn(true);
        when(repository.activeRoles(PHARMACIST)).thenReturn(List.of("pharmacy_staff"));

        attest(PHARMACIST, ORG, "1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(repository, never()).loadForUpdate(anyString());
        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
        verify(audit).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
    }

    @Test
    void refusesAttestationWithoutActiveMembership() throws Exception {
        when(repository.hasActiveMembership(PRESCRIBER)).thenReturn(false);

        attest(PRESCRIBER, ORG, "1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(repository, never()).loadForUpdate(anyString());
        verify(repository, never()).attest(anyString(), anyInt());
        verify(audit).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
    }

    @Test
    void concealsAnInvisiblePrescriptionFromAnotherTenant() throws Exception {
        // Forced RLS makes the local read empty; with a mocked repository that is
        // modeled by an empty Optional for a row that exists only in the other tenant.
        when(repository.loadForUpdate(ID)).thenReturn(Optional.empty());

        attest(PRESCRIBER, OTHER_ORG, "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesStaleVersion() throws Exception {
        // The row is at version 1; a caller holding version 9 must not move it.
        attest(PRESCRIBER, ORG, "9")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesAMissingIfMatch() throws Exception {
        attest(PRESCRIBER, ORG, null)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        verifyNoInteractions(repository);
    }

    @Test
    void refusesAttestationOfAnAlreadyAttestedPrescription() throws Exception {
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.ATTESTED, 2)));

        attest(PRESCRIBER, ORG, "2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));

        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesAMalformedIfMatch() throws Exception {
        attest(PRESCRIBER, ORG, "abc")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IF_MATCH"));

        verifyNoInteractions(repository);
    }

    @Test
    void refusesWhenNoMarketPolicyApproved() throws Exception {
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.empty());

        attest(PRESCRIBER, ORG, "1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_MARKET_POLICY"));

        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesWhenNoEntitlementSetting() throws Exception {
        when(repository.approvedSetting("rx02.entitlement." + PRESCRIBER)).thenReturn(Optional.empty());

        attest(PRESCRIBER, ORG, "1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ENTITLEMENT"));

        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesWhenEntitlementIsDisabled() throws Exception {
        when(repository.approvedSetting("rx02.entitlement." + PRESCRIBER)).thenReturn(Optional.of("disabled"));

        attest(PRESCRIBER, ORG, "1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ENTITLEMENT"));

        verify(repository, never()).attest(anyString(), anyInt());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    /** Build the synthetic attest command, optionally without a version precondition. */
    private ResultActions attest(String subject, String organization, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/prescriptions/{id}/attest", ID)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + NOTE + "\"}");
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    /** Build a synthetically owned prescription in the requested state and version. */
    private static Prescription prescription(State state, int version) {
        return new Prescription(ID, version, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, PRESCRIBER, "pharmacy-x", state, null, null, false,
                Instant.parse("2026-11-20T10:00:00Z"));
    }
}
