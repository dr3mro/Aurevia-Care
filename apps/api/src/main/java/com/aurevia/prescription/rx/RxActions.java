package com.aurevia.prescription.rx;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the RX-02 slice.
 *
 * <p>Prescribing and transmission reconciliation are clinician duties; a
 * pharmacy response and dispensing evidence belong to pharmacy staff and can
 * never be recorded by a prescriber. Administrative and audit roles may read
 * the trail but cannot drive a prescription. The matrix is paired with a fresh
 * membership and role check on every protected request in the domain service.</p>
 */
@Component
public class RxActions {

    /** Create a prescription draft. */
    public static final String CREATE = "RX_CREATE";
    /** Attest to a draft as the prescribing clinician. */
    public static final String ATTEST = "RX_ATTEST";
    /** Route an attested prescription through an approved channel. */
    public static final String TRANSMIT = "RX_TRANSMIT";
    /** Confirm delivery of a stable submission id. */
    public static final String CONFIRM = "RX_CONFIRM";
    /** Verify partner status for an ambiguous submission. */
    public static final String VERIFY = "RX_VERIFY";
    /** Retry a verified-absent submission with the same submission id. */
    public static final String RETRY = "RX_RETRY";
    /** Record a pharmacy acceptance, rejection or clarification. */
    public static final String PHARMACY_RESPONSE = "RX_PHARMACY_RESPONSE";
    /** Record partner-supplied dispensing evidence. */
    public static final String DISPENSE = "RX_DISPENSE";
    /** Record a renewal linked to the original submission. */
    public static final String RENEW = "RX_RENEW";
    /** Cancel a prescription with a reason. */
    public static final String CANCEL = "RX_CANCEL";
    /** Read a prescription or its event trail. */
    public static final String READ = "RX_READ";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case CREATE, ATTEST, TRANSMIT, VERIFY, RETRY, RENEW, CANCEL -> "clinician".equals(role);
            case PHARMACY_RESPONSE, DISPENSE -> "pharmacy_staff".equals(role);
            case CONFIRM -> "clinician".equals(role) || "pharmacy_staff".equals(role);
            case READ -> "clinician".equals(role) || "pharmacy_staff".equals(role)
                    || "tenant_admin".equals(role) || "privacy_auditor".equals(role);
            default -> false;
        };
    }
}
