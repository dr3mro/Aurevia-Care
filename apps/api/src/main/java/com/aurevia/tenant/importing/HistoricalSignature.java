package com.aurevia.tenant.importing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Historical signature evidence carried by a source record.
 *
 * <p>This evidence is preserved verbatim on the imported record. An import never
 * mints a new clinical attestation: the original issuer, signing time and
 * checksum are copied, not replaced.</p>
 *
 * @param issuer   non-blank name of the original signer or issuing system
 * @param signedAt non-blank original signing instant
 * @param checksum lowercase 64-hex-digit SHA-256 checksum of the original signed content
 */
public record HistoricalSignature(
        String issuer,
        @JsonProperty("signed_at") String signedAt,
        String checksum) {
}
