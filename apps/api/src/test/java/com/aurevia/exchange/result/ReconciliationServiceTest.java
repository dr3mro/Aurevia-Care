package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.ResolveRequest;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.tenant.TenantContext;

/**
 * Every service branch of XCH-04 reconciliation resolution, with mocked boundaries.
 */
class ReconciliationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "99999999-9999-9999-9999-999999999999";
    private static final String ACTOR = "operator-1";
    private static final String ENTRY_ID = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private ResultRepository repository;
    private AuditAppender audit;
    private ReconciliationStore store;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ResultRepository.class);
        audit = mock(AuditAppender.class);
        store = mock(ReconciliationStore.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ReconciliationService(repository, new ResultActionPolicy(), transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), store);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "XCH04_RECONCILIATION_RESOLVE", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles));
    }

    private void openEntry() {
        when(repository.findReconciliation(ENTRY_ID)).thenReturn(Optional.of(new ReconciliationEntry(
                ENTRY_ID,
                new ResultIdentifiers("33333333-3333-3333-3333-333333333333", "src", "acc", "dest", "order-1"),
                ReconciliationReason.CONFLICT, List.of("patientId"), "identifier mismatch",
                ReconciliationStatus.OPEN, NOW)));
    }

    private static ResolveRequest request(String action, String reason, String owner) {
        return new ResolveRequest(action, reason, owner);
    }

    private void storeAccepts(Resolution resolution) {
        when(store.insert(eq(ORG), any(Resolution.class))).thenReturn(resolution);
        when(store.find(ORG, ENTRY_ID)).thenReturn(resolution);
    }

    @Test
    void matchRecordsTheReasonWithoutAnOwnerAndAuditsSuccess() {
        authorized("tenant_admin");
        openEntry();
        Resolution expected = new Resolution(ENTRY_ID, ReconciliationAction.MATCH,
                "identifiers verified", null, NOW);
        storeAccepts(expected);

        Resolution resolution = service.resolve(ENTRY_ID, request("match", "identifiers verified", null));

        assertEquals(expected, resolution);
        assertEquals(NOW, resolution.resolvedAt());
        assertEquals(Optional.of(expected), service.resolution(ENTRY_ID));
        verify(audit, atLeastOnce()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
    }

    @Test
    void reassignRecordsTheNewOwnerAndReason() {
        authorized("lab_verifier");
        openEntry();
        Resolution expected = new Resolution(ENTRY_ID, ReconciliationAction.REASSIGN,
                "owner on leave", "owner-b", NOW);
        storeAccepts(expected);

        Resolution resolution = service.resolve(ENTRY_ID,
                request("reassign", "owner on leave", "owner-b"));

        assertEquals(expected, resolution);
        assertEquals("owner-b", service.resolution(ENTRY_ID).orElseThrow().assignedOwner());
    }

    @Test
    void rejectsAMissingRequestAndAMalformedEntryId() {
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.resolve("not-a-uuid", request("match", "verified", null))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, null)).code());
        verify(repository, never()).findReconciliation(anyString());
        verify(store, never()).insert(anyString(), any(Resolution.class));
    }

    @Test
    void rejectsAnUnknownActionBeforeTouchingTheQueue() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, request("delete", "verified", null)));
        assertEquals(422, error.status());
        assertEquals("INVALID_ACTION", error.code());
        verify(repository, never()).findReconciliation(anyString());
    }

    @Test
    void deniesInactiveMembershipAndMissingRoleWithAudit() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, request("match", "verified", null))).status());
        verify(audit, atLeastOnce()).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));

        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, request("match", "verified", null))).status());
        verify(repository, never()).findReconciliation(anyString());
    }

    @Test
    void concealsAnEntryThatIsNotVisibleInTheActiveTenant() {
        authorized("tenant_admin");
        when(repository.findReconciliation(ENTRY_ID)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, request("match", "verified", null))).status());
        verify(store, never()).insert(anyString(), any(Resolution.class));
    }

    @Test
    void doesNotRecordWhenTheReasonIsMissing() {
        authorized("tenant_admin");
        openEntry();
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, request("match", "  ", null))).code());
        assertTrue(service.resolution(ENTRY_ID).isEmpty());
        verify(store, never()).insert(anyString(), any(Resolution.class));
    }

    @Test
    void aRejectedStoreInsertIsReportedAsAConflictAndAudited() {
        authorized("tenant_admin");
        openEntry();
        when(store.insert(eq(ORG), any(Resolution.class))).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.resolve(ENTRY_ID, request("reject", "changed my mind", null)));
        assertEquals(409, error.status());
        assertEquals("ENTRY_ALREADY_RESOLVED", error.code());
        verify(audit, atLeastOnce()).append(any(), any(), any(), any(), any(), any(), eq("CONFLICT"));
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
    }

    @Test
    void readingAResolutionRequiresMembershipAndAReadRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        assertEquals(403, assertThrows(ApiException.class, () -> service.resolution(ENTRY_ID)).status());
        verify(audit, atLeastOnce()).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));

        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.resolution(ENTRY_ID)).status());
        verify(store, never()).find(anyString(), anyString());
    }

    @Test
    void resolutionIsScopedToTheActiveTenant() {
        authorized("tenant_admin");
        openEntry();
        Resolution decision = new Resolution(ENTRY_ID, ReconciliationAction.MATCH, "verified", null, NOW);
        storeAccepts(decision);
        assertEquals(decision, service.resolve(ENTRY_ID, request("match", "verified", null)));

        TenantContext.set(new TenantContext(OTHER_ORG, ACTOR, "XCH04_RECONCILIATION_RESOLVE", "corr-2"));
        assertTrue(service.resolution(ENTRY_ID).isEmpty());
        verify(store).find(OTHER_ORG, ENTRY_ID);
    }
}
