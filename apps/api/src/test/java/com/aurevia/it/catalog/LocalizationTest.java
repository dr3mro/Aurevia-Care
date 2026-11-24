package com.aurevia.it.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.catalog.dictionary.Approval;
import com.aurevia.catalog.dictionary.Direction;
import com.aurevia.catalog.dictionary.LocalizedExport;
import com.aurevia.catalog.dictionary.LocalizedName;
import com.aurevia.catalog.dictionary.LocalizedText;
import com.aurevia.catalog.dictionary.UiLocale;

/**
 * PRD-05 / UX-01: locale direction, localized value objects and governance
 * approval data. Every branch of each value object is asserted with synthetic
 * fixtures; no clinical or real patient data is used.
 */
class LocalizationTest {

    @Test
    void directionAndLocaleMapping() {
        assertEquals(Direction.LTR, UiLocale.EN.direction());
        assertEquals(Direction.RTL, UiLocale.AR.direction());
        assertEquals("ltr", Direction.LTR.attribute());
        assertEquals("rtl", Direction.RTL.attribute());
        assertEquals("en", UiLocale.EN.tag());
        assertEquals("ar", UiLocale.AR.tag());
        for (Direction direction : Direction.values()) {
            assertEquals(direction.name().toLowerCase(java.util.Locale.ROOT), direction.attribute());
        }
        assertNotNull(Direction.valueOf("LTR"));
    }

    @Test
    void localeTagParsing() {
        assertEquals(UiLocale.EN, UiLocale.fromTag("en"));
        assertEquals(UiLocale.EN, UiLocale.fromTag("EN-us"));
        assertEquals(UiLocale.AR, UiLocale.fromTag("ar-EG"));
        assertEquals(UiLocale.AR, UiLocale.fromTag("Ar"));
        assertThrows(IllegalArgumentException.class, () -> UiLocale.fromTag(null));
        assertThrows(IllegalArgumentException.class, () -> UiLocale.fromTag("  "));
        assertThrows(IllegalArgumentException.class, () -> UiLocale.fromTag("fr"));
    }

    @Test
    void localizedTextValidation() {
        LocalizedText text = new LocalizedText("قيمة", "ar");
        assertEquals("قيمة", text.text());
        assertEquals("ar", text.languageTag());
        assertThrows(IllegalArgumentException.class, () -> new LocalizedText(null, "en"));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedText("  ", "en"));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedText("x", null));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedText("x", " "));
    }

    @Test
    void localizedNameChoosesLocalFormFirstInArabicAndFallsBack() {
        LocalizedName both = new LocalizedName("John Smith", "جون سميث");
        assertEquals("جون سميث", both.display(UiLocale.AR));
        assertEquals("John Smith", both.display(UiLocale.EN));

        LocalizedName latinOnly = new LocalizedName("John Smith", null);
        assertEquals("John Smith", latinOnly.display(UiLocale.AR));
        assertEquals("John Smith", latinOnly.display(UiLocale.EN));

        LocalizedName localOnly = new LocalizedName(null, "جون سميث");
        assertEquals("جون سميث", localOnly.display(UiLocale.AR));
        assertEquals("جون سميث", localOnly.display(UiLocale.EN));

        assertTrue(both.hasBoth());
        assertFalse(latinOnly.hasBoth());
        assertFalse(localOnly.hasBoth());
        assertThrows(IllegalArgumentException.class, () -> new LocalizedName(null, null));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedName(" ", "  "));
    }

    @Test
    void localizedNameShowsBothFormsForIdentityContexts() {
        LocalizedName both = new LocalizedName("John Smith", "جون سميث");
        assertEquals("جون سميث / John Smith", both.displayBoth(UiLocale.AR));
        assertEquals("John Smith / جون سميث", both.displayBoth(UiLocale.EN));

        assertEquals("John Smith", new LocalizedName("John Smith", null).displayBoth(UiLocale.EN));
        assertEquals("جون سميث", new LocalizedName(null, "جون سميث").displayBoth(UiLocale.AR));
        assertEquals("Same", new LocalizedName("Same", "Same").displayBoth(UiLocale.AR));
    }

    @Test
    void localizedExportCarriesLanguageAndUnitVerbatim() {
        LocalizedExport withUnit = new LocalizedExport("ar", "قيمة", "mg");
        assertEquals("ar", withUnit.languageTag());
        assertEquals("قيمة", withUnit.text());
        assertEquals("mg", withUnit.unit());

        LocalizedExport noUnit = new LocalizedExport("en", "value", null);
        assertNull(noUnit.unit());

        assertThrows(IllegalArgumentException.class, () -> new LocalizedExport(null, "x", null));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedExport(" ", "x", null));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedExport("en", null, null));
        assertThrows(IllegalArgumentException.class, () -> new LocalizedExport("en", " ", null));
    }

    @Test
    void approvalRequiresNamedApproverAndInstant() {
        Approval approval = new Approval("reviewer-1", "2026-11-19T00:00:00Z", "governance note");
        assertEquals("reviewer-1", approval.approver());
        assertEquals("2026-11-19T00:00:00Z", approval.approvedAt());
        assertEquals("governance note", approval.note());
        assertNull(new Approval("r", "2026-11-19T00:00:00Z", null).note());

        assertThrows(IllegalArgumentException.class, () -> new Approval(null, "t", null));
        assertThrows(IllegalArgumentException.class, () -> new Approval(" ", "t", null));
        assertThrows(IllegalArgumentException.class, () -> new Approval("r", null, null));
        assertThrows(IllegalArgumentException.class, () -> new Approval("r", " ", null));
    }
}
