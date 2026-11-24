package com.aurevia.tenant.importing;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Deterministic, read-only result of an OPS-08 onboarding dry run.
 *
 * <p>The five findings named by the specification are reported together. The
 * report is a pure value: computing it writes nothing to the chart. Sorted and
 * de-duplicated collections make the same input produce the same report.</p>
 *
 * @param counts             record, batch and finding counts
 * @param unsupportedFields  sorted source fields that are neither mapped nor ignored
 * @param duplicateRisk      sorted labels of every record sharing a deterministic identity
 * @param missingAttachments declared attachments that are missing or in quarantine
 * @param errors             manifest, mapping, provenance and record-level errors
 */
public record DryRunReport(
        Counts counts,
        @JsonProperty("unsupported_fields") List<String> unsupportedFields,
        @JsonProperty("duplicate_risk") List<String> duplicateRisk,
        @JsonProperty("missing_attachments") List<MissingAttachment> missingAttachments,
        List<String> errors) {

    /**
     * Freeze the finding collections so a report cannot be altered after return.
     *
     * @throws NullPointerException when a collection is null
     */
    public DryRunReport {
        unsupportedFields = List.copyOf(unsupportedFields);
        duplicateRisk = List.copyOf(duplicateRisk);
        missingAttachments = List.copyOf(missingAttachments);
        errors = List.copyOf(errors);
    }

    /**
     * Return the immutable unsupported-field list.
     *
     * @return sorted unsupported source-field names
     */
    @Override
    public List<String> unsupportedFields() {
        return List.copyOf(unsupportedFields);
    }

    /**
     * Return the immutable duplicate-risk list.
     *
     * @return sorted labels of duplicate identity members
     */
    @Override
    public List<String> duplicateRisk() {
        return List.copyOf(duplicateRisk);
    }

    /**
     * Return the immutable missing-attachment list.
     *
     * @return missing or quarantined attachments
     */
    @Override
    public List<MissingAttachment> missingAttachments() {
        return List.copyOf(missingAttachments);
    }

    /**
     * Return the immutable error list.
     *
     * @return named errors found during the dry run
     */
    @Override
    public List<String> errors() {
        return List.copyOf(errors);
    }

    /**
     * Counts surfaced by an OPS-08 dry run.
     *
     * @param totalRecords      number of source records inspected
     * @param batchCount        number of declared controlled batches
     * @param unsupportedFields number of unsupported source fields
     * @param duplicateRisk     number of records in duplicate identity groups
     * @param missingAttachments number of missing attachments
     * @param errors            number of errors
     */
    public record Counts(
            @JsonProperty("total_records") int totalRecords,
            @JsonProperty("batch_count") int batchCount,
            @JsonProperty("unsupported_fields") int unsupportedFields,
            @JsonProperty("duplicate_risk") int duplicateRisk,
            @JsonProperty("missing_attachments") int missingAttachments,
            int errors) {
    }

    /**
     * One declared attachment that was missing or still in quarantine.
     *
     * @param record source id of the owning record
     * @param key    declared object key
     */
    public record MissingAttachment(String record, String key) {
    }
}
