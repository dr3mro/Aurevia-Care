package com.aurevia.smartclinic.it.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Proves the strict OpenAPI route gate itself with small synthetic handler sets.
 *
 * <p>Each failure mode of {@link OpenApiRouteCoverage} is exercised with a
 * fixture that is independent of the running application, so a regression in
 * the gate cannot be hidden by the real contract. The framework-only exclusion
 * is asserted in both directions: the method-less {@code /error} fallback is
 * removed, while a real application route is never removed.</p>
 */
class OpenApiRouteCoverageTest {

    @Test
    void undocumentedApplicationRouteFailsStrictParity() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/appointments", "POST /api/v1/new-command"), Set.of());

        assertFalse(report.passed());
        assertEquals(route("POST /api/v1/appointments", "POST /api/v1/new-command"),
                report.undocumented());
        assertEquals(Set.of(), report.ghost());
        assertTrue(OpenApiRouteCoverage.describe(report).contains("POST /api/v1/new-command"));
    }

    @Test
    void strictParityAcceptsDocumentedRouteAndRejectsGhost() {
        OpenApiRouteCoverage.Report clean = OpenApiRouteCoverage.compare(
                route("GET /api/v1/facilities"), route("GET /api/v1/facilities"));
        assertTrue(clean.passed());
        assertEquals(Set.of(), clean.undocumented());
        assertEquals(Set.of(), clean.ghost());

        OpenApiRouteCoverage.Report withGhost = OpenApiRouteCoverage.compare(
                route("GET /api/v1/facilities"),
                route("GET /api/v1/facilities", "GET /api/v1/ghost"));
        assertFalse(withGhost.passed());
        assertEquals(Set.of(), withGhost.undocumented());
        assertEquals(route("GET /api/v1/ghost"), withGhost.ghost());
        assertTrue(OpenApiRouteCoverage.describe(withGhost)
                .contains("Contract routes with no controller"));
        assertTrue(OpenApiRouteCoverage.describe(withGhost).contains("GET /api/v1/ghost"));
    }

    @Test
    void frameworkErrorRouteIsExcludedButARealRouteIsNot() {
        Set<OpenApiRouteCoverage.Route> filtered = OpenApiRouteCoverage.withoutFrameworkRoutes(
                route("ANY /error", "GET /error", "GET /api/v1/facilities"));

        assertEquals(route("GET /error", "GET /api/v1/facilities"), filtered);
        assertFalse(filtered.contains(new OpenApiRouteCoverage.Route("ANY", "/error")));
    }

    @Test
    void pathVariableNamesDoNotCreateFalseDrift() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/orders/{orderId}/accept"),
                route("POST /api/v1/orders/{id}/accept"));

        assertTrue(report.passed());
        assertEquals(Set.of(), report.ghost());
    }

    @Test
    void parseRouteSkipsNothingButRejectsAMalformedLine() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OpenApiRouteCoverage.parseRoute("GET"));

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
    void committedContractPublishesEveryStaffAttachmentRoute() throws IOException {
        Set<OpenApiRouteCoverage.Route> published = OpenApiRouteParityTest
                .readContract(Path.of("../../contracts/openapi.yaml")).stream()
                .map(OpenApiRouteCoverage.Route::normalized)
                .collect(Collectors.toUnmodifiableSet());

        assertTrue(published.containsAll(route(
                "GET /api/v1/staff/attachments/pending",
                "GET /api/v1/staff/attachments/{}/download",
                "POST /api/v1/staff/attachments/send",
                "POST /api/v1/staff/attachments/upload",
                "POST /api/v1/staff/attachments/{}/scan")),
                "contract must publish every attachment route, got " + published);
    }

    @Test
    void describeListsEveryMissingRouteAsWellAsEachViolation() {
        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                route("POST /api/v1/facilities", "POST /api/v1/legacy"), Set.of());

        String description = OpenApiRouteCoverage.describe(report);
        assertTrue(description.contains("POST /api/v1/legacy"));
        assertTrue(description.contains("POST /api/v1/facilities"));
    }

    @Test
    void committedContractDescribesAttachmentCommandsTruthfully() throws IOException {
        Map<String, Object> document = asMap(new Yaml().load(
                Files.readString(Path.of("../../contracts/openapi.yaml"))));
        Map<String, Object> paths = asMap(document.get("paths"));
        Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));

        Map<String, Object> verdict = asMap(asMap(
                asMap(schemas.get("ScanRequest")).get("properties")).get("verdict"));
        assertEquals(List.of("clean", "failed"), verdict.get("enum"),
                "the scan command uses terminal verdicts, not the stored scan-state vocabulary");

        for (String path : List.of("/api/v1/staff/attachments/upload", "/api/v1/staff/attachments/{id}/scan",
                "/api/v1/staff/attachments/send", "/api/v1/staff/attachments/{id}/download")) {
            Map<String, Object> item = asMap(paths.get(path));
            Map<String, Object> operation = asMap(item.containsKey("get") ? item.get("get") : item.get("post"));
            assertTrue(asMap(operation.get("responses")).containsKey("409"),
                    path + " must document its reachable 409 conflicts");
        }

        for (String path : List.of("/api/v1/staff/attachments/upload", "/api/v1/staff/attachments/{id}/scan",
                "/api/v1/staff/attachments/pending", "/api/v1/staff/attachments/send",
                "/api/v1/staff/attachments/{id}/download")) {
            Map<String, Object> item = asMap(paths.get(path));
            Map<String, Object> operation = asMap(item.containsKey("get") ? item.get("get") : item.get("post"));
            assertTrue(asMap(operation.get("responses")).containsKey("422"),
                    path + " must document its reachable 422 invalid-input error");
        }

        Map<String, Object> download = asMap(asMap(
                paths.get("/api/v1/staff/attachments/{id}/download")).get("get"));
        Map<String, Object> content = asMap(asMap(asMap(
                asMap(download.get("responses")).get("200")).get("content")));
        assertTrue(content.keySet().containsAll(List.of("application/pdf", "image/jpeg", "image/png")),
                "download returns the original PDF, JPEG or PNG type: " + content.keySet());
    }

    private static Set<OpenApiRouteCoverage.Route> route(String... lines) {
        Set<OpenApiRouteCoverage.Route> routes = new java.util.TreeSet<>(OpenApiRouteCoverage.Route.ORDER);
        for (String line : lines) {
            routes.add(OpenApiRouteCoverage.parseRoute(line));
        }
        return java.util.Collections.unmodifiableSet(routes);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
