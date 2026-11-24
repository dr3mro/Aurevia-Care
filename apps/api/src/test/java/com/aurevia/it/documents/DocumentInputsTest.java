package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentInputs;

/** S-G1-07 boundary validation: every rejection path stays fail-closed with 422. */
class DocumentInputsTest {

    private static void assert422(Runnable action) {
        assertEquals(422, assertThrows(ApiException.class, action::run).status());
    }

    @Test
    void uuidAcceptsOnlyRealIdentifiers() {
        String value = "11111111-1111-1111-1111-111111111111";
        assertEquals(value, DocumentInputs.uuid(value, "subjectId"));
        assert422(() -> DocumentInputs.uuid(null, "subjectId"));
        assert422(() -> DocumentInputs.uuid("not-a-uuid", "subjectId"));
    }

    @Test
    void subjectTypeIsBoundedToChartOrderResult() {
        assertEquals("chart", DocumentInputs.subjectType("chart"));
        assertEquals("order", DocumentInputs.subjectType("order"));
        assertEquals("result", DocumentInputs.subjectType("result"));
        assert422(() -> DocumentInputs.subjectType(null));
        assert422(() -> DocumentInputs.subjectType("encounter"));
    }

    @Test
    void mediaTypeIsBoundedToPdfJpegPng() {
        assertEquals("application/pdf", DocumentInputs.mediaType("application/pdf"));
        assertEquals("image/jpeg", DocumentInputs.mediaType("image/jpeg"));
        assertEquals("image/png", DocumentInputs.mediaType("image/png"));
        assert422(() -> DocumentInputs.mediaType(null));
        assert422(() -> DocumentInputs.mediaType("image/gif"));
    }

    @Test
    void localeIsBoundedToEnglishAndArabic() {
        assertEquals("en", DocumentInputs.locale("en"));
        assertEquals("ar", DocumentInputs.locale("ar"));
        assert422(() -> DocumentInputs.locale(null));
        assert422(() -> DocumentInputs.locale("fr"));
    }

    @Test
    void rotationIsPresentationMetadataWithAZeroDefault() {
        assertEquals(0, DocumentInputs.rotation(null));
        assertEquals(90, DocumentInputs.rotation(90));
        assertEquals(180, DocumentInputs.rotation(180));
        assertEquals(270, DocumentInputs.rotation(270));
        assert422(() -> DocumentInputs.rotation(45));
    }

    @Test
    void fileNameIsBoundedAndPathFree() {
        assertEquals("scan.pdf", DocumentInputs.fileName("scan.pdf"));
        assert422(() -> DocumentInputs.fileName(null));
        assert422(() -> DocumentInputs.fileName(" "));
        assert422(() -> DocumentInputs.fileName("../scan.pdf"));
        assert422(() -> DocumentInputs.fileName("a\\b.pdf"));
        assert422(() -> DocumentInputs.fileName("x".repeat(181)));
    }

    @Test
    void reasonIsNonBlankAndBounded() {
        assertEquals("wrong chart", DocumentInputs.reason("  wrong chart  "));
        assert422(() -> DocumentInputs.reason(null));
        assert422(() -> DocumentInputs.reason("   "));
        assert422(() -> DocumentInputs.reason("x".repeat(201)));
    }

    @Test
    void boundedTextHonoursItsLimits() {
        assertEquals("lab", DocumentInputs.bounded("lab", "SOURCE_SYSTEM", 1, 16));
        assert422(() -> DocumentInputs.bounded(null, "TITLE", 1, 8));
        assert422(() -> DocumentInputs.bounded("", "TITLE", 1, 8));
        assert422(() -> DocumentInputs.bounded("x", "TITLE", 2, 8));
        assert422(() -> DocumentInputs.bounded("too long", "TITLE", 1, 4));
    }

    @Test
    void linesAreNonBlankAndBounded() {
        List<String> lines = DocumentInputs.lines(List.of("first line", "second line"));
        assertEquals(2, lines.size());
        assertThrows(UnsupportedOperationException.class, () -> lines.add("third"));
        assert422(() -> DocumentInputs.lines(null));
        assert422(() -> DocumentInputs.lines(List.of()));
        assert422(() -> DocumentInputs.lines(List.of("  ")));
        assert422(() -> DocumentInputs.lines(java.util.Arrays.asList("ok", null)));
        assert422(() -> DocumentInputs.lines(List.of("x".repeat(501))));
        List<String> tooMany = java.util.stream.IntStream.range(0, 41)
                .mapToObj(i -> "line" + i).toList();
        assert422(() -> DocumentInputs.lines(tooMany));
    }

    @Test
    void decodeRejectsMissingInvalidEmptyAndOversizeContent() {
        byte[] original = "synthetic pdf bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertArrayEquals(original, DocumentInputs.decode(Base64.getEncoder().encodeToString(original)));
        assert422(() -> DocumentInputs.decode(null));
        assert422(() -> DocumentInputs.decode("  "));
        assert422(() -> DocumentInputs.decode("%%%not base64%%%"));
        assert422(() -> DocumentInputs.decode(""));
        byte[] oversize = new byte[DocumentInputs.MAX_ORIGINAL_BYTES + 1];
        assert422(() -> DocumentInputs.decode(Base64.getEncoder().encodeToString(oversize)));
    }

    @Test
    void newIdIsAlwaysAUuid() {
        String first = DocumentInputs.newId();
        String second = DocumentInputs.newId();
        assertNotNull(first);
        assertFalse(first.equals(second));
        assertEquals(first, UUID.fromString(first).toString());
    }
}
