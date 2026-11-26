package com.aurevia.api.contract;

/**
 * Machine-readable error codes owned by the cross-cutting API contract (ARC-03, ARC-04).
 *
 * <p>These are the codes raised by the version, idempotency, digest, cursor and
 * paging primitives. Other codes in the uniform {@code {code, message,
 * correlationId}} body remain owned by their modules (for example
 * {@code PRECONDITION_REQUIRED} by {@code IfMatch} and
 * {@code INVALID_CORRELATION_ID} by the tenant interceptor). Every instance is
 * an {@link com.aurevia.api.ApiException}, so the existing single
 * {@code RestControllerAdvice} renders it without a second handler.</p>
 */
public enum ApiErrorCode {

    /** The requested API version is not routed. */
    UNSUPPORTED_API_VERSION(422, "unsupported API version"),
    /** A protected command requires an idempotency key. */
    IDEMPOTENCY_KEY_REQUIRED(428, "an idempotency key is required"),
    /** The idempotency key is malformed. */
    INVALID_IDEMPOTENCY_KEY(422, "idempotency key has invalid characters or length"),
    /** The key was reused with a different payload or is still in progress. */
    IDEMPOTENCY_CONFLICT(409, "the idempotency key was reused or is still in progress"),
    /** A replay key was reused with a different request body on a write endpoint. */
    IDEMPOTENCY_KEY_REUSED(422, "idempotency key reused with a different payload"),
    /** The tenant, caller or command scope of a key is incomplete. */
    INVALID_SCOPE(422, "idempotency scope is incomplete"),
    /** An opaque cursor could not be decoded or belongs to another tenant. */
    INVALID_CURSOR(422, "cursor is invalid"),
    /** A page limit is absent-invalid or outside the allowed range. */
    INVALID_LIMIT(422, "limit must be between 1 and 200"),
    /** The resource is concealed because it is inaccessible. */
    NOT_FOUND(404, "resource not found"),
    /** A version precondition did not match the stored revision. */
    STALE_VERSION(412, "the resource version is stale"),
    /** An irreversible transition is not legal from the current state. */
    INVALID_TRANSITION(409, "invalid state transition");

    private final int status;
    private final String message;

    ApiErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * Return the HTTP status for this code.
     *
     * @return HTTP status code
     */
    public int status() {
        return status;
    }

    /**
     * Return the non-sensitive detail message.
     *
     * @return stable message text
     */
    public String message() {
        return message;
    }

    /**
     * Build the machine-readable failure for this code.
     *
     * @return an exception rendered by the existing uniform error handler
     */
    public com.aurevia.api.ApiException exception() {
        return new com.aurevia.api.ApiException(status, name(), message);
    }
}
