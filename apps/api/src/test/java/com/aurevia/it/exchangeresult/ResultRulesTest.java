package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.MatchDecision;
import com.aurevia.exchange.result.ResultModel.MatchStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.Routing;
import com.aurevia.exchange.result.ResultRules;

/** S-G2-04 (XCH-04) pure identifier-matching and routing rules, covering every branch. */
class ResultRulesTest {

    private static final ResultIdentifiers EXPECTED = new ResultIdentifiers(
            "patient-1", "src-lab", "ACC-1", "dest-clinic", "order-1");
    private static final ExpectedOrder ORDER =
            new ExpectedOrder("order-1", EXPECTED, "owner-a", "queue-critical-care");

    @Test
    void absentOrderIsUnmatchedBeforeAnyImport() {
        MatchDecision decision = ResultRules.match(Optional.empty(), EXPECTED);
        assertEquals(MatchStatus.UNMATCHED, decision.status());
        assertTrue(decision.mismatches().isEmpty(), "an absent order has no field-level conflicts");
    }

    @Test
    void allFiveMatchingIdentifiersProduceAMatchedDecision() {
        MatchDecision decision = ResultRules.match(Optional.of(ORDER), EXPECTED);
        assertEquals(MatchStatus.MATCHED, decision.status());
        assertTrue(decision.mismatches().isEmpty());
    }

    @Test
    void eachMismatchedIdentifierIsReportedByName() {
        assertEquals(List.of("patientId"), mismatches("patient-2", "src-lab", "ACC-1", "dest-clinic", "order-1"));
        assertEquals(List.of("sourceId"), mismatches("patient-1", "src-other", "ACC-1", "dest-clinic", "order-1"));
        assertEquals(List.of("accessionId"), mismatches("patient-1", "src-lab", "ACC-2", "dest-clinic", "order-1"));
        assertEquals(List.of("destinationId"), mismatches("patient-1", "src-lab", "ACC-1", "dest-other", "order-1"));
        assertEquals(List.of("orderId"), mismatches("patient-1", "src-lab", "ACC-1", "dest-clinic", "order-2"));
    }

    @Test
    void multipleMismatchesAreAllReportedInStableOrder() {
        assertEquals(List.of("patientId", "sourceId", "orderId"),
                mismatches("patient-2", "src-other", "ACC-1", "dest-clinic", "order-2"));
    }

    @Test
    void nullAndAbsentValuesAreComparedDefensively() {
        MatchDecision nullActual = ResultRules.match(Optional.of(ORDER),
                new ResultIdentifiers(null, "src-lab", "ACC-1", "dest-clinic", "order-1"));
        assertEquals(MatchStatus.CONFLICT, nullActual.status());
        assertEquals(List.of("patientId"), nullActual.mismatches());

        MatchDecision nullExpected = ResultRules.match(
                Optional.of(new ExpectedOrder("order-1",
                        new ResultIdentifiers(null, "src-lab", "ACC-1", "dest-clinic", "order-1"),
                        "owner-a", "queue-critical-care")),
                new ResultIdentifiers(null, "src-lab", "ACC-1", "dest-clinic", "order-1"));
        assertEquals(MatchStatus.MATCHED, nullExpected.status(), "equal nulls must not be a conflict");
    }

    @Test
    void everyDispositionRoutesToTheNamedOwnerAndCoverageQueue() {
        Routing finalRoute = ResultRules.route(ORDER, ResultKind.FINAL);
        assertEquals("owner-a", finalRoute.owner());
        assertEquals("queue-critical-care", finalRoute.coverageQueue());
        assertFalse(finalRoute.critical());
        assertTrue(finalRoute.rationale().contains("order-1"));

        Routing corrected = ResultRules.route(ORDER, ResultKind.CORRECTED);
        assertEquals("owner-a", corrected.owner());
        assertEquals("queue-critical-care", corrected.coverageQueue());
        assertFalse(corrected.critical());
        assertTrue(corrected.rationale().contains("corrected"));

        Routing critical = ResultRules.route(ORDER, ResultKind.CRITICAL);
        assertEquals("owner-a", critical.owner());
        assertEquals("queue-critical-care", critical.coverageQueue());
        assertTrue(critical.critical());
        assertTrue(critical.rationale().contains("critical"));
    }

    @Test
    void kindPredicatesDistinguishCorrectionsAndCriticalResults() {
        assertTrue(ResultKind.CORRECTED.isCorrection());
        assertFalse(ResultKind.FINAL.isCorrection());
        assertTrue(ResultKind.CRITICAL.isCritical());
        assertFalse(ResultKind.CORRECTED.isCritical());
    }

    private static List<String> mismatches(String patient, String source, String accession,
                                           String destination, String order) {
        MatchDecision decision = ResultRules.match(Optional.of(ORDER),
                new ResultIdentifiers(patient, source, accession, destination, order));
        assertEquals(MatchStatus.CONFLICT, decision.status());
        return decision.mismatches();
    }
}
