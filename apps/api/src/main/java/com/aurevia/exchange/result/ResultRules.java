package com.aurevia.exchange.result;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.MatchDecision;
import com.aurevia.exchange.result.ResultModel.MatchStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultModel.Routing;

/**
 * Pure XCH-04 decision rules: five-facet identifier matching and result routing.
 *
 * <p>No I/O or framework dependency lives here, so every branch is covered by
 * the fast unit tier. A result is imported only when all five identifiers agree
 * with the locally registered expectation; every disposition is routed to the
 * owner and coverage queue recorded on that expectation.</p>
 */
public final class ResultRules {

    private ResultRules() {
    }

    /**
     * Match an inbound result against the locally registered order expectation.
     *
     * <p>An absent expectation yields {@code UNMATCHED}. An expectation with any
     * disagreeing facet yields {@code CONFLICT} and names every disagreeing
     * field in a stable order. Identifiers are compared exactly: normalization
     * and validation happen at the service boundary, never implicitly here.</p>
     *
     * @param expected the locally registered expectation, if one exists
     * @param actual   identifiers claimed by the inbound result
     * @return the match decision with all disagreeing field names
     */
    public static MatchDecision match(Optional<ExpectedOrder> expected, ResultIdentifiers actual) {
        if (expected.isEmpty()) {
            return new MatchDecision(MatchStatus.UNMATCHED, List.of());
        }
        ResultIdentifiers wanted = expected.get().identifiers();
        List<String> mismatches = new ArrayList<>();
        if (!Objects.equals(actual.patientId(), wanted.patientId())) {
            mismatches.add("patientId");
        }
        if (!Objects.equals(actual.sourceId(), wanted.sourceId())) {
            mismatches.add("sourceId");
        }
        if (!Objects.equals(actual.accessionId(), wanted.accessionId())) {
            mismatches.add("accessionId");
        }
        if (!Objects.equals(actual.destinationId(), wanted.destinationId())) {
            mismatches.add("destinationId");
        }
        if (!Objects.equals(actual.orderId(), wanted.orderId())) {
            mismatches.add("orderId");
        }
        if (mismatches.isEmpty()) {
            return new MatchDecision(MatchStatus.MATCHED, List.of());
        }
        return new MatchDecision(MatchStatus.CONFLICT, mismatches);
    }

    /**
     * Route an imported result to a named owner and coverage queue.
     *
     * <p>Critical findings carry an explicit marker for the downstream
     * escalation story; delivery and clinical acknowledgment are separate facts
     * owned by XCH-05 and are not performed here.</p>
     *
     * @param order the matched local expectation owning the routing policy
     * @param kind  the result disposition
     * @return the routing decision
     */
    public static Routing route(ExpectedOrder order, ResultKind kind) {
        String rationale = switch (kind) {
            case FINAL -> "final result for order " + order.orderId();
            case CORRECTED -> "corrected result for order " + order.orderId();
            case CRITICAL -> "critical result for order " + order.orderId();
        };
        return new Routing(order.owner(), order.coverageQueue(), kind.isCritical(), rationale);
    }
}
