package com.aurevia.api.contract;

/**
 * The first successful HTTP response frozen under an idempotency key (ARC-03).
 *
 * <p>The replay cache stores only the status and a JSON body string. Strings are
 * immutable, so the value cannot be mutated after it is committed. No request
 * headers, bearer tokens, correlation identifiers or raw request bodies are
 * retained; the request is represented by its server-computed digest only.</p>
 *
 * @param statusCode the 2xx status returned by the first successful call
 * @param body the response body captured from that call, possibly empty
 */
public record StoredResponse(int statusCode, String body) {
}
