package com.aurevia.clinical.templates;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tenant-owned JDBC store for CLN-04 template versions, field dictionaries,
 * saved note revisions and audit facts.
 *
 * <p>Every statement is parameterised and carries the active organization, which
 * V80 also enforces with forced row-level security. A published version is immutable
 * in the database, version numbers come from an atomic per-family counter, and a
 * saved note keeps the field meanings and dictionary entries it was saved with as
 * application-written JSON facts that reads never re-derive (ARC-02, CLN-04).</p>
 */
@Repository
public class JdbcTemplateStore implements TemplateStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> ENTRIES = new TypeReference<>() { };
    private static final TypeReference<List<FieldDefinition>> FIELDS = new TypeReference<>() { };
    private static final TypeReference<List<FieldValue>> VALUES = new TypeReference<>() { };
    private static final TypeReference<Map<String, List<String>>> DICTIONARIES = new TypeReference<>() { };

    private static final RowMapper<FieldDictionary> DICTIONARY_MAPPER = JdbcTemplateStore::mapDictionary;
    private static final RowMapper<TemplateVersion> VERSION_MAPPER = JdbcTemplateStore::mapVersion;
    private static final RowMapper<SavedNoteRevision> NOTE_MAPPER = JdbcTemplateStore::mapNoteRevision;
    private static final RowMapper<TemplateAuditEvent> AUDIT_MAPPER = JdbcTemplateStore::mapAudit;

    private static final String DICTIONARY_COLUMNS = """
            dictionary_key, entries::text, approved, approved_by
              FROM public.template_field_dictionary
             WHERE organization_id = CAST(:organizationId AS uuid) AND dictionary_key = :key
            """;

    private static final String UPSERT_DICTIONARY = """
            INSERT INTO public.template_field_dictionary
                (organization_id, dictionary_key, entries, approved, approved_by)
            VALUES
                (CAST(:organizationId AS uuid), :key, CAST(:entries AS jsonb), :approved, :approvedBy)
            ON CONFLICT (organization_id, dictionary_key) DO UPDATE SET
                entries = EXCLUDED.entries,
                approved = EXCLUDED.approved,
                approved_by = EXCLUDED.approved_by
            """;

    private static final String VERSION_COLUMNS = """
            kind, version, approved, fields::text, approved_by
              FROM public.template_version
             WHERE organization_id = CAST(:organizationId AS uuid)
               AND kind = :kind AND version = :version
            """;

    private static final String VERSIONS_OF_FAMILY = """
            SELECT kind, version, approved, fields::text, approved_by
              FROM public.template_version
             WHERE organization_id = CAST(:organizationId AS uuid) AND kind = :kind
             ORDER BY version
            """;

    private static final String NEXT_VERSION = """
            INSERT INTO public.template_version_sequence (organization_id, kind, next_version)
            VALUES (CAST(:organizationId AS uuid), :kind, 2)
            ON CONFLICT (organization_id, kind)
            DO UPDATE SET next_version = public.template_version_sequence.next_version + 1
            RETURNING next_version
            """;

    private static final String INSERT_VERSION = """
            INSERT INTO public.template_version
                (organization_id, kind, version, approved, fields, approved_by)
            VALUES
                (CAST(:organizationId AS uuid), :kind, :version, false, CAST(:fields AS jsonb), null)
            """;

    private static final String APPROVE_VERSION = """
            UPDATE public.template_version
               SET approved = true, approved_by = :approvedBy
             WHERE organization_id = CAST(:organizationId AS uuid)
               AND kind = :kind AND version = :version AND approved = false
            """;

    private static final String INSERT_NOTE = """
            INSERT INTO public.template_note_revision
                (organization_id, note_id, kind, template_version, patient_ref, author,
                 values, field_meanings, dictionaries)
            VALUES
                (CAST(:organizationId AS uuid), :noteId, :kind, :templateVersion, :patientRef,
                 :author, CAST(:values AS jsonb), CAST(:fieldMeanings AS jsonb),
                 CAST(:dictionaries AS jsonb))
            """;

    private static final String NOTE_COLUMNS = """
            organization_id::text, note_id, kind, template_version, patient_ref, author,
            values::text, field_meanings::text, dictionaries::text
              FROM public.template_note_revision
             WHERE organization_id = CAST(:organizationId AS uuid) AND note_id = :noteId
            """;

    private static final String AUDIT_COLUMNS = """
            organization_id::text, actor_subject, action, resource_type, resource_id, outcome
              FROM public.template_audit_event
             WHERE organization_id = CAST(:organizationId AS uuid)
             ORDER BY event_id
            """;

    private static final String INSERT_AUDIT = """
            INSERT INTO public.template_audit_event
                (organization_id, actor_subject, action, resource_type, resource_id, outcome)
            VALUES
                (CAST(:organizationId AS uuid), :actorSubject, :action,
                 :resourceType, :resourceId, :outcome)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the store over the tenant-guarded JDBC boundary.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public JdbcTemplateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FieldDictionary> dictionary(String organizationId, String key) {
        return jdbc.query("SELECT " + DICTIONARY_COLUMNS,
                new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("key", key),
                DICTIONARY_MAPPER).stream().findFirst();
    }

    @Override
    public void putDictionary(String organizationId, FieldDictionary dictionary) {
        jdbc.update(UPSERT_DICTIONARY, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("key", dictionary.key())
                .addValue("entries", JSON.writeValueAsString(dictionary.entries()))
                .addValue("approved", dictionary.approved())
                .addValue("approvedBy", dictionary.approvedBy()));
    }

    @Override
    public Optional<TemplateVersion> version(String organizationId, TemplateKind kind, int version) {
        return jdbc.query("SELECT " + VERSION_COLUMNS,
                versionParams(organizationId, kind, version), VERSION_MAPPER).stream().findFirst();
    }

    @Override
    public List<TemplateVersion> versions(String organizationId, TemplateKind kind) {
        return jdbc.query(VERSIONS_OF_FAMILY,
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("kind", kind.name()), VERSION_MAPPER);
    }

    @Override
    public void putVersion(String organizationId, TemplateVersion version) {
        jdbc.update(APPROVE_VERSION, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("kind", version.kind().name())
                .addValue("version", version.version())
                .addValue("approvedBy", version.approvedBy()));
    }

    @Override
    public TemplateVersion addVersion(String organizationId, TemplateKind kind, List<FieldDefinition> fields) {
        int next = Objects.requireNonNull(jdbc.queryForObject(NEXT_VERSION,
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("kind", kind.name()), Integer.class),
                "version allocation must return a number");
        jdbc.update(INSERT_VERSION, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("kind", kind.name())
                .addValue("version", next)
                .addValue("fields", JSON.writeValueAsString(fields)));
        return new TemplateVersion(kind, next, false, fields, null);
    }

    @Override
    public void saveNoteRevision(String organizationId, SavedNoteRevision revision) {
        TemplateNoteRevision note = revision.note();
        jdbc.update(INSERT_NOTE, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("noteId", revision.noteId())
                .addValue("kind", note.kind().name())
                .addValue("templateVersion", note.templateVersion())
                .addValue("patientRef", note.patientRef())
                .addValue("author", note.author())
                .addValue("values", JSON.writeValueAsString(note.values()))
                .addValue("fieldMeanings", JSON.writeValueAsString(revision.fieldMeanings()))
                .addValue("dictionaries", JSON.writeValueAsString(revision.dictionaries())));
    }

    @Override
    public Optional<SavedNoteRevision> noteRevision(String organizationId, String noteId) {
        return jdbc.query("SELECT " + NOTE_COLUMNS,
                new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("noteId", noteId),
                NOTE_MAPPER).stream().findFirst();
    }

    @Override
    public List<TemplateAuditEvent> audit(String organizationId) {
        return jdbc.query("SELECT " + AUDIT_COLUMNS,
                new MapSqlParameterSource().addValue("organizationId", organizationId), AUDIT_MAPPER);
    }

    @Override
    public void appendAudit(TemplateAuditEvent event) {
        jdbc.update(INSERT_AUDIT, new MapSqlParameterSource()
                .addValue("organizationId", event.organizationId())
                .addValue("actorSubject", event.actorSubject())
                .addValue("action", event.action())
                .addValue("resourceType", event.resourceType())
                .addValue("resourceId", event.resourceId())
                .addValue("outcome", event.outcome()));
    }

    private static MapSqlParameterSource versionParams(String organizationId, TemplateKind kind, int version) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("kind", kind.name()).addValue("version", version);
    }

    static FieldDictionary mapDictionary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new FieldDictionary(resultSet.getString("dictionary_key"),
                JSON.readValue(resultSet.getString("entries"), ENTRIES),
                resultSet.getBoolean("approved"), resultSet.getString("approved_by"));
    }

    static TemplateVersion mapVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TemplateVersion(TemplateKind.valueOf(resultSet.getString("kind")),
                resultSet.getInt("version"), resultSet.getBoolean("approved"),
                JSON.readValue(resultSet.getString("fields"), FIELDS), resultSet.getString("approved_by"));
    }

    static SavedNoteRevision mapNoteRevision(ResultSet resultSet, int rowNumber) throws SQLException {
        TemplateNoteRevision note = new TemplateNoteRevision(
                TemplateKind.valueOf(resultSet.getString("kind")), resultSet.getInt("template_version"),
                resultSet.getString("patient_ref"), resultSet.getString("author"),
                JSON.readValue(resultSet.getString("values"), VALUES));
        return new SavedNoteRevision(resultSet.getString("organization_id"), resultSet.getString("note_id"),
                note, JSON.readValue(resultSet.getString("field_meanings"), FIELDS),
                JSON.readValue(resultSet.getString("dictionaries"), DICTIONARIES));
    }

    static TemplateAuditEvent mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TemplateAuditEvent(resultSet.getString("organization_id"),
                resultSet.getString("actor_subject"), resultSet.getString("action"),
                resultSet.getString("resource_type"), resultSet.getString("resource_id"),
                resultSet.getString("outcome"));
    }
}
