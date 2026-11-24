package com.aurevia.tenant.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

import org.junit.jupiter.api.Test;

/** OPS-07: the export checksum primitive is canonical and fails closed. */
class ExportDigestsTest {

    @Test
    void digestsBytesAsLowercaseSha256() {
        String digest = new ExportDigests().sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", digest);
    }

    @Test
    void unavailableAlgorithmFailsClosed() {
        ExportDigests digests = new ExportDigests(() -> {
            throw new NoSuchAlgorithmException("unavailable");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> digests.sha256Hex(new byte[] { 1, 2, 3 }));
        assertTrue(failure.getMessage().contains("SHA-256"));
    }
}
