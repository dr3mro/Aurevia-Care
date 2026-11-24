package com.aurevia.it.exchange.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.response.ExchangeState;
import com.aurevia.exchange.response.ExchangeResponseActionPolicy;
import com.aurevia.exchange.response.ExchangeResponseCommands.AcceptRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeResponseRepository;
import com.aurevia.exchange.response.ExchangeResponseService;
import com.aurevia.tenant.TenantContext;

/**
 * Regression for the integrity-failure boundary of XCH-03 acceptance (XCH-03, ARC-03, ARC-04).
 *
 * <p>A locally verified patient association is a database invariant enforced by a
 * tenant-scoped foreign key. A syntactically valid but unresolved identifier must be
 * concealed as not found, never surfaced as an unhandled 500. All fixtures are synthetic.</p>
 */
class ExchangeResponseIntegrityTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "lab-verifier-a";
    private static final String ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CHECKSUM = "a".repeat(64);

    private ExchangeResponseRepository repository;
    private ExchangeResponseService service;

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
        TenantContext.set(new TenantContext(ORG, ACTOR, "EXCHANGE", "corr-1"));
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_verifier"));
        when(repository.findForUpdate(ORDER)).thenReturn(Optional.of(new ExchangeOrder(ORDER, 1,
                "LAB-REQ-1", ExchangeState.REQUESTED, 1, CHECKSUM, null, null, null, null, null, null)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anUnresolvableLocalPatientIsConcealedInsteadOfFailingWith500() {
        doThrow(integrityFailure("23503"))
                .when(repository).update(anyString(), anyInt(), any(), any(), any(), any(), any(),
                        any(), any(), anyInt());

        ApiException failure = assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1,
                        new AcceptRequest(PATIENT, "clinician-a", "RESULT_REVIEW", "STANDARD")));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
    }

    @Test
    void anotherIntegrityFailureBecomesAConflict() {
        doThrow(integrityFailure("23514"))
                .when(repository).update(anyString(), anyInt(), any(), any(), any(), any(), any(),
                        any(), any(), anyInt());

        ApiException failure = assertThrows(ApiException.class,
                () -> service.accept(ORDER, 1,
                        new AcceptRequest(PATIENT, "clinician-a", "RESULT_REVIEW", "STANDARD")));

        assertEquals(409, failure.status());
        assertEquals("CONFLICT", failure.code());
    }

    private static DataIntegrityViolationException integrityFailure(String sqlState) {
        return new DataIntegrityViolationException("integrity failure",
                new SQLException("constraint", sqlState, 23503));
    }
}
