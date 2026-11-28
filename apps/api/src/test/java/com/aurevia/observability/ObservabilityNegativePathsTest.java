package com.aurevia.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
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

import jakarta.servlet.FilterChain;
import tools.jackson.databind.JsonNode;

/**
 * Negative-path regression tests for the correlation-id servlet boundary (WEB-06).
 *
 * <p>Every case drives the real {@code CorrelationIdFilter.doFilter} entry point with
 * synthetic servlet mocks and asserts the single request-log line actually emitted.
 * These are defensive input variants: the production branches themselves are already
 * covered by {@code CorrelationIdFilterTest}. The tenant-shaped path case proves only
 * that raw path segments are redacted from the log; it is not tenant-isolation evidence.
 * The repeated-header case proves id reuse, not idempotency or replay deduplication.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityNegativePathsTest {

    private static final String VALID_ID = "111e1111-1111-1111-1111-111111111111";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC);

    /**
     * Remove any correlation id left in the thread-local diagnostic context so tests
     * cannot leak state into one another when a chain fails before cleanup.
     */
    @AfterEach
    void clearMdc() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    /**
     * A present but blank header must be replaced by one fresh opaque id, and that same
     * id must be visible in the response header, the request attribute and the log line.
     *
     * @param output captured console output for this test
     * @throws Exception when the filter or log extraction fails
     */
    @Test
    void generatesAndEchoesSingleIdWhenHeaderIsBlank(CapturedOutput output) throws Exception {
        String blank = "   ";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, blank);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter(clock).doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertNotEquals(blank, generated);
        assertEquals(generated, request.getAttribute(CorrelationIdFilter.CORRELATION_ATTRIBUTE));
        assertEquals(generated, RequestLogCapture.requestLog(output).get("correlationId").asString());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    /**
     * An oversized header carrying a PHI-shaped marker must be rejected as a correlation
     * id and must not reach the response, the request attribute, the structured log line
     * or any other captured console output.
     *
     * @param output captured console output for this test
     * @throws Exception when the filter or log extraction fails
     */
    @Test
    void neverEchoesLongPhiBearingHeader(CapturedOutput output) throws Exception {
        String marker = "ZZ-PHI-MARKER";
        String longHeader = "x".repeat(4096 - marker.length()) + marker;
        assertEquals(4096, longHeader.length());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, longHeader);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter(clock).doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertNotEquals(longHeader, generated);
        assertFalse(generated.contains(marker), "caller marker echoed in response header");
        Object attribute = request.getAttribute(CorrelationIdFilter.CORRELATION_ATTRIBUTE);
        assertNotNull(attribute);
        assertEquals(generated, attribute);
        assertFalse(String.valueOf(attribute).contains(marker),
                "caller marker echoed in request attribute");
        JsonNode line = RequestLogCapture.requestLog(output);
        assertEquals(generated, line.get("correlationId").asString());
        assertFalse(line.toString().contains(marker), "caller marker reached the structured log line");
        assertFalse(output.getAll().contains(marker), "caller marker reached captured output");
    }

    /**
     * A UUID-shaped value with trailing garbage must be rejected in full, not accepted
     * by its prefix, and replaced with a fresh id on every sink.
     *
     * @param output captured console output for this test
     * @throws Exception when the filter or log extraction fails
     */
    @Test
    void rejectsUuidWithTrailingGarbage(CapturedOutput output) throws Exception {
        String malformed = VALID_ID + "-ZZ";
        // Guard the fixture: a value Java's parser accepts would make this test meaningless.
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(malformed));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, malformed);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter(clock).doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(CorrelationIdFilter.CORRELATION_HEADER);
        assertNotNull(generated);
        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertNotEquals(malformed, generated);
        assertNotEquals(VALID_ID, generated);
        assertEquals(generated, request.getAttribute(CorrelationIdFilter.CORRELATION_ATTRIBUTE));
        assertEquals(generated, RequestLogCapture.requestLog(output).get("correlationId").asString());
    }

    /**
     * An IOException from the downstream chain must propagate while the correlation
     * header survives, the failure is still logged and the MDC is still cleared. This
     * differs from the existing ServletException case by exception type and by asserting
     * that the id was present inside the chain before cleanup.
     *
     * @param output captured console output for this test
     */
    @Test
    void propagatesIOExceptionAfterLoggingFailureResponse(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/patients");
        request.addHeader(CorrelationIdFilter.CORRELATION_HEADER, VALID_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] mdcInChain = new String[1];
        FilterChain throwing = (req, res) -> {
            mdcInChain[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            ((MockHttpServletResponse) res).setStatus(500);
            throw new IOException("synthetic downstream IO failure");
        };

        IOException thrown = assertThrows(IOException.class,
                () -> new CorrelationIdFilter(clock).doFilter(request, response, throwing));

        assertEquals("synthetic downstream IO failure", thrown.getMessage());
        assertEquals(VALID_ID, response.getHeader(CorrelationIdFilter.CORRELATION_HEADER));
        assertEquals(VALID_ID, mdcInChain[0]);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
        JsonNode line = RequestLogCapture.requestLog(output);
        assertEquals(VALID_ID, line.get("correlationId").asString());
        assertEquals(500, line.get("status").asInt());
    }

    /**
     * A raw path shaped like an organization and record identifier must be replaced by
     * the constant route label when no handler pattern was matched, and none of its
     * segments may reach the log. This is WEB-06/WEB-02 redaction evidence only: no
     * tenant context, policy decision or RLS boundary is exercised here.
     *
     * @param output captured console output for this test
     * @throws Exception when the filter or log extraction fails
     */
    @Test
    void unmatchedTenantShapedRawPathIsRedacted(CapturedOutput output) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/patients/org-2/rec-9");
        // No BEST_MATCHING_PATTERN_ATTRIBUTE: the filter must fall back to the constant
        // label rather than echo segments that resemble tenant-owned identifiers.
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter(clock).doFilter(request, response, (req, res) -> { });

        JsonNode line = RequestLogCapture.requestLog(output);
        assertEquals(CorrelationIdFilter.UNMATCHED_ROUTE, line.get("route").asString());
        assertFalse(line.toString().contains("org-2"), "raw org segment reached the log line");
        assertFalse(line.toString().contains("rec-9"), "raw record segment reached the log line");
        assertFalse(output.getAll().contains("org-2"), "raw org segment reached captured output");
        assertFalse(output.getAll().contains("rec-9"), "raw record segment reached captured output");
    }

    /**
     * Two requests carrying the same valid header must each use that caller-supplied id,
     * with no second mint. This proves id reuse only; the filter has no key, store or
     * side-effect suppression, so it is not idempotency or replay-dedup evidence.
     *
     * @param output captured console output for this test
     * @throws Exception when the filter or log extraction fails
     */
    @Test
    void reusesTheSameCallerIdAcrossTwoRequests(CapturedOutput output) throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/patients");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        String[] firstMdc = new String[1];
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/patients");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        String[] secondMdc = new String[1];
        first.addHeader(CorrelationIdFilter.CORRELATION_HEADER, VALID_ID);
        second.addHeader(CorrelationIdFilter.CORRELATION_HEADER, VALID_ID);

        new CorrelationIdFilter(clock).doFilter(first, firstResponse, (req, res) ->
                firstMdc[0] = MDC.get(CorrelationIdFilter.MDC_KEY));
        new CorrelationIdFilter(clock).doFilter(second, secondResponse, (req, res) ->
                secondMdc[0] = MDC.get(CorrelationIdFilter.MDC_KEY));

        assertEquals(VALID_ID, firstResponse.getHeader(CorrelationIdFilter.CORRELATION_HEADER));
        assertEquals(VALID_ID, secondResponse.getHeader(CorrelationIdFilter.CORRELATION_HEADER));
        assertEquals(VALID_ID, first.getAttribute(CorrelationIdFilter.CORRELATION_ATTRIBUTE));
        assertEquals(VALID_ID, second.getAttribute(CorrelationIdFilter.CORRELATION_ATTRIBUTE));
        assertEquals(VALID_ID, firstMdc[0]);
        assertEquals(VALID_ID, secondMdc[0]);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
        assertEquals(VALID_ID, RequestLogCapture.requestLog(output).get("correlationId").asString());
        assertEquals(2, output.getOut().lines()
                .filter(candidate -> candidate.contains("CorrelationIdFilter")
                        && candidate.contains(VALID_ID))
                .count());
    }
}
