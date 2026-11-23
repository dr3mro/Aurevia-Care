package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.order.AuthorizationView;
import com.aurevia.smartclinic.exchange.order.OrderRepository;
import com.aurevia.smartclinic.exchange.order.OrderRequest;
import com.aurevia.smartclinic.exchange.order.OrderService;
import com.aurevia.smartclinic.exchange.order.OrderView;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * XCH-01 clause 3 proof: order release snapshots the exact released content,
 * stamps fresh identifiers and version one, and records one bounded disclosure.
 *
 * <p>The positive cases drive the real
 * {@link com.aurevia.smartclinic.exchange.order.OrderService} release command under
 * a fixed clock and assert the immutable snapshot, the stamped identifiers and the
 * disclosure that shares the snapshot fingerprint. The negative cases show that a
 * caller without the exchange role, without active membership or without a valid
 * authorization cannot write any of the four records. Data is synthetic and the
 * fixtures mirror the neighbouring exchange-order service tests.</p>
 */
class SnapshotReleasedContentStampIdentifiersVersionsRecordTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ACTOR = "clinician-a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    /**
     * Canonical released-content projection, written out independently of production
     * so a change to the snapshot rule changes the fingerprint assertion.
     */
    private static final String RELEASED_CONTENT = String.join("\u001f",
            "v1", "CBC", PATIENT, "ENC-1", "routine", "Synthetic CBC question", DEST,
            "lab review", "dr-a", "lab-queue", "30");

    /** Fingerprint of the canonical released content under the released-content algorithm. */
    private static final String EXPECTED_FINGERPRINT = "2c7b7a47-6c79-3629-9d1d-a7351ebf66b8";

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new OrderService(repository, audit, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-clause"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Give the acting subject active membership and one exchange role. */
    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** Set up every read a fully authorized release needs before any write. */
    private void setupSuccessfulRelease() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
    }

    /** A valid, active authorization covering the synthetic release. */
    private static AuthorizationView grant() {
        return new AuthorizationView(GRANT, ORG, "CBC", DEST, "lab review", 60,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), true);
    }

    /** Standard synthetic release command. */
    private static OrderRequest request() {
        return new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "Synthetic CBC question",
                DEST, "lab review", GRANT, "dr-a", "lab-queue", 30);
    }

    /** Assert the command failed with a machine-readable API error. */
    private static ApiException refusal(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that no released order, snapshot, disclosure or work item was written. */
    private void verifyNoReleaseWrites() {
        verify(repository, never()).insertOrder(any());
        verify(repository, never()).appendSnapshot(anyString(), anyString(), anyInt(),
                anyString(), anyString());
        verify(repository, never()).appendDisclosure(anyString(), anyString(), anyString(),
                anyString(), anyInt(), any(), anyString());
        verify(repository, never()).insertWorkItem(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    /** Assert the release refusal was recorded before the error was returned. */
    private void verifyDeniedAudit() {
        verify(audit).append(any(), eq("ORDER_DISCLOSURE"), eq("ORDER_DISCLOSURE"), eq("ORDER"),
                eq("EXCHANGE"), eq(GRANT), eq("DENIED"));
    }

    @Test
    void releaseStampsVersionOneFreshIdentifiersSnapshotAndDisclosure() {
        setupSuccessfulRelease();

        OrderView released = service.create(request());

        assertEquals(1, released.version());
        assertEquals(PATIENT, released.patientId());
        assertEquals(DEST, released.destinationOrg());
        assertEquals(GRANT, released.authorizationId());
        assertEquals(ACTOR, released.sourceActor());
        assertEquals(NOW, released.createdAt());
        assertEquals(EXPECTED_FINGERPRINT, released.snapshotFingerprint());
        assertNotNull(released.id());
        assertNotNull(released.snapshotId());
        assertNotNull(released.disclosureId());
        assertNotNull(released.workItemId());
        assertNotEquals(released.id(), released.snapshotId());
        assertNotEquals(released.id(), released.disclosureId());
        assertNotEquals(released.id(), released.workItemId());
        assertNotEquals(released.snapshotId(), released.disclosureId());
        assertNotEquals(released.snapshotId(), released.workItemId());
        assertNotEquals(released.disclosureId(), released.workItemId());

        verify(repository).insertOrder(released);
        verify(repository).appendSnapshot(released.snapshotId(), released.id(), 1,
                RELEASED_CONTENT, EXPECTED_FINGERPRINT);
        verify(repository).appendDisclosure(released.disclosureId(), released.id(), DEST,
                "lab review", 30, NOW.plusSeconds(1800), EXPECTED_FINGERPRINT);
        verify(repository).insertWorkItem(released.workItemId(), DEST, GRANT, released.id(),
                PATIENT, "MRN-1", "CLINIC", "routine", "Synthetic CBC question", "lab-queue",
                EXPECTED_FINGERPRINT);
        verify(audit).append(any(), eq("EXCHANGE"), eq("ORDER_DISCLOSURE"), eq("ORDER"),
                eq("EXCHANGE"), eq(released.id()), eq("SUCCESS"));
    }

    @Test
    void everyReleaseOfTheSameContentStampsFreshIdentifiers() {
        setupSuccessfulRelease();

        OrderView first = service.create(request());
        OrderView second = service.create(request());

        assertEquals(EXPECTED_FINGERPRINT, first.snapshotFingerprint());
        assertEquals(first.snapshotFingerprint(), second.snapshotFingerprint());
        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertNotEquals(first.disclosureId(), second.disclosureId());
        assertNotEquals(first.workItemId(), second.workItemId());
    }

    @Test
    void wrongRoleRefusesTheReleaseAndWritesNoSnapshotOrDisclosure() {
        authorized("reception");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grant()));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));

        ApiException denial = refusal(() -> service.create(request()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verifyNoReleaseWrites();
        verifyDeniedAudit();
    }

    @Test
    void noActiveMembershipRefusesTheReleaseAndWritesNoSnapshotOrDisclosure() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException denial = refusal(() -> service.create(request()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verifyNoReleaseWrites();
        verifyDeniedAudit();
    }

    @Test
    void unauthorizedGrantRefusesTheReleaseAndWritesNoSnapshotOrDisclosure() {
        authorized("clinician");
        AuthorizationView limited = new AuthorizationView(GRANT, ORG, "CBC", DEST, "lab review",
                15, NOW.minusSeconds(60), NOW.plusSeconds(3600), true);
        when(repository.authorization(GRANT)).thenReturn(Optional.of(limited));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));

        ApiException denial = refusal(() -> service.create(request()));

        assertEquals(403, denial.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", denial.code());
        verifyNoReleaseWrites();
        verifyDeniedAudit();
    }

    @Test
    void expiredGrantRefusesTheReleaseAndWritesNoSnapshotOrDisclosure() {
        authorized("clinician");
        when(repository.authorization(GRANT)).thenReturn(Optional.of(new AuthorizationView(
                GRANT, ORG, "CBC", DEST, "lab review", 60, NOW.minusSeconds(120), NOW, true)));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));

        ApiException denial = refusal(() -> service.create(request()));

        assertEquals(403, denial.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", denial.code());
        verifyNoReleaseWrites();
        verifyDeniedAudit();
    }
}
