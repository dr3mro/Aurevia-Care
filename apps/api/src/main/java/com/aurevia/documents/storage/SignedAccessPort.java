package com.aurevia.documents.storage;

import java.net.URL;
import java.util.Optional;

/**
 * Short-lived signed download URLs for mediated document downloads (ARC-06).
 *
 * <p>A presigned URL is a bearer capability, so this port is deliberately
 * separate from {@link com.aurevia.documents.storage.ObjectStorePort}:
 * only the managed adapter implements it, and the caller must have completed
 * mediation and authorization in the domain above before asking for a URL. The
 * URL carries only the patient-opaque object key, never a patient identifier,
 * file name or payload, and is valid for a fixed short interval. No audit fact
 * is recorded here: the mediating domain service owns the disclosure record and
 * must not log the signed URL itself.</p>
 */
public interface SignedAccessPort {

    /**
     * Issue a short-lived signed GET URL for a promoted, verified object.
     *
     * @param key              tenant-scoped object key carrying the immutable version
     * @param expectedChecksum checksum the mediator verified
     * @return the signed URL, or empty when the object is absent, still in
     *         quarantine, at another version or checksummed differently
     */
    Optional<URL> signedDownloadUrl(ObjectKey key, String expectedChecksum);
}
