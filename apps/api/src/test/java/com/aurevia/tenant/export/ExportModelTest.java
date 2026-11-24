package com.aurevia.tenant.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.tenant.export.ExportModel.AuditEntry;
import com.aurevia.tenant.export.ExportModel.ExportedDocument;
import com.aurevia.tenant.export.ExportModel.ExportJob;
import com.aurevia.tenant.export.ExportModel.ExportRecord;
import com.aurevia.tenant.export.ExportModel.Manifest;
import com.aurevia.tenant.export.ExportModel.Provenance;

/** OPS-07: manifest bytes carry schema, policy, checksums, provenance and audit. */
class ExportModelTest {

    private static final String CHECKSUM = "a".repeat(64);

    private static ExportRecord record(String id) {
        return new ExportRecord(id, CHECKSUM, new Provenance("documents", "doc-" + id, 1),
                List.of(new ExportedDocument("doc-" + id, CHECKSUM, "release_policy", "policy-1")),
                List.of(new AuditEntry(1L, "released", "SUCCESS")));
    }

    @Test
    void manifestCarriesEveryDeclaredField() {
        Manifest manifest = new Manifest("1.0", "policy-1", "org-a",
                "2026-11-21T10:00:00Z", 1, List.of(record("rec-1")));
        String json = new String(manifest.bytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schema_version\":\"1.0\""));
        assertTrue(json.contains("\"policy_version\":\"policy-1\""));
        assertTrue(json.contains("\"organization\":\"org-a\""));
        assertTrue(json.contains("\"exported_at\":\"2026-11-21T10:00:00Z\""));
        assertTrue(json.contains("\"record_count\":1"));
        assertTrue(json.contains("\"id\":\"rec-1\""));
        assertTrue(json.contains("\"checksum\":\"" + CHECKSUM + "\""));
        assertTrue(json.contains("\"source_system\":\"documents\""));
        assertTrue(json.contains("\"source_id\":\"doc-rec-1\""));
        assertTrue(json.contains("\"version\":1"));
        assertTrue(json.contains("\"authorization\":\"release_policy\""));
        assertTrue(json.contains("\"seq\":1"));
        assertTrue(json.contains("\"action\":\"released\""));
        assertTrue(json.contains("\"outcome\":\"SUCCESS\""));
    }

    @Test
    void multipleRecordsAreCommaSeparated() {
        Manifest manifest = new Manifest("1.0", "policy-1", "org-a",
                "2026-11-21T10:00:00Z", 2, List.of(record("rec-1"), record("rec-2")));
        String json = new String(manifest.bytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"id\":\"rec-1\""));
        assertTrue(json.contains("\"id\":\"rec-2\""));
        assertEquals(2, json.split("\"id\":\"rec-", -1).length - 1);
    }

    @Test
    void multipleDocumentsAndAuditEntriesAreCommaSeparated() {
        ExportRecord record = new ExportRecord("rec-1", CHECKSUM,
                new Provenance("documents", "doc-1", 1),
                List.of(new ExportedDocument("doc-1", CHECKSUM, "release_policy", "policy-1"),
                        new ExportedDocument("doc-2", "b".repeat(64), "record_access_request", "policy-1")),
                List.of(new AuditEntry(1L, "uploaded", "SUCCESS"),
                        new AuditEntry(2L, "released", "SUCCESS")));
        Manifest manifest = new Manifest("1.0", "policy-1", "org-a",
                "2026-11-21T10:00:00Z", 1, List.of(record));
        String json = new String(manifest.bytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"authorization\":\"record_access_request\""));
        assertTrue(json.contains("\"seq\":2"));
        assertEquals(3, json.split("\"policy_version\":\"policy-1\"", -1).length - 1);
        assertEquals(2, json.split("\"action\":", -1).length - 1);
    }

    @Test
    void recordAndManifestCollectionsCannotBeMutatedByCallers() {
        List<ExportedDocument> documents = new ArrayList<>();
        ExportedDocument document = new ExportedDocument("doc-1", CHECKSUM, "release_policy", "policy-1");
        documents.add(document);
        ExportRecord record = new ExportRecord("rec-1", CHECKSUM,
                new Provenance("documents", "doc-1", 1), documents, new ArrayList<>());
        documents.clear();
        assertEquals(1, record.documents().size());
        assertThrows(UnsupportedOperationException.class,
                () -> record.documents().add(document));

        List<ExportRecord> records = new ArrayList<>();
        records.add(record);
        Manifest manifest = new Manifest("1.0", "policy-1", "org-a",
                "2026-11-21T10:00:00Z", 1, records);
        records.clear();
        assertEquals(1, manifest.records().size());
        assertThrows(UnsupportedOperationException.class, () -> manifest.records().add(record));
    }

    @Test
    void quotesEscapesControlCharacters() {
        Manifest manifest = new Manifest("1.0", "policy-1", "org-\"a\"\n",
                "2026-11-21T10:00:00Z", 1, List.of());
        String json = new String(manifest.bytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("org-\\\"a\\\"\\n"));
    }

    @Test
    void jobExpiryIsStrict() {
        String expiresAt = "2026-11-22T10:00:00Z";
        ExportJob job = new ExportJob("job-1", "COMPLETE", "obj-1", 1, CHECKSUM,
                "2026-11-21T10:00:00Z", expiresAt);
        assertFalse(job.expired(Instant.parse("2026-11-22T09:59:59Z")));
        assertTrue(job.expired(Instant.parse(expiresAt)));
        assertTrue(job.expired(Instant.parse("2026-11-22T10:00:01Z")));
    }

    @Test
    void anExplicitlyExpiredJobIsExpiredBeforeItsRetentionInstant() {
        ExportJob job = new ExportJob("job-1", "EXPIRED", "obj-1", 1, CHECKSUM,
                "2026-11-21T10:00:00Z", "2026-11-22T10:00:00Z");
        // Explicit expiry ends disclosure now; the retention instant is only a
        // backstop for jobs that nobody expired. Expiry marks the row before it
        // removes the object, so status must gate downloads in that window.
        assertTrue(job.expired(Instant.parse("2026-11-21T10:00:01Z")));
    }
}
