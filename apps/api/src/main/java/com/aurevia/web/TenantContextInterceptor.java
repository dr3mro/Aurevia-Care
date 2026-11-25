package com.aurevia.web;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Derives tenant context from the authenticated principal and an explicit
 * organization selection.
 *
 * <p>The synthetic subject header is accepted only under the
 * {@code synthetic-auth} profile and is not a production authentication
 * mechanism. In production both a bearer {@code JwtAuthenticationToken} and a
 * browser {@code OAuth2AuthenticationToken} are resolved by the same claim
 * rules, so the tenant cannot be chosen from a request body.</p>
 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern CORRELATION_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** Static API documentation route that needs no sign-in (section 7). */
    private static final String PUBLIC_CONTRACT_PATH = "/api/v1/openapi.yaml";

    /** Shared limiter key for the anonymous contract route; it holds no identity. */
    private static final String PUBLIC_CONTRACT_LIMIT_KEY = "anonymous:openapi";

    private final Environment environment;
    private final RateLimiter rateLimiter;

    /**
     * Create the interceptor for direct unit use with a wall-clock limiter.
     *
     * @param environment spring environment used for profile and limit checks
     */
    public TenantContextInterceptor(Environment environment) {
        this(environment, new RateLimiter(Clock.systemUTC(),
                environment.getProperty("aurevia.rate-limit.per-minute", Integer.class, 600)));
    }

    /**
     * Create the interceptor with its instance-local limiter.
     *
     * @param environment spring environment used for profile checks
     * @param rateLimiter limiter keyed by organization and subject
     */
    @Autowired
    public TenantContextInterceptor(Environment environment, RateLimiter rateLimiter) {
        this.environment = environment;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Validate headers and bind the request tenant context.
     *
     * <p>The static contract route is the one exception: it is served without a
     * subject or organization, but still consumes a rate-limit allowance.</p>
     *
     * @param request  current HTTP request
     * @param response current HTTP response
     * @param handler  selected handler
     * @return {@code true} so the request proceeds
     * @throws ApiException when authentication or tenant selection is invalid
     */
    @Override
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "HRS_REQUEST_PARAMETER_TO_HTTP_HEADER",
            justification = "Correlation id is allowlist-validated before being echoed")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String correlation = request.getHeader("X-Correlation-Id");
        if (correlation == null || correlation.isBlank()) {
            correlation = UUID.randomUUID().toString();
        } else if (!CORRELATION_PATTERN.matcher(correlation).matches()) {
            throw new ApiException(422, "INVALID_CORRELATION_ID", "correlation id has invalid characters");
        }
        response.setHeader("X-Correlation-Id", correlation);
        // WEB-06: publish the validated correlation id so the structured console
        // formatter can stamp it on log lines emitted while this request runs.
        // The observability filter clears MDC in its own finally block on every
        // path; afterCompletion clears it on the normal completion path too.
        MDC.put("correlationId", correlation);

        if (PUBLIC_CONTRACT_PATH.equals(request.getRequestURI())) {
            enforceRateLimit(response, PUBLIC_CONTRACT_LIMIT_KEY);
            return true;
        }
        if (!environment.acceptsProfiles(Profiles.of("synthetic-auth"))) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String requestedOrganization = request.getHeader("X-Active-Organization");
            if (authentication instanceof JwtAuthenticationToken token) {
                TenantContext.set(new ClaimsTenantResolver().resolve(token.getToken().getClaims(),
                        requestedOrganization, correlation));
                enforceRateLimit(response);
                return true;
            }
            // The staff web browser session authenticates with OIDC user-info
            // attributes, which carry the same verified claims as the JWT path.
            if (authentication instanceof OAuth2AuthenticationToken browser) {
                OAuth2User principal = Objects.requireNonNull(browser.getPrincipal(),
                        "OAuth2 browser principal must be present");
                TenantContext.set(new ClaimsTenantResolver().resolve(
                        principal.getAttributes(), requestedOrganization, correlation));
                enforceRateLimit(response);
                return true;
            }
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        String subject = request.getHeader("X-Synthetic-Subject");
        if (subject == null || subject.isBlank()) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        String organization = request.getHeader("X-Active-Organization");
        if (organization == null || !UUID_PATTERN.matcher(organization).matches()) {
            throw new ApiException(422, "INVALID_TENANT_SELECTION", "an active organization must be selected");
        }
        TenantContext.set(new TenantContext(organization, subject, TenantContext.PURPOSE, correlation));
        enforceRateLimit(response);
        return true;
    }

    /**
     * Reject an over-limit call after the tenant context is bound (WEB-04).
     *
     * @param response current response, given the Retry-After header on rejection
     * @throws ApiException with status 429 when the key has used its allowance
     */
    private void enforceRateLimit(HttpServletResponse response) {
        TenantContext context = TenantContext.current();
        enforceRateLimit(response, context.organizationId() + ":" + context.subject());
    }

    /**
     * Reject an over-limit call made under an explicit key, used by public routes
     * that have no tenant context to key on (section 7).
     *
     * @param response current response, given the Retry-After header on rejection
     * @param key      stable limiter key; never a patient identifier
     * @throws ApiException with status 429 when the key has used its allowance
     */
    private void enforceRateLimit(HttpServletResponse response, String key) {
        long retryAfter = rateLimiter.retryAfterSeconds(key);
        if (retryAfter > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfter));
            // A throw from preHandle skips this interceptor's afterCompletion,
            // so clear the thread-local here to avoid leaking tenant identity
            // to the next request served on this servlet thread.
            TenantContext.clear();
            throw new ApiException(429, "RATE_LIMITED", "too many requests");
        }
    }

    /**
     * Clear the thread-local context at request completion.
     *
     * @param request   current HTTP request
     * @param response  current HTTP response
     * @param handler   selected handler
     * @param exception any exception raised during handling
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception exception) {
        TenantContext.clear();
        MDC.remove("correlationId");
    }

    /**
     * Register the interceptor on protected API routes.
     *
     * @param registry spring interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**").order(10);
    }
}
