package com.aurevia.smartclinic.exchange.clauses;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.order.OrderController;
import com.aurevia.smartclinic.exchange.order.OrderRepository;
import com.aurevia.smartclinic.exchange.order.OrderService;
import com.aurevia.smartclinic.exchange.order.WorkItemView;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * XCH-01 clause 5: sharing an order grants no general chart access.
 *
 * <p>The proof drives the real {@link OrderService} through the real HTTP adapter
 * and request interceptor with synthetic headers. The repository is mocked because
 * the tenant boundary lives in PostgreSQL FORCE RLS (migration V19) and is not
 * reproducible in a standalone MockMvc harness; an empty repository result models
 * the row concealment the policy performs. The DB-backed proof of that same clause,
 * including a live chart read attempt, belongs to the existing integration test
 * {@code OrderExchangeIntegrationTest.verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess}.</p>
 *
 * <p>Every request runs with an explicit subject and active organization, and every
 * negative case first satisfies the membership precondition so the refusal comes
 * from the specific rule under test rather than from an earlier guard.</p>
 */
class SharingOrderGrantsGeneralChartAccessTest {

    private static final String SENDER_ORG = "11111111-1111-1111-1111-111111111111";
    private static final String RECEIVER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SENDER_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SENDER_PATIENT_REF = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String LOCAL_PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String WORK_ITEM = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String FINGERPRINT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String RECEIVER_CLINICIAN = "receiver-clinician";
    private static final String RECEIVER_RECEPTION = "receiver-reception";
    private static final Instant TIME = Instant.parse("2026-11-22T10:15:30Z");

    private OrderRepository repository;
    private MockMvc mvc;

    /** Build the HTTP stack around a real domain service and an inline transaction. */
    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        AuditAppender audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        // The domain service opens a tenant-guarded transaction; run its callback
        // inline so the interceptor's TenantContext remains bound for the call.
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        OrderService service = new OrderService(repository, audit, transactions,
                Clock.fixed(TIME, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Never leak the request tenant to another test on the same thread. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant an active membership and the clinician role needed by every exchange action. */
    private void entitledClinician(String subject) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("clinician"));
    }

    /** A bounded destination projection: no sender chart identifier is present. */
    private static WorkItemView projectedItem() {
        return new WorkItemView(WORK_ITEM, SENDER_ORG, RECEIVER_ORG, SENDER_ORDER,
                SENDER_PATIENT_REF, "MRN-SENDER-1", "ISSUER-SENDER", null, false, "routine",
                "Synthetic clinical question", "lab-queue", FINGERPRINT,
                "awaiting_local_patient", TIME);
    }

    /**
     * The approved receiver sees only the bounded exchange projection. The exact
     * field set is asserted so no sender chart column can be added to the response
     * without failing this test; MRN and issuer are present solely for local
     * verification and are not a chart key.
     */
    @Test
    void receiverListsOnlyBoundedWorkItemProjection() throws Exception {
        entitledClinician(RECEIVER_CLINICIAN);
        when(repository.workItems()).thenReturn(List.of(projectedItem()));

        mvc.perform(get("/api/v1/exchange/work-items")
                        .header("X-Synthetic-Subject", RECEIVER_CLINICIAN)
                        .header("X-Active-Organization", RECEIVER_ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(aMapWithSize(15)))
                .andExpect(jsonPath("$[0].id").value(WORK_ITEM))
                .andExpect(jsonPath("$[0].senderOrg").value(SENDER_ORG))
                .andExpect(jsonPath("$[0].destinationOrg").value(RECEIVER_ORG))
                .andExpect(jsonPath("$[0].orderRef").value(SENDER_ORDER))
                .andExpect(jsonPath("$[0].patientRef").value(SENDER_PATIENT_REF))
                .andExpect(jsonPath("$[0].patientMrn").value("MRN-SENDER-1"))
                .andExpect(jsonPath("$[0].mrnIssuer").value("ISSUER-SENDER"))
                .andExpect(jsonPath("$[0].localPatientId").value(nullValue()))
                .andExpect(jsonPath("$[0].patientVerified").value(false))
                .andExpect(jsonPath("$[0].priority").value("routine"))
                .andExpect(jsonPath("$[0].clinicalQuestion").value("Synthetic clinical question"))
                .andExpect(jsonPath("$[0].workQueue").value("lab-queue"))
                .andExpect(jsonPath("$[0].fingerprint").value(FINGERPRINT))
                .andExpect(jsonPath("$[0].state").value("awaiting_local_patient"))
                .andExpect(jsonPath("$[0].createdAt").exists());
        verify(repository).workItems();
    }

    /**
     * Active membership alone is not enough: a receiver without the clinician role
     * is refused before any work item is read.
     */
    @Test
    void receiverWithoutClinicianRoleCannotListWorkItems() throws Exception {
        when(repository.hasActiveMembership(RECEIVER_RECEPTION)).thenReturn(true);
        when(repository.activeRoles(RECEIVER_RECEPTION)).thenReturn(List.of("reception"));

        mvc.perform(get("/api/v1/exchange/work-items")
                        .header("X-Synthetic-Subject", RECEIVER_RECEPTION)
                        .header("X-Active-Organization", RECEIVER_ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(repository, never()).workItems();
    }

    /**
     * A receiver clinician still cannot read the sender's order. The empty
     * repository result models V19's tenant_isolation policy under FORCE RLS on
     * exchange_order; concealment is reported as NOT_FOUND, not as a chart view.
     */
    @Test
    void sharingDoesNotExposeSenderOrderToReceiver() throws Exception {
        entitledClinician(RECEIVER_CLINICIAN);
        when(repository.find(SENDER_ORDER)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/orders/" + SENDER_ORDER)
                        .header("X-Synthetic-Subject", RECEIVER_CLINICIAN)
                        .header("X-Active-Organization", RECEIVER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        verify(repository).find(SENDER_ORDER);
    }

    /**
     * The opaque sender patient reference cannot be used as a chart key: binding a
     * local patient requires an issuer-scoped MRN match, and a mismatched MRN is
     * refused with IDENTITY_MISMATCH before any projection is mutated.
     */
    @Test
    void receiverCannotUseSenderPatientRefAsChartKey() throws Exception {
        entitledClinician(RECEIVER_CLINICIAN);
        when(repository.workItem(WORK_ITEM)).thenReturn(Optional.of(projectedItem()));
        when(repository.patientExists(LOCAL_PATIENT)).thenReturn(true);
        String body = "{\"localPatientId\":\"" + LOCAL_PATIENT + "\",\"localMrn\":\"MRN-MISMATCH\","
                + "\"mrnIssuer\":\"ISSUER-SENDER\",\"attestation\":\"site-verified MRN match\"}";

        mvc.perform(post("/api/v1/exchange/work-items/" + WORK_ITEM + "/verify-patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Synthetic-Subject", RECEIVER_CLINICIAN)
                        .header("X-Active-Organization", RECEIVER_ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_MISMATCH"));
        verify(repository, never()).verifyPatient(any(), any());
    }
}
