package com.aurevia.api;

/**
 * Parses the {@code If-Match} version precondition used by irreversible commands
 * (ARC-03, ARC-04).
 *
 * <p>A weak validator ({@code W/"3"}) and a quoted validator ({@code "3"}) are
 * accepted. A missing precondition is {@code 428}, a malformed one is
 * {@code 422}, and a stale version is decided by the caller's compare-and-set.</p>
 */
public final class IfMatch {

    private IfMatch() {
    }

    /**
     * Parse a version precondition.
     *
     * @param header raw {@code If-Match} header, possibly {@code null}
     * @return the expected positive version
     * @throws ApiException {@code 428} when absent and {@code 422} when malformed
     */
    public static int parse(String header) {
        if (header == null || header.isBlank()) {
            throw new ApiException(428, "PRECONDITION_REQUIRED", "an If-Match version is required");
        }
        String value = header.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        int version;
        try {
            version = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_IF_MATCH", "If-Match must be a positive integer version");
        }
        if (version < 1) {
            throw new ApiException(422, "INVALID_IF_MATCH", "If-Match must be a positive integer version");
        }
        return version;
    }
}
