package com.aurevia.messaging.enrollment;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.enrollment.EnrollmentContract.AccessEvent;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.CaseState;
import com.aurevia.messaging.enrollment.EnrollmentContract.EventType;
import com.aurevia.messaging.enrollment.EnrollmentContract.GenericNotification;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportCase;
import com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind;

/**
 * PostgreSQL-backed persistence boundary for privacy-safe patient access
 * (PAT-03, IAM-05, ARC-02).
 *
 * <p>V83 owns the six tenant tables and their forced row-level security. Every
 * operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org()} and {@code app.actor} after the transaction opens;
 * forced RLS therefore confines each statement to the active organization and
 * no manual organization predicate is needed on reads. The {@code organizationId}
 * argument identifies that same active tenant and is written explicitly on
 * inserts so the policy's {@code WITH CHECK} rejects a mismatch.</p>
 *
 * <p>Only digests of opaque invitation codes and access tokens are persisted;
 * the raw values exist only in the caller's response. Single use is a
 * compare-and-set in the database, so two simultaneous redemptions cannot create
 * two accounts. Account state (including the lockout counter) and the two
 * append-only ledgers are rows, never process-local maps, so a restart cannot
 * lose them.</p>
 */
@Repository
public class JdbcPatientAccessStore implements PatientAccessStore {

    private static final String ACCOUNT_COLUMNS = """
            id::text, organization_id::text, subject, linked_chart_ids, fact_digest,
            state, failed_attempts, contact_digest, created_at
            """;
    private static final String INVITATION_COLUMNS = """
            id::text, organization_id::text, chart_id::text, fact_digest, state,
            failed_attempts, expires_at, issued_by, issued_at
            """;
    private static final String TOKEN_COLUMNS = """
            id::text, organization_id::text, subject, purpose, expires_at, used
            """;
    private static final String CASE_COLUMNS = """
            id::text, organization_id::text, patient_id, kind, summary, state, assignee, opened_at
            """;
    private static final String EVENT_COLUMNS = """
            id::text, organization_id::text, subject, event_type, resource_id, outcome, detail, occurred_at
            """;
    private static final String NOTIFICATION_COLUMNS = """
            organization_id::text, subject, event_type, occurred_at
            """;

    private static final RowMapper<PatientAccount> ACCOUNT_MAPPER = JdbcPatientAccessStore::mapAccount;
    private static final RowMapper<Invitation> INVITATION_MAPPER = JdbcPatientAccessStore::mapInvitation;
    private static final RowMapper<AccessToken> TOKEN_MAPPER = JdbcPatientAccessStore::mapToken;
    private static final RowMapper<SupportCase> CASE_MAPPER = JdbcPatientAccessStore::mapCase;
    private static final RowMapper<AccessEvent> EVENT_MAPPER = JdbcPatientAccessStore::mapEvent;
    private static final RowMapper<GenericNotification> NOTIFICATION_MAPPER =
            JdbcPatientAccessStore::mapNotification;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PatientAccessService.DigestFactory digester;

