package com.aurevia.smartclinic.staffmsg.governance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceStore;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentStore;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.smartclinic.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;
import com.aurevia.smartclinic.staffmsg.instant.InstantConversationStore;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.SentMail;
import com.aurevia.smartclinic.staffmsg.mail.MailStore;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * Unit verification of the durable staff-governance adapter (MSG-07).
 *
 * <p>Collaborators are mocked so every delegation, mapping and SQL branch is
 * driven directly; the real-DB integration test is tracked in the notes as an
 * out-of-scope follow-up.</p>
 */
class JdbcStaffMessageGovernanceStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CONV = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String MESSAGE = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final Instant AT = Instant.parse("2026-11-23T12:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private MessagingGovernanceStore governance;
    private MailStore mail;
    private InstantConversationStore conversations;
    private StaffAttachmentStore attachments;
    private JdbcStaffMessageGovernanceStore store;

    /** Wire mocked collaborators with a passthrough transaction template. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        governance = mock(MessagingGovernanceStore.class);
        mail = mock(MailStore.class);
        conversations = mock(InstantConversationStore.class);
        attachments = mock(StaffAttachmentStore.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        org.mockito.Mockito.doAnswer(invocation -> {
            ((Consumer<Object>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        store = new JdbcStaffMessageGovernanceStore(jdbc, transactions, governance, mail,
                conversations, attachments);
    }

    private static SentMail sent(String threadId) {
        return new SentMail(ORG, MESSAGE, "facility", threadId, "Subject", "Body", "nurse-a",
                "unit_staff", AT, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void membershipAndRolesDelegateToTheAuthoritativeFolds() {
        when(governance.hasActiveMembership(ORG, "nurse-a")).thenReturn(true);
        when(governance.activeRoles(ORG, "nurse-a")).thenReturn(List.of("unit_staff"));
        assertTrue(store.hasActiveMembership(ORG, "nurse-a"));
        assertEquals(List.of("unit_staff"), store.activeRoles(ORG, "nurse-a"));
    }

    @Test
    void auditFactsAreInsertedAndMappedBackInTenantOrder() {
        AuditEvent event = new AuditEvent("fact-1", ORG, "nurse-a", "STAFFMSG_GOVERNANCE",
                StaffAction.OPEN, CONV, "facility", "corr-1",
                com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome.SUCCESS, AT);
        store.appendAudit(event);
        org.mockito.ArgumentCaptor<SqlParameterSource> params =
                org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals("open", params.getValue().getValue("action"));
        assertEquals("STAFF_MESSAGE", params.getValue().getValue("resourceType"));
        assertEquals("success", params.getValue().getValue("outcome"));
        assertEquals(OffsetDateTime.ofInstant(AT, ZoneOffset.UTC), params.getValue().getValue("occurredAt"));

        stubAuditRows();
        List<AuditEvent> found = store.auditEvents(ORG);
        assertEquals(1, found.size());
        assertEquals(StaffAction.FORWARD, found.get(0).action());
        assertEquals(com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome.NOT_FOUND,
                found.get(0).outcome());
        assertEquals(AT, found.get(0).occurredAt());
    }

    private void stubAuditRows() {
        when(jdbc.query(anyString(), any(Map.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = (RowMapper<Object>) invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    try {
                        when(resultSet.getString("id")).thenReturn("fact-1");
                        when(resultSet.getString("organization_id")).thenReturn(ORG);
                        when(resultSet.getString("actor_id")).thenReturn("actor-uuid");
                        when(resultSet.getString("action")).thenReturn("forward");
                        when(resultSet.getString("resource_id")).thenReturn(CONV);
                        when(resultSet.getString("scope")).thenReturn("facility");
                        when(resultSet.getString("correlation_id")).thenReturn("corr-1");
                        when(resultSet.getString("outcome")).thenReturn("not_found");
                        when(resultSet.getObject("occurred_at", OffsetDateTime.class))
                                .thenReturn(OffsetDateTime.ofInstant(AT, ZoneOffset.UTC));
                        return List.of(mapper.mapRow(resultSet, 0));
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                });
    }

    @Test
    void holdsAndPoliciesDelegateToTheGovernanceStore() {
        LegalHold hold = new LegalHold("hold-1", ORG, CONV, "dispute", "auditor", AT,
                null, null, null);
        when(governance.legalHolds(ORG)).thenReturn(List.of(hold));
        store.putLegalHold(ORG, hold);
        RetentionPolicy policy = new RetentionPolicy(ORG, "conversation", 30);
        when(governance.retentionPolicy(ORG, "conversation")).thenReturn(Optional.of(policy));

        assertEquals(List.of(hold), store.legalHolds(ORG));
        assertEquals(Optional.of(policy), store.retentionPolicy(ORG, "conversation"));
        verify(governance).putLegalHold(ORG, hold);
    }

    @Test
    void messagesFallBackToTheMessageIdForAStandaloneMail() {
        when(mail.messages(ORG)).thenReturn(List.of(sent(CONV), sent(null)));
        var views = store.messages(ORG);
        assertEquals(2, views.size());
        assertEquals(CONV, views.get(0).conversationId());
        assertEquals(MESSAGE, views.get(1).conversationId(), "a standalone mail is its own conversation");
        assertEquals("Subject", views.get(0).subject());
        assertEquals("Body", views.get(0).body());
    }

    @Test
    void conversationFoldersAndParticipantsDelegateToTheirStores() {
        when(conversations.participants(ORG, CONV)).thenReturn(List.of());
        when(conversations.conversation(ORG, CONV)).thenReturn(Optional.empty());
        when(mail.folders(ORG, "nurse-a")).thenReturn(Map.of(MESSAGE, MailFolder.INBOX));
        when(mail.messages(ORG)).thenReturn(List.of());

        assertTrue(store.participants(ORG, CONV).isEmpty());
        assertTrue(store.conversation(ORG, CONV).isEmpty());
        assertEquals(MailFolder.INBOX, store.folders(ORG, "nurse-a").get(MESSAGE));
        store.setFolder(ORG, MESSAGE, "nurse-a", MailFolder.TRASH);
        verify(mail).setFolder(ORG, MESSAGE, "nurse-a", MailFolder.TRASH);
    }

    @Test
    void attachmentLinksCarryChecksumsFromTheDurableLinkTable() {
        when(attachments.attachmentsForDraft(ORG, MESSAGE)).thenReturn(List.of(
                new StaffAttachment(ORG, "link-1", "nurse-a", "draft-1", "doc-1", "note.pdf",
                        "application/pdf", 12L, "sha-1", "object-key", 1, ScanState.CLEAN,
                        MESSAGE, true, AT)));
        List<AttachmentLink> links = store.attachmentLinks(ORG, MESSAGE);
        assertEquals(1, links.size());
        assertEquals("sha-1", links.get(0).checksum());
        assertEquals(MESSAGE, links.get(0).messageId());
        assertEquals(ScanState.CLEAN, links.get(0).scanState());
    }

    @Test
    void exportPersistsOnlyMetadataWithItsProvenance() {
        ExportRecord export = new ExportRecord("export-1", ORG, "conversation", "auditor", AT,
                AT.plusSeconds(3600),
                new com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance(
                        CONV, "rev-2", "record_access"));
        store.putExport(ORG, new ConversationExport("export-1", ORG, CONV, "auditor", AT,
                AT.plusSeconds(3600), List.of("sha-1"), export.provenance()));
        verify(governance).putExport(ORG, export);
        assertFalse(export.provenance().purpose().isBlank());
    }
}
