package com.aurevia.smartclinic.tenant.export;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Value types for the OPS-07 tenant export manifest and its job record.
 *
 * <p>The manifest names its schema and authorizing policy, carries a checksum
 * for every exported file, keeps the source provenance and includes the
 * append-only audit history for each record. Its JSON bytes are the artifact
 * placed in the private object store; no patient identifier or file name is
 * included beyond the opaque tenant-local document alias.</p>
 */
public final class ExportModel {

    private ExportModel() {
    }

    /**
     * One append-only audit fact attached to an exported record.
     *
     * @param seq    per-tenant monotonic audit sequence
     * @param action audit action key
     * @param outcome audit outcome
     */
    public record AuditEntry(long seq, String action, String outcome) {
    }

    /**
     * Origin of an exported record.
     *
     * @param sourceSystem system that created the record
     * @param sourceId     opaque identifier in the source system
     * @param version      source version, strictly positive
     */
    public record Provenance(String sourceSystem, String sourceId, int version) {
    }

    /**
     * Metadata for one policy-authorized source document in the active tenant.
     *
     * @param recordId     tenant-local record identifier
     * @param objectId     tenant-scoped object alias holding the document bytes
     * @param objectVersion immutable object version
     * @param checksum     verified checksum of the source bytes
     * @param authorization policy authorization that permits the export
     * @param provenance   source lineage preserved on the exported record
     */
    public record ExportSource(String recordId, String objectId, int objectVersion,
                               String checksum, String authorization, Provenance provenance) {
    }

    /**
     * One authorized document in an exported record.
     *
     * @param id            opaque document identifier
     * @param checksum      checksum recomputed from the exported bytes
     * @param authorization policy authorization that permits the export
     * @param policyVersion policy version that authorized the document
     */
    public record ExportedDocument(String id, String checksum, String authorization,
                                   String policyVersion) {
    }

    /**
     * One exported record with checksums, provenance, documents and audit history.
     *
     * @param id        tenant-local record identifier
     * @param checksum  checksum recomputed from the exported bytes
     * @param provenance source lineage
     * @param documents authorized documents
     * @param audit     append-only audit history in increasing sequence order
     */
    public record ExportRecord(String id, String checksum, Provenance provenance,
                               List<ExportedDocument> documents, List<AuditEntry> audit) {

        /**
         * Freeze the collections so a caller cannot change an exported record.
         */
        public ExportRecord {
            documents = List.copyOf(documents);
            audit = List.copyOf(audit);
        }

        /**
         * Return the immutable document list.
         *
         * @return authorized documents
         */
        @Override
        public List<ExportedDocument> documents() {
            return List.copyOf(documents);
        }

        /**
         * Return the immutable audit history.
         *
         * @return append-only audit entries
         */
        @Override
        public List<AuditEntry> audit() {
            return List.copyOf(audit);
        }
    }

