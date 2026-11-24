package com.aurevia.clinical.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic coverage for the PostgreSQL {@link com.aurevia.clinical.templates.TemplateStore}
 * adapter (CLN-04, ARC-02). The JDBC boundary is mocked so empty-result and write
 * paths are forced without a database; real PostgreSQL behavior is the integrator's
 * database gate. Every value here is synthetic.
 */
class JdbcTemplateStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final ObjectMapper JSON = new ObjectMapper();

    private NamedParameterJdbcTemplate jdbc;
    private JdbcTemplateStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        store = new JdbcTemplateStore(jdbc);
    }

    private static FieldDefinition field() {
        return new FieldDefinition("obstetricHistory", "obstetric history", FieldType.CODE, true,
                "obstetric_history", null);
    }

    private static FieldDictionary dictionary() {
        return new FieldDictionary("obstetric_history", List.of("G0P0", "G1P0"), true, "gov-a");
    }

    private static TemplateVersion version() {
        return new TemplateVersion(TemplateKind.OBGYN, 1, true, List.of(field()), "gov-a");
    }

    private static TemplateNoteRevision note() {
        return new TemplateNoteRevision(TemplateKind.OBGYN, 1, "PAT-001", "clin-a",
                List.of(new FieldValue("obstetricHistory", "G1P0", null)));
    }

    private static SavedNoteRevision savedNote() {
        return new SavedNoteRevision(ORG, "NOTE-1", note(), List.of(field()),
                Map.of("obstetric_history", List.of("G0P0", "G1P0")));
    }

    private static TemplateAuditEvent auditEvent() {
        return new TemplateAuditEvent(ORG, "gov-a", "TEMPLATE_APPROVE", "template", "OBGYN", "SUCCESS");
    }

    private void stubRows(Object... rows) {
        doReturn(List.of(rows)).when(jdbc).query(anyString(), any(SqlParameterSource.class),
                any(RowMapper.class));
    }

    private static ResultSet dictionaryRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("dictionary_key")).thenReturn("obstetric_history");
        when(rs.getString("entries")).thenReturn(JSON.writeValueAsString(List.of("G0P0", "G1P0")));
        when(rs.getBoolean("approved")).thenReturn(true);
        when(rs.getString("approved_by")).thenReturn("gov-a");
        return rs;
    }

    private static ResultSet versionRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("kind")).thenReturn("OBGYN");
        when(rs.getInt("version")).thenReturn(1);
        when(rs.getBoolean("approved")).thenReturn(true);
        when(rs.getString("fields")).thenReturn(JSON.writeValueAsString(List.of(field())));
        when(rs.getString("approved_by")).thenReturn("gov-a");
        return rs;
    }

    private static ResultSet noteRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("organization_id")).thenReturn(ORG);
        when(rs.getString("note_id")).thenReturn("NOTE-1");
        when(rs.getString("kind")).thenReturn("OBGYN");
        when(rs.getInt("template_version")).thenReturn(1);
        when(rs.getString("patient_ref")).thenReturn("PAT-001");
        when(rs.getString("author")).thenReturn("clin-a");
        when(rs.getString("values")).thenReturn(JSON.writeValueAsString(note().values()));
        when(rs.getString("field_meanings")).thenReturn(JSON.writeValueAsString(List.of(field())));
        when(rs.getString("dictionaries"))
                .thenReturn(JSON.writeValueAsString(Map.of("obstetric_history", List.of("G0P0", "G1P0"))));
        return rs;
    }

    private static ResultSet auditRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("organization_id")).thenReturn(ORG);
        when(rs.getString("actor_subject")).thenReturn("gov-a");
        when(rs.getString("action")).thenReturn("TEMPLATE_APPROVE");
        when(rs.getString("resource_type")).thenReturn("template");
        when(rs.getString("resource_id")).thenReturn("OBGYN");
        when(rs.getString("outcome")).thenReturn("SUCCESS");
        return rs;
    }

    @Test
    void dictionaryMapsRow() throws Exception {
        stubRows(JdbcTemplateStore.mapDictionary(dictionaryRow(), 0));
        assertEquals(dictionary(), store.dictionary(ORG, "obstetric_history").orElseThrow());
    }

    @Test
    void dictionaryIsEmptyWhenNoRow() {
        stubRows();
        assertTrue(store.dictionary(ORG, "absent").isEmpty());
    }

    @Test
    void putDictionaryWritesParameterizedRow() {
        store.putDictionary(ORG, dictionary());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void versionMapsRow() throws Exception {
        stubRows(JdbcTemplateStore.mapVersion(versionRow(), 0));
        assertEquals(version(), store.version(ORG, TemplateKind.OBGYN, 1).orElseThrow());
    }

    @Test
    void versionIsEmptyWhenNoRow() {
        stubRows();
        assertTrue(store.version(ORG, TemplateKind.OBGYN, 9).isEmpty());
    }

    @Test
    void versionsReturnsEveryFamilyRow() throws Exception {
        stubRows(JdbcTemplateStore.mapVersion(versionRow(), 0));
        List<TemplateVersion> found = store.versions(ORG, TemplateKind.OBGYN);
        assertEquals(List.of(version()), found);
    }

    @Test
    void addVersionAllocatesAndInserts() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(Integer.class)))
                .thenReturn(3);
        List<FieldDefinition> fields = List.of(field());
        assertEquals(new TemplateVersion(TemplateKind.OBGYN, 3, false, fields, null),
                store.addVersion(ORG, TemplateKind.OBGYN, fields));
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void putVersionMarksApproved() {
        store.putVersion(ORG, version());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void saveNoteRevisionWritesSnapshot() {
        store.saveNoteRevision(ORG, savedNote());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void noteRevisionMapsSnapshot() throws Exception {
        stubRows(JdbcTemplateStore.mapNoteRevision(noteRow(), 0));
        assertEquals(savedNote(), store.noteRevision(ORG, "NOTE-1").orElseThrow());
    }

    @Test
    void noteRevisionIsEmptyWhenNoRow() {
        stubRows();
        assertTrue(store.noteRevision(ORG, "absent").isEmpty());
    }

    @Test
    void auditMapsEvents() throws Exception {
        stubRows(JdbcTemplateStore.mapAudit(auditRow(), 0));
        assertEquals(List.of(auditEvent()), store.audit(ORG));
    }

    @Test
    void appendAuditWritesEvent() {
        store.appendAudit(auditEvent());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void frozenSnapshotsCannotBeMutatedThroughTheStoreRecord() {
        SavedNoteRevision saved = savedNote();
        assertFalse(saved.fieldMeanings().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> saved.fieldMeanings().clear());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> saved.dictionaries().clear());
    }
}
