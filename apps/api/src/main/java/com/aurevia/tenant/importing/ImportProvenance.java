package com.aurevia.tenant.importing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Provenance declaration for an onboarding dataset (OPS-08).
 *
 * <p>The import preserves where the dataset came from and keeps each record's
 * historical signature evidence. It must never create a new attestation:
 * {@code preserveHistoricalSignatures} is required and
 * {@code createsNewAttestation} must be {@code false}.</p>
 *
 * @param sourceSystem               non-blank source system or dataset name
 * @param sourceVersion              non-blank version of the source
 * @param preserveHistoricalSignatures must be {@code true}
 * @param createsNewAttestation      must be {@code false}
 */
public record ImportProvenance(
        @JsonProperty("source_system") String sourceSystem,
        @JsonProperty("source_version") String sourceVersion,
        @JsonProperty("preserve_historical_signatures") Boolean preserveHistoricalSignatures,
        @JsonProperty("creates_new_attestation") Boolean createsNewAttestation) {
}
