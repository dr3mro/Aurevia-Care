package com.aurevia.messaging.instructions;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for MSG-04.
 *
 * <p>Only staff can author instructions; patients read them through their
 * verified connection. Free text and clinical templates require a clinician,
 * while administrative template approval is separated from clinical sending.
 * The matrix is paired with a fresh membership and role check on every
 * protected command in the service.</p>
 */
@Component
public class InstructionActions {

    /** Approve a versioned unit template. */
    public static final String DEFINE_TEMPLATE = "INSTRUCTION_DEFINE_TEMPLATE";
    /** Send an instruction from an approved template. */
    public static final String SEND = "INSTRUCTION_SEND";
    /** Send free text or a clinical template; clinicians only. */
    public static final String CLINICAL_SEND = "INSTRUCTION_CLINICAL_SEND";
    /** Withdraw or replace a published revision. */
    public static final String WITHDRAW = "INSTRUCTION_WITHDRAW";
    /** Read a thread or a patient's instruction list. */
    public static final String READ = "INSTRUCTION_READ";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case DEFINE_TEMPLATE -> "tenant_admin".equals(role);
            case SEND, WITHDRAW -> staffSender(role);
            case CLINICAL_SEND -> "clinician".equals(role);
            case READ -> staffSender(role) || "tenant_admin".equals(role)
                    || "privacy_auditor".equals(role) || "patient".equals(role);
            default -> false;
        };
    }

    private boolean staffSender(String role) {
        return "clinician".equals(role) || "clinical_assistant".equals(role)
                || "lab_collector".equals(role) || "radiology_staff".equals(role)
                || "pt_clinician".equals(role) || "pharmacy_staff".equals(role);
    }
}
