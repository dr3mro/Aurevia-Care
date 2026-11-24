package com.aurevia.it.catalog.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.catalog.dictionary.Approval;
import com.aurevia.catalog.dictionary.CatalogVersion;
import com.aurevia.catalog.dictionary.ClinicalDictionaryService;
import com.aurevia.catalog.dictionary.DiagnosisCatalogService;
import com.aurevia.catalog.dictionary.DiagnosisCode;
import com.aurevia.catalog.dictionary.DictionaryField;
import com.aurevia.catalog.dictionary.Direction;
import com.aurevia.catalog.dictionary.LocalizedExport;
import com.aurevia.catalog.dictionary.LocalizedName;
import com.aurevia.catalog.dictionary.LocalizedText;
import com.aurevia.catalog.dictionary.MixedScriptRun;
import com.aurevia.catalog.dictionary.MixedScriptSupport;
import com.aurevia.catalog.dictionary.Requiredness;
import com.aurevia.catalog.dictionary.RunDirection;
import com.aurevia.catalog.dictionary.UiLocale;

/**
 * Acceptance-level backend evidence for CLN-03, CLN-04, PRD-05 and UX-01.
 *
 * <p>All fixtures are synthetic. These tests exercise the domain services through
 * their public entry points and cover the boundary validation, governance fold,
 * mapping preservation and bidirectional/time data contracts owned by this slice.
 * Client-only rendering, real authentication, database persistence and tenant
 * policy belong to other layers and are deliberately not asserted here.</p>
 */
class ClinicalCatalogAcceptanceTest {

    private static final String DICTIONARY_KEY = "intake_dictionary";
    private static final String CATALOG_KEY = "diagnosis_catalog";

    private final MixedScriptSupport mixedScript = new MixedScriptSupport();

    // --- Boundary validation (trust boundary: an entries list supplied by a caller) ---

    @Test
    void defineRejectsNullDictionaryEntryWithoutAllocatingAVersion() {
        ClinicalDictionaryService service = new ClinicalDictionaryService();
        List<DictionaryField> entries = new ArrayList<>();
        entries.add(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.define(DICTIONARY_KEY, entries, "author-1"));
        assertEquals("entry must not be null", error.getMessage());
        assertTrue(service.history(DICTIONARY_KEY).isEmpty());
        assertNull(service.effective(DICTIONARY_KEY));
    }

