package com.aurevia.smartclinic.tenant;

/** Immutable request-scoped tenant context; never inferred from client resources. */
public record TenantContext(String organizationId, String subject, String purpose, String correlationId) {

    public static final String PURPOSE = "FACILITY_ADMIN";

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    public static void set(TenantContext context) {
        CURRENT.set(context);
    }

    public static TenantContext current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
