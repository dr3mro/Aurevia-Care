package com.aurevia.smartclinic.it.contract;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pure comparison logic for the strict OpenAPI route gate (WEB-05, ARC-04).
 *
 * <p>A controller route is acceptable only when the authoritative contract
 * documents it. There is no shrinking baseline: the gate also rejects contract
 * routes with no controller, so the contract cannot drift in either direction.
 * The single framework fallback that Spring registers without a method
 * condition is removed explicitly by {@link #withoutFrameworkRoutes(Set)} before
 * the comparison; every real application route remains subject to the gate.</p>
 *
 * <p>Path template variables are normalised so a controller and a contract may
 * use different placeholder names for the same route.</p>
 */
public final class OpenApiRouteCoverage {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}");
    private static final String LINE_SHAPE = "METHOD /path";

    /**
     * Spring's {@code BasicErrorController} registers a method-less {@code /error}
     * fallback. It is servlet plumbing, not a staff-API operation, and is the only
     * route excluded from strict parity.
     */
    private static final Route FRAMEWORK_ERROR = new Route("ANY", "/error");

    private OpenApiRouteCoverage() {
    }

    /** One HTTP method and path template, compared case-insensitively on the method. */
    public record Route(String method, String path) {

        /** Deterministic order for failure messages. */
        public static final Comparator<Route> ORDER = Comparator
                .comparing(Route::method)
                .thenComparing(Route::path);

        /**
         * Create a route.
         *
         * @param method HTTP method or {@code ANY} for a method-less framework mapping
         * @param path path template, with or without variable names
         */
        public Route {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
        }

        /**
         * Replace variable names with a stable placeholder so {@code {id}} and
         * {@code {orderId}} describe the same route.
         *
         * @return the normalised route
         */
        public Route normalized() {
            return new Route(method, PATH_VARIABLE.matcher(path).replaceAll("{}"));
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /**
     * The outcome of comparing real controller routes with the contract. Every
     * set is sorted so a failure message is reproducible.
     *
     * @param undocumented controller routes absent from the contract
     * @param ghost contract routes with no controller
     */
    public record Report(Set<Route> undocumented, Set<Route> ghost) {

        /**
         * Construct an immutable, ordered report.
         *
         * @param undocumented controller routes absent from the contract
         * @param ghost contract routes with no controller
         */
        public Report {
            undocumented = sortedCopy(undocumented);
            ghost = sortedCopy(ghost);
        }

        /**
         * Whether strict parity is satisfied.
         *
         * @return true only when no controller route is undocumented and the
         *         contract publishes no ghost route
         */
        public boolean passed() {
            return undocumented.isEmpty() && ghost.isEmpty();
        }
    }

    /**
     * Compare controller and contract routes.
     *
     * @param controller routes registered by Spring, already stripped of the
     *                   framework-only fallback
     * @param contract routes documented in the authoritative OpenAPI contract
     * @return the reproducible comparison report
     */
    public static Report compare(Set<Route> controller, Set<Route> contract) {
        Set<Route> controllerRoutes = normalized(controller);
        Set<Route> contractRoutes = normalized(contract);

        Set<Route> undocumented = new TreeSet<>(Route.ORDER);
        undocumented.addAll(controllerRoutes);
        undocumented.removeAll(contractRoutes);

        Set<Route> ghost = new TreeSet<>(Route.ORDER);
        ghost.addAll(contractRoutes);
        ghost.removeAll(controllerRoutes);

        return new Report(undocumented, ghost);
    }

    /**
     * Drop the method-less {@code /error} fallback that Spring registers for
     * {@code BasicErrorController}. The predicate is exact: a real application
     * route such as {@code GET /error} is not excluded.
     *
     * @param routes routes derived from the live handler mapping
     * @return an immutable set with only the framework fallback removed
     */
    public static Set<Route> withoutFrameworkRoutes(Set<Route> routes) {
        Set<Route> result = new HashSet<>();
        for (Route route : routes) {
            if (!FRAMEWORK_ERROR.equals(route.normalized())) {
                result.add(route);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Parse one {@code METHOD /path} line, upper-casing the method. Retained as
     * the small parser behind the gate's synthetic test fixtures.
     *
     * @param line a single route entry
     * @return the parsed route
     * @throws IllegalArgumentException when the line is not in {@code METHOD /path} form
     */
    public static Route parseRoute(String line) {
        String trimmed = line.trim();
        int separator = trimmed.indexOf(' ');
        if (separator <= 0) {
            throw new IllegalArgumentException("expected " + LINE_SHAPE + " but got: " + line);
        }
        String method = trimmed.substring(0, separator).toUpperCase(Locale.ROOT);
        String path = trimmed.substring(separator + 1).trim();
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("expected " + LINE_SHAPE + " but got: " + line);
        }
        return new Route(method, path);
    }

    /**
     * Build a human-readable failure message naming every affected route.
     *
     * @param report comparison outcome
     * @return a deterministic multiline description
     */
    public static String describe(Report report) {
        StringBuilder message = new StringBuilder("OpenAPI route parity failed.");
        append(message, "NEW undocumented controller routes", report.undocumented());
        append(message, "Contract routes with no controller", report.ghost());
        return message.toString();
    }

    private static void append(StringBuilder message, String heading, Set<Route> routes) {
        if (routes.isEmpty()) {
            return;
        }
        message.append('\n').append(heading).append(':');
        routes.forEach(route -> message.append('\n').append("  ").append(route));
    }

    private static Set<Route> normalized(Set<Route> routes) {
        Set<Route> result = new HashSet<>();
        routes.forEach(route -> result.add(route.normalized()));
        return result;
    }

    private static Set<Route> sortedCopy(Set<Route> routes) {
        Set<Route> sorted = new TreeSet<>(Route.ORDER);
        sorted.addAll(routes);
        return Collections.unmodifiableSet(sorted);
    }
}
