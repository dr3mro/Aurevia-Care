package com.aurevia.exchange.task;

import org.springframework.stereotype.Component;

/**
 * Bounded role matrix for the XCH-02 slice (IAM-01, PRD-06).
 *
 * <p>Kept inside the story package so a parallel worker cannot change the
 * shared action policy. Consolidation into the shared {@code ActionPolicy} is a
 * follow-up once the exchange module is settled. Clinical review is deliberately
 * clinician-only: a lab collector may record a delivery receipt, but cannot
 * acknowledge or resolve a clinical task.</p>
 */
@Component
public class ExchangeActionPolicy {

    /** Action key for opening an exchange work item. */
    public static final String CREATE = "EXCHANGE_CREATE";
    /** Action key for reading state and history. */
    public static final String READ = "EXCHANGE_READ";

    /**
     * Whether the role may open an exchange work item.
     *
     * @param role role key in the active organization
     * @return {@code true} when allowed
     */
    public boolean allowsCreate(String role) {
        return "tenant_admin".equals(role) || "clinician".equals(role);
    }

    /**
     * Whether the role may read state and history.
     *
     * @param role role key in the active organization
     * @return {@code true} when allowed
     */
    public boolean allowsRead(String role) {
        return "tenant_admin".equals(role) || "clinician".equals(role)
                || "lab_collector".equals(role) || "reception".equals(role);
    }

    /**
     * Whether the role may perform an action on a state channel.
     *
     * @param action action key
     * @param role   role key in the active organization
     * @return {@code true} when allowed
     */
    public boolean allows(String action, String role) {
        if (ExchangeDimension.WORKFLOW.policyAction().equals(action)) {
            return "lab_collector".equals(role) || "clinician".equals(role);
        }
        if (ExchangeDimension.TRANSMISSION.policyAction().equals(action)) {
            return "lab_collector".equals(role) || "clinician".equals(role)
                    || "tenant_admin".equals(role);
        }
        return ExchangeDimension.CLINICAL_REVIEW.policyAction().equals(action)
                && "clinician".equals(role);
    }
}
