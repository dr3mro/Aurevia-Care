package com.aurevia.staffmsg.instant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * PostgreSQL store for instant staff conversations (MSG-02, ARC-07).
 *
 * <p>V91 owns the six tenant tables and their forced row-level security. Every
 * statement runs in the guarded transaction that binds {@code app.current_org}
 * and {@code app.actor}, names the active organization explicitly, and relies on
 * the database triggers for the creator, sender and gap-free sequence. The
 * immutable message ledger is only ever inserted and selected here.</p>
 *
 * <p>V96 adds the durable read-cursor table. A cursor is tenant-scoped by forced
 * row-level security, keyed by conversation and participant, raised only by a
 * monotonic upsert and clamped to the last sequence appended to the immutable
 * ledger so it can never hide a future message. It is per-person read state,
 * not a clinical record.</p>
 */
@Repository
public class JdbcInstantConversationStore implements InstantConversationStore {

    private static final RowMapper<StaffConversation> CONVERSATION_MAPPER =
            JdbcInstantConversationStore::mapConversation;
    private static final RowMapper<StaffParticipant> PARTICIPANT_MAPPER =
            JdbcInstantConversationStore::mapParticipant;
    private static final RowMapper<StaffMessage> MESSAGE_MAPPER = JdbcInstantConversationStore::mapMessage;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the store with the shared JDBC and transaction templates.
     *
     * @param jdbc         named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcInstantConversationStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<StaffConversation> directConversation(String organizationId, String facilityId,
                                                          String firstSubject, String secondSubject) {
        return transactions.execute(status -> jdbc.query("""
                SELECT c.organization_id::text, c.id::text, c.facility_id::text, c.kind, c.topic,
                       c.created_by_subject, c.created_at
                  FROM public.staff_conversation c
                  JOIN public.staff_participant p1 ON p1.organization_id = c.organization_id
                       AND p1.conversation_id = c.id AND p1.staff_subject = :firstSubject
                       AND p1.left_at IS NULL
                  JOIN public.staff_participant p2 ON p2.organization_id = c.organization_id
                       AND p2.conversation_id = c.id AND p2.staff_subject = :secondSubject
                       AND p2.left_at IS NULL
                 WHERE c.organization_id = CAST(:organizationId AS uuid)
                   AND c.facility_id = CAST(:facilityId AS uuid) AND c.kind = 'direct'
                 ORDER BY c.created_at DESC
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("facilityId", facilityId)
                        .addValue("firstSubject", firstSubject)
                        .addValue("secondSubject", secondSubject), CONVERSATION_MAPPER)
                .stream().findFirst());
    }

    @Override
    public StaffConversation createConversation(String organizationId, String facilityId,
                                                ConversationKind kind, String topic, String creatorSubject) {
        return transactions.execute(status -> jdbc.query("""
                INSERT INTO public.staff_conversation (organization_id, facility_id, kind, topic)
                VALUES (CAST(:organizationId AS uuid), CAST(:facilityId AS uuid), :kind, :topic)
                RETURNING organization_id::text, id::text, facility_id::text, kind, topic,
                          created_by_subject, created_at
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("facilityId", facilityId)
                        .addValue("kind", kind.wire())
                        .addValue("topic", topic), CONVERSATION_MAPPER)
                .get(0));
    }

