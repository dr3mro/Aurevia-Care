package com.aurevia.smartclinic.g1.s_g1_07;

import com.aurevia.smartclinic.g1.s_g1_07.DocumentModel.DocumentAssoc;
import com.aurevia.smartclinic.g1.s_g1_07.DocumentModel.AssociationEvent;
import com.aurevia.smartclinic.g1.s_g1_07.DocumentModel.RenderedDocument;

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
 * The single SQL boundary for document associations and renders (CLN-07, ARC-02).
 *
 * <p>V17 applies forced RLS and actor-derived triggers to every table here. The
 * checksum is always computed by PostgreSQL from the retained bytes, never
 * supplied by a caller, so a forged digest cannot enter the store.</p>
 */
@Repository
public class DocumentRepository {

    private static final RowMapper<DocumentAssoc> ASSOC_MAPPER = DocumentRepository::mapAssoc;
    private static final RowMapper<AssociationEvent> EVENT_MAPPER = DocumentRepository::mapEvent;
    private static final RowMapper<RenderedDocument> RENDER_MAPPER = DocumentRepository::mapRender;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborator")
    public DocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership using the authoritative database fold.
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
     * Confirm that a chart subject exists in the active tenant.
     *
     * @param id opaque chart identifier
     * @return {@code true} when an effective chart is visible
     */
    public boolean chartExists(String id) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.current_patients() WHERE id = CAST(:id AS uuid))
                """, Map.of("id", id), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert an original with its bytes and database-derived checksum.
     *
     * @param organizationId active organization identifier
     * @param id             opaque association identifier
     * @param subjectType    chart, order or result
     * @param subjectId      opaque target identifier
     * @param fileName       original file name
     * @param mediaType      PDF, JPEG or PNG media type
     * @param content        retained original bytes
     * @param rotation       presentation-only quarter turn
     * @return number of inserted rows
     */
    public int insert(String organizationId, String id, String subjectType, String subjectId,
                      String fileName, String mediaType, byte[] content, int rotation) {
        return jdbc.update("""
                INSERT INTO public.document_association
                    (organization_id, id, subject_type, subject_id, file_name, media_type,
                     byte_size, content, rotation_degrees)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subjectType,
                        CAST(:subjectId AS uuid), :fileName, :mediaType, :byteSize,
                        :content, :rotation)
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("id", id)
                .addValue("subjectType", subjectType)
                .addValue("subjectId", subjectId)
                .addValue("fileName", fileName)
                .addValue("mediaType", mediaType)
                .addValue("byteSize", content.length)
                .addValue("content", content)
                .addValue("rotation", rotation));
    }

    /**
     * Find one association inside the active tenant.
     *
     * @param id opaque association identifier
     * @return the association when visible, otherwise empty
     */
    public Optional<DocumentAssoc> findAssociation(String id) {
        return jdbc.query("""
                SELECT id::text, version, subject_type, subject_id::text, file_name, media_type,
                       byte_size, sha256, rotation_degrees, status, void_reason,
                       created_by::text, created_at::text, voided_at::text
                  FROM public.document_association WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), ASSOC_MAPPER).stream().findFirst();
    }

    /**
     * Void an active association with a version precondition.
     *
     * @param id       opaque association identifier
     * @param version  version read by the caller
     * @param reason   recorded reason
     * @return number of updated rows
     */
    public int voidAssociation(String id, int version, String reason) {
        return jdbc.update("""
                UPDATE public.document_association
                   SET status = 'voided', void_reason = :reason, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :version AND status = 'active'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("version", version)
                .addValue("reason", reason));
    }

    /**
     * Append one link or void history event.
     *
     * @param associationId association the event describes
     * @param action        link or void
     * @param reason        recorded reason, or {@code null}
     * @return number of inserted rows
     */
    public int appendEvent(String associationId, String action, String reason) {
        return jdbc.update("""
                INSERT INTO public.document_association_event
                    (organization_id, association_id, action, reason)
                VALUES (public.app_org(), CAST(:associationId AS uuid), :action, :reason)
                """, new MapSqlParameterSource()
                .addValue("associationId", associationId)
                .addValue("action", action)
                .addValue("reason", reason));
    }

    /**
     * Read the append-only history of an association.
     *
     * @param id opaque association identifier
     * @return events in occurrence order
     */
    public List<AssociationEvent> associationHistory(String id) {
        return jdbc.query("""
                SELECT id::text, association_id::text, action, reason,
                       actor_id::text, occurred_at::text
                  FROM public.document_association_event
                 WHERE association_id = CAST(:id AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("id", id), EVENT_MAPPER);
    }

    /**
     * Reserve the next render version with a tenant-scoped database lock.
     *
     * @param subjectType chart, order or result
     * @param subjectId   opaque target identifier
     * @param locale      en or ar
     * @return the next positive version
     */
    public int nextRenderVersion(String subjectType, String subjectId, String locale) {
        // The SQL fold always returns one row, so no null fallback is needed.
        Integer version = jdbc.queryForObject("""
                SELECT public.next_render_version(:subjectType, CAST(:subjectId AS uuid), :locale)
                """, new MapSqlParameterSource()
                .addValue("subjectType", subjectType)
                .addValue("subjectId", subjectId)
                .addValue("locale", locale), Integer.class);
        return java.util.Objects.requireNonNull(version);
    }

    /**
     * Insert one immutable PDF render with its database-derived checksum.
     *
     * @param organizationId active organization identifier
     * @param id             opaque render identifier
     * @param subjectType    chart, order or result
     * @param subjectId      opaque target identifier
     * @param version        render version
     * @param locale         en or ar
     * @param direction      LTR or RTL
     * @param sourceSystem   source system provenance
     * @param sourceId       source identifier provenance
     * @param effectiveAt    clinical effective instant as text
     * @param content        immutable PDF bytes
     * @return number of inserted rows
     */
    public int insertRendered(String organizationId, String id, String subjectType, String subjectId,
                              int version, String locale, String direction, String sourceSystem,
                              String sourceId, String effectiveAt, byte[] content) {
        return jdbc.update("""
                INSERT INTO public.rendered_document
                    (organization_id, id, subject_type, subject_id, version, locale, direction,
                     source_system, source_id, effective_at, byte_size, content)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subjectType,
                        CAST(:subjectId AS uuid), :version, :locale, :direction,
                        :sourceSystem, :sourceId, :effectiveAt, :byteSize, :content)
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("id", id)
                .addValue("subjectType", subjectType)
                .addValue("subjectId", subjectId)
                .addValue("version", version)
                .addValue("locale", locale)
                .addValue("direction", direction)
                .addValue("sourceSystem", sourceSystem)
                .addValue("sourceId", sourceId)
                .addValue("effectiveAt", effectiveAt)
                .addValue("byteSize", content.length)
                .addValue("content", content));
    }

    /**
     * Find one rendered revision inside the active tenant.
     *
     * @param id opaque render identifier
     * @return the render metadata when visible, otherwise empty
     */
    public Optional<RenderedDocument> findRendered(String id) {
        return jdbc.query("""
                SELECT id::text, version, subject_type, subject_id::text, locale, direction,
                       page_count, byte_size, sha256, source_system, source_id,
                       rendered_at::text, created_by::text
                  FROM public.rendered_document WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), RENDER_MAPPER).stream().findFirst();
    }

    /**
     * List every rendered revision of a subject and locale, oldest first.
     *
     * @param subjectType chart, order or result
     * @param subjectId   opaque target identifier
     * @param locale      en or ar
     * @return render metadata in version order
     */
    public List<RenderedDocument> renderedVersions(String subjectType, String subjectId, String locale) {
        return jdbc.query("""
                SELECT id::text, version, subject_type, subject_id::text, locale, direction,
                       page_count, byte_size, sha256, source_system, source_id,
                       rendered_at::text, created_by::text
                  FROM public.rendered_document
                 WHERE subject_type = :subjectType AND subject_id = CAST(:subjectId AS uuid)
                   AND locale = :locale
                 ORDER BY version
                """, new MapSqlParameterSource()
                .addValue("subjectType", subjectType)
                .addValue("subjectId", subjectId)
                .addValue("locale", locale), RENDER_MAPPER);
    }

    /**
     * Read the immutable bytes of one rendered revision.
     *
     * @param id opaque render identifier
     * @return the PDF bytes when visible, otherwise empty
     */
    public Optional<byte[]> renderedContent(String id) {
        return jdbc.query("""
                SELECT content FROM public.rendered_document WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), (resultSet, rowNumber) -> resultSet.getBytes("content"))
                .stream().findFirst();
    }

    private static DocumentAssoc mapAssoc(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DocumentAssoc(
                resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("subject_type"), resultSet.getString("subject_id"),
                resultSet.getString("file_name"), resultSet.getString("media_type"),
                resultSet.getLong("byte_size"), resultSet.getString("sha256"),
                resultSet.getInt("rotation_degrees"), resultSet.getString("status"),
                resultSet.getString("void_reason"), resultSet.getString("created_by"),
                resultSet.getString("created_at"), resultSet.getString("voided_at"));
    }

    private static AssociationEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AssociationEvent(
                resultSet.getString("id"), resultSet.getString("association_id"),
                resultSet.getString("action"), resultSet.getString("reason"),
                resultSet.getString("actor_id"), resultSet.getString("occurred_at"));
    }

    private static RenderedDocument mapRender(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RenderedDocument(
                resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("subject_type"), resultSet.getString("subject_id"),
                resultSet.getString("locale"), resultSet.getString("direction"),
                resultSet.getInt("page_count"), resultSet.getLong("byte_size"),
                resultSet.getString("sha256"),
                resultSet.getString("source_system") + "/" + resultSet.getString("source_id"),
                resultSet.getString("rendered_at"), resultSet.getString("created_by"));
    }
}
