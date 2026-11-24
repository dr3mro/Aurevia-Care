package com.aurevia.printing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.printing.HandoutContract.Handout;
import com.aurevia.printing.HandoutContract.HandoutRequest;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutContract.RenderedHandout;

/**
 * CLN-07 / ARC-06 handout rendering through the printing service.
 *
 * <p>Every fixture is synthetic: invented organization identifiers, a made-up
 * patient and fixed instants. No database, partner or real patient record is
 * touched. The tests exercise the public service entry point rather than a
 * private helper, and assert the rendered bytes as well as the metadata.</p>
 */
class HandoutServiceTest {

    private static final String ORG = "org-alpha";
    private static final String OTHER_ORG = "org-beta";
    private static final String CHECKSUM_A = "a".repeat(64);
    private static final String CHECKSUM_B = "b".repeat(64);
    private static final Instant ISSUED = Instant.parse("2026-11-21T10:15:00Z");

    private LocalHandoutArchive archive;
    private HandoutService service;

    /** Build a service over an empty archive and a pinned clock. */
    @BeforeEach
    void setUp() {
        archive = new LocalHandoutArchive();
        service = new HandoutService(new HandoutRenderer(new PdfRenderer()), archive,
                Clock.fixed(ISSUED, ZoneOffset.UTC));
    }

    private static HandoutRequest request(String organizationId, String language, HandoutSource source) {
        return new HandoutRequest(Actor.staff(organizationId, "clinician-a", "clinician"), language, source);
    }

    private static HandoutSource source(String organizationId, HandoutKind kind, String state,
                                        boolean attested, String channel, int revision, String checksum) {
        return new HandoutSource(organizationId, kind, "src-1", revision, checksum, state, attested,
                channel, "PAT-SYN-1", "Synthetic Patient", "Dr Synthetic", "Synthetic Clinic",
                ISSUED, "SYNTHETIC-EMR", List.of("Synthetic handout line", "Dose 10 mg"));
    }

    private static HandoutSource valid(HandoutKind kind) {
        if (kind == HandoutKind.PRESCRIPTION) {
            return source(ORG, kind, "attested", true, "PRINTABLE_LOCAL", 1, CHECKSUM_A);
        }
        if (kind == HandoutKind.LAB_ORDER) {
            return source(ORG, kind, "accessioned", false, null, 1, CHECKSUM_A);
        }
        if (kind == HandoutKind.IMAGING_ORDER) {
            return source(ORG, kind, "scheduled", false, null, 1, CHECKSUM_A);
        }
        if (kind == HandoutKind.CHART_SUMMARY) {
            return source(ORG, kind, "signed", true, null, 1, CHECKSUM_A);
        }
        return source(ORG, kind, "final", true, null, 1, CHECKSUM_A);
    }