    @Override
    public Optional<StaffConversation> conversation(String organizationId, String conversationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, facility_id::text, kind, topic,
                       created_by_subject, created_at
                  FROM public.staff_conversation
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND id = CAST(:conversationId AS uuid)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId), CONVERSATION_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<StaffParticipant> participants(String organizationId, String conversationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, conversation_id::text, staff_subject, role,
                       joined_at, left_at, muted
                  FROM public.staff_participant
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND conversation_id = CAST(:conversationId AS uuid)
                 ORDER BY joined_at, staff_subject
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId), PARTICIPANT_MAPPER));
    }

    @Override
    public void addParticipant(String organizationId, String conversationId, String subject,
                               ParticipantRole role, Instant joinedAt) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.staff_participant
                    (organization_id, conversation_id, staff_subject, role, joined_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:conversationId AS uuid),
                        :subject, :role, CAST(:joinedAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("subject", subject)
                        .addValue("role", role.wire())
                        .addValue("joinedAt", dateTime(joinedAt))));
    }

    @Override
    public void leaveParticipant(String organizationId, String conversationId, String subject, Instant leftAt) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE public.staff_participant SET left_at = CAST(:leftAt AS timestamptz)
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND conversation_id = CAST(:conversationId AS uuid)
                   AND staff_subject = :subject AND left_at IS NULL
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("subject", subject)
                        .addValue("leftAt", dateTime(leftAt))));
    }

    @Override
    public Optional<StaffMessage> messageByClientId(String organizationId, String conversationId,
                                                    String clientId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, conversation_id::text, sequence,
                       sender_subject, sender_role, body, sent_at, corrects_message_id::text
                  FROM public.staff_message
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND conversation_id = CAST(:conversationId AS uuid)
                   AND client_message_id = :clientId
                 ORDER BY sequence
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("clientId", clientId), MESSAGE_MAPPER)
                .stream().findFirst());
    }

    @Override
    public StaffMessage sendMessage(String organizationId, String conversationId, String senderRole,
                                    String body, String clientId, Instant sentAt) {
        return transactions.execute(status -> jdbc.query("""
                INSERT INTO public.staff_message
                    (organization_id, conversation_id, sender_role, body, client_message_id, sent_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:conversationId AS uuid),
                        :senderRole, :body, :clientId, CAST(:sentAt AS timestamptz))
                RETURNING organization_id::text, id::text, conversation_id::text, sequence,
                          sender_subject, sender_role, body, sent_at, corrects_message_id::text
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("senderRole", senderRole)
                        .addValue("body", body)
                        .addValue("clientId", clientId)
                        .addValue("sentAt", dateTime(sentAt)), MESSAGE_MAPPER)
                .get(0));
    }

    @Override
    public List<StaffMessage> messages(String organizationId, String conversationId,
                                       long afterSequence, Instant notAfter) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, conversation_id::text, sequence,
                       sender_subject, sender_role, body, sent_at, corrects_message_id::text
                  FROM public.staff_message
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND conversation_id = CAST(:conversationId AS uuid)
                   AND sequence > :afterSequence
                   AND (CAST(:notAfter AS timestamptz) IS NULL
                        OR sent_at < CAST(:notAfter AS timestamptz))
                 ORDER BY sequence
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("afterSequence", afterSequence)
                        .addValue("notAfter", notAfter == null ? null : dateTime(notAfter)), MESSAGE_MAPPER));
    }

    @Override
    public boolean activeStaff(String organizationId, String subject) {
        // organizationId is intentionally unused: the guarded transaction binds
        // the active tenant and both folds are tenant-scoped by forced RLS.
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT public.has_active_membership(:subject)
                       AND EXISTS (SELECT 1 FROM public.active_roles(:subject))
                """, Map.of("subject", subject), Boolean.class)));
    }

    @Override
    public boolean facilityWithinScope(String organizationId, String subject, String facilityId) {
        // The fold is authoritative: legacy members with no assignment keep
        // organization-wide scope, while assigned members are confined to it.
        // A facility absent from this tenant makes the scalar subquery NULL,
        // and COALESCE refuses it instead of throwing on an empty result.
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE((SELECT NOT public.is_facility_out_of_scope(:subject, f.code)
                                   FROM public.facility f
                                  WHERE f.organization_id = CAST(:organizationId AS uuid)
                                    AND f.id = CAST(:facilityId AS uuid)), false)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject)
                        .addValue("facilityId", facilityId), Boolean.class)));
    }

    @Override
    public List<StaffConversation> conversationsForFacility(String organizationId, String facilityId,
                                                            String subject) {
        return transactions.execute(status -> jdbc.query("""
                SELECT c.organization_id::text, c.id::text, c.facility_id::text, c.kind, c.topic,
                       c.created_by_subject, c.created_at
                  FROM public.staff_conversation c
                  JOIN public.staff_participant p ON p.organization_id = c.organization_id
                       AND p.conversation_id = c.id AND p.staff_subject = :subject
                       AND p.left_at IS NULL
                 WHERE c.organization_id = CAST(:organizationId AS uuid)
                   AND c.facility_id = CAST(:facilityId AS uuid)
                 ORDER BY c.created_at
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("facilityId", facilityId)
                        .addValue("subject", subject), CONVERSATION_MAPPER));
    }

    @Override
    public void markRead(String organizationId, String conversationId, String subject, long upToSequence) {
        // One upsert keeps the cursor durable and monotonic without a Java
        // compare-and-set: the conflict target serializes concurrent readers and
        // GREATEST ignores a stale acknowledgement. The inserted value is bounded
        // by the last message appended under the same tenant and conversation, so
        // an over-large acknowledgement cannot swallow a future message.
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.staff_message_read_cursor
                    (organization_id, conversation_id, participant_subject, last_read_sequence)
                VALUES (CAST(:organizationId AS uuid), CAST(:conversationId AS uuid), :subject,
                        LEAST(:upToSequence, (SELECT COALESCE(MAX(m.sequence), 0)
                                                FROM public.staff_message m
                                               WHERE m.organization_id = CAST(:organizationId AS uuid)
                                                 AND m.conversation_id = CAST(:conversationId AS uuid))))
                ON CONFLICT (organization_id, conversation_id, participant_subject) DO UPDATE
                    SET last_read_sequence = GREATEST(excluded.last_read_sequence,
                                                      public.staff_message_read_cursor.last_read_sequence),
                        updated_at = clock_timestamp()
                  WHERE excluded.last_read_sequence > public.staff_message_read_cursor.last_read_sequence
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("subject", subject)
                        .addValue("upToSequence", upToSequence)));
    }

    @Override
    public long readSequence(String organizationId, String conversationId, String subject) {
        // COALESCE guarantees one row even before the first acknowledgement, so
        // an unread participant reads zero instead of an empty-result exception.
        return transactions.execute(status -> Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COALESCE(MAX(last_read_sequence), 0)
                  FROM public.staff_message_read_cursor
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND conversation_id = CAST(:conversationId AS uuid)
                   AND participant_subject = :subject
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("conversationId", conversationId)
                        .addValue("subject", subject), Long.class),
                "the cursor aggregate always returns one row"));
    }

    private static StaffConversation mapConversation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StaffConversation(resultSet.getString("organization_id"),
                resultSet.getString("facility_id"), resultSet.getString("id"),
                ConversationKind.from(resultSet.getString("kind")),
                resultSet.getString("topic"), resultSet.getString("created_by_subject"),
                instant(resultSet, "created_at"));
    }

    private static StaffParticipant mapParticipant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StaffParticipant(resultSet.getString("organization_id"),
                resultSet.getString("conversation_id"), resultSet.getString("staff_subject"),
                ParticipantRole.from(resultSet.getString("role")), instant(resultSet, "joined_at"),
                nullableInstant(resultSet, "left_at"), resultSet.getBoolean("muted"));
    }

    private static StaffMessage mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StaffMessage(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getString("conversation_id"), resultSet.getLong("sequence"),
                resultSet.getString("sender_subject"), resultSet.getString("sender_role"),
                resultSet.getString("body"), instant(resultSet, "sent_at"),
                resultSet.getString("corrects_message_id"));
    }

    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
