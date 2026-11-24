package com.aurevia.exchange.order;

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
 * SQL boundary for the XCH-01 order exchange.
 *
 * <p>Every statement runs inside the tenant-guarded transaction. The sender owns
 * the order, its snapshot and its disclosure ledger; the work item is a bounded
 * projection readable by both the sender and the verified destination under an
 * explicit two-party row policy, so no cross-tenant context switch is needed.</p>
 */
@Repository
public class OrderRepository {

    private static final String SELECT_ORDER = """
            SELECT o.id::text AS id, o.version, o.patient_id::text AS patient_id,
                   o.encounter_ref, o.order_code, o.priority, o.clinical_question,
                   o.destination_org::text AS destination_org, o.source_actor,
                   o.responsible_clinician, o.work_queue, o.authorization_id::text AS authorization_id,
                   o.snapshot_id::text AS snapshot_id, s.fingerprint::text AS snapshot_fingerprint,
                   o.disclosure_id::text AS disclosure_id, o.work_item_id::text AS work_item_id,
                   o.created_at
              FROM public.exchange_order o
              JOIN public.exchange_order_snapshot s
                ON s.organization_id = o.organization_id AND s.id = o.snapshot_id
             """;

    private static final String SELECT_WORK_ITEM = """
            SELECT w.id::text AS id, w.organization_id::text AS sender_org,
                   w.destination_org::text AS destination_org, w.order_ref::text AS order_ref,
                   w.patient_ref::text AS patient_ref, w.patient_mrn, w.mrn_issuer,
                   w.local_patient_id::text AS local_patient_id,
                   w.patient_verified, w.priority, w.clinical_question, w.work_queue,
                   w.fingerprint::text AS fingerprint, w.state, w.created_at
              FROM public.exchange_work_item w
             """;

    private static final RowMapper<OrderView> ORDER_MAPPER = OrderRepository::mapOrder;
    private static final RowMapper<WorkItemView> WORK_ITEM_MAPPER = OrderRepository::mapWorkItem;
    private static final RowMapper<AuthorizationView> AUTHORIZATION_MAPPER =
            OrderRepository::mapAuthorization;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public OrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest membership event is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List the role keys currently in force for a subject.
     *
     * @param subject authenticated subject
     * @return active role keys of the subject
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Check that a patient is visible in the active tenant.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient record exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Read the issuer-scoped identity of a patient in the active tenant.
     *
     * @param patientId patient identifier
     * @return the MRN and issuer when the patient is visible
     */
    public Optional<PatientIdentity> patientIdentity(String patientId) {
        return jdbc.query("""
                SELECT mrn, mrn_issuer FROM public.patient_record WHERE id = CAST(:patientId AS uuid)
                """, Map.of("patientId", patientId), (resultSet, rowNumber) ->
                new PatientIdentity(resultSet.getString("mrn"), resultSet.getString("mrn_issuer")))
                .stream().findFirst();
    }

