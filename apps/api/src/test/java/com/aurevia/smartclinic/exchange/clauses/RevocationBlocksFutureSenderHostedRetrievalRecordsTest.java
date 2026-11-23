package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands.RevokeRequest;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseRepository;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseService;
import com.aurevia.smartclinic.exchange.response.ExchangeState;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Per-clause proof of XCH-03 revocation: it ends sender-hosted retrieval and records exactly
 * one immutable downstream notification (XCH-03). Every write is made under the caller's
 * tenant and actor, and a caller without the revoke permission writes no notification.
 *
 * <p>The service is driven with the same synthetic Mockito fixtures as
 * {@code com.aurevia.smartclinic.it.exchange.response.ExchangeResponseIntegrityTest}; no
 * production code, patient data or real identifier is used.</p>
 */
class RevocationBlocksFutureSenderHostedRetrievalRecordsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-23T00:00:00Z");
    private static final String REASON = "disclosure revoked";
    private static final String RETENTION = "LEGAL_HOLD";
    private static final String TARGET = "partner-lab";
    private static final String CHANNEL = "secure-email";

    private ExchangeResponseRepository repository;
    private ExchangeResponseService service;

    /** Build a fresh service over mocked boundaries and bind a synthetic clinician tenant. */
    @BeforeEach
    void setUp() {
        repository = mock(ExchangeResponseRepository.class);
        AuditAppender audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ExchangeResponseService(repository, new ExchangeResponseActionPolicy(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-c04"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        // A mocked int defaults to 0; run() would read that as a lost compare-and-set and
        // answer 412 before any assertion in these tests can run.
        when(repository.update(anyString(), anyInt(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(1);
        // run() returns the freshly read projection rather than the in-memory decision.
        when(repository.find(ORDER)).thenReturn(Optional.of(revoked(3)));
    }

    /** Always release the tenant-bound thread local, even when a test fails. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A revocation ends retrieval and emits one notification under the caller's tenant. */
    @Test
    void revokedReleaseBlocksLaterRetrievalAndRecordsOneDownstreamNotification() {
        // Revoke locks the requested order; the later retrieve must re-read and see REVOKED.
        when(repository.findForUpdate(ORDER))
                .thenReturn(Optional.of(requested()), Optional.of(revoked(2)));
        // Fault detector: with a released revision available, a missing REVOKED guard in
        // retrieve would return the revision instead of raising 410.
        when(repository.currentRevision(ORDER, 1))
                .thenReturn(Optional.of(new Revision("revision-1", 1, null, CHECKSUM, null, ACTOR, OCCURRED_AT)));

        ExchangeOrder result = service.revoke(ORDER, 1,
                new RevokeRequest(REASON, RETENTION, TARGET, CHANNEL, "event-c04-1"));

        // Assert the state sent to the guarded write, independently of the find() stub below.
        verify(repository).update(eq(ORDER), eq(1), eq(ExchangeState.REVOKED),
                any(), any(), any(), any(), any(), any(), eq(1));
        assertEquals(ExchangeState.REVOKED, result.state());
        verify(repository, times(1)).insertNotification(eq(ORG), anyString(), eq(ORDER), eq("event-c04-1"),
                eq(TARGET), eq(CHANNEL), eq("NOTIFIED"), eq(ACTOR));

        ApiException blocked = assertThrows(ApiException.class, () -> service.retrieve(ORDER));
        assertEquals(410, blocked.status());
        assertEquals("RETRIEVAL_REVOKED", blocked.code());
        // Revoke locks the row once and retrieve must lock it again: no cached-view bypass.
        verify(repository, times(2)).findForUpdate(ORDER);
    }

    /** A recipient-only role is refused revocation and therefore writes no notification. */
    @Test
    void recipientRoleCannotRevokeSoNoNotificationIsRecorded() {
        // lab_verifier may retrieve but is not in REVOKE_ROLES; authorization denies before
        // the locked read, so no downstream write can happen.
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_verifier"));

        ApiException denial = assertThrows(ApiException.class, () -> service.revoke(ORDER, 1,
                new RevokeRequest(REASON, RETENTION, TARGET, CHANNEL, "event-c04-2")));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).insertNotification(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** A stale version is refused revocation and therefore writes no notification. */
    @Test
    void staleVersionCannotRevokeSoNoNotificationIsRecorded() {
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(requested()));

        ApiException stale = assertThrows(ApiException.class, () -> service.revoke(ORDER, 2,
                new RevokeRequest(REASON, RETENTION, TARGET, CHANNEL, "event-c04-3")));

        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());
        verify(repository, never()).insertNotification(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static ExchangeOrder requested() {
        return new ExchangeOrder(ORDER, 1, "LAB-REQ-1", ExchangeState.REQUESTED, 1, CHECKSUM,
                null, null, null, null, null, null);
    }

    private static ExchangeOrder revoked(int version) {
        return new ExchangeOrder(ORDER, version, "LAB-REQ-1", ExchangeState.REVOKED, 1, CHECKSUM,
                null, null, REASON, null, null, RETENTION);
    }
}
