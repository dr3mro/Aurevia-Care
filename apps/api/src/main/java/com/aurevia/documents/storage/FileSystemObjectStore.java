package com.aurevia.documents.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local filesystem implementation of the object-storage port for development
 * and tests (ARC-06).
 *
 * <p>Payload bytes live under a tenant-scoped, patient-opaque key. A checksum
 * is derived from the bytes at write time and re-derived on every verified
 * read, so corrupted or tampered bytes are refused even when the caller holds
 * the right key and checksum label. All filesystem access goes through one
 * helper that fails closed as an unchecked storage error, so a partial I/O
 * failure can never be mistaken for a successful object write.</p>
 *
 * <p>The metadata index is process-scoped: this adapter is for development and
 * tests only and is not restarted with durable state. Production uses a
 * managed object-store adapter instead of this class.</p>
 */
public final class FileSystemObjectStore implements ObjectStorePort {

    /** Digest used for write-time capture and read-time integrity checks. */
    static final String DIGEST = "SHA-256";

    private final Path root;
    private final ConcurrentHashMap<ObjectKey, StoredEntry> entries = new ConcurrentHashMap<>();

    /**
     * Create an adapter rooted at the given directory.
     *
     * @param root directory under which private object files are written
     */
    public FileSystemObjectStore(Path root) {
        this.root = root;
    }

    @Override
    public StoredObject putInQuarantine(ObjectKey key, byte[] content) {
        Path directory = directoryFor(key);
        Path file = fileFor(key);
        String checksum = checksum(DIGEST, content);
        perform(() -> {
            Files.createDirectories(directory);
            Files.write(file, content);
            return null;
        });
        entries.put(key, new StoredEntry(checksum, content.length, ObjectState.QUARANTINED, file));
        return new StoredObject(key, checksum, content.length, ObjectState.QUARANTINED);
    }

    @Override
    public StoredObject promote(ObjectKey key, String scannedChecksum) {
        StoredEntry entry = entries.get(key);
        if (entry == null) {
            throw new IllegalStateException("object is unknown: " + key.value());
        }
        if (entry.state() != ObjectState.QUARANTINED) {
            throw new IllegalStateException("object is not in quarantine: " + key.value());
        }
        if (!entry.checksum().equals(scannedChecksum)) {
            throw new IllegalStateException("object checksum does not match the scan: " + key.value());
        }
        entries.put(key, entry.withState(ObjectState.PROMOTED));
        return new StoredObject(key, entry.checksum(), entry.sizeBytes(), ObjectState.PROMOTED);
    }

    @Override
    public Optional<byte[]> getVerified(ObjectKey key, String expectedChecksum) {
        StoredEntry entry = entries.get(key);
        if (entry == null || entry.state() != ObjectState.PROMOTED
                || !entry.checksum().equals(expectedChecksum)) {
            return Optional.empty();
        }
        byte[] content = perform(() -> Files.readAllBytes(entry.file()));
        if (!checksum(DIGEST, content).equals(expectedChecksum)) {
            return Optional.empty();
        }
        return Optional.of(content);
    }

    @Override
    public boolean delete(ObjectKey key) {
        return remove(key, false);
    }

    @Override
    public boolean expire(ObjectKey key) {
        return remove(key, true);
    }

    private boolean remove(ObjectKey key, boolean promotedOnly) {
        StoredEntry entry = entries.get(key);
        if (entry == null || (promotedOnly && entry.state() != ObjectState.PROMOTED)) {
            return false;
        }
        entries.remove(key);
        Path file = entry.file();
        perform(() -> {
            Files.deleteIfExists(file);
            return null;
        });
        return true;
    }

    private Path directoryFor(ObjectKey key) {
        return root.resolve("objects").resolve(key.tenantId()).resolve(key.objectId())
                .resolve("v" + key.version());
    }

    Path fileFor(ObjectKey key) {
        return directoryFor(key).resolve("object.bin");
    }

    /**
     * Digest bytes with the named algorithm. The algorithm is a parameter so
     * the failure path is deterministically testable without a platform change.
     *
     * @param algorithm digest algorithm name
     * @param content   bytes to digest
     * @return lowercase hexadecimal digest
     * @throws IllegalArgumentException when the platform lacks the algorithm
     */
    static String checksum(String algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("unsupported digest algorithm: " + algorithm, e);
        }
    }

    private static <T> T perform(IoOperation<T> operation) {
        try {
            return operation.run();
        } catch (IOException e) {
            throw new UncheckedIOException("object storage I/O failed", e);
        }
    }

    @FunctionalInterface
    /**
     * One checked filesystem operation with a value result, used to keep the
     * quarantine, promote and delete paths on a single error-handling route.
     *
     * @param <T> result type of the operation
     */
    private interface IoOperation<T> {
        T run() throws IOException;
    }

    private record StoredEntry(String checksum, long sizeBytes, ObjectState state, Path file) {

        private StoredEntry withState(ObjectState next) {
            return new StoredEntry(checksum, sizeBytes, next, file);
        }
    }
}
