package com.aurevia.messaging.acknowledgment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Fact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;

/**
 * PostgreSQL-backed persistence boundary for instruction facts. Every
 * operation runs in a tenant-guarded transaction.
 *
 * <p>V63 owns the nine tenant tables and their forced row-level security. Every
 * operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org} and {@code app.actor} after the transaction opens;
 * forced RLS therefore confines each statement to the active organization and
 * every read also names the active organization explicitly. Writes carry the
 * method's {@code organizationId} so the policy's {@code WITH CHECK} rejects a
 * mismatch.</p>
 *
 * <p>Membership and roles are owned by the tenancy module, verified patient
 * charts by patient identity proofing through the {@code current_chart_links()}
 * fold and patient-unit connections by the care-unit directory. This adapter
 * reads those authoritative folds rather than projecting
 * a second copy, so a deactivation or revocation takes effect on the next
 * request. Staff-to-unit assignment has
 * no authoritative durable source yet and is read from the V63 reference table.
 * Immutable facts are inserted with {@code ON CONFLICT DO NOTHING} and read
 * back, so a replay returns the original fact. No clinical payload is persisted:
 * instructions carry only an opaque content reference and dispatches only a
 * generic notification key.</p>
 */
@Repository
public class JdbcInstructionFactStore implements InstructionFactStore {

    private static final String INSTRUCTION_COLUMNS =
            "id::text, organization_id::text, patient_id::text, unit_id::text, revision, "
            + "content_ref, requires_acknowledgment, acknowledgment_due_at";
    private static final String PUBLICATION_COLUMNS =
            "organization_id::text, instruction_id::text, revision, actor, occurred_at";
    private static final String DISPATCH_COLUMNS =
            "organization_id::text, instruction_id::text, revision, generic_key, correlation_id, occurred_at";
    private static final String FIRST_VIEW_COLUMNS =
            "organization_id::text, instruction_id::text, revision, actor, occurred_at";
    private static final String ACKNOWLEDGMENT_COLUMNS =
            "organization_id::text, instruction_id::text, revision, actor, scope, occurred_at";
    private static final String FOLLOW_UP_COLUMNS =
            "organization_id::text, instruction_id::text, revision, unit_id::text, routed_at, "
            + "state, resolved_by, resolved_at, reason";
    private static final String AUDIT_COLUMNS =
            "organization_id::text, subject, action, resource_id, outcome, purpose, scope, "
            + "correlation_id, occurred_at";

