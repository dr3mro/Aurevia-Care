package com.aurevia.smartclinic.it.contract;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pure comparison logic for the OpenAPI route gate (WEB-05, ARC-04).
 *
 * <p>A controller route is acceptable when the authoritative contract documents
 * it, or when the shrinking baseline explicitly carries the debt. The gate also
 * rejects contract routes with no controller and baseline entries that have
 * since been documented, so the baseline can only shrink. Path template
 * variables are normalised so a controller and a contract may use different
 * placeholder names for the same route.</p>
 */
public final class OpenApiRouteCoverage {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}");
    private static final String LINE_SHAPE = "METHOD /path";

    private OpenApiRouteCoverage() {
    }

    /** One HTTP method and path template, compared case-insensitively on the method. */
    public record Route(String method, String path) {

        /** Deterministic order for failure messages and baseline files. */
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
     * The outcome of comparing real controller routes with the contract and the
     * baseline. Every set is sorted so a failure message is reproducible.
     *
     * @param missingFromContract controller routes absent from the contract
     * @param undocumented missing routes that the baseline does not carry
     * @param ghost contract routes with no controller
     * @param staleBaseline baseline entries that are now documented
     */
    public record Report(Set<Route> missingFromContract, Set<Route> undocumented,
                         Set<Route> ghost, Set<Route> staleBaseline) {

        /**
         * Construct an immutable, ordered report.
         *
         * @param missingFromContract controller routes absent from the contract
         * @param undocumented missing routes that the baseline does not carry
         * @param ghost contract routes with no controller
         * @param staleBaseline baseline entries that are now documented
         */
        public Report {
            missingFromContract = sortedCopy(missingFromContract);
            undocumented = sortedCopy(undocumented);
            ghost = sortedCopy(ghost);
            staleBaseline = sortedCopy(staleBaseline);
        }

        /**
         * Whether the gate is satisfied.
         *
         * @return true only when there are no undocumented, ghost or stale-baseline routes
         */
        public boolean passed() {
            return undocumented.isEmpty() && ghost.isEmpty() && staleBaseline.isEmpty();
        }
    }

    /**
     * Compare controller, contract and baseline routes.
     *
     * @param controller routes registered by Spring
     * @param contract routes documented in the authoritative OpenAPI contract
     * @param baseline currently accepted undocumented routes
     * @return the reproducible comparison report
     */
    public static Report compare(Set<Route> controller, Set<Route> contract, Set<Route> baseline) {
        Set<Route> controllerRoutes = normalized(controller);
        Set<Route> contractRoutes = normalized(contract);
        Set<Route> baselineRoutes = normalized(baseline);

        Set<Route> missing = new TreeSet<>(Route.ORDER);
        missing.addAll(controllerRoutes);
        missing.removeAll(contractRoutes);

        Set<Route> undocumented = new TreeSet<>(Route.ORDER);
        undocumented.addAll(missing);
        undocumented.removeAll(baselineRoutes);

        Set<Route> ghost = new TreeSet<>(Route.ORDER);
        ghost.addAll(contractRoutes);
        ghost.removeAll(controllerRoutes);

        Set<Route> stale = new TreeSet<>(Route.ORDER);
        stale.addAll(baselineRoutes);
        stale.retainAll(contractRoutes);

        return new Report(missing, undocumented, ghost, stale);
    }

    /**
     * Parse the baseline file, ignoring blank lines and {@code #} comments.
     *
     * @param lines raw file lines
     * @return an immutable, ordered set of routes
     */
    public static Set<Route> parseBaseline(List<String> lines) {
        Set<Route> routes = new TreeSet<>(Route.ORDER);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            routes.add(parseRoute(trimmed));
        }
        return Collections.unmodifiableSet(routes);
    }

    /**
     * Parse one {@code METHOD /path} line, upper-casing the method.
     *
     * @param line a single baseline entry
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
        append(message, "Controller routes missing from the contract", report.missingFromContract());
        append(message, "NEW undocumented controller routes (add to the contract or the baseline)",
                report.undocumented());
        append(message, "Contract routes with no controller", report.ghost());
        append(message, "Baseline entries already documented (remove them so the list only shrinks)",
                report.staleBaseline());
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
