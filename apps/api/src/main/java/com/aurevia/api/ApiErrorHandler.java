package com.aurevia.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.aurevia.tenant.TenantContext;

/**
 * Uniform error contract for every protected route.
 *
 * <p>Never exposes stack traces or sensitive payloads; every body includes the
 * correlation identifier when one has been assigned.</p>
 */
@RestControllerAdvice
public class ApiErrorHandler {

    /**
     * Render a domain command failure.
     *
     * @param exception the raised command failure
     * @return response with the failure status and error body
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorBody(exception.code(), exception.getMessage(), correlation()));
    }

    /**
     * Map an unreadable request body to a uniform client error.
     *
     * <p>Jackson's failure detail can quote fragments of the submitted payload,
     * which may carry protected health information. Only a fixed detail is
     * returned; the stable machine code lets a client distinguish a binding
     * failure from a domain validation failure without parsing free text.</p>
     *
     * @param exception the body-reading failure raised during argument binding
     * @return HTTP 400 response with the uniform error body and correlation id
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(400)
                .body(new ErrorBody("MALFORMED_REQUEST_BODY", "request body could not be read", correlation()));
    }

    /**
     * Map a uniqueness violation to a conflict.
     *
     * @param exception the data-access exception
     * @return HTTP 409 response
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorBody> handleDuplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(409)
                .body(new ErrorBody("CONFLICT", "resource already exists", correlation()));
    }

    /**
     * Map invalid input to an unprocessable response.
     *
     * @param exception the invalid argument
     * @return HTTP 422 response
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.status(422)
                .body(new ErrorBody("INVALID_INPUT", exception.getMessage(), correlation()));
    }

    /**
     * Map a missing tenant context to a generic internal error.
     *
     * @param exception the missing-state failure
     * @return HTTP 500 response without implementation detail
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorBody> handleMissingContext(IllegalStateException exception) {
        return ResponseEntity.status(500)
                .body(new ErrorBody("INTERNAL_ERROR", "request could not be processed", correlation()));
    }

    /**
     * Read the correlation identifier from the current request context.
     *
     * @return the correlation id, or {@code unassigned} before context is set
     */
    private String correlation() {
        TenantContext context = TenantContext.current();
        return context == null ? "unassigned" : context.correlationId();
    }

    /**
     * Error payload shared by all failures.
     *
     * @param code          stable error code
     * @param message       non-sensitive detail
     * @param correlationId request correlation identifier
     * @return the immutable error body
     */
    public record ErrorBody(String code, String message, String correlationId) {
    }
}
