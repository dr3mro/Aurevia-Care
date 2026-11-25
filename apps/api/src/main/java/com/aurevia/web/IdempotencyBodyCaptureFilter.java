package com.aurevia.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.api.contract.IdempotencyLedger.Scope;
import com.aurevia.api.contract.IdempotencyResponseStore;
import com.aurevia.api.contract.RequestDigest;
import com.aurevia.api.contract.StoredResponse;
import com.aurevia.tenant.TenantContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Buffers a keyed POST so its response can be frozen for a later replay (ARC-03).
 *
 * <p>Only POST requests carrying a non-blank {@code Idempotency-Key} enter this
 * path; every other request flows through the original request and response
 * objects unchanged. The body is read once before dispatch so the interceptor
 * can hash it, then replayed to the handler by
 * {@link com.aurevia.web.BufferedBodyRequestWrapper}. The response is cached
 * and copied back in a {@code finally} block so an error or an early replay
 * still reaches the caller. The scope, key and digest are captured by the
 * interceptor while the tenant context is live and are read from request
 * attributes here: the thread-local is cleared before this filter resumes.</p>
 */
@Component
@Order(0)
public final class IdempotencyBodyCaptureFilter extends OncePerRequestFilter {

    /** Fixed, non-sensitive diagnostic; payloads are never logged. */
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyBodyCaptureFilter.class);

    /** Upper bound for a buffered keyed request; larger bodies are not captured. */
    public static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    /** Raw request body, captured before the handler consumes the stream. */
    public static final String BODY_ATTRIBUTE = "aurevia.idempotency.body";
    /** Response wrapper used to read the final status and body. */
    public static final String RESPONSE_ATTRIBUTE = "aurevia.idempotency.response";
    /** Set only when a first attempt is allowed to freeze its successful response. */
    public static final String STORE_ATTRIBUTE = "aurevia.idempotency.store";
    /** Tenant, caller and command captured while the tenant context is bound. */
    public static final String SCOPE_ATTRIBUTE = "aurevia.idempotency.scope";
    /** Validated idempotency key for the call. */
    public static final String KEY_ATTRIBUTE = "aurevia.idempotency.key";
    /** Server-computed digest of the canonical request. */
    public static final String DIGEST_ATTRIBUTE = "aurevia.idempotency.digest";
    /** Tenant context captured while it was bound, needed by the guarded transaction. */
    public static final String TENANT_ATTRIBUTE = "aurevia.idempotency.tenant";

    private final IdempotencyResponseStore store;

    /**
     * Create the capture filter with the durable replay store.
     *
     * @param store store that freezes the first successful response
     */
    public IdempotencyBodyCaptureFilter(IdempotencyResponseStore store) {
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Idempotency-Key");
        String contentType = request.getContentType();
        // Multipart parts are resolved by the container from the original stream,
        // which cannot be rewound, so only bounded JSON payloads are captured.
        // Order 0 puts this after the security chain, never before authentication.
        if (header == null || header.isBlank()
                || !"POST".equals(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/")
                || contentType == null || !contentType.startsWith("application/json")
                || request.getContentLengthLong() > MAX_CAPTURE_BYTES) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        request.setAttribute(BODY_ATTRIBUTE, new String(body, StandardCharsets.UTF_8));
        ContentCachingResponseWrapper cached = new ContentCachingResponseWrapper(response);
        request.setAttribute(RESPONSE_ATTRIBUTE, cached);
        try {
            chain.doFilter(new BufferedBodyRequestWrapper(request, body), cached);
        } finally {
            try {
                freezeFirstSuccess(request, cached);
            } finally {
                cached.copyBodyToResponse();
            }
        }
    }

    /**
     * Freeze the response only for a claimed first call that completed with a
     * 2xx status and a non-empty body. Other outcomes release the key so a
     * corrected retry can proceed; an empty body is not valid JSONB content.
     *
     * @param request current request carrying the values captured in preHandle
     * @param cached response wrapper holding the final status and body
     */
    private void freezeFirstSuccess(HttpServletRequest request, ContentCachingResponseWrapper cached) {
        if (!Boolean.TRUE.equals(request.getAttribute(STORE_ATTRIBUTE))) {
            return;
        }
        int status = cached.getStatus();
        if (status / 100 != 2) {
            return;
        }
        String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
        if (body.isEmpty()) {
            return;
        }
        Scope scope = (Scope) request.getAttribute(SCOPE_ATTRIBUTE);
        IdempotencyKey key = (IdempotencyKey) request.getAttribute(KEY_ATTRIBUTE);
        RequestDigest digest = (RequestDigest) request.getAttribute(DIGEST_ATTRIBUTE);
        // The guarded transaction manager binds the tenant GUC from the
        // thread-local context, which the tenant interceptor has already
        // cleared when this filter resumes. Rebind the captured context for
        // the write only, then clear it so no pooled thread keeps the identity.
        TenantContext.set((TenantContext) request.getAttribute(TENANT_ATTRIBUTE));
        try {
            store.store(scope, key, digest, new StoredResponse(status, body));
        } catch (RuntimeException failure) {
            // The handler already committed a successful response. A ledger
            // failure must not turn it into a 500; the fixed diagnostic never
            // includes the payload, which could carry clinical data.
            LOG.warn("idempotency ledger write failed; original response preserved");
        } finally {
            TenantContext.clear();
        }
    }
}
