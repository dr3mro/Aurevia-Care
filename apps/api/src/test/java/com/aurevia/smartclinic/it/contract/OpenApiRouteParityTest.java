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
 * controller route. There is no baseline: any undocumented route, or any
 * contract route with no controller, fails the build.
 *
 * <p>Routes come from Spring's live {@link RequestMappingHandlerMapping}, not a
 * source-text search, so a new mapping is always seen. The only route removed
 * before comparison is the method-less {@code /error} fallback that Spring
 * registers for {@code BasicErrorController}; it is servlet plumbing, not a
 * staff-API operation, and {@link OpenApiRouteCoverage#withoutFrameworkRoutes(Set)}
 * excludes it with an explicit, unit-tested predicate. The X-04 catalog document
 * is command-oriented and intentionally contains virtual routes.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("synthetic-auth")
class OpenApiRouteParityTest {

    /** Surefire's basedir is the module directory (apps/api). */
    private static final Path CONTRACT = Path.of("../../contracts/openapi.yaml");
    private static final Set<String> HTTP_METHODS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    /**
     * Fail when a live application route is absent from the contract or the
     * contract publishes a route with no live controller.
     *
     * @throws IOException when the authoritative contract cannot be read
     */
    @Test
    void everyControllerRouteIsDocumented() throws IOException {
        Set<OpenApiRouteCoverage.Route> controller = OpenApiRouteCoverage.withoutFrameworkRoutes(
                controllerRoutes(handlerMapping));
        Set<OpenApiRouteCoverage.Route> contract = readContract(CONTRACT);

        OpenApiRouteCoverage.Report report = OpenApiRouteCoverage.compare(controller, contract);

        assertTrue(report.passed(), OpenApiRouteCoverage.describe(report));
    }

    /**
     * Prove reachability as well as presence: each live application route must
     * have a documented operation that declares at least one 2xx response.
     *
     * @throws IOException when the authoritative contract cannot be read
     */
    @Test
    void everyApplicationRouteHasAnOperationWithASuccessResponse() throws IOException {
        // Normalise variable names on both sides: a controller {orderId} and a
        // contract {id} describe the same route, as in the parity comparison.
        Set<OpenApiRouteCoverage.Route> controller = OpenApiRouteCoverage.withoutFrameworkRoutes(
                        controllerRoutes(handlerMapping)).stream()
                .map(OpenApiRouteCoverage.Route::normalized)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<OpenApiRouteCoverage.Route> withSuccess = readSuccessRoutes(CONTRACT).stream()
                .map(OpenApiRouteCoverage.Route::normalized)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertTrue(withSuccess.containsAll(controller),
                () -> "operations missing a 2xx response: " + cloneDifference(controller, withSuccess));
    }

    private static Set<OpenApiRouteCoverage.Route> cloneDifference(
            Set<OpenApiRouteCoverage.Route> controller, Set<OpenApiRouteCoverage.Route> documented) {
        Set<OpenApiRouteCoverage.Route> missing = new HashSet<>(controller);
        missing.removeAll(documented);
        return missing;
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
                    // Spring's BasicErrorController /error fallback has no method
                    // condition; keep it visible so the explicit exclusion is tested.
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

    /**
     * Read the method + path pairs whose operation declares at least one 2xx
     * response, so a documented route with only error responses is rejected.
     *
     * @param path authoritative contract path
     * @return routes with a declared success response
     * @throws IOException when the contract cannot be read
     */
    static Set<OpenApiRouteCoverage.Route> readSuccessRoutes(Path path) throws IOException {
        Set<OpenApiRouteCoverage.Route> routes = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> root) || !(root.get("paths") instanceof Map<?, ?> pathMap)) {
                throw new IllegalStateException("OpenAPI document has no paths mapping: " + path);
            }
            for (Map.Entry<?, ?> entry : pathMap.entrySet()) {
                String template = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> operations)) {
                    continue;
                }
                for (Map.Entry<?, ?> operation : operations.entrySet()) {
                    String method = String.valueOf(operation.getKey()).toUpperCase(Locale.ROOT);
                    if (!HTTP_METHODS.contains(method)
                            || !(operation.getValue() instanceof Map<?, ?> details)) {
                        continue;
                    }
                    if (!(details.get("responses") instanceof Map<?, ?> responses)) {
                        continue;
                    }
                    for (Object status : responses.keySet()) {
                        String code = String.valueOf(status);
                        if (code.length() == 3 && code.startsWith("2")) {
                            routes.add(new OpenApiRouteCoverage.Route(method, template));
                            break;
                        }
                    }
                }
            }
        }
        return routes;
    }
}
