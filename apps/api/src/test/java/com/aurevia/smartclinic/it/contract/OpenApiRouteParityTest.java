package com.aurevia.smartclinic.it.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import org.yaml.snakeyaml.Yaml;

/**
 * WEB-05 / ARC-04 gate: the authoritative OpenAPI contract must describe every
 * controller route except the ones explicitly carried in the shrinking baseline.
 *
 * <p>Routes come from Spring's live {@link RequestMappingHandlerMapping}, not a
 * source-text search, so a new mapping is always seen. The contract is the
 * hand-authored staff-API document that generates the client; the X-04 catalog
 * document is command-oriented and intentionally contains virtual routes.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("synthetic-auth")
class OpenApiRouteParityTest {

    /** Surefire's basedir is the module directory (apps/api). */
    private static final Path CONTRACT = Path.of("../../contracts/openapi.yaml");
    private static final Path BASELINE = Path.of("../../contracts/openapi/missing-routes.txt");
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyControllerRouteIsDocumentedOrBaselined() throws IOException {
        Set<OpenApiRouteCoverage.Route> controllerRoutes = controllerRoutes(handlerMapping);
        Set<OpenApiRouteCoverage.Route> contractRoutes = readContract(CONTRACT);
        Set<OpenApiRouteCoverage.Route> baseline = Files.exists(BASELINE)
                ? OpenApiRouteCoverage.parseBaseline(Files.readAllLines(BASELINE, StandardCharsets.UTF_8))
                : Set.of();

        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(
                controllerRoutes, contractRoutes, baseline);

        assertTrue(report.passed(), OpenApiRouteCoverage.describe(report)
                + "\nBaseline file: " + BASELINE);
    }

    /** Enumerate method plus path template for every registered handler. */
    static Set<OpenApiRouteCoverage.Route> controllerRoutes(RequestMappingHandlerMapping mapping) {
        Set<OpenApiRouteCoverage.Route> routes = new HashSet<>();
        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            Set<String> methods = info.getMethodsCondition().getMethods().stream()
                    .map(method -> method.name())
                    .collect(java.util.stream.Collectors.toSet());
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) {
                    // Spring's framework fallback (BasicErrorController /error) has no
                    // method condition; keep it visible rather than silently dropping it.
                    routes.add(new OpenApiRouteCoverage.Route("ANY", pattern));
                } else {
                    for (String method : methods) {
                        routes.add(new OpenApiRouteCoverage.Route(method, pattern));
                    }
                }
            }
        }
        return routes;
    }

    /** Read the method + path pairs from the committed OpenAPI document. */
    static Set<OpenApiRouteCoverage.Route> readContract(Path path) throws IOException {
        Set<OpenApiRouteCoverage.Route> routes = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalStateException("OpenAPI root is not a mapping: " + path);
            }
            Object paths = root.get("paths");
            if (!(paths instanceof Map<?, ?> pathMap)) {
                throw new IllegalStateException("OpenAPI 'paths' is not a mapping: " + path);
            }
            for (Map.Entry<?, ?> entry : pathMap.entrySet()) {
                String template = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> operations)) {
                    continue;
                }
                for (Map.Entry<?, ?> operation : operations.entrySet()) {
                    String method = String.valueOf(operation.getKey()).toUpperCase(Locale.ROOT);
                    if (HTTP_METHODS.contains(method)) {
                        routes.add(new OpenApiRouteCoverage.Route(method, template));
                    }
                }
            }
        }
        return routes;
    }
}
