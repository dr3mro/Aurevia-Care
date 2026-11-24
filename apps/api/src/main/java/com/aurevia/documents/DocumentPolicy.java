package com.aurevia.documents;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for document commands (CLN-07, IAM-01).
 *
 * <p>This slice owns its own matrix so it stays inside the S-G1-07 boundary;
 * the shared {@code com.aurevia.authz.ActionPolicy} is reused only
 * for the role keys it already defines. Reception may attach and read originals,
 * while rendering a clinical PDF and voiding an association are reserved for
 * clinicians. Following the V1 role table, tenant administration and privacy
 * audit are administrative functions and carry no clinical document access by
 * default. Consolidating this matrix into the shared policy is a follow-on once
 * the document surface is broader.</p>
 */
@Component
public class DocumentPolicy {

    /** Attach an original to a chart, order or result. */
    public static final String DOCUMENT_LINK = "DOCUMENT_LINK";
    /** Read an association or a rendered revision. */
    public static final String DOCUMENT_READ = "DOCUMENT_READ";
    /** Generate an immutable localized PDF revision. */
    public static final String DOCUMENT_RENDER = "DOCUMENT_RENDER";
    /** Void an association with a recorded reason. */
    public static final String DOCUMENT_VOID = "DOCUMENT_VOID";

    private static final Set<String> CLINICIAN = Set.of(
            DOCUMENT_LINK, DOCUMENT_READ, DOCUMENT_RENDER, DOCUMENT_VOID);
    private static final Set<String> RECEPTION = Set.of(DOCUMENT_LINK, DOCUMENT_READ);

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key declared above
     * @param role   role key assigned in the active organization
     * @return {@code true} only for an explicitly allowed pair
     */
    public boolean allows(String action, String role) {
        if (action == null) {
            return false;
        }
        return switch (role == null ? "" : role) {
            case "clinician" -> CLINICIAN.contains(action);
            case "reception" -> RECEPTION.contains(action);
            default -> false;
        };
    }
}
