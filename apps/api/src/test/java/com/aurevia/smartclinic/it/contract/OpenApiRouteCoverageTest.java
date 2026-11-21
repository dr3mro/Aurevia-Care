package com.aurevia.smartclinic.it.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Proves the OpenAPI route gate itself with small synthetic handler sets.
 *
 * <p>Each failure mode of {@link OpenApiRouteCoverage} is exercised with a
 * fixture that is independent of the running application, so a regression in
 * the gate cannot be hidden by the real baseline file.</p>
 */
class OpenApiRouteCoverageTest {

    @Test
    void aNewUndocumentedControllerRouteFailsAndIsNamed() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/facilities", "POST /api/v1/new-command"),
                route("POST /api/v1/facilities"), Set.of());

        assertFalse(report.passed());
        assertEquals(route("POST /api/v1/new-command"), report.undocumented());
        assertEquals(Set.of(), report.ghost());
        assertEquals(Set.of(), report.staleBaseline());
        assertTrue(OpenApiRouteCoverage.describe(report).contains("POST /api/v1/new-command"));
    }

    @Test
    void aStaleBaselineLineFailsOnceTheRouteIsDocumented() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/facilities"), route("POST /api/v1/facilities"),
                route("POST /api/v1/facilities"));

        assertFalse(report.passed());
        assertEquals(Set.of(), report.undocumented());
        assertEquals(route("POST /api/v1/facilities"), report.staleBaseline());
        assertTrue(OpenApiRouteCoverage.describe(report).contains("POST /api/v1/facilities"));
    }

    @Test
    void aContractRouteWithNoControllerFailsAndIsNamed() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/facilities"),
                route("POST /api/v1/facilities", "POST /api/v1/ghost"),
                Set.of());

        assertFalse(report.passed());
        assertEquals(route("POST /api/v1/ghost"), report.ghost());
        assertTrue(OpenApiRouteCoverage.describe(report).contains("POST /api/v1/ghost"));
    }

    @Test
    void aBaselinedUndocumentedRoutePasses() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/facilities", "POST /api/v1/legacy"),
                route("POST /api/v1/facilities"), route("POST /api/v1/legacy"));

        assertTrue(report.passed());
        assertEquals(route("POST /api/v1/legacy"), report.missingFromContract());
        assertEquals(Set.of(), report.undocumented());
    }

    @Test
    void pathVariableNamesDoNotCreateFalseDrift() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/orders/{orderId}/accept"),
                route("POST /api/v1/orders/{id}/accept"), Set.of());

        assertTrue(report.passed());
        assertEquals(Set.of(), report.ghost());
    }

    @Test
    void parseBaselineSkipsBlankAndCommentLines() {
        Set<OpenApiRouteCoverage.Route> parsed = OpenApiRouteCoverage.parseBaseline(List.of(
                "", "   ", "# TODO: shrink this list", "GET /api/v1/facilities",
                "  post   /api/v1/staff  "));

        assertEquals(route("GET /api/v1/facilities", "POST /api/v1/staff"), parsed);
    }

    @Test
    void parseBaselineRejectsAMalformedLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OpenApiRouteCoverage.parseBaseline(List.of("GET")));

        assertTrue(error.getMessage().contains("METHOD /path"));
    }

    @Test
    void parseRouteRejectsAPathWithoutALeadingSlash() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OpenApiRouteCoverage.parseRoute("GET api/v1/facilities"));

        assertTrue(error.getMessage().contains("METHOD /path"));
    }

    @Test
    void aRouteRejectsNullMethodOrPath() {
        assertThrows(NullPointerException.class,
                () -> new OpenApiRouteCoverage.Route(null, "/api/v1/facilities"));
        assertThrows(NullPointerException.class,
                () -> new OpenApiRouteCoverage.Route("GET", null));
    }

    @Test
    void describeListsEveryMissingRouteAsWellAsEachViolation() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/facilities", "POST /api/v1/legacy"),
                route("POST /api/v1/other"), Set.of());

        String description = OpenApiRouteCoverage.describe(report);
        assertTrue(description.contains("POST /api/v1/legacy"));
        assertTrue(description.contains("POST /api/v1/facilities"));
        assertTrue(description.contains("POST /api/v1/other"));
    }

    private static Set<OpenApiRouteCoverage.Route> route(String... lines) {
        return OpenApiRouteCoverage.parseBaseline(List.of(lines));
    }
}
