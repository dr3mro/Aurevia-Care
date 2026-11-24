package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.DrugCatalog;
import com.aurevia.prescription.PrescriptionActions;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AlternativeKind;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.CatalogHit;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.CreateDraftRequest;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.OverrideRecord;
import com.aurevia.prescription.PrescriptionContract.SelectMedicationRequest;
import com.aurevia.prescription.PrescriptionInputs;

/** RX-01 validation, bounded action matrix and immutable contract values. */
class PrescriptionDomainTest {

    private static DrugEntry entry() {
        return new DrugEntry("E", 1, "Generic", "Trade", "tablet", "500", "mg",
                List.of(), true, java.time.Instant.parse("2026-01-01T00:00:00Z"), null, "market");
    }

    @Test
    void inputsValidateBoundaryFields() {
        String uuid = "11111111-1111-1111-1111-111111111111";
        assertEquals(uuid, PrescriptionInputs.uuid(uuid, "patientId"));
        assertThrows(ApiException.class, () -> PrescriptionInputs.uuid("not-a-uuid", "patientId"));
        assertThrows(ApiException.class, () -> PrescriptionInputs.uuid(null, "patientId"));

        assertEquals("tablet", PrescriptionInputs.text(" tablet ", "formulation", 32));
        assertThrows(ApiException.class, () -> PrescriptionInputs.text(" ", "formulation", 32));
        assertThrows(ApiException.class, () -> PrescriptionInputs.text(null, "formulation", 32));
        assertThrows(ApiException.class, () -> PrescriptionInputs.text("x".repeat(33), "formulation", 32));

        assertEquals(null, PrescriptionInputs.optionalText(null, "query", 64));
        assertEquals(null, PrescriptionInputs.optionalText("  ", "query", 64));
        assertEquals("amox", PrescriptionInputs.optionalText(" amox ", "query", 64));
        assertThrows(ApiException.class, () -> PrescriptionInputs.optionalText("x".repeat(65), "query", 64));

        assertEquals(2, PrescriptionInputs.refills(2));
        assertEquals(null, PrescriptionInputs.refills(null));
        assertThrows(ApiException.class, () -> PrescriptionInputs.refills(-1));
        assertThrows(ApiException.class, () -> PrescriptionInputs.refills(13));
        assertNotEquals("", PrescriptionInputs.newId());
    }

    @Test
    void actionMatrixGrantsWritesOnlyToClinicians() {
        PrescriptionActions actions = new PrescriptionActions();
        assertTrue(actions.allows(PrescriptionActions.CREATE_DRAFT, "clinician"));
        assertTrue(actions.allows(PrescriptionActions.SELECT_MEDICATION, "clinician"));
        assertTrue(actions.allows(PrescriptionActions.CHECK_ALLERGIES, "clinician"));
        assertTrue(actions.allows(PrescriptionActions.SEARCH_CATALOG, "clinician"));
        assertTrue(actions.allows(PrescriptionActions.READ_DRAFT, "clinician"));
        assertTrue(actions.allows(PrescriptionActions.READ_DRAFT, "tenant_admin"));
        assertTrue(actions.allows(PrescriptionActions.READ_DRAFT, "privacy_auditor"));
        assertTrue(actions.allows(PrescriptionActions.SEARCH_CATALOG, "tenant_admin"));
        assertTrue(actions.allows(PrescriptionActions.SEARCH_CATALOG, "privacy_auditor"));
        assertTrue(actions.allows(PrescriptionActions.CHECK_ALLERGIES, "tenant_admin"));
        assertTrue(actions.allows(PrescriptionActions.CHECK_ALLERGIES, "privacy_auditor"));
        assertFalse(actions.allows(PrescriptionActions.CREATE_DRAFT, "reception"));
        assertFalse(actions.allows(PrescriptionActions.SELECT_MEDICATION, "tenant_admin"));
        assertFalse(actions.allows(PrescriptionActions.READ_DRAFT, "reception"));
        assertFalse(actions.allows(PrescriptionActions.SEARCH_CATALOG, "reception"));
        assertFalse(actions.allows(PrescriptionActions.CHECK_ALLERGIES, "unknown-role"));
        assertFalse(actions.allows("UNKNOWN_ACTION", "clinician"));
        assertFalse(actions.allows(PrescriptionActions.READ_DRAFT, "unknown-role"));
    }

