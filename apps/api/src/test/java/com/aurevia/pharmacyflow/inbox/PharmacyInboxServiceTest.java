package com.aurevia.pharmacyflow.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.Cursor;
import com.aurevia.audit.AuditAppender;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxItem;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxPage;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxRepository.InboxRow;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.tenant.TenantContext;

/**
 * Unit contract for the pharmacy inbox: validation, authorization, paging and projection.
 *
 * <p>All fixtures are synthetic. The transaction and persistence boundaries are
 * mocked so every denial and page boundary is deterministic; the HTTP integration
 * suite proves the same behavior through the real controller and PostgreSQL.</p>
 */
class PharmacyInboxServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "pharmacy-a";
    private static final String PHARMACY = "pharmacy-x";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CREATED_A = "2026-11-20T09:07:06Z";
    private static final String CREATED_B = "2026-11-20T09:07:05Z";
    private static final String CREATED_C = "2026-11-20T09:07:04Z";

    private PharmacyInboxRepository repository;
    private AuditAppender audit;
    private PharmacyInboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(PharmacyInboxRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new PharmacyInboxService(repository, new PharmacyInboxActions(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "PHARMACY_INBOX", "corr-1"));
        authorized();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("pharmacy_staff"));
        when(repository.assignedPharmacy(ACTOR)).thenReturn(Optional.of(PHARMACY));
    }

    private static InboxItem item(String id, String createdAt, State state) {
        return new InboxItem(id, 1, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, state, "bbbbbbbb-0000-0000-0000-000000000001",
                "clinician-a", PHARMACY, Instant.parse(createdAt));
    }

    private static InboxRow row(String id, String createdAt, State state) {
        return new InboxRow(item(id, createdAt, state), createdAt);
    }

    private ApiException failure(String status, String cursor, String limit) {
        return assertThrows(ApiException.class, () -> service.list(status, cursor, limit));
    }

    @Test
    void absentAndBlankParametersUseDefaults() {
        InboxPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        verify(repository).query(PHARMACY, null, null, null, 51);

        service.list("  ", "  ", "  ");
        verify(repository, times(2)).query(PHARMACY, null, null, null, 51);
    }

    @Test
    void invalidCursorsAndLimitsAreRejectedBeforeAnyQuery() {
        assertEquals("INVALID_CURSOR", failure(null, "not-a-cursor", null).code());
        assertEquals("INVALID_LIMIT", failure(null, null, "0").code());
        verify(repository, never()).query(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void invalidStatusIsRejectedBeforeAnyQuery() {
        ApiException exception = failure("not-a-state", null, null);
        assertEquals(422, exception.status());
        assertEquals("INVALID_STATUS", exception.code());
        verify(repository, never()).query(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void statusFilterIsNormalizedAndPassedToTheRepository() {
        service.list("delivery_confirmed", null, null);
        verify(repository).query(PHARMACY, "delivery_confirmed", null, null, 51);
        service.list("  accepted  ", null, null);
        verify(repository).query(PHARMACY, "accepted", null, null, 51);
    }

    @Test
    void emptyAndExactPagesHaveNoNextCursor() {
        when(repository.query(PHARMACY, null, null, null, 3)).thenReturn(List.of());
        assertNull(service.list(null, null, "2").nextCursor());

        when(repository.query(PHARMACY, null, null, null, 3)).thenReturn(List.of(
                row(ID_A, CREATED_A, State.DELIVERY_CONFIRMED),
                row(ID_B, CREATED_B, State.ACCEPTED)));
        InboxPage page = service.list(null, null, "2");
        assertEquals(List.of(ID_A, ID_B), page.items().stream().map(InboxItem::id).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesTenantBoundCursorFromTheLastReturnedRow() {
        String token = Cursor.encode(new Cursor(ORG, CREATED_A, ID_A));
        when(repository.query(PHARMACY, "delivery_confirmed", CREATED_A, ID_A, 3)).thenReturn(List.of(
                row(ID_A, CREATED_A, State.DELIVERY_CONFIRMED),
                row(ID_B, CREATED_B, State.ACCEPTED),
                row(ID_C, CREATED_C, State.DISPENSED)));
        InboxPage page = service.list("delivery_confirmed", token, "2");
        assertEquals(List.of(ID_A, ID_B), page.items().stream().map(InboxItem::id).toList());
        String expected = Cursor.encode(new Cursor(ORG, CREATED_B, ID_B));
        assertEquals(expected, page.nextCursor());
        verify(audit).append(TenantContext.current(), "PHARMACY_INBOX", "PHARMACY_INBOX_READ",
                "PRESCRIPTION", "PHARMACY_INBOX", PHARMACY, "SUCCESS");
    }

    @Test
    void inactiveMembershipIsDeniedAndAudited() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "PHARMACY_INBOX", "PHARMACY_INBOX_READ",
                "PRESCRIPTION", "PHARMACY_INBOX", "ALL", "DENIED");
        verify(repository, never()).query(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void missingPermissionIsDeniedAndAudited() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "PHARMACY_INBOX", "PHARMACY_INBOX_READ",
                "PRESCRIPTION", "PHARMACY_INBOX", "ALL", "DENIED");
        verify(repository, never()).query(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void missingPharmacyAssignmentIsDeniedAndAudited() {
        when(repository.assignedPharmacy(ACTOR)).thenReturn(Optional.empty());
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "PHARMACY_INBOX", "PHARMACY_INBOX_READ",
                "PRESCRIPTION", "PHARMACY_INBOX", "ALL", "DENIED");
        verify(repository, never()).query(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void malformedAssignmentValueFailsClosed() {
        when(repository.assignedPharmacy(ACTOR)).thenReturn(Optional.of("  bad value  "));
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "PHARMACY_INBOX", "PHARMACY_INBOX_READ",
                "PRESCRIPTION", "PHARMACY_INBOX", "ALL", "DENIED");
        verify(repository, never()).query(anyString(), any(), any(), any(), anyInt());
    }

    @Test
    void getReturnsOnlyRoutedItems() {
        InboxItem expected = item(ID_A, CREATED_A, State.ACCEPTED);
        when(repository.findRouted(ID_A, PHARMACY)).thenReturn(Optional.of(expected));
        assertEquals(expected, service.get(ID_A));
        verify(audit).append(TenantContext.current(), "PHARMACY_INBOX", "PHARMACY_INBOX_READ",
                "PRESCRIPTION", "PHARMACY_INBOX", ID_A, "SUCCESS");
    }

    @Test
    void getConcealsAnInvisiblePrescription() {
        when(repository.findRouted(ID_A, PHARMACY)).thenReturn(Optional.empty());
        ApiException exception = assertThrows(ApiException.class, () -> service.get(ID_A));
        assertEquals(404, exception.status());
        assertEquals("NOT_FOUND", exception.code());
    }

    @Test
    void getRejectsMalformedIdentifiersAndDenials() {
        assertEquals("INVALID_ID", assertThrows(ApiException.class, () -> service.get("bad")).code());
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(ID_A)).status());
        verify(repository, never()).findRouted(anyString(), anyString());
    }

    @Test
    void actionMatrixAllowsOnlyPharmacyStaffReads() {
        PharmacyInboxActions actions = new PharmacyInboxActions();
        assertTrue(actions.allows(PharmacyInboxActions.READ, "pharmacy_staff"));
        assertFalse(actions.allows(PharmacyInboxActions.READ, "clinician"));
        assertFalse(actions.allows("OTHER", "pharmacy_staff"));
    }

    @Test
    void pageFreezesItemsAgainstCallerMutation() {
        List<InboxItem> mutable = new ArrayList<>(List.of(item(ID_A, CREATED_A, State.ACCEPTED)));
        InboxPage page = new InboxPage(mutable, "next");
        mutable.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
        assertNotNull(page.nextCursor());
    }
}