    /**
     * Manifest envelope and records placed in the private object store.
     *
     * @param schemaVersion evidence-envelope schema version
     * @param policyVersion authorizing policy version
     * @param organization  active organization that owns the artifact
     * @param exportedAt    UTC instant of export
     * @param recordCount   number of exported records
     * @param records       immutable exported records
     */
    public record Manifest(String schemaVersion, String policyVersion, String organization,
                           String exportedAt, int recordCount, List<ExportRecord> records) {

        /**
         * Freeze the record list so a caller cannot change a manifest.
         */
        public Manifest {
            records = List.copyOf(records);
        }

        /**
         * Return the immutable record list.
         *
         * @return exported records
         */
        @Override
        public List<ExportRecord> records() {
            return List.copyOf(records);
        }

        /**
         * Render the canonical UTF-8 JSON artifact.
         *
         * @return manifest bytes suitable for the object store
         */
        public byte[] bytes() {
            StringBuilder json = new StringBuilder();
            json.append("{\"schema_version\":\"").append(quote(schemaVersion)).append("\",");
            json.append("\"organization\":\"").append(quote(organization)).append("\",");
            json.append("\"export\":{\"manifest\":{");
            json.append("\"schema_version\":\"").append(quote(schemaVersion)).append("\",");
            json.append("\"policy_version\":\"").append(quote(policyVersion)).append("\",");
            json.append("\"organization\":\"").append(quote(organization)).append("\",");
            json.append("\"exported_at\":\"").append(quote(exportedAt)).append("\",");
            json.append("\"record_count\":").append(recordCount).append("},\"records\":[");
            for (int index = 0; index < records.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendRecord(json, records.get(index));
            }
            json.append("]}}");
            return json.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static void appendRecord(StringBuilder json, ExportRecord record) {
            json.append("{\"id\":\"").append(quote(record.id())).append("\",");
            json.append("\"checksum\":\"").append(quote(record.checksum())).append("\",");
            json.append("\"provenance\":{\"source_system\":\"")
                    .append(quote(record.provenance().sourceSystem())).append("\",");
            json.append("\"source_id\":\"").append(quote(record.provenance().sourceId())).append("\",");
            json.append("\"version\":").append(record.provenance().version()).append("},");
            json.append("\"documents\":[");
            List<ExportedDocument> documents = record.documents();
            for (int index = 0; index < documents.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                ExportedDocument document = documents.get(index);
                json.append("{\"id\":\"").append(quote(document.id())).append("\",");
                json.append("\"checksum\":\"").append(quote(document.checksum())).append("\",");
                json.append("\"authorization\":\"").append(quote(document.authorization())).append("\",");
                json.append("\"policy_version\":\"").append(quote(document.policyVersion())).append("\"}");
            }
            json.append("],\"audit\":[");
            List<AuditEntry> audit = record.audit();
            for (int index = 0; index < audit.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                AuditEntry entry = audit.get(index);
                json.append("{\"seq\":").append(entry.seq()).append(",\"action\":\"")
                        .append(quote(entry.action())).append("\",\"outcome\":\"")
                        .append(quote(entry.outcome())).append("\"}");
            }
            json.append("]}");
        }

        private static String quote(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    /**
     * Durable job metadata for one export artifact.
     *
     * @param id           opaque job identifier
     * @param status       {@code COMPLETE} or {@code EXPIRED}
     * @param objectId     tenant-scoped object alias holding the manifest
     * @param objectVersion immutable object version
     * @param checksum     checksum of the manifest bytes
     * @param createdAt    UTC creation instant
     * @param expiresAt    UTC expiry instant
     */
    public record ExportJob(String id, String status, String objectId, int objectVersion,
                            String checksum, String createdAt, String expiresAt) {

        /**
         * Report whether the durable row has already been transitioned to EXPIRED.
         *
         * <p>The lifecycle has only two states, so this also identifies the retry
         * case in which an earlier expiry marked the row but was interrupted before
         * removing the manifest object from the private store.</p>
         *
         * @return {@code true} when the job status is EXPIRED
         */
        public boolean isExpired() {
            return "EXPIRED".equals(status);
        }

        /**
         * Decide whether the artifact must no longer be disclosed.
         *
         * <p>An explicit expiry ends disclosure at once; the retention instant is
         * only a backstop for a job nobody expired. Expiry marks the durable row
         * before removing the object, so checking status here closes the window
         * in which the manifest is still present after the job was expired.</p>
         *
         * @param now instant to test
         * @return {@code true} when the job was expired or its retention has passed
         */
        public boolean expired(Instant now) {
            return isExpired() || !now.isBefore(Instant.parse(expiresAt));
        }
    }

    /**
     * Result of starting an export.
     *
     * @param jobId       opaque job identifier
     * @param checksum    checksum of the manifest bytes
     * @param recordCount number of exported records
     * @param expiresAt   UTC expiry instant
     */
    public record ExportResult(
            @JsonProperty("job_id") String jobId,
            String checksum,
            @JsonProperty("record_count") int recordCount,
            @JsonProperty("expires_at") String expiresAt) {
    }
}
