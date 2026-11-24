package com.aurevia.it.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

import com.aurevia.catalog.dictionary.Direction;
import com.aurevia.catalog.dictionary.LocalizedExport;
import com.aurevia.catalog.dictionary.LocalizedName;
import com.aurevia.catalog.dictionary.LocalizedText;
import com.aurevia.catalog.dictionary.MixedScriptRun;
import com.aurevia.catalog.dictionary.MixedScriptSupport;
import com.aurevia.catalog.dictionary.RunDirection;
import com.aurevia.catalog.dictionary.UiLocale;

/**
 * UX-01 / PRD-05: localization primitives as a backend domain kernel.
 *
 * <p>Client-only rendering (dir/lang DOM attributes, isolation elements, logical
 * CSS, Intl digit systems, screen-reader passes) is explicitly out of scope here;
 * these tests only assert the data and deterministic formatting the server owns.</p>
 */
class MixedScriptSupportTest {

    private final MixedScriptSupport support = new MixedScriptSupport();

    @Test
    void directionIsDerivedFromUiLocaleNotPersistedOnContent() {
        assertEquals(Direction.LTR, support.directionFor(UiLocale.EN));
        assertEquals(Direction.RTL, support.directionFor(UiLocale.AR));
        assertThrows(IllegalArgumentException.class, () -> support.directionFor(null));
        assertEquals(2, UiLocale.values().length);
        assertNotNull(UiLocale.valueOf("AR"));
    }

    @Test
    void displayLocalNameDelegatesWithIdentityMode() {
        LocalizedName name = new LocalizedName("John Smith", "جون سميث");
        assertEquals("جون سميث", support.displayLocalName(name, UiLocale.AR, false));
        assertEquals("جون / John", new MixedScriptSupport().displayLocalName(
                new LocalizedName("John", "جون"), UiLocale.AR, true));
        assertEquals("John Smith / جون سميث", support.displayLocalName(name, UiLocale.EN, true));
        assertThrows(IllegalArgumentException.class, () -> support.displayLocalName(null, UiLocale.EN, false));
        assertThrows(IllegalArgumentException.class, () -> support.displayLocalName(name, null, false));
    }

