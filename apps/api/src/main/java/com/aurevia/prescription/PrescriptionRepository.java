package com.aurevia.prescription;

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

import com.aurevia.catalog.MedicationCatalogItem;
import com.aurevia.catalog.MedicationCatalogVersion;
import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.AllergyEntry;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;
import com.aurevia.prescription.PrescriptionContract.OverrideRecord;

/**
 * The single SQL boundary for RX-01 prescription drafts.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced
 * row-level security confines it to the active organization. Authors and
 * prescribers are derived by database triggers from {@code app.actor}; no caller
 * value can attribute a draft or an override to another user.</p>
 */
@Repository
public class PrescriptionRepository {

    private static final String DRAFT_SELECT = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, state,
                   selected_entry_key, selected_entry_version, drug_generic_name,
                   dose_value, dose_unit, route, frequency, quantity, quantity_unit,
                   refills, instructions, destination, encounter_reference,
                   prescriber_subject, created_at
              FROM public.prescription_draft
            """;

    private static final String CATALOG_SELECT = """
            SELECT entry_key, version, generic_name, trade_name, formulation,
                   strength_value, strength_unit, substance_ids, substance_groups, group_versions,
                   approved, effective_from, effective_to,
                   coalesce(approval_provenance, 'unapproved') AS approval_provenance,
                   route
              FROM public.drug_catalog_entry
             ORDER BY entry_key, version
            """;

    private static final RowMapper<Draft> DRAFT_MAPPER = PrescriptionRepository::mapDraft;
    private static final RowMapper<DrugEntry> CATALOG_MAPPER = PrescriptionRepository::mapDrugEntry;
    private static final RowMapper<MedicationCatalogItem> MEDICATION_ITEM_MAPPER =
            PrescriptionRepository::mapMedicationItem;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public PrescriptionRepository(NamedParameterJdbcTemplate jdbc) {
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
     * Insert a new draft in the draft state.
     *
     * @param id                new opaque identifier
     * @param patientId         owning patient
     * @param encounterReference optional encounter link
     * @param destination       intended destination
     * @return number of inserted rows
     */
    public int insertDraft(String id, String patientId, String encounterReference, String destination) {
        return jdbc.update("""
                INSERT INTO public.prescription_draft
                    (organization_id, id, version, patient_id, state, encounter_reference, destination)
                VALUES (public.app_org(), CAST(:id AS uuid), 1, CAST(:patientId AS uuid), 'draft',
                        :encounterReference, :destination)
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("patientId", patientId).addValue("encounterReference", encounterReference)
                .addValue("destination", destination));
    }

    /**
     * Read one draft.
     *
     * @param id draft identifier
     * @return the draft when visible
     */
    public Optional<Draft> find(String id) {
        return jdbc.query(DRAFT_SELECT + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), DRAFT_MAPPER).stream().findFirst();
    }

    /**
     * Read one draft, locking its row for the current transaction.
     *
     * @param id draft identifier
     * @return the locked draft when visible
     */
    public Optional<Draft> loadForUpdate(String id) {
        return jdbc.query(DRAFT_SELECT + " WHERE id = CAST(:id AS uuid) FOR UPDATE",
                Map.of("id", id), DRAFT_MAPPER).stream().findFirst();
    }

    /**
     * Apply a version-guarded medication selection to a draft.
     *
     * @param id               draft identifier
     * @param expectedVersion  version read by the caller
     * @param entryKey         selected catalog entry key
     * @param entryVersion     selected catalog entry version
     * @param drugGenericName  generic name snapshot
     * @param doseValue        dose value as entered
     * @param doseUnit         dose unit as entered
     * @param route            route as entered
     * @param frequency        frequency as entered
     * @param quantity         quantity as entered
     * @param quantityUnit     quantity unit as entered
     * @param refills          supported refill count, or {@code null}
     * @param instructions     clinician instructions
     * @param catalogVersion   published catalog version to bind on first selection
     * @return number of updated rows; zero means the version moved
     */
    public int selectMedication(String id, int expectedVersion, String entryKey, int entryVersion,
                                String drugGenericName, String doseValue, String doseUnit,
                                String route, String frequency, String quantity, String quantityUnit,
                                Integer refills, String instructions, int catalogVersion) {
        return jdbc.update("""
                UPDATE public.prescription_draft
                   SET selected_entry_key = :entryKey, selected_entry_version = :entryVersion,
                       drug_generic_name = :drugGenericName, dose_value = :doseValue,
                       dose_unit = :doseUnit, route = :route, frequency = :frequency,
                       quantity = :quantity, quantity_unit = :quantityUnit, refills = :refills,
                       instructions = :instructions, version = version + 1,
                       catalog_version = coalesce(catalog_version, :catalogVersion)
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("entryKey", entryKey)
                .addValue("entryVersion", entryVersion).addValue("drugGenericName", drugGenericName)
                .addValue("doseValue", doseValue).addValue("doseUnit", doseUnit)
                .addValue("route", route).addValue("frequency", frequency)
                .addValue("quantity", quantity).addValue("quantityUnit", quantityUnit)
                .addValue("refills", refills, java.sql.Types.INTEGER)
                .addValue("instructions", instructions).addValue("catalogVersion", catalogVersion));
    }

    /**
     * Append an immutable override with its matched data snapshot.
     *
     * @param draftId        owning draft
     * @param draftVersion   exact revision that was overridden
     * @param reason         clinician's documented reason
     * @param matchedSnapshot deterministic snapshot of the matched data
     */
    public void insertOverride(String draftId, int draftVersion, String reason, String matchedSnapshot) {
        jdbc.update("""
                INSERT INTO public.prescription_override
                    (organization_id, prescription_draft_id, draft_version, override_reason, matched_snapshot)
                VALUES (public.app_org(), CAST(:draftId AS uuid), :draftVersion, :reason, :snapshot)
                """, new MapSqlParameterSource().addValue("draftId", draftId)
                .addValue("draftVersion", draftVersion).addValue("reason", reason)
                .addValue("snapshot", matchedSnapshot));
    }

    /**
     * Read the newest override recorded for a draft.
     *
     * @param draftId draft identifier
     * @return the override when one exists
     */
    public Optional<OverrideRecord> findOverride(String draftId) {
        return jdbc.query("""
                SELECT prescription_draft_id::text AS draft_id, draft_version, override_reason,
                       author_subject, matched_snapshot, created_at
                  FROM public.prescription_override
                 WHERE prescription_draft_id = CAST(:draftId AS uuid)
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, Map.of("draftId", draftId), (resultSet, rowNumber) -> new OverrideRecord(
                resultSet.getString("draft_id"), resultSet.getInt("draft_version"),
                resultSet.getString("override_reason"), resultSet.getString("author_subject"),
                resultSet.getString("matched_snapshot"), instant(resultSet, "created_at")))
                .stream().findFirst();
    }

    /**
     * Load every stored version of the tenant's drug catalog.
     *
     * @return all catalog rows, including unapproved and superseded versions
     */
    public List<DrugEntry> loadCatalog() {
        return jdbc.query(CATALOG_SELECT, CATALOG_MAPPER);
    }

    /**
     * Load every stored version of the tenant's medication catalog with its approved route.
     *
     * @return all catalog items, including unapproved and superseded versions
     */
    public List<MedicationCatalogItem> loadMedicationCatalog() {
        return jdbc.query(CATALOG_SELECT, MEDICATION_ITEM_MAPPER);
    }

    /**
     * Read the newest published market-approved catalog version.
     *
     * <p>Tenants whose catalog predates version publication read the documented
     * baseline (version 1); the registry is authoritative once a version exists.</p>
     *
     * @return the current catalog version, never {@code null}
     */
    public MedicationCatalogVersion currentCatalogVersion() {
        List<MedicationCatalogVersion> versions = jdbc.query("""
                SELECT version, market, published_at, provenance
                  FROM public.medication_catalog_version
                 ORDER BY version DESC LIMIT 1
                """, (resultSet, rowNumber) -> new MedicationCatalogVersion(
                resultSet.getInt("version"), resultSet.getString("market"),
                instant(resultSet, "published_at"), resultSet.getString("provenance")));
        return versions.stream().findFirst().orElse(MedicationCatalogVersion.BASELINE);
    }

    /**
     * Read the market-approved route declared on a specific drug catalog version.
     *
     * @param entryId catalog entry key
     * @param version approved version number
     * @return the declared route, or the sentinel {@code unspecified} for legacy entries
     */
    public String catalogRoute(String entryId, int version) {
        return jdbc.queryForObject("""
                SELECT route FROM public.drug_catalog_entry
                 WHERE entry_key = :entryKey AND version = :version
                """, new MapSqlParameterSource().addValue("entryKey", entryId)
                .addValue("version", version), String.class);
    }

    /**
     * Read the catalog version a draft was built from.
     *
     * @param draftId draft identifier
     * @return the stored version, or {@code null} for a draft with no medication selected
     */
    public Integer catalogVersion(String draftId) {
        // A draft with no medication selected legitimately stores NULL here.
        // queryForObject returns that null directly; the service has already
        // confirmed the row exists, so there is no empty-result path to fold.
        return jdbc.queryForObject("""
                SELECT catalog_version FROM public.prescription_draft
                 WHERE id = CAST(:id AS uuid)
                """, Map.of("id", draftId), Integer.class);
    }

    /**
     * Fold a patient's latest allergy status and all synthetic allergy statements.
     *
     * <p>Absence of a status row is mapped to the explicit UNKNOWN state; it is
     * never treated as "no known allergy".</p>
     *
     * @param patientId patient identifier
     * @return the assessment with its statements
     */
    public AllergyAssessment allergyAssessment(String patientId) {
        List<String> statuses = jdbc.query("""
                SELECT status FROM public.patient_allergy_status
                 WHERE patient_id = CAST(:patientId AS uuid)
                 ORDER BY recorded_at DESC, id DESC LIMIT 1
                """, Map.of("patientId", patientId),
                (resultSet, rowNumber) -> resultSet.getString("status"));
        AllergyStatus status = statuses.isEmpty() ? AllergyStatus.UNKNOWN
                : AllergyStatus.from(statuses.get(0));
        List<AllergyEntry> entries = jdbc.query("""
                SELECT substance_id, group_code, coalesce(group_version, 0) AS group_version,
                       verification_state, source, effective_from
                  FROM public.patient_allergy
                 WHERE patient_id = CAST(:patientId AS uuid)
                 ORDER BY effective_from, id
                """, Map.of("patientId", patientId), (resultSet, rowNumber) -> new AllergyEntry(
                resultSet.getString("substance_id"), resultSet.getString("group_code"),
                resultSet.getInt("group_version"), resultSet.getString("verification_state"),
                resultSet.getString("source"), instant(resultSet, "effective_from")));
        return new AllergyAssessment(status, entries);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static DrugEntry mapDrugEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        List<Ingredient> ingredients = ingredients(resultSet);
        return new DrugEntry(resultSet.getString("entry_key"), resultSet.getInt("version"),
                resultSet.getString("generic_name"), resultSet.getString("trade_name"),
                resultSet.getString("formulation"), resultSet.getString("strength_value"),
                resultSet.getString("strength_unit"), ingredients,
                resultSet.getBoolean("approved"), instant(resultSet, "effective_from"),
                instant(resultSet, "effective_to"), resultSet.getString("approval_provenance"));
    }

    private static MedicationCatalogItem mapMedicationItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MedicationCatalogItem(mapDrugEntry(resultSet, rowNumber), resultSet.getString("route"));
    }

    private static List<Ingredient> ingredients(ResultSet resultSet) throws SQLException {
        // The V43 schema declares all three arrays NOT NULL with equal cardinality,
        // so no null-array guard is needed here.
        java.sql.Array substances = resultSet.getArray("substance_ids");
        java.sql.Array substance_groups = resultSet.getArray("substance_groups");
        java.sql.Array versions = resultSet.getArray("group_versions");
        Object[] substanceValues = (Object[]) substances.getArray();
        Object[] groupValues = (Object[]) substance_groups.getArray();
        Object[] versionValues = (Object[]) versions.getArray();
        java.util.List<Ingredient> ingredients = new java.util.ArrayList<>();
        for (int index = 0; index < substanceValues.length; index++) {
            Object version = versionValues[index];
            ingredients.add(new Ingredient((String) substanceValues[index],
                    (String) groupValues[index], version == null ? 0 : ((Number) version).intValue()));
        }
        return ingredients;
    }

    private static Draft mapDraft(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Draft(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("state"),
                resultSet.getString("selected_entry_key"), nullableInt(resultSet, "selected_entry_version"),
                resultSet.getString("drug_generic_name"), resultSet.getString("dose_value"),
                resultSet.getString("dose_unit"), resultSet.getString("route"),
                resultSet.getString("frequency"), resultSet.getString("quantity"),
                resultSet.getString("quantity_unit"), nullableInt(resultSet, "refills"),
                resultSet.getString("instructions"), resultSet.getString("destination"),
                resultSet.getString("encounter_reference"), resultSet.getString("prescriber_subject"),
                instant(resultSet, "created_at"));
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
