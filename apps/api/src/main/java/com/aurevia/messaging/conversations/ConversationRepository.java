package com.aurevia.messaging.conversations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.InboxEntry;
import com.aurevia.messaging.conversations.ConversationContract.Message;
import com.aurevia.messaging.conversations.ConversationContract.SenderKind;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.WorkEventType;
import com.aurevia.messaging.conversations.ConversationContract.WorkItemEvent;

/**
 * The single SQL boundary for MSG-02 conversations.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced
 * row-level security confines it to the active organization. The message and
 * work-item ledgers are append-only for every role, and the V33 triggers derive
 * the conversation author and each message sender from the transaction's
 * {@code app.actor} binding, never from caller input.</p>
 */
@Repository
public class ConversationRepository {

    private static final String CONVERSATION_SELECT = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, patient_subject,
                   unit_key, connection_id, topic, state, assignee_subject,
                   patient_read_sequence, staff_read_sequence, created_at
              FROM public.conversation
            """;

    private static final String MESSAGE_SELECT = """
            SELECT id::text AS id, conversation_id::text AS conversation_id, sequence,
                   sender_kind, sender_subject, sender_role, body, created_at
              FROM public.conversation_message
            """;

    private static final String EVENT_SELECT = """
            SELECT id::text AS id, conversation_id::text AS conversation_id,
                   conversation_version, event_type, actor_subject, from_state, to_state,
                   reason, occurred_at
              FROM public.work_item_event
            """;

    private static final RowMapper<Conversation> CONVERSATION_MAPPER = ConversationRepository::mapConversation;
    private static final RowMapper<Message> MESSAGE_MAPPER = ConversationRepository::mapMessage;
    private static final RowMapper<WorkItemEvent> EVENT_MAPPER = ConversationRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List the roles currently in force for a subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Check that a verified patient link is active in the active organization.
     *
     * @param subject   patient account subject
     * @param patientId patient identifier
     * @return {@code true} when the link is active and visible
     */
    public boolean patientLinkActive(String subject, String patientId) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.patient_link_active(:subject, CAST(:patientId AS uuid))",
                new MapSqlParameterSource().addValue("subject", subject).addValue("patientId", patientId),
                Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * Check that a patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert a new conversation and its first message will be appended by the caller.
     *
     * @param id             new opaque identifier
     * @param patientId      owning patient
     * @param patientSubject verified patient account subject
     * @param unitKey        recipient care-unit key
     * @param connectionId   verified connection identifier
     * @param topic          optional topic, or {@code null}
     * @param state          initial lifecycle state wire key
     * @return number of inserted rows
     */
    public int insertConversation(String id, String patientId, String patientSubject, String unitKey,
                                  String connectionId, String topic, String state) {
        return jdbc.update("""
                INSERT INTO public.conversation
                    (organization_id, id, version, patient_id, patient_subject, unit_key,
                     connection_id, topic, state)
                VALUES (public.app_org(), CAST(:id AS uuid), 1, CAST(:patientId AS uuid),
                        :patientSubject, :unitKey, :connectionId, :topic, CAST(:state AS text))
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("patientId", patientId).addValue("patientSubject", patientSubject)
                .addValue("unitKey", unitKey).addValue("connectionId", connectionId)
                .addValue("topic", topic).addValue("state", state));
    }

    /**
     * Read one conversation.
     *
     * @param id conversation identifier
     * @return the conversation when visible
     */
    public Optional<Conversation> find(String id) {
        return jdbc.query(CONVERSATION_SELECT + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), CONVERSATION_MAPPER).stream().findFirst();
    }

    /**
     * Read one conversation, locking its row for the current transaction.
     *
     * @param id conversation identifier
     * @return the locked conversation when visible
     */
    public Optional<Conversation> loadForUpdate(String id) {
        return jdbc.query(CONVERSATION_SELECT + " WHERE id = CAST(:id AS uuid) FOR UPDATE",
                Map.of("id", id), CONVERSATION_MAPPER).stream().findFirst();
    }

    /**
     * Compute the next per-conversation message sequence under the caller's lock.
     *
     * @param conversationId conversation identifier
     * @return one plus the current maximum sequence
     */
    public int nextSequence(String conversationId) {
        // The aggregate always returns exactly one non-null row; fail closed if
        // a driver ever violates that contract instead of unboxing null.
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1 FROM public.conversation_message
                 WHERE conversation_id = CAST(:conversationId AS uuid)
                """, Map.of("conversationId", conversationId), Integer.class);
        return Objects.requireNonNull(value, "nextSequence aggregate returned no row");
    }

    /**
     * Find the message accepted for a client id, confined by row-level security.
     *
     * @param conversationId owning conversation
     * @param clientMessageId durable client key from the reply request
     * @return the first matching message, or an empty result when this is a new reply
     */
    public Optional<Message> findMessageByClientId(String conversationId, String clientMessageId) {
        List<Message> found = jdbc.query(MESSAGE_SELECT + """
                WHERE conversation_id = CAST(:conversationId AS uuid)
                  AND client_message_id = :clientMessageId
                """, new MapSqlParameterSource()
                .addValue("conversationId", conversationId)
                .addValue("clientMessageId", clientMessageId), MESSAGE_MAPPER);
        return found.stream().findFirst();
    }

    /**
     * Append a message once for a durable client id.
     *
     * <p>{@code ON CONFLICT DO NOTHING RETURNING} is used instead of catching a
     * unique violation: a failed statement would abort the surrounding
     * transaction and make the subsequent replay lookup impossible. An empty
     * result means a concurrent request already accepted that client id.</p>
     *
     * @param conversationId owning conversation
     * @param sequence       per-conversation sequence
     * @param senderKind     patient or staff wire key
     * @param senderSubject  authenticated author subject
     * @param senderRole     role active at publication
     * @param body           non-sensitive message text
     * @param clientMessageId durable client key
     * @return {@code true} when this call inserted the message
     */
    public boolean insertMessageIfAbsent(String conversationId, int sequence, String senderKind,
                                         String senderSubject, String senderRole, String body,
                                         String clientMessageId) {
        String inserted = jdbc.query("""
                INSERT INTO public.conversation_message
                    (organization_id, conversation_id, sequence, sender_kind, sender_subject,
                     sender_role, body, client_message_id)
                VALUES (public.app_org(), CAST(:conversationId AS uuid), :sequence, :senderKind,
                        :senderSubject, :senderRole, :body, :clientMessageId)
                ON CONFLICT (organization_id, conversation_id, client_message_id) DO NOTHING
                RETURNING id::text
                """, new MapSqlParameterSource()
                .addValue("conversationId", conversationId)
                .addValue("sequence", sequence).addValue("senderKind", senderKind)
                .addValue("senderSubject", senderSubject).addValue("senderRole", senderRole)
                .addValue("body", body).addValue("clientMessageId", clientMessageId),
                results -> results.next() ? results.getString(1) : null);
        return inserted != null;
    }

    /**
     * Append one immutable work-item fact.
     *
     * @param conversationId     owning conversation
     * @param version            conversation revision the fact is bound to
     * @param eventType          work-item fact wire key
     * @param actorSubject       authenticated actor
     * @param fromState          state before the fact, or {@code null}
     * @param toState            state after the fact, or {@code null}
     * @param reason             non-sensitive reason, or {@code null}
     * @return number of inserted rows
     */
    public int appendEvent(String conversationId, int version, String eventType, String actorSubject,
                           String fromState, String toState, String reason) {
        return jdbc.update("""
                INSERT INTO public.work_item_event
                    (organization_id, conversation_id, conversation_version, event_type,
                     actor_subject, from_state, to_state, reason)
                VALUES (public.app_org(), CAST(:conversationId AS uuid), :version, :eventType,
                        :actorSubject, :fromState, :toState, :reason)
                """, new MapSqlParameterSource().addValue("conversationId", conversationId)
                .addValue("version", version).addValue("eventType", eventType)
                .addValue("actorSubject", actorSubject).addValue("fromState", fromState)
                .addValue("toState", toState).addValue("reason", reason));
    }

    /**
     * Move a conversation to a new lifecycle state.
     *
     * @param id              conversation identifier
     * @param expectedVersion version read by the caller
     * @param state           target state wire key
     * @return number of updated rows; zero means the version moved
     */
    public int updateState(String id, int expectedVersion, String state) {
        return jdbc.update("""
                UPDATE public.conversation
                   SET state = CAST(:state AS text), version = version + 1, updated_at = now()
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("state", state));
    }

    /**
     * Set the current work-item assignee.
     *
     * @param id              conversation identifier
     * @param expectedVersion version read by the caller
     * @param assigneeSubject staff subject in the same unit
     * @return number of updated rows; zero means the version moved
     */
    public int assign(String id, int expectedVersion, String assigneeSubject) {
        return jdbc.update("""
                UPDATE public.conversation
                   SET assignee_subject = :assigneeSubject, version = version + 1, updated_at = now()
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("assigneeSubject", assigneeSubject));
    }

    /**
     * Advance the patient's read watermark; it can only move forward.
     *
     * @param id              conversation identifier
     * @param expectedVersion version read by the caller
     * @param sequence        highest sequence the patient has viewed
     * @return number of updated rows; zero means the version moved
     */
    public int markPatientRead(String id, int expectedVersion, int sequence) {
        return jdbc.update("""
                UPDATE public.conversation
                   SET patient_read_sequence = GREATEST(patient_read_sequence, :sequence),
                       version = version + 1, updated_at = now()
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("sequence", sequence));
    }

    /**
     * Advance the staff's read watermark; it can only move forward.
     *
     * @param id              conversation identifier
     * @param expectedVersion version read by the caller
     * @param sequence        highest sequence the unit has viewed
     * @return number of updated rows; zero means the version moved
     */
    public int markStaffRead(String id, int expectedVersion, int sequence) {
        return jdbc.update("""
                UPDATE public.conversation
                   SET staff_read_sequence = GREATEST(staff_read_sequence, :sequence),
                       version = version + 1, updated_at = now()
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("sequence", sequence));
    }

    /**
     * Read the ordered message trail of a conversation.
     *
     * @param conversationId conversation identifier
     * @return immutable messages in sequence order
     */
    public List<Message> messages(String conversationId) {
        return jdbc.query(MESSAGE_SELECT + " WHERE conversation_id = CAST(:conversationId AS uuid)"
                + " ORDER BY sequence", Map.of("conversationId", conversationId), MESSAGE_MAPPER);
    }

    /**
     * Read the ordered work-item fact trail of a conversation.
     *
     * @param conversationId conversation identifier
     * @return immutable facts in chronological order
     */
    public List<WorkItemEvent> events(String conversationId) {
        return jdbc.query(EVENT_SELECT + " WHERE conversation_id = CAST(:conversationId AS uuid)"
                + " ORDER BY occurred_at, id", Map.of("conversationId", conversationId), EVENT_MAPPER);
    }

    /**
     * Read a patient's inbox with derived unread counts.
     *
     * @param patientSubject verified patient account subject
     * @return entries ordered by most recently updated
     */
    public List<InboxEntry> inbox(String patientSubject) {
        return jdbc.query("""
                SELECT c.id::text AS id, c.version, c.patient_id::text AS patient_id,
                       c.patient_subject, c.unit_key, c.connection_id, c.topic, c.state,
                       c.assignee_subject, c.patient_read_sequence, c.staff_read_sequence,
                       c.created_at,
                       (SELECT count(*) FROM public.conversation_message m
                         WHERE m.conversation_id = c.id AND m.sender_kind = 'staff'
                           AND m.sequence > c.patient_read_sequence) AS unread_count
                  FROM public.conversation c
                 WHERE c.patient_subject = :patientSubject
                 ORDER BY c.updated_at DESC, c.id
                """, Map.of("patientSubject", patientSubject), (resultSet, rowNumber) ->
                new InboxEntry(mapConversation(resultSet, rowNumber), resultSet.getInt("unread_count")));
    }

    /**
     * Read the role-scoped staff work queue.
     *
     * @param unitKeys care units the caller may work
     * @return open conversations ordered by state then last update
     */
    public List<Conversation> workQueue(List<String> unitKeys) {
        return jdbc.query(CONVERSATION_SELECT
                        + " WHERE unit_key IN (:unitKeys) AND state <> 'closed'"
                        + " ORDER BY state, updated_at DESC, id",
                new MapSqlParameterSource().addValue("unitKeys", unitKeys), CONVERSATION_MAPPER);
    }

    private static Conversation mapConversation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Conversation(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("patient_subject"),
                resultSet.getString("unit_key"), resultSet.getString("connection_id"),
                resultSet.getString("topic"), State.from(resultSet.getString("state")),
                resultSet.getString("assignee_subject"), resultSet.getInt("patient_read_sequence"),
                resultSet.getInt("staff_read_sequence"), instant(resultSet, "created_at"));
    }

    private static Message mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Message(resultSet.getString("id"), resultSet.getString("conversation_id"),
                resultSet.getInt("sequence"), SenderKind.from(resultSet.getString("sender_kind")),
                resultSet.getString("sender_subject"), resultSet.getString("sender_role"),
                resultSet.getString("body"), instant(resultSet, "created_at"));
    }

    private static WorkItemEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkItemEvent(resultSet.getString("id"), resultSet.getString("conversation_id"),
                resultSet.getInt("conversation_version"),
                WorkEventType.from(resultSet.getString("event_type")),
                resultSet.getString("actor_subject"), resultSet.getString("from_state"),
                resultSet.getString("to_state"), resultSet.getString("reason"),
                instant(resultSet, "occurred_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        // Every mapped timestamp column is NOT NULL with a server default.
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
