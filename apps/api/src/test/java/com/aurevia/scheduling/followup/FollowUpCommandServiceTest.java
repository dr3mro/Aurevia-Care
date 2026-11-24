package com.aurevia.scheduling.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;

/**
 * CLN-06 transaction boundary: each command runs inside the tenant-guarded template,
 * a denial audit commits before the exception is rethrown, and a non-domain failure
 * propagates so the transaction rolls back.
 */
class FollowUpCommandServiceTest {

    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String EPOCH = "2026-03-06T15:00:00Z";

    private FollowUpService service;
    private TransactionTemplate transactions;
    private FollowUpCommandService commands;
    private FollowUpSuggestion suggestion;
    private CareTeamTask task;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(FollowUpService.class);
        transactions = mock(TransactionTemplate.class);
        // Run the callback without a database: the fake stands in for the
        // tenant-guarded template so this tier stays fast and deterministic.
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        commands = new FollowUpCommandService(service, transactions);
        suggestion = new FollowUpSuggestion(ID, "org", "patient", "CLINIC", "FOLLOW",
                Instant.parse(EPOCH), "UTC", LocalDate.of(2026, 3, 20), 14, "CONFIGURED_INTERVAL",
                "rule", null, false, null, 1);
        task = new CareTeamTask("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "org", ID, "nurse",
                LocalDate.of(2026, 3, 18), "call", CareTeamTask.OPEN, null, "clinician",
                Instant.parse(EPOCH));
    }

    @Test
    void everyCommandDelegatesInsideTheTransaction() {
        when(service.suggest("patient", "CLINIC", "FOLLOW", EPOCH)).thenReturn(suggestion);
        when(service.override(ID, "2026-03-25", "why")).thenReturn(suggestion);
        when(service.confirm(ID, 1)).thenReturn(suggestion);
        when(service.createTask(ID, "nurse", "2026-03-18", "call")).thenReturn(task);
        when(service.completeTask(task.id(), "done")).thenReturn(task.complete("done"));
        when(service.tasks(ID)).thenReturn(List.of(task));

        assertEquals(suggestion, commands.suggest("patient", "CLINIC", "FOLLOW", EPOCH));
        assertEquals(suggestion, commands.override(ID, "2026-03-25", "why"));
        assertEquals(suggestion, commands.confirm(ID, 1));
        assertEquals(task, commands.createTask(ID, "nurse", "2026-03-18", "call"));
        assertEquals(CareTeamTask.COMPLETED, commands.completeTask(task.id(), "done").status());
        assertEquals(List.of(task), commands.tasks(ID));

        verify(service).suggest("patient", "CLINIC", "FOLLOW", EPOCH);
        verify(service).override(ID, "2026-03-25", "why");
        verify(service).confirm(ID, 1);
        verify(service).createTask(ID, "nurse", "2026-03-18", "call");
        verify(service).completeTask(task.id(), "done");
        verify(service).tasks(ID);
    }

    @Test
    void domainDenialIsRethrownUnchangedAfterTheTransactionCommits() {
        ApiException denial = new ApiException(403, "FORBIDDEN", "missing action permission");
        when(service.confirm(ID, 1)).thenThrow(denial);
        // The domain writes its DENIED audit before throwing. Catching the
        // domain error inside the callback commits that audit; the wrapper then
        // rethrows the same error to the caller.
        assertEquals(denial, assertThrows(ApiException.class, () -> commands.confirm(ID, 1)));
    }

    @Test
    void nonDomainFailurePropagatesForRollback() {
        IllegalStateException outage = new IllegalStateException("booking partner outage");
        when(service.confirm(ID, 1)).thenThrow(outage);
        assertEquals(outage, assertThrows(IllegalStateException.class, () -> commands.confirm(ID, 1)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedBookingRollbackSurfacesTheOriginalDomainDecision() {
        when(service.confirm(ID, 1)).thenThrow(
                new ApiException(422, "MIN_NOTICE_NOT_MET", "the slot is inside the minimum notice"));
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            callback.doInTransaction(null);
            throw new UnexpectedRollbackException("marked rollback-only by the booking service");
        });
        assertEquals("MIN_NOTICE_NOT_MET",
                assertThrows(ApiException.class, () -> commands.confirm(ID, 1)).code());
    }

    @Test
    void unexpectedRollbackWithoutADomainDecisionPropagates() {
        UnexpectedRollbackException failure = new UnexpectedRollbackException("rollback-only");
        when(transactions.execute(any(TransactionCallback.class))).thenThrow(failure);
        assertEquals(failure, assertThrows(UnexpectedRollbackException.class,
                () -> commands.confirm(ID, 1)));
    }
}
