package com.aurevia.tenant.importing;

/**
 * Declared evidence for one attachment referenced by an onboarding record (OPS-08).
 *
 * <p>The import never reads the object bytes: it validates the declared key,
 * lowercase SHA-256 checksum, supported type and clean-scan verdict. A
 * {@code present} value of {@code false} means the object is missing or still in
 * quarantine and is reported by the dry run.</p>
 *
 * @param key     non-blank object key in the source system
 * @param sha256  lowercase 64-hex-digit SHA-256 checksum
 * @param type    one of {@code pdf}, {@code jpeg} or {@code png}
 * @param present clean-scan verdict; {@code true} when the object is available
 */
public record ImportAttachment(String key, String sha256, String type, Boolean present) {
}
