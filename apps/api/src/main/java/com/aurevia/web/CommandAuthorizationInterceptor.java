package com.aurevia.web;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.CommandDefinition;
import com.aurevia.tenant.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The single HTTP entry point to the server-side command gate (OPS-02, ARC-04).
 *
 * <p>Registered only on the POST routes named by the command catalog, after the tenant
 * interceptor has bound the verified identity and before idempotency replay. The matched
 * Spring route template, never the raw request URI, selects the catalog command; a request
 * that cannot be resolved to a registered command is denied with the gate's bounded
 * {@code UNKNOWN_COMMAND} decision. Identity and correlation come only from the request
 * tenant context.</p>
 *
 * <p>The gate is the durable decision ledger, not the enforcing policy point: the owning
 * service re-checks membership, role, object and preconditions and produces its own error
 * contract and application audit. A recorded {@code FORBIDDEN} decision therefore does not
 * short-circuit that service, which preserves ARC-04 concealment and idempotent replay
 * semantics. Only unresolvable commands, unauthenticated callers and audit failures fail
 * closed here, because no owning service can enforce those cases. Static resources and
 * other non-controller handlers are not commands and are never gated. The interceptor is
 * stateless: a denial thrown from {@code preHandle} skips its own completion callbacks.</p>
 */
@Component
public final class CommandAuthorizationInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    /** Fixed, non-sensitive id used when no catalog entry matches the handler. */
    private static final String UNKNOWN_COMMAND = "UNRESOLVED_COMMAND";

    /** Runs after tenant binding (10) and before idempotency replay (20). */
    private static final int ORDER = 15;

    private final CommandCatalog catalog;
    private final CommandAuthorization authorization;

    /**
     * Create the gate interceptor.
     *
     * @param catalog       authoritative catalog used to resolve matched routes and registration
     * @param authorization the single injected command authorizer
     */
    public CommandAuthorizationInterceptor(CommandCatalog catalog, CommandAuthorization authorization) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    /**
     * Authorize one POST command before its handler runs.
     *
     * @param request  current HTTP request
     * @param response current HTTP response, unused; failures use the shared error handler
     * @param handler  selected handler; only controller methods are gated, fallbacks are left alone
     * @return {@code true} when the request may continue; a recorded policy deny still
     *         reaches the owning service, which enforces its own error and audit contract
     * @throws com.aurevia.api.ApiException when the command is unresolvable, unauthenticated
     *         or its decision could not be recorded; the gate's machine-readable status and
     *         code propagate to the shared error handler unchanged
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Interceptors are registered on path patterns shared by every HandlerMapping, including
        // the static-resource fallback. Only a controller method is a command; a resource or
        // view handler on a catalog-shaped path must keep its native 404 instead of a deny.
        if (!"POST".equals(request.getMethod()) || !(handler instanceof HandlerMethod)) {
            return true;
        }
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        // Production registration is limited to catalog paths, so this null side is
        // defence in depth: a future registration change can never turn an unresolved
        // route into an allow decision. The sentinel carries no client text.
        CommandDefinition definition = matched instanceof String route
                ? catalog.findByPath("POST", route).orElse(null)
                : null;
        TenantContext context = TenantContext.current();
        ServerIdentity identity = new ServerIdentity(
                context == null ? null : context.organizationId(),
                context == null ? null : context.subject(),
                context == null ? null : context.correlationId());
        CommandRequest command = definition == null
                ? new CommandRequest(UNKNOWN_COMMAND, null, null)
                : new CommandRequest(definition.id(), null, null);
        try {
            // The gate always records before answering, so a deny is durable even when
            // the owning service performs the user-facing enforcement below.
            authorization.authorize(command, identity);
        } catch (com.aurevia.api.ApiException denial) {
            // A recorded FORBIDDEN decision for a known command stays with its owning
            // service: it re-checks role, object and revision and writes its own audit, so
            // an early gate error would corrupt that contract (ARC-04) and idempotent
            // replay concealment. Audit failures and unresolvable commands carry other
            // codes and still fail closed here because no service can enforce them.
            if (definition != null && "FORBIDDEN".equals(denial.code())) {
                return true;
            }
            throw denial;
        }
        return true;
    }

    /**
     * Register the gate on exactly the catalogued POST routes.
     *
     * @param registry spring interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(catalog.paths().toArray(String[]::new)).order(ORDER);
    }
}
