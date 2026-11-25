package com.aurevia.authz;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.it.contract.OpenApiRouteCoverage;
import com.aurevia.it.contract.OpenApiRouteCoverage.Route;

/**
 * Reusable build-gate comparison between POST handlers and the command catalog (OPS-02, ARC-04).
 *
 * <p>The caller supplies routes discovered by Spring's
 * {@link org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping};
 * this class does not read controller source text. Route normalisation and the two-way difference
 * come from {@link com.aurevia.it.contract.OpenApiRouteCoverage}. On top of that strict comparison
 * this gate accepts only non-command POSTs carrying a one-line reason; an unexplained exception is
 * itself reported as a defect.</p>
 */
final class CommandRouteParityCheck {

    private CommandRouteParityCheck() {
    }

    /**
     * Mismatch sets from comparing handlers with the catalog.
     *
     * @param handlersWithoutEntry POST handlers with neither a catalog entry nor a reasoned exception
     * @param entriesWithoutHandler catalog commands with no live handler
     * @param unexplainedAllowList allow-list routes whose reason is absent, blank or multi-line
     */
    record Report(Set<Route> handlersWithoutEntry, Set<Route> entriesWithoutHandler,
                  Set<Route> unexplainedAllowList) {

        /**
         * Whether every handler is catalogued and every entry is live and explained.
         *
         * @return true only when all three mismatch sets are empty
         */
        boolean passed() {
            return handlersWithoutEntry.isEmpty() && entriesWithoutHandler.isEmpty()
                    && unexplainedAllowList.isEmpty();
        }

        /**
         * Name every mismatch so a build failure points at the side to fix.
         *
         * @return deterministic multiline failure message
         */
        String describe() {
            if (passed()) {
                return "POST handler/catalog parity OK";
            }
            StringBuilder message = new StringBuilder("POST handler/catalog parity failed:");
            append(message, "handlers without a catalog entry", handlersWithoutEntry);
            append(message, "catalog entries without a handler", entriesWithoutHandler);
            append(message, "allow-list entries without a one-line reason", unexplainedAllowList);
            return message.toString();
        }
    }

    /**
     * Compare discovered handlers with the catalog, applying only reasoned exceptions.
     *
     * @param handlers  routes reported by a live Spring handler mapping
     * @param catalog   authoritative command catalog
     * @param allowList non-command POST routes mapped to a one-line reason
     * @return the three mismatch sets
     */
    static Report evaluate(Set<Route> handlers, CommandCatalog catalog, Map<Route, String> allowList) {
        Objects.requireNonNull(handlers, "handlers");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(allowList, "allowList");
        // CommandDefinition accepts only POST, so catalog entries need no method filter.
        Set<Route> posts = handlers.stream()
                .filter(route -> "POST".equals(route.method()))
                .collect(Collectors.toUnmodifiableSet());
        Set<Route> commands = catalog.commands().stream()
                .map(definition -> new Route(definition.method(), definition.path()))
                .collect(Collectors.toUnmodifiableSet());
        OpenApiRouteCoverage.Report strict = OpenApiRouteCoverage.compare(posts, commands);
        Set<Route> explained = allowList.entrySet().stream()
                .filter(entry -> validReason(entry.getValue()))
                .map(entry -> entry.getKey().normalized())
                .collect(Collectors.toUnmodifiableSet());
        Set<Route> unexplained = new TreeSet<>(Route.ORDER);
        allowList.entrySet().stream()
                .filter(entry -> !validReason(entry.getValue()))
                .map(entry -> entry.getKey().normalized())
                .forEach(unexplained::add);
        Set<Route> missing = new TreeSet<>(Route.ORDER);
        missing.addAll(strict.undocumented());
        missing.removeAll(explained);
        return new Report(Collections.unmodifiableSet(missing), strict.ghost(),
                Collections.unmodifiableSet(unexplained));
    }

    /**
     * Require a one-line, non-blank justification for a non-command POST.
     *
     * @param reason allow-list justification to check
     * @return true only when the reason can be reviewed as a single line
     */
    private static boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.indexOf('\n') < 0
                && reason.indexOf('\r') < 0;
    }

    /**
     * Append one named mismatch category when it is not empty.
     */
    private static void append(StringBuilder message, String label, Set<Route> routes) {
        if (routes.isEmpty()) {
            return;
        }
        message.append(System.lineSeparator()).append(label).append(':');
        routes.forEach(route -> message.append(' ').append(route));
    }
}
