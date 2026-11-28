package com.aurevia.tenant;

/**
 * Immutable request-scoped tenant context, derived from authentication and an
 * explicitly selected organization; never inferred from client resources.
 *
 * @param organizationId active organization UUID as text
 * @param subject        authenticated synthetic subject
 * @param purpose        recorded business purpose
 * @param correlationId  request correlation identifier
 * @return this record
 */
public record TenantContext(String organizationId, String subject, String purpose, String correlationId) {

    /** Purpose recorded on facility administration audit events. */
    public static final String PURPOSE = "FACILITY_ADMIN";

    // Request-thread storage: a pooled thread must not carry an organization into the
    // next request, so the interceptor clears it after every call.
    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    /**
     * Bind a context to the current request thread.
     *
     * @param context the context to bind
     */
    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    /**
     * Return the bound context.
     *
     * @return the current context, or {@code null} when none is bound
     */
    public static TenantContext current() {
        return CURRENT.get();
    }

    /** Remove the bound context, typically at request completion. */
    public static void clear() {
        CURRENT.remove();
    }
}
