package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * XCH-01 clause 2 acceptance: the sending organization authorizes the specific
 * payload, recipient, purpose and duration of a disclosure. A grant that covers
 * all four dimensions permits exactly one release; a mismatch on any one of them
 * is refused before the order, immutable snapshot, disclosure ledger entry and
 * destination work item are written. The fixed clock makes every window decision
 * deterministic and all fixtures are synthetic.
 */
class SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String OTHER_DEST = "66666666-6666-6666-6666-666666666666";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ACTOR = "clinician-a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    /** Builds the real service over mocked boundaries and binds a synthetic tenant. */
    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new OrderService(repository, audit, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-clause-2"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
    }

    /** Clears the request-scoped tenant after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A grant covering every clause-2 dimension for the baseline synthetic request. */
    private static AuthorizationView coveringGrant() {
        return new AuthorizationView(GRANT, ORG, "CBC", DEST, "lab review", 60,
                NOW.minusSeconds(60), NOW.plusSeconds(3600), true);
    }

    /**
     * A grant with no expiry so the per-release duration ceiling is the only
     * duration rule that can refuse the request.
     */
    private static AuthorizationView grantWithoutExpiry() {
        return new AuthorizationView(GRANT, ORG, "CBC", DEST, "lab review", 60,
                NOW.minusSeconds(60), null, true);
    }

    /** The baseline release command, varied per test through its clause dimensions. */
    private static OrderRequest request(String orderCode, String destination, String purpose,
                                        int durationMinutes) {
        return new OrderRequest(PATIENT, "ENC-1", orderCode, "routine", "Synthetic CBC question",
                destination, purpose, GRANT, "dr-a", "lab-queue", durationMinutes);
    }

    /** Asserts a clause-2 refusal and that no disclosure side effect was committed. */
    private void assertRefused(ApiException denial) {
        assertEquals(403, denial.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", denial.code());
        verify(repository).authorization(GRANT);
        verify(repository, never()).patientIdentity(anyString());
        verify(repository, never()).insertOrder(any());
        verify(repository, never()).appendSnapshot(anyString(), anyString(), anyInt(),
                anyString(), anyString());
        verify(repository, never()).appendDisclosure(anyString(), anyString(), anyString(),
                anyString(), anyInt(), any(), anyString());
        verify(repository, never()).insertWorkItem(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(audit).append(any(), eq("ORDER_DISCLOSURE"), eq("ORDER_DISCLOSURE"), eq("ORDER"),
                eq("EXCHANGE"), eq(GRANT), eq("DENIED"));
        verifyNoMoreInteractions(audit);
    }

    /** A matching payload, recipient, purpose and duration permits one stamped disclosure. */
    @Test
    void authorizationCoveringPayloadRecipientPurposeAndDurationPermitsDisclosure() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(coveringGrant()));

        OrderView released = service.create(request("CBC", DEST, "lab review", 30));

        assertNotNull(released);
        assertEquals(DEST, released.destinationOrg());
        assertEquals(GRANT, released.authorizationId());
        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(repository).appendSnapshot(eq(released.snapshotId()), eq(released.id()), eq(1),
                snapshot.capture(), eq(released.snapshotFingerprint()));
        assertTrue(snapshot.getValue().contains("CBC"), "the snapshot carries the authorized payload class");
        assertTrue(snapshot.getValue().contains(DEST), "the snapshot carries the authorized recipient");
        assertTrue(snapshot.getValue().contains("lab review"), "the snapshot carries the authorized purpose");
        assertTrue(snapshot.getValue().contains("30"), "the snapshot carries the authorized duration");
        verify(repository).insertOrder(released);
        verify(repository).appendDisclosure(released.disclosureId(), released.id(), DEST,
                "lab review", 30, NOW.plusSeconds(1800), released.snapshotFingerprint());
        verify(audit).append(any(), eq("EXCHANGE"), eq("ORDER_DISCLOSURE"), eq("ORDER"),
                eq("EXCHANGE"), eq(released.id()), eq("SUCCESS"));
    }

    /** The payload dimension: an order code outside the grant is refused. */
    @Test
    void disclosureIsRefusedWhenOrderCodeIsNotTheAuthorizedPayload() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(coveringGrant()));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.create(request("CMP", DEST, "lab review", 30)));

        assertRefused(denial);
    }

    /** The recipient dimension: a different valid destination organization is refused. */
    @Test
    void disclosureIsRefusedForAnUnauthorizedRecipient() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(coveringGrant()));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.create(request("CBC", OTHER_DEST, "lab review", 30)));

        assertRefused(denial);
    }

    /** The purpose dimension: a purpose outside the grant is refused. */
    @Test
    void disclosureIsRefusedForAnUnauthorizedPurpose() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(coveringGrant()));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.create(request("CBC", DEST, "marketing", 30)));

        assertRefused(denial);
    }

    /** The duration ceiling: a release longer than the authorized maximum is refused. */
    @Test
    void disclosureIsRefusedWhenDurationExceedsTheAuthorizedMaximum() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grantWithoutExpiry()));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.create(request("CBC", DEST, "lab review", 61)));

        assertRefused(denial);
    }

    /** The duration window: a release cannot outlive the grant that authorized it. */
    @Test
    void disclosureIsRefusedWhenItWouldOutliveTheAuthorizedWindow() {
        when(repository.authorization(GRANT)).thenReturn(Optional.of(new AuthorizationView(
                GRANT, ORG, "CBC", DEST, "lab review", 60,
                NOW.minusSeconds(60), NOW.plusSeconds(300), true)));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.create(request("CBC", DEST, "lab review", 30)));

        assertRefused(denial);
    }
}
