package com.aurevia.it.exchangetrend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.trend.ComparabilityRule;
import com.aurevia.exchange.trend.ExclusionReason;
import com.aurevia.exchange.trend.ExcludedObservation;
import com.aurevia.exchange.trend.TrendObservation;
import com.aurevia.exchange.trend.Qualifier;
import com.aurevia.exchange.trend.TrendActionPolicy;
import com.aurevia.exchange.trend.TrendAnalyzer;
import com.aurevia.exchange.trend.TrendPoint;
import com.aurevia.exchange.trend.TrendView;

/**
 * S-G2-07 (XCH-07) fast domain tests: comparability classification, display
 * fields, ordering, qualifier parsing and input boundaries. No database and no
 * Spring context, so these run in the DB-less fast tier.
 */
class TrendAnalyzerTest {

    private static final String PATIENT = "11111111-1111-1111-1111-111111111111";
    private static final ComparabilityRule RULE =
            new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", true, 1);

    private final TrendAnalyzer analyzer = new TrendAnalyzer();

    private static TrendObservation observation(String id, String test, String group, String status,
                                           BigDecimal value, String unit, Qualifier qualifier,
                                           String qualitative, Instant at) {
        return new TrendObservation(id, PATIENT, test, "Synthetic " + test, group, status, value, unit,
                qualifier, new BigDecimal("4.0"), new BigDecimal("7.0"), unit, at,
                "within range", qualitative, "SYNTHETIC_LAB", "v1");
    }

    private static TrendObservation comparable(String id, String value, Instant at) {
        return observation(id, "HBA1C", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal(value),
                "mmol/mol", Qualifier.NONE, null, at);
    }

    @Test
    void trendsOnlyComparableObservationsInEffectiveTimeOrder() {
        TrendObservation later = comparable("o3", "8.2", Instant.parse("2026-11-10T00:00:00Z"));
        TrendObservation earliest = comparable("o1", "6.1", Instant.parse("2026-08-10T00:00:00Z"));
        TrendObservation middle = comparable("o2", "7.0", Instant.parse("2026-09-10T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(later, earliest, middle));

        assertEquals("HBA1C", view.testCode());
        assertEquals("mmol/mol", view.unit());
        assertEquals(1, view.ruleVersion());
        assertEquals(List.of("o1", "o2", "o3"),
                view.points().stream().map(TrendPoint::observationId).toList());
        assertTrue(view.excluded().isEmpty());
    }

    @Test
    void eachTrendPointShowsValueUnitRangeTimeInterpretationAndProvenance() {
        TrendPoint point = analyzer.analyze("HBA1C", RULE,
                List.of(comparable("o1", "8.2", Instant.parse("2026-09-10T00:00:00Z"))))
                .points().get(0);

        assertEquals(new BigDecimal("8.2"), point.value());
        assertEquals("mmol/mol", point.unit(), "the reported source unit is shown, not just a canonical label");
        assertEquals(new BigDecimal("4.0"), point.referenceLow());
        assertEquals(new BigDecimal("7.0"), point.referenceHigh());
        assertEquals("mmol/mol", point.referenceUnit());
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), point.effectiveAt());
        assertEquals("within range", point.interpretation());
        assertEquals("SYNTHETIC_LAB", point.sourceSystem());
        assertEquals("v1", point.sourceVersion());
        assertEquals("LAB-HPLC", point.comparabilityGroup());
    }

