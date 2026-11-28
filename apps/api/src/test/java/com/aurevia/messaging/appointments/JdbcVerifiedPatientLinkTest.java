package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.tenant.TenantContext;

/** PAT-01 verified-link adapter: the durable fold is re-read inside the tenant transaction. */
class JdbcVerifiedPatientLinkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private NamedParameterJdbcOperations jdbc;
    private JdbcVerifiedPatientLink links;

    /** Bind the tenant and run mocked transaction callbacks immediately. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-1"));
        jdbc = mock(NamedParameterJdbcOperations.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        links = new JdbcVerifiedPatientLink(jdbc, transactions);
    }

    /** Remove the thread-local tenant so one test cannot authenticate another. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void allowsReturnsTrueForAnActiveLinkedPatient() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(true);
        assertTrue(links.allows(SUBJECT, PATIENT));
    }

    @Test
    void allowsReturnsFalseForAnUnlinkedPatient() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(false);
        assertFalse(links.allows(SUBJECT, PATIENT));
    }

    @Test
    void allowsReturnsFalseWhenTheQueryResultIsNull() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(null);
        assertFalse(links.allows(SUBJECT, PATIENT));
    }

    @Test
    void allowsReturnsFalseForAMalformedPatientId() {
        assertFalse(links.allows(SUBJECT, "not-a-uuid"));
    }

    @Test
    void allowsReturnsFalseWhenTheDatabaseRejectsTheLookup() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenThrow(new DataRetrievalFailureException("lookup failed"));
        assertFalse(links.allows(SUBJECT, PATIENT));
    }
}
