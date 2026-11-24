package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * Boundary tests for the single typed upload-validation primitive shared by the
 * document-artifact and messaging-attachment upload paths (ARC-06, MSG-03).
 *
 * <p>Every value is synthetic. Size bounds, path-traversal safety and checksum
 * shape are asserted here once so the two upload callers cannot drift apart.</p>
 */
class UploadValidationTest {

    private static final String CHECKSUM = "a".repeat(64);
    private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";

    private static void assertDenied(String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(422, failure.status());
        assertEquals(code, failure.code());
    }

    @Test
    void acceptsCanonicalUuidAndRejectsMalformedAndMissingValues() {
        assertEquals(UUID, UploadValidation.uuid(UUID, "id"));
        assertDenied("INVALID_ID", () -> UploadValidation.uuid("not-a-uuid", "id"));
        assertDenied("INVALID_ID", () -> UploadValidation.uuid(null, "id"));
    }

    @Test
    void acceptsBoundedKeysAndRejectsBlankMalformedAndTooLongOnes() {
        assertEquals("key_1", UploadValidation.key(" key_1 ", "key"));
        assertDenied("INVALID_KEY", () -> UploadValidation.key(null, "key"));
        assertDenied("INVALID_KEY", () -> UploadValidation.key(" ", "key"));
        assertDenied("INVALID_KEY", () -> UploadValidation.key("a", "key"));
        assertDenied("INVALID_KEY", () -> UploadValidation.key("a".repeat(65), "key"));
        assertDenied("INVALID_KEY", () -> UploadValidation.key("has space", "key"));
    }

    @Test
    void acceptsPathFreeFileNamesIncludingArabicAndRejectsTraversal() {
        assertEquals("notes.pdf", UploadValidation.fileName(" notes.pdf "));
        assertEquals("تقرير.pdf", UploadValidation.fileName("تقرير.pdf"));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName(null));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName("notes..pdf"));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName("../notes.pdf"));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName("notes\\..\\pdf"));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName("notes\u0001.pdf"));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName(""));
        assertDenied("INVALID_FILE_NAME", () -> UploadValidation.fileName("a".repeat(201)));
    }

    @Test
    void acceptsBoundedHexMagicAndRejectsShortLongAndNonHexValues() {
        assertEquals("ffd8ff", UploadValidation.magicHex("FFD8FF"));
        assertDenied("INVALID_MAGIC_HEX", () -> UploadValidation.magicHex(null));
        assertDenied("INVALID_MAGIC_HEX", () -> UploadValidation.magicHex("a"));
        assertDenied("INVALID_MAGIC_HEX", () -> UploadValidation.magicHex("a".repeat(33)));
        assertDenied("INVALID_MAGIC_HEX", () -> UploadValidation.magicHex("zz"));
    }

    @Test
    void acceptsOnlyLowercaseSha256Checksums() {
        assertEquals(CHECKSUM, UploadValidation.checksum(CHECKSUM));
        assertDenied("INVALID_CHECKSUM", () -> UploadValidation.checksum(null));
        assertDenied("INVALID_CHECKSUM", () -> UploadValidation.checksum("a".repeat(63)));
        assertDenied("INVALID_CHECKSUM", () -> UploadValidation.checksum("A".repeat(64)));
    }

    @Test
    void acceptsSizesInsideOneByteToTenMebibytes() {
        assertEquals(1L, UploadValidation.size(1L));
        assertEquals(UploadValidation.MAX_SIZE_BYTES, UploadValidation.size(UploadValidation.MAX_SIZE_BYTES));
        assertDenied("INVALID_SIZE_BYTES", () -> UploadValidation.size(0L));
        assertDenied("INVALID_SIZE_BYTES", () -> UploadValidation.size(-1L));
        assertDenied("INVALID_SIZE_BYTES", () -> UploadValidation.size(UploadValidation.MAX_SIZE_BYTES + 1));
    }

    @Test
    void normalizesOptionalDetailAcrossNullBlankAndTooLongValues() {
        assertNull(UploadValidation.detail(null, 10));
        assertNull(UploadValidation.detail("   ", 10));
        assertEquals("detail", UploadValidation.detail(" detail ", 10));
        assertDenied("INVALID_DETAIL", () -> UploadValidation.detail("x".repeat(11), 10));
    }

    @Test
    void buildsDeterministicObjectKeyAndOpaqueIds() {
        assertEquals("quarantine/org-1/a/v3", UploadValidation.objectKey("org-1", "a", 3));
        String first = UploadValidation.newId();
        assertNotNull(first);
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertFalse(first.equals(UploadValidation.newId()));
    }

    @Test
    void acceptsOnlyTerminalVerdictWires() {
        assertEquals("clean", UploadValidation.terminalVerdict("clean", "verdict"));
        assertEquals("failed", UploadValidation.terminalVerdict("failed", "verdict"));
        assertDenied("INVALID_VERDICT", () -> UploadValidation.terminalVerdict(null, "verdict"));
        assertDenied("INVALID_VERDICT", () -> UploadValidation.terminalVerdict("pending", "verdict"));
        assertDenied("INVALID_VERDICT", () -> UploadValidation.terminalVerdict("CLEAN", "verdict"));
    }

    @Test
    void producesStableInvalidCodesForCamelCaseFields() {
        ApiException failure = UploadValidation.invalid("sizeBytes", "must be positive");
        assertEquals(422, failure.status());
        assertEquals("INVALID_SIZE_BYTES", failure.code());
        assertEquals("sizeBytes must be positive", failure.getMessage());
        assertEquals("INVALID_FILE_NAME", UploadValidation.invalid("fileName", "bad").code());
    }
}