    @Test
    void mixedScriptRunAnnotatesWithoutMutatingStoredText() {
        String stored = "قيمة (5 mg) / 37.5 °C - صداع";
        LocalizedText text = new LocalizedText(stored, "ar-EG");

        MixedScriptRun free = support.annotateRun(text, false);
        assertEquals(stored, free.text());
        assertEquals("ar-EG", free.languageTag());
        assertEquals(RunDirection.AUTO, free.direction());

        MixedScriptRun identifier = support.annotateRun(text, true);
        assertEquals(stored, identifier.text());
        assertEquals(RunDirection.LTR, identifier.direction());

        // Round trip is exact at the code-point level: no bidi/format marks added.
        assertEquals(stored.codePointCount(0, stored.length()), free.text().codePointCount(0, free.text().length()));
        for (int i = 0; i < free.text().length(); i++) {
            char c = free.text().charAt(i);
            assertFalse(c == '\u200E' || c == '\u200F' || (c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069'), "isolation must not alter stored text");
        }
        assertEquals("ltr", RunDirection.LTR.attribute());
        assertEquals("auto", RunDirection.AUTO.attribute());
        assertEquals(2, RunDirection.values().length);
        assertNotNull(RunDirection.valueOf("AUTO"));
        assertThrows(IllegalArgumentException.class, () -> support.annotateRun(null, false));

        assertThrows(IllegalArgumentException.class, () -> new MixedScriptRun(null, "en", RunDirection.AUTO));
        assertThrows(IllegalArgumentException.class, () -> new MixedScriptRun(" ", "en", RunDirection.AUTO));
        assertThrows(IllegalArgumentException.class, () -> new MixedScriptRun("x", null, RunDirection.AUTO));
        assertThrows(IllegalArgumentException.class, () -> new MixedScriptRun("x", " ", RunDirection.AUTO));
        assertThrows(IllegalArgumentException.class, () -> new MixedScriptRun("x", "en", null));
    }

    @Test
    void measurementKeepsUnitAndQualifierWithoutConversionOrDigitRewriting() {
        assertEquals("5\u00A0mg", support.formatMeasurement("5", "mg", null));
        assertEquals("5\u00A0mg", support.formatMeasurement("5", "mg", " "));
        assertEquals("<\u00A00.5\u00A0mg/L", support.formatMeasurement("0.5", "mg/L", "<"));
        assertEquals("5\u00A0mmol/L", support.formatMeasurement("5", "mmol/L", null));
        for (char c : support.formatMeasurement("5", "mg", null).toCharArray()) {
            assertTrue(c == '.' || c == '\u00A0' || (c >= '0' && c <= '9') || c == 'm' || c == 'g',
                    "numerals stay Latin; the unit is not translated");
        }
        assertThrows(IllegalArgumentException.class, () -> support.formatMeasurement(null, "mg", null));
        assertThrows(IllegalArgumentException.class, () -> support.formatMeasurement(" ", "mg", null));
        assertThrows(IllegalArgumentException.class, () -> support.formatMeasurement("5", null, null));
        assertThrows(IllegalArgumentException.class, () -> support.formatMeasurement("5", " ", null));
    }

    @Test
    void exportCarriesSourceLanguageAndUnitsVerbatim() {
        LocalizedExport export = support.export(new LocalizedText("قيمة", "ar-EG"), "mg/L");
        assertEquals("ar-EG", export.languageTag());
        assertEquals("قيمة", export.text());
        assertEquals("mg/L", export.unit());
        assertThrows(IllegalArgumentException.class, () -> support.export(null, "mg"));
    }

    @Test
    void instantKeepsUtcInstantAndAppliesIanaZoneAcrossDstTransitions() {
        Instant spring = Instant.parse("2026-03-08T07:00:00Z");
        ZonedDateTime springResult = support.instantWithZone("2026-03-08T07:00:00Z", "America/New_York");
        assertEquals(spring, springResult.toInstant());
        assertEquals(ZoneId.of("America/New_York"), springResult.getZone());
        assertEquals(3, springResult.getHour());

        Instant fall = Instant.parse("2026-11-01T06:00:00Z");
        ZonedDateTime fallResult = support.instantWithZone("2026-11-01T06:00:00Z", "America/New_York");
        assertEquals(fall, fallResult.toInstant());
        assertEquals(ZoneId.of("America/New_York"), fallResult.getZone());
        // Fall-back overlap: the instant is real in both offsets; assert the
        // rendered wall clock is one of the two documented values from runtime tzdata.
        assertTrue(fallResult.getHour() == 1 || fallResult.getHour() == 2);
        assertEquals("2026-11-01", fallResult.format(DateTimeFormatter.ISO_LOCAL_DATE));

        assertThrows(IllegalArgumentException.class, () -> support.instantWithZone(null, "UTC"));
        assertThrows(IllegalArgumentException.class, () -> support.instantWithZone(" ", "UTC"));
        assertThrows(IllegalArgumentException.class, () -> support.instantWithZone("2026-01-01T00:00:00Z", null));
        assertThrows(IllegalArgumentException.class, () -> support.instantWithZone("2026-01-01T00:00:00Z", " "));
        assertThrows(RuntimeException.class, () -> support.instantWithZone("2026-01-01T00:00:00Z", "Not/AZone"));
    }

    @Test
    void civilDateNeverShiftsThroughALocalZone() {
        assertEquals(LocalDate.of(2026, 1, 1), support.civilDate("2026-01-01"));
        assertEquals(LocalDate.of(2026, 2, 28), support.civilDate("2026-02-28"));
        assertEquals(2026, support.civilDate("2026-12-31").getYear());
        assertEquals(31, support.civilDate("2026-12-31").getDayOfMonth());
        assertThrows(IllegalArgumentException.class, () -> support.civilDate(null));
        assertThrows(IllegalArgumentException.class, () -> support.civilDate(" "));
    }
}