    @Test
    void contractValuesAreValidatedAndDefensivelyCopied() {
        assertEquals("suggestion", AlternativeKind.SUGGESTION.wire());
        assertEquals(AlternativeKind.SUGGESTION, AlternativeKind.from("suggestion"));
        assertThrows(IllegalArgumentException.class, () -> AlternativeKind.from("accepted"));

        Alternative alternative = new Alternative(AlternativeKind.SUGGESTION, entry(),
                "same generic name", "market-v1", true);
        assertTrue(alternative.requiresClinicianSelection());
        assertEquals("same generic name", alternative.reason());
        assertThrows(ApiException.class, () -> new Alternative(null, entry(), "why", "market", true));
        assertThrows(ApiException.class, () -> new Alternative(AlternativeKind.SUGGESTION, null,
                "why", "market", true));
        assertThrows(ApiException.class, () -> new Alternative(AlternativeKind.SUGGESTION, entry(),
                " ", "market", true));
        assertThrows(ApiException.class, () -> new Alternative(AlternativeKind.SUGGESTION, entry(),
                "why", " ", true));

        List<Alternative> mutable = new ArrayList<>();
        mutable.add(alternative);
        CatalogHit hit = new CatalogHit(entry(), mutable);
        mutable.clear();
        assertEquals(1, hit.alternatives().size(), "caller mutation cannot rewrite a catalog hit");
        assertThrows(UnsupportedOperationException.class, () -> hit.alternatives().clear());
        assertThrows(ApiException.class, () -> new CatalogHit(null, List.of()));

        OverrideRecord override = new OverrideRecord("draft", 2, "documented clinical override",
                "clinician-1", "catalog=E:1;allergy=verified_list;findings=exact_match", null);
        assertThrows(ApiException.class, () -> new AllergyCheck(null, AllergyStatus.UNKNOWN,
                List.of(), "market"));
        assertThrows(ApiException.class, () -> new AllergyCheck(CheckOutcome.NO_CONFLICT, null,
                List.of(), "market"));
        assertThrows(ApiException.class, () -> new AllergyCheck(CheckOutcome.NO_CONFLICT,
                AllergyStatus.UNKNOWN, null, "market"));
        assertThrows(ApiException.class, () -> new AllergyCheck(CheckOutcome.NO_CONFLICT,
                AllergyStatus.UNKNOWN, List.of(), " "));
        assertThrows(ApiException.class, () -> new Draft("draft", 0, "patient", "draft",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                "clinician", null));
        assertThrows(ApiException.class, () -> new Draft("", 1, "patient", "draft",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                "clinician", null));
        assertThrows(ApiException.class, () -> new Draft("draft", 1, " ", "draft",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                "clinician", null));
        assertThrows(ApiException.class, () -> new Draft("draft", 1, "patient", " ",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                "clinician", null));

        assertEquals("clinician-1", override.authorSubject());
        assertThrows(ApiException.class, () -> new OverrideRecord("draft", 2, " ",
                "clinician-1", "snapshot", null));
        assertThrows(ApiException.class, () -> new OverrideRecord("draft", 2, "why",
                "clinician-1", " ", null));
        assertThrows(ApiException.class, () -> new OverrideRecord("draft", 0, "why",
                "clinician-1", "snapshot", null));

        CreateDraftRequest create = new CreateDraftRequest("11111111-1111-1111-1111-111111111111",
                null, "synthetic-clinic");
        assertEquals("synthetic-clinic", create.destination());
        SelectMedicationRequest select = new SelectMedicationRequest("E", "500", "mg", "oral",
                "once daily", "10", "tablet", 2, "synthetic instruction", "reviewed with clinician");
        assertEquals(2, select.refills());
        assertEquals("reviewed with clinician", select.overrideReason());
    }
}
