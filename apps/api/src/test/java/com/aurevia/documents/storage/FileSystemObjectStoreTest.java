package com.aurevia.documents.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance and branch tests for the local object-storage adapter (ARC-06).
 */
class FileSystemObjectStoreTest {

    private static final String TENANT = "tenant-north";
    private static final String OBJECT = "export-42";

    @TempDir
    Path root;

    private ObjectKey key(int version) {
        return new ObjectKey(TENANT, OBJECT, version);
    }

    @Test
    void promotedObjectKeepsItsChecksum() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        byte[] content = "clean clinical payload".getBytes(StandardCharsets.UTF_8);
        ObjectKey key = key(1);

        StoredObject quarantined = store.putInQuarantine(key, content);
        assertEquals(ObjectState.QUARANTINED, quarantined.state());
        String checksum = quarantined.checksum();
        assertEquals(content.length, quarantined.sizeBytes());

        StoredObject promoted = store.promote(key, checksum);
        assertEquals(ObjectState.PROMOTED, promoted.state());
        assertEquals(checksum, promoted.checksum(),
                "promotion must not change the verified checksum");

        byte[] retrieved = store.getVerified(key, checksum).orElseThrow();
        assertArrayEquals(content, retrieved,
                "a promoted object is retrievable at its verified version and checksum");
    }

    @Test
    void changedObjectVersionIsRejected() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        byte[] content = "versioned payload".getBytes(StandardCharsets.UTF_8);
        String checksum = store.putInQuarantine(key(1), content).checksum();
        store.promote(key(1), checksum);

        assertTrue(store.getVerified(key(2), checksum).isEmpty(),
                "a different object version must never resolve to the stored object");
        assertThrows(IllegalStateException.class, () -> store.promote(key(2), checksum));
    }

    @Test
    void expiredExportDisappears() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        byte[] content = "export payload".getBytes(StandardCharsets.UTF_8);
        String checksum = store.putInQuarantine(key(1), content).checksum();
        store.promote(key(1), checksum);

        assertTrue(store.expire(key(1)), "expiring a promoted export succeeds");
        assertTrue(store.getVerified(key(1), checksum).isEmpty(),
                "an expired export disappears from verified reads");
    }

    @Test
    void quarantinedObjectIsNotRetrievable() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "unsafe".getBytes(StandardCharsets.UTF_8)).checksum();

        assertTrue(store.getVerified(key(1), checksum).isEmpty(),
                "a quarantined object stays private until promoted");
    }

    @Test
    void wrongChecksumIsRejected() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "payload".getBytes(StandardCharsets.UTF_8)).checksum();
        store.promote(key(1), checksum);

        assertTrue(store.getVerified(key(1), "b".repeat(64)).isEmpty(),
                "a mismatched checksum must not release the object");
    }

    @Test
    void tamperedBytesAreRejected() throws Exception {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "original payload".getBytes(StandardCharsets.UTF_8)).checksum();
        store.promote(key(1), checksum);
        Files.write(store.fileFor(key(1)), "tampered!".getBytes(StandardCharsets.UTF_8));

        assertTrue(store.getVerified(key(1), checksum).isEmpty(),
                "bytes changed on disk must fail verification even with the right label");
    }

    @Test
    void otherTenantCannotReadObject() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "tenant private".getBytes(StandardCharsets.UTF_8)).checksum();
        store.promote(key(1), checksum);
        ObjectKey otherTenant = new ObjectKey("tenant-south", OBJECT, 1);

        assertTrue(store.getVerified(otherTenant, checksum).isEmpty(),
                "key-based isolation keeps another tenant from reading the object");
        assertFalse(store.delete(otherTenant));
    }

    @Test
    void promoteRejectsNonQuarantinedObject() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "payload".getBytes(StandardCharsets.UTF_8)).checksum();
        store.promote(key(1), checksum);

        assertThrows(IllegalStateException.class, () -> store.promote(key(1), checksum),
                "a promoted object cannot be promoted again");
    }

    @Test
    void promoteRejectsChecksumMismatch() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        store.putInQuarantine(key(1), "payload".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> store.promote(key(1), "c".repeat(64)),
                "a clean scan for a different checksum must not promote the object");
    }

    @Test
    void deleteRemovesPromotedObject() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "payload".getBytes(StandardCharsets.UTF_8)).checksum();
        store.promote(key(1), checksum);

        assertTrue(store.delete(key(1)));
        assertTrue(store.getVerified(key(1), checksum).isEmpty());
    }

    @Test
    void deleteRemovesQuarantinedObject() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        store.putInQuarantine(key(1), "payload".getBytes(StandardCharsets.UTF_8));

        assertTrue(store.delete(key(1)),
                "delete removes an object in any state, including quarantine");
    }

    @Test
    void expireRejectsNonPromotedObject() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        String checksum = store.putInQuarantine(key(1),
                "payload".getBytes(StandardCharsets.UTF_8)).checksum();

        assertFalse(store.expire(key(1)),
                "only a promoted export may be expired; quarantine uses delete");
        assertTrue(store.getVerified(key(1), checksum).isEmpty());
    }

    @Test
    void removingMissingObjectReturnsFalse() {
        FileSystemObjectStore store = new FileSystemObjectStore(root);
        ObjectKey missing = key(9);

        assertFalse(store.delete(missing));
        assertFalse(store.expire(missing));
        assertFalse(Files.exists(store.fileFor(missing)));
    }

    @Test
    void ioFailureIsWrapped() throws Exception {
        Path fileAsRoot = Files.createFile(root.resolve("not-a-directory"));
        FileSystemObjectStore store = new FileSystemObjectStore(fileAsRoot);

        assertThrows(UncheckedIOException.class,
                () -> store.putInQuarantine(key(1), "payload".getBytes(StandardCharsets.UTF_8)),
                "a filesystem failure must fail closed as an unchecked storage error");
    }

    @Test
    void unsupportedDigestFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> FileSystemObjectStore.checksum("not-a-digest", "payload".getBytes(StandardCharsets.UTF_8)),
                "an unavailable digest must fail closed rather than return an empty label");
    }
}
