package com.aurevia.exchange.clauses;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.exchange.result.ResultController;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-04 clause 2 (HTTP test): an inbound result is imported only after the
 * patient, source, accession, destination and order identifiers all match the
 * locally registered expectation.
 *
 * <p>Every case enters through {@code POST /api/v1/xch04/results} on the real
 * {@link com.aurevia.exchange.result.ResultController}, which calls the
 * real {@link com.aurevia.exchange.result.ResultRoutingService} and
 * {@link com.aurevia.exchange.result.ResultRules}. Only the persistence
 * and audit boundaries are mocked, so a refused match is proven to leave the
 * report ledger untouched. All identifiers are synthetic; the tenant context is
 * bound for each case and cleared afterwards. This test proves the service gate,
 * not PostgreSQL row-level security - the real two-tenant isolation evidence is
 * {@code it.exchangeresult.ResultRoutingIntegrationTest.authorizationAndTenantIsolationFailClosed}.
 */
class MatchPatientSourceAccessionDestinationOrderIdentifiersTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "88888888-8888-8888-8888-888888888888";
    private static final String SUBJECT = "verifier-clause2";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ORDER = "order-clause2";
    private static final String UNREGISTERED_ORDER = "order-unregistered-clause2";
    private static final String SOURCE = "source-clause2";
    private static final String OTHER_SOURCE = "source-other-clause2";
    private static final String ACCESSION = "accession-clause2";
    private static final String OTHER_ACCESSION = "accession-other-clause2";
    private static final String DESTINATION = "destination-clause2";
    private static final String OTHER_DESTINATION = "destination-other-clause2";
    private static final String OWNER = "owner-clause2";
    private static final String COVERAGE_QUEUE = "queue-clause2";
    private static final String CHECKSUM = "checksum-clause2";
    private static final String EFFECTIVE_AT = "2026-11-22T09:00:00Z";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final String RESULTS = "/api/v1/xch04/results";

    private ResultRepository repository;
    private AuditAppender audit;
    private MockMvc mvc;

    /** Build the HTTP stack around the real domain decision with deterministic boundaries. */
    @BeforeEach
    void setUp() {
        repository = mock(ResultRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        // Run the guarded callback synchronously so a denial is observable as an HTTP status.
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        ResultRoutingService service = new ResultRoutingService(repository, new ResultActionPolicy(),
                transactions, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new ResultController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
        TenantContext.set(new TenantContext(ORG, SUBJECT, "XCH04_RESULT_IMPORT", "corr-clause2"));
        authorizedVerifier();
    }

    /** Remove the synthetic tenant context so no other test can inherit it. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant the caller an active membership and the result-import role. */
    private void authorizedVerifier() {
        when(repository.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(repository.activeRoles(SUBJECT)).thenReturn(List.of("lab_verifier"));
    }

    /** Make the synthetic expectation visible to the real service through its repository seam. */
    private void registerExpectedOrder() {
        when(repository.findOrder(ORDER)).thenReturn(Optional.of(expectedOrder()));
    }

    /** The locally registered order expectation all five identifiers must agree with. */
    private static ExpectedOrder expectedOrder() {
        return new ExpectedOrder(ORDER, new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DESTINATION, ORDER),
                OWNER, COVERAGE_QUEUE);
    }

    /** Stub the writes a matched final import performs so the 201 path is deterministic. */
    private void stubMatchedImport() {
        when(repository.insertReport(any())).thenReturn(true);
    }

    /** JSON body for one import; each identifier can be varied independently. */
    private static String importJson(String orderId, String patientId, String sourceId,
                                     String accessionId, String destinationId) {
        return """
                {"orderId":"%s","patientId":"%s","sourceId":"%s","accessionId":"%s",
                 "destinationId":"%s","kind":"final","supersedesVersion":null,
                 "effectiveAt":"%s","checksum":"%s"}
                """.formatted(orderId, patientId, sourceId, accessionId, destinationId,
                        EFFECTIVE_AT, CHECKSUM);
    }

    /** Import whose five identifiers all equal the registered expectation. */
    private static String matchingImportJson() {
        return importJson(ORDER, PATIENT, SOURCE, ACCESSION, DESTINATION);
    }

    /** Assert the audit event a command wrote, without depending on its generated id. */
    private void audited(String action, String outcome) {
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq("XCH04_RESULT_ROUTING"),
                eq(action), anyString(), eq("EXCHANGE"), anyString(), eq(outcome));
    }

    /** Assert one refused import: 202 reconciliation, no report or review write. */
    private void assertDiverted(String body, String reason, String detail, String mismatchField) throws Exception {
        var response = mvc.perform(post(RESULTS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value(reason))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.detail").value(detail));
        if (mismatchField == null) {
            response.andExpect(jsonPath("$.mismatches").isEmpty());
        } else {
            response.andExpect(jsonPath("$.mismatches[0]").value(mismatchField));
        }
        ArgumentCaptor<ReconciliationEntry> entry = ArgumentCaptor.forClass(ReconciliationEntry.class);
        verify(repository).insertReconciliation(entry.capture());
        assertEquals(ReconciliationStatus.OPEN, entry.getValue().status());
        assertEquals(reason.equals("conflict")
                ? ReconciliationReason.CONFLICT : ReconciliationReason.UNMATCHED, entry.getValue().reason());
        if (mismatchField != null) {
            assertEquals(List.of(mismatchField), entry.getValue().mismatches());
        }
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        audited("XCH04_RESULT_RECONCILED", "SUCCESS");
    }

    /** All five identifiers equal: the report is imported and routed, with no reconciliation entry. */
    @Test
    void allFiveIdentifiersMatchImportsTheReport() throws Exception {
        registerExpectedOrder();
        stubMatchedImport();

        mvc.perform(post(RESULTS).contentType(MediaType.APPLICATION_JSON).content(matchingImportJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith(RESULTS + "/")))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.kind").value("final"))
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.owner").value(OWNER))
                .andExpect(jsonPath("$.coverageQueue").value(COVERAGE_QUEUE))
                .andExpect(jsonPath("$.identifiers.patientId").value(PATIENT))
                .andExpect(jsonPath("$.identifiers.sourceId").value(SOURCE))
                .andExpect(jsonPath("$.identifiers.accessionId").value(ACCESSION))
                .andExpect(jsonPath("$.identifiers.destinationId").value(DESTINATION))
                .andExpect(jsonPath("$.identifiers.orderId").value(ORDER));

        ArgumentCaptor<ResultReport> report = ArgumentCaptor.forClass(ResultReport.class);
        verify(repository).insertReport(report.capture());
        assertEquals(OWNER, report.getValue().owner());
        assertEquals(COVERAGE_QUEUE, report.getValue().coverageQueue());
        verify(repository).insertReview(report.getValue().id());
        verify(repository, never()).insertReconciliation(any());
        audited("XCH04_RESULT_IMPORT", "SUCCESS");
    }

    /** A wrong patient identifier is refused to reconciliation without an import. */
    @Test
    void mismatchedPatientIdIsDivertedToReconciliationWithoutImport() throws Exception {
        registerExpectedOrder();
        assertDiverted(importJson(ORDER, OTHER_PATIENT, SOURCE, ACCESSION, DESTINATION),
                "conflict", "identifier mismatch: patientId", "patientId");
    }

    /** A wrong source identifier is refused to reconciliation without an import. */
    @Test
    void mismatchedSourceIdIsDivertedToReconciliationWithoutImport() throws Exception {
        registerExpectedOrder();
        assertDiverted(importJson(ORDER, PATIENT, OTHER_SOURCE, ACCESSION, DESTINATION),
                "conflict", "identifier mismatch: sourceId", "sourceId");
    }

    /** A wrong accession identifier is refused to reconciliation without an import. */
    @Test
    void mismatchedAccessionIdIsDivertedToReconciliationWithoutImport() throws Exception {
        registerExpectedOrder();
        assertDiverted(importJson(ORDER, PATIENT, SOURCE, OTHER_ACCESSION, DESTINATION),
                "conflict", "identifier mismatch: accessionId", "accessionId");
    }

    /** A wrong destination identifier is refused to reconciliation without an import. */
    @Test
    void mismatchedDestinationIdIsDivertedToReconciliationWithoutImport() throws Exception {
        registerExpectedOrder();
        assertDiverted(importJson(ORDER, PATIENT, SOURCE, ACCESSION, OTHER_DESTINATION),
                "conflict", "identifier mismatch: destinationId", "destinationId");
    }

    /**
     * An unregistered order reference is refused as UNMATCHED. The order identifier is
     * the lookup key, so a wrong reference cannot resolve an expectation; the field-level
     * orderId comparison itself is covered by {@code it.exchangeresult.ResultRulesTest}.
     */
    @Test
    void unregisteredOrderIsDivertedToReconciliationAsUnmatched() throws Exception {
        when(repository.findOrder(UNREGISTERED_ORDER)).thenReturn(Optional.empty());

        assertDiverted(importJson(UNREGISTERED_ORDER, PATIENT, SOURCE, ACCESSION, DESTINATION),
                "unmatched", "no expected order registered for " + UNREGISTERED_ORDER, null);
        verify(repository).findOrder(UNREGISTERED_ORDER);
    }

    /** A caller without the result-import role is refused before any match or write. */
    @Test
    void callerWithoutResultImportRoleIsForbiddenBeforeMatching() throws Exception {
        when(repository.activeRoles(SUBJECT)).thenReturn(List.of("reception"));

        mvc.perform(post(RESULTS).contentType(MediaType.APPLICATION_JSON).content(matchingImportJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("missing result permission"));

        verify(repository, never()).findOrder(anyString());
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        verify(repository, never()).insertReconciliation(any());
        audited("XCH04_RESULT_IMPORT", "DENIED");
    }

    /** A caller without active membership is refused before any match or write. */
    @Test
    void callerWithoutActiveMembershipIsForbiddenBeforeMatching() throws Exception {
        when(repository.hasActiveMembership(SUBJECT)).thenReturn(false);

        mvc.perform(post(RESULTS).contentType(MediaType.APPLICATION_JSON).content(matchingImportJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("no active membership"));

        verify(repository, never()).findOrder(anyString());
        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        verify(repository, never()).insertReconciliation(any());
        audited("XCH04_RESULT_IMPORT", "DENIED");
    }

    /**
     * An order not visible in the active tenant is refused. The mocked repository models
     * the RLS outcome (no visible expectation); the diverted report and its audit stay in
     * the calling tenant. This does not itself prove RLS - the PostgreSQL integration test
     * named in the class Javadoc is that evidence.
     */
    @Test
    void orderNotVisibleInActiveTenantIsDivertedWithoutImport() throws Exception {
        TenantContext.set(new TenantContext(PEER_ORG, SUBJECT, "XCH04_RESULT_IMPORT", "corr-clause2-peer"));
        when(repository.findOrder(ORDER)).thenReturn(Optional.empty());

        mvc.perform(post(RESULTS).contentType(MediaType.APPLICATION_JSON).content(matchingImportJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reason").value("unmatched"))
                .andExpect(jsonPath("$.status").value("open"))
                .andExpect(jsonPath("$.mismatches").isEmpty());

        verify(repository, never()).insertReport(any());
        verify(repository, never()).insertReview(anyString());
        verify(repository).insertReconciliation(any());
        ArgumentCaptor<TenantContext> tenant = ArgumentCaptor.forClass(TenantContext.class);
        verify(audit).append(tenant.capture(), eq("XCH04_RESULT_ROUTING"),
                eq("XCH04_RESULT_RECONCILED"), eq("RECONCILIATION_ENTRY"), eq("EXCHANGE"),
                anyString(), eq("SUCCESS"));
        assertEquals(PEER_ORG, tenant.getValue().organizationId());
        assertEquals(SUBJECT, tenant.getValue().subject());
    }
}
