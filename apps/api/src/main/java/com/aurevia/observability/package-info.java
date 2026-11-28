/**
 * Per-request correlation and the single structured request-log line.
 *
 * <p>This package owns correlation-id resolution, its publication to the
 * request context and the response header, matched-handler route selection and
 * the fixed five-field JSON log line. A valid UUID header is reused; a missing
 * or malformed value is replaced with a freshly generated identifier. The log
 * line carries no body, header, query-string or user value, and the correlation
 * context is cleared even when the request fails.</p>
 *
 * <p>The package must not depend on domain packages such as exchange work
 * items or trends, on tenant identity context, or on raw request text in logs.
 * It resolves one identifier for downstream consumers; it does not itself
 * guarantee that every downstream component consumes that same value.</p>
 */
package com.aurevia.observability;
