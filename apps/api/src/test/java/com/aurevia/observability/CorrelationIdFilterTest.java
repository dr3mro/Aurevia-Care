package com.aurevia.observability;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import tools.jackson.databind.JsonNode;

/**
 * WEB-06 servlet-boundary tests for the correlation id and request log filter.
 *
 * <p>Every request is run through the real {@code doFilter} entry point with a
 * synthetic request and a stub chain. The captured console output is the actual
 * log line the filter emits, so these tests prove both the correlation contract
 * and that no query string, body, header or raw path value reaches the log.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class CorrelationIdFilterTest {

    private static final String VALID_ID = "111e1111-1111-1111-1111-111111111111";
    private static final String HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);

    @AfterEach
    void clearMdc() {
        MDC.remove(MDC_KEY);
    }

    @Test
    void usesValidHeaderAndEchoesIt(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(HEADER, VALID_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];
        FilterChain chain = (req, res) -> seenInChain[0] = MDC.get(MDC_KEY);

        new CorrelationIdFilter(clock).doFilter(request, response, chain);

        assertEquals(VALID_ID, response.getHeader(HEADER));
        assertEquals(VALID_ID, seenInChain[0]);
        assertEquals(VALID_ID, RequestLogCapture.requestLog(output).get("correlationId").asString());
    }

    @Test
    void generatesIdWhenHeaderIsMissing(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seenInChain = new String[1];
        FilterChain chain = (req, res) -> seenInChain[0] = MDC.get(MDC_KEY);

        new CorrelationIdFilter(clock).doFilter(request, response, chain);

        String generated = response.getHeader(HEADER);
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertEquals(generated, seenInChain[0]);
        assertEquals(generated, RequestLogCapture.requestLog(output).get("correlationId").asString());
    }

    @Test
    void generatesIdWhenHeaderIsNotAUuid(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(HEADER, "not-a-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter(clock).doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(HEADER);
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertNotEquals("not-a-uuid", generated);
        assertEquals(generated, RequestLogCapture.requestLog(output).get("correlationId").asString());
    }

    @Test
    void logsMethodMatchedRouteStatusAndLatency(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/patients/ZZ-PATIENT-MARKER");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/patients/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(201);

        new CorrelationIdFilter(clock).doFilter(request, response, chain);

        JsonNode line = RequestLogCapture.requestLog(output);
        assertEquals("POST", line.get("method").asString());
        assertEquals("/api/v1/patients/{id}", line.get("route").asString());
        assertEquals(201, line.get("status").asInt());
        assertEquals(0, line.get("latencyMs").asLong());
        assertEquals(5, line.size());
        assertEquals(1, output.getOut().lines()
                .filter(candidate -> candidate.contains("CorrelationIdFilter"))
                .count());
    }

    @Test
    void usesConstantRouteWhenNoHandlerPatternWasMatched(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health/live");

        new CorrelationIdFilter(clock).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertEquals("UNMATCHED", RequestLogCapture.requestLog(output).get("route").asString());
    }

    @Test
    void neverLogsQueryBodyHeaderOrRawPathValues(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/patients/ZZ-PATIENT-MARKER");
        request.setQueryString("name=ZZ-PATIENT-MARKER&note=ZZ-PHI-MARKER");
        request.addHeader("Authorization", "Bearer ZZ-TOKEN-MARKER");
        request.setContent("{\"display\":\"ZZ-PATIENT-MARKER\",\"token\":\"ZZ-TOKEN-MARKER\"}"
                .getBytes(UTF_8));
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/patients/{id}");

        new CorrelationIdFilter(clock).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        String all = output.getAll();
        assertFalse(all.contains("ZZ-PATIENT-MARKER"), all);
        assertFalse(all.contains("ZZ-PHI-MARKER"), all);
        assertFalse(all.contains("ZZ-TOKEN-MARKER"), all);
        assertFalse(all.contains("name="), all);
        assertFalse(all.contains("Authorization"), all);
        assertFalse(all.contains("/api/v1/patients/ZZ"), all);
    }

    @Test
    void clearsMdcAfterASuccessfulRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(HEADER, VALID_ID);

        new CorrelationIdFilter(clock).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertNull(MDC.get(MDC_KEY));
    }

    @Test
    void clearsMdcAndLogsWhenTheChainThrows(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(HEADER, VALID_ID);
        FilterChain throwing = (req, res) -> {
            ((MockHttpServletResponse) res).setStatus(500);
            throw new ServletException("synthetic downstream failure");
        };

        ServletException thrown = assertThrows(ServletException.class,
                () -> new CorrelationIdFilter(clock).doFilter(request, new MockHttpServletResponse(), throwing));

        assertEquals("synthetic downstream failure", thrown.getMessage());
        assertNull(MDC.get(MDC_KEY));
        assertEquals(VALID_ID, RequestLogCapture.requestLog(output).get("correlationId").asString());
        assertEquals(500, RequestLogCapture.requestLog(output).get("status").asInt());
    }
}
