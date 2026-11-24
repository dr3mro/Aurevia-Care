package com.aurevia.exchange.response;

import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.exchange.response.ExchangeResponseCommands.RetainedCopy;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeEvent;
import com.aurevia.exchange.response.ExchangeResponseCommands.Notification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL boundary for XCH-03 order responses and their immutable ledgers.
 *
 * <p>Every statement relies on the forced-RLS active-tenant context bound by the
 * guarded transaction manager. Reads expose only rows of the active
 * organization; ownership columns of the mutable order are pinned by the V21
 * update guard, so a resource id from another tenant is concealed as not found.</p>
 */
@Repository
public class ExchangeResponseRepository {

    private static final String SELECT_ORDER = """
            SELECT id::text AS id, version, order_code, state, revision, content_checksum,
                   local_patient_id::text AS local_patient_id, assignee, reason, question,
                   permitted_purpose, retention_policy
              FROM public.exchange_response_order
            """;

    private static final RowMapper<ExchangeOrder> ORDER_MAPPER = ExchangeResponseRepository::mapOrder;
    private static final RowMapper<ExchangeEvent> EVENT_MAPPER = ExchangeResponseRepository::mapEvent;
    private static final RowMapper<Revision> REVISION_MAPPER = ExchangeResponseRepository::mapRevision;
    private static final RowMapper<RetainedCopy> COPY_MAPPER = ExchangeResponseRepository::mapCopy;
    private static final RowMapper<Notification> NOTIFICATION_MAPPER = ExchangeResponseRepository::mapNotification;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ExchangeResponseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Re-read active membership inside the current transaction.
     *
     * @param subject authenticated subject
     * @return {@code true} when the subject has active membership in this tenant
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * Re-read the role keys currently in force inside the current transaction.
     *
     * @param subject authenticated subject
     * @return the active role keys in this tenant
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read one order, locking it for this transaction.
     *
     * @param id order identifier
     * @return the locked order when visible in the active tenant
     */
    public Optional<ExchangeOrder> findForUpdate(String id) {
        return jdbc.query(SELECT_ORDER + " WHERE id = CAST(:id AS uuid) FOR UPDATE",
                Map.of("id", id), ORDER_MAPPER).stream().findFirst();
    }

