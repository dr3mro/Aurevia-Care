package com.aurevia.therapy;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.Evaluation;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Intervention;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.OutcomeReading;
import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.ReturnAcknowledgment;
import com.aurevia.therapy.TherapyContract.Summary;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;

/**
 * PostgreSQL-backed persistence boundary for the PT-01 therapy workflow (ARC-02).
 *
 * <p>V77 owns the therapy tables and V85 adds evaluation-problem storage plus the
 * atomic scale-version counter. Every statement runs in the tenant-guarded
 * transaction, which binds {@code app.current_org()} after the transaction opens;
 * forced row-level security confines each statement to the active organization.
 * The organization is also written explicitly on inserts so the policy's
 * {@code WITH CHECK} rejects a mismatch. Append-only facts are written with
 * {@code ON CONFLICT DO NOTHING} and the signed-note update is restricted to
 * unsigned rows, so re-saving an aggregate can never rewrite a signed note.</p>
 */
@Repository
public class JdbcTherapyStore implements TherapyStore {

    static final RowMapper<Episode> EPISODE_MAPPER = JdbcTherapyStore::mapEpisode;
    static final RowMapper<Evaluation> EVALUATION_MAPPER = JdbcTherapyStore::mapEvaluation;
    static final RowMapper<String> PROBLEM_MAPPER = (resultSet, row) -> resultSet.getString("problem");
    static final RowMapper<Measurement> BASELINE_MAPPER = JdbcTherapyStore::mapMeasurement;
    static final RowMapper<Goal> GOAL_MAPPER = JdbcTherapyStore::mapGoal;
    static final RowMapper<Intervention> INTERVENTION_MAPPER = JdbcTherapyStore::mapIntervention;
    static final RowMapper<OutcomeReading> READING_MAPPER = JdbcTherapyStore::mapReading;
    static final RowMapper<ClinicalNote> NOTE_MAPPER = JdbcTherapyStore::mapNote;
    static final RowMapper<Summary> SUMMARY_MAPPER = JdbcTherapyStore::mapSummary;
    static final RowMapper<ReturnAcknowledgment> ACK_MAPPER = JdbcTherapyStore::mapAcknowledgment;
    static final RowMapper<OutcomeScale> SCALE_MAPPER = JdbcTherapyStore::mapScale;
    static final RowMapper<TherapyAuditEvent> AUDIT_MAPPER = JdbcTherapyStore::mapAudit;

