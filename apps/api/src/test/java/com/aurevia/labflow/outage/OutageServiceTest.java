package com.aurevia.labflow.outage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.labflow.outage.OutageModel.Disposition;
import com.aurevia.labflow.outage.OutageModel.EvidenceAttachment;
import com.aurevia.labflow.outage.OutageModel.ManualResult;
import com.aurevia.labflow.outage.OutageModel.ManualResultRequest;
import com.aurevia.labflow.outage.OutageModel.OutageEvent;
import com.aurevia.labflow.results.ObservationInput;
import com.aurevia.tenant.TenantContext;

/** LAB-02 / XCH-03 / PRD-02 partner-outage fallback service behaviour. */
class OutageServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String COLLECTOR = "lab-collector-a";
    private static final String VERIFIER = "lab-verifier-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String SPECIMEN = "33333333-3333-3333-3333-333333333333";
    private static final String UNKNOWN = "44444444-4444-4444-4444-444444444444";
    private static final String NUMBER = "ACC-1";
    private static final String ISSUER = "LAB-A";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private LabAccessionRepository accessions;
    private TransactionTemplate transactions;
    private AuditAppender audit;
    private OutageService service;

    @BeforeEach
    void setUp() {
        accessions = mock(LabAccessionRepository.class);
        transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        Clock clock = Clock.fixed(AT, ZoneOffset.UTC);
        service = new OutageService(accessions, transactions, audit, clock);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, COLLECTOR, "PARTNER_OUTAGE_FALLBACK", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(accessions.hasActiveMembership(anyString())).thenReturn(true);
        when(accessions.activeRoles(anyString())).thenReturn(List.of(roles));
    }

    private static EvidenceAttachment attachment() {
        return new EvidenceAttachment("partner/report-1", "original-report.pdf",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "application/pdf");
    }

    private static ObservationInput observation() {
        return new ObservationInput("GLU", "numeric", "present", "5.5", null, "mmol/L",
                null, null, "photometry", "analyser-1", null, null, null, null);
    }

    private static ManualResultRequest request(String reason, String owner) {
        return new ManualResultRequest(PATIENT, NUMBER, ISSUER, reason, attachment(),
                List.of(observation()), owner);
    }

    private static LabAccession specimen() {
        return new LabAccession(SPECIMEN, 1, NUMBER, ISSUER, PATIENT, "ORD/1",
                AccessionState.COLLECTED, "PLASMA", "SITE-A", "Sam Collector",
                "2026-11-21T09:00:00Z", null, "acceptable", null, null, null);
    }

    @Test
    void manualEntryRequiresAReasonBeforeAnyWork() {
        authorized("lab_collector");
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, request("  ", null))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, null)).code());
        verify(transactions, never()).execute(any());
    }

    @Test
    void matchedEntryLinksTheAttachmentToTheOrderAndAppearsInHistory() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen()));

        ManualResult result = service.record(SPECIMEN, request("partner interface down", null));

        assertEquals(Disposition.LINKED, result.disposition());
        assertEquals(SPECIMEN, result.orderId());
        assertEquals(attachment(), result.attachment());
        assertEquals("GLU", result.observationSummary());
        assertEquals(COLLECTOR, result.enteredBy());
        assertEquals(AT, result.recordedAt());
        assertEquals(result, service.forOrder(SPECIMEN).orElseThrow());
        assertEquals(attachment(), service.forOrder(SPECIMEN).orElseThrow().attachment());

        List<OutageEvent> history = service.history(SPECIMEN);
        assertEquals(1, history.size());
        assertEquals(OutageService.ACTION, history.get(0).action());
        assertEquals(Disposition.LINKED, history.get(0).disposition());
        assertEquals("partner interface down", history.get(0).reason());
        assertEquals(COLLECTOR, history.get(0).actor());
        verify(audit).append(any(), eq(OutageService.PURPOSE), eq(OutageService.ACTION),
                eq(OutageService.RESOURCE), eq(OutageService.SCOPE), eq(SPECIMEN), eq("SUCCESS"));
    }

    @Test
    void aVerifierMayAlsoEnterAManualResultDuringAnOutage() {
        authorized("lab_verifier");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen()));

        assertEquals(Disposition.LINKED,
                service.record(SPECIMEN, request("partner interface down", null)).disposition());
    }

    @Test
    void unmatchedResultGoesToTheQueueWithAnOwnerAndAppearsInHistory() {
        authorized("lab_collector");
        when(accessions.findById(UNKNOWN)).thenReturn(Optional.empty());

        ManualResult result = service.record(UNKNOWN, request("partner interface down", "reconciliation-owner"));

        assertEquals(Disposition.UNMATCHED, result.disposition());
        assertNull(result.orderId());
        assertEquals("reconciliation-owner", result.owner());
        assertEquals(attachment(), result.attachment());
        assertEquals(1, service.unmatchedQueue().size());
        assertEquals(result, service.unmatchedQueue().get(0));
        assertEquals(Disposition.UNMATCHED, service.history(UNKNOWN).get(0).disposition());
        assertTrue(service.forOrder(UNKNOWN).isEmpty());
        verify(audit).append(any(), eq(OutageService.PURPOSE), eq(OutageService.ACTION),
                eq(OutageService.RESOURCE), eq(OutageService.SCOPE), eq(UNKNOWN), eq("UNMATCHED"));
    }

    @Test
    void mismatchedIdentifiersAreQueuedRatherThanLinked() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen()));

        ManualResult result = service.record(SPECIMEN,
                new ManualResultRequest(OTHER_PATIENT, NUMBER, ISSUER, "partner interface down",
                        attachment(), List.of(observation()), "reconciliation-owner"));

        assertEquals(Disposition.UNMATCHED, result.disposition());
        assertNull(result.orderId());
        assertEquals(1, service.unmatchedQueue().size());
    }

    @Test
    void anUnmatchedResultRequiresABoundedOwner() {
        authorized("lab_collector");
        when(accessions.findById(UNKNOWN)).thenReturn(Optional.empty());

        assertEquals("OWNER_REQUIRED", assertThrows(ApiException.class,
                () -> service.record(UNKNOWN, request("partner interface down", " "))).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.record(UNKNOWN, request("partner interface down", "o".repeat(201)))).status());
        assertTrue(service.unmatchedQueue().isEmpty());
    }

    @Test
    void inactiveMembershipAndWrongRoleAreDeniedAndAudited() {
        when(accessions.hasActiveMembership(anyString())).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, request("partner interface down", null))).status());

        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, request("partner interface down", null))).status());
        verify(audit, times(2)).append(any(), eq(OutageService.PURPOSE), eq(OutageService.ACTION),
                eq(OutageService.RESOURCE), eq(OutageService.SCOPE), eq(SPECIMEN), eq("DENIED"));
        verify(accessions, never()).findById(anyString());
    }

    @Test
    void invalidBoundaryValuesAreRejectedBeforeATransaction() {
        assertEquals("INVALID_ID", assertThrows(ApiException.class,
                () -> service.record("not-a-uuid", request("partner interface down", null))).code());
        assertEquals("INVALID_PATIENT", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, new ManualResultRequest("bad", NUMBER, ISSUER,
                        "partner interface down", attachment(), List.of(observation()), null))).code());
        assertEquals("INVALID_ACCESSION", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, new ManualResultRequest(PATIENT, "!", ISSUER,
                        "partner interface down", attachment(), List.of(observation()), null))).code());
        assertEquals("INVALID_ISSUER", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, new ManualResultRequest(PATIENT, NUMBER, "!",
                        "partner interface down", attachment(), List.of(observation()), null))).code());
        assertEquals("INVALID_OBJECT_KEY", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, new ManualResultRequest(PATIENT, NUMBER, ISSUER,
                        "partner interface down", new EvidenceAttachment("k", "f.pdf", "bad", "application/pdf"),
                        List.of(observation()), null))).code());
        assertEquals("NO_OBSERVATIONS", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, new ManualResultRequest(PATIENT, NUMBER, ISSUER,
                        "partner interface down", attachment(), List.of(), null))).code());
        // A null observation list is frozen to empty at the boundary and refused.
        ManualResultRequest nullObservations = new ManualResultRequest(PATIENT, NUMBER, ISSUER,
                "partner interface down", attachment(), null, null);
        assertTrue(nullObservations.observations().isEmpty());
        assertEquals("NO_OBSERVATIONS", assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, nullObservations)).code());
        verify(transactions, never()).execute(any());
    }

    @Test
    void outageFactsAreReadableByAnyTenantMemberButOnlyLaboratoryRolesMayWrite() {
        when(accessions.hasActiveMembership(COLLECTOR)).thenReturn(true);
        when(accessions.activeRoles(COLLECTOR)).thenReturn(List.of("lab_collector"));
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen()));
        ManualResult linked = service.record(SPECIMEN, request("partner interface down", null));

        TenantContext.set(new TenantContext(ORG, "reception-a", "PARTNER_OUTAGE_FALLBACK", "corr-2"));
        when(accessions.hasActiveMembership("reception-a")).thenReturn(true);
        when(accessions.activeRoles("reception-a")).thenReturn(List.of("reception"));

        assertEquals(linked, service.forOrder(SPECIMEN).orElseThrow());
        assertEquals(1, service.history(SPECIMEN).size());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.record(SPECIMEN, request("partner interface down", null))).status());
    }

    @Test
    void concurrentEntriesKeepEveryQueueAndHistoryFact() throws Exception {
        authorized("lab_collector");
        when(accessions.findById(UNKNOWN)).thenReturn(Optional.empty());
        int count = 8;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ManualResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    TenantContext.set(new TenantContext(ORG, COLLECTOR,
                            "PARTNER_OUTAGE_FALLBACK", "corr-concurrent"));
                    start.countDown();
                    try {
                        return service.record(UNKNOWN,
                                request("partner interface down", "reconciliation-owner"));
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            start.await();
            for (Future<ManualResult> future : futures) {
                assertNotNull(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(count, service.unmatchedQueue().size());
        assertEquals(count, service.history(UNKNOWN).size());
    }

    @Test
    void readsAreTenantScopedAndEmptyForAnUnknownOrder() {
        authorized("lab_collector");
        when(accessions.findById(SPECIMEN)).thenReturn(Optional.of(specimen()));
        service.record(SPECIMEN, request("partner interface down", null));

        assertTrue(service.history(UNKNOWN).isEmpty());
        assertNotNull(service.forOrder(SPECIMEN).orElseThrow());

        TenantContext.set(new TenantContext(OTHER_ORG, COLLECTOR, "PARTNER_OUTAGE_FALLBACK", "corr-2"));
        assertTrue(service.forOrder(SPECIMEN).isEmpty());
        assertTrue(service.history(SPECIMEN).isEmpty());
        assertTrue(service.unmatchedQueue().isEmpty());
    }
}
