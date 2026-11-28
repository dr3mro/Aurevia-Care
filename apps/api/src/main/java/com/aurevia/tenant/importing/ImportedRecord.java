package com.aurevia.tenant.importing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A source record prepared for import with its provenance attached.
 *
 * <p>Every imported record carries the source system and version plus the
 * original signature evidence. {@code imported} records where this projection
 * came from an import; {@code newAttestation} is always {@code false} because an
 * import preserves historical signatures instead of minting a new clinical
 * attestation.</p>
 *
 * @param sourceId            stable source identifier
 * @param sourceSystem        source system from the manifest provenance
 * @param sourceVersion       source version from the manifest provenance
 * @param historicalSignature preserved original signature evidence
 * @param imported            marks this record as an import projection
 * @param newAttestation      always {@code false} for an import
 */
public record ImportedRecord(
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("source_system") String sourceSystem,
        @JsonProperty("source_version") String sourceVersion,
        @JsonProperty("historical_signature") HistoricalSignature historicalSignature,
        boolean imported,
        @JsonProperty("new_attestation") boolean newAttestation) {
}
