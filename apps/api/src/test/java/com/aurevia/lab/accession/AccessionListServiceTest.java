package com.aurevia.lab.accession;

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

import java.time.Instant;
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
import com.aurevia.lab.accession.LabAccessionRepository.AccessionRow;
import com.aurevia.tenant.TenantContext;

/** ARC-03 cursor paging on the lab accession list: validation, authorization and page boundaries. */
class AccessionListServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ACTOR = "lab-a";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CREATED_A = "2026-11-20T09:07:06Z";
    private static final String CREATED_B = "2026-11-20T09:07:05Z";
    private static final String CREATED_C = "2026-11-20T09:07:04Z";

    private LabAccessionRepository repository;
    private AuditAppender audit;
    private LabAccessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(LabAccessionRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new LabAccessionService(repository, new LabActionPolicy(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "LAB_ACCESSION", "corr-1"));
        authorized();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_collector"));
    }

    private static AccessionRow row(String id, String createdAt, AccessionState state) {
        LabAccession accession = new LabAccession(id, 1, "ACC-" + id.substring(0, 1), "LAB-A", PATIENT,
                "ORD-SYN", state, null, null, null, null, null, null, null, null, null);
        return new AccessionRow(accession, createdAt);
    }

    private ApiException failure(String cursor, String limit, String state) {
        return assertThrows(ApiException.class, () -> service.list(cursor, limit, state));
    }

    @Test
    void absentAndBlankParametersUseDefaults() {
        AccessionPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        // Default limit is 50, so the repository fetches 51 rows.
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
                CREATED_A + ":",
                "not-a-time:" + ID_A,
                "BAD:" + ID_A,
                CREATED_A + ":not-a-uuid",
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
    void invalidStatesAreRejectedBeforeAnyQuery() {
        for (String state : List.of("all", "RECEIVED", "unknown")) {
            ApiException exception = failure(null, null, state);
            assertEquals(422, exception.status());
            assertEquals("INVALID_STATE", exception.code());
        }
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void stateFiltersArePassedThroughToTheRepository() {
        for (AccessionState state : AccessionState.values()) {
            service.list(null, null, state.wire());
            verify(repository).query(null, null, state.wire(), 51);
        }
    }

    @Test
    void emptyPageHasNoNextCursor() {
        when(repository.query(null, null, null, 51)).thenReturn(List.of());
        AccessionPage page = service.list(null, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void exactlyLimitRowsHaveNoNextCursor() {
        when(repository.query(null, null, null, 3)).thenReturn(List.of(
                row(ID_A, CREATED_A, AccessionState.ACCESSIONED),
                row(ID_B, CREATED_B, AccessionState.COLLECTED)));
        AccessionPage page = service.list(null, "2", null);
        assertEquals(List.of(ID_A, ID_B),
                page.items().stream().map(item -> item.accession().id()).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesCursorFromTheLastReturnedRow() {
        String cursor = CREATED_A + ":" + ID_A;
        when(repository.query(CREATED_A, ID_A, null, 3)).thenReturn(List.of(
                row(ID_A, CREATED_A, AccessionState.ACCESSIONED),
                row(ID_B, CREATED_B, AccessionState.COLLECTED),
                row(ID_C, CREATED_C, AccessionState.RECEIVED)));
        AccessionPage page = service.list(cursor, "2", null);
        assertEquals(List.of(ID_A, ID_B),
                page.items().stream().map(item -> item.accession().id()).toList());
        assertEquals(CREATED_B + ":" + ID_B, page.nextCursor());
        verify(audit).append(TenantContext.current(), "LAB_ACCESSION", "LAB_ACCESSION_READ", "ACCESSION",
                "LAB", "ALL", "SUCCESS");
    }

    @Test
    void inactiveMembershipIsDeniedAndAudited() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "LAB_ACCESSION", "LAB_ACCESSION_READ", "ACCESSION",
                "LAB", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void missingPermissionIsDeniedAndAudited() {
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        ApiException exception = failure(null, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), "LAB_ACCESSION", "LAB_ACCESSION_READ", "ACCESSION",
                "LAB", "ALL", "DENIED");
        verify(repository, never()).query(any(), any(), any(), anyInt());
    }

    @Test
    void pageFreezesItsItemsAgainstCallerMutation() {
        List<AccessionView> mutable = new ArrayList<>(
                List.of(new AccessionView(row(ID_A, CREATED_A, AccessionState.ACCESSIONED).accession(),
                        List.of())));
        AccessionPage page = new AccessionPage(mutable, CREATED_A + ":" + ID_A);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());
    }
}
