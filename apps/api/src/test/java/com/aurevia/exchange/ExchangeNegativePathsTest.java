package com.aurevia.exchange;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.order.OrderController;
import com.aurevia.exchange.order.OrderRepository;
import com.aurevia.exchange.order.OrderService;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.exchange.result.ResultController;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;
import com.aurevia.exchange.task.ClinicalReviewState;
import com.aurevia.exchange.task.ExchangeActionPolicy;
import com.aurevia.exchange.task.ExchangeTask;
import com.aurevia.exchange.task.ExchangeTaskController;
import com.aurevia.exchange.task.ExchangeTaskRepository;
import com.aurevia.exchange.task.ExchangeTaskService;
import com.aurevia.exchange.task.TransmissionState;
import com.aurevia.exchange.task.WorkflowState;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * Executable refusal contract for the exchange HTTP surface (XCH-01, ARC-02).
 *
 * <p>Each case drives a real controller and real domain service with mocked
 * repositories. The service is not replaced by a stub: that is what lets a test
 * assert the audit outcome and the absence of a repository write together with
 * the status and machine-readable code the caller sees. The repository answers
 * are keyed on the active organization, so a foreign identifier is modelled as
 * an empty result exactly as forced row-level security presents it. No test
 * opens a database, and all identifiers and payload markers are synthetic.</p>
 */
class ExchangeNegativePathsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String ORDER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String TASK_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String FINGERPRINT = "owner-snapshot-fingerprint";
    private static final String REPORT_ID = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
    private static final String ORDER_REF = "order-1";
    private static final String SOURCE = "src-lab";
    private static final String ACCESSION = "ACC-1";
    private static final String DESTINATION = "clinic-a";
    private static final String CLINICIAN = "clinician-a";
    private static final String RECEPTION = "reception-a";
    private static final String LAB_VERIFIER = "lab-verifier-a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private OrderRepository orderRepository;
    private ExchangeTaskRepository taskRepository;
    private ResultRepository resultRepository;
    private AuditAppender audit;
    private MockMvc mvc;

    /** Wire real services behind thin controllers with mocked, bounded repositories. */
    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        taskRepository = mock(ExchangeTaskRepository.class);
        resultRepository = mock(ResultRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = inlineTransactions();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OrderService orderService = new OrderService(orderRepository, audit, transactions, clock);
        ExchangeTaskService taskService = new ExchangeTaskService(taskRepository,
                new ExchangeActionPolicy(), transactions, audit, clock);
        ResultRoutingService resultService = new ResultRoutingService(resultRepository,
                new ResultActionPolicy(), transactions, audit, clock);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(
                        new OrderController(orderService),
                        new ExchangeTaskController(taskService),
                        new ResultController(resultService))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Execute the tenant-guarded callback immediately so tests stay deterministic. */
    private static TransactionTemplate inlineTransactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** A synthetic entitled member of the active organization. */
    private void entitled(String subject, String role) {
        when(orderRepository.hasActiveMembership(subject)).thenReturn(true);
        when(orderRepository.activeRoles(subject)).thenReturn(List.of(role));
        when(taskRepository.hasActiveMembership(subject)).thenReturn(true);
        when(taskRepository.activeRoles(subject)).thenReturn(List.of(role));
        when(resultRepository.hasActiveMembership(subject)).thenReturn(true);
        when(resultRepository.activeRoles(subject)).thenReturn(List.of(role));
    }

    /** HTTP GET carrying the synthetic-auth identity headers. */
    private ResultActions getAs(String path, String subject, String organization) throws Exception {
        return mvc.perform(get(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization));
    }

    /** Released order carrying distinctive, non-clinical markers for leak assertions. */
    private static OrderView releasedOrder() {
        return new OrderView(ORDER_ID, 1, PATIENT, "ENC-owner-only", "CBC-owner-only", "routine",
                "Synthetic question", DESTINATION, CLINICIAN, "dr-a", "lab-queue",
                "55555555-5555-5555-5555-555555555555",
                "66666666-6666-6666-6666-666666666666", FINGERPRINT,
                "88888888-8888-8888-8888-888888888888",
                "99999999-9999-9999-9999-999999999999", NOW);
    }

    /** A task in the initial workflow channel, used only as a stale-version boundary. */
    private static ExchangeTask task(int version) {
        return new ExchangeTask(TASK_ID, version, "LOCAL-owner-only", WorkflowState.REQUESTED,
                TransmissionState.PENDING, ClinicalReviewState.UNREVIEWED,
                null, null, null, null, NOW);
    }

    /** The registered expectation and the stored revision that the inbound result conflicts with. */
    private static ExpectedOrder expectedOrder() {
        return new ExpectedOrder(ORDER_REF, identifiers(), "owner-a", "queue-a");
    }

    private static ResultIdentifiers identifiers() {
        return new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DESTINATION, ORDER_REF);
    }

    private static ResultReport storedReport(String checksum) {
        return new ResultReport(REPORT_ID, 1, ORDER_REF, identifiers(), ResultKind.FINAL,
                null, null, "owner-a", "queue-a", NOW, NOW, checksum);
    }

    /** Assert that the body carries only the fixed error envelope, never a record marker. */
    private static void assertEnvelope(String body, String... absentMarkers) {
        for (String marker : absentMarkers) {
            assertFalse(body.contains(marker), "the error body disclosed " + marker);
        }
        assertFalse(body.isBlank(), "the error body must be present");
    }

    /** A malformed identifier is refused before a transaction or repository is entered. */
    @Test
    void malformedOrderIdIsUnprocessableBeforeAnyRepositoryCall() throws Exception {
        ResultActions refused = getAs("/api/v1/orders/not-a-uuid", CLINICIAN, ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("id must be a UUID"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$", aMapWithSize(3)));

        assertEnvelope(refused.andReturn().getResponse().getContentAsString(), "not-a-uuid");
        verifyNoInteractions(orderRepository, taskRepository, resultRepository, audit);
    }

    /** A caller without the exchange role is denied and audited before any resource read. */
    @Test
    void wrongRoleIsForbiddenWithDeniedAuditAndNoWrite() throws Exception {
        entitled(RECEPTION, "reception");

        ResultActions refused = getAs("/api/v1/orders/" + ORDER_ID, RECEPTION, ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("missing exchange permission"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$", aMapWithSize(3)));

        assertEnvelope(refused.andReturn().getResponse().getContentAsString(), ORDER_ID, PATIENT);
        verify(orderRepository, never()).find(anyString());
        verify(audit).append(any(), eq("ORDER_READ"), eq("ORDER_READ"), eq("ORDER"),
                eq("EXCHANGE"), eq(ORDER_ID), eq("DENIED"));
    }

    /** A foreign order id is concealed as not found, with no record field in the error body. */
    @Test
    void foreignOrderIdIsNotFoundWithoutRecordFieldsInBody() throws Exception {
        entitled(CLINICIAN, "clinician");
        when(orderRepository.find(ORDER_ID)).thenAnswer(invocation -> {
            TenantContext context = TenantContext.current();
            return ORG.equals(context.organizationId())
                    ? Optional.of(releasedOrder()) : Optional.empty();
        });

        getAs("/api/v1/orders/" + ORDER_ID, CLINICIAN, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.orderCode").value("CBC-owner-only"))
                .andExpect(jsonPath("$.snapshotFingerprint").value(FINGERPRINT));
        // Only the peer request's audit is under test; its own read writes no audit row.
        clearInvocations(audit);

        ResultActions concealed = getAs("/api/v1/orders/" + ORDER_ID, CLINICIAN, PEER_ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("order not found"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$", aMapWithSize(3)));

        assertEnvelope(concealed.andReturn().getResponse().getContentAsString(),
                ORDER_ID, PATIENT, "ENC-owner-only", "CBC-owner-only", FINGERPRINT, ORG);
        verifyNoInteractions(audit);
    }

    /** A foreign exchange task is concealed on read with no history read, write or audit. */
    @Test
    void foreignTaskIdIsNotFoundWithoutRecordFieldsInBody() throws Exception {
        entitled(CLINICIAN, "clinician");
        when(taskRepository.find(TASK_ID)).thenAnswer(invocation -> {
            TenantContext context = TenantContext.current();
            return ORG.equals(context.organizationId())
                    ? Optional.of(task(1)) : Optional.empty();
        });

        getAs("/api/v1/exchange-tasks/" + TASK_ID, CLINICIAN, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.orderRef").value("LOCAL-owner-only"));
        // The peer request's concealment is the assertion under test; its own
        // read writes no audit row.
        clearInvocations(audit);

        ResultActions concealed = getAs("/api/v1/exchange-tasks/" + TASK_ID, CLINICIAN, PEER_ORG)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("exchange task not found"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$", aMapWithSize(3)));

        assertEnvelope(concealed.andReturn().getResponse().getContentAsString(),
                TASK_ID, "LOCAL-owner-only", PEER_ORG);
        // Exactly one history read: the owner's successful read above. The
        // concealed peer read must not reach the history query.
        verify(taskRepository, times(1)).history(TASK_ID);
        verify(taskRepository, never()).updateState(anyString(), anyInt(), any());
        verifyNoInteractions(audit);
    }

    /** A stale read version and a lost compare-and-set both yield 412 with no event or audit. */
    @Test
    void staleVersionHasTwoRefusalPaths() throws Exception {
        entitled(CLINICIAN, "clinician");
        String path = "/api/v1/exchange-tasks/" + TASK_ID + "/accept";

        when(taskRepository.loadForUpdate(TASK_ID)).thenReturn(Optional.of(task(3)));
        mvc.perform(post(path)
                        .header("If-Match", "\"1\"")
                        .header("X-Synthetic-Subject", CLINICIAN)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.message").value("the exchange task changed since it was read"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$", aMapWithSize(3)));
        verify(taskRepository, never()).updateState(anyString(), anyInt(), any());

        when(taskRepository.loadForUpdate(TASK_ID)).thenReturn(Optional.of(task(1)));
        when(taskRepository.updateState(eq(TASK_ID), eq(1), any())).thenReturn(0);
        mvc.perform(post(path)
                        .header("If-Match", "\"1\"")
                        .header("X-Synthetic-Subject", CLINICIAN)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$", aMapWithSize(3)));

        verify(taskRepository, never()).appendEvent(anyString(), anyInt(), any(), any(),
                any(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    /** A repeated natural key carrying a different clinical payload conflicts instead of replaying. */
    @Test
    void repeatedResultKeyWithDifferentPayloadConflicts() throws Exception {
        entitled(LAB_VERIFIER, "lab_verifier");
        when(resultRepository.findOrder(ORDER_REF)).thenReturn(Optional.of(expectedOrder()));
        when(resultRepository.lineage(ORDER_REF, SOURCE, ACCESSION)).thenReturn(List.of());
        when(resultRepository.findReport(ORDER_REF, SOURCE, ACCESSION, 1))
                .thenReturn(Optional.of(storedReport("stored-payload")));

        String body = """
                {"orderId":"%s","patientId":"%s","sourceId":"%s","accessionId":"%s",
                 "destinationId":"%s","kind":"final","supersedesVersion":null,
                 "effectiveAt":"%s","checksum":"incoming-payload"}
                """.formatted(ORDER_REF, PATIENT, SOURCE, ACCESSION, DESTINATION, NOW);

        ResultActions refused = mvc.perform(post("/api/v1/xch04/results")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Synthetic-Subject", LAB_VERIFIER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYLOAD_CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("the result version already exists with different clinical content"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$", aMapWithSize(3)));

        assertEnvelope(refused.andReturn().getResponse().getContentAsString(), "incoming-payload");
        verify(resultRepository, never()).insertReport(any());
        verify(resultRepository, never()).insertReview(anyString());
        verify(resultRepository, never()).insertNotification(any());
        verify(audit).append(any(), eq("XCH04_RESULT_ROUTING"), eq("XCH04_RESULT_CONFLICT"),
                eq("RESULT_REPORT"), eq("EXCHANGE"), eq(REPORT_ID), eq("CONFLICT"));
    }
}
