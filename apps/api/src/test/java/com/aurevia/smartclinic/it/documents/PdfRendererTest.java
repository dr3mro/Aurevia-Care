package com.aurevia.smartclinic.it.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.documents.PdfRenderer;
import com.aurevia.smartclinic.documents.DocumentModel.PdfSpec;

/** S-G1-07 rendering: deterministic, versioned, localized, provenance-bearing PDFs. */
class PdfRendererTest {

    private static final PdfRenderer RENDERER = new PdfRenderer();

    private static PdfSpec spec(String locale, int version, int rotation, String title, List<String> lines) {
        return new PdfSpec("11111111-1111-1111-1111-111111111111", "chart",
                "22222222-2222-2222-2222-222222222222", locale, version, rotation,
                title, lines, "SYNTHETIC-LAB", "REQ-1", "2026-11-19T10:00:00Z");
    }

    private static String utf16Hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE)).toUpperCase();
    }

    private static String asAscii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    void producesAStructurallyCompletePdf() {
        byte[] pdf = RENDERER.render(spec("en", 1, 0, "Visit summary", List.of("Plan: rest")));
        String text = asAscii(pdf);
        assertTrue(text.startsWith("%PDF-1.4"));
        assertTrue(text.endsWith("%%EOF\n"));
        assertTrue(text.contains("/Type /Catalog"));
        assertTrue(text.contains("/Type /Pages"));
        assertTrue(text.contains("/Type /Page"));
        assertTrue(text.contains("/FontFile2"), "a font must be embedded");
        assertTrue(text.contains("/PrintScaling /None"), "a print profile must be declared");
        assertTrue(text.contains("/MediaBox"));
        assertTrue(text.contains("/Length "), "streams must declare their length");
        assertTrue(text.contains("startxref"));
        assertTrue(text.contains("xref"));
    }

    @Test
    void writesTitleLinesAndPageNumberAsUtf16Text() {
        byte[] pdf = RENDERER.render(spec("en", 1, 0, "Visit summary", List.of("Plan: rest")));
        String text = asAscii(pdf);
        assertTrue(text.contains(utf16Hex("Visit summary")));
        assertTrue(text.contains(utf16Hex("Plan: rest")));
        assertTrue(text.contains(utf16Hex("Page 1 / 1")));
    }

    @Test
    void embedsProvenanceInTheDocumentInformation() {
        byte[] pdf = RENDERER.render(spec("en", 3, 0, "Report", List.of("Finding")));
        String text = asAscii(pdf);
        assertTrue(text.contains("/Author <" + utf16Hex("org:11111111-1111-1111-1111-111111111111") + ">"));
        assertTrue(text.contains("/Subject <" + utf16Hex("source:SYNTHETIC-LAB/REQ-1") + ">"));
        assertTrue(text.contains("/Keywords <" + utf16Hex(
                "LTR v3 chart 22222222-2222-2222-2222-222222222222 2026-11-19T10:00:00Z") + ">"));
        assertTrue(text.contains("/Producer <" + utf16Hex("SmartClinic PDF Renderer") + ">"));
    }

    @Test
    void arabicIsRightToLeftWithVisuallyReorderedWords() {
        byte[] arabic = RENDERER.render(spec("ar", 1, 0, "\u0644\u062e\u0635\u0629",
                List.of("\u0627\u0644\u0645\u0631\u064a\u0636 \u064a\u062a\u0646\u0627\u0648\u0644 \u0627\u0644\u062f\u0648\u0627\u0621")));
        String text = asAscii(arabic);
        assertTrue(text.contains("/Direction <" + utf16Hex("RTL") + ">"));
        assertTrue(text.contains(utf16Hex("\u0644\u062e\u0635\u0629")), "original script must be preserved");
        assertTrue(text.indexOf(utf16Hex("\u0627\u0644\u062f\u0648\u0627\u0621"))
                < text.indexOf(utf16Hex("\u0627\u0644\u0645\u0631\u064a\u0636")),
                "RTL visual order reverses logical words");
    }

    @Test
    void englishIsLeftToRightAndDiffersFromArabic() {
        byte[] english = RENDERER.render(spec("en", 1, 0, "Summary", List.of("one two")));
        byte[] arabic = RENDERER.render(spec("ar", 1, 0, "Summary", List.of("one two")));
        assertTrue(asAscii(english).contains("/Direction <" + utf16Hex("LTR") + ">"));
        assertFalse(asAscii(arabic).contains("/Direction <" + utf16Hex("LTR") + ">"));
        assertFalse(java.util.Arrays.equals(english, arabic));
        assertTrue(asAscii(english).indexOf(utf16Hex("one"))
                < asAscii(english).indexOf(utf16Hex("two")), "LTR keeps logical word order");
    }

    @Test
    void identicalSpecsProduceIdenticalBytes() {
        assertArrayEquals(RENDERER.render(spec("en", 1, 0, "Same", List.of("line"))),
                RENDERER.render(spec("en", 1, 0, "Same", List.of("line"))));
    }

    @Test
    void versionAndRotationChangeTheRenderedBytes() {
        byte[] first = RENDERER.render(spec("en", 1, 0, "Same", List.of("line")));
        byte[] second = RENDERER.render(spec("en", 2, 0, "Same", List.of("line")));
        byte[] rotated = RENDERER.render(spec("en", 1, 90, "Same", List.of("line")));
        assertFalse(java.util.Arrays.equals(first, second), "each version is a distinct artifact");
        assertFalse(java.util.Arrays.equals(first, rotated), "rotation is presentation metadata of the render");
        assertTrue(asAscii(rotated).contains("/Rotate 90"));
    }

    @Test
    void preservesPunctuationAndArabicInUtf16InfoStrings() {
        String title = "A (B) \\ \u0644\u062e\u0635\u0629";
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, title, List.of("line")));
        assertTrue(asAscii(pdf).contains("/Title <" + utf16Hex(title) + ">"));
    }

    @Test
    void directionDefaultsToLeftToRightForUnknownLocales() {
        assertEquals("LTR", PdfRenderer.direction(null));
        assertEquals("LTR", PdfRenderer.direction("en"));
        assertEquals("RTL", PdfRenderer.direction("ar"));
    }
}
