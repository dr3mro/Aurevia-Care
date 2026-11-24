package com.aurevia.clinical.notes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single SQL boundary for the CLN-05 revision ledger.
 *
 * <p>V15 applies forced RLS and append-only triggers to the revision table.
 * Writes are INSERTs inside the guarded transaction; the version, the actors and
 * the checksum are owned by database triggers. Reads use the effective-revision
 * fold for the current view and the raw ledger for history.</p>
 */
@Repository
public class NoteRepository {

    private static final RowMapper<ClinicalNoteRevision> MAPPER = NoteRepository::mapRevision;

    private static final String COLUMNS = """
            id::text, version, state, patient_id::text, encounter_id::text, title, content,
            language, template_code, template_version, clinical_author_subject,
            data_entry_subject, effective_at::text, signed_at::text, signer_subject,
            attestation, content_checksum, supersedes_version, entered_error_reason,
            entered_error_actor, affected_recipients::text, created_at::text
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public NoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative V4 fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective membership is active
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
     * Confirm the patient is visible in the active organization and current.
     *
     * @param patientId opaque patient identifier
     * @return {@code true} when the patient resolves in the active tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.current_patients() WHERE id = CAST(:id AS uuid))
                """, Map.of("id", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Serialize all revisions of one note inside the current transaction.
     *
     * @param id opaque note identifier
     */
    public void lockNote(String id) {
        jdbc.queryForObject("""
                SELECT pg_advisory_xact_lock(hashtextextended(
                    public.app_org()::text || ':' || CAST(:id AS uuid)::text, 0))
                """, Map.of("id", id), Object.class);
    }

    /**
     * Find the current revision of a note.
     *
     * @param id opaque note identifier
     * @return the current revision when visible, otherwise empty
     */
    public Optional<ClinicalNoteRevision> current(String id) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM public.current_clinical_note_revision() WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), MAPPER).stream().findFirst();
    }

    /**
     * Read every revision of a note in version order.
     *
     * @param id opaque note identifier
     * @return the complete immutable history
     */
    public List<ClinicalNoteRevision> history(String id) {
        return jdbc.query("SELECT " + COLUMNS + """
                  FROM public.clinical_note_revision WHERE id = CAST(:id AS uuid)
                 ORDER BY version
                """, Map.of("id", id), MAPPER);
    }

    /**
     * Append one revision. The database owns its version, actors and checksum.
     *
     * @param organizationId active organization identifier
     * @param noteId         opaque note identifier
     * @param input          caller-supplied revision fields
     * @return number of inserted rows
     */
    public int insert(String organizationId, String noteId, RevisionInput input) {
        return jdbc.update("""
                INSERT INTO public.clinical_note_revision
                    (organization_id, id, version, state, patient_id, encounter_id, title, content,
                     language, template_code, template_version, clinical_author_subject,
                     effective_at, signed_at, attestation, content_checksum,
                     supersedes_version, entered_error_reason, affected_recipients)
                VALUES
                    (CAST(:organizationId AS uuid), CAST(:noteId AS uuid), 0, :state,
                     CAST(:patientId AS uuid), CAST(:encounterId AS uuid), :title, :content,
                     :language, :templateCode, :templateVersion, :clinicalAuthorSubject,
                     CAST(:effectiveAt AS timestamptz), CAST(:signedAt AS timestamptz), :attestation,
                     :contentChecksum, :supersedesVersion, :enteredErrorReason,
                     CAST(:affectedRecipients AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("noteId", noteId)
                .addValue("state", input.state().wire())
                .addValue("patientId", input.patientId())
                .addValue("encounterId", input.encounterId())
                .addValue("title", input.title())
                .addValue("content", input.content())
                .addValue("language", input.language())
                .addValue("templateCode", input.templateCode())
                .addValue("templateVersion", input.templateVersion())
                .addValue("clinicalAuthorSubject", input.clinicalAuthorSubject())
                .addValue("effectiveAt", input.effectiveAt())
                .addValue("signedAt", input.signedAt())
                .addValue("attestation", input.attestation())
                .addValue("contentChecksum", input.contentChecksum())
                .addValue("supersedesVersion", input.supersedesVersion())
                .addValue("enteredErrorReason", input.enteredErrorReason())
                .addValue("affectedRecipients", input.affectedRecipients()));
    }

    private static ClinicalNoteRevision mapRevision(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClinicalNoteRevision(resultSet.getString("id"), resultSet.getInt("version"),
                NoteState.from(resultSet.getString("state")), resultSet.getString("patient_id"),
                resultSet.getString("encounter_id"), resultSet.getString("title"),
                resultSet.getString("content"), resultSet.getString("language"),
                resultSet.getString("template_code"), resultSet.getInt("template_version"),
                resultSet.getString("clinical_author_subject"), resultSet.getString("data_entry_subject"),
                resultSet.getString("effective_at"), resultSet.getString("signed_at"),
                resultSet.getString("signer_subject"), resultSet.getString("attestation"),
                resultSet.getString("content_checksum"), (Integer) resultSet.getObject("supersedes_version"),
                resultSet.getString("entered_error_reason"), resultSet.getString("entered_error_actor"),
                resultSet.getString("affected_recipients"), resultSet.getString("created_at"));
    }
}
