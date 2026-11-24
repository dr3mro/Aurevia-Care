package com.aurevia.radiology;

import java.util.Set;

/**
 * Pure role-boundary rules for the RAD-01 imaging workflow.
 *
 * <p>Roles are supplied by the caller from the active tenant membership and are
 * re-derived on every protected request. A role grant that has been deactivated
 * is therefore denied on the next command. The checks mirror the section 2 role
 * table: reception may receive and schedule, radiology staff may accession and
 * associate a study, and only an interpreter with verified imaging privileges
 * may sign an interpretation or addendum.</p>
 */
public interface RadRules {

    /** Role allowed to receive and schedule an imaging order. */
    String RECEPTION = "reception";
    /** Role allowed to accession and associate an external study. */
    String RADIOLOGY_STAFF = "radiology_staff";
    /** Role allowed to sign interpretations and addenda. */
    String INTERPRETER = "radiology_interpreter";

    /**
     * Whether the subject may intake an imaging order.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for reception or radiology staff
     */
    static boolean mayIntake(Set<String> roles) {
        return roles.contains(RECEPTION) || roles.contains(RADIOLOGY_STAFF);
    }

    /**
     * Whether the subject may schedule an imaging order.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for reception or radiology staff
     */
    static boolean maySchedule(Set<String> roles) {
        return mayIntake(roles);
    }

    /**
     * Whether the subject may assign an issuer-scoped accession.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for radiology staff
     */
    static boolean mayAccession(Set<String> roles) {
        return roles.contains(RADIOLOGY_STAFF);
    }

    /**
     * Whether the subject may associate an external study.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for radiology staff
     */
    static boolean mayAssociate(Set<String> roles) {
        return roles.contains(RADIOLOGY_STAFF);
    }

    /**
     * Whether the subject may sign an interpretation or addendum.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for an interpreter
     */
    static boolean mayInterpret(Set<String> roles) {
        return roles.contains(INTERPRETER);
    }

    /**
     * Whether the subject may record a critical communication.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for an interpreter or radiology staff
     */
    static boolean mayRecordCritical(Set<String> roles) {
        return roles.contains(INTERPRETER) || roles.contains(RADIOLOGY_STAFF);
    }
}
