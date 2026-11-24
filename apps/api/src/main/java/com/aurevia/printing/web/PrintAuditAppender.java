package com.aurevia.printing.web;

import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * Writes print facts through the shared OPS-02 audit appender (CLN-07, OPS-02).
 *
 * <p>The appender leaves {@code actor_id} to the database trigger, which derives
 * it from the transaction-local {@code app.actor} binding set by the tenant
 * guard; no caller-supplied subject can attribute an action to another user.
 * The reason becomes the row's purpose, the facility of the record its scope,
 * and the resource id is a bounded colon-separated list of identifiers. No
 * handout bytes, clinical text, patient name or identifier is written.</p>
 */
@Component
public final class PrintAuditAppender implements PrintAuditTrail {

    /** Audit resource type for every handout fact. */
    public static final String RESOURCE_TYPE = "HANDOUT";

    private final AuditAppender audit;

    /**
     * Create the trail over the shared audit appender.
     *
     * @param audit transactional OPS-02 append boundary
     */
    public PrintAuditAppender(AuditAppender audit) {
        this.audit = audit;
    }

    /**
     * Append one sanitized fact using the request's tenant context.
     *
     * @param entry sanitized print facts
     * @throws ApiException when no tenant context is bound
     */
    @Override
    public void record(PrintAuditEntry entry) {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        audit.append(context, entry.reason().wire(), entry.action(), RESOURCE_TYPE,
                entry.facility(), resourceId(entry), entry.outcome());
    }

    private static String resourceId(PrintAuditEntry entry) {
        return entry.kind().wire() + ":" + entry.handoutId() + ":v" + entry.version()
                + ":r" + entry.sourceRevision();
    }
}
