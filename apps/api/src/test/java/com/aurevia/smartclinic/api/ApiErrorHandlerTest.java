package com.aurevia.smartclinic.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.aurevia.smartclinic.tenant.TenantContext;

class ApiErrorHandlerTest {

    private final ApiErrorHandler handler = new ApiErrorHandler();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void apiExceptionKeepsItsStatusAndCode() {
        var response = handler.handleApi(new ApiException(403, "FORBIDDEN", "denied"));
        assertEquals(403, response.getStatusCode().value());
        assertEquals("FORBIDDEN", response.getBody().code());
        assertEquals("unassigned", response.getBody().correlationId());
    }

    @Test
    void duplicateKeyBecomesConflict() {
        var response = handler.handleDuplicate(new org.springframework.dao.DuplicateKeyException("dup"));
        assertEquals(409, response.getStatusCode().value());
        assertEquals("CONFLICT", response.getBody().code());
    }

    @Test
    void invalidInputBecomesUnprocessable() {
        var response = handler.handleInvalid(new IllegalArgumentException("bad"));
        assertEquals(422, response.getStatusCode().value());
        assertEquals("INVALID_INPUT", response.getBody().code());
    }

    @Test
    void missingContextBecomesInternalErrorWithCorrelation() {
        TenantContext.set(new TenantContext("org", "subject", "purpose", "corr-9"));
        var response = handler.handleMissingContext(new IllegalStateException("no context"));
        assertEquals(500, response.getStatusCode().value());
        assertEquals("corr-9", response.getBody().correlationId());
    }

    @Test
    void malformedBodyReturnsBadRequestWithMachineCode() {
        TenantContext.set(new TenantContext("org", "subject", "purpose", "corr-body"));
        var response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("bad payload fragment", null));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("MALFORMED_REQUEST_BODY", response.getBody().code());
        // A fixed detail proves the binding exception text (which may contain
        // payload fragments) is never echoed to the caller.
        assertEquals("request body could not be read", response.getBody().message());
        assertEquals("corr-body", response.getBody().correlationId());
    }

    @Test
    void errorBodyExposesComponents() {
        var body = new ApiErrorHandler.ErrorBody("CODE", "message", "corr");
        assertEquals(List.of("CODE", "message", "corr"),
                List.of(body.code(), body.message(), body.correlationId()));
    }
}
