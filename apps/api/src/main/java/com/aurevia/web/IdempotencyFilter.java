package com.aurevia.web;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aurevia.api.contract.ApiErrorCode;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.api.contract.IdempotencyLedger.Decision;
import com.aurevia.api.contract.IdempotencyLedger.Scope;
import com.aurevia.api.contract.IdempotencyResponseStore;
import com.aurevia.api.contract.RequestDigest;
import com.aurevia.api.contract.ResponseResolution;
import com.aurevia.api.contract.StoredResponse;
import com.aurevia.tenant.TenantContext;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Decides replay, conflict or first execution for keyed POST commands (ARC-03, ARC-04).
 *
 * <p>This is an MVC interceptor rather than a servlet filter because tenant
 * identity is bound by {@link com.aurevia.web.TenantContextInterceptor} inside
 * the dispatcher, and only an MVC interceptor can throw into the uniform
 * {@link com.aurevia.api.ApiErrorHandler}. It runs after the tenant interceptor
 * and before the handler. A replay first re-checks the caller's live
 * authorization; when it still holds, the frozen status and body are written
 * and {@code false} is returned so the handler never executes a second time.
 * Lapsed authorization is concealed with 404 and no cached body is disclosed.
 * A key reused with a different body answers 422 {@code IDEMPOTENCY_KEY_REUSED}.
 * Requests without the header are left exactly as they were.</p>
 */
@Component
public final class IdempotencyFilter implements HandlerInterceptor, WebMvcConfigurer {

    private final IdempotencyResponseStore store;
    private final ReplayAuthorization authorization;

    /**
     * Create the interceptor with the durable replay store and a live authorization check.
     *
     * @param store store that evaluates and freezes keyed responses
     * @param authorization live check that gates disclosure of a cached response
     */
    public IdempotencyFilter(IdempotencyResponseStore store, ReplayAuthorization authorization) {
        this.store = store;
        this.authorization = authorization;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String header = request.getHeader("Idempotency-Key");
        if (header == null || header.isBlank()) {
            return true;
        }
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        TenantContext context = TenantContext.current();
        if (context == null) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String route)) {
            return true;
        }
        IdempotencyKey key = IdempotencyKey.parse(header);
        String command = "POST " + route;
        Scope scope = new Scope(context.organizationId(), context.subject(), command);
        Object captured = request.getAttribute(IdempotencyBodyCaptureFilter.BODY_ATTRIBUTE);
        if (!(captured instanceof String payload)) {
            return true;
        }
        // The matched pattern hides path variables, and the query string is not
        // part of it either. Bind both so the same key cannot replay a different
        // resource merely because its template is shared.
        String canonical = request.getRequestURI() + "\n"
                + java.util.Objects.toString(request.getQueryString(), "") + "\n" + payload;
        RequestDigest digest = RequestDigest.of(scope.organizationId(), scope.subject(), command, canonical);
        ResponseResolution resolution = store.evaluate(scope, key, digest);

        if (resolution.decision() == Decision.REPLAY) {
            // The handler is not re-run on a replay, so its normal authorization
            // checks must be replaced by a fresh decision from live identity state.
            // Conceal lapsed authorization with 404 and never write the cached body.
            if (!authorization.stillAuthorized(context, command)) {
                throw ApiErrorCode.NOT_FOUND.exception();
            }
            StoredResponse frozen = resolution.response();
            response.setStatus(frozen.statusCode());
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(frozen.body().getBytes(StandardCharsets.UTF_8));
            return false;
        }
        if (resolution.decision() == Decision.CONFLICT) {
            throw ApiErrorCode.IDEMPOTENCY_KEY_REUSED.exception();
        }
        // These values are read after the dispatcher has cleared the thread-local
        // tenant context, so the capture filter never has to resolve identity.
        request.setAttribute(IdempotencyBodyCaptureFilter.STORE_ATTRIBUTE, Boolean.TRUE);
        request.setAttribute(IdempotencyBodyCaptureFilter.SCOPE_ATTRIBUTE, scope);
        request.setAttribute(IdempotencyBodyCaptureFilter.KEY_ATTRIBUTE, key);
        request.setAttribute(IdempotencyBodyCaptureFilter.DIGEST_ATTRIBUTE, digest);
        request.setAttribute(IdempotencyBodyCaptureFilter.TENANT_ATTRIBUTE, context);
        return true;
    }

    /**
     * Register after the tenant interceptor on every protected route.
     *
     * @param registry spring interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**").order(20);
    }
}
