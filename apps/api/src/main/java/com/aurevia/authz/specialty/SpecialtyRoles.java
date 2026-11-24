package com.aurevia.authz.specialty;

import java.util.Set;

/**
 * Stable specialty role keys used by the S-G4-06 permission matrix (IAM-01, IAM-02).
 *
 * <p>The keys mirror the specification's role table. They are matched against
 * the authoritative active-role fold of the active organization on every
 * protected request; presenting a key in a request never grants it.</p>
 */
public final class SpecialtyRoles {

    /** Accessions, collects and rejects specimens. */
    public static final String LAB_COLLECTOR = "lab_collector";
    /** Verifies, releases and corrects results; separation of duties from collection. */
    public static final String LAB_VERIFIER = "lab_verifier";
    /** Schedules studies and associates external imaging with a verified patient. */
    public static final String RADIOLOGY_STAFF = "radiology_staff";
    /** Signs interpretations and addenda with verified imaging privileges. */
    public static final String RADIOLOGY_INTERPRETER = "radiology_interpreter";
    /** Accepts therapy referrals and signs progress and discharge notes. */
    public static final String PT_CLINICIAN = "pt_clinician";
    /** Reviews explicitly routed prescriptions and records status evidence. */
    public static final String PHARMACY_STAFF = "pharmacy_staff";
    /** General clinical role allowed to attest under the approved permissions. */
    public static final String CLINICIAN = "clinician";
    /** Organization administrator without clinical attestation rights. */
    public static final String TENANT_ADMIN = "tenant_admin";
    /** Privacy auditor with metadata read but no clinical content access. */
    public static final String PRIVACY_AUDITOR = "privacy_auditor";
    /** Clerical role with no clinical or attestation rights. */
    public static final String RECEPTION = "reception";

    /** Every recognized role key; an unknown key is never granted a permission. */
    public static final Set<String> ALL = Set.of(
            LAB_COLLECTOR, LAB_VERIFIER, RADIOLOGY_STAFF, RADIOLOGY_INTERPRETER,
            PT_CLINICIAN, PHARMACY_STAFF, CLINICIAN, TENANT_ADMIN, PRIVACY_AUDITOR, RECEPTION);

    private SpecialtyRoles() {
    }
}
