package com.aurevia.tenant.importing;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One source record in an onboarding dataset.
 *
 * <p>The field map is keyed by source-field name; the deterministic mapping
 * declared by the manifest decides which values become identity and chart data.
 * Lists and maps are frozen so a parsed record cannot be mutated after the dry
 * run has inspected it.</p>
 *
 * @param sourceId           non-blank identifier unique within the dataset
 * @param fields             source-field name to value
 * @param attachments        declared attachment evidence
 * @param historicalSignature preserved original signature evidence
 */
public record ImportRecord(
        @JsonProperty("source_id") String sourceId,
        Map<String, String> fields,
        List<ImportAttachment> attachments,
        @JsonProperty("historical_signature") HistoricalSignature historicalSignature) {

    /**
     * Freeze the mutable collections so callers cannot alter inspected data.
     *
     * @throws NullPointerException when fields or attachments is null
     */
    public ImportRecord {
        fields = Map.copyOf(fields);
        attachments = List.copyOf(attachments);
    }

    /**
     * Return the immutable source-field map.
     *
     * @return source-field name to value
     */
    @Override
    public Map<String, String> fields() {
        return Map.copyOf(fields);
    }

    /**
     * Return the immutable attachment list.
     *
     * @return declared attachment evidence
     */
    @Override
    public List<ImportAttachment> attachments() {
        return List.copyOf(attachments);
    }
}
