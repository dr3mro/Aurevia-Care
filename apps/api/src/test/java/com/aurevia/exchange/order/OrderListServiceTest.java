package com.aurevia.exchange.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/** ARC-03 cursor paging on the order list: validation, authorization and page boundaries. */
class OrderListServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String DEST = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String SNAPSHOT = "66666666-6666-6666-6666-666666666666";
    private static final String FINGERPRINT = "77777777-7777-7777-7777-777777777777";
    private static final String DISCLOSURE = "88888888-8888-8888-8888-888888888888";
    private static final String WORK = "99999999-9999-9999-9999-999999999999";
    private static final String ACTOR = "clinician-a";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant TIME_A = Instant.parse("2026-11-20T09:07:06Z");
    private static final Instant TIME_B = Instant.parse("2026-11-20T09:07:05Z");
    private static final Instant TIME_C = Instant.parse("2026-11-20T09:07:04Z");

    private OrderRepository repository;
    private AuditAppender audit;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new OrderService(repository, audit, transactions,
                Clock.fixed(TIME_A, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-1"));
        authorized();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
    }

    private static OrderView row(String id, Instant createdAt) {
        return new OrderView(id, 1, PATIENT, "ENC-1", "CBC", "routine", "Synthetic question",
                DEST, ACTOR, "dr-a", "lab-queue", GRANT, SNAPSHOT, FINGERPRINT, DISCLOSURE, WORK,
                createdAt);
    }

    private ApiException failure(String cursor, String limit, String status) {
        return assertThrows(ApiException.class, () -> service.list(cursor, limit, status));
    }

    @Test
    void absentAndBlankParametersUseDefaults() {
        OrderPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        // default limit is 50, so the repository fetches 51 rows.
        verify(repository).query(null, null, null, 51);

        service.list("  ", "  ", "  ");
        verify(repository, times(2)).query(null, null, null, 51);
    }

    @Test
    void invalidCursorsAreRejectedBeforeAnyQuery() {
        List<String> malformed = List.of(
                "plain",
                "a:b:c",
                ":" + ID_A,
                TIME_A + ":",
                "not-a-time:" + ID_A,
                "BAD:" + ID_A,
                TIME_A + ":not-a-uuid",
                "2026-11-20T09:07:06.1Z:" + ID_A);
        for (String cursor : malformed) {
            ApiException exception = failure(cursor, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_CURSOR", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void invalidLimitsAreRejectedBeforeAnyQuery() {
        for (String limit : List.of("0", "201", "abc")) {
            ApiException exception = failure(null, limit, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_LIMIT", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void invalidStatusesAreRejectedBeforeAnyQuery() {
        for (String status : List.of("all", "READY", "unknown")) {
            ApiException exception = failure(null, null, status);
            assertEquals(422, exception.status());
            assertEquals("INVALID_STATUS", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void statusFiltersArePassedThroughToTheRepository() {
        service.list(null, null, "awaiting_local_patient");
        verify(repository).query(null, null, "awaiting_local_patient", 51);
        service.list(null, null, "ready");
        verify(repository).query(null, null, "ready", 51);
    }

    @Test
    void emptyPageHasNoNextCursor() {
        when(repository.query(null, null, null, 51)).thenReturn(List.of());
        OrderPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactlyLimitRowsHaveNoNextCursor() {
        when(repository.query(null, null, null, 3)).thenReturn(List.of(row(ID_A, TIME_A), row(ID_B, TIME_B)));
        OrderPage page = service.list(null, "2", null);
        assertEquals(List.of(ID_A, ID_B), page.items().stream().map(OrderView::id).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesCursorFromTheLastReturnedRow() {
        String cursor = TIME_A + ":" + ID_A;
        when(repository.query(TIME_A.toString(), ID_A, null, 3)).thenReturn(
                List.of(row(ID_A, TIME_A), row(ID_B, TIME_B), row(ID_C, TIME_C)));
        OrderPage page = service.list(cursor, "2", null);
        assertEquals(List.of(ID_A, ID_B), page.items().stream().map(OrderView::id).toList());
        assertEquals(TIME_B + ":" + ID_B, page.nextCursor());
        verify(audit).append(TenantContext.current(), "EXCHANGE", "ORDER_READ", "ORDER",
                "EXCHANGE", "ALL", "SUCCESS");
    }

    @Test
    void inactiveMembershipIsDeniedAndAudited() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "ORDER_READ", "ORDER_READ", "ORDER",
                "EXCHANGE", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void missingPermissionIsDeniedAndAudited() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "ORDER_READ", "ORDER_READ", "ORDER",
                "EXCHANGE", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void pageFreezesItsItemsAgainstCallerMutation() {
        List<OrderView> mutable = new ArrayList<>(List.of(row(ID_A, TIME_A)));
        OrderPage page = new OrderPage(mutable, TIME_A + ":" + ID_A);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }
}