    private static String latin(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String hexText(String text) {
        return "<" + HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE))
                .toUpperCase(Locale.ROOT) + ">";
    }

    private static boolean containsText(String pdf, String text) {
        return pdf.contains(hexText(text).substring(1, hexText(text).length() - 1));
    }

    /** All five kinds render in English and Arabic as one-page A4 PDFs with the required furniture. */
    @Test
    void rendersEveryKindInEnglishAndArabic() {
        for (HandoutKind kind : HandoutKind.values()) {
            for (String language : List.of("en", "ar")) {
                RenderedHandout result = service.handout(request(ORG, language, valid(kind)));
                Handout handout = result.metadata();
                String label = kind + "/" + language;
                assertEquals(kind, handout.kind(), label);
                assertEquals(ORG, handout.organizationId(), label);
                assertEquals(1, handout.version(), label);
                assertEquals(1, handout.sourceRevision(), label);
                assertEquals(CHECKSUM_A, handout.sourceChecksum(), label);
                assertEquals(language, handout.language(), label);
                assertEquals(PdfRenderer.direction(language), handout.direction(), label);
                assertEquals(1, handout.pageCount(), label);
                assertEquals(result.content().length, handout.byteSize(), label);
                assertEquals(64, handout.sha256().length(), label);
                assertEquals(ISSUED, handout.producedAt(), label);
                assertEquals("clinician-a", handout.producedBy(), label);

                String pdf = latin(result.content());
                assertTrue(pdf.startsWith("%PDF-1.4"), label);
                assertTrue(pdf.contains("/MediaBox [0 0 595.28 841.89]"), label);
                assertTrue(pdf.contains("/FontFile2"), label);
                assertTrue(pdf.contains("/Direction " + hexText(handout.direction())), label);
                assertTrue(containsText(pdf, "Page 1 / 1"), label);
                assertTrue(containsText(pdf, kind.title(language)), label);
                assertTrue(containsText(pdf, "Synthetic Patient"), label);
                assertTrue(containsText(pdf, "PAT-SYN-1"), label);
                assertTrue(containsText(pdf, "Dr Synthetic"), label);
                assertTrue(containsText(pdf, "Synthetic Clinic"), label);
                assertTrue(containsText(pdf, "SYNTHETIC-EMR/src-1"), label);
            }
        }
    }

    /** A draft source of any kind is refused, even when it looks otherwise complete. */
    @Test
    void refusesDraftSources() {
        for (HandoutKind kind : HandoutKind.values()) {
            HandoutSource draft = source(ORG, kind, "draft", true, "PRINTABLE_LOCAL", 1, CHECKSUM_A);
            ApiException error = assertThrows(ApiException.class,
                    () -> service.handout(request(ORG, "en", draft)), kind.toString());
            assertEquals(409, error.status(), kind.toString());
            assertEquals("SOURCE_NOT_PLACED", error.code(), kind.toString());
        }
    }

    /** Chart summaries and reports need a signature, not merely a recognised state. */
    @Test
    void refusesUnsignedSignedContent() {
        for (HandoutKind kind : List.of(HandoutKind.CHART_SUMMARY, HandoutKind.SIGNED_REPORT)) {
            HandoutSource unsigned = source(ORG, kind, "signed", false, null, 1, CHECKSUM_A);
            ApiException error = assertThrows(ApiException.class,
                    () -> service.handout(request(ORG, "en", unsigned)), kind.toString());
            assertEquals(409, error.status(), kind.toString());
            assertEquals("SOURCE_NOT_SIGNED", error.code(), kind.toString());
        }
        HandoutSource unattested = source(ORG, HandoutKind.PRESCRIPTION, "attested", false,
                "PRINTABLE_LOCAL", 1, CHECKSUM_A);
        ApiException error = assertThrows(ApiException.class,
                () -> service.handout(request(ORG, "en", unattested)));
        assertEquals("SOURCE_NOT_SIGNED", error.code());
    }

    /** A prescription needs a channel on the approved BUILD-10 list. */
    @Test
    void refusesPrescriptionWithoutApprovedPrintChannel() {
        for (String channel : Arrays.asList(null, "UNKNOWN_CHANNEL")) {
            HandoutSource source = source(ORG, HandoutKind.PRESCRIPTION, "attested", true, channel,
                    1, CHECKSUM_A);
            ApiException error = assertThrows(ApiException.class,
                    () -> service.handout(request(ORG, "en", source)));
            assertEquals(409, error.status());
            assertEquals("PRINT_CHANNEL_NOT_APPROVED", error.code());
        }
        HandoutSource electronic = source(ORG, HandoutKind.PRESCRIPTION, "attested", true,
                "E_PRESCRIBE", 1, CHECKSUM_A);
        assertEquals(1, service.handout(request(ORG, "en", electronic)).metadata().version());
    }

    /** Another tenant's source is concealed behind a 403 before any render work. */
    @Test
    void refusesAnotherTenant() {
        HandoutSource foreign = source(OTHER_ORG, HandoutKind.PRESCRIPTION, "attested", true,
                "PRINTABLE_LOCAL", 1, CHECKSUM_A);
        ApiException error = assertThrows(ApiException.class,
                () -> service.handout(request(ORG, "en", foreign)));
        assertEquals(403, error.status());
        assertEquals("WRONG_TENANT", error.code());
    }

    /** A missing actor and an unsupported language fail closed at the boundary. */
    @Test
    void refusesInvalidRequests() {
        ApiException noActor = assertThrows(ApiException.class,
                () -> service.handout(new HandoutRequest(null, "en", valid(HandoutKind.PRESCRIPTION))));
        assertEquals(403, noActor.status());
        assertEquals("FORBIDDEN", noActor.code());

        ApiException badLanguage = assertThrows(ApiException.class,
                () -> service.handout(request(ORG, "fr", valid(HandoutKind.PRESCRIPTION))));
        assertEquals(422, badLanguage.status());
        assertEquals("INVALID_LANGUAGE", badLanguage.code());
    }

    /** Printing an unchanged source again returns the very same version and bytes. */
    @Test
    void reusesVersionForUnchangedSource() {
        HandoutSource source = valid(HandoutKind.PRESCRIPTION);
        RenderedHandout first = service.handout(request(ORG, "en", source));
        RenderedHandout again = service.handout(request(ORG, "en", source));
        assertEquals(1, first.metadata().version());
        assertEquals(first.metadata(), again.metadata());
        assertArrayEquals(first.content(), again.content());
        assertEquals(1, archive.find(ORG, HandoutKind.PRESCRIPTION, "src-1", 1, CHECKSUM_A, "en")
                .orElseThrow().metadata().version());
    }

    /** A changed source revision produces a new immutable version; its reprint is stable. */
    @Test
    void createsNewVersionForChangedSource() {
        HandoutSource firstSource = source(ORG, HandoutKind.LAB_ORDER, "accessioned", false, null,
                1, CHECKSUM_A);
        RenderedHandout first = service.handout(request(ORG, "en", firstSource));
        HandoutSource changed = source(ORG, HandoutKind.LAB_ORDER, "accessioned", false, null,
                2, CHECKSUM_B);
        RenderedHandout second = service.handout(request(ORG, "en", changed));
        assertEquals(1, first.metadata().version());
        assertEquals(2, second.metadata().version());
        assertEquals(2, second.metadata().sourceRevision());
        assertEquals(CHECKSUM_B, second.metadata().sourceChecksum());
        assertNotEquals(first.metadata().handoutId(), second.metadata().handoutId());
        assertFalse(Arrays.equals(first.content(), second.content()));

        RenderedHandout again = service.handout(request(ORG, "en", changed));
        assertEquals(2, again.metadata().version());
        assertArrayEquals(second.content(), again.content());
    }

    /** A content change at the same revision number also advances the immutable version. */
    @Test
    void createsNewVersionWhenContentChangesAtSameRevision() {
        HandoutSource original = source(ORG, HandoutKind.SIGNED_REPORT, "final", true, null,
                1, CHECKSUM_A);
        RenderedHandout first = service.handout(request(ORG, "en", original));
        HandoutSource changed = source(ORG, HandoutKind.SIGNED_REPORT, "final", true, null,
                1, CHECKSUM_B);
        RenderedHandout second = service.handout(request(ORG, "en", changed));
        assertEquals(1, first.metadata().version());
        assertEquals(2, second.metadata().version());
        assertEquals(1, second.metadata().sourceRevision());
        assertEquals(CHECKSUM_B, second.metadata().sourceChecksum());
        assertNotEquals(first.metadata().handoutId(), second.metadata().handoutId());
    }

    /** A second language for the same source revision stays at the same source-bound version. */
    @Test
    void keepsVersionForAnotherLanguageOfTheSameSource() {
        HandoutSource source = valid(HandoutKind.CHART_SUMMARY);
        RenderedHandout english = service.handout(request(ORG, "en", source));
        RenderedHandout arabic = service.handout(request(ORG, "ar", source));
        assertEquals(1, english.metadata().version());
        assertEquals(1, arabic.metadata().version());
        assertEquals("ar", arabic.metadata().language());
        assertEquals("RTL", arabic.metadata().direction());
        assertNotEquals(english.metadata().handoutId(), arabic.metadata().handoutId());
    }

    /** Callers cannot mutate rendered bytes through the returned handle. */
    @Test
    void renderedBytesAreDefensivelyCopied() {
        RenderedHandout result = service.handout(request(ORG, "en", valid(HandoutKind.SIGNED_REPORT)));
        byte[] exposed = result.content();
        byte original = exposed[0];
        exposed[0] = 0;
        assertNotEquals(0, result.content()[0]);
        assertEquals(original, result.content()[0]);

        Handout metadata = result.metadata();
        byte[] supplied = new byte[] { '%', 'P', 'D', 'F' };
        RenderedHandout constructed = new RenderedHandout(metadata, supplied);
        supplied[0] = 0;
        assertEquals((byte) '%', constructed.content()[0]);
    }

    /** The Spring wiring constructs a service that renders through the normal entry point. */
    @Test
    void configurationBuildsAUsableService() {
        HandoutService configured = new HandoutConfiguration()
                .handoutService(new PdfRenderer(), Clock.fixed(ISSUED, ZoneOffset.UTC));
        assertNotNull(configured);
        RenderedHandout result = configured.handout(request(ORG, "en", valid(HandoutKind.PRESCRIPTION)));
        assertEquals(1, result.metadata().version());
        assertTrue(result.content().length > 0);
    }
}
