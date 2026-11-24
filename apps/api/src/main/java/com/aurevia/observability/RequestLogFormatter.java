package com.aurevia.observability;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the single structured request-log line emitted for each HTTP request (WEB-06).
 *
 * <p>This is the only production code that decides what a request log may contain.
 * The field list is fixed by the five-argument signature and the five writes in
 * {@link com.aurevia.observability.RequestLogFormatter#toJson(String, String, String, int, long)}: {@code correlationId},
 * {@code method}, {@code route}, {@code status} and {@code latencyMs}. There is no
 * parameter for request or response bodies, headers, the query string or any user
 * value, so none of those can be added here. String values are emitted through a
 * JSON node tree, which escapes quotes, backslashes and control characters and so
 * prevents a caller-supplied token from breaking out of the line.</p>
 */
public final class RequestLogFormatter {

    private RequestLogFormatter() {
    }

    /**
     * Format one request-log line.
     *
     * @param correlationId opaque correlation id, already validated or generated
     * @param method        HTTP method from the servlet request
     * @param route         matched handler pattern, never the raw request path
     * @param status        HTTP status code of the completed response
     * @param latencyMs     elapsed whole milliseconds for the request
     * @return one JSON object with exactly the five fixed fields
     */
    public static String toJson(String correlationId, String method, String route, int status,
            long latencyMs) {
        ObjectNode line = JsonNodeFactory.instance.objectNode();
        line.put("correlationId", correlationId);
        line.put("method", method);
        line.put("route", route);
        line.put("status", status);
        line.put("latencyMs", latencyMs);
        return line.toString();
    }
}
