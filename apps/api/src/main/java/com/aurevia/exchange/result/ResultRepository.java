package com.aurevia.exchange.result;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.RecipientNotification;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.ResultReport;
import com.aurevia.exchange.result.ResultModel.Review;
import com.aurevia.exchange.result.ResultModel.ReviewState;

/**
 * Thin SQL boundary for result routing.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced RLS
 * confines it to the active organization and a foreign tenant's identifiers
 * cannot resolve. All result ledger tables are append-only; only the review
 * task advances a version. No domain decision is made here.</p>
 */
@Repository
public class ResultRepository {

    private static final String REPORT_COLUMNS = """
            id::text AS id, version, order_ref, patient_id::text AS patient_id,
            source_code, accession, destination_code, kind, supersedes_version,
            previous_id::text AS previous_id, owner_subject, coverage_queue,
            effective_at, received_at, checksum
            """;
    private static final String RECONCILIATION_COLUMNS = """
            id::text AS id, order_ref, patient_id::text AS patient_id, source_code, accession,
            destination_code, reason, mismatches, detail, status, created_at
            """;
    // The ledger's status is immutable, so the open/resolved view is folded from the durable decision
    // fact. Every shared column is qualified: both tables carry id and created_at.
    private static final String RECONCILIATION_LIST_COLUMNS = """
            r.id::text AS id, r.order_ref, r.patient_id::text AS patient_id, r.source_code, r.accession,
            r.destination_code, r.reason, r.mismatches, r.detail,
            CASE WHEN resolution.id IS NOT NULL THEN 'resolved' ELSE r.status END AS status,
            r.created_at
            """;
    private static final RowMapper<ResultReport> REPORT_MAPPER = ResultRepository::mapReport;
    private static final RowMapper<ReconciliationEntry> RECONCILIATION_MAPPER =
            ResultRepository::mapReconciliation;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ResultRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return true when the latest effective membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List role keys currently in force for the subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read the local order expectation by tenant-local order reference.
     *
     * @param orderRef tenant-local order identifier
     * @return the expectation when visible in the active tenant
     */
    public Optional<ExpectedOrder> findOrder(String orderRef) {
        return jdbc.query("""
                SELECT order_ref, patient_id::text AS patient_id, source_code, accession,
                       destination_code, owner_subject, coverage_queue
                  FROM public.xch04_expected_order WHERE order_ref = :orderRef
                """, Map.of("orderRef", orderRef), (resultSet, rowNumber) -> new ExpectedOrder(
                resultSet.getString("order_ref"),
                new ResultIdentifiers(resultSet.getString("patient_id"),
                        resultSet.getString("source_code"), resultSet.getString("accession"),
                        resultSet.getString("destination_code"), resultSet.getString("order_ref")),
                resultSet.getString("owner_subject"), resultSet.getString("coverage_queue")))
                .stream().findFirst();
    }

    /**
     * Insert the local order expectation.
     *
     * <p>The unique tenant-scoped order reference makes registration idempotent
     * under concurrency: a racing insert returns no row and the caller maps that
     * to a documented conflict instead of an unmapped constraint violation.</p>
     *
     * @param order expectation with a caller-generated opaque identifier
     * @return the same expectation, or {@code null} when a concurrent
     *         registration already owns the order reference
     */
    public ExpectedOrder insertOrder(ExpectedOrder order) {
        List<String> inserted = jdbc.query("""
                INSERT INTO public.xch04_expected_order
                    (organization_id, id, order_ref, patient_id, source_code, accession,
                     destination_code, owner_subject, coverage_queue)
                VALUES (public.app_org(), CAST(:id AS uuid), :orderRef, CAST(:patientId AS uuid),
                        :sourceCode, :accession, :destinationCode, :owner, :coverageQueue)
                ON CONFLICT (organization_id, order_ref) DO NOTHING
                RETURNING id::text
                """, new MapSqlParameterSource()
                .addValue("id", newId())
                .addValue("orderRef", order.orderId())
                .addValue("patientId", order.identifiers().patientId())
                .addValue("sourceCode", order.identifiers().sourceId())
                .addValue("accession", order.identifiers().accessionId())
                .addValue("destinationCode", order.identifiers().destinationId())
                .addValue("owner", order.owner())
                .addValue("coverageQueue", order.coverageQueue()),
                (resultSet, rowNumber) -> resultSet.getString("id"));
        return inserted.isEmpty() ? null : order;
    }