    /**
     * Create the PostgreSQL patient-access store.
     *
     * @param jdbc          shared named-parameter JDBC template
     * @param transactions  tenant-guarded transaction template
     * @param digester      digest factory for opaque credential hashes
     */
    public JdbcPatientAccessStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions,
                                  PatientAccessService.DigestFactory digester) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.digester = digester;
    }

    @Override
    public boolean chartExists(String organizationId, String chartId) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:chartId AS uuid))
                """, new MapSqlParameterSource().addValue("chartId", chartId), Boolean.class)));
    }

    @Override
    public Optional<Invitation> invitation(String organizationId, String code) {
        return transactions.execute(status -> jdbc.query("SELECT " + INVITATION_COLUMNS
                + "FROM public.patient_access_invitation WHERE code_hash = :codeHash",
                new MapSqlParameterSource().addValue("codeHash", hash(code)), INVITATION_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putInvitation(String organizationId, Invitation invitation) {
        String codeHash = hash(invitation.code());
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.patient_access_invitation
                    (organization_id, id, code_hash, chart_id, fact_digest, state,
                     failed_attempts, expires_at, issued_by, issued_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :codeHash,
                        CAST(:chartId AS uuid), :factDigest, :state, :failedAttempts,
                        CAST(:expiresAt AS timestamptz), :issuedBy, CAST(:issuedAt AS timestamptz))
                ON CONFLICT (organization_id, code_hash) DO UPDATE
                   SET state = EXCLUDED.state, failed_attempts = EXCLUDED.failed_attempts
                 WHERE public.patient_access_invitation.state = 'pending'
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", idFromHash(codeHash))
                        .addValue("codeHash", codeHash)
                        .addValue("chartId", invitation.chartId())
                        .addValue("factDigest", invitation.factDigest())
                        .addValue("state", invitation.state().wire())
                        .addValue("failedAttempts", invitation.failedAttempts())
                        .addValue("expiresAt", dateTime(invitation.expiresAt()))
                        .addValue("issuedBy", invitation.issuedBy())
                        .addValue("issuedAt", dateTime(invitation.issuedAt()))));
    }

    @Override
    public boolean consumeInvitation(String organizationId, String code) {
        // organizationId is intentionally unused: the guarded transaction binds
        // the active tenant and forced RLS confines the database function to it.
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT public.consume_patient_access_invitation(:codeHash)
                """, new MapSqlParameterSource().addValue("codeHash", hash(code)), Boolean.class)));
    }

    @Override
    public void recordInvitationFailure(String organizationId, String code, int failedAttempts) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE public.patient_access_invitation
                   SET failed_attempts = :failedAttempts
                 WHERE code_hash = :codeHash AND state = 'pending'
                """, new MapSqlParameterSource()
                        .addValue("failedAttempts", failedAttempts)
                        .addValue("codeHash", hash(code))));
    }

    @Override
    public Optional<PatientAccount> account(String organizationId, String accountId) {
        return transactions.execute(status -> jdbc.query("SELECT " + ACCOUNT_COLUMNS
                + "FROM public.patient_access_account WHERE id = CAST(:accountId AS uuid)",
                new MapSqlParameterSource().addValue("accountId", accountId), ACCOUNT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public Optional<PatientAccount> accountBySubject(String organizationId, String subject) {
        return transactions.execute(status -> jdbc.query("SELECT " + ACCOUNT_COLUMNS
                + "FROM public.patient_access_account WHERE subject = :subject",
                new MapSqlParameterSource().addValue("subject", subject), ACCOUNT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putAccount(String organizationId, PatientAccount account) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.patient_access_account
                    (organization_id, id, subject, linked_chart_ids, fact_digest, state,
                     failed_attempts, contact_digest, created_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subject,
                        CAST(:linkedChartIds AS text[]), :factDigest, :state, :failedAttempts,
                        :contactDigest, CAST(:createdAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO UPDATE
                   SET linked_chart_ids = EXCLUDED.linked_chart_ids,
                       fact_digest = EXCLUDED.fact_digest, state = EXCLUDED.state,
                       failed_attempts = EXCLUDED.failed_attempts,
                       contact_digest = EXCLUDED.contact_digest
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", account.id())
                        .addValue("subject", account.subject())
                        .addValue("linkedChartIds", account.linkedChartIds().toArray(String[]::new))
                        .addValue("factDigest", account.factDigest())
                        .addValue("state", account.state().wire())
                        .addValue("failedAttempts", account.failedAttempts())
                        .addValue("contactDigest", account.contactDigest())
                        .addValue("createdAt", dateTime(account.createdAt()))));
    }

    @Override
    public Optional<AccessToken> token(String organizationId, String token) {
        return transactions.execute(status -> jdbc.query("SELECT " + TOKEN_COLUMNS
                + "FROM public.patient_access_token WHERE token_hash = :tokenHash",
                new MapSqlParameterSource().addValue("tokenHash", hash(token)), TOKEN_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putToken(String organizationId, AccessToken accessToken) {
        String tokenHash = hash(accessToken.token());
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.patient_access_token
                    (organization_id, id, token_hash, subject, purpose, expires_at, used)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :tokenHash, :subject,
                        :purpose, CAST(:expiresAt AS timestamptz), :used)
                ON CONFLICT (organization_id, token_hash) DO UPDATE SET used = EXCLUDED.used
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", idFromHash(tokenHash))
                        .addValue("tokenHash", tokenHash)
                        .addValue("subject", accessToken.subject())
                        .addValue("purpose", accessToken.purpose())
                        .addValue("expiresAt", dateTime(accessToken.expiresAt()))
                        .addValue("used", accessToken.used())));
    }

    @Override
    public boolean consumeToken(String organizationId, String token) {
        return transactions.execute(status -> jdbc.update("""
                UPDATE public.patient_access_token SET used = true
                 WHERE token_hash = :tokenHash AND NOT used
                """, new MapSqlParameterSource().addValue("tokenHash", hash(token))) == 1);
    }

    @Override
    public Optional<SupportCase> supportCase(String organizationId, String caseId) {
        return transactions.execute(status -> jdbc.query("SELECT " + CASE_COLUMNS
                + "FROM public.patient_access_support_case WHERE id = CAST(:caseId AS uuid)",
                new MapSqlParameterSource().addValue("caseId", caseId), CASE_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putSupportCase(String organizationId, SupportCase supportCase) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.patient_access_support_case
                    (organization_id, id, patient_id, kind, summary, state, assignee, opened_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :patientId, :kind,
                        :summary, :state, :assignee, CAST(:openedAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO UPDATE
                   SET patient_id = EXCLUDED.patient_id, kind = EXCLUDED.kind,
                       summary = EXCLUDED.summary, state = EXCLUDED.state,
                       assignee = EXCLUDED.assignee
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", supportCase.id())
                        .addValue("patientId", supportCase.patientId())
                        .addValue("kind", supportCase.kind().wire())
                        .addValue("summary", supportCase.summary())
                        .addValue("state", supportCase.state().wire())
                        .addValue("assignee", supportCase.assignee())
                        .addValue("openedAt", dateTime(supportCase.openedAt()))));
    }

    @Override
    public List<SupportCase> supportCases(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + CASE_COLUMNS
                + "FROM public.patient_access_support_case ORDER BY opened_at, id", CASE_MAPPER));
    }

    @Override
    public List<AccessEvent> audit(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + EVENT_COLUMNS
                + "FROM public.patient_access_event ORDER BY seq", EVENT_MAPPER));
    }

    @Override
    public void appendAudit(AccessEvent event) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.patient_access_event
                    (organization_id, id, subject, event_type, resource_id, outcome, detail, occurred_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subject, :eventType,
                        :resourceId, :outcome, :detail, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", event.organizationId())
                        .addValue("id", event.id())
                        .addValue("subject", event.subject())
                        .addValue("eventType", event.eventType().wire())
                        .addValue("resourceId", event.resourceId())
                        .addValue("outcome", event.outcome())
                        .addValue("detail", event.detail())
                        .addValue("occurredAt", dateTime(event.occurredAt()))));
    }

    @Override
    public List<GenericNotification> notifications(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + NOTIFICATION_COLUMNS
                + "FROM public.patient_access_notification ORDER BY seq", NOTIFICATION_MAPPER));
    }

    @Override
    public void appendNotification(GenericNotification notification) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.patient_access_notification (organization_id, subject, event_type, occurred_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :eventType, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", notification.organizationId())
                        .addValue("subject", notification.subject())
                        .addValue("eventType", notification.eventType())
                        .addValue("occurredAt", dateTime(notification.occurredAt()))));
    }

    /**
     * Hash an opaque credential so no raw code or token reaches the database.
     *
     * @param value raw opaque credential
     * @return lowercase SHA-256 hex digest
     * @throws IllegalStateException when the platform digest is unavailable
     */
    private String hash(String value) {
        try {
            byte[] bytes = digester.create().digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(Character.forDigit((current >> 4) & 0xf, 16));
                result.append(Character.forDigit(current & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Derive a stable, non-reversible primary key from a credential digest.
     *
     * @param hash credential digest
     * @return deterministic UUID key
     */
    private static String idFromHash(String hash) {
        return UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
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

    private static PatientAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        Array links = resultSet.getArray("linked_chart_ids");
        return new PatientAccount(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("subject"), Set.of((String[]) links.getArray()),
                resultSet.getString("fact_digest"), AccountState.from(resultSet.getString("state")),
                resultSet.getInt("failed_attempts"), resultSet.getString("contact_digest"),
                instant(resultSet, "created_at"));
    }

    private static Invitation mapInvitation(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Invitation(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("chart_id"), resultSet.getString("fact_digest"),
                instant(resultSet, "expires_at"), InvitationState.from(resultSet.getString("state")),
                resultSet.getInt("failed_attempts"), resultSet.getString("issued_by"),
                instant(resultSet, "issued_at"));
    }

    private static AccessToken mapToken(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccessToken(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("subject"), resultSet.getString("purpose"),
                instant(resultSet, "expires_at"), resultSet.getBoolean("used"));
    }

    private static SupportCase mapCase(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SupportCase(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("patient_id"), SupportKind.from(resultSet.getString("kind")),
                resultSet.getString("summary"), CaseState.from(resultSet.getString("state")),
                resultSet.getString("assignee"), instant(resultSet, "opened_at"));
    }

    private static AccessEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccessEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("subject"), EventType.from(resultSet.getString("event_type")),
                resultSet.getString("resource_id"), resultSet.getString("outcome"),
                resultSet.getString("detail"), instant(resultSet, "occurred_at"));
    }

    private static GenericNotification mapNotification(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GenericNotification(resultSet.getString("subject"), resultSet.getString("organization_id"),
                resultSet.getString("event_type"), instant(resultSet, "occurred_at"));
    }
}