    private static final String EPISODE_COLUMNS = """
            SELECT e.id::text AS id, e.organization_id::text AS organization_id, e.patient_id::text AS patient_id,
                   e.referral_id, e.referrer_org, e.referrer_subject, e.state, e.version,
                   e.created_at, e.received_at, e.clinical_question
              FROM public.therapy_episode e
             WHERE e.organization_id = CAST(:organizationId AS uuid) AND e.id = CAST(:episodeId AS uuid)
            """;
    private static final String EVALUATION_COLUMNS = """
            SELECT ev.episode_id::text AS episode_id, ev.version, ev.author, ev.recorded_at,
                   ev.frequency_per_week, ev.duration_weeks
              FROM public.therapy_evaluation ev
             WHERE ev.organization_id = CAST(:organizationId AS uuid) AND ev.episode_id = CAST(:episodeId AS uuid)
            """;
    private static final String PROBLEM_COLUMNS = """
            SELECT p.problem FROM public.therapy_evaluation_problem p
             WHERE p.organization_id = CAST(:organizationId AS uuid) AND p.episode_id = CAST(:episodeId AS uuid)
             ORDER BY p.ordinal
            """;
    private static final String BASELINE_COLUMNS = """
            SELECT b.code, b.value, b.unit, b.observed_at FROM public.therapy_baseline_measurement b
             WHERE b.organization_id = CAST(:organizationId AS uuid) AND b.episode_id = CAST(:episodeId AS uuid)
             ORDER BY b.code
            """;
    private static final String GOAL_COLUMNS = """
            SELECT g.description, g.target, g.target_date FROM public.therapy_goal g
             WHERE g.organization_id = CAST(:organizationId AS uuid) AND g.episode_id = CAST(:episodeId AS uuid)
             ORDER BY g.ordinal
            """;
    private static final String INTERVENTION_COLUMNS = """
            SELECT i.description, i.performed_at, i.author FROM public.therapy_intervention i
             WHERE i.organization_id = CAST(:organizationId AS uuid) AND i.episode_id = CAST(:episodeId AS uuid)
             ORDER BY i.created_at, i.id
            """;
    private static final String READING_COLUMNS = """
            SELECT r.scale_key, r.scale_version, r.value, r.unit, r.observed_at
              FROM public.therapy_outcome_reading r
             WHERE r.organization_id = CAST(:organizationId AS uuid) AND r.episode_id = CAST(:episodeId AS uuid)
             ORDER BY r.created_at, r.id
            """;
    private static final String NOTE_COLUMNS = """
            SELECT n.kind, n.version, n.patient_id::text AS patient_id, n.content, n.author,
                   n.effective_at, n.template_version, n.signed, n.signed_by, n.signed_at,
                   n.attestation, n.content_checksum
              FROM public.therapy_clinical_note n
             WHERE n.organization_id = CAST(:organizationId AS uuid) AND n.episode_id = CAST(:episodeId AS uuid)
             ORDER BY n.kind, n.version
            """;
    private static final String SUMMARY_COLUMNS = """
            SELECT s.version, s.content, s.referrer_org, s.referrer_subject, s.approved,
                   s.approved_by, s.sent_at
              FROM public.therapy_summary s
             WHERE s.organization_id = CAST(:organizationId AS uuid) AND s.episode_id = CAST(:episodeId AS uuid)
             ORDER BY s.version DESC
            """;
    private static final String ACKNOWLEDGMENT_COLUMNS = """
            SELECT a.summary_version, a.received_at, a.received_by, a.note
              FROM public.therapy_return_acknowledgment a
             WHERE a.organization_id = CAST(:organizationId AS uuid) AND a.episode_id = CAST(:episodeId AS uuid)
               AND a.summary_version = :summaryVersion
            """;
    private static final String SCALE_COLUMNS = """
            SELECT sc.organization_id::text AS organization_id, sc.key, sc.version, sc.description, sc.unit,
                   ARRAY(SELECT value FROM jsonb_array_elements_text(sc.permitted_values) WITH ORDINALITY AS t(value, ord)
                          ORDER BY ord) AS permitted_values,
                   sc.approved, sc.approved_by
              FROM public.therapy_outcome_scale sc
             WHERE sc.organization_id = CAST(:organizationId AS uuid) AND sc.key = :key
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL therapy store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcTherapyStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<TherapyAggregate> find(String organizationId, String episodeId) {
        return transactions.execute(status -> jdbc.query(EPISODE_COLUMNS, episodeParams(organizationId, episodeId),
                EPISODE_MAPPER).stream().findFirst().map(episode -> reconstruct(organizationId, episode)));
    }

    @Override
    public void save(TherapyAggregate aggregate) {
        Episode episode = aggregate.episode();
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO public.therapy_episode
                        (organization_id, id, version, patient_id, referral_id, referrer_org,
                         referrer_subject, state, created_at, received_at, clinical_question)
                    VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :version,
                            CAST(:patientId AS uuid), :referralId, :referrerOrg, :referrerSubject,
                            :state, CAST(:createdAt AS timestamptz), :receivedAt, :clinicalQuestion)
                    ON CONFLICT (organization_id, id) DO UPDATE SET
                        version = EXCLUDED.version, state = EXCLUDED.state
                    """, episodeParams(episode.organizationId(), episode.id())
                            .addValue("id", episode.id())
                            .addValue("version", episode.version())
                            .addValue("patientId", episode.patientId())
                            .addValue("referralId", episode.referralId())
                            .addValue("referrerOrg", episode.referrerOrg())
                            .addValue("referrerSubject", episode.referrerSubject())
                            .addValue("state", episode.state().name().toLowerCase(Locale.ROOT))
                            .addValue("createdAt", dateTime(episode.createdAt()))
                            .addValue("receivedAt", episode.receivedAt())
                            .addValue("clinicalQuestion", episode.clinicalQuestion()));

            Evaluation evaluation = aggregate.evaluation();
            if (evaluation != null) {
                jdbc.update("""
                        INSERT INTO public.therapy_evaluation
                            (organization_id, episode_id, version, author, recorded_at,
                             frequency_per_week, duration_weeks)
                        VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :version,
                                :author, CAST(:recordedAt AS timestamptz), :frequency, :duration)
                        ON CONFLICT (organization_id, episode_id) DO NOTHING
                        """, episodeParams(episode.organizationId(), episode.id())
                                .addValue("version", evaluation.version())
                                .addValue("author", evaluation.author())
                                .addValue("recordedAt", dateTime(evaluation.recordedAt()))
                                .addValue("frequency", evaluation.frequencyPerWeek())
                                .addValue("duration", evaluation.durationWeeks()));
                int ordinal = 0;
                for (String problem : evaluation.problems()) {
                    jdbc.update("""
                            INSERT INTO public.therapy_evaluation_problem
                                (organization_id, episode_id, ordinal, problem)
                            VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :ordinal, :problem)
                            ON CONFLICT (organization_id, episode_id, ordinal) DO NOTHING
                            """, episodeParams(episode.organizationId(), episode.id())
                                    .addValue("ordinal", ++ordinal).addValue("problem", problem));
                }
                for (Measurement baseline : evaluation.baselines()) {
                    jdbc.update("""
                            INSERT INTO public.therapy_baseline_measurement
                                (organization_id, episode_id, code, value, unit, observed_at)
                            VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :code,
                                    :value, :unit, :observedAt)
                            ON CONFLICT (organization_id, episode_id, code) DO NOTHING
                            """, episodeParams(episode.organizationId(), episode.id())
                                    .addValue("code", baseline.code()).addValue("value", baseline.value())
                                    .addValue("unit", baseline.unit()).addValue("observedAt", baseline.observedAt()));
                }
                int goalOrdinal = 0;
                for (Goal goal : evaluation.goals()) {
                    jdbc.update("""
                            INSERT INTO public.therapy_goal
                                (organization_id, episode_id, ordinal, description, target, target_date)
                            VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :ordinal,
                                    :description, :target, :targetDate)
                            ON CONFLICT (organization_id, episode_id, ordinal) DO NOTHING
                            """, episodeParams(episode.organizationId(), episode.id())
                                    .addValue("ordinal", ++goalOrdinal)
                                    .addValue("description", goal.description()).addValue("target", goal.target())
                                    .addValue("targetDate", goal.targetDate()));
                }
            }
            for (OutcomeReading reading : aggregate.readings()) {
                jdbc.update("""
                        INSERT INTO public.therapy_outcome_reading
                            (organization_id, id, episode_id, scale_key, scale_version, value, unit, observed_at)
                        VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:episodeId AS uuid),
                                :scaleKey, :scaleVersion, :value, :unit, :observedAt)
                        ON CONFLICT (organization_id, id) DO NOTHING
                        """, episodeParams(episode.organizationId(), episode.id())
                                .addValue("id", deterministicId(episode.id(), "reading", reading.scaleKey(),
                                        reading.scaleVersion(), reading.value(), reading.observedAt()))
                                .addValue("scaleKey", reading.scaleKey())
                                .addValue("scaleVersion", reading.scaleVersion()).addValue("value", reading.value())
                                .addValue("unit", reading.unit()).addValue("observedAt", reading.observedAt()));
            }
            for (Intervention intervention : aggregate.interventions()) {
                jdbc.update("""
                        INSERT INTO public.therapy_intervention
                            (organization_id, id, episode_id, description, performed_at, author)
                        VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:episodeId AS uuid),
                                :description, :performedAt, :author)
                        ON CONFLICT (organization_id, id) DO NOTHING
                        """, episodeParams(episode.organizationId(), episode.id())
                                .addValue("id", deterministicId(episode.id(), "intervention",
                                        intervention.description(), intervention.performedAt(), intervention.author()))
                                .addValue("description", intervention.description())
                                .addValue("performedAt", intervention.performedAt())
                                .addValue("author", intervention.author()));
            }
            for (ClinicalNote note : aggregate.notes()) {
                jdbc.update("""
                        INSERT INTO public.therapy_clinical_note
                            (organization_id, episode_id, kind, version, patient_id, content, author,
                             effective_at, template_version)
                        VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :kind, :version,
                                CAST(:patientId AS uuid), :content, :author, :effectiveAt, :templateVersion)
                        ON CONFLICT (organization_id, episode_id, kind, version) DO NOTHING
                        """, episodeParams(episode.organizationId(), episode.id())
                                .addValue("kind", note.kind().name().toLowerCase(Locale.ROOT))
                                .addValue("version", note.version()).addValue("patientId", note.patientId())
                                .addValue("content", note.content()).addValue("author", note.author())
                                .addValue("effectiveAt", note.effectiveAt())
                                .addValue("templateVersion", note.templateVersion()));
            }
            for (ClinicalNote note : aggregate.notes()) {
                if (note.signed()) {
                    jdbc.update("""
                            UPDATE public.therapy_clinical_note SET signed = true, signed_by = :signedBy,
                                   signed_at = CAST(:signedAt AS timestamptz), attestation = :attestation,
                                   content_checksum = :checksum
                             WHERE organization_id = CAST(:organizationId AS uuid)
                               AND episode_id = CAST(:episodeId AS uuid) AND kind = :kind
                               AND version = :version AND signed = false
                            """, episodeParams(episode.organizationId(), episode.id())
                                    .addValue("kind", note.kind().name().toLowerCase(Locale.ROOT))
                                    .addValue("version", note.version()).addValue("signedBy", note.signedBy())
                                    .addValue("signedAt", dateTime(note.signedAt()))
                                    .addValue("attestation", note.attestation())
                                    .addValue("checksum", note.contentChecksum()));
                }
            }
            Summary summary = aggregate.summary();
            if (summary != null) {
                jdbc.update("""
                        INSERT INTO public.therapy_summary
                            (organization_id, episode_id, version, content, referrer_org, referrer_subject,
                             approved, approved_by, sent_at)
                        VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :version, :content,
                                :referrerOrg, :referrerSubject, :approved, :approvedBy,
                                CAST(:sentAt AS timestamptz))
                        ON CONFLICT (organization_id, episode_id, version) DO NOTHING
                        """, episodeParams(episode.organizationId(), episode.id())
                                .addValue("version", summary.version()).addValue("content", summary.content())
                                .addValue("referrerOrg", summary.referrerOrg())
                                .addValue("referrerSubject", summary.referrerSubject())
                                .addValue("approved", summary.approved()).addValue("approvedBy", summary.approvedBy())
                                .addValue("sentAt", dateTime(summary.sentAt())));
            }
            ReturnAcknowledgment acknowledgment = aggregate.acknowledgment();
            if (acknowledgment != null) {
                jdbc.update("""
                        INSERT INTO public.therapy_return_acknowledgment
                            (organization_id, episode_id, summary_version, received_at, received_by, note)
                        VALUES (CAST(:organizationId AS uuid), CAST(:episodeId AS uuid), :summaryVersion,
                                :receivedAt, :receivedBy, :note)
                        ON CONFLICT (organization_id, episode_id, summary_version) DO NOTHING
                        """, episodeParams(episode.organizationId(), episode.id())
                                .addValue("summaryVersion", acknowledgment.summaryVersion())
                                .addValue("receivedAt", acknowledgment.receivedAt())
                                .addValue("receivedBy", acknowledgment.receivedBy())
                                .addValue("note", acknowledgment.note()));
            }
        });
    }

    @Override
    public boolean hasReferral(String organizationId, String referralId) {
        Boolean exists = transactions.execute(status -> jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.therapy_episode
                                WHERE organization_id = CAST(:organizationId AS uuid)
                                  AND referral_id = :referralId)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("referralId", referralId), Boolean.class));
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<OutcomeScale> scale(String organizationId, String key, int version) {
        return transactions.execute(status -> jdbc.query(SCALE_COLUMNS + " AND sc.version = :version",
                scaleParams(organizationId, key).addValue("version", version), SCALE_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<OutcomeScale> scales(String organizationId, String key) {
        return transactions.execute(status -> jdbc.query(SCALE_COLUMNS + " ORDER BY sc.version",
                scaleParams(organizationId, key), SCALE_MAPPER));
    }

    @Override
    public int allocateScaleVersion(String organizationId, String key) {
        Integer next = transactions.execute(status -> jdbc.queryForObject("""
                INSERT INTO public.therapy_scale_sequence (organization_id, key, next_version)
                VALUES (CAST(:organizationId AS uuid), :key, 1)
                ON CONFLICT (organization_id, key)
                DO UPDATE SET next_version = public.therapy_scale_sequence.next_version + 1
                RETURNING next_version
                """, scaleParams(organizationId, key), Integer.class));
        return Objects.requireNonNull(next, "scale version allocation returned no row");
    }

    @Override
    public void saveScale(OutcomeScale scale) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.therapy_outcome_scale
                    (organization_id, key, version, description, unit, permitted_values, approved, approved_by)
                VALUES (CAST(:organizationId AS uuid), :key, :version, :description, :unit,
                        to_jsonb(CAST(:permittedValues AS text[])), :approved, :approvedBy)
                ON CONFLICT (organization_id, key, version) DO UPDATE SET
                    approved = EXCLUDED.approved, approved_by = EXCLUDED.approved_by
                """, scaleParams(scale.organizationId(), scale.key())
                        .addValue("version", scale.version()).addValue("description", scale.description())
                        .addValue("unit", scale.unit())
                        .addValue("permittedValues", scale.permittedValues().toArray(new String[0]))
                        .addValue("approved", scale.approved()).addValue("approvedBy", scale.approvedBy())));
    }

    @Override
    public List<TherapyAuditEvent> audit(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT a.organization_id::text AS organization_id, a.actor_subject, a.action,
                       a.resource_id, a.outcome
                  FROM public.therapy_audit_event a
                 WHERE a.organization_id = CAST(:organizationId AS uuid)
                 ORDER BY a.seq
                """, new MapSqlParameterSource().addValue("organizationId", organizationId), AUDIT_MAPPER));
    }

    @Override
    public void appendAudit(TherapyAuditEvent event) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.therapy_audit_event
                    (organization_id, actor_subject, action, resource_id, outcome)
                VALUES (CAST(:organizationId AS uuid), :actorSubject, :action, :resourceId, :outcome)
                """, new MapSqlParameterSource().addValue("organizationId", event.organizationId())
                        .addValue("actorSubject", event.actorSubject()).addValue("action", event.action())
                        .addValue("resourceId", event.resourceId()).addValue("outcome", event.outcome())));
    }

    private TherapyAggregate reconstruct(String organizationId, Episode episode) {
        MapSqlParameterSource params = episodeParams(organizationId, episode.id());
        List<String> problems = jdbc.query(PROBLEM_COLUMNS, params, PROBLEM_MAPPER);
        List<Measurement> baselines = jdbc.query(BASELINE_COLUMNS, params, BASELINE_MAPPER);
        List<Goal> goals = jdbc.query(GOAL_COLUMNS, params, GOAL_MAPPER);
        Evaluation base = jdbc.query(EVALUATION_COLUMNS, params, EVALUATION_MAPPER)
                .stream().findFirst().orElse(null);
        Evaluation evaluation = base == null ? null : new Evaluation(episode.id(), episode.patientId(),
                problems, baselines, goals, base.frequencyPerWeek(), base.durationWeeks(), base.author(),
                base.recordedAt(), base.version());
        List<Intervention> interventions = jdbc.query(INTERVENTION_COLUMNS, params, INTERVENTION_MAPPER);
        List<OutcomeReading> readings = jdbc.query(READING_COLUMNS, params, READING_MAPPER);
        List<ClinicalNote> notes = jdbc.query(NOTE_COLUMNS, params, NOTE_MAPPER);
        Summary summary = jdbc.query(SUMMARY_COLUMNS, params, SUMMARY_MAPPER).stream().findFirst().orElse(null);
        ReturnAcknowledgment acknowledgment = summary == null ? null
                : jdbc.query(ACKNOWLEDGMENT_COLUMNS, params.addValue("summaryVersion", summary.version()),
                        ACK_MAPPER).stream().findFirst().orElse(null);
        return new TherapyAggregate(episode, evaluation, interventions, readings, notes, summary, acknowledgment);
    }

    private static MapSqlParameterSource episodeParams(String organizationId, String episodeId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("episodeId", episodeId);
    }

    private static MapSqlParameterSource scaleParams(String organizationId, String key) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("key", key);
    }

    private static String deterministicId(String episodeId, String kind, Object... parts) {
        StringBuilder seed = new StringBuilder(episodeId).append('|').append(kind);
        for (Object part : parts) {
            seed.append('|').append(part);
        }
        return UUID.nameUUIDFromBytes(seed.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant must be present"), ZoneOffset.UTC);
    }

    private static Episode mapEpisode(ResultSet resultSet, int row) throws SQLException {
        return new Episode(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("patient_id"), resultSet.getString("referral_id"),
                resultSet.getString("referrer_org"), resultSet.getString("referrer_subject"),
                EpisodeState.valueOf(resultSet.getString("state").toUpperCase(Locale.ROOT)),
                resultSet.getInt("version"), instant(resultSet, "created_at"),
                resultSet.getString("received_at"), resultSet.getString("clinical_question"));
    }

    private static Evaluation mapEvaluation(ResultSet resultSet, int row) throws SQLException {
        return new Evaluation(resultSet.getString("episode_id"), null, List.of(), List.of(), List.of(),
                resultSet.getInt("frequency_per_week"), resultSet.getInt("duration_weeks"),
                resultSet.getString("author"), instant(resultSet, "recorded_at"), resultSet.getInt("version"));
    }

    private static Measurement mapMeasurement(ResultSet resultSet, int row) throws SQLException {
        return new Measurement(resultSet.getString("code"), resultSet.getString("value"),
                resultSet.getString("unit"), resultSet.getString("observed_at"));
    }

    private static Goal mapGoal(ResultSet resultSet, int row) throws SQLException {
        return new Goal(resultSet.getString("description"), resultSet.getString("target"),
                resultSet.getString("target_date"));
    }

    private static Intervention mapIntervention(ResultSet resultSet, int row) throws SQLException {
        return new Intervention(resultSet.getString("description"), resultSet.getString("performed_at"),
                resultSet.getString("author"));
    }

    private static OutcomeReading mapReading(ResultSet resultSet, int row) throws SQLException {
        return new OutcomeReading(resultSet.getString("scale_key"), resultSet.getInt("scale_version"),
                resultSet.getString("value"), resultSet.getString("unit"), resultSet.getString("observed_at"));
    }

    private static ClinicalNote mapNote(ResultSet resultSet, int row) throws SQLException {
        return new ClinicalNote(NoteKind.valueOf(resultSet.getString("kind").toUpperCase(Locale.ROOT)),
                resultSet.getInt("version"), resultSet.getString("patient_id"), resultSet.getString("content"),
                resultSet.getString("author"), resultSet.getString("effective_at"),
                resultSet.getObject("template_version", Integer.class), resultSet.getBoolean("signed"),
                resultSet.getString("signed_by"), instantNullable(resultSet, "signed_at"),
                resultSet.getString("attestation"), resultSet.getString("content_checksum"));
    }

    private static Summary mapSummary(ResultSet resultSet, int row) throws SQLException {
        return new Summary(resultSet.getInt("version"), resultSet.getString("content"),
                resultSet.getString("referrer_org"), resultSet.getString("referrer_subject"),
                resultSet.getBoolean("approved"), resultSet.getString("approved_by"),
                instant(resultSet, "sent_at"));
    }

    private static ReturnAcknowledgment mapAcknowledgment(ResultSet resultSet, int row) throws SQLException {
        return new ReturnAcknowledgment(resultSet.getInt("summary_version"), resultSet.getString("received_at"),
                resultSet.getString("received_by"), resultSet.getString("note"));
    }

    private static OutcomeScale mapScale(ResultSet resultSet, int row) throws SQLException {
        Array values = Objects.requireNonNull(resultSet.getArray("permitted_values"),
                "permitted_values must be present");
        return new OutcomeScale(resultSet.getString("organization_id"), resultSet.getString("key"),
                resultSet.getInt("version"), resultSet.getString("description"), resultSet.getString("unit"),
                List.of((String[]) values.getArray()), resultSet.getBoolean("approved"),
                resultSet.getString("approved_by"));
    }

    private static TherapyAuditEvent mapAudit(ResultSet resultSet, int row) throws SQLException {
        return new TherapyAuditEvent(resultSet.getString("organization_id"),
                resultSet.getString("actor_subject"), resultSet.getString("action"),
                resultSet.getString("resource_id"), resultSet.getString("outcome"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Objects.requireNonNull(resultSet.getObject(column, OffsetDateTime.class),
                column + " must be present").toInstant();
    }

    private static Instant instantNullable(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
