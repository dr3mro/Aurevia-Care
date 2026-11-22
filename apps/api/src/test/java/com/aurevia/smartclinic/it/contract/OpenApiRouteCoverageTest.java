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
                route("POST /api/v1/facilities", "POST /api/v1/legacy"),
                route("POST /api/v1/other"), Set.of());

        String description = OpenApiRouteCoverage.describe(report);
        assertTrue(description.contains("POST /api/v1/legacy"));
        assertTrue(description.contains("POST /api/v1/facilities"));
        assertTrue(description.contains("POST /api/v1/other"));
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
        return OpenApiRouteCoverage.parseBaseline(List.of(lines));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
}
