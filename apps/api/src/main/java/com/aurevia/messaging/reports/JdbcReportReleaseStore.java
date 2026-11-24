package com.aurevia.messaging.reports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.reports.ReleasedReport.CorrectionContext;
import com.aurevia.messaging.reports.ReleasedReport.Status;

/**
 * PostgreSQL-backed persistence boundary for patient releases (XCH-06, PAT-02, ARC-02).
 *
 * <p>V84 owns the three tenant tables and their forced row-level security. Every
 * statement runs in the tenant-guarded transaction, which binds {@code app.current_org()}
 * after the transaction opens; forced RLS confines each statement to the active
 * organization. The organization argument is also written explicitly on inserts so the
 * policy's {@code WITH CHECK} rejects a mismatch. A release decision updates the current
 * access row and appends an immutable history row in the same transaction, so a
 * withdrawn or corrected release never rewrites prior decisions.</p>
 */
@Repository
public class JdbcReportReleaseStore implements ReportReleaseStore {

    private static final RowMapper<PatientChartLink> LINK_MAPPER = JdbcReportReleaseStore::mapLink;
    private static final RowMapper<ReleasedReport> REPORT_MAPPER = JdbcReportReleaseStore::mapReport;
    private static final RowMapper<ReportRelease> HISTORY_MAPPER = JdbcReportReleaseStore::mapHistory;

