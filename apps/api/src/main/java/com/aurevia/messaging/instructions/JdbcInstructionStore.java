package com.aurevia.messaging.instructions;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.Fact;
import com.aurevia.messaging.instructions.InstructionContract.FactType;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.tenant.TenantContext;

/**
 * PostgreSQL-backed persistence port for MSG-04 typed patient instructions
 * (ARC-01, ARC-02, OPS-02).
 *
 * <p>Migration V64 owns the three tenant tables for templates, revisions and
 * facts. Every operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org} from the active context; forced RLS confines each
 * statement to the caller's organization and every statement also names the
 * active organization explicitly. A missing tenant context fails closed with a
 * 403 before any SQL runs. Roles, verified charts, active connections and staff
 * unit assignment are read from the authoritative V4, V10, V60 and V63 folds,
 * so a revocation takes effect on the next request. Sent revisions are never
 * rewritten: supersede and withdraw advance only the state-bearing columns of
 * a single revision, and facts are append-only.</p>
 */
@Repository
public class JdbcInstructionStore implements InstructionStore {

    private static final String TEMPLATE_COLUMNS =
            "id, unit_id, kind, title, body, language, version, approved_by, approved_at, "
            + "clinical, context_type";
    private static final String REVISION_COLUMNS =
            "thread_id, revision, template_id, kind, title, body, language, author_subject, "
            + "author_role, unit_id, connection_id, patient_id, issued_at, action_due_at, "
            + "context_type, context_id, attachment_ids, state, superseded_by, "
            + "replaces_revision, withdrawal_reason";
    private static final String FACT_COLUMNS =
            "thread_id, revision, type, recipient, occurred_at, note";

