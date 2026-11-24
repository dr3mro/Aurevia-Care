package com.aurevia.it.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.catalog.dictionary.Approval;
import com.aurevia.catalog.dictionary.CatalogVersion;
import com.aurevia.catalog.dictionary.ClinicalDictionaryService;
import com.aurevia.catalog.dictionary.DictionaryField;
import com.aurevia.catalog.dictionary.LocalizedText;
import com.aurevia.catalog.dictionary.Requiredness;

/**
 * CLN-03 / CLN-04 / ADM-01 (data shape): versioned clinical field dictionaries
 * with explicit requiredness, governance approval and preserved history.
 */
class ClinicalDictionaryTest {

    private static final String KEY = "intake_dictionary";

    private DictionaryField field(String code) {
        return new DictionaryField(code, new LocalizedText("Label " + code, "en"),
                Requiredness.REQUIRED, List.of());
    }

    private List<DictionaryField> fields() {
        return List.of(field("chief_concern"), field("history"));
    }

    @Test
    void requirednessIsExplicit() {
        assertEquals(3, Requiredness.values().length);
        assertNotNull(Requiredness.valueOf("REQUIRED"));
        assertEquals("REQUIRED", Requiredness.REQUIRED.name());
        assertEquals("OPTIONAL", Requiredness.OPTIONAL.name());
        assertEquals("UNKNOWN", Requiredness.UNKNOWN.name());
    }

    @Test
    void fieldValidationAndDefensiveCopy() {
        LocalizedText label = new LocalizedText("Chief concern", "en");
        List<String> allowed = new ArrayList<>(List.of("yes", "no"));
        DictionaryField f = new DictionaryField("chief_concern", label, Requiredness.REQUIRED, allowed);
        allowed.add("tamper");
        assertEquals(List.of("yes", "no"), f.allowedValues());
        assertThrows(UnsupportedOperationException.class, () -> f.allowedValues().add("x"));
        assertEquals("chief_concern", f.code());
        assertEquals(label, f.label());
        assertEquals(Requiredness.REQUIRED, f.requiredness());

        assertTrue(new DictionaryField("field_x", label, Requiredness.OPTIONAL, null).allowedValues().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> new DictionaryField(null, label, Requiredness.REQUIRED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DictionaryField("Bad Code", label, Requiredness.REQUIRED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DictionaryField("field_x", null, Requiredness.REQUIRED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DictionaryField("field_x", label, null, List.of()));
    }

    @Test
    void catalogVersionIsValidatedAndImmutable() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogVersion<String>(0, List.of(), "a", null));
        assertThrows(IllegalArgumentException.class, () -> new CatalogVersion<String>(1, List.of(), " ", null));
        assertThrows(IllegalArgumentException.class, () -> new CatalogVersion<String>(1, List.of(), null, null));
        CatalogVersion<String> version = new CatalogVersion<>(1, List.of("x"), "author", null);
        assertFalse(version.isApproved());
        assertNull(version.approval());
        assertEquals(1, version.version());
        assertEquals("author", version.author());
        assertEquals(List.of("x"), version.entries());
        assertThrows(UnsupportedOperationException.class, () -> version.entries().add("y"));

        CatalogVersion<String> approved = version.approve(new Approval("reviewer", "2026-11-19T00:00:00Z", null));
        assertTrue(approved.isApproved());
        assertEquals("reviewer", approved.approval().approver());
    }

    @Test
    void defineValidatesInputsAndAllocatesMonotonicVersions() {
        ClinicalDictionaryService svc = new ClinicalDictionaryService();
        assertEquals(1, svc.define(KEY, fields(), "author-1"));
        assertEquals(2, svc.define(KEY, fields(), "author-1"));

        assertThrows(IllegalArgumentException.class, () -> svc.define(null, fields(), "a"));
        assertThrows(IllegalArgumentException.class, () -> svc.define("Bad", fields(), "a"));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, fields(), null));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, fields(), " "));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, null, "a"));
        assertThrows(IllegalArgumentException.class, () -> svc.define(KEY, List.of(), "a"));
        assertThrows(IllegalArgumentException.class,
                () -> svc.define(KEY, List.of(field("dup"), field("dup")), "a"));
    }

    @Test
    void approveEnforcesNamedGovernanceDistinctFromAuthor() {
        ClinicalDictionaryService svc = new ClinicalDictionaryService();
        svc.define(KEY, fields(), "author-1");
        svc.define(KEY, fields(), "author-1");
        Approval approval = new Approval("reviewer", "2026-11-19T00:00:00Z", "governance approval");

        CatalogVersion<DictionaryField> approved = svc.approve(KEY, 1, approval);
        assertEquals(approval, approved.approval());
        assertEquals(1, approved.version());

        assertThrows(IllegalStateException.class, () -> svc.approve(KEY, 1, approval));
        assertThrows(IllegalArgumentException.class,
                () -> svc.approve(KEY, 2, new Approval("author-1", "t", null)));
        assertThrows(IllegalArgumentException.class, () -> svc.approve(KEY, 1, null));
        assertThrows(IllegalArgumentException.class, () -> svc.approve(KEY, 0, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve("missing_key", 1, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve(KEY, 9, approval));
        assertThrows(IllegalArgumentException.class, () -> svc.approve("Bad", 1, approval));
    }

    @Test
    void draftIsNeverEffectiveAndLatestApprovedVersionWins() {
        ClinicalDictionaryService svc = new ClinicalDictionaryService();
        assertNull(svc.effective(KEY));

        svc.define(KEY, fields(), "a");
        assertNull(svc.effective(KEY));

        svc.approve(KEY, 1, new Approval("reviewer-1", "t1", null));
        assertEquals(1, svc.effective(KEY).version());

        svc.define(KEY, fields(), "a");
        assertEquals(1, svc.effective(KEY).version());

        svc.approve(KEY, 2, new Approval("reviewer-2", "t2", null));
        assertEquals(2, svc.effective(KEY).version());

        List<CatalogVersion<DictionaryField>> history = svc.history(KEY);
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).version());
        assertEquals(2, history.get(1).version());
        assertThrows(UnsupportedOperationException.class, () -> history.add(null));
        assertTrue(svc.history("missing_key").isEmpty());

        assertThrows(IllegalArgumentException.class, () -> svc.effective("Bad"));
        assertThrows(IllegalArgumentException.class, () -> svc.history(null));
    }

    @Test
    void historyPreservesEarlierFieldMeaningsAfterANewVersion() {
        ClinicalDictionaryService svc = new ClinicalDictionaryService();
        svc.define(KEY, List.of(new DictionaryField("chief_concern",
                new LocalizedText("Chief concern", "en"), Requiredness.REQUIRED, List.of())), "a");
        svc.approve(KEY, 1, new Approval("reviewer-1", "t1", null));
        svc.define(KEY, List.of(new DictionaryField("chief_concern",
                new LocalizedText("الشكوى الرئيسية", "ar"), Requiredness.OPTIONAL, List.of())), "a");
        svc.approve(KEY, 2, new Approval("reviewer-2", "t2", null));

        List<CatalogVersion<DictionaryField>> history = svc.history(KEY);
        assertEquals("Chief concern", history.get(0).entries().get(0).label().text());
        assertEquals(Requiredness.REQUIRED, history.get(0).entries().get(0).requiredness());
        assertEquals("الشكوى الرئيسية", history.get(1).entries().get(0).label().text());
        assertEquals(Requiredness.OPTIONAL, history.get(1).entries().get(0).requiredness());
        assertEquals("Chief concern", svc.effective(KEY) == null ? "" : history.get(0).entries().get(0).label().text());
    }
}