    /**
     * Read one order without locking it.
     *
     * @param id order identifier
     * @return the order when visible in the active tenant
     */
    public Optional<ExchangeOrder> find(String id) {
        return jdbc.query(SELECT_ORDER + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), ORDER_MAPPER).stream().findFirst();
    }

    /**
     * Advance one order version, recording the command outcome.
     *
     * @param id              order identifier
     * @param version         version read by the caller
     * @param state           resulting state
     * @param localPatientId  verified local patient association, or {@code null}
     * @param assignee        receiving assignee, or {@code null}
     * @param reason          recorded reason, or {@code null}
     * @param question        recorded question, or {@code null}
     * @param permittedPurpose retained-copy purpose, or {@code null}
     * @param retentionPolicy retained-copy policy, or {@code null}
     * @param revision        current released revision
     * @return number of updated rows; {@code 0} means the version moved underneath
     */
    public int update(String id, int version, ExchangeState state, String localPatientId, String assignee,
                      String reason, String question, String permittedPurpose, String retentionPolicy,
                      int revision) {
        return jdbc.update("""
                UPDATE public.exchange_response_order
                   SET state = :state, version = version + 1, local_patient_id = CAST(:patient AS uuid),
                       assignee = :assignee, reason = :reason, question = :question,
                       permitted_purpose = :purpose, retention_policy = :policy, revision = :revision
                 WHERE id = CAST(:id AS uuid) AND version = :version
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("version", version).addValue("state", state.wire())
                .addValue("patient", localPatientId).addValue("assignee", assignee)
                .addValue("reason", reason).addValue("question", question)
                .addValue("purpose", permittedPurpose).addValue("policy", retentionPolicy)
                .addValue("revision", revision));
    }

    /**
     * Insert an immutable linked revision.
     *
     * @param organizationId active organization identifier
     * @param id             new revision identifier
     * @param orderId        owning order identifier
     * @param revision       new revision number
     * @param previous       previous revision number, or {@code null}
     * @param checksum       content checksum of the new revision
     * @param summary        recorded change summary
     * @param actor          authenticated actor subject
     */
    public void insertRevision(String organizationId, String id, String orderId, int revision,
                               Integer previous, String checksum, String summary, String actor) {
        jdbc.update("""
                INSERT INTO public.exchange_response_revision
                    (organization_id, id, order_id, revision, previous_revision, content_checksum,
                     change_summary, actor_id)
                VALUES (CAST(:org AS uuid), CAST(:id AS uuid), CAST(:orderId AS uuid), :revision,
                        :previous, :checksum, :summary,
                        (SELECT id FROM public.user_identity WHERE organization_id = CAST(:org AS uuid)
                          AND subject = :actor))
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id)
                .addValue("orderId", orderId).addValue("revision", revision).addValue("previous", previous)
                .addValue("checksum", checksum).addValue("summary", summary).addValue("actor", actor));
    }

    /**
     * Insert an immutable retained recipient copy.
     *
     * @param organizationId active organization identifier
     * @param id             new copy identifier
     * @param orderId        owning order identifier
     * @param revision       retained revision number
     * @param checksum       content checksum proven against the revision
     * @param purpose        permitted purpose
     * @param policy         approved retention policy
     * @param legalHold      whether a legal hold suspends expiry
     * @param actor          authenticated actor subject
     */
    public void insertRetained(String organizationId, String id, String orderId, int revision,
                               String checksum, String purpose, String policy, boolean legalHold,
                               String actor) {
        jdbc.update("""
                INSERT INTO public.exchange_response_retained_copy
                    (organization_id, id, order_id, revision, content_checksum, permitted_purpose,
                     retention_policy, legal_hold, actor_id)
                VALUES (CAST(:org AS uuid), CAST(:id AS uuid), CAST(:orderId AS uuid), :revision,
                        :checksum, :purpose, :policy, :legalHold,
                        (SELECT id FROM public.user_identity WHERE organization_id = CAST(:org AS uuid)
                          AND subject = :actor))
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id)
                .addValue("orderId", orderId).addValue("revision", revision).addValue("checksum", checksum)
                .addValue("purpose", purpose).addValue("policy", policy).addValue("legalHold", legalHold)
                .addValue("actor", actor));
    }

    /**
     * Insert an immutable downstream notification.
     *
     * @param organizationId active organization identifier
     * @param id             new notification identifier
     * @param orderId        revoked order identifier
     * @param eventId        durable downstream event identifier
     * @param target         named downstream recipient
     * @param channel        approved contact channel
     * @param outcome        recorded delivery outcome
     * @param actor          authenticated actor subject
     */
    public void insertNotification(String organizationId, String id, String orderId, String eventId,
                                   String target, String channel, String outcome, String actor) {
        jdbc.update("""
                INSERT INTO public.exchange_response_notification
                    (organization_id, id, order_id, event_id, target, channel, outcome, actor_id)
                VALUES (CAST(:org AS uuid), CAST(:id AS uuid), CAST(:orderId AS uuid), :eventId,
                        :target, :channel, :outcome,
                        (SELECT id FROM public.user_identity WHERE organization_id = CAST(:org AS uuid)
                          AND subject = :actor))
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id)
                .addValue("orderId", orderId).addValue("eventId", eventId).addValue("target", target)
                .addValue("channel", channel).addValue("outcome", outcome).addValue("actor", actor));
    }

    /**
     * Append one response-history event.
     *
     * @param organizationId active organization identifier
     * @param id             new event identifier
     * @param orderId        owning order identifier
     * @param version        order version after the command
     * @param action         command action key
     * @param fromState      state before the command, or {@code null}
     * @param toState        state after the command
     * @param reason         recorded reason, or {@code null}
     * @param actor          authenticated actor subject
     */
    public void appendEvent(String organizationId, String id, String orderId, int version, String action,
                            String fromState, String toState, String reason, String actor) {
        jdbc.update("""
                INSERT INTO public.exchange_response_event
                    (organization_id, id, order_id, order_version, action, from_state, to_state,
                     reason, actor_id)
                VALUES (CAST(:org AS uuid), CAST(:id AS uuid), CAST(:orderId AS uuid), :version,
                        :action, :fromState, :toState, :reason,
                        (SELECT id FROM public.user_identity WHERE organization_id = CAST(:org AS uuid)
                          AND subject = :actor))
                """, new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id)
                .addValue("orderId", orderId).addValue("version", version).addValue("action", action)
                .addValue("fromState", fromState).addValue("toState", toState)
                .addValue("reason", reason).addValue("actor", actor));
    }

    /**
     * Read the append-only event ledger, oldest first.
     *
     * @param orderId order identifier
     * @return the response events
     */
    public List<ExchangeEvent> history(String orderId) {
        return jdbc.query("""
                SELECT id::text, order_version, action, from_state, to_state, reason,
                       actor_id::text, occurred_at
                  FROM public.exchange_response_event WHERE order_id = CAST(:id AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("id", orderId), EVENT_MAPPER);
    }

    /**
     * Read the immutable revision ledger, oldest first.
     *
     * @param orderId order identifier
     * @return the released revisions
     */
    public List<Revision> revisions(String orderId) {
        return jdbc.query("""
                SELECT id::text, revision, previous_revision, content_checksum, change_summary,
                       actor_id::text, occurred_at
                  FROM public.exchange_response_revision WHERE order_id = CAST(:id AS uuid)
                 ORDER BY revision
                """, Map.of("id", orderId), REVISION_MAPPER);
    }

    /**
     * Read the immutable retained-copy ledger, oldest first.
     *
     * @param orderId order identifier
     * @return the retained copies
     */
    public List<RetainedCopy> retained(String orderId) {
        return jdbc.query("""
                SELECT id::text, revision, content_checksum, permitted_purpose, retention_policy,
                       legal_hold, actor_id::text, occurred_at
                  FROM public.exchange_response_retained_copy WHERE order_id = CAST(:id AS uuid)
                 ORDER BY revision
                """, Map.of("id", orderId), COPY_MAPPER);
    }

    /**
     * Read the immutable downstream-notification ledger, oldest first.
     *
     * @param orderId order identifier
     * @return the revocation notifications
     */
    public List<Notification> notifications(String orderId) {
        return jdbc.query("""
                SELECT id::text, event_id, target, channel, outcome, actor_id::text, occurred_at
                  FROM public.exchange_response_notification WHERE order_id = CAST(:id AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("id", orderId), NOTIFICATION_MAPPER);
    }

    /**
     * Read one released revision of an order.
     *
     * @param orderId  order identifier
     * @param revision revision number
     * @return the revision when visible in the active tenant
     */
    public Optional<Revision> currentRevision(String orderId, int revision) {
        return jdbc.query("""
                SELECT id::text, revision, previous_revision, content_checksum, change_summary,
                       actor_id::text, occurred_at
                  FROM public.exchange_response_revision WHERE order_id = CAST(:id AS uuid) AND revision = :revision
                """, new MapSqlParameterSource().addValue("id", orderId).addValue("revision", revision),
                REVISION_MAPPER).stream().findFirst();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static ExchangeOrder mapOrder(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExchangeOrder(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("order_code"), ExchangeState.from(resultSet.getString("state")),
                resultSet.getInt("revision"), resultSet.getString("content_checksum"),
                resultSet.getString("local_patient_id"), resultSet.getString("assignee"),
                resultSet.getString("reason"), resultSet.getString("question"),
                resultSet.getString("permitted_purpose"), resultSet.getString("retention_policy"));
    }

    private static ExchangeEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExchangeEvent(resultSet.getString("id"), resultSet.getInt("order_version"),
                resultSet.getString("action"), resultSet.getString("from_state"),
                resultSet.getString("to_state"), resultSet.getString("reason"),
                resultSet.getString("actor_id"), instant(resultSet, "occurred_at"));
    }

    private static Revision mapRevision(ResultSet resultSet, int rowNumber) throws SQLException {
        int previous = resultSet.getInt("previous_revision");
        // wasNull() must be read immediately after the column it describes; the
        // original order read "revision" here and never mapped a linked change's
        // first revision to the documented unknown previousRevision.
        Integer previousRevision = resultSet.wasNull() ? null : previous;
        return new Revision(resultSet.getString("id"), resultSet.getInt("revision"),
                previousRevision, resultSet.getString("content_checksum"),
                resultSet.getString("change_summary"), resultSet.getString("actor_id"),
                instant(resultSet, "occurred_at"));
    }

    private static RetainedCopy mapCopy(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RetainedCopy(resultSet.getString("id"), resultSet.getInt("revision"),
                resultSet.getString("content_checksum"), resultSet.getString("permitted_purpose"),
                resultSet.getString("retention_policy"), resultSet.getBoolean("legal_hold"),
                resultSet.getString("actor_id"), instant(resultSet, "occurred_at"));
    }

    private static Notification mapNotification(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Notification(resultSet.getString("id"), resultSet.getString("event_id"),
                resultSet.getString("target"), resultSet.getString("channel"), resultSet.getString("outcome"),
                resultSet.getString("actor_id"), instant(resultSet, "occurred_at"));
    }
}
