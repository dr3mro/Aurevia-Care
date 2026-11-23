package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentContract.Actor;
import com.aurevia.smartclinic.documents.DocumentContract.BindRequest;
import com.aurevia.smartclinic.documents.DocumentContract.EventType;
import com.aurevia.smartclinic.documents.DocumentContract.Language;
import com.aurevia.smartclinic.documents.DocumentContract.PdfBinding;
import com.aurevia.smartclinic.documents.DocumentContract.PdfJob;
import com.aurevia.smartclinic.documents.DocumentContract.RenderFinishRequest;
import com.aurevia.smartclinic.documents.DocumentContract.RenderRequest;
import com.aurevia.smartclinic.documents.DocumentContract.ReleaseRequest;
import com.aurevia.smartclinic.documents.DocumentContract.SourceRevision;
import com.aurevia.smartclinic.documents.DocumentGuard;
import com.aurevia.smartclinic.documents.DocumentModel.PdfSpec;
import com.aurevia.smartclinic.documents.InMemoryDocumentStore;
import com.aurevia.smartclinic.documents.PdfBindingService;
import com.aurevia.smartclinic.documents.PdfRenderer;

/**
 * CLN-07 clause 5 proof: immutable, versioned PDFs in English or Arabic with
 * embedded fonts, direction-aware layout, page numbers, provenance and a print
 * profile.
 *
 * <p>The first two tests read the rendered bytes directly: the renderer is a
 * pure function of its specification, so a byte-level assertion proves what a
 * paper printout or a viewer would receive without starting a Spring context.
 * The third test drives the immutable-revision binding service through its
 * domain entry points with a tenant-scoped in-memory store and a fixed clock.
 * Every identifier, subject and payload is synthetic; no production code is
 * changed by this proof.</p>
 */
class GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest {

    private static final PdfRenderer RENDERER = new PdfRenderer();

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "33333333-3333-3333-3333-333333333333";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String OTHER_CHECKSUM = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final Actor clinicianA = Actor.staff(ORG_A, "clinician-a", "clinician");
    private final Actor reviewerA = Actor.staff(ORG_A, "reviewer-a", "document_reviewer");
    private final Actor rendererA = Actor.staff(ORG_A, "renderer-a", "pdf_renderer");
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", "security_scanner");