    @Test
    void defineRejectsNullCatalogEntryWithoutAllocatingAVersion() {
        DiagnosisCatalogService service = new DiagnosisCatalogService();
        List<DiagnosisCode> entries = new ArrayList<>();
        entries.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.define(CATALOG_KEY, entries, "author-1"));
        assertTrue(service.history(CATALOG_KEY).isEmpty());
        assertNull(service.effective(CATALOG_KEY));
    }

    // --- CLN-03 / CLN-04: market mapping, governance approval and preserved history ---

    @Test
    void marketMappingAddsAnEntryWithoutRewritingTheOriginalCodeOrText() {
        DiagnosisCode original = new DiagnosisCode("R51", new LocalizedText("الصداع", "ar"), null, null);
        DiagnosisCode mapped = original.withMapping("G43.9", new LocalizedText("Migraine", "en"));

        assertNotEquals(original, mapped);
        assertEquals("R51", mapped.code());
        assertEquals("الصداع", mapped.originalText().text());
        assertEquals("ar", mapped.originalText().languageTag());
        assertEquals("G43.9", mapped.mappedCode());
        assertEquals("en", mapped.mappedText().languageTag());
        assertTrue(mapped.isMapped());
        assertFalse(original.isMapped());
    }

    @Test
    void governanceApprovalRequiresADistinctNamedReviewerAndOnlyApprovedVersionsAreEffective() {
        ClinicalDictionaryService service = new ClinicalDictionaryService();
        List<DictionaryField> fields = List.of(new DictionaryField(
                "chief_concern", new LocalizedText("Chief concern", "en"),
                Requiredness.REQUIRED, List.of()));

        int version = service.define(DICTIONARY_KEY, fields, "author-1");
        assertEquals(1, version);
        assertNull(service.effective(DICTIONARY_KEY));

        assertThrows(IllegalArgumentException.class, () -> service.approve(
                DICTIONARY_KEY, version, new Approval("author-1", "2026-11-19T00:00:00Z", null)));

        CatalogVersion<DictionaryField> approved = service.approve(DICTIONARY_KEY, version,
                new Approval("reviewer-1", "2026-11-19T01:00:00Z", "clinical governance approval"));
        assertTrue(approved.isApproved());
        assertEquals(1, service.effective(DICTIONARY_KEY).version());

        int second = service.define(DICTIONARY_KEY, fields, "author-1");
        assertEquals(2, second);
        assertEquals(1, service.effective(DICTIONARY_KEY).version());
        service.approve(DICTIONARY_KEY, second,
                new Approval("reviewer-2", "2026-11-19T02:00:00Z", null));
        assertEquals(2, service.effective(DICTIONARY_KEY).version());

        List<CatalogVersion<DictionaryField>> history = service.history(DICTIONARY_KEY);
        assertEquals(2, history.size());
        assertEquals("Chief concern", history.get(0).entries().get(0).label().text());
    }

    @Test
    void searchReadsOnlyTheApprovedVersionAndNeverMutatesTheStoredSpellings() {
        DiagnosisCatalogService service = new DiagnosisCatalogService();
        DiagnosisCode original = new DiagnosisCode("R51", new LocalizedText(" Headache ", "en"), null, null);
        service.define(CATALOG_KEY, List.of(original), "author-1");
        assertTrue(service.search(CATALOG_KEY, "head").isEmpty());

        service.approve(CATALOG_KEY, 1,
                new Approval("reviewer-1", "2026-11-19T00:00:00Z", null));
        List<DiagnosisCode> found = service.search(CATALOG_KEY, "head");
        assertEquals(1, found.size());
        assertEquals("R51", found.get(0).code());
        assertEquals(" Headache ", found.get(0).originalText().text());
        assertTrue(service.search(CATALOG_KEY, " ").isEmpty());
        assertTrue(service.search(CATALOG_KEY, null).isEmpty());
    }

    // --- PRD-05 / UX-01: RTL direction, mixed-script text, measurement and time ---

    @Test
    void rtlDirectionAndLocalNameDisplayAreDerivedFromTheInterfaceLocale() {
        assertEquals(Direction.LTR, mixedScript.directionFor(UiLocale.EN));
        assertEquals(Direction.RTL, mixedScript.directionFor(UiLocale.AR));
        assertEquals("rtl", UiLocale.AR.direction().attribute());

        LocalizedName name = new LocalizedName("John Smith", "جون سميث");
        assertEquals("جون سميث", mixedScript.displayLocalName(name, UiLocale.AR, false));
        assertEquals("John Smith", mixedScript.displayLocalName(name, UiLocale.EN, false));
        assertTrue(name.displayBoth(UiLocale.AR).startsWith("جون سميث"));
        assertTrue(name.displayBoth(UiLocale.EN).startsWith("John Smith"));
    }

    @Test
    void mixedScriptRunIsIsolationMetadataAndKeepsStoredCodePointsExactly() {
        String stored = "قيمة (5 mg) / 37.5 °C - صداع";
        LocalizedText text = new LocalizedText(stored, "ar-EG");

        MixedScriptRun freeText = mixedScript.annotateRun(text, false);
        assertEquals(stored, freeText.text());
        assertEquals("ar-EG", freeText.languageTag());
        assertEquals(RunDirection.AUTO, freeText.direction());
        assertEquals("auto", freeText.direction().attribute());
        assertEquals(stored.length(), freeText.text().length());
        for (int i = 0; i < freeText.text().length(); i++) {
            char c = freeText.text().charAt(i);
            assertFalse((c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069'),
                    "isolation metadata must never alter the stored value");
        }

        assertEquals(RunDirection.LTR, mixedScript.annotateRun(text, true).direction());
        assertEquals("ltr", RunDirection.LTR.attribute());
    }

    @Test
    void measurementAndExportPreserveSourceUnitsAndLanguageWithoutConversion() {
        assertEquals("<\u00A00.5\u00A0mg/L",
                mixedScript.formatMeasurement("0.5", "mg/L", "<"));
        assertEquals("5\u00A0mg", mixedScript.formatMeasurement("5", "mg", " "));

        LocalizedExport export = mixedScript.export(new LocalizedText("قيمة", "ar-EG"), "mg/L");
        assertEquals("ar-EG", export.languageTag());
        assertEquals("قيمة", export.text());
        assertEquals("mg/L", export.unit());
    }

    @Test
    void utcInstantsKeepTheirIdentityAcrossDstAndCivilDatesNeverShift() {
        ZonedDateTime spring = mixedScript.instantWithZone("2026-03-08T07:00:00Z", "America/New_York");
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"), spring.toInstant());
        assertEquals(ZoneId.of("America/New_York"), spring.getZone());
        assertEquals(3, spring.getHour());

        ZonedDateTime fall = mixedScript.instantWithZone("2026-11-01T06:00:00Z", "America/New_York");
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), fall.toInstant());
        assertTrue(fall.getHour() == 1 || fall.getHour() == 2);

        assertEquals(LocalDate.of(2026, 1, 1), mixedScript.civilDate("2026-01-01"));
    }

    @Test
    void versionRejectsNullEntriesWithoutLeakingANullPointerException() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new CatalogVersion<DictionaryField>(1, null, "author-1", null));
        assertEquals("entries are required", error.getMessage());
    }

    @Test
    void approvingAVersionRequiresANamedApprovalAndFailsClosed() {
        CatalogVersion<DictionaryField> draft = new CatalogVersion<>(1,
                List.of(new DictionaryField("chief_concern",
                        new LocalizedText("Chief concern", "en"),
                        Requiredness.REQUIRED, List.of())),
                "author-1", null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> draft.approve(null));
        assertEquals("approval is required", error.getMessage());
        assertFalse(draft.isApproved());
    }

    @Test
    void malformedTimeAndZoneInputsAreRejectedWithDomainErrors() {
        IllegalArgumentException instantError = assertThrows(IllegalArgumentException.class,
                () -> mixedScript.instantWithZone("not-an-instant", "UTC"));
        assertTrue(instantError.getMessage().contains("UTC instant"));

        IllegalArgumentException zoneError = assertThrows(IllegalArgumentException.class,
                () -> mixedScript.instantWithZone("2026-01-01T00:00:00Z", "Not/AZone"));
        assertTrue(zoneError.getMessage().contains("IANA zone"));

        IllegalArgumentException dateError = assertThrows(IllegalArgumentException.class,
                () -> mixedScript.civilDate("2026-13-45"));
        assertTrue(dateError.getMessage().contains("civil date"));
    }
}
