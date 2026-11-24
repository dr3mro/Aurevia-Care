package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.AllergyChecker;
import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyEntry;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Finding;
import com.aurevia.prescription.PrescriptionContract.FindingKind;
import com.aurevia.prescription.PrescriptionContract.Ingredient;

/** RX-01: verified ingredient allergy checks fail closed and never treat absent data as safe. */
class AllergyCheckerTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final String MARKET = "synthetic-market-v1";

    private static DrugEntry drug(String substance, String group) {
        return new DrugEntry("AMOX", 1, "Amoxicillin", "Amoxil", "tablet", "500", "mg",
                List.of(new Ingredient(substance, group, 1)), true, FROM, null, MARKET);
    }

    private static AllergyEntry verified(String substance, String group) {
        return new AllergyEntry(substance, group, 1, "verified", "synthetic-chart", FROM);
    }

    @Test
    void exactVerifiedSubstanceIsAConflict() {
        AllergyAssessment assessment = new AllergyAssessment(AllergyStatus.VERIFIED_LIST,
                List.of(verified("AMOX-SUB", "BETA_LACTAM")));
        AllergyCheck check = AllergyChecker.check(drug("AMOX-SUB", "BETA_LACTAM"), assessment);
        assertEquals(CheckOutcome.CONFLICT, check.outcome());
        assertEquals(AllergyStatus.VERIFIED_LIST, check.allergyStatus());
        assertTrue(check.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.EXACT_MATCH && "AMOX-SUB".equals(finding.substanceId())));
        assertEquals(MARKET, check.catalogVersion());
    }

    @Test
    void sameApprovedCrossReactivityGroupIsAWarning() {
        AllergyAssessment assessment = new AllergyAssessment(AllergyStatus.VERIFIED_LIST,
                List.of(verified("PENICILLIN-SUB", "BETA_LACTAM")));
        AllergyCheck check = AllergyChecker.check(drug("AMOX-SUB", "BETA_LACTAM"), assessment);
        assertEquals(CheckOutcome.WARNING, check.outcome());
        assertTrue(check.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.CROSS_REACTIVITY
                        && "BETA_LACTAM".equals(finding.group())));
    }

    @Test
    void unknownAndUnverifiedAssessmentsNeverReportNoConflict() {
        AllergyCheck unknown = AllergyChecker.check(drug("AMOX-SUB", "BETA_LACTAM"),
                new AllergyAssessment(AllergyStatus.UNKNOWN, List.of()));
        assertEquals(CheckOutcome.UNRESOLVED, unknown.outcome());
        assertEquals(AllergyStatus.UNKNOWN, unknown.allergyStatus());
        assertTrue(unknown.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.INCOMPLETE_ASSESSMENT));

        AllergyCheck unverified = AllergyChecker.check(drug("AMOX-SUB", "BETA_LACTAM"),
                new AllergyAssessment(AllergyStatus.UNVERIFIED,
                        List.of(new AllergyEntry("AMOX-SUB", "BETA_LACTAM", 1, "unverified",
                                "synthetic-chart", FROM))));
        assertEquals(CheckOutcome.UNRESOLVED, unverified.outcome());
        assertTrue(unverified.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.INCOMPLETE_ASSESSMENT));
        assertFalse(unverified.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.EXACT_MATCH),
                "an unverified assertion is surfaced but is not a verified contraindication");
    }

    @Test
    void verifiedNoKnownAllergyIsCleanButUnknownIngredientStaysVisible() {
        AllergyAssessment none = new AllergyAssessment(AllergyStatus.VERIFIED_NONE, List.of());
        assertEquals(CheckOutcome.NO_CONFLICT,
                AllergyChecker.check(drug("AMOX-SUB", "BETA_LACTAM"), none).outcome());

        DrugEntry unmapped = new DrugEntry("X", 1, "Unknown", "Unknown", "tablet", "1", "mg",
                List.of(new Ingredient(null, null, 0)), true, FROM, null, MARKET);
        AllergyCheck check = AllergyChecker.check(unmapped, none);
        assertEquals(CheckOutcome.UNRESOLVED, check.outcome());
        assertTrue(check.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.UNRESOLVED_INGREDIENT));
    }

    @Test
    void nameSimilarityIsNeverUsedAsAllergyEvidence() {
        AllergyAssessment assessment = new AllergyAssessment(AllergyStatus.VERIFIED_LIST,
                List.of(verified("AMOX-SUB", "BETA_LACTAM")));
        AllergyCheck check = AllergyChecker.check(drug("AMOXICILLIN-NAME-ONLY", "QUINOLONE"),
                assessment);
        assertEquals(CheckOutcome.NO_CONFLICT, check.outcome(),
                "only substance identity or an approved group may match");
    }

    @Test
    void exactMatchTakesPrecedenceOverGroupWarningAndPartialMatches() {
        AllergyAssessment assessment = new AllergyAssessment(AllergyStatus.VERIFIED_LIST, List.of(
                verified("PENICILLIN-SUB", "BETA_LACTAM"),
                verified("AMOX-SUB", "BETA_LACTAM")));
        AllergyCheck check = AllergyChecker.check(drug("AMOX-SUB", "BETA_LACTAM"), assessment);
        assertEquals(CheckOutcome.CONFLICT, check.outcome());
        assertTrue(check.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.EXACT_MATCH));
        assertTrue(check.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.CROSS_REACTIVITY),
                "every verified ingredient relation is retained, not only the first");
    }

    @Test
    void substanceOnlyIngredientsAndGrouplessAssertionsDoNotMatch() {
        DrugEntry substanceOnly = new DrugEntry("SUB-ONLY", 1, "Substance", "Substance", "tablet",
                "1", "mg", List.of(new Ingredient("SUB-ONLY-ID", null, 0)), true, FROM, null, MARKET);
        AllergyEntry groupless = new AllergyEntry("OTHER-ID", null, 0, "verified", "synthetic-chart", FROM);
        AllergyCheck check = AllergyChecker.check(substanceOnly,
                new AllergyAssessment(AllergyStatus.VERIFIED_LIST, List.of(groupless)));
        assertEquals(CheckOutcome.NO_CONFLICT, check.outcome());
        assertTrue(check.findings().isEmpty());
    }

    @Test
    void groupOnlyIngredientMatchesThroughTheApprovedGroup() {
        DrugEntry groupOnly = new DrugEntry("GROUP-ONLY", 1, "GroupOnly", "GroupOnly", "tablet",
                "1", "mg", List.of(new Ingredient(null, "BETA_LACTAM", 1)), true, FROM, null, MARKET);
        AllergyCheck check = AllergyChecker.check(groupOnly,
                new AllergyAssessment(AllergyStatus.VERIFIED_LIST,
                        List.of(verified("AMOX-SUB", "BETA_LACTAM"))));
        assertEquals(CheckOutcome.WARNING, check.outcome());
        assertTrue(check.findings().stream().anyMatch(finding ->
                finding.kind() == FindingKind.CROSS_REACTIVITY));
    }

    @Test
    void invalidAssessmentsAndFindingsAreRejected() {
        assertThrows(ApiException.class, () -> new AllergyAssessment(null, List.of()));
        assertThrows(ApiException.class, () -> new AllergyAssessment(AllergyStatus.VERIFIED_LIST, null));
        assertThrows(ApiException.class, () -> AllergyChecker.check(null,
                new AllergyAssessment(AllergyStatus.UNKNOWN, List.of())));
        assertThrows(ApiException.class, () -> AllergyChecker.check(drug("S", "G"), null));
        assertThrows(ApiException.class, () -> new AllergyAssessment(AllergyStatus.VERIFIED_LIST,
                List.of(new AllergyEntry(null, "G", 1, "verified", "synthetic", FROM))));
        assertThrows(ApiException.class, () -> new AllergyEntry("S", "G", 1, "verified", " ", FROM));
        assertThrows(ApiException.class, () -> new Finding(FindingKind.EXACT_MATCH, "S", "G", 1, " "));
        assertThrows(ApiException.class, () -> new Finding(null, "S", "G", 1, "note"));
        assertEquals("exact_match", FindingKind.EXACT_MATCH.wire());
        assertEquals(FindingKind.CROSS_REACTIVITY, FindingKind.from("cross_reactivity"));
        assertThrows(IllegalArgumentException.class, () -> FindingKind.from("unknown-kind"));
        assertEquals("no_conflict", CheckOutcome.NO_CONFLICT.wire());
        assertEquals(AllergyStatus.UNKNOWN, AllergyStatus.from("unknown"));
        assertThrows(IllegalArgumentException.class, () -> AllergyStatus.from("nope"));
    }
}
