package com.aurevia.documents.storage;

import java.util.Optional;

/**
 * Port for private object storage behind the document slice (ARC-06).
 *
 * <p>The port owns the byte-level lifecycle only: an object is placed in
 * quarantine, promoted after a clean scan, read back at an exact verified
 * version and checksum, deleted during cleanup or expired when a promoted
 * export reaches the end of its retention. The adapter derives and stores the
 * checksum from the bytes themselves, so a verified read re-checks the bytes
 * rather than trusting a caller-supplied label. Keys are tenant-scoped and free
 * of patient data by construction. Authorization, scan verdicts, audit facts
 * and mediated-download policy stay in the domain services above this boundary.
 * Development and tests use the local filesystem adapter; production binds a
 * managed object-store adapter at startup.</p>
 */
public interface ObjectStorePort {

    /**
     * Place a payload in private quarantine at its immutable version.
     *
     * @param key     tenant-scoped object key
     * @param content payload bytes
     * @return the quarantined object metadata, including the derived checksum
     */
    StoredObject putInQuarantine(ObjectKey key, byte[] content);

    /**
     * Promote a quarantined object after a clean scan.
     *
     * @param key             tenant-scoped object key
     * @param scannedChecksum checksum the clean scan verified
     * @return the promoted object metadata
     * @throws IllegalStateException when the object is unknown, no longer in
     *                               quarantine or scanned under a different checksum
     */
    StoredObject promote(ObjectKey key, String scannedChecksum);

    /**
     * Read a promoted object only when its version and checksum verify against
     * the bytes on disk.
     *
     * @param key              tenant-scoped object key carrying the immutable version
     * @param expectedChecksum checksum the caller expects
     * @return the payload bytes, or empty when the object is absent, still
     *         quarantined, or its bytes no longer match the expected checksum
     */
    Optional<byte[]> getVerified(ObjectKey key, String expectedChecksum);

    /**
     * Delete an object in any state, for example a rejected upload.
     *
     * @param key tenant-scoped object key
     * @return {@code true} when an object was removed
     */
    boolean delete(ObjectKey key);

    /**
     * Expire a promoted export so it disappears from verified reads.
     *
     * @param key tenant-scoped object key
     * @return {@code true} when a promoted object was expired
     */
    boolean expire(ObjectKey key);
}
