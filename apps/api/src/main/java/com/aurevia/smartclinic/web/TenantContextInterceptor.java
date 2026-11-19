package com.aurevia.smartclinic.web;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.tenant.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Derives tenant context from the authenticated principal and an explicit
 * organization selection.
 *
 * <p>The synthetic subject header is accepted only under the
 * {@code synthetic-auth} profile and is not a production authentication
 * mechanism.</p>
 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern CORRELATION_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final Environment environment;

    /**
     * Create the interceptor.
     *
     * @param environment spring environment used for profile checks
     */
    public TenantContextInterceptor(Environment environment) {
        this.environment = environment;
    }

    /**
     * Validate headers and bind the request tenant context.
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

        if (!environment.acceptsProfiles(Profiles.of("synthetic-auth"))) {
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
        return true;
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
    }

    /**
     * Register the interceptor on protected API routes.
     *
     * @param registry spring interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }
}
