package com.aurevia.api.contract;

/**
 * The single API version routed by the platform (ARC-03).
 *
 * <p>Only {@code /api/v1} is registered by the tenant interceptor. An absent or
 * blank version header selects the default; an explicit unsupported version is
 * rejected with the machine-readable {@code UNSUPPORTED_API_VERSION} error
 * before any command is evaluated. This enum does not create new routes —
 * adding a future version requires a separate web-layer change.</p>
 */
public enum ApiVersion {

    /** The only supported V1 route family. */
    V1("/api/v1");

    private final String route;

    ApiVersion(String route) {
        this.route = route;
    }

    /**
     * Return the route prefix for this version.
     *
     * @return route prefix such as {@code /api/v1}
     */
    public String route() {
        return route;
    }

    /**
     * Resolve a version header to a supported version.
     *
     * @param header raw version header, possibly {@code null} or blank
     * @return the supported version, defaulting to
     *         {@link com.aurevia.api.contract.ApiVersion#V1}
     * @throws com.aurevia.api.ApiException {@code 422} with code
     *         {@code UNSUPPORTED_API_VERSION} when the version is not supported
     */
    public static ApiVersion from(String header) {
        if (header == null || header.isBlank()) {
            return V1;
        }
        String value = header.trim();
        if (value.equalsIgnoreCase(V1.name())) {
            return V1;
        }
        throw ApiErrorCode.UNSUPPORTED_API_VERSION.exception();
    }
}
