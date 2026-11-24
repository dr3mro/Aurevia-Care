package com.aurevia.documents.storage;

/**
 * Metadata projection of one stored object (ARC-06).
 *
 * <p>Carries the verified checksum and size alongside the current storage
 * state. No bytes, patient identifier, original file name or URL is exposed.</p>
 *
 * @param key       tenant-scoped object key
 * @param checksum  checksum captured at upload and preserved through promotion
 * @param sizeBytes stored payload size in bytes
 * @param state     current storage lifecycle state
 */
public record StoredObject(ObjectKey key, String checksum, long sizeBytes, ObjectState state) {
}
