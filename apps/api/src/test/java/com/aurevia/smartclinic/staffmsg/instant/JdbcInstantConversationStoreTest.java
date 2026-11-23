package com.aurevia.smartclinic.staffmsg.instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Unit verification of the durable cursor adapter's JDBC boundary.
 *
 * <p>Mocked collaborators drive the no-op conflict side and the empty aggregate
 * side deterministically; the real-PostgreSQL integration test proves the same
 * behavior through the HTTP entry point.</p>
 */
class JdbcInstantConversationStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CONVERSATION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUBJECT = "nurse-b";

    private NamedParameterJdbcTemplate jdbc;
    private JdbcInstantConversationStore store;

    /** Wire a mocked JDBC template with a passthrough tenant transaction. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        store = new JdbcInstantConversationStore(jdbc, transactions);
        TenantContext.set(new TenantContext(ORG, "nurse-a", TenantContext.PURPOSE, "corr-cursor"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A stale acknowledgement loses the conflict and changes nothing. */
    @Test
    void markReadIgnoresAZeroRowConflictUpdate() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

        assertDoesNotThrow(() -> store.markRead(ORG, CONVERSATION, SUBJECT, 2L));

        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(2L, params.getValue().getValue("upToSequence"));
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals(SUBJECT, params.getValue().getValue("subject"));
    }

    /** An empty cursor aggregate reads as zero rather than throwing. */
    @Test
    void readSequenceReturnsZeroWhenNoCursorIsStored() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);

        assertEquals(0L, store.readSequence(ORG, CONVERSATION, SUBJECT));
    }

    /** A stored cursor is read back verbatim through the JDBC boundary. */
    @Test
    void readSequenceReturnsTheStoredCursor() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(7L);

        assertEquals(7L, store.readSequence(ORG, CONVERSATION, SUBJECT));
    }
}
