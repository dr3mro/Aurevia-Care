package com.aurevia.it.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.documents.PdfRenderer;
import com.aurevia.documents.DocumentModel.PdfSpec;

/** S-G1-07 rendering: deterministic, versioned, localized, provenance-bearing PDFs. */
class PdfRendererTest {

    private static final PdfRenderer RENDERER = new PdfRenderer();

    private static PdfSpec spec(String locale, int version, int rotation, String title, List<String> lines) {
        return new PdfSpec("11111111-1111-1111-1111-111111111111", "chart",
                "22222222-2222-2222-2222-222222222222", locale, version, rotation,
                title, lines, "SYNTHETIC-LAB", "REQ-1", "2026-11-19T10:00:00Z", null);
    }

    private static PdfSpec specWithSource(String sourceAssociationId) {
        return new PdfSpec("11111111-1111-1111-1111-111111111111", "chart",
                "22222222-2222-2222-2222-222222222222", "en", 1, 0, "Title", List.of("line"),
                "SYNTHETIC-LAB", "REQ-1", "2026-11-19T10:00:00Z", sourceAssociationId);
    }

    private static String utf16Hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE)).toUpperCase();
    }

    private static String asAscii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String contentStream(byte[] pdf) {
        String text = asAscii(pdf);
        int begin = text.indexOf("BT\n");
        int end = text.indexOf("ET\n", begin);
        return text.substring(begin, end);
    }

    private static String fileId(byte[] pdf) {
        String text = asAscii(pdf);
        int begin = text.indexOf("/ID [<");
        int end = text.indexOf(">", begin);
        return text.substring(begin, end);
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
        assertTrue(text.contains("/Producer <" + utf16Hex("Aurevia PDF Renderer") + ">"));
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
    void arabicTitleUsesTheSameVisualReorderingAsBodyLines() {
        String first = "\u0627\u0644\u062a\u0642\u0631\u064a\u0631";
        String last = "\u0627\u0644\u0637\u0628\u064a";
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, first + " " + last, List.of("line")));
        String text = asAscii(pdf);
        int begin = text.indexOf("BT\n");
        int end = text.indexOf("ET\n", begin);
        String contentStream = text.substring(begin, end);
        assertTrue(contentStream.indexOf(utf16Hex(last)) < contentStream.indexOf(utf16Hex(first)),
                "an Arabic title must follow RTL visual order like body lines");
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
    void provenanceBindsTheValidatedSourceAssociation() {
        String sourceId = "33333333-3333-3333-3333-333333333333";
        String text = asAscii(RENDERER.render(specWithSource(sourceId)));
        assertTrue(text.contains("/Subject <" + utf16Hex("source:SYNTHETIC-LAB/REQ-1 association:" + sourceId) + ">"));
        assertFalse(asAscii(RENDERER.render(spec("en", 1, 0, "Title", List.of("line"))))
                .contains("association:"), "an unbound render keeps free-text provenance only");
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

    @Test
    void arabicLineKeepsNumericValueAndUnitAsOneLtrRun() {
        String dose = "\u0627\u0644\u062c\u0631\u0639\u0629";
        String value = "500";
        String unit = "mg";
        String daily = "\u064a\u0648\u0645\u064a\u0627";
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, "\u062a\u0642\u0631\u064a\u0631",
                List.of(dose + " " + value + " " + unit + " " + daily)));
        String content = contentStream(pdf);
        assertTrue(content.indexOf(utf16Hex(value)) < content.indexOf(utf16Hex(unit)),
                "a numeric value must stay immediately before its unit in an RTL line");
        assertTrue(content.indexOf(utf16Hex(daily)) < content.indexOf(utf16Hex(dose)),
                "Arabic words still follow RTL visual order");
    }

    @Test
    void arabicLineWithATrailingValueUnitRunKeepsTheRunIntact() {
        String daily = "\u064a\u0648\u0645\u064a\u0627";
        String value = "12.5";
        String unit = "mmol/L";
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, "\u062a\u0642\u0631\u064a\u0631",
                List.of(daily + " " + value + " " + unit)));
        String content = contentStream(pdf);
        assertTrue(content.indexOf(utf16Hex(value)) < content.indexOf(utf16Hex(unit)),
                "a trailing value/unit run must not be inverted");
        assertTrue(content.indexOf(utf16Hex(value)) < content.indexOf(utf16Hex(daily)),
                "the LTR run leads once its RTL group is reversed");
    }

    @Test
    void distinctRevisionsDoNotShareThePdfFileIdentifier() {
        String first = fileId(RENDERER.render(spec("en", 1, 0, "Same", List.of("line"))));
        String second = fileId(RENDERER.render(spec("en", 2, 0, "Same", List.of("line"))));
        String rotated = fileId(RENDERER.render(spec("en", 1, 90, "Same", List.of("line"))));
        assertNotEquals(first, second, "each immutable version owns its PDF file identifier");
        assertNotEquals(first, rotated, "a rotated artifact owns its PDF file identifier");
    }

    @Test
    void repeatedSpacesDoNotEmitAnEmptyTextOperator() {
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, "\u062a\u0642\u0631\u064a\u0631",
                List.of("\u0627\u0644\u062c\u0631\u0639\u0629  500")));
        assertFalse(contentStream(pdf).contains("<> Tj"),
                "collapsed spacing must not emit an empty text-showing operator");
    }

    @Test
    void englishBodyLineKeepsInterWordSpacesInItsVisibleTextOperator() {
        String line = "Plan: rest and review";
        byte[] pdf = RENDERER.render(spec("en", 1, 0, "Visit", List.of(line)));
        String visible = contentStream(pdf).replaceAll("(?m)^%LOGICAL.*$", "");
        assertTrue(visible.contains("<" + utf16Hex(line) + "> Tj"),
                "a body line must be one show-text run so inter-word spaces are rendered, "
                        + "not only preserved in the %LOGICAL provenance comment");
    }

    @Test
    void arabicBodyLineKeepsSpacesBetweenBidiRunsInItsVisibleTextOperator() {
        String dose = "\u0627\u0644\u062c\u0631\u0639\u0629";
        String value = "500";
        String unit = "mg";
        String daily = "\u064a\u0648\u0645\u064a\u0627";
        String line = dose + " " + value + " " + unit + " " + daily;
        String visualLine = daily + " " + value + " " + unit + " " + dose;
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, "\u062a\u0642\u0631\u064a\u0631", List.of(line)));
        String visible = contentStream(pdf).replaceAll("(?m)^%LOGICAL.*$", "");
        assertTrue(visible.contains("<" + utf16Hex(visualLine) + "> Tj"),
                "the visually ordered Arabic line must be one run so spaces between "
                        + "reordered bidi runs survive in the visible text");
    }

    @Test
    void arabicIndicDigitsStayInTheLtrValueUnitRun() {
        String dose = "\u0627\u0644\u062c\u0631\u0639\u0629";
        String value = "\u0665\u0660\u0660"; // Arabic-Indic digits ٥٠٠
        String unit = "mg";
        String daily = "\u064a\u0648\u0645\u064a\u0627";
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, "\u062a\u0642\u0631\u064a\u0631",
                List.of(dose + " " + value + " " + unit + " " + daily)));
        String visible = contentStream(pdf).replaceAll("(?m)^%LOGICAL.*$", "");
        assertTrue(visible.contains("<" + utf16Hex(daily + " " + value + " " + unit + " " + dose) + "> Tj"),
                "Arabic-Indic digits are numerals, not an Arabic RTL run: the value "
                        + "must stay immediately before its unit");
    }

    @Test
    void extendedArabicIndicDigitsStayInTheLtrValueUnitRun() {
        String daily = "\u064a\u0648\u0645\u064a\u0627";
        String value = "\u06f4\u06f0"; // Extended Arabic-Indic digits ۴۰
        String unit = "mg";
        byte[] pdf = RENDERER.render(spec("ar", 1, 0, "\u062a\u0642\u0631\u064a\u0631",
                List.of(value + " " + unit + " " + daily)));
        String visible = contentStream(pdf).replaceAll("(?m)^%LOGICAL.*$", "");
        assertTrue(visible.contains("<" + utf16Hex(daily + " " + value + " " + unit) + "> Tj"),
                "extended Arabic-Indic digits must behave like other numerals: "
                        + "value and unit stay one LTR run");
    }
}
