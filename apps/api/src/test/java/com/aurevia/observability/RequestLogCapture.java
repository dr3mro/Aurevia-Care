package com.aurevia.observability;

import org.springframework.boot.test.system.CapturedOutput;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Test helper that extracts the single structured request-log line emitted by
 * {@link com.aurevia.observability.CorrelationIdFilter} from captured
 * console output (WEB-06).
 *
 * <p>The same filter emits two console shapes depending on which logging system
 * was initialised in the test JVM: an isolated unit test uses logback's plain
 * pattern ({@code ... CorrelationIdFilter -- {"correlationId":...}}), while any
 * Spring context in the same fork applies the {@code ecs} console format from
 * {@code application.yml}, which wraps that JSON in an ECS envelope whose
 * {@code message} field holds the escaped request-log JSON. Spring's own init
 * lines can also carry a top-level MDC {@code correlationId}, so selecting a
 * line by that key alone is not sufficient. This helper selects the line by its
 * logger name and then unwraps either shape, so tests are deterministic regardless
 * of execution order in the full suite.</p>
 */
public final class RequestLogCapture {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestLogCapture() {
    }

    /**
     * Find and parse the request-log line emitted by the correlation filter.
     *
     * @param output captured console output for the current test
     * @return the parsed request-log object with its five fixed fields
     * @throws JacksonException when no filter line was captured or the line is not valid JSON
     */
    public static JsonNode requestLog(CapturedOutput output) throws JacksonException {
        String line = output.getOut().lines()
                .filter(candidate -> candidate.contains("CorrelationIdFilter"))
                .filter(candidate -> candidate.contains("correlationId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no CorrelationIdFilter request log captured: " + output.getAll()));
        int start = line.indexOf('{');
        int end = line.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new AssertionError("no JSON object in captured log line: " + line);
        }
        JsonNode outer = MAPPER.readTree(line.substring(start, end + 1));
        JsonNode message = outer.get("message");
        if (message != null && message.isTextual()) {
            return MAPPER.readTree(message.asString());
        }
        return outer;
    }
}
