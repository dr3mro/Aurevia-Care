package com.aurevia.it.exchange.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.order.AuthorizationView;
import com.aurevia.exchange.order.OrderRepository;
import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.order.OrderService;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.tenant.TenantContext;

/**
 * S-G2-01 regression: XCH-01 requires the sender to authorize the release duration.
 * A disclosure whose validity extends past the authorization's own window would
 * keep sharing content after the sender's permission has lapsed, so it must be
 * refused. All fixtures are synthetic.
 */
class OrderAuthorizationWindowTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ACTOR = "clinician-a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private OrderRepository repository;
    private OrderService service;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        AuditAppender audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        service = new OrderService(repository, audit, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-window"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        when(repository.patientIdentity(PATIENT)).thenReturn(
                Optional.of(new OrderRepository.PatientIdentity("MRN-1", "CLINIC")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static OrderRequest request(int durationMinutes) {
        return new OrderRequest(PATIENT, "ENC-1", "CBC", "routine", "Synthetic CBC question",
                DEST, "lab review", GRANT, "dr-a", "lab-queue", durationMinutes);
    }

    private static AuthorizationView grantExpiringAt(Instant expiresAt) {
        return new AuthorizationView(GRANT, ORG, "CBC", DEST, "lab review", 60,
                NOW.minusSeconds(60), expiresAt, true);
    }

    @Test
    void aDisclosureCannotOutliveTheAuthorizationWindow() {
        // The grant is still effective at release time but expires in 5 minutes,
        // while the requested release is valid for 30 minutes.
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grantExpiringAt(NOW.plusSeconds(300))));

        ApiException denial = assertThrows(ApiException.class, () -> service.create(request(30)));

        assertEquals(403, denial.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", denial.code());
        verify(repository, never()).insertOrder(any());
        verify(repository, never()).appendDisclosure(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), any(), anyString());
    }

    @Test
    void aDurationAboveTheAuthorizedMaximumIsRejectedEvenWithoutAGrantExpiry() {
        // With no grant expiry the window check is skipped, so the independent
        // per-release duration ceiling must still refuse an over-long release.
        when(repository.authorization(GRANT)).thenReturn(Optional.of(new AuthorizationView(
                GRANT, ORG, "CBC", DEST, "lab review", 60, NOW.minusSeconds(60), null, true)));

        ApiException denial = assertThrows(ApiException.class, () -> service.create(request(61)));

        assertEquals(403, denial.status());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", denial.code());
        verify(repository, never()).insertOrder(any());
    }

    @Test
    void aDisclosureEndingExactlyAtTheAuthorizationExpiryIsAllowed() {
        // Boundary: a 5-minute release under a grant that expires in 5 minutes
        // does not extend past the authorization and remains permitted.
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grantExpiringAt(NOW.plusSeconds(300))));

        OrderView released = service.create(request(5));

        assertNotNull(released);
        verify(repository).insertOrder(released);
        verify(repository).appendDisclosure(eq(released.disclosureId()), eq(released.id()), eq(DEST),
                eq("lab review"), eq(5), eq(NOW.plusSeconds(300)), eq(released.snapshotFingerprint()));
    }

    @Test
    void theRecordedDisclosureExpiryUsesTheSameInstantThatAuthorizedTheWindow() {
        // A live clock advances between reads. The grant window check and the
        // disclosure expiry stamp must share one instant: if the check uses an
        // earlier read than the stamp, a release that exactly fits the grant at
        // check time is recorded as outliving it (XCH-01 duration fail-open).
        AtomicInteger reads = new AtomicInteger();
        Clock advancing = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return reads.getAndIncrement() == 0 ? NOW : NOW.plusSeconds(1);
            }
        };
        OrderService advancingService =
                new OrderService(repository, mock(AuditAppender.class), transactions, advancing);
        when(repository.authorization(GRANT)).thenReturn(Optional.of(grantExpiringAt(NOW.plusSeconds(300))));

        OrderView released = advancingService.create(request(5));

        assertNotNull(released);
        // The grant expires at NOW+300s; the 5-minute release fits it exactly when
        // checked at NOW and must not be stamped one second past it.
        verify(repository).appendDisclosure(eq(released.disclosureId()), eq(released.id()), eq(DEST),
                eq("lab review"), eq(5), eq(NOW.plusSeconds(300)), eq(released.snapshotFingerprint()));
    }
}
