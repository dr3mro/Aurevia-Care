package com.aurevia.tenant.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** OPS-08: the shared lowercase-hex digest and blank check used by both import services. */
class ImportDigestsTest {

    private static final byte[] DATA =
            "synthetic-import-canonical-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void sha256HexMatchesThePlatformReferenceDigestInLowercase() throws NoSuchAlgorithmException {
        byte[] reference = MessageDigest.getInstance("SHA-256").digest(DATA);
        String expected = HexFormat.of().formatHex(reference);

        String actual = new ImportDigests().sha256Hex(DATA);

        assertEquals(expected, actual);
        assertEquals(64, actual.length());
        assertEquals(actual.toLowerCase(Locale.ROOT), actual);
    }

    @Test
    void sha256HexFailsClosedWhenTheAlgorithmIsUnavailable() {
        ImportDigests digests = new ImportDigests(() -> {
            throw new NoSuchAlgorithmException("missing");
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> digests.sha256Hex(DATA));
        assertEquals("SHA-256 is unavailable", thrown.getMessage());
    }

    @Test
    void isNonBlankRejectsNullAndWhitespaceAndAcceptsText() {
        assertFalse(ImportDigests.isNonBlank(null));
        assertFalse(ImportDigests.isNonBlank(""));
        assertFalse(ImportDigests.isNonBlank("  \t\n"));
        assertTrue(ImportDigests.isNonBlank("synthetic"));
    }
}
