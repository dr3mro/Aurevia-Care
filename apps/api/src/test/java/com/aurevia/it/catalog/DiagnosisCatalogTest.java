package com.aurevia.it.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.catalog.dictionary.Approval;
import com.aurevia.catalog.dictionary.CatalogVersion;
import com.aurevia.catalog.dictionary.DiagnosisCatalogService;
import com.aurevia.catalog.dictionary.DiagnosisCode;
import com.aurevia.catalog.dictionary.LocalizedText;

/**
 * CLN-03 / ADM-01 (data shape): versioned diagnosis and procedure catalogs.
 * Mapping adds a market code and display text but never replaces the original
 * code or original text (including their language tags).
 */
class DiagnosisCatalogTest {

    private static final String KEY = "diagnosis_catalog";

    private DiagnosisCode code(String code, String text, String lang) {
        return new DiagnosisCode(code, new LocalizedText(text, lang), null, null);
    }

    private List<DiagnosisCode> codes() {
        return List.of(code("R51", "Headache", "en"), code("R42", "Dizziness", "en"));
    }

    @Test
    void codeValidationAndMappingPreservesOriginals() {
        DiagnosisCode original = code("R51", "الصداع", "ar");
        assertFalse(original.isMapped());
        assertNull(original.mappedCode());
        assertNull(original.mappedText());

        DiagnosisCode mapped = original.withMapping("G43.9", new LocalizedText("Migraine", "en"));
        assertEquals("R51", mapped.code());
        assertEquals("الصداع", mapped.originalText().text());
        assertEquals("ar", mapped.originalText().languageTag());
        assertEquals("G43.9", mapped.mappedCode());
        assertEquals("Migraine", mapped.mappedText().text());
        assertEquals("en", mapped.mappedText().languageTag());
        assertTrue(mapped.isMapped());

        assertThrows(IllegalArgumentException.class, () -> new DiagnosisCode(null, original.originalText(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisCode(" ", original.originalText(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisCode("a b", original.originalText(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisCode("R51", null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DiagnosisCode("R51", original.originalText(), "G43", null));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosisCode("R51", original.originalText(),
                null, new LocalizedText("Migraine", "en")));
    }

    @Test
    void searchIsCaseInsensitiveAcrossCodeTextAndMappingWithoutMutatingOriginals() {
        DiagnosisCode original = code("R51", "Headache", "en");
        DiagnosisCode mapped = original.withMapping("G43.9", new LocalizedText("Migraine", "en"));
        assertTrue(mapped.matches("r51"));
        assertTrue(mapped.matches("head"));
        assertTrue(mapped.matches("G43"));
        assertTrue(mapped.matches("migraine"));
        assertFalse(mapped.matches("nothing"));
        assertFalse(mapped.matches(null));
        assertFalse(mapped.matches(" "));
        assertFalse(original.matches("G43"));
        assertEquals("R51", original.code());
        assertEquals("Headache", original.originalText().text());
    }

    @Test
    void defineApproveAndEffectiveFollowTheSameVersionRules() {
        DiagnosisCatalogService svc = new DiagnosisCatalogService();
        assertEquals(1, svc.define(KEY, codes(), "author"));
        assertEquals(2, svc.define(KEY, codes(), "author"));

        assertThrows(IllegalArgumentException.class, () -> svc.define(null, codes(), "a"));
        assertThrows(IllegalArgumentException.class, () -> svc.define("Bad", codes(), "a"));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, codes(), null));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, codes(), " "));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, null, "a"));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, List.of(), "a"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.define(KEY, List.of(code("R51", "a", "en"), code("R51", "b", "en")), "a"));

        Approval approval = new Approval("reviewer", "2026-11-19T00:00:00Z", null);
        svc.approve(KEY, 1, approval);
        assertEquals(1, svc.effective(KEY).version());
        svc.approve(KEY, 2, new Approval("reviewer-2", "t2", null));
        assertEquals(2, svc.effective(KEY).version());
        assertEquals(2, svc.history(KEY).size());
        assertThrows(UnsupportedOperationException.class, () -> svc.history(KEY).add(null));

        assertThrows(IllegalStateException.class, () -> svc.approve(KEY, 1, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve(KEY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> svc.approve(KEY, 0, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve("missing_key", 1, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve(KEY, 9, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve("Bad", 1, approval));
        assertNull(svc.effective("missing_key"));
        assertThrows(IllegalArgumentException.class, () -> svc.effective("Bad"));
        assertThrows(IllegalArgumentException.class, () -> svc.history(null));
    }

    @Test
    void searchOnlyReadsTheEffectiveApprovedVersion() {
        DiagnosisCatalogService svc = new DiagnosisCatalogService();
        assertTrue(svc.search("missing_key", "head").isEmpty());
        assertTrue(svc.search(KEY, "head").isEmpty());

        svc.define(KEY, codes(), "author");
        assertTrue(svc.search(KEY, "head").isEmpty());

        svc.approve(KEY, 1, new Approval("reviewer", "t", null));
        List<DiagnosisCode> found = svc.search(KEY, "head");
        assertEquals(1, found.size());
        assertEquals("R51", found.get(0).code());
        assertTrue(svc.search(KEY, "R42").stream().anyMatch(c -> "R42".equals(c.code())));
        assertTrue(svc.search(KEY, null).isEmpty());
        assertTrue(svc.search(KEY, " ").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> svc.search("Bad", "head"));
    }

    @Test
    void mappingSurvivesANewApprovedVersionInHistory() {
        DiagnosisCatalogService svc = new DiagnosisCatalogService();
        DiagnosisCode original = code("R51", "الصداع", "ar");
        svc.define(KEY, List.of(original), "author");
        svc.approve(KEY, 1, new Approval("reviewer", "t", null));

        DiagnosisCode mapped = original.withMapping("G43.9", new LocalizedText("Migraine", "en"));
        svc.define(KEY, List.of(mapped), "author");
        svc.approve(KEY, 2, new Approval("reviewer", "t", null));

        CatalogVersion<DiagnosisCode> oldVersion = svc.history(KEY).get(0);
        CatalogVersion<DiagnosisCode> newVersion = svc.history(KEY).get(1);
        assertFalse(oldVersion.entries().get(0).isMapped());
        assertTrue(newVersion.entries().get(0).isMapped());
        assertEquals("الصداع", newVersion.entries().get(0).originalText().text());
        assertEquals("ar", newVersion.entries().get(0).originalText().languageTag());
    }
}
