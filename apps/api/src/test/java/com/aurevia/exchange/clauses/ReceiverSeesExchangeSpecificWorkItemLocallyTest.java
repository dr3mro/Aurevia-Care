package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.order.OrderController;
import com.aurevia.exchange.order.OrderRepository;
import com.aurevia.exchange.order.OrderService;
import com.aurevia.exchange.order.VerifyPatientRequest;
import com.aurevia.exchange.order.WorkItemView;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * XCH-01 clause 4 proof: the receiver sees an exchange-specific work item and
 * binds a locally verified patient without gaining general chart access.
 *
 * <p>The rules are exercised through {@link com.aurevia.exchange.order.OrderService#workItems()}
 * and {@link com.aurevia.exchange.order.OrderService#verifyPatient(String, com.aurevia.exchange.order.VerifyPatientRequest)},
 * with the HTTP surface added separately through {@link com.aurevia.exchange.order.OrderController}.
 * Every refusal is asserted on its distinct outcome and on the absence of a write;
 * the audit outcome distinguishes an allowed action from a denial. All identifiers,
 * MRNs and issuers are synthetic; no sender chart is reachable from the projection.</p>
 */
class ReceiverSeesExchangeSpecificWorkItemLocallyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String LOCAL_PATIENT = "44444444-4444-4444-4444-444444444444";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ORDER_REF = "66666666-6666-6666-6666-666666666666";
    private static final String WORK_ITEM = "77777777-7777-7777-7777-777777777777";
    private static final String ACTOR = "clinician-receiver";
    private static final String MRN = "MRN-1";
    private static final String ISSUER = "CLINIC";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;
    private MockMvc mvc;

    /** Build the domain service with mocked boundaries and a synthetic-auth HTTP adapter over it. */
    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        service = new OrderService(repository, audit, transactions(), Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        TenantContext.set(new TenantContext(DEST, ACTOR, "EXCHANGE", "corr-clause-four"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A transaction template that executes the tenant-guarded callback immediately. */
    private static TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Model an active member of the receiving organization holding exactly one role. */
    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** A bounded exchange projection; the local patient is absent until verification. */
    private static WorkItemView workItem(boolean verified) {
        return new WorkItemView(WORK_ITEM, ORG, DEST, ORDER_REF, PATIENT, MRN, ISSUER,
                verified ? LOCAL_PATIENT : null, verified, "routine", "Synthetic CBC question",
                "lab-queue", "fingerprint-1", verified ? "ready" : "awaiting_local_patient", NOW);
    }

    /** A receiver identity check with a site-verified issuer-scoped MRN. */
    private static VerifyPatientRequest verifyRequest() {
        return new VerifyPatientRequest(LOCAL_PATIENT, MRN, ISSUER, "site-verified MRN match");
    }

    private static ApiException denial(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void receiverListsOnlyItsOwnWorkItems() {
        authorized("clinician");
        WorkItemView projected = workItem(false);
        when(repository.workItems()).thenReturn(List.of(projected));

        List<WorkItemView> items = service.workItems();

        assertEquals(List.of(projected), items);
        assertEquals(DEST, items.get(0).destinationOrg());
        assertEquals(ORG, items.get(0).senderOrg());
        assertEquals(ORDER_REF, items.get(0).orderRef());
        assertEquals(PATIENT, items.get(0).patientRef());
        assertFalse(items.get(0).patientVerified());
        assertNull(items.get(0).localPatientId());
        verify(repository).workItems();
        verify(audit).append(any(), eq("EXCHANGE"), eq("WORK_ITEM_LIST"), eq("WORK_ITEM"),
                eq("EXCHANGE"), eq("ALL"), eq("SUCCESS"));
    }

    @Test
    void receiverBindsLocallyVerifiedPatientAndItemBecomesReady() {
        authorized("clinician");
        when(repository.workItem(WORK_ITEM)).thenReturn(
                Optional.of(workItem(false)), Optional.of(workItem(true)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, MRN, ISSUER)).thenReturn(true);
        when(repository.verifyPatient(WORK_ITEM, LOCAL_PATIENT)).thenReturn(1);

        WorkItemView verified = service.verifyPatient(WORK_ITEM, verifyRequest());

        assertTrue(verified.patientVerified());
        assertEquals(LOCAL_PATIENT, verified.localPatientId());
        assertEquals("ready", verified.state());
        verify(repository).verifyPatient(WORK_ITEM, LOCAL_PATIENT);
        verify(audit).append(any(), eq("EXCHANGE"), eq("WORK_ITEM_VERIFY"), eq("WORK_ITEM"),
                eq("EXCHANGE"), eq(WORK_ITEM), eq("SUCCESS"));
    }

    @Test
    void receptionRoleCannotListOrVerify() {
        authorized("reception");

        ApiException listDenial = denial(() -> service.workItems());
        ApiException verifyDenial = denial(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));

        assertEquals(403, listDenial.status());
        assertEquals("FORBIDDEN", listDenial.code());
        assertEquals(403, verifyDenial.status());
        assertEquals("FORBIDDEN", verifyDenial.code());
        verify(repository, never()).workItems();
        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(audit, times(2)).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("DENIED"));
    }

    @Test
    void otherTenantsWorkItemCannotBeVerified() {
        authorized("clinician");
        // A work item projected to another destination is concealed from this tenant.
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.empty());

        ApiException failure = denial(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).verifyPatient(anyString(), anyString());
    }

    @Test
    void mismatchedMrnOrIssuerCannotBind() {
        authorized("clinician");
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.of(workItem(false)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, MRN, ISSUER)).thenReturn(false);

        ApiException wrongMrn = denial(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, "MRN-2", ISSUER, "attested")));
        ApiException wrongIssuer = denial(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, MRN, "OTHER", "attested")));
        ApiException unmatchedLocalPatient = denial(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));

        assertEquals(403, wrongMrn.status());
        assertEquals("IDENTITY_MISMATCH", wrongMrn.code());
        assertEquals(403, wrongIssuer.status());
        assertEquals("IDENTITY_MISMATCH", wrongIssuer.code());
        assertEquals(403, unmatchedLocalPatient.status());
        assertEquals("IDENTITY_MISMATCH", unmatchedLocalPatient.code());
        verify(repository, never()).verifyPatient(anyString(), anyString());
    }

    @Test
    void repeatVerificationIsRejected() {
        authorized("clinician");
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.of(workItem(false)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, MRN, ISSUER)).thenReturn(true);
        when(repository.verifyPatient(WORK_ITEM, LOCAL_PATIENT)).thenReturn(0);

        ApiException failure = denial(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));

        assertEquals(409, failure.status());
        assertEquals("ALREADY_VERIFIED", failure.code());
    }

    @Test
    void blankLocalPatientFieldsAreRejected() {
        authorized("clinician");

        ApiException blankPatient = denial(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(" ", MRN, ISSUER, "attested")));
        ApiException blankMrn = denial(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, " ", ISSUER, "attested")));
        ApiException blankIssuer = denial(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, MRN, " ", "attested")));
        ApiException blankAttestation = denial(() -> service.verifyPatient(WORK_ITEM,
                new VerifyPatientRequest(LOCAL_PATIENT, MRN, ISSUER, " ")));
        for (ApiException invalid : List.of(blankPatient, blankMrn, blankIssuer, blankAttestation)) {
            assertEquals(422, invalid.status());
            assertEquals("INVALID_INPUT", invalid.code());
        }
        verify(repository, never()).workItem(anyString());
        verify(repository, never()).verifyPatient(anyString(), anyString());
    }

    @Test
    void inactiveMembershipCannotListOrVerify() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException listDenial = denial(() -> service.workItems());
        ApiException verifyDenial = denial(() -> service.verifyPatient(WORK_ITEM, verifyRequest()));

        assertEquals(403, listDenial.status());
        assertEquals(403, verifyDenial.status());
        assertEquals("FORBIDDEN", listDenial.code());
        verify(repository, never()).activeRoles(anyString());
        verify(repository, never()).workItems();
        verify(repository, never()).verifyPatient(anyString(), anyString());
        verify(audit, times(2)).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("DENIED"));
    }

    @Test
    void httpWorkItemRoutesReachTheReceiverProjection() throws Exception {
        authorized("clinician");
        when(repository.workItems()).thenReturn(List.of(workItem(false)));
        when(repository.workItem(WORK_ITEM)).thenReturn(
                Optional.of(workItem(false)), Optional.of(workItem(true)));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        when(repository.localPatientMatches(LOCAL_PATIENT, MRN, ISSUER)).thenReturn(true);
        when(repository.verifyPatient(WORK_ITEM, LOCAL_PATIENT)).thenReturn(1);

        mvc.perform(get("/api/v1/exchange/work-items")
                        .header("X-Synthetic-Subject", ACTOR)
                        .header("X-Active-Organization", DEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(WORK_ITEM))
                .andExpect(jsonPath("$[0].destinationOrg").value(DEST))
                .andExpect(jsonPath("$[0].patientVerified").value(false));
        mvc.perform(post("/api/v1/exchange/work-items/" + WORK_ITEM + "/verify-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"localPatientId\":\"" + LOCAL_PATIENT + "\",\"localMrn\":\""
                                + MRN + "\",\"mrnIssuer\":\"" + ISSUER
                                + "\",\"attestation\":\"site-verified MRN match\"}")
                        .header("X-Synthetic-Subject", ACTOR)
                        .header("X-Active-Organization", DEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.patientVerified").value(true));
        mvc.perform(get("/api/v1/exchange/work-items")
                        .header("X-Active-Organization", DEST))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        verify(repository).workItems();
        verify(repository).verifyPatient(WORK_ITEM, LOCAL_PATIENT);
    }
}