    /**
     * Check that a receiver patient carries the MRN and issuer projected by the work item.
     *
     * @param localPatientId receiver patient identifier
     * @param mrn            issuer-scoped MRN projected with the referral
     * @param issuer         issuer of that MRN
     * @return {@code true} only for an exact identifier match in the active tenant
     */
    public boolean localPatientMatches(String localPatientId, String mrn, String issuer) {
        Boolean matched = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.patient_record
                     WHERE id = CAST(:localPatientId AS uuid) AND mrn = :mrn AND mrn_issuer = :issuer
                )
                """, new MapSqlParameterSource().addValue("localPatientId", localPatientId)
                .addValue("mrn", mrn).addValue("issuer", issuer), Boolean.class);
        return Boolean.TRUE.equals(matched);
    }

    /**
     * Read one approved exchange authorization in the active tenant.
     *
     * @param id authorization identifier
     * @return the authorization when visible
     */
    public Optional<AuthorizationView> authorization(String id) {
        return jdbc.query("""
                SELECT id::text AS id, organization_id::text AS organization_id, order_code,
                       destination_org::text AS destination_org, purpose, max_duration_minutes,
                       effective_from, expires_at, active
                  FROM public.exchange_authorization WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), AUTHORIZATION_MAPPER).stream().findFirst();
    }

    /**
     * Append an approved authorization; the author is derived from the transaction actor.
     *
     * @param authorization authorization to append
     * @return number of inserted rows
     */
    public int insertAuthorization(AuthorizationView authorization) {
        return jdbc.update("""
                INSERT INTO public.exchange_authorization
                    (organization_id, id, order_code, destination_org, purpose,
                     max_duration_minutes, effective_from, expires_at, active)
                VALUES (public.app_org(), CAST(:id AS uuid), :orderCode, CAST(:destinationOrg AS uuid),
                        :purpose, :maxDurationMinutes, CAST(:effectiveFrom AS timestamptz),
                        CAST(:expiresAt AS timestamptz), :active)
                """, new MapSqlParameterSource()
                .addValue("id", authorization.id())
                .addValue("orderCode", authorization.orderCode())
                .addValue("destinationOrg", authorization.destinationOrg())
                .addValue("purpose", authorization.purpose())
                .addValue("maxDurationMinutes", authorization.maxDurationMinutes())
                .addValue("effectiveFrom", authorization.effectiveFrom().toString())
                .addValue("expiresAt", authorization.expiresAt().toString())
                .addValue("active", authorization.active()));
    }

    /**
     * Insert the released order; its source actor is derived from the transaction actor.
     *
     * @param order released order
     * @return number of inserted rows
     */
    public int insertOrder(OrderView order) {
        return jdbc.update("""
                INSERT INTO public.exchange_order
                    (organization_id, id, version, patient_id, encounter_ref, order_code, priority,
                     clinical_question, destination_org, responsible_clinician, work_queue,
                     authorization_id, snapshot_id, disclosure_id, work_item_id)
                VALUES (public.app_org(), CAST(:id AS uuid), :version, CAST(:patientId AS uuid),
                        :encounterRef, :orderCode, :priority, :clinicalQuestion,
                        CAST(:destinationOrg AS uuid), :responsibleClinician, :workQueue,
                        CAST(:authorizationId AS uuid), CAST(:snapshotId AS uuid),
                        CAST(:disclosureId AS uuid), CAST(:workItemId AS uuid))
                """, new MapSqlParameterSource()
                .addValue("id", order.id()).addValue("version", order.version())
                .addValue("patientId", order.patientId()).addValue("encounterRef", order.encounterRef())
                .addValue("orderCode", order.orderCode()).addValue("priority", order.priority())
                .addValue("clinicalQuestion", order.clinicalQuestion())
                .addValue("destinationOrg", order.destinationOrg())
                .addValue("responsibleClinician", order.responsibleClinician())
                .addValue("workQueue", order.workQueue())
                .addValue("authorizationId", order.authorizationId())
                .addValue("snapshotId", order.snapshotId())
                .addValue("disclosureId", order.disclosureId())
                .addValue("workItemId", order.workItemId()));
    }

    /**
     * Append the immutable released-content snapshot.
     *
     * @param id           snapshot identifier
     * @param orderId      released order identifier
     * @param version      released revision
     * @param content      canonical released content
     * @param fingerprint  deterministic content fingerprint
     * @return number of inserted rows
     */
    public int appendSnapshot(String id, String orderId, int version, String content,
                              String fingerprint) {
        return jdbc.update("""
                INSERT INTO public.exchange_order_snapshot
                    (organization_id, id, order_id, version, content, fingerprint)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:orderId AS uuid), :version,
                        :content, CAST(:fingerprint AS uuid))
                """, new MapSqlParameterSource().addValue("id", id).addValue("orderId", orderId)
                .addValue("version", version).addValue("content", content)
                .addValue("fingerprint", fingerprint));
    }

    /**
     * Append the disclosure ledger entry for one released revision.
     *
     * @param id              disclosure identifier
     * @param orderId         released order identifier
     * @param destinationOrg  receiving organization
     * @param purpose         approved purpose
     * @param durationMinutes authorized validity
     * @param expiresAt       instant at which the release stops being valid
     * @param fingerprint     fingerprint of the released snapshot
     * @return number of inserted rows
     */
    public int appendDisclosure(String id, String orderId, String destinationOrg, String purpose,
                                int durationMinutes, Instant expiresAt, String fingerprint) {
        return jdbc.update("""
                INSERT INTO public.exchange_disclosure
                    (organization_id, id, order_id, recipient_org, purpose, duration_minutes,
                     expires_at, fingerprint)
                VALUES (public.app_org(), CAST(:id AS uuid), CAST(:orderId AS uuid),
                        CAST(:destinationOrg AS uuid), :purpose, :durationMinutes,
                        CAST(:expiresAt AS timestamptz), CAST(:fingerprint AS uuid))
                """, new MapSqlParameterSource().addValue("id", id).addValue("orderId", orderId)
                .addValue("destinationOrg", destinationOrg).addValue("purpose", purpose)
                .addValue("durationMinutes", durationMinutes).addValue("expiresAt", expiresAt.toString())
                .addValue("fingerprint", fingerprint));
    }

    /**
     * Insert the bounded projection visible to the approved destination.
     *
     * @param id             work item identifier
     * @param destinationOrg receiving organization
     * @param authorizationId approved authorization that binds the destination
     * @param orderRef       sender order identifier
     * @param patientRef     opaque sender patient reference
     * @param patientMrn     issuer-scoped MRN of the referred patient
     * @param mrnIssuer      issuer of that MRN
     * @param priority       released priority
     * @param question       released clinical question
     * @param workQueue      destination work queue
     * @param fingerprint    released snapshot fingerprint
     * @return number of inserted rows
     */
    public int insertWorkItem(String id, String destinationOrg, String authorizationId,
                              String orderRef, String patientRef, String patientMrn,
                              String mrnIssuer, String priority, String question,
                              String workQueue, String fingerprint) {
        return jdbc.update("""
                INSERT INTO public.exchange_work_item
                    (organization_id, destination_org, authorization_id, id, order_ref, patient_ref,
                     patient_mrn, mrn_issuer, priority, clinical_question, work_queue, fingerprint)
                VALUES (public.app_org(), CAST(:destinationOrg AS uuid),
                        CAST(:authorizationId AS uuid), CAST(:id AS uuid), CAST(:orderRef AS uuid),
                        CAST(:patientRef AS uuid), :patientMrn, :mrnIssuer, :priority, :question,
                        :workQueue, CAST(:fingerprint AS uuid))
                """, new MapSqlParameterSource()
                .addValue("destinationOrg", destinationOrg)
                .addValue("authorizationId", authorizationId).addValue("id", id)
                .addValue("orderRef", orderRef).addValue("patientRef", patientRef)
                .addValue("patientMrn", patientMrn).addValue("mrnIssuer", mrnIssuer)
                .addValue("priority", priority).addValue("question", question)
                .addValue("workQueue", workQueue).addValue("fingerprint", fingerprint));
    }

    /**
     * Read one released order of the active tenant.
     *
     * @param id order identifier
     * @return the order when visible
     */
    public Optional<OrderView> find(String id) {
        return jdbc.query(SELECT_ORDER + " WHERE o.id = CAST(:id AS uuid)", Map.of("id", id),
                ORDER_MAPPER).stream().findFirst();
    }

    /**
     * Read one cursor-paged slice of the active tenant's released orders.
     *
     * <p>Forced RLS confines both the order and the projected work item to the
     * active organization, so no manual organization predicate is needed. The
     * immutable id breaks a creation-time tie, which makes descending paging
     * stable even when two orders share a release instant. The caller fetches one
     * extra row to decide whether another page exists.</p>
     *
     * @param cursorCreatedAt exclusive upper creation-time bound, or {@code null}
     * @param cursorId        immutable id that breaks a tie at the cursor instant
     * @param status          optional exact work-item state filter
     * @param limit           maximum rows to fetch (one more than the page size)
     * @return released orders in creation-time descending order
     */
    public List<OrderView> query(String cursorCreatedAt, String cursorId, String status, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder(SELECT_ORDER);
        if (status != null) {
            sql.append("""
                     JOIN public.exchange_work_item w
                       ON w.organization_id = o.organization_id AND w.id = o.work_item_id
                    """);
        }
        sql.append(" WHERE 1=1");
        if (cursorCreatedAt != null) {
            sql.append("""
                        AND (o.created_at < CAST(:cursorCreatedAt AS timestamptz)
                             OR (o.created_at = CAST(:cursorCreatedAt AS timestamptz)
                                 AND o.id < CAST(:cursorId AS uuid)))
                       """);
            params.addValue("cursorCreatedAt", cursorCreatedAt).addValue("cursorId", cursorId);
        }
        if (status != null) {
            sql.append(" AND w.state = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, ORDER_MAPPER);
    }

    /**
     * List bounded work items projected to the active receiving organization.
     *
     * @return the destination's exchange work items
     */
    public List<WorkItemView> workItems() {
        return jdbc.query(SELECT_WORK_ITEM
                + " WHERE w.destination_org = public.app_org() ORDER BY w.created_at, w.id",
                WORK_ITEM_MAPPER);
    }

    /**
     * Read one work item projected to the active receiving organization.
     *
     * @param id work item identifier
     * @return the work item when visible in this destination
     */
    public Optional<WorkItemView> workItem(String id) {
        return jdbc.query(SELECT_WORK_ITEM
                        + " WHERE w.id = CAST(:id AS uuid) AND w.destination_org = public.app_org()",
                Map.of("id", id), WORK_ITEM_MAPPER).stream().findFirst();
    }

    /**
     * Bind a locally verified patient to a work item once.
     *
     * @param id            work item identifier
     * @param localPatientId receiver tenant patient identifier
     * @return number of updated rows; {@code 0} when the item is unknown or already verified
     */
    public int verifyPatient(String id, String localPatientId) {
        return jdbc.update("""
                UPDATE public.exchange_work_item
                   SET local_patient_id = CAST(:localPatientId AS uuid),
                       patient_verified = true, state = 'ready'
                 WHERE id = CAST(:id AS uuid) AND destination_org = public.app_org()
                   AND patient_verified = false
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("localPatientId", localPatientId));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static AuthorizationView mapAuthorization(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AuthorizationView(resultSet.getString("id"),
                resultSet.getString("organization_id"), resultSet.getString("order_code"),
                resultSet.getString("destination_org"), resultSet.getString("purpose"),
                resultSet.getInt("max_duration_minutes"), instant(resultSet, "effective_from"),
                instant(resultSet, "expires_at"), resultSet.getBoolean("active"));
    }

    private static OrderView mapOrder(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OrderView(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("encounter_ref"),
                resultSet.getString("order_code"), resultSet.getString("priority"),
                resultSet.getString("clinical_question"), resultSet.getString("destination_org"),
                resultSet.getString("source_actor"), resultSet.getString("responsible_clinician"),
                resultSet.getString("work_queue"), resultSet.getString("authorization_id"),
                resultSet.getString("snapshot_id"), resultSet.getString("snapshot_fingerprint"),
                resultSet.getString("disclosure_id"), resultSet.getString("work_item_id"),
                instant(resultSet, "created_at"));
    }

    private static WorkItemView mapWorkItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkItemView(resultSet.getString("id"), resultSet.getString("sender_org"),
                resultSet.getString("destination_org"), resultSet.getString("order_ref"),
                resultSet.getString("patient_ref"), resultSet.getString("patient_mrn"),
                resultSet.getString("mrn_issuer"), resultSet.getString("local_patient_id"),
                resultSet.getBoolean("patient_verified"), resultSet.getString("priority"),
                resultSet.getString("clinical_question"), resultSet.getString("work_queue"),
                resultSet.getString("fingerprint"), resultSet.getString("state"),
                instant(resultSet, "created_at"));
    }

    /**
     * Issuer-scoped patient identity used for local verification.
     *
     * @param mrn    medical record number
     * @param issuer issuer of that number
     */
    public record PatientIdentity(String mrn, String issuer) {
    }
}