    private static final RowMapper<Template> TEMPLATE_MAPPER = JdbcInstructionStore::mapTemplate;
    private static final RowMapper<Revision> REVISION_MAPPER = JdbcInstructionStore::mapRevision;
    private static final RowMapper<Fact> FACT_MAPPER = JdbcInstructionStore::mapFact;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the durable instruction store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcInstructionStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<String> activeRoles(String subject) {
        // The fold is tenant-scoped by forced RLS; deriving the organization here
        // only enforces the fail-closed guard shared by every operation.
        organization();
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject) ORDER BY role_key",
                Map.of("subject", subject), String.class));
    }

    @Override
    public Set<String> verifiedPatientCharts(String subject) {
        organization();
        return transactions.execute(status -> Set.copyOf(jdbc.queryForList("""
                SELECT chart_id::text FROM public.current_chart_links()
                 WHERE account_subject = :subject AND event = 'linked'
                """, Map.of("subject", subject), String.class)));
    }

    @Override
    public boolean staffAssignedToUnit(String subject, String unitId) {
        String organization = organization();
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.instruction_fact_unit_assignment
                                WHERE organization_id = CAST(:organizationId AS uuid)
                                  AND subject = :subject AND unit_id::text = :unitId)
                """, new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("subject", subject).addValue("unitId", unitId), Boolean.class)));
    }

    @Override
    public boolean connectionActive(String connectionId, String patientId, String unitId) {
        organization();
        // The port carries a connection identifier, but the authoritative V60
        // fold is keyed by patient and unit: any active verified connection
        // between them authorizes access, so no instruction-owned copy is kept.
        // unitId is an opaque contract key, so compare as text rather than cast.
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_unit_connection
                                WHERE patient_id = CAST(:patientId AS uuid)
                                  AND unit_id::text = :unitId
                                  AND state = 'active')
                """, new MapSqlParameterSource().addValue("patientId", patientId)
                        .addValue("unitId", unitId), Boolean.class)));
    }

    @Override
    public Optional<Template> approvedTemplate(String unitId, String templateId) {
        String organization = organization();
        return transactions.execute(status -> jdbc.query(
                "SELECT " + TEMPLATE_COLUMNS + " FROM public.instruction_template "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) AND id = :templateId",
                new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("templateId", templateId), TEMPLATE_MAPPER)
                .stream().findFirst().filter(template -> template.unitId().equals(unitId)));
    }

    @Override
    public void saveTemplate(Template template) {
        String organization = organization();
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.instruction_template
                    (organization_id, id, unit_id, kind, title, body, language, version,
                     approved_by, approved_at, clinical, context_type)
                VALUES (CAST(:organizationId AS uuid), :id, :unitId, :kind, :title, :body,
                        :language, :version, :approvedBy, CAST(:approvedAt AS timestamptz),
                        :clinical, :contextType)
                ON CONFLICT (organization_id, id) DO UPDATE SET
                    unit_id = EXCLUDED.unit_id, kind = EXCLUDED.kind, title = EXCLUDED.title,
                    body = EXCLUDED.body, language = EXCLUDED.language,
                    version = EXCLUDED.version, approved_by = EXCLUDED.approved_by,
                    approved_at = EXCLUDED.approved_at, clinical = EXCLUDED.clinical,
                    context_type = EXCLUDED.context_type
                """, new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("id", template.id()).addValue("unitId", template.unitId())
                        .addValue("kind", template.kind().wire()).addValue("title", template.title())
                        .addValue("body", template.body()).addValue("language", template.language())
                        .addValue("version", template.version()).addValue("approvedBy", template.approvedBy())
                        .addValue("approvedAt", dateTime(template.approvedAt()))
                        .addValue("clinical", template.clinical())
                        .addValue("contextType", wire(template.contextType()))));
    }

    @Override
    public Optional<Revision> current(String threadId) {
        String organization = organization();
        return transactions.execute(status -> jdbc.query(
                "SELECT " + REVISION_COLUMNS + " FROM public.instruction_revision "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND thread_id = :threadId ORDER BY revision DESC LIMIT 1",
                new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("threadId", threadId), REVISION_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<Revision> history(String threadId) {
        String organization = organization();
        return transactions.execute(status -> jdbc.query(
                "SELECT " + REVISION_COLUMNS + " FROM public.instruction_revision "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND thread_id = :threadId ORDER BY revision",
                new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("threadId", threadId), REVISION_MAPPER));
    }

    @Override
    public List<Revision> forPatient(String patientId) {
        String organization = organization();
        // One current revision per thread: the highest database-owned revision
        // number, mirroring the port's current() fold over the patient's threads.
        return transactions.execute(status -> jdbc.query(
                "SELECT DISTINCT ON (thread_id) " + REVISION_COLUMNS
                        + " FROM public.instruction_revision "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND patient_id = :patientId ORDER BY thread_id, revision DESC",
                new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("patientId", patientId), REVISION_MAPPER));
    }

    @Override
    public void save(Revision revision) {
        String organization = organization();
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.instruction_revision
                    (organization_id, thread_id, revision, template_id, kind, title, body,
                     language, author_subject, author_role, unit_id, connection_id, patient_id,
                     issued_at, action_due_at, context_type, context_id, attachment_ids,
                     state, superseded_by, replaces_revision, withdrawal_reason)
                VALUES (CAST(:organizationId AS uuid), :threadId, :revision, :templateId, :kind,
                        :title, :body, :language, :authorSubject, :authorRole, :unitId,
                        :connectionId, :patientId, CAST(:issuedAt AS timestamptz),
                        CAST(:actionDueAt AS timestamptz), :contextType, :contextId,
                        CAST(:attachmentIds AS text[]), :state, :supersededBy,
                        :replacesRevision, :withdrawalReason)
                ON CONFLICT (organization_id, thread_id, revision) DO NOTHING
                """, new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("threadId", revision.threadId()).addValue("revision", revision.revision())
                        .addValue("templateId", revision.templateId()).addValue("kind", revision.kind().wire())
                        .addValue("title", revision.title()).addValue("body", revision.text())
                        .addValue("language", revision.language())
                        .addValue("authorSubject", revision.authorSubject())
                        .addValue("authorRole", revision.authorRole()).addValue("unitId", revision.unitId())
                        .addValue("connectionId", revision.connectionId())
                        .addValue("patientId", revision.patientId())
                        .addValue("issuedAt", dateTime(revision.issuedAt()))
                        .addValue("actionDueAt", dateTime(revision.actionDueAt()))
                        .addValue("contextType", wire(revision.contextType()))
                        .addValue("contextId", revision.contextId())
                        .addValue("attachmentIds", revision.attachmentIds().toArray(new String[0]))
                        .addValue("state", revision.state().wire())
                        .addValue("supersededBy", revision.supersededBy())
                        .addValue("replacesRevision", revision.replacesRevision())
                        .addValue("withdrawalReason", revision.withdrawalReason())));
    }

    @Override
    public void supersede(String threadId, int priorRevision, int newRevision) {
        advanceState(threadId, priorRevision, State.SUPERSEDED, newRevision, null);
    }

    @Override
    public void withdraw(String threadId, int revision, String reason) {
        advanceState(threadId, revision, State.WITHDRAWN, null, reason);
    }

    @Override
    public void append(Fact fact) {
        String organization = organization();
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.instruction_fact
                    (organization_id, thread_id, revision, type, recipient, occurred_at, note)
                VALUES (CAST(:organizationId AS uuid), :threadId, :revision, :type,
                        :recipient, CAST(:occurredAt AS timestamptz), :note)
                """, new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("threadId", fact.threadId()).addValue("revision", fact.revision())
                        .addValue("type", fact.type().wire()).addValue("recipient", fact.recipient())
                        .addValue("occurredAt", dateTime(fact.occurredAt())).addValue("note", fact.note())));
    }

    @Override
    public List<Fact> facts(String threadId) {
        String organization = organization();
        return transactions.execute(status -> jdbc.query(
                "SELECT " + FACT_COLUMNS + " FROM public.instruction_fact "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND thread_id = :threadId ORDER BY seq",
                new MapSqlParameterSource().addValue("organizationId", organization)
                        .addValue("threadId", threadId), FACT_MAPPER));
    }

    /**
     * Advance the state columns of one revision inside the caller's tenant.
     *
     * <p>A thread with no visible revision is a 404, matching the port. A
     * revision number that is absent from an existing thread is a no-op so a
     * stale caller cannot corrupt history. Only state, superseded-by and
     * withdrawal reason change; the sent content stays immutable.</p>
     *
     * @param threadId      owning thread
     * @param revision      revision whose state advances
     * @param state         target lifecycle state
     * @param supersededBy  linked later revision, or {@code null}
     * @param reason        withdrawal reason, or {@code null}
     */
    private void advanceState(String threadId, int revision, State state, Integer supersededBy,
                              String reason) {
        String organization = organization();
        transactions.executeWithoutResult(status -> {
            Integer count = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM public.instruction_revision "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) AND thread_id = :threadId",
                    new MapSqlParameterSource().addValue("organizationId", organization)
                            .addValue("threadId", threadId), Integer.class),
                    "revision count must be present");
            if (count == 0) {
                throw new ApiException(404, "NOT_FOUND", "instruction thread not found");
            }
            jdbc.update("""
                    UPDATE public.instruction_revision
                       SET state = :state, superseded_by = :supersededBy,
                           withdrawal_reason = :withdrawalReason
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND thread_id = :threadId AND revision = :revision
                    """, new MapSqlParameterSource().addValue("organizationId", organization)
                            .addValue("threadId", threadId).addValue("revision", revision)
                            .addValue("state", state.wire()).addValue("supersededBy", supersededBy)
                            .addValue("withdrawalReason", reason));
        });
    }

    /** @return the active organization, or fail closed with a 403 */
    private String organization() {
        TenantContext context = TenantContext.current();
        if (context == null || context.organizationId() == null) {
            throw new ApiException(403, "FORBIDDEN", "no active tenant context");
        }
        return context.organizationId();
    }

    private static Template mapTemplate(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Template(resultSet.getString("id"), resultSet.getString("unit_id"),
                Kind.from(resultSet.getString("kind")), resultSet.getString("title"),
                resultSet.getString("body"), resultSet.getString("language"),
                resultSet.getInt("version"), resultSet.getString("approved_by"),
                instant(resultSet, "approved_at"), resultSet.getBoolean("clinical"),
                Optional.ofNullable(resultSet.getString("context_type")).map(ContextType::from).orElse(null));
    }

    private static Revision mapRevision(ResultSet resultSet, int rowNumber) throws SQLException {
        Array attachments = Objects.requireNonNull(resultSet.getArray("attachment_ids"),
                "attachment ids must be present");
        return new Revision(resultSet.getString("thread_id"), resultSet.getInt("revision"),
                resultSet.getString("template_id"), Kind.from(resultSet.getString("kind")),
                resultSet.getString("title"), resultSet.getString("body"),
                resultSet.getString("language"), resultSet.getString("author_subject"),
                resultSet.getString("author_role"), resultSet.getString("unit_id"),
                resultSet.getString("connection_id"), resultSet.getString("patient_id"),
                instant(resultSet, "issued_at"), nullableInstant(resultSet, "action_due_at"),
                Optional.ofNullable(resultSet.getString("context_type")).map(ContextType::from).orElse(null),
                resultSet.getString("context_id"), List.of((String[]) attachments.getArray()),
                State.from(resultSet.getString("state")),
                resultSet.getObject("superseded_by", Integer.class),
                resultSet.getObject("replaces_revision", Integer.class),
                resultSet.getString("withdrawal_reason"));
    }

    private static Fact mapFact(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Fact(resultSet.getString("thread_id"), resultSet.getInt("revision"),
                FactType.from(resultSet.getString("type")), resultSet.getString("recipient"),
                instant(resultSet, "occurred_at"), resultSet.getString("note"));
    }

    /** @return the wire key of an optional enum, or {@code null} */
    private static String wire(ContextType contextType) {
        return contextType == null ? null : contextType.wire();
    }

    /** @return the driver representation of an optional server instant */
    private static OffsetDateTime dateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Objects.requireNonNull(resultSet.getObject(column, OffsetDateTime.class),
                column + " must be present").toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        return Optional.ofNullable(resultSet.getObject(column, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant).orElse(null);
    }
}
