package com.aurevia.it.messagingconversations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.aurevia.messaging.conversations.ConversationRepository;

/**
 * Deterministic branch proof for the durable message insert.
 *
 * <p>The JDBC extractor's empty-result side is the concurrent-retry path: an
 * {@code ON CONFLICT DO NOTHING RETURNING} insert returns no row when another
 * request already owns the client id. The mocked query executes the real
 * extractor against a synthetic result set, so both sides are genuinely covered.
 * No database or patient data is used.</p>
 */
@ExtendWith(MockitoExtension.class)
class ConversationRepositoryTest {

    private static final String CONVERSATION = "33333333-3333-3333-3333-333333333333";
    private static final String MESSAGE = "44444444-4444-4444-4444-444444444444";

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private ResultSet results;

    private boolean insert() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<ResultSetExtractor<String>>any()))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<String> extractor = invocation.getArgument(2);
                    return extractor.extractData(results);
                });
        return new ConversationRepository(jdbc).insertMessageIfAbsent(
                CONVERSATION, 2, "staff", "lab-a", "lab_verifier", "hello", "reply-key");
    }

    @Test
    void insertMessageIfAbsentReturnsFalseWhenReturningFindsNoRow() throws SQLException {
        when(results.next()).thenReturn(false);

        assertFalse(insert());
    }

    @Test
    void insertMessageIfAbsentReturnsTrueWhenReturningFindsTheInsertedRow() throws SQLException {
        when(results.next()).thenReturn(true);
        when(results.getString(1)).thenReturn(MESSAGE);

        assertTrue(insert());
    }
}