    private static final RowMapper<Instruction> INSTRUCTION_MAPPER =
            JdbcInstructionFactStore::mapInstruction;
    private static final RowMapper<Publication> PUBLICATION_MAPPER =
            JdbcInstructionFactStore::mapPublication;
    private static final RowMapper<Dispatch> DISPATCH_MAPPER =
            JdbcInstructionFactStore::mapDispatch;
    private static final RowMapper<FirstView> FIRST_VIEW_MAPPER =
            JdbcInstructionFactStore::mapFirstView;
    private static final RowMapper<Acknowledgment> ACKNOWLEDGMENT_MAPPER =
            JdbcInstructionFactStore::mapAcknowledgment;
    private static final RowMapper<FollowUp> FOLLOW_UP_MAPPER =
            JdbcInstructionFactStore::mapFollowUp;
    private static final RowMapper<AuditFact> AUDIT_MAPPER =
            JdbcInstructionFactStore::mapAudit;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL instruction-fact store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcInstructionFactStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        // organizationId is intentionally unused: the guarded transaction binds
        // the active tenant and the fold is tenant-scoped by RLS.
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)",
                Map.of("subject", subject), Boolean.class)));
    }

    /** {@inheritDoc} */
    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject) ORDER BY role_key",
                Map.of("subject", subject), String.class));
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> verifiedPatientCharts(String organizationId, String subject) {
        return transactions.execute(status -> Set.copyOf(jdbc.queryForList("""
                SELECT chart_id::text FROM public.current_chart_links()
                 WHERE account_subject = :subject AND event = 'linked'
                """, Map.of("subject", subject), String.class)));
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasActiveConnection(String organizationId, String patientId, String unitId) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_unit_connection
                                WHERE patient_id = CAST(:patientId AS uuid)
                                  AND unit_id = CAST(:unitId AS uuid)
                                  AND state = 'active')
                """, new MapSqlParameterSource().addValue("patientId", patientId)
                        .addValue("unitId", unitId), Boolean.class)));
    }

    /** {@inheritDoc} */
    @Override
    public boolean isStaffAssignedToUnit(String organizationId, String subject, String unitId) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.instruction_fact_unit_assignment
                                WHERE organization_id = CAST(:organizationId AS uuid)
                                  AND subject = :subject AND unit_id = CAST(:unitId AS uuid))
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject).addValue("unitId", unitId), Boolean.class)));
    }

    /** {@inheritDoc} */
    @Override
    public Instruction putInstruction(String organizationId, Instruction instruction) {
        if (instruction == null || !organizationId.equals(instruction.organizationId())) {
            throw new IllegalArgumentException("instruction must belong to the active organization");
        }
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.instruction_fact_instruction
                        (organization_id, id, revision, patient_id, unit_id, content_ref,
                         requires_acknowledgment, acknowledgment_due_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :revision,
                            CAST(:patientId AS uuid), CAST(:unitId AS uuid), :contentRef,
                            :requiresAcknowledgment, CAST(:dueAt AS timestamptz))
                    ON CONFLICT (organization_id, id, revision) DO NOTHING
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("id", instruction.id())
                            .addValue("revision", instruction.revision())
                            .addValue("patientId", instruction.patientId())
                            .addValue("unitId", instruction.unitId())
                            .addValue("contentRef", instruction.contentRef())
                            .addValue("requiresAcknowledgment", instruction.requiresAcknowledgment())
                            .addValue("dueAt", instruction.acknowledgmentDueAt() == null
                                    ? null : dateTime(instruction.acknowledgmentDueAt())));
            return jdbc.query("SELECT " + INSTRUCTION_COLUMNS + " FROM public.instruction_fact_instruction "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND id = CAST(:id AS uuid) AND revision = :revision",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("id", instruction.id())
                            .addValue("revision", instruction.revision()), INSTRUCTION_MAPPER)
                    .stream().findFirst().orElse(instruction);
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Instruction> instruction(String organizationId, String instructionId, int revision) {
        return transactions.execute(status -> jdbc.query(
                "SELECT " + INSTRUCTION_COLUMNS + " FROM public.instruction_fact_instruction "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND id = CAST(:id AS uuid) AND revision = :revision",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", instructionId).addValue("revision", revision),
                INSTRUCTION_MAPPER).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Instruction> latestInstruction(String organizationId, String instructionId) {
        return transactions.execute(status -> jdbc.query(
                "SELECT " + INSTRUCTION_COLUMNS + " FROM public.instruction_fact_instruction "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND id = CAST(:id AS uuid) ORDER BY revision DESC LIMIT 1",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", instructionId), INSTRUCTION_MAPPER).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public List<Instruction> instructionsForUnit(String organizationId, String unitId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT i.id::text, i.organization_id::text, i.patient_id::text, i.unit_id::text,
                       i.revision, i.content_ref, i.requires_acknowledgment, i.acknowledgment_due_at
                  FROM public.instruction_fact_instruction i
                 WHERE i.organization_id = CAST(:organizationId AS uuid)
                   AND i.unit_id = CAST(:unitId AS uuid)
                   AND EXISTS (SELECT 1 FROM public.instruction_fact_publication p
                                WHERE p.organization_id = i.organization_id
                                  AND p.instruction_id = i.id AND p.revision = i.revision)
                 ORDER BY i.id, i.revision
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("unitId", unitId), INSTRUCTION_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public Publication putPublication(String organizationId, Publication publication) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.instruction_fact_publication
                        (organization_id, instruction_id, revision, actor, occurred_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:instructionId AS uuid), :revision,
                            :actor, CAST(:occurredAt AS timestamptz))
                    ON CONFLICT (organization_id, instruction_id, revision) DO NOTHING
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", publication.instructionId())
                            .addValue("revision", publication.revision())
                            .addValue("actor", publication.actor())
                            .addValue("occurredAt", dateTime(publication.occurredAt())));
            return jdbc.query("SELECT " + PUBLICATION_COLUMNS
                            + " FROM public.instruction_fact_publication "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", publication.instructionId())
                            .addValue("revision", publication.revision()), PUBLICATION_MAPPER)
                    .stream().findFirst().orElse(publication);
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<Publication> publications(String organizationId, String instructionId, int revision) {
        return transactions.execute(status -> jdbc.query("SELECT " + PUBLICATION_COLUMNS
                        + " FROM public.instruction_fact_publication "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("instructionId", instructionId).addValue("revision", revision),
                PUBLICATION_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public Dispatch appendDispatch(String organizationId, Dispatch dispatch) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.instruction_fact_dispatch
                        (organization_id, instruction_id, revision, generic_key, correlation_id, occurred_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:instructionId AS uuid), :revision,
                            :genericKey, :correlationId, CAST(:occurredAt AS timestamptz))
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", dispatch.instructionId())
                            .addValue("revision", dispatch.revision())
                            .addValue("genericKey", dispatch.genericKey())
                            .addValue("correlationId", dispatch.correlationId())
                            .addValue("occurredAt", dateTime(dispatch.occurredAt())));
            return dispatch;
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<Dispatch> dispatches(String organizationId, String instructionId, int revision) {
        return transactions.execute(status -> jdbc.query("SELECT " + DISPATCH_COLUMNS
                        + " FROM public.instruction_fact_dispatch "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision "
                        + "ORDER BY seq",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("instructionId", instructionId).addValue("revision", revision),
                DISPATCH_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public FirstView putFirstViewIfAbsent(String organizationId, FirstView firstView) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.instruction_fact_first_view
                        (organization_id, instruction_id, revision, actor, occurred_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:instructionId AS uuid), :revision,
                            :actor, CAST(:occurredAt AS timestamptz))
                    ON CONFLICT (organization_id, instruction_id, revision, actor) DO NOTHING
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", firstView.instructionId())
                            .addValue("revision", firstView.revision())
                            .addValue("actor", firstView.actor())
                            .addValue("occurredAt", dateTime(firstView.occurredAt())));
            return jdbc.query("SELECT " + FIRST_VIEW_COLUMNS
                            + " FROM public.instruction_fact_first_view "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND instruction_id = CAST(:instructionId AS uuid) "
                            + "AND revision = :revision AND actor = :actor",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", firstView.instructionId())
                            .addValue("revision", firstView.revision())
                            .addValue("actor", firstView.actor()), FIRST_VIEW_MAPPER)
                    .stream().findFirst().orElse(firstView);
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<FirstView> firstViews(String organizationId, String instructionId, int revision,
                                      String viewer) {
        return transactions.execute(status -> jdbc.query("SELECT " + FIRST_VIEW_COLUMNS
                        + " FROM public.instruction_fact_first_view "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND instruction_id = CAST(:instructionId AS uuid) "
                        + "AND revision = :revision AND actor = :viewer ORDER BY seq",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("instructionId", instructionId).addValue("revision", revision)
                        .addValue("viewer", viewer), FIRST_VIEW_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public Acknowledgment putAcknowledgmentIfAbsent(String organizationId, Acknowledgment acknowledgment) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.instruction_fact_acknowledgment
                        (organization_id, instruction_id, revision, actor, scope, occurred_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:instructionId AS uuid), :revision,
                            :actor, :scope, CAST(:occurredAt AS timestamptz))
                    ON CONFLICT (organization_id, instruction_id, revision, actor) DO NOTHING
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", acknowledgment.instructionId())
                            .addValue("revision", acknowledgment.revision())
                            .addValue("actor", acknowledgment.actor())
                            .addValue("scope", acknowledgment.scope().wire())
                            .addValue("occurredAt", dateTime(acknowledgment.occurredAt())));
            return jdbc.query("SELECT " + ACKNOWLEDGMENT_COLUMNS
                            + " FROM public.instruction_fact_acknowledgment "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND instruction_id = CAST(:instructionId AS uuid) "
                            + "AND revision = :revision AND actor = :actor",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", acknowledgment.instructionId())
                            .addValue("revision", acknowledgment.revision())
                            .addValue("actor", acknowledgment.actor()), ACKNOWLEDGMENT_MAPPER)
                    .stream().findFirst().orElse(acknowledgment);
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<Acknowledgment> acknowledgments(String organizationId, String instructionId, int revision) {
        return transactions.execute(status -> jdbc.query("SELECT " + ACKNOWLEDGMENT_COLUMNS
                        + " FROM public.instruction_fact_acknowledgment "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision "
                        + "ORDER BY occurred_at, actor",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("instructionId", instructionId).addValue("revision", revision),
                ACKNOWLEDGMENT_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public FollowUp putFollowUpIfAbsent(String organizationId, FollowUp followUp) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.instruction_fact_follow_up
                        (organization_id, instruction_id, revision, unit_id, routed_at, state,
                         resolved_by, resolved_at, reason)
                    VALUES (CAST(:organizationId AS uuid), CAST(:instructionId AS uuid), :revision,
                            CAST(:unitId AS uuid), CAST(:routedAt AS timestamptz), :state,
                            :resolvedBy, CAST(:resolvedAt AS timestamptz), :reason)
                    ON CONFLICT (organization_id, instruction_id, revision) DO NOTHING
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", followUp.instructionId())
                            .addValue("revision", followUp.revision())
                            .addValue("unitId", followUp.unitId())
                            .addValue("routedAt", dateTime(followUp.routedAt()))
                            .addValue("state", followUp.state().wire())
                            .addValue("resolvedBy", followUp.resolvedBy())
                            .addValue("resolvedAt", followUp.resolvedAt() == null
                                    ? null : dateTime(followUp.resolvedAt()))
                            .addValue("reason", followUp.reason()));
            return jdbc.query("SELECT " + FOLLOW_UP_COLUMNS
                            + " FROM public.instruction_fact_follow_up "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", followUp.instructionId())
                            .addValue("revision", followUp.revision()), FOLLOW_UP_MAPPER)
                    .stream().findFirst().orElse(followUp);
        });
    }

    /** {@inheritDoc} */
    @Override
    public Optional<FollowUp> followUps(String organizationId, String instructionId, int revision) {
        return transactions.execute(status -> jdbc.query("SELECT " + FOLLOW_UP_COLUMNS
                        + " FROM public.instruction_fact_follow_up "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) "
                        + "AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("instructionId", instructionId).addValue("revision", revision),
                FOLLOW_UP_MAPPER).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public FollowUp resolveFollowUp(String organizationId, String instructionId, int revision,
                                    String resolver, String reason, Instant resolvedAt) {
        return transactions.execute(status -> {
            Optional<FollowUp> current = followUps(organizationId, instructionId, revision);
            if (current.isEmpty()) {
                throw new IllegalStateException("no follow-up to resolve");
            }
            FollowUp existing = current.orElseThrow();
            jdbc.update("""
                    UPDATE public.instruction_fact_follow_up
                       SET state = :state, resolved_by = :resolver,
                           resolved_at = CAST(:resolvedAt AS timestamptz), reason = :reason
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND instruction_id = CAST(:instructionId AS uuid) AND revision = :revision
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("instructionId", instructionId).addValue("revision", revision)
                            .addValue("state", FollowUpState.RESOLVED.wire())
                            .addValue("resolver", resolver)
                            .addValue("resolvedAt", dateTime(resolvedAt))
                            .addValue("reason", reason));
            return new FollowUp(existing.organizationId(), existing.instructionId(),
                    existing.revision(), existing.unitId(), existing.routedAt(),
                    FollowUpState.RESOLVED, resolver, resolvedAt, reason);
        });
    }

    /** {@inheritDoc} */
    @Override
    public List<FollowUp> allFollowUps(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + FOLLOW_UP_COLUMNS
                        + " FROM public.instruction_fact_follow_up "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) ORDER BY seq",
                Map.of("organizationId", organizationId), FOLLOW_UP_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public List<Instruction> overdueCandidates(String organizationId, Instant now) {
        return transactions.execute(status -> jdbc.query("""
                SELECT i.id::text, i.organization_id::text, i.patient_id::text, i.unit_id::text,
                       i.revision, i.content_ref, i.requires_acknowledgment, i.acknowledgment_due_at
                  FROM public.instruction_fact_instruction i
                 WHERE i.organization_id = CAST(:organizationId AS uuid)
                   AND i.requires_acknowledgment
                   AND i.acknowledgment_due_at IS NOT NULL
                   AND i.acknowledgment_due_at < CAST(:now AS timestamptz)
                   AND i.revision = (SELECT MAX(latest.revision)
                                       FROM public.instruction_fact_instruction latest
                                      WHERE latest.organization_id = i.organization_id
                                        AND latest.id = i.id)
                   AND NOT EXISTS (SELECT 1 FROM public.instruction_fact_acknowledgment a
                                    WHERE a.organization_id = i.organization_id
                                      AND a.instruction_id = i.id AND a.revision = i.revision)
                   AND NOT EXISTS (SELECT 1 FROM public.instruction_fact_follow_up f
                                    WHERE f.organization_id = i.organization_id
                                      AND f.instruction_id = i.id AND f.revision = i.revision)
                 ORDER BY i.id, i.revision
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("now", dateTime(now)), INSTRUCTION_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public boolean putClientDigest(String organizationId, String subject, String action, String clientId,
                                   String digest) {
        return transactions.execute(status -> jdbc.update("""
                INSERT INTO public.instruction_fact_client_digest
                    (organization_id, subject, action, client_id, digest)
                VALUES (CAST(:organizationId AS uuid), :subject, :action, :clientId, :digest)
                ON CONFLICT (organization_id, subject, action, client_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject).addValue("action", action)
                        .addValue("clientId", clientId).addValue("digest", digest)) == 1);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> clientDigest(String organizationId, String subject, String action,
                                         String clientId) {
        return transactions.execute(status -> jdbc.queryForList("""
                SELECT digest FROM public.instruction_fact_client_digest
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND subject = :subject AND action = :action AND client_id = :clientId
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject).addValue("action", action)
                        .addValue("clientId", clientId), String.class).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public int clientDigestCount(String organizationId, String subject, String action) {
        return transactions.execute(status -> Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.instruction_fact_client_digest
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND subject = :subject AND action = :action
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject).addValue("action", action), Long.class),
                "client digest count must be present").intValue());
    }

    /** {@inheritDoc} */
    @Override
    public void appendAudit(AuditFact fact) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.instruction_fact_audit
                    (organization_id, subject, action, resource_id, outcome, purpose, scope,
                     correlation_id, occurred_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :action, :resourceId, :outcome,
                        :purpose, :scope, :correlationId, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("organizationId", fact.organizationId())
                        .addValue("subject", fact.subject()).addValue("action", fact.action())
                        .addValue("resourceId", fact.resourceId()).addValue("outcome", fact.outcome())
                        .addValue("purpose", fact.purpose()).addValue("scope", fact.scope())
                        .addValue("correlationId", fact.correlationId())
                        .addValue("occurredAt", dateTime(fact.occurredAt()))));
    }

    /** {@inheritDoc} */
    @Override
    public List<AuditFact> auditEvents(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + AUDIT_COLUMNS
                        + " FROM public.instruction_fact_audit "
                        + "WHERE organization_id = CAST(:organizationId AS uuid) ORDER BY seq",
                Map.of("organizationId", organizationId), AUDIT_MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public List<Fact> allFacts(String organizationId, String instructionId) {
        return transactions.execute(status -> {
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("organizationId", organizationId).addValue("instructionId", instructionId);
            List<Fact> facts = new ArrayList<>();
            facts.addAll(jdbc.query("SELECT " + PUBLICATION_COLUMNS
                    + " FROM public.instruction_fact_publication "
                    + "WHERE organization_id = CAST(:organizationId AS uuid) "
                    + "AND instruction_id = CAST(:instructionId AS uuid) ORDER BY seq",
                    parameters, PUBLICATION_MAPPER));
            facts.addAll(jdbc.query("SELECT " + DISPATCH_COLUMNS
                    + " FROM public.instruction_fact_dispatch "
                    + "WHERE organization_id = CAST(:organizationId AS uuid) "
                    + "AND instruction_id = CAST(:instructionId AS uuid) ORDER BY seq",
                    parameters, DISPATCH_MAPPER));
            facts.addAll(jdbc.query("SELECT " + FIRST_VIEW_COLUMNS
                    + " FROM public.instruction_fact_first_view "
                    + "WHERE organization_id = CAST(:organizationId AS uuid) "
                    + "AND instruction_id = CAST(:instructionId AS uuid) ORDER BY seq",
                    parameters, FIRST_VIEW_MAPPER));
            facts.addAll(jdbc.query("SELECT " + ACKNOWLEDGMENT_COLUMNS
                    + " FROM public.instruction_fact_acknowledgment "
                    + "WHERE organization_id = CAST(:organizationId AS uuid) "
                    + "AND instruction_id = CAST(:instructionId AS uuid) ORDER BY seq",
                    parameters, ACKNOWLEDGMENT_MAPPER));
            facts.addAll(jdbc.query("SELECT " + FOLLOW_UP_COLUMNS
                    + " FROM public.instruction_fact_follow_up "
                    + "WHERE organization_id = CAST(:organizationId AS uuid) "
                    + "AND instruction_id = CAST(:instructionId AS uuid) ORDER BY seq",
                    parameters, FOLLOW_UP_MAPPER));
            return List.copyOf(facts);
        });
    }

    private static Instruction mapInstruction(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Instruction(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("patient_id"), resultSet.getString("unit_id"),
                resultSet.getInt("revision"), resultSet.getString("content_ref"),
                resultSet.getBoolean("requires_acknowledgment"),
                nullableInstant(resultSet, "acknowledgment_due_at"));
    }

    private static Publication mapPublication(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Publication(resultSet.getString("organization_id"),
                resultSet.getString("instruction_id"), resultSet.getInt("revision"),
                resultSet.getString("actor"), instant(resultSet, "occurred_at"));
    }

    private static Dispatch mapDispatch(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Dispatch(resultSet.getString("organization_id"),
                resultSet.getString("instruction_id"), resultSet.getInt("revision"),
                resultSet.getString("generic_key"), resultSet.getString("correlation_id"),
                instant(resultSet, "occurred_at"));
    }

    private static FirstView mapFirstView(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FirstView(resultSet.getString("organization_id"),
                resultSet.getString("instruction_id"), resultSet.getInt("revision"),
                resultSet.getString("actor"), instant(resultSet, "occurred_at"));
    }

    private static Acknowledgment mapAcknowledgment(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Acknowledgment(resultSet.getString("organization_id"),
                resultSet.getString("instruction_id"), resultSet.getInt("revision"),
                resultSet.getString("actor"), AcknowledgmentScope.from(resultSet.getString("scope")),
                instant(resultSet, "occurred_at"));
    }

    private static FollowUp mapFollowUp(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FollowUp(resultSet.getString("organization_id"),
                resultSet.getString("instruction_id"), resultSet.getInt("revision"),
                resultSet.getString("unit_id"), instant(resultSet, "routed_at"),
                FollowUpState.from(resultSet.getString("state")), resultSet.getString("resolved_by"),
                nullableInstant(resultSet, "resolved_at"), resultSet.getString("reason"));
    }

    private static AuditFact mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditFact(resultSet.getString("organization_id"), resultSet.getString("subject"),
                resultSet.getString("action"), resultSet.getString("resource_id"),
                resultSet.getString("outcome"), resultSet.getString("purpose"),
                resultSet.getString("scope"), resultSet.getString("correlation_id"),
                instant(resultSet, "occurred_at"));
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * <p>pgjdbc cannot infer a SQL type for {@link java.time.Instant}; the UTC
     * offset keeps the wire value unambiguous.</p>
     *
     * @param instant server instant
     * @return an offset date time
     */
    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Read a non-null timestamptz column as a server instant.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant
     * @throws SQLException when the column cannot be read
     */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    /**
     * Read a nullable timestamptz column, preserving "not yet recorded" as null.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant, or {@code null} when the column is null
     * @throws SQLException when the column cannot be read
     */
    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