    @Test
    void qualifiedNumericResultsAreVisibleButNotTrended() {
        TrendObservation lessThan = observation("o-lt", "HBA1C", "LAB-HPLC", TrendObservation.FINAL,
                new BigDecimal("3.0"), "mmol/mol", Qualifier.LESS_THAN, null,
                Instant.parse("2026-09-01T00:00:00Z"));
        TrendObservation greaterThan = observation("o-gt", "HBA1C", "LAB-HPLC", TrendObservation.FINAL,
                new BigDecimal("9.0"), "mmol/mol", Qualifier.GREATER_THAN, null,
                Instant.parse("2026-09-02T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(lessThan, greaterThan));

        assertTrue(view.points().isEmpty(), "a qualified value must never be plotted at its literal value");
        assertEquals(List.of(ExclusionReason.QUALIFIED_NUMERIC, ExclusionReason.QUALIFIED_NUMERIC),
                view.excluded().stream().map(ExcludedObservation::reason).toList());
        assertEquals(Qualifier.LESS_THAN, view.excluded().get(0).observation().qualifier());
        assertEquals(new BigDecimal("3.0"), view.excluded().get(0).observation().value());
    }

    @Test
    void qualitativeResultsAreVisibleButNotTrended() {
        TrendObservation qualitative = observation("o-q", "HBA1C", "LAB-HPLC", TrendObservation.FINAL,
                null, null, Qualifier.NONE, "Negative", Instant.parse("2026-09-01T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(qualitative));

        assertTrue(view.points().isEmpty());
        assertEquals(ExclusionReason.QUALITATIVE, view.excluded().get(0).reason());
        assertEquals("Negative", view.excluded().get(0).observation().qualitativeValue());
    }

    @Test
    void incompatibleUnitsAreVisibleButNotTrended() {
        TrendObservation percent = observation("o-pct", "HBA1C", "LAB-HPLC", TrendObservation.FINAL,
                new BigDecimal("7.1"), "%", Qualifier.NONE, null,
                Instant.parse("2026-09-01T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(percent));

        assertTrue(view.points().isEmpty(), "no unit conversion is attempted");
        assertEquals(ExclusionReason.INCOMPATIBLE_UNIT, view.excluded().get(0).reason());
        assertEquals("%", view.excluded().get(0).observation().unit());
    }

    @Test
    void aDifferentTestIsVisibleButNotMixedIntoTheTrend() {
        TrendObservation glucose = observation("o-glu", "GLU", "LAB-HPLC", TrendObservation.FINAL,
                new BigDecimal("90"), "mg/dL", Qualifier.NONE, null,
                Instant.parse("2026-09-01T00:00:00Z"));
        TrendObservation hba1c = comparable("o-hb", "7.0", Instant.parse("2026-09-02T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(glucose, hba1c));

        assertEquals(List.of("o-hb"), view.points().stream().map(TrendPoint::observationId).toList());
        assertEquals(ExclusionReason.DIFFERENT_TEST, view.excluded().get(0).reason());
        assertEquals("GLU", view.excluded().get(0).observation().testCode());
    }

    @Test
    void aDifferentComparabilityGroupIsNeverSilentlyJoined() {
        TrendObservation otherMethod = observation("o-m", "HBA1C", "LAB-IMMUNO", TrendObservation.FINAL,
                new BigDecimal("7.0"), "mmol/mol", Qualifier.NONE, null,
                Instant.parse("2026-09-01T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(otherMethod));

        assertTrue(view.points().isEmpty());
        assertEquals(ExclusionReason.DIFFERENT_COMPARABILITY_GROUP, view.excluded().get(0).reason());
    }

    @Test
    void nonFinalRevisionsAreNeverTreatedAsCurrentValues() {
        for (String status : List.of("preliminary", "corrected", "entered_in_error")) {
            TrendObservation revision = observation("o-r", "HBA1C", "LAB-HPLC", status,
                    new BigDecimal("7.0"), "mmol/mol", Qualifier.NONE, null,
                    Instant.parse("2026-09-01T00:00:00Z"));
            TrendView view = analyzer.analyze("HBA1C", RULE, List.of(revision));
            assertTrue(view.points().isEmpty(), status + " must not be plotted as current");
            assertEquals(ExclusionReason.NON_FINAL_REVISION, view.excluded().get(0).reason());
        }
    }

    @Test
    void absentOrNonTrendableComparabilityKeepsEveryObservationVisible() {
        TrendObservation source = comparable("o1", "7.0", Instant.parse("2026-09-01T00:00:00Z"));

        TrendObservation second = comparable("o2", "6.0", Instant.parse("2026-10-01T00:00:00Z"));
        TrendView absent = analyzer.analyze("HBA1C", null, List.of(second, source));
        assertTrue(absent.points().isEmpty());
        assertEquals(ExclusionReason.COMPARABILITY_NOT_ESTABLISHED, absent.excluded().get(0).reason());
        assertEquals(0, absent.ruleVersion());

        TrendView off = analyzer.analyze("HBA1C",
                new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", false, 2), List.of(source));
        assertTrue(off.points().isEmpty());
        assertEquals(ExclusionReason.NOT_TRENDABLE, off.excluded().get(0).reason());
        assertEquals(2, off.ruleVersion());

        TrendView empty = analyzer.analyze("HBA1C", RULE, List.of());
        assertTrue(empty.points().isEmpty());
        assertTrue(empty.excluded().isEmpty());
    }

    @Test
    void exclusionsAreAlsoOrderedByEffectiveTimeThenId() {
        TrendObservation later = observation("o-z", "GLU", "LAB-HPLC", TrendObservation.FINAL,
                new BigDecimal("1"), "mg/dL", Qualifier.NONE, null,
                Instant.parse("2026-10-01T00:00:00Z"));
        TrendObservation first = observation("o-a", "GLU", "LAB-HPLC", TrendObservation.FINAL,
                new BigDecimal("2"), "mg/dL", Qualifier.NONE, null,
                Instant.parse("2026-09-01T00:00:00Z"));

        TrendView view = analyzer.analyze("HBA1C", RULE, List.of(later, first));

        assertEquals(List.of("o-a", "o-z"), view.excluded().stream()
                .map(ExcludedObservation::observation).map(TrendObservation::id).toList());
    }

    @Test
    void viewCopiesItsListsDefensively() {
        List<TrendPoint> points = new ArrayList<>();
        List<ExcludedObservation> excluded = new ArrayList<>();
        TrendView view = new TrendView("HBA1C", "mmol/mol", 1, points, excluded);
        points.add(null);
        excluded.add(null);

        assertTrue(view.points().isEmpty());
        assertTrue(view.excluded().isEmpty());
        assertNotSame(points, view.points());
    }

    @Test
    void qualifierParsingCoversNoneOperatorsAndRejectsUnknownValues() {
        assertEquals(Qualifier.NONE, Qualifier.from(null));
        assertEquals(Qualifier.NONE, Qualifier.from("  "));
        assertEquals(Qualifier.NONE, Qualifier.from("NONE"));
        assertEquals(Qualifier.LESS_THAN, Qualifier.from("<"));
        assertEquals(Qualifier.LESS_THAN, Qualifier.from("less_than"));
        assertEquals(Qualifier.GREATER_THAN, Qualifier.from(">"));
        assertEquals(Qualifier.GREATER_THAN, Qualifier.from("GREATER_THAN"));
        assertEquals("less_than", Qualifier.LESS_THAN.wire());
        assertEquals("greater_than", Qualifier.GREATER_THAN.wire());
        assertEquals("none", Qualifier.NONE.wire());
        assertEquals(422, assertThrows(ApiException.class, () -> Qualifier.from("approx")).status());
    }

    @Test
    void qualifierSerializesWithItsStableWireValue() throws Exception {
        ObjectMapper json = new ObjectMapper();
        assertEquals("\"less_than\"", json.writeValueAsString(Qualifier.LESS_THAN));
        assertEquals("\"greater_than\"", json.writeValueAsString(Qualifier.GREATER_THAN));
        assertEquals("\"none\"", json.writeValueAsString(Qualifier.NONE));
    }

    @Test
    void observationValidationRejectsMissingIdentityValueTimeAndBadRanges() {
        Instant at = Instant.parse("2026-09-01T00:00:00Z");
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, " ", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", " ", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", "bogus", new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, null, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, null, null,
                Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, null, "mmol/mol",
                Qualifier.LESS_THAN, null, null, null, at, null, "Positive", "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                " ", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, new BigDecimal("8"), new BigDecimal("4"),
                "mmol/mol", at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, new BigDecimal("4"), new BigDecimal("8"),
                "mg/dL", at, null, null, "src", null)).status());
    }

    @Test
    void observationValidationCoversNullAndPartialFieldBranches() {
        Instant at = Instant.parse("2026-09-01T00:00:00Z");

        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, null, "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", null, TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", null, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, null, null,
                Qualifier.NONE, null, null, null, at, null, "  ", "src", null)).status());
        // A qualified result does have a numeric value: only its display is qualified.
        assertNotNullValue(new TrendObservation("o", PATIENT, "HBA1C", "display", "LAB-HPLC",
                TrendObservation.FINAL, new BigDecimal("3"), "mmol/mol", Qualifier.LESS_THAN,
                null, null, null, at, null, null, "src", null));
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                null, Qualifier.NONE, null, null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, new BigDecimal("4"), null, null, at, null, null, "src", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new TrendObservation(
                "o", PATIENT, "HBA1C", "display", "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("1"),
                "mmol/mol", Qualifier.NONE, null, new BigDecimal("8"), "mg/dL", at,
                null, null, "src", null)).status());
    }

    private static void assertNotNullValue(TrendObservation observation) {
        assertTrue(observation.value() != null);
        assertTrue(observation.qualifier() == Qualifier.LESS_THAN);
    }

    @Test
    void comparabilityRuleValidationRejectsNullFields() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule(null, "mmol/mol", "LAB-HPLC", true, 1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule("HBA1C", null, "LAB-HPLC", true, 1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule("HBA1C", "mmol/mol", null, true, 1)).status());
    }

    @Test
    void comparabilityRuleValidationRejectsBlankTestGroupUnitAndBadVersion() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule(" ", "mmol/mol", "LAB-HPLC", true, 1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule("HBA1C", " ", "LAB-HPLC", true, 1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule("HBA1C", "mmol/mol", " ", true, 1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", true, 0)).status());
        assertEquals("HBA1C",
                new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", false, 3).testCode());
    }

    @Test
    void trendPolicySeparatesClinicalReadFromPolicyWrite() {
        TrendActionPolicy policy = new TrendActionPolicy();
        assertTrue(policy.allowsRead("clinician"));
        assertTrue(policy.allowsWrite("clinician"));
        assertTrue(policy.allowsPolicyWrite("tenant_admin"));
        assertEquals(false, policy.allowsRead("tenant_admin"));
        assertEquals(false, policy.allowsWrite("tenant_admin"));
        assertEquals(false, policy.allowsRead("reception"));
        assertEquals(false, policy.allowsWrite("reception"));
        assertEquals(false, policy.allowsRead("privacy_auditor"));
        assertEquals(false, policy.allowsPolicyWrite("clinician"));
        assertEquals(false, policy.allowsPolicyWrite("reception"));
    }
}
