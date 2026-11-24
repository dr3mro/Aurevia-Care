package com.aurevia.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.ConfigurationController;

/** S-G0-03 service branches: validation, authorization, concealment and audit (ADM-01/OPS-02). */
class ConfigurationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String KEY = "policy.key";

    private ConfigurationRepository repository;
    private TransactionTemplate transactions;
    private ConfigurationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConfigurationRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new ConfigurationService(repository, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, ConfigurationRepository.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    private ConfigEntry pending() {
        return new ConfigEntry(KEY, 1, "value", "pending", "2026-01-01T00:00:00Z", null, null, null, null);
    }

    @Test
    void rejectsNullKey() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(null, "value", null)).status());
    }

    @Test
    void rejectsNullValue() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(KEY, null, null)).status());
    }

    @Test
    void rejectsBlankValue() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(KEY, "  ", null)).status());
    }

    @Test
    void rejectsLongValue() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(KEY, "x".repeat(4001), null)).status());
    }

    @Test
    void rejectsBlankEffectiveFrom() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(KEY, "value", "  ")).status());
    }

    @Test
    void rejectsUnparseableEffectiveFrom() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(KEY, "value", "not-a-date")).status());
    }

    @Test
    void createDeniesInactiveMembershipAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.create(KEY, "value", null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_CREATE", KEY, "DENIED");
        verify(repository, never()).insertEntry(any(), anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void createDeniesMissingPermissionAndAudits() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.create(KEY, "value", null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_CREATE", KEY, "DENIED");
    }

    @Test
    void createRejectsConcurrentDuplicate() {
        authorized();
        when(repository.nextVersion(KEY)).thenThrow(new DuplicateKeyException("race"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.create(KEY, "value", null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_CREATE", KEY, "CONFLICT");
    }

    @Test
    void createAppendsDraftAndAudits() {
        authorized();
        when(repository.nextVersion(KEY)).thenReturn(1);
        when(repository.insertEntry(eq(ORG), eq(KEY), eq(1), eq("value"), anyString())).thenReturn("entry-id");
        ConfigEntry entry = service.create(KEY, "value", null);
        assertEquals(1, entry.version());
        assertEquals("pending", entry.status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_CREATE", KEY, "SUCCESS");
    }

    @Test
    void approveRejectsNonPositiveVersion() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.approve(KEY, 0, null)).status());
    }

    @Test
    void approveDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.approve(KEY, 1, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "DENIED");
    }

    @Test
    void approveConcealsMissingEntry() {
        authorized();
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.approve(KEY, 1, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "NOT_FOUND");
    }

    @Test
    void approveConcealsEntryWhoseIdIsMissing() {
        authorized();
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.of(pending()));
        when(repository.findUserIdBySubject(ACTOR)).thenReturn(Optional.of("actor-id"));
        when(repository.findEntryId(KEY, 1)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.approve(KEY, 1, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "NOT_FOUND");
    }

    @Test
    void approveConflictsOnAlreadyApproved() {
        authorized();
        ConfigEntry approved = new ConfigEntry(KEY, 1, "value", "approved", "t0", null, null, null, null);
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.of(approved));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.approve(KEY, 1, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "CONFLICT");
    }

    @Test
    void approveDeniesWhenApproverHasNoIdentity() {
        authorized();
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.of(pending()));
        when(repository.findEntryId(KEY, 1)).thenReturn(Optional.of("entry-id"));
        when(repository.findUserIdBySubject(ACTOR)).thenReturn(Optional.empty());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.approve(KEY, 1, null)).status());
        verify(repository, never()).insertApproval(any(), anyString(), any());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "DENIED");
    }

    @Test
    void approveRejectsConcurrentDuplicate() {
        authorized();
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.of(pending()));
        when(repository.findEntryId(KEY, 1)).thenReturn(Optional.of("entry-id"));
        when(repository.findUserIdBySubject(ACTOR)).thenReturn(Optional.of("actor-id"));
        when(repository.insertApproval(ORG, "entry-id", null)).thenThrow(new DuplicateKeyException("race"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.approve(KEY, 1, null)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "CONFLICT");
    }

    @Test
    void approveAppendsApprovalAndAudits() {
        authorized();
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.of(pending()));
        when(repository.findEntryId(KEY, 1)).thenReturn(Optional.of("entry-id"));
        when(repository.findUserIdBySubject(ACTOR)).thenReturn(Optional.of("actor-id"));
        ConfigEntry entry = service.approve(KEY, 1, "note");
        assertEquals("approved", entry.status());
        assertEquals("actor-id", entry.approvedBy());
        assertEquals("note", entry.note());
        verify(repository).insertApproval(ORG, "entry-id", "note");
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "SUCCESS");
    }

    @Test
    void getDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(KEY)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_READ", KEY, "DENIED");
    }

    @Test
    void getConcealsAbsentEffectiveVersion() {
        authorized();
        when(repository.effective(KEY)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(KEY)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_READ", KEY, "NOT_FOUND");
    }

    @Test
    void getAuditsRead() {
        authorized();
        ConfigEntry effective = new ConfigEntry(KEY, 1, "value", "approved", "t0", null, null, null, null);
        when(repository.effective(KEY)).thenReturn(Optional.of(effective));
        assertEquals("value", service.get(KEY).value());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_READ", KEY, "SUCCESS");
    }

    @Test
    void historyDeniesInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.history(KEY)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_HISTORY", KEY, "DENIED");
    }

    @Test
    void historyConcealsUnknownKey() {
        authorized();
        when(repository.findAll(KEY)).thenReturn(List.of());
        assertEquals(404, assertThrows(ApiException.class, () -> service.history(KEY)).status());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_HISTORY", KEY, "NOT_FOUND");
    }

    @Test
    void historyReturnsFrozenEntriesAndAudits() {
        authorized();
        ConfigEntry pending = pending();
        when(repository.findAll(KEY)).thenReturn(List.of(pending));
        ConfigHistory result = service.history(KEY);
        assertEquals(KEY, result.key());
        assertEquals(List.of(pending), result.entries());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().clear());
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_HISTORY", KEY, "SUCCESS");
    }

    @Test
    void valueRecordsExposeTheirParts() {
        ConfigEntry entry = pending();
        assertEquals(KEY, entry.key());
        assertEquals(1, entry.version());
        assertEquals("value", entry.value());
        assertEquals("pending", entry.status());
        assertEquals("2026-01-01T00:00:00Z", entry.effectiveFrom());
        assertEquals(null, entry.createdBy());
        assertEquals(null, entry.approvedBy());
        assertEquals(null, entry.approvedAt());
        assertEquals(null, entry.note());
        assertEquals(entry, new ConfigEntry(KEY, 1, "value", "pending", "2026-01-01T00:00:00Z",
                null, null, null, null));
        assertEquals(entry.hashCode(), new ConfigEntry(KEY, 1, "value", "pending", "2026-01-01T00:00:00Z",
                null, null, null, null).hashCode());
        assertEquals(entry.toString(), new ConfigEntry(KEY, 1, "value", "pending", "2026-01-01T00:00:00Z",
                null, null, null, null).toString());

        ConfigHistory history = new ConfigHistory(KEY, List.of(entry));
        assertEquals(KEY, history.key());
        assertEquals(List.of(entry), history.entries());
        assertEquals(history, new ConfigHistory(KEY, List.of(entry)));
        assertEquals(history.hashCode(), new ConfigHistory(KEY, List.of(entry)).hashCode());
        assertEquals(history.toString(), new ConfigHistory(KEY, List.of(entry)).toString());

        ConfigurationController.CreateRequest create =
                new ConfigurationController.CreateRequest(KEY, "value", Instant.EPOCH.toString());
        assertEquals(KEY, create.key());
        assertEquals("value", create.value());
        assertEquals(Instant.EPOCH.toString(), create.effectiveFrom());
        assertEquals(create, new ConfigurationController.CreateRequest(KEY, "value", Instant.EPOCH.toString()));
        assertEquals(create.hashCode(), new ConfigurationController.CreateRequest(KEY, "value", Instant.EPOCH.toString()).hashCode());
        assertEquals(create.toString(), new ConfigurationController.CreateRequest(KEY, "value", Instant.EPOCH.toString()).toString());

        ConfigurationController.ApproveRequest approve = new ConfigurationController.ApproveRequest("note");
        assertEquals("note", approve.note());
        assertEquals(approve, new ConfigurationController.ApproveRequest("note"));
        assertEquals(approve.hashCode(), new ConfigurationController.ApproveRequest("note").hashCode());
        assertEquals(approve.toString(), new ConfigurationController.ApproveRequest("note").toString());
    }
}