    /**
     * Find one revision by its order lineage, source, accession and version.
     *
     * <p>The order reference is part of the lookup key on purpose: two orders in
     * the same tenant may carry an identical issuer accession, so matching only
     * on source and accession could replay another patient's revision.</p>
     *
     * @param orderRef   tenant-local order identifier owning the lineage
     * @param sourceCode issuer system code
     * @param accession  issuer-scoped accession
     * @param version    lineage version
     * @return the revision when visible
     */
    public Optional<ResultReport> findReport(String orderRef, String sourceCode, String accession, int version) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM public.xch04_report_revision "
                        + "WHERE order_ref = :orderRef AND source_code = :sourceCode "
                        + "AND accession = :accession AND version = :version",
                new MapSqlParameterSource().addValue("orderRef", orderRef)
                        .addValue("sourceCode", sourceCode)
                        .addValue("accession", accession).addValue("version", version),
                REPORT_MAPPER).stream().findFirst();
    }

    /**
     * Find one revision by its opaque identifier.
     *
     * @param id revision identifier
     * @return the revision when visible
     */
    public Optional<ResultReport> findReportById(String id) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM public.xch04_report_revision "
                        + "WHERE id = CAST(:id AS uuid)", Map.of("id", id), REPORT_MAPPER)
                .stream().findFirst();
    }

    /**
     * List every revision of one order lineage, oldest first.
     *
     * @param orderRef   tenant-local order identifier
     * @param sourceCode issuer system code
     * @param accession  issuer-scoped accession
     * @return the immutable revision chain
     */
    public List<ResultReport> lineage(String orderRef, String sourceCode, String accession) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM public.xch04_report_revision "
                        + "WHERE order_ref = :orderRef AND source_code = :sourceCode "
                        + "AND accession = :accession ORDER BY version",
                new MapSqlParameterSource().addValue("orderRef", orderRef)
                        .addValue("sourceCode", sourceCode).addValue("accession", accession),
                REPORT_MAPPER);
    }

    /**
     * Append one immutable result revision, tolerating a concurrent replay.
     *
     * <p>The unique key makes the insert idempotent under concurrency: a second
     * transaction racing the same (order, source, accession, version) inserts no
     * row and returns {@code false}, so the caller can return the winning
     * revision instead of surfacing a constraint violation.</p>
     *
     * @param report revision with server-derived times and caller-generated id
     * @return {@code true} when this call inserted the revision, {@code false}
     *         when a concurrent import already inserted it
     */
    public boolean insertReport(ResultReport report) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", report.id())
                .addValue("version", report.version())
                .addValue("orderRef", report.orderId())
                .addValue("patientId", report.identifiers().patientId())
                .addValue("sourceCode", report.identifiers().sourceId())
                .addValue("accession", report.identifiers().accessionId())
                .addValue("destinationCode", report.identifiers().destinationId())
                .addValue("kind", report.kind().name().toLowerCase(java.util.Locale.ROOT))
                .addValue("owner", report.owner())
                .addValue("coverageQueue", report.coverageQueue())
                .addValue("effectiveAt", report.effectiveAt().toString())
                .addValue("receivedAt", report.receivedAt().toString())
                .addValue("checksum", report.checksum());
        if (report.supersedesVersion() == null) {
            parameters.addValue("supersedesVersion", null, Types.INTEGER);
            parameters.addValue("previousId", null, Types.VARCHAR);
        } else {
            parameters.addValue("supersedesVersion", report.supersedesVersion());
            parameters.addValue("previousId", report.previousId());
        }
        List<String> inserted = jdbc.query("""
                INSERT INTO public.xch04_report_revision
                    (organization_id, id, version, order_ref, patient_id, source_code, accession,
                     destination_code, kind, supersedes_version, previous_id, owner_subject,
                     coverage_queue, effective_at, received_at, checksum)
                VALUES (public.app_org(), CAST(:id AS uuid), :version, :orderRef,
                        CAST(:patientId AS uuid), :sourceCode, :accession, :destinationCode,
                        :kind, :supersedesVersion, CAST(:previousId AS uuid), :owner,
                        :coverageQueue, CAST(:effectiveAt AS timestamptz),
                        CAST(:receivedAt AS timestamptz), :checksum)
                ON CONFLICT (organization_id, order_ref, source_code, accession, version) DO NOTHING
                RETURNING id::text
                """, parameters, (resultSet, rowNumber) -> resultSet.getString("id"));
        return !inserted.isEmpty();
    }

    /**
     * Insert the review task bound to a new revision.
     *
     * @param reportId revision identifier
     */
    public void insertReview(String reportId) {
        jdbc.update("""
                INSERT INTO public.xch04_review (organization_id, id, report_id)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:reportId AS uuid))
                """, new MapSqlParameterSource().addValue("id", newId())
                .addValue("reportId", reportId));
    }

    /**
     * Read the review task bound to one revision.
     *
     * @param reportId revision identifier
     * @return the review task when visible
     */
    public Optional<Review> findReview(String reportId) {
        return jdbc.query("""
                SELECT report_id::text AS report_id, version, review_state, acknowledged_at
                  FROM public.xch04_review WHERE report_id = CAST(:reportId AS uuid)
                """, Map.of("reportId", reportId), (resultSet, rowNumber) -> new Review(
                resultSet.getString("report_id"), resultSet.getInt("version"),
                ReviewState.valueOf(resultSet.getString("review_state").toUpperCase(java.util.Locale.ROOT)),
                instant(resultSet, "acknowledged_at"))).stream().findFirst();
    }

    /**
     * Acknowledge one review task with an optimistic version precondition.
     *
     * @param reportId       revision identifier
     * @param expectedVersion version read by the caller
     * @param acknowledgedAt  server timestamp of the acknowledgment
     * @return number of updated rows
     */
    public int updateReview(String reportId, int expectedVersion, Instant acknowledgedAt) {
        return jdbc.update("""
                UPDATE public.xch04_review
                   SET version = version + 1, review_state = 'acknowledged',
                       acknowledged_at = CAST(:acknowledgedAt AS timestamptz)
                 WHERE report_id = CAST(:reportId AS uuid) AND version = :expectedVersion
                   AND review_state = 'unreviewed'
                """, new MapSqlParameterSource().addValue("reportId", reportId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("acknowledgedAt", acknowledgedAt.toString()));
    }

    /**
     * Append one entry to the reconciliation queue.
     *
     * @param entry queue entry with a caller-generated identifier
     */
    public void insertReconciliation(ReconciliationEntry entry) {
        jdbc.update("""
                INSERT INTO public.xch04_reconciliation
                    (organization_id, id, order_ref, patient_id, source_code, accession,
                     destination_code, reason, mismatches, detail, status)
                VALUES (public.app_org(), CAST(:id AS uuid), :orderRef, CAST(:patientId AS uuid),
                        :sourceCode, :accession, :destinationCode, :reason, :mismatches,
                        :detail, :status)
                """, new MapSqlParameterSource()
                .addValue("id", entry.id())
                .addValue("orderRef", entry.identifiers().orderId())
                .addValue("patientId", entry.identifiers().patientId())
                .addValue("sourceCode", entry.identifiers().sourceId())
                .addValue("accession", entry.identifiers().accessionId())
                .addValue("destinationCode", entry.identifiers().destinationId())
                .addValue("reason", entry.reason().name().toLowerCase(java.util.Locale.ROOT))
                .addValue("mismatches", String.join(",", entry.mismatches()))
                .addValue("detail", entry.detail())
                .addValue("status", entry.status().name().toLowerCase(java.util.Locale.ROOT)));
    }

    /**
     * List a bounded page of reconciliation entries in the requested folded status.
     *
     * <p>The immutable ledger never advances its status column. Presence of a durable decision fact is
     * the authority: an entry without one is open, and an entry with one is resolved. The correlated
     * join carries the tenant key so a fact from another organization cannot change this tenant's view.</p>
     *
     * @param status {@code open} or {@code resolved}
     * @return entries newest first, at most 200
     */
    public List<ReconciliationEntry> listReconciliation(String status) {
        return jdbc.query("SELECT " + RECONCILIATION_LIST_COLUMNS
                        + " FROM public.xch04_reconciliation r"
                        + " LEFT JOIN public.reconciliation_resolution resolution"
                        + "   ON resolution.organization_id = r.organization_id"
                        + "  AND resolution.entry_id = r.id"
                        + " WHERE (resolution.id IS NOT NULL) = CAST(:resolved AS boolean)"
                        + " ORDER BY r.created_at DESC, r.id LIMIT 200",
                Map.of("resolved", "resolved".equals(status)), RECONCILIATION_MAPPER);
    }

    /**
     * Read one reconciliation entry by its opaque identifier.
     *
     * @param id queue entry identifier
     * @return the entry when visible in the active tenant
     */
    public Optional<ReconciliationEntry> findReconciliation(String id) {
        return jdbc.query("SELECT " + RECONCILIATION_COLUMNS
                        + " FROM public.xch04_reconciliation WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), RECONCILIATION_MAPPER).stream().findFirst();
    }

    /**
     * List distinct owners of earlier revisions of one order.
     *
     * @param orderRef tenant-local order identifier
     * @return prior recipient subjects
     */
    public List<String> priorRecipients(String orderRef) {
        return jdbc.query("""
                SELECT DISTINCT owner_subject FROM public.xch04_report_revision
                 WHERE order_ref = :orderRef ORDER BY owner_subject
                """, Map.of("orderRef", orderRef), (resultSet, rowNumber) ->
                resultSet.getString("owner_subject"));
    }

    /**
     * Record that a correction reached a recipient of an earlier revision.
     *
     * @param notification notification with a caller-generated identifier
     */
    public void insertNotification(RecipientNotification notification) {
        jdbc.update("""
                INSERT INTO public.xch04_notification
                    (organization_id, id, report_id, recipient_subject, superseded_version)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:reportId AS uuid),
                        :recipient, :supersededVersion)
                """, new MapSqlParameterSource()
                .addValue("id", notification.id())
                .addValue("reportId", notification.reportId())
                .addValue("recipient", notification.recipient())
                .addValue("supersededVersion", notification.supersededVersion()));
    }

    /**
     * List correction notifications bound to one revision.
     *
     * @param reportId revision identifier
     * @return notifications oldest first
     */
    public List<RecipientNotification> notifications(String reportId) {
        return jdbc.query("""
                SELECT id::text AS id, report_id::text AS report_id, recipient_subject,
                       superseded_version, created_at
                  FROM public.xch04_notification WHERE report_id = CAST(:reportId AS uuid)
                 ORDER BY created_at, id
                """, Map.of("reportId", reportId), (resultSet, rowNumber) ->
                new RecipientNotification(resultSet.getString("id"), resultSet.getString("report_id"),
                        resultSet.getString("recipient_subject"), resultSet.getInt("superseded_version"),
                        instant(resultSet, "created_at")));
    }

    private static String newId() {
        return java.util.UUID.randomUUID().toString();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static ResultReport mapReport(ResultSet resultSet, int rowNumber) throws SQLException {
        ResultIdentifiers identifiers = new ResultIdentifiers(resultSet.getString("patient_id"),
                resultSet.getString("source_code"), resultSet.getString("accession"),
                resultSet.getString("destination_code"), resultSet.getString("order_ref"));
        Integer supersedes = resultSet.getObject("supersedes_version", Integer.class);
        return new ResultReport(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("order_ref"), identifiers,
                ResultKind.valueOf(resultSet.getString("kind").toUpperCase(java.util.Locale.ROOT)),
                supersedes, resultSet.getString("previous_id"), resultSet.getString("owner_subject"),
                resultSet.getString("coverage_queue"), instant(resultSet, "effective_at"),
                instant(resultSet, "received_at"), resultSet.getString("checksum"));
    }

    private static ReconciliationEntry mapReconciliation(ResultSet resultSet, int rowNumber)
            throws SQLException {
        String joined = resultSet.getString("mismatches");
        List<String> mismatches = joined.isEmpty() ? List.of() : List.of(joined.split(","));
        ResultIdentifiers identifiers = new ResultIdentifiers(resultSet.getString("patient_id"),
                resultSet.getString("source_code"), resultSet.getString("accession"),
                resultSet.getString("destination_code"), resultSet.getString("order_ref"));
        return new ReconciliationEntry(resultSet.getString("id"), identifiers,
                ReconciliationReason.valueOf(
                        resultSet.getString("reason").toUpperCase(java.util.Locale.ROOT)),
                mismatches, resultSet.getString("detail"),
                ReconciliationStatus.valueOf(
                        resultSet.getString("status").toUpperCase(java.util.Locale.ROOT)),
                instant(resultSet, "created_at"));
    }
}
