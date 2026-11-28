package com.aurevia.staffmsg.mail;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.staffmsg.mail.MailContract.RecipientKind;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/**
 * PostgreSQL adapter for the durable staff-mail state.
 *
 * <p>V95 owns drafts and the send retry ledger; V91 owns the immutable message
 * ledger, its metadata and the per-person folders, which this adapter reuses.
 * Every statement runs in the guarded transaction binding {@code app.current_org}
 * and {@code app.actor}, names the active organization explicitly and relies on
 * forced row-level security as a second tenant boundary. Publication, folders,
 * draft removal and the retry claim share one transaction so a failed send
 * leaves neither a half-published message nor a consumed retry key.</p>
 */
@Repository
public class JdbcMailStore implements MailStore {

    private static final String DRAFT_COLUMNS = """
            organization_id::text, id::text, facility_id::text, owner_subject, version,
            subject, body, to_recipients, cc_recipients, attachment_ids, thread_id::text, updated_at
            """;

    private static final String MESSAGE_COLUMNS = """
            m.organization_id::text AS organization_id, m.id::text AS id,
            c.facility_id::text AS facility_id, c.id::text AS thread_id,
            md.subject, m.body, m.sender_subject, m.sender_role, m.sent_at,
            md.to_recipients, md.cc_recipients, md.resolved_recipients, md.attachment_ids, md.flags
            """;

    private static final String MESSAGE_FROM = """
              FROM public.staff_message m
              JOIN public.staff_mail_metadata md
                ON md.organization_id = m.organization_id AND md.message_id = m.id
              JOIN public.staff_conversation c
                ON c.organization_id = m.organization_id AND c.id = m.conversation_id
             WHERE m.organization_id = CAST(:organizationId AS uuid)
            """;

