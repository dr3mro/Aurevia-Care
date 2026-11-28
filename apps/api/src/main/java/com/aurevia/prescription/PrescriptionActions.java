package com.aurevia.prescription;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the prescription slice.
 *
 * <p>Draft creation and medication selection are prescribing acts reserved to a
 * clinician. Catalog and allergy decision support are readable by clinical and
 * governance roles. The matrix is paired with a fresh membership and role fold
 * on every protected request in the domain service.</p>
 */
@Component
public class PrescriptionActions {

    /** Create a prescription draft. */
    public static final String CREATE_DRAFT = "RX_CREATE_DRAFT";
    /** Read a draft and its immutable override record. */
    public static final String READ_DRAFT = "RX_READ_DRAFT";
    /** Search the approved effective drug catalog. */
    public static final String SEARCH_CATALOG = "RX_SEARCH_CATALOG";
    /** Run an allergy check for a candidate drug. */
    public static final String CHECK_ALLERGIES = "RX_CHECK_ALLERGIES";
    /** Select a medication, optionally documenting an override. */
    public static final String SELECT_MEDICATION = "RX_SELECT_MEDICATION";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case CREATE_DRAFT, SELECT_MEDICATION -> "clinician".equals(role);
            case READ_DRAFT -> "clinician".equals(role) || "tenant_admin".equals(role)
                    || "privacy_auditor".equals(role);
            case SEARCH_CATALOG, CHECK_ALLERGIES -> "clinician".equals(role)
                    || "tenant_admin".equals(role) || "privacy_auditor".equals(role);
            default -> false;
        };
    }
}
