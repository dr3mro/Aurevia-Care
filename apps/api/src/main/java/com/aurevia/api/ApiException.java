package com.aurevia.api;

/**
 * Machine-readable command failure carrying an explicit HTTP status and error code.
 *
 * <p>Instances are raised at domain or web boundaries and rendered by
 * the API error handler into a uniform response body.</p>
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    /**
     * Create a command failure.
     *
     * @param status  HTTP status to return
     * @param code    stable machine-readable error code
     * @param message human-readable, non-sensitive detail
     */
    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * Return the HTTP status.
     *
     * @return HTTP status code
     */
    public int status() {
        return status;
    }

    /**
     * Return the machine-readable error code.
     *
     * @return stable error code
     */
    public String code() {
        return code;
    }
}