    private static final RowMapper<DraftView> DRAFT_MAPPER = JdbcMailStore::mapDraft;
    private static final RowMapper<SentMail> MESSAGE_MAPPER = JdbcMailStore::mapMessage;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the store with the shared JDBC and tenant-guarded transaction templates.
     *
     * @param jdbc         named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcMailStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }
    /**
     * Insert or replace a draft, keyed by its tenant and identifier.
     *
     * @param organizationId active tenant
     * @param draft          the current draft value
     */
    @Override
    public void saveDraft(String organizationId, DraftView draft) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.staff_mail_draft
                    (organization_id, id, facility_id, owner_subject, version, subject, body,
                     to_recipients, cc_recipients, attachment_ids, thread_id, updated_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:facilityId AS uuid),
                        :owner, :version, :subject, :body, CAST(:to AS text[]), CAST(:cc AS text[]),
                        CAST(:attachments AS text[]), CAST(:threadId AS uuid), CAST(:updatedAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO UPDATE SET
                    version = EXCLUDED.version, subject = EXCLUDED.subject, body = EXCLUDED.body,
                    to_recipients = EXCLUDED.to_recipients, cc_recipients = EXCLUDED.cc_recipients,
                    attachment_ids = EXCLUDED.attachment_ids, thread_id = EXCLUDED.thread_id,
                    updated_at = EXCLUDED.updated_at
                """, draftParameters(organizationId, draft)));
    }
    /**
     * Find a draft owned by one subject.
     *
     * @param organizationId active tenant
     * @param draftId        draft identifier
     * @param actor          owner subject, never accepted from a request body
     * @return the draft, or empty when it is absent or owned by somebody else
     */
    @Override
    public Optional<DraftView> draft(String organizationId, String draftId, String actor) {
        return transactions.execute(status -> jdbc.query("SELECT " + DRAFT_COLUMNS + """
                  FROM public.staff_mail_draft
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND id = CAST(:draftId AS uuid) AND owner_subject = :actor
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("draftId", draftId).addValue("actor", actor), DRAFT_MAPPER)
                .stream().findFirst());
    }
    /**
     * Find a published message by identifier.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @return the message, or empty when it is absent in this tenant
     */
    @Override
    public Optional<SentMail> message(String organizationId, String messageId) {
        return transactions.execute(status -> jdbc.query("SELECT " + MESSAGE_COLUMNS + MESSAGE_FROM
                + " AND m.id = CAST(:messageId AS uuid)",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("messageId", messageId), MESSAGE_MAPPER)
                .stream().findFirst());
    }
    /**
     * List every published message in a tenant. Participation and folder filters
     * are applied by the kernel, not here, so read policy stays in one place.
     *
     * @param organizationId active tenant
     * @return all published messages in insertion order
     */
    @Override
    public List<SentMail> messages(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + MESSAGE_COLUMNS + MESSAGE_FROM
                + " ORDER BY m.sent_at, m.id",
                new MapSqlParameterSource().addValue("organizationId", organizationId), MESSAGE_MAPPER));
    }
    /**
     * Replace the flags of a published message; the message body never changes.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @param flags          complete replacement flag set
     */
    @Override
    public void setFlags(String organizationId, String messageId, List<String> flags) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE public.staff_mail_metadata SET flags = CAST(:flags AS text[])
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND message_id = CAST(:messageId AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("messageId", messageId)
                        .addValue("flags", flags.toArray(String[]::new))));
    }
    /**
     * Return the folder placement of one viewer for every message.
     *
     * @param organizationId active tenant
     * @param actor          viewer subject
     * @return message identifier to folder, absent entries default to inbox
     */
    @Override
    public Map<String, MailFolder> folders(String organizationId, String actor) {
        return transactions.execute(status -> jdbc.query("""
                SELECT message_id::text, folder
                  FROM public.staff_mail_folder
                 WHERE organization_id = CAST(:organizationId AS uuid) AND staff_subject = :actor
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("actor", actor),
                (resultSet, rowNumber) -> Map.entry(resultSet.getString("message_id"),
                        MailFolder.from(resultSet.getString("folder"))))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }
    /**
     * Place one message in one viewer's folder.
     *
     * @param organizationId active tenant
     * @param messageId      message identifier
     * @param actor          viewer subject
     * @param folder         target folder
     */
    @Override
    public void setFolder(String organizationId, String messageId, String actor, MailFolder folder) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.staff_mail_folder (organization_id, message_id, staff_subject, folder)
                VALUES (CAST(:organizationId AS uuid), CAST(:messageId AS uuid), :actor, :folder)
                ON CONFLICT (organization_id, message_id, staff_subject) DO UPDATE SET folder = EXCLUDED.folder
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("messageId", messageId).addValue("actor", actor)
                        .addValue("folder", folder.wire())));
    }
    /**
     * Read the message id already published for a client retry key.
     *
     * @param organizationId   active tenant
     * @param actor            original sender
     * @param clientMessageId  opaque retry key from the request
     * @return the first published message id, or empty on a fresh key
     */
    @Override
    public Optional<String> replay(String organizationId, String actor, String clientMessageId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT message_id::text
                  FROM public.staff_mail_idempotency
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND actor_subject = :actor AND client_message_id = :key
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("actor", actor).addValue("key", clientMessageId),
                (resultSet, rowNumber) -> resultSet.getString(1))
                .stream().findFirst());
    }

    @Override
    public String publish(String organizationId, String actor, String clientMessageId, String draftId,
                          SentMail message, List<String> recipients) {
        SqlParameterSource claim = retryParameters(organizationId, actor, clientMessageId, message.id());
        return transactions.execute(status -> {
            Boolean claimed = jdbc.query("""
                    INSERT INTO public.staff_mail_idempotency
                        (organization_id, actor_subject, client_message_id, message_id)
                    VALUES (CAST(:organizationId AS uuid), :actor, :key, CAST(:messageId AS uuid))
                    ON CONFLICT DO NOTHING RETURNING true
                    """, claim, (resultSet, rowNumber) -> resultSet.getBoolean(1))
                    .stream().findFirst().orElse(false);
            if (!claimed) {
                return jdbc.query("""
                        SELECT message_id::text
                          FROM public.staff_mail_idempotency
                         WHERE organization_id = CAST(:organizationId AS uuid)
                           AND actor_subject = :actor AND client_message_id = :key
                        """, claim, (resultSet, rowNumber) -> resultSet.getString(1)).get(0);
            }
            jdbc.update("""
                    INSERT INTO public.staff_conversation (organization_id, id, facility_id, kind, topic)
                    VALUES (CAST(:organizationId AS uuid), CAST(:threadId AS uuid),
                            CAST(:facilityId AS uuid), 'mail_thread', :subject)
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("threadId", message.threadId())
                            .addValue("facilityId", message.facilityId())
                            .addValue("subject", message.subject()));
            jdbc.update("""
                    INSERT INTO public.staff_message
                        (organization_id, id, conversation_id, client_message_id,
                         sender_role, body, sent_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:threadId AS uuid),
                            :key, :senderRole, :body, CAST(:sentAt AS timestamptz))
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("id", message.id()).addValue("threadId", message.threadId())
                            .addValue("key", clientMessageId).addValue("senderRole", message.senderRole())
                            .addValue("body", message.body()).addValue("sentAt", dateTime(message.sentAt())));
            jdbc.update("""
                    INSERT INTO public.staff_mail_metadata
                        (organization_id, message_id, subject, to_recipients, cc_recipients,
                         resolved_recipients, attachment_ids, flags, draft_state)
                    VALUES (CAST(:organizationId AS uuid), CAST(:messageId AS uuid), :subject,
                            CAST(:to AS text[]), CAST(:cc AS text[]), CAST(:resolved AS text[]),
                            CAST(:attachments AS text[]), CAST(:flags AS text[]), 'sent')
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("messageId", message.id()).addValue("subject", message.subject())
                            .addValue("to", recipientWire(message.to()))
                            .addValue("cc", recipientWire(message.cc()))
                            .addValue("resolved", message.resolvedRecipients().toArray(String[]::new))
                            .addValue("attachments", message.attachmentDocumentIds().toArray(String[]::new))
                            .addValue("flags", message.flags().toArray(String[]::new)));
            SqlParameterSource[] placements = Stream.concat(
                    Stream.of(folderParameters(organizationId, message.id(), actor, MailFolder.SENT)),
                    recipients.stream().map(subject ->
                            folderParameters(organizationId, message.id(), subject, MailFolder.INBOX)))
                    .toArray(SqlParameterSource[]::new);
            jdbc.batchUpdate("""
                    INSERT INTO public.staff_mail_folder (organization_id, message_id, staff_subject, folder)
                    VALUES (CAST(:organizationId AS uuid), CAST(:messageId AS uuid), :actor, :folder)
                    ON CONFLICT (organization_id, message_id, staff_subject) DO UPDATE SET folder = EXCLUDED.folder
                    """, placements);
            jdbc.update("""
                    DELETE FROM public.staff_mail_draft
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND id = CAST(:draftId AS uuid) AND owner_subject = :actor
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("draftId", draftId).addValue("actor", actor));
            return message.id();
        });
    }

    private static SqlParameterSource draftParameters(String organizationId, DraftView draft) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("id", draft.id()).addValue("facilityId", draft.facilityId())
                .addValue("owner", draft.ownerSubject()).addValue("version", draft.version())
                .addValue("subject", draft.subject()).addValue("body", draft.body())
                .addValue("to", recipientWire(draft.to())).addValue("cc", recipientWire(draft.cc()))
                .addValue("attachments", draft.attachmentDocumentIds().toArray(String[]::new))
                .addValue("threadId", draft.threadId()).addValue("updatedAt", dateTime(draft.updatedAt()));
    }

    private static SqlParameterSource retryParameters(String organizationId, String actor,
                                                      String clientMessageId, String messageId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("actor", actor).addValue("key", clientMessageId).addValue("messageId", messageId);
    }

    private static SqlParameterSource folderParameters(String organizationId, String messageId,
                                                       String actor, MailFolder folder) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("messageId", messageId).addValue("actor", actor).addValue("folder", folder.wire());
    }

    private static String[] recipientWire(List<Recipient> recipients) {
        return recipients.stream().map(recipient -> recipient.kind().wire() + "|" + recipient.value())
                .toArray(String[]::new);
    }

    private static List<Recipient> recipients(String[] values) {
        return Arrays.stream(values).map(JdbcMailStore::recipient).toList();
    }

    private static Recipient recipient(String value) {
        int separator = value.indexOf('|');
        return new Recipient(RecipientKind.from(value.substring(0, separator)),
                value.substring(separator + 1));
    }

    private static String[] strings(ResultSet resultSet, String column) throws SQLException {
        Array array = resultSet.getArray(column);
        return (String[]) array.getArray();
    }

    private static DraftView mapDraft(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DraftView(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getString("facility_id"), resultSet.getString("owner_subject"),
                resultSet.getInt("version"), resultSet.getString("subject"), resultSet.getString("body"),
                recipients(strings(resultSet, "to_recipients")),
                recipients(strings(resultSet, "cc_recipients")),
                List.of((String[]) resultSet.getArray("attachment_ids").getArray()),
                resultSet.getString("thread_id"), instant(resultSet, "updated_at"));
    }

    private static SentMail mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SentMail(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getString("facility_id"), resultSet.getString("thread_id"),
                resultSet.getString("subject"), resultSet.getString("body"),
                resultSet.getString("sender_subject"), resultSet.getString("sender_role"),
                instant(resultSet, "sent_at"), recipients(strings(resultSet, "to_recipients")),
                recipients(strings(resultSet, "cc_recipients")),
                List.of((String[]) resultSet.getArray("resolved_recipients").getArray()),
                List.of((String[]) resultSet.getArray("attachment_ids").getArray()),
                List.of((String[]) resultSet.getArray("flags").getArray()));
    }

    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
