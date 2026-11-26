package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.CommandDefinition;
import com.aurevia.authz.CommandRouteParityCheck.Report;
import com.aurevia.it.contract.OpenApiRouteCoverage.Route;

/**
 * Red/green proof of the reusable OPS-02 parity evaluator. These tests use synthetic route sets
 * only; they prove that a handler without a catalog entry and a catalog entry without a handler are
 * both named. The live Spring-discovery assertion is a separate, still-blocked step: see the note.
 */
class CommandRouteParityTest {

    private static final String FACILITIES = "/api/v1/facilities";
    private static final String SIGN_IN = "/api/v1/auth/sign-in";

    /**
     * Small catalog with one route, so each assertion isolates one mismatch category.
     */
    private static CommandCatalog facilitiesCatalog() {
        return new CommandCatalog(List.of(new CommandDefinition(
                "createFacility", "FACILITY_WRITE", "POST", FACILITIES, "ActionPolicy")));
    }

    @Test
    void variableNamesAreNormalisedOnBothSides() {
        Route controller = new Route("POST", "/api/v1/orders/{orderId}/accept");
        Route catalogRoute = new Route("POST", "/api/v1/orders/{id}/accept");

        assertEquals(catalogRoute.normalized(), controller.normalized());
        assertEquals("POST /api/v1/orders/{orderId}/accept", controller.toString());
        Route literal = new Route("POST", FACILITIES);
        assertEquals(literal, literal.normalized());
    }

    @Test
    void nonPostHandlersAreIgnored() {
        Report report = CommandRouteParityCheck.evaluate(
                Set.of(new Route("GET", FACILITIES), new Route("POST", FACILITIES)),
                facilitiesCatalog(), Map.of());

        assertTrue(report.passed(), report.describe());
        assertEquals("POST handler/catalog parity OK", report.describe());
    }

    @Test
    void missingHandlerAndStaleCatalogEntryAreBothReported() {
        Route newCommand = new Route("POST", "/api/v1/new-command");

        Report report = CommandRouteParityCheck.evaluate(
                Set.of(newCommand), facilitiesCatalog(), Map.of());

        assertEquals(Set.of(newCommand), report.handlersWithoutEntry());
        assertEquals(Set.of(new Route("POST", FACILITIES)), report.entriesWithoutHandler());
        assertFalse(report.passed());
        String message = report.describe();
        assertTrue(message.contains("POST /api/v1/new-command"), message);
        assertTrue(message.contains("POST " + FACILITIES), message);
    }

    @Test
    void staleCatalogEntryAloneFailsParity() {
        Report report = CommandRouteParityCheck.evaluate(Set.of(), facilitiesCatalog(), Map.of());

        assertFalse(report.passed());
        assertTrue(report.handlersWithoutEntry().isEmpty());
        assertEquals(Set.of(new Route("POST", FACILITIES)), report.entriesWithoutHandler());
    }

    @Test
    void reasonedAllowListEntrySuppressesANonCommandPost() {
        Route signIn = new Route("POST", SIGN_IN);
        CommandCatalog empty = new CommandCatalog(List.of());

        Report report = CommandRouteParityCheck.evaluate(Set.of(signIn), empty,
                Map.of(signIn, "Authentication is not an explicit command"));

        assertTrue(report.passed(), report.describe());
        assertTrue(report.handlersWithoutEntry().isEmpty());
    }

    @Test
    void allowListKeysAreNormalisedBeforeMatching() {
        Route handler = new Route("POST", "/api/v1/new-command/{id}");
        Route allowListKey = new Route("POST", "/api/v1/new-command/{orderId}");

        Report report = CommandRouteParityCheck.evaluate(
                Set.of(new Route("POST", FACILITIES), handler), facilitiesCatalog(),
                Map.of(allowListKey, "Domain query, not a command"));

        assertTrue(report.passed(), report.describe());
    }

    @Test
    void unusedAllowListEntryDoesNotFailParity() {
        Route unused = new Route("POST", "/api/v1/retired-command");

        Report report = CommandRouteParityCheck.evaluate(
                Set.of(new Route("POST", FACILITIES)), facilitiesCatalog(),
                Map.of(unused, "Removed endpoint kept during rollout"));

        assertTrue(report.passed(), report.describe());
    }

    @Test
    void nullAllowListReasonIsReportedAsUnexplained() {
        Route signIn = new Route("POST", SIGN_IN);
        CommandCatalog empty = new CommandCatalog(List.of());

        Report report = CommandRouteParityCheck.evaluate(Set.of(), empty,
                Collections.singletonMap(signIn, null));

        assertFalse(report.passed());
        assertTrue(report.handlersWithoutEntry().isEmpty());
        assertTrue(report.entriesWithoutHandler().isEmpty());
        assertEquals(Set.of(signIn), report.unexplainedAllowList());
        assertTrue(report.describe().contains("allow-list entries without a one-line reason"),
                report.describe());
    }

    @Test
    void blankAllowListReasonIsReportedAsUnexplained() {
        Route signIn = new Route("POST", SIGN_IN);
        CommandCatalog empty = new CommandCatalog(List.of());

        Report report = CommandRouteParityCheck.evaluate(Set.of(), empty, Map.of(signIn, "  "));

        assertFalse(report.passed());
        assertEquals(Set.of(signIn), report.unexplainedAllowList());
    }

    @Test
    void multilineAllowListReasonIsReportedAsUnexplained() {
        Route signIn = new Route("POST", SIGN_IN);
        CommandCatalog empty = new CommandCatalog(List.of());

        Report report = CommandRouteParityCheck.evaluate(Set.of(), empty,
                Map.of(signIn, "first line\nsecond line"));

        assertFalse(report.passed());
        assertEquals(Set.of(signIn), report.unexplainedAllowList());
    }

    @Test
    void carriageReturnAllowListReasonIsReportedAsUnexplained() {
        Route signIn = new Route("POST", SIGN_IN);
        CommandCatalog empty = new CommandCatalog(List.of());

        Report report = CommandRouteParityCheck.evaluate(Set.of(), empty,
                Map.of(signIn, "first line\rsecond line"));

        assertFalse(report.passed());
        assertEquals(Set.of(signIn), report.unexplainedAllowList());
    }

    @Test
    void describeNamesOnlyTheCategoriesThatFailed() {
        Report report = CommandRouteParityCheck.evaluate(
                Set.of(new Route("POST", FACILITIES), new Route("POST", "/api/v1/new-command")),
                facilitiesCatalog(), Map.of());

        String message = report.describe();
        assertTrue(message.contains("handlers without a catalog entry"), message);
        assertFalse(message.contains("catalog entries without a handler"), message);
        assertFalse(message.contains("allow-list entries without a reason"), message);
    }

    @Test
    void nullInputsAreRejected() {
        CommandCatalog catalog = facilitiesCatalog();

        assertThrows(NullPointerException.class,
                () -> CommandRouteParityCheck.evaluate(null, catalog, Map.of()));
        assertThrows(NullPointerException.class,
                () -> CommandRouteParityCheck.evaluate(Set.of(), null, Map.of()));
        assertThrows(NullPointerException.class,
                () -> CommandRouteParityCheck.evaluate(Set.of(), catalog, null));
        assertThrows(NullPointerException.class, () -> new Route(null, FACILITIES));
        assertThrows(NullPointerException.class, () -> new Route("POST", null));
    }
}
