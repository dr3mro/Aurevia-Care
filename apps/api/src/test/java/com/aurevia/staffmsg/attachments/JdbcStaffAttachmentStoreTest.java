package com.aurevia.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.ArtifactState;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.documents.DocumentContract.ScanVerdict;
import com.aurevia.documents.DocumentStore;
import com.aurevia.documents.storage.ObjectKey;
import com.aurevia.documents.storage.ObjectStorePort;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * Unit verification of the durable staff-attachment store (MSG-03, CLN-07).
 * Collaborators are mocked so each SQL and mapping branch is driven directly.
 */
class JdbcStaffAttachmentStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private DocumentStore documents;
    private ObjectStorePort objects;
    private JdbcStaffAttachmentStore store;

    /** Wire mocked collaborators with a passthrough transaction template. */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        documents = mock(DocumentStore.class);
        objects = mock(ObjectStorePort.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            ((Consumer<Object>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        store = new JdbcStaffAttachmentStore(jdbc, transactions, documents, objects);
    }

    private static StaffAttachment attachment(ScanState scanState, boolean sent) {
        return new StaffAttachment(ORG, "11111111-1111-1111-1111-111111111111", "nurse-a",
                "draft-1", "22222222-2222-2222-2222-222222222222", "note.pdf", "application/pdf",
                12L, "checksum", "quarantine/org/doc/v1", 1, scanState,
                sent ? "message-1" : null, sent, AT);
    }

    private static Artifact artifact(ArtifactState state, MediaType type) {
        return new Artifact(ORG, "22222222-2222-2222-2222-222222222222", 1, "nurse-a", "draft-1",
                null, "note.pdf", type, 12L, "checksum", "object-key", 1,
                ScanVerdict.PENDING, state, AT);
    }

    @SuppressWarnings("unchecked")
    private void stubLinkRows(List<String> states) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(2);
                    return states.stream().map(state -> {
                        ResultSet resultSet = mock(ResultSet.class);
                        try {
                            when(resultSet.getString("organization_id")).thenReturn(ORG);
                            when(resultSet.getString("id")).thenReturn("11111111-1111-1111-1111-111111111111");
                            when(resultSet.getString("message_id")).thenReturn("message-1");
                            when(resultSet.getString("document_id"))
                                    .thenReturn("22222222-2222-2222-2222-222222222222");
                            when(resultSet.getString("checksum")).thenReturn("checksum");
                            when(resultSet.getString("scan_state")).thenReturn(state);
                            when(resultSet.getObject("created_at", OffsetDateTime.class))
                                    .thenReturn(OffsetDateTime.ofInstant(AT, ZoneOffset.UTC));
                            return mapper.mapRow(resultSet, 0);
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    }).toList();
                });
    }

    @Test
    void attachmentComposesLinkAndMetadataForSentAndDraftFiles() {
        stubLinkRows(List.of("clean"));
        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222"))
                .thenReturn(Optional.of(artifact(ArtifactState.COMPLETED, MediaType.PDF)));
        StaffAttachment sent = store.attachment(ORG, "11111111-1111-1111-1111-111111111111").orElseThrow();
        assertTrue(sent.sent());
        assertEquals("message-1", sent.messageId());

        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222"))
                .thenReturn(Optional.of(artifact(ArtifactState.QUARANTINED, MediaType.PDF)));
        StaffAttachment draft = store.attachment(ORG, "11111111-1111-1111-1111-111111111111").orElseThrow();
        assertFalse(draft.sent());
        assertEquals(null, draft.messageId());
    }

    @Test
    void attachmentReturnsEmptyForUnknownLinkOrMissingMetadata() {
        stubLinkRows(List.of());
        assertTrue(store.attachment(ORG, "11111111-1111-1111-1111-111111111111").isEmpty());

        stubLinkRows(List.of("pending"));
        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222")).thenReturn(Optional.empty());
        assertTrue(store.attachment(ORG, "11111111-1111-1111-1111-111111111111").isEmpty());
    }

    @Test
    void attachmentsForDraftSkipsLinksWithoutMetadata() {
        stubLinkRows(List.of("clean", "pending"));
        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222"))
                .thenReturn(Optional.of(artifact(ArtifactState.CLEAN, MediaType.PDF)), Optional.empty());
        assertEquals(1, store.attachmentsForDraft(ORG, "draft-1").size());
    }

    @SuppressWarnings("unchecked")
    private void stubInsertRow(boolean present) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    if (!present) {
                        return List.of();
                    }
                    RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    try {
                        when(resultSet.getString("id")).thenReturn("11111111-1111-1111-1111-111111111111");
                        return List.of(mapper.mapRow(resultSet, 0));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    @Test
    void putAttachmentPersistsMetadataAndRejectsUncommittedLinks() {
        stubInsertRow(true);
        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222")).thenReturn(Optional.empty());
        store.putAttachment(attachment(ScanState.PENDING, false));
        verify(documents).putArtifact(any(Artifact.class));

        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222"))
                .thenReturn(Optional.of(artifact(ArtifactState.CLEAN, MediaType.PDF)));
        store.putAttachment(attachment(ScanState.CLEAN, false));
        store.putAttachment(attachment(ScanState.QUARANTINED, false));
        store.putAttachment(attachment(ScanState.CLEAN, true));
        verify(documents, org.mockito.Mockito.times(3)).replaceArtifactIfVersion(
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(1), any(Artifact.class));

        stubInsertRow(false);
        assertEquals("UNREACHABLE_WRITE", assertThrows(ApiException.class,
                () -> store.putAttachment(attachment(ScanState.PENDING, false))).code());
    }

    private void stubBoundMessage(String messageId) {
        when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = (RowMapper<?>) invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getString("message_id")).thenReturn(messageId);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
    }

    @Test
    void bindToMessageReportsOnlyTheWinningConditionalWrite() {
        StaffAttachment binding = attachment(ScanState.CLEAN, true);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(documents.artifact(ORG, "22222222-2222-2222-2222-222222222222")).thenReturn(Optional.empty());
        assertTrue(store.bindToMessage(binding));
        verify(documents).putArtifact(any(Artifact.class));

        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);
        stubBoundMessage("message-1");
        assertFalse(store.bindToMessage(binding));

        stubBoundMessage("other-message");
        assertEquals("ALREADY_SENT", assertThrows(ApiException.class,
                () -> store.bindToMessage(binding)).code());
    }

    @Test
    void countAndRecipientChecksReturnDatabaseResults() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(3);
        assertEquals(3, store.countForDraft(ORG, "draft-1"));

        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(true, false, null);
        assertTrue(store.isRecipient(ORG, "message-1", "clinician-b"));
        assertFalse(store.isRecipient(ORG, "message-1", "stranger"));
        assertFalse(store.isRecipient(ORG, "message-1", "unknown"));
    }

    @Test
    void recipientQueryDerivesTheAudiencePerMessageAndRejectsFormerParticipants() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class)))
                .thenReturn(true);
        assertTrue(store.isRecipient(ORG, "message-1", "clinician-b"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), any(SqlParameterSource.class), eq(Boolean.class));
        assertTrue(sql.getValue().contains("resolved_recipients @> ARRAY[CAST(:subject AS text)]"));
        assertTrue(sql.getValue().contains("left_at IS NULL"));
        assertTrue(sql.getValue().contains("mail.message_id = msg.id"));
        assertTrue(sql.getValue().contains(" OR EXISTS ("));
        assertTrue(sql.getValue().contains("part.conversation_id = msg.conversation_id"));
        assertTrue(sql.getValue().contains("part.staff_subject = CAST(:subject AS text)"));
    }

    @Test
    void grantRecipientsPerformsNoDurableWrite() {
        store.grantRecipients(ORG, "message-1", Set.of("clinician-b"));
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void bytesArePromotedAndVerifiedThroughTheObjectStore() {
        byte[] content = "%PDF".getBytes(StandardCharsets.UTF_8);
        store.putBytes(ORG, "quarantine/org/doc/v1", content);
        verify(objects).putInQuarantine(any(ObjectKey.class), org.mockito.ArgumentMatchers.eq(content));
        verify(objects).promote(any(ObjectKey.class), anyString());

        store.putBytes(ORG, "plainkey", content);
        when(objects.getVerified(any(ObjectKey.class), anyString())).thenReturn(Optional.of(content), Optional.empty());
        assertTrue(store.bytes(ORG, "key", "checksum").isPresent());
        assertTrue(store.bytes(ORG, "key", "checksum").isEmpty());
    }

    @Test
    void auditsAreAppendedAndMappedBackFromTheDocumentLedger() {
        AttachmentAudit audit = new AttachmentAudit("id", ORG, "attachment-1", AuditType.SENT,
                "nurse-a", "success", null, AT);
        store.appendAudit(audit);
        verify(documents).appendEvent(any(DocumentEvent.class));

        when(documents.events(ORG)).thenReturn(List.of(
                new DocumentEvent("e1", ORG, "attachment-1", EventType.UPLOAD_COMPLETED,
                        "nurse-a", "success", null, AT),
                new DocumentEvent("e2", ORG, "attachment-1", EventType.RENDER_REQUESTED,
                        "nurse-a", "success", null, AT)));
        List<AttachmentAudit> mapped = store.audits(ORG);
        assertEquals(1, mapped.size());
        assertEquals(AuditType.SENT, mapped.get(0).type());
    }

    @Test
    void objectStoreConfigurationProvidesTheAdapter() {
        assertTrue(new StaffAttachmentConfiguration()
                .staffAttachmentObjectStore("/tmp/aurevia-test") instanceof ObjectStorePort);
    }

    @Test
    void configurationProvidesTheLimitsBeanRequiredByTheService() {
        StaffAttachmentLimits limits = new StaffAttachmentConfiguration().staffAttachmentLimits();
        assertEquals(StaffAttachmentLimits.DEFAULT_MAX_SIZE_BYTES, limits.maxSizeBytes());
        assertEquals(StaffAttachmentLimits.DEFAULT_MAX_FILES_PER_DRAFT, limits.maxFilesPerDraft());
    }
}
