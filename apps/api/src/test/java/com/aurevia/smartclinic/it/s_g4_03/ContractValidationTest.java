package com.aurevia.smartclinic.it.s_g4_03;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g4.s_g4_03.DrugCatalog;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.AllergyCheckRequest;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.AllergyEntry;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.CatalogHit;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.CheckOutcome;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.Draft;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.DraftView;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.DrugEntry;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.Finding;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.FindingKind;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.Ingredient;
import com.aurevia.smartclinic.g4.s_g4_03.PrescriptionContract.OverrideRecord;

/** RX-01: every record guard and enum parse branch is exercised, not merely the happy path. */
class ContractValidationTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private static DrugEntry entry() {
        return new DrugEntry("E", 1, "Generic", "Trade", "tablet", "500", "mg",
                List.of(new Ingredient("S", "G", 1)), true, FROM, null, "market");
    }

    @Test
    void checkOutcomeWiresAndParsesEveryValue() {
        assertEquals("no_conflict", CheckOutcome.NO_CONFLICT.wire());
        assertEquals("conflict", CheckOutcome.CONFLICT.wire());
        assertEquals("warning", CheckOutcome.WARNING.wire());
        assertEquals("unresolved", CheckOutcome.UNRESOLVED.wire());
        assertEquals(CheckOutcome.NO_CONFLICT, CheckOutcome.from("no_conflict"));
        assertEquals(CheckOutcome.CONFLICT, CheckOutcome.from("conflict"));
        assertEquals(CheckOutcome.WARNING, CheckOutcome.from("warning"));
        assertEquals(CheckOutcome.UNRESOLVED, CheckOutcome.from("unresolved"));
        assertThrows(IllegalArgumentException.class, () -> CheckOutcome.from("missing"));
    }

    @Test
    void ingredientGuardCoversNegativeVersionAndBlankGroup() {
        assertThrows(ApiException.class, () -> new Ingredient("S", "G", -1));
        assertThrows(ApiException.class, () -> new Ingredient("S", " ", 1));
        assertEquals("S", new Ingredient("S", null, 0).substanceId());
    }

    @Test
    void findingGuardCoversEveryIdentityCombination() {
        assertThrows(ApiException.class, () -> new Finding(FindingKind.EXACT_MATCH, " ", " ", 1, "note"));
        assertEquals("G", new Finding(FindingKind.EXACT_MATCH, " ", "G", 1, "note").group());
        assertEquals("S", new Finding(FindingKind.EXACT_MATCH, "S", null, 1, "note").substanceId());
        assertEquals(FindingKind.INCOMPLETE_ASSESSMENT,
                FindingKind.from("incomplete_assessment"));
        assertEquals(FindingKind.UNRESOLVED_INGREDIENT,
                FindingKind.from("unresolved_ingredient"));
        assertThrows(IllegalArgumentException.class, () -> FindingKind.from("other"));
    }

    @Test
    void allergyEntryGuardCoversVersionAndVerificationBranches() {
        assertThrows(ApiException.class, () -> new AllergyEntry("S", "G", -1, "verified", "src", FROM));
        assertThrows(ApiException.class, () -> new AllergyEntry("S", " ", 1, "verified", "src", FROM));
        assertThrows(ApiException.class, () -> new AllergyEntry("S", "G", 1, "invalid", "src", FROM));
        assertThrows(ApiException.class, () -> new AllergyEntry("S", "G", 1, " ", "src", FROM));
        assertThrows(ApiException.class, () -> new AllergyEntry("S", "G", 1, "verified", "src", null));
        assertEquals("unknown", new AllergyEntry("S", null, 0, "unknown", "src", FROM)
                .verificationState());
    }

    @Test
    void drugEntryGuardCoversEveryRequiredField() {
        assertThrows(ApiException.class, () -> new DrugEntry("", 1, "G", "T", "tablet", "1", "mg",
                List.of(), true, FROM, null, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("E", 1, "G", " ", "tablet", "1", "mg",
                List.of(), true, FROM, null, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("E", 1, "G", "T", " ", "1", "mg",
                List.of(), true, FROM, null, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("E", 1, "G", "T", "tablet", " ", "mg",
                List.of(), true, FROM, null, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("E", 1, "G", "T", "tablet", "1", " ",
                List.of(), true, FROM, null, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("E", 1, "G", "T", "tablet", "1", "mg",
                List.of(), true, null, null, "market"));
    }

    @Test
    void overrideAndHitGuardsCoverMissingIdentityAndAlternatives() {
        assertThrows(ApiException.class, () -> new OverrideRecord(" ", 2, "why", "author",
                "snapshot", null));
        assertThrows(ApiException.class, () -> new CatalogHit(entry(), null));
        assertThrows(ApiException.class, () -> new DraftView(null, null));
    }

    @Test
    void draftAndRequestRecordsAreConstructible() {
        Draft draft = new Draft("draft", 1, "patient", "draft", null, null, null, null, null,
                null, null, null, null, null, null, null, null, "clinician", FROM);
        DraftView view = new DraftView(draft, null);
        assertEquals("draft", view.draft().id());
        assertEquals(null, view.override());
        assertEquals("E", new AllergyCheckRequest("E").catalogEntryId());
    }

    @Test
    void effectiveEntryResolvesOnlyApprovedEffectiveKeys() {
        DrugCatalog catalog = new DrugCatalog(List.of(entry()));
        assertEquals(1, catalog.effectiveEntry("E", FROM).version());
        assertEquals(null, catalog.effectiveEntry("OTHER", FROM));
    }
}
