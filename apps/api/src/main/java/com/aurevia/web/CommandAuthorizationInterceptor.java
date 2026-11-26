package com.aurevia.web;

import java.util.Objects;

import org.springframework.stereotype.Component;
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
 * tenant context. This interceptor is stateless: a denial thrown from {@code preHandle}
 * skips its own completion callbacks, so it keeps no per-request state.</p>
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
     * @param handler  selected handler, unused; the matched route is read from the request
     * @return {@code true} only when the gate returned an allow decision
     * @throws com.aurevia.api.ApiException with the gate's machine-readable status and code
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) {
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
        String commandId = definition == null ? UNKNOWN_COMMAND : definition.id();
        // The gate records the decision before throwing, and a sink failure denies rather than
        // allowing the handler to run. ApiException propagates to ApiErrorHandler unchanged.
        authorization.authorize(new CommandRequest(commandId, null, null), identity);
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