    private InMemoryDocumentStore store;
    private PdfBindingService pdf;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new InMemoryDocumentStore();
        pdf = new PdfBindingService(store, new DocumentGuard(store, clock), clock);
    }

    /**
     * Positive proof for one English artifact: the structural markers named in
     * the clause are present in the bytes, and producing the same specification
     * twice is byte-identical while a new version or rotation owns new bytes and
     * a new PDF file identifier, so the artifact is immutable and versioned.
     */
    @Test
    void rendersImmutableVersionedEnglishPdfWithEmbeddedFontPageNumberProvenanceAndPrintProfile() {
        byte[] pdfBytes = RENDERER.render(spec("en", 3, 0, "Visit summary", List.of("Plan: rest")));
        String text = asAscii(pdfBytes);

        assertTrue(text.startsWith("%PDF-1.4"), "the rendered artifact is a PDF");
        assertTrue(text.contains("/Type /Font /Subtype /TrueType"), "a TrueType font dictionary is declared");
        assertTrue(text.contains("/FontFile2"), "a font program is embedded");
        assertTrue(text.contains("/PrintScaling /None"), "the print profile disables scaling");
        assertTrue(text.contains("/PrintArea /CropBox"), "the print profile declares its area");
        assertTrue(text.contains("/MediaBox [0 0 595.28 841.89]"), "the page is A4");
        assertTrue(text.contains("/Direction <" + utf16Hex("LTR") + ">"), "English is left-to-right");
        assertTrue(text.contains(utf16Hex("Page 1 / 1")), "the page number is written as UTF-16 text");
        assertTrue(text.contains("/Subject <" + utf16Hex("source:SYNTHETIC-LAB/REQ-1") + ">"),
                "the source system and identifier are stamped as provenance");
        assertTrue(text.contains("/Keywords <" + utf16Hex(
                "LTR v3 chart " + SUBJECT + " 2026-11-19T10:00:00Z") + ">"),
                "the immutable version and effective time are part of the artifact");

        assertArrayEquals(pdfBytes,
                RENDERER.render(spec("en", 3, 0, "Visit summary", List.of("Plan: rest"))),
                "the same specification always yields the same immutable bytes");
        byte[] nextVersion = RENDERER.render(spec("en", 4, 0, "Visit summary", List.of("Plan: rest")));
        byte[] rotated = RENDERER.render(spec("en", 3, 90, "Visit summary", List.of("Plan: rest")));
        assertFalse(Arrays.equals(pdfBytes, nextVersion), "each version is a distinct artifact");
        assertFalse(Arrays.equals(pdfBytes, rotated), "rotation is presentation metadata of the render");
        assertNotEquals(fileId(pdfBytes), fileId(nextVersion), "a new version owns a new file identifier");
        assertNotEquals(fileId(pdfBytes), fileId(rotated), "a rotated artifact owns a new file identifier");
    }

    /**
     * Positive proof for Arabic direction-aware layout: visual word order is
     * right-to-left while the logical text and the value/unit run survive, the
     * page stays a UTF-16 artifact, and English differs in direction and bytes.
     */
    @Test
    void rendersArabicRightToLeftWithLogicalTextPreserved() {
        String dose = "\u0627\u0644\u062c\u0631\u0639\u0629";
        String value = "\u0665\u0660\u0660"; // Arabic-Indic digits, a numeral LTR run
        String unit = "mg";
        String daily = "\u064a\u0648\u0645\u064a\u0627";
        String logicalLine = dose + " " + value + " " + unit + " " + daily;
        String visualLine = daily + " " + value + " " + unit + " " + dose;

        byte[] arabic = RENDERER.render(spec("ar", 1, 0, "\u0644\u062e\u0635\u0629", List.of(logicalLine)));
        String text = asAscii(arabic);
        String content = contentStream(arabic);
        String visible = content.replaceAll("(?m)^%LOGICAL.*$", "");

        assertTrue(text.contains("/Direction <" + utf16Hex("RTL") + ">"), "Arabic is right-to-left");
        assertTrue(text.contains(utf16Hex("Page 1 / 1")), "the page number is shared by both scripts");
        assertTrue(visible.contains("<" + utf16Hex(visualLine) + "> Tj"),
                "the visible run follows RTL visual word order");
        assertTrue(content.contains("%LOGICAL " + utf16Hex(logicalLine)),
                "the original logical text is preserved for provenance and copy");
        assertTrue(visible.indexOf(utf16Hex(value)) < visible.indexOf(utf16Hex(unit)),
                "a value stays immediately before its unit in an RTL line");
        assertTrue(visible.indexOf(utf16Hex(daily)) < visible.indexOf(utf16Hex(dose)),
                "Arabic words follow visual order while the LTR run stays intact");

        byte[] english = RENDERER.render(spec("en", 1, 0, "Summary", List.of("one two")));
        assertTrue(asAscii(english).contains("/Direction <" + utf16Hex("LTR") + ">"));
        assertFalse(Arrays.equals(arabic, english), "the two languages are distinct artifacts");
    }

    /**
     * Negative proof of immutability at the binding boundary: a stale render
     * cannot be completed, the finished render cannot be bound to another
     * revision, a foreign tenant or an unauthorized role cannot bind it, a second
     * completed render cannot rebind the same revision, and a corrected revision
     * cannot inherit the original binding.
     */
    @Test
    void refusesToBindPdfToAStaleVersionForeignTenantOrUnauthorizedRole() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));

        assertDenied(412, "STALE_VERSION", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", CHECKSUM), 2));

        pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 1);

        assertDenied(409, "REVISION_MISMATCH", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_A, 2, CHECKSUM), job.jobId())));
        assertDenied(409, "REVISION_MISMATCH", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_A, 1, OTHER_CHECKSUM), job.jobId())));
        assertDenied(403, "WRONG_TENANT", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_B, 1, CHECKSUM), job.jobId())));
        assertDenied(403, "FORBIDDEN", () -> pdf.bind(scannerA, new BindRequest(revision, job.jobId())));

        PdfBinding binding = pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));
        assertEquals(1, binding.revisionVersion());
        assertEquals(CHECKSUM, binding.contentChecksum());
        assertEquals(Language.EN, binding.language());
        assertTrue(store.events(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.PDF_BOUND),
                "a successful immutable binding is recorded in the audit trail");

        PdfJob second = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-ar", "ar"));
        pdf.finishRender(rendererA, second.jobId(), finish("pdf/object-ar", CHECKSUM), 1);
        assertDenied(409, "PDF_ALREADY_BOUND", () -> pdf.bind(reviewerA,
                new BindRequest(revision, second.jobId())));

        SourceRevision corrected = revision(ORG_A, 2, OTHER_CHECKSUM);
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(corrected, "policy-1", true)));
    }

    private static PdfSpec spec(String locale, int version, int rotation, String title, List<String> lines) {
        return new PdfSpec(ORG_A, "chart", SUBJECT, locale, version, rotation,
                title, lines, "SYNTHETIC-LAB", "REQ-1", "2026-11-19T10:00:00Z", null);
    }

    private static String utf16Hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE)).toUpperCase();
    }

    private static String asAscii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String contentStream(byte[] pdfBytes) {
        String text = asAscii(pdfBytes);
        int begin = text.indexOf("BT\n");
        int end = text.indexOf("ET\n", begin);
        return text.substring(begin, end);
    }

    private static String fileId(byte[] pdfBytes) {
        String text = asAscii(pdfBytes);
        int begin = text.indexOf("/ID [<");
        int end = text.indexOf(">", begin);
        return text.substring(begin, end);
    }

    private static SourceRevision revision(String org, int version, String checksum) {
        return new SourceRevision(org, "rev-1", version, checksum);
    }

    private static RenderFinishRequest finish(String objectKey, String checksum) {
        return new RenderFinishRequest(objectKey, 1, checksum, 128);
    }

    private static void assertDenied(int status, String code, Supplier<?> action) {
        ApiException failure = assertThrows(ApiException.class, action::get);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}
