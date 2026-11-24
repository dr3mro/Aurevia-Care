package com.aurevia.messaging.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;

/**
 * Deterministic branch and mapping coverage for the PostgreSQL attachment store.
 *
 * <p>The empty-result side of the binding-safe upsert cannot be forced
 * reliably through a real database race, so that boundary is mocked here while
 * the normal paths are proven by {@code JdbcAttachmentStoreIntegrationTest}.
 * Every value is synthetic.</p>
 */
class JdbcAttachmentStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FILE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String EVENT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String CONNECTION = "conv-1";
    private static final String UNIT = "unit-1";
    private static final String PATIENT = "pat-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcAttachmentStore store;
    private boolean grantPresent;
    private boolean attachmentPresent;
    private boolean eventPresent;
    private boolean upsertReturnsRow;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        store = new JdbcAttachmentStore(jdbc, transactions);
        grantPresent = true;
        attachmentPresent = true;
        eventPresent = true;
        upsertReturnsRow = true;
        answerQueries();
    }

    /** Dispatch the row mapper with a synthetic row chosen by SQL purpose. */
    @SuppressWarnings("unchecked")
    private void answerQueries() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("RETURNING id")) {
                        return upsertReturnsRow ? List.of(FILE) : List.of();
                    }
                    if (sql.contains("attachment_event")) {
                        return eventPresent ? List.of(mapper.mapRow(eventRow(), 1)) : List.of();
                    }
                    if (sql.contains("attachment_connection_grant")) {
                        return grantPresent ? List.of(mapper.mapRow(grantRow(), 1)) : List.of();
                    }
                    return attachmentPresent ? List.of(mapper.mapRow(attachmentRow(), 1)) : List.of();
                });
    }

    private static Attachment attachment() {
        return new Attachment(ORG, FILE, 1, "patient-a", CONNECTION, UNIT, PATIENT,
                "notes.pdf", AttachmentContract.FileType.PDF, 128, CHECKSUM,
                "quarantine/" + ORG + "/" + FILE + "/v1", 1,
                AttachmentContract.ScanVerdict.PENDING, AttachmentContract.AttachmentState.QUARANTINED,
                null, null, NOW);
    }

    private static ConnectionGrant grant() {
        return new ConnectionGrant(ORG, CONNECTION, UNIT, PATIENT, true);
    }

    private static AttachmentEvent event() {
        return new AttachmentEvent(EVENT, ORG, FILE, EventType.UPLOADED, "patient-a",
                "success", null, NOW);
    }

    private static ResultSet grantRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("connection_id")).thenReturn(CONNECTION);
        when(row.getString("unit_id")).thenReturn(UNIT);
        when(row.getString("patient_id")).thenReturn(PATIENT);
        when(row.getBoolean("active")).thenReturn(true);
        return row;
    }

    private static ResultSet attachmentRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("id")).thenReturn(FILE);
        when(row.getInt("version")).thenReturn(1);
        when(row.getString("uploader_subject")).thenReturn("patient-a");
        when(row.getString("connection_id")).thenReturn(CONNECTION);
        when(row.getString("unit_id")).thenReturn(UNIT);
        when(row.getString("patient_id")).thenReturn(PATIENT);
        when(row.getString("file_name")).thenReturn("notes.pdf");
        when(row.getString("file_type")).thenReturn("application/pdf");
        when(row.getLong("size_bytes")).thenReturn(128L);
        when(row.getString("checksum")).thenReturn(CHECKSUM);
        when(row.getString("object_key")).thenReturn("quarantine/" + ORG + "/" + FILE + "/v1");
        when(row.getInt("object_version")).thenReturn(1);
        when(row.getString("verdict")).thenReturn("pending");
        when(row.getString("state")).thenReturn("quarantined");
        when(row.getString("message_id")).thenReturn(null);
        when(row.getString("recipient_unit_id")).thenReturn(null);
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    private static ResultSet eventRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("id")).thenReturn(EVENT);
        when(row.getString("attachment_id")).thenReturn(FILE);
        when(row.getString("event_type")).thenReturn("uploaded");
        when(row.getString("actor_subject")).thenReturn("patient-a");
        when(row.getString("outcome")).thenReturn("success");
        when(row.getString("detail")).thenReturn(null);
        when(row.getObject("occurred_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    @Test
    void grantReadsTheMappedRowWhenPresent() {
        assertEquals(grant(), store.grant(ORG, CONNECTION).orElseThrow());
    }

    @Test
    void grantIsEmptyWhenNoRowIsReturned() {
        grantPresent = false;
        assertTrue(store.grant(ORG, CONNECTION).isEmpty());
    }

    @Test
    void putGrantWritesTheGrant() {
        store.putGrant(grant());
        org.mockito.Mockito.verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void attachmentReadsTheMappedRowWhenPresent() {
        assertEquals(attachment(), store.attachment(ORG, FILE).orElseThrow());
    }

    @Test
    void attachmentIsEmptyWhenNoRowIsReturned() {
        attachmentPresent = false;
        assertTrue(store.attachment(ORG, FILE).isEmpty());
    }

    @Test
    void putAttachmentWritesWhenTheDraftBindingMatches() {
        store.putAttachment(attachment());
        org.mockito.Mockito.verify(jdbc).query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Object>>any());
    }

    @Test
    void putAttachmentRefusesRebindingWhenNoRowIsReturned() {
        upsertReturnsRow = false;
        ApiException failure = assertThrows(ApiException.class, () -> store.putAttachment(attachment()));
        assertEquals(409, failure.status());
        assertEquals("WRONG_CONVERSATION", failure.code());
    }

    @Test
    void attachmentsReturnsTheMappedSnapshotOrAnEmptyList() {
        assertEquals(List.of(attachment()), store.attachments(ORG));
        attachmentPresent = false;
        assertTrue(store.attachments(ORG).isEmpty());
    }

    @Test
    void appendEventWritesTheFact() {
        store.appendEvent(event());
        org.mockito.Mockito.verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void eventsReturnTheMappedTrailOrAnEmptyList() {
        assertEquals(List.of(event()), store.events(ORG));
        eventPresent = false;
        assertTrue(store.events(ORG).isEmpty());
        assertFalse(store.events(ORG).stream().findAny().isPresent());
    }
}
