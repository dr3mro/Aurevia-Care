package com.aurevia.smartclinic.api;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.aurevia.smartclinic.tenant.TenantContext;

/** Uniform error contract for every protected route. */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorBody(exception.code(), exception.getMessage(), correlation()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorBody> handleDuplicate(DuplicateKeyException exception) {
        return ResponseEntity.status(409)
                .body(new ErrorBody("CONFLICT", "resource already exists", correlation()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleInvalid(IllegalArgumentException exception) {
        return ResponseEntity.status(422)
                .body(new ErrorBody("INVALID_INPUT", exception.getMessage(), correlation()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorBody> handleMissingContext(IllegalStateException exception) {
        return ResponseEntity.status(500)
                .body(new ErrorBody("INTERNAL_ERROR", "request could not be processed", correlation()));
    }

    private String correlation() {
        TenantContext context = TenantContext.current();
        return context == null ? "unassigned" : context.correlationId();
    }

    /** Error payload shared by all failures. */
    public record ErrorBody(String code, String message, String correlationId) {
    }
}