    private static final String LINK_COLUMNS = """
            SELECT organization_id::text, patient_id, verified, active
              FROM public.report_chart_link
             WHERE organization_id = CAST(:organizationId AS uuid) AND subject = :subject
            """;
    private static final String REPORT_COLUMNS = """
            SELECT organization_id::text, id, revision, patient_id, issuing_organization,
                   effective_date::text, language, document_id, checksum, status,
                   correction_revision, correction_reason
              FROM public.report_release_revision
             WHERE organization_id = CAST(:organizationId AS uuid)
            """;
    private static final String HISTORY_COLUMNS = """
            SELECT h.organization_id::text, h.report_id, h.revision, r.patient_id,
                   r.issuing_organization, r.effective_date::text, r.language,
                   r.document_id, r.checksum, h.status, r.correction_revision,
                   r.correction_reason, h.policy_version, h.effective_time,
                   h.hold_reason, h.hold_expires_at, h.recorded_at
              FROM public.report_release_history h
              JOIN public.report_release_revision r
                ON r.organization_id = h.organization_id
               AND r.id = h.report_id AND r.revision = h.revision
             WHERE h.organization_id = CAST(:organizationId AS uuid)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL report-release store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcReportReleaseStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void putLink(String organizationId, String subject, PatientChartLink link) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.report_chart_link
                    (organization_id, subject, patient_id, verified, active)
                VALUES (CAST(:organizationId AS uuid), :subject, :patientId, :verified, :active)
                ON CONFLICT (organization_id, subject) DO UPDATE SET
                    patient_id = EXCLUDED.patient_id, verified = EXCLUDED.verified,
                    active = EXCLUDED.active, updated_at = now()
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("subject", subject)
                        .addValue("patientId", link.patientId())
                        .addValue("verified", link.verified())
                        .addValue("active", link.active())));
    }

    @Override
    public Optional<PatientChartLink> link(String organizationId, String subject) {
        return transactions.execute(status -> jdbc.query(LINK_COLUMNS + " ORDER BY subject",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject), LINK_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putReport(ReleasedReport report) {
        CorrectionContext correction = report.correction();
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.report_release_revision
                    (organization_id, id, revision, patient_id, issuing_organization,
                     effective_date, language, document_id, checksum, status,
                     correction_revision, correction_reason)
                VALUES (CAST(:organizationId AS uuid), :id, :revision, :patientId,
                        :issuingOrganization, CAST(:effectiveDate AS date), :language,
                        :documentId, :checksum, :status, :correctionRevision, :correctionReason)
                ON CONFLICT (organization_id, id, revision) DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("organizationId", report.organizationId())
                        .addValue("id", report.id())
                        .addValue("revision", report.revision())
                        .addValue("patientId", report.patientId())
                        .addValue("issuingOrganization", report.issuingOrganization())
                        .addValue("effectiveDate", report.effectiveDate())
                        .addValue("language", report.language())
                        .addValue("documentId", report.documentId())
                        .addValue("checksum", report.checksum())
                        .addValue("status", report.status().name())
                        .addValue("correctionRevision", correction == null ? null : correction.supersedesRevision())
                        .addValue("correctionReason", correction == null ? null : correction.reason())));
    }

    @Override
    public Optional<ReleasedReport> find(String organizationId, String id, int revision) {
        return transactions.execute(status -> jdbc.query(REPORT_COLUMNS + " AND id = :id AND revision = :revision",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", id).addValue("revision", revision), REPORT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<ReleasedReport> reportsFor(String organizationId, String patientId) {
        return transactions.execute(status -> jdbc.query(REPORT_COLUMNS + " AND patient_id = :patientId"
                        + " ORDER BY effective_date DESC, id, revision",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("patientId", patientId), REPORT_MAPPER));
    }

    @Override
    public OptionalInt latestReleasedRevision(String organizationId, String id, LocalDate effectiveOn) {
        Integer latest = transactions.execute(status -> jdbc.queryForObject("""
                SELECT MAX(revision) FROM public.report_release_revision
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND id = :id AND status = 'RELEASED'
                   AND effective_date <= CAST(:effectiveOn AS date)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", id).addValue("effectiveOn", effectiveOn.toString()), Integer.class));
        return latest == null ? OptionalInt.empty() : OptionalInt.of(latest);
    }

    @Override
    public boolean isSuperseded(String organizationId, ReleasedReport report) {
        Boolean superseded = transactions.execute(status -> jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.report_release_revision
                                WHERE organization_id = CAST(:organizationId AS uuid)
                                  AND id = :id AND revision > :revision
                                  AND status <> 'HELD' AND correction_revision = :revision)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", report.id()).addValue("revision", report.revision()), Boolean.class));
        return Boolean.TRUE.equals(superseded);
    }

    @Override
    public boolean withdraw(String organizationId, String id, int revision) {
        return transactions.execute(status -> {
            if (find(organizationId, id, revision).isEmpty()) {
                return false;
            }
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("organizationId", organizationId)
                    .addValue("id", id)
                    .addValue("revision", revision);
            int updated = jdbc.update("""
                    UPDATE public.report_release_revision SET status = 'WITHDRAWN'
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND id = :id AND revision = :revision AND status <> 'WITHDRAWN'
                    """, params);
            if (updated != 1) {
                return false;
            }
            // The decision is appended, never written over an earlier row. The current
            // access row and this history row commit in the same guarded transaction.
            jdbc.update("""
                    INSERT INTO public.report_release_history
                        (organization_id, report_id, revision, policy_version, effective_time, status)
                    SELECT organization_id, id, revision, policy_version, clock_timestamp(), 'WITHDRAWN'
                      FROM public.report_release_revision
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND id = :id AND revision = :revision
                    """, params);
            return true;
        });
    }

    @Override
    public void recordRelease(ReportRelease release) {
        ReleasedReport revision = release.revision();
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    INSERT INTO public.report_release_revision
                        (organization_id, id, revision, patient_id, issuing_organization,
                         effective_date, language, document_id, checksum, status,
                         correction_revision, correction_reason, policy_version)
                    VALUES (CAST(:organizationId AS uuid), :id, :revision, :patientId,
                            :issuingOrganization, CAST(:effectiveDate AS date), :language,
                            :documentId, :checksum, :status, :correctionRevision,
                            :correctionReason, :policyVersion)
                    ON CONFLICT (organization_id, id, revision) DO UPDATE SET
                        status = EXCLUDED.status, policy_version = EXCLUDED.policy_version
                    WHERE public.report_release_revision.status <> 'WITHDRAWN'
                    """, new MapSqlParameterSource()
                            .addValue("organizationId", revision.organizationId())
                            .addValue("id", revision.id())
                            .addValue("revision", revision.revision())
                            .addValue("patientId", revision.patientId())
                            .addValue("issuingOrganization", revision.issuingOrganization())
                            .addValue("effectiveDate", revision.effectiveDate())
                            .addValue("language", revision.language())
                            .addValue("documentId", revision.documentId())
                            .addValue("checksum", revision.checksum())
                            .addValue("status", revision.status().name())
                            .addValue("correctionRevision", correctionRevision(revision))
                            .addValue("correctionReason", correctionReason(revision))
                            .addValue("policyVersion", release.policyVersion()));
            // A withdrawn revision is terminal. The guarded upsert reports zero
            // rows when it would resurrect one; abort before appending a decision
            // that would contradict the current access state.
            if (updated != 1) {
                throw new IllegalStateException("a withdrawn revision cannot be released again");
            }

            MapSqlParameterSource history = new MapSqlParameterSource()
                    .addValue("organizationId", revision.organizationId())
                    .addValue("reportId", revision.id())
                    .addValue("revision", revision.revision())
                    .addValue("policyVersion", release.policyVersion())
                    .addValue("effectiveTime", dateTime(release.effectiveTime()))
                    .addValue("status", revision.status().name())
                    .addValue("recordedAt", dateTime(release.recordedAt()));
            if (release.holdReason() == null) {
                history.addValue("holdReason", null, Types.VARCHAR);
                history.addValue("holdExpiresAt", null, Types.TIMESTAMP);
            } else {
                history.addValue("holdReason", release.holdReason());
                history.addValue("holdExpiresAt", dateTime(release.holdExpiresAt()));
            }
            jdbc.update("""
                    INSERT INTO public.report_release_history
                        (organization_id, report_id, revision, policy_version, effective_time,
                         hold_reason, hold_expires_at, status, recorded_at)
                    VALUES (CAST(:organizationId AS uuid), :reportId, :revision, :policyVersion,
                            CAST(:effectiveTime AS timestamptz), :holdReason,
                            CAST(:holdExpiresAt AS timestamptz), :status,
                            CAST(:recordedAt AS timestamptz))
                    """, history);
        });
    }

    @Override
    public List<ReportRelease> releaseHistory(String organizationId, String reportId, int revision) {
        return transactions.execute(status -> jdbc.query(HISTORY_COLUMNS + " AND h.report_id = :reportId"
                        + " AND h.revision = :revision ORDER BY h.recorded_at, h.id",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("reportId", reportId).addValue("revision", revision), HISTORY_MAPPER));
    }

    private static Integer correctionRevision(ReleasedReport report) {
        CorrectionContext correction = report.correction();
        return correction == null ? null : correction.supersedesRevision();
    }

    private static String correctionReason(ReleasedReport report) {
        CorrectionContext correction = report.correction();
        return correction == null ? null : correction.reason();
    }

    private static PatientChartLink mapLink(ResultSet resultSet, int rowNumber) throws SQLException {
        return new PatientChartLink(resultSet.getString("patient_id"),
                resultSet.getBoolean("verified"), resultSet.getBoolean("active"));
    }

    private static ReleasedReport mapReport(ResultSet resultSet, int rowNumber) throws SQLException {
        String reason = resultSet.getString("correction_reason");
        CorrectionContext correction = reason == null ? null
                : new CorrectionContext(resultSet.getInt("correction_revision"), reason);
        return new ReleasedReport(resultSet.getString("id"), resultSet.getInt("revision"),
                resultSet.getString("patient_id"), resultSet.getString("organization_id"),
                resultSet.getString("issuing_organization"), resultSet.getString("effective_date"),
                resultSet.getString("language"), resultSet.getString("document_id"),
                resultSet.getString("checksum"), Status.valueOf(resultSet.getString("status")), correction);
    }

    private static ReportRelease mapHistory(ResultSet resultSet, int rowNumber) throws SQLException {
        ReleasedReport revision = new ReleasedReport(resultSet.getString("report_id"),
                resultSet.getInt("revision"), resultSet.getString("patient_id"),
                resultSet.getString("organization_id"), resultSet.getString("issuing_organization"),
                resultSet.getString("effective_date"), resultSet.getString("language"),
                resultSet.getString("document_id"), resultSet.getString("checksum"),
                Status.valueOf(resultSet.getString("status")), correction(resultSet));
        String holdReason = resultSet.getString("hold_reason");
        OffsetDateTime expiry = holdReason == null ? null
                : resultSet.getObject("hold_expires_at", OffsetDateTime.class);
        return new ReportRelease(revision, resultSet.getInt("policy_version"),
                instant(resultSet, "effective_time"), holdReason,
                expiry == null ? null : expiry.toInstant(), instant(resultSet, "recorded_at"));
    }

    private static CorrectionContext correction(ResultSet resultSet) throws SQLException {
        String reason = resultSet.getString("correction_reason");
        return reason == null ? null : new CorrectionContext(resultSet.getInt("correction_revision"), reason);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * <p>pgjdbc cannot infer a SQL type for {@link java.time.Instant}; the UTC
     * offset keeps the wire value unambiguous.</p>
     *
     * @param instant server instant, or {@code null} for a nullable hold column
     * @return an offset date time, or {@code null} for a nullable hold column
     */
    private static OffsetDateTime dateTime(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
