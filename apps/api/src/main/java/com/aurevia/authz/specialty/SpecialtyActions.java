package com.aurevia.authz.specialty;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the S-G4-06 specialty slice (IAM-01, IAM-02, PRD-06).
 *
 * <p>Separation of duties is structural: a lab collector can accession and
 * collect but never verify or release; radiology staff can associate a study
 * but never sign an interpretation; pharmacy staff record review evidence but
 * do not make a diagnosis, treatment or release decision. Only the roles named
 * by {@link com.aurevia.authz.specialty.SpecialtyActions#ATTEST} may produce
 * a human attestation, and an attestation is
 * additionally checked against the workflow action it claims. The matrix is
 * paired with a fresh membership and role check on every protected request in
 * the domain service.</p>
 */
@Component
public class SpecialtyActions {

    /** Accession an issuer-scoped requisition (LAB-01). */
    public static final String LAB_ACCESSION = "SPECIALTY_LAB_ACCESSION";
    /** Collect or reject a specimen (LAB-01). */
    public static final String LAB_COLLECT = "SPECIALTY_LAB_COLLECT";
    /** Verify, release or correct a result (LAB-02, XCH-06). */
    public static final String LAB_VERIFY_RELEASE = "SPECIALTY_LAB_VERIFY_RELEASE";
    /** Schedule and associate an external imaging study (RAD-01). */
    public static final String RAD_ASSOCIATE_STUDY = "SPECIALTY_RAD_ASSOCIATE_STUDY";
    /** Sign an imaging interpretation or addendum (RAD-01). */
    public static final String RAD_SIGN_INTERPRETATION = "SPECIALTY_RAD_SIGN_INTERPRETATION";
    /** Accept a referral into a therapy episode (PT-01). */
    public static final String PT_ACCEPT_REFERRAL = "SPECIALTY_PT_ACCEPT_REFERRAL";
    /** Sign a therapy progress or discharge note (PT-01). */
    public static final String PT_SIGN_DISCHARGE = "SPECIALTY_PT_SIGN_DISCHARGE";
    /** Review an explicitly routed prescription (RX-01). */
    public static final String PHARMACY_REVIEW = "SPECIALTY_PHARMACY_REVIEW";
    /** Record a routed prescription status event (RX-02). */
    public static final String PHARMACY_STATUS = "SPECIALTY_PHARMACY_STATUS";
    /** Record a human clinical attestation (PRD-06). */
    public static final String ATTEST = "SPECIALTY_ATTEST";
    /** Read an attestation and its evidence (PRD-06). */
    public static final String ATTESTATION_READ = "SPECIALTY_ATTESTATION_READ";
    /** Read the caller's effective permissions (IAM-01). */
    public static final String PERMISSIONS_READ = "SPECIALTY_PERMISSIONS_READ";

    /** All action keys in stable order, used to project an effective permission view. */
    public static final List<String> ALL_ACTIONS = List.of(
            LAB_ACCESSION, LAB_COLLECT, LAB_VERIFY_RELEASE, RAD_ASSOCIATE_STUDY,
            RAD_SIGN_INTERPRETATION, PT_ACCEPT_REFERRAL, PT_SIGN_DISCHARGE,
            PHARMACY_REVIEW, PHARMACY_STATUS, ATTEST, ATTESTATION_READ, PERMISSIONS_READ);

    private static final Set<String> CLINICAL_ATTESTERS = Set.of(
            SpecialtyRoles.LAB_VERIFIER, SpecialtyRoles.RADIOLOGY_INTERPRETER,
            SpecialtyRoles.PT_CLINICIAN, SpecialtyRoles.CLINICIAN);
    private static final Set<String> CLINICAL_READERS = Set.of(
            SpecialtyRoles.LAB_COLLECTOR, SpecialtyRoles.LAB_VERIFIER,
            SpecialtyRoles.RADIOLOGY_STAFF, SpecialtyRoles.RADIOLOGY_INTERPRETER,
            SpecialtyRoles.PT_CLINICIAN, SpecialtyRoles.PHARMACY_STAFF, SpecialtyRoles.CLINICIAN);

    /** Actions that represent a PRD-06 decision and the decision each one implies. */
    private static final Map<String, SpecialtyDecision> ATTESTABLE = Map.of(
            LAB_VERIFY_RELEASE, SpecialtyDecision.RELEASE,
            RAD_SIGN_INTERPRETATION, SpecialtyDecision.DIAGNOSIS,
            PT_ACCEPT_REFERRAL, SpecialtyDecision.TREATMENT,
            PT_SIGN_DISCHARGE, SpecialtyDecision.TREATMENT);

    /**
     * Decide whether a role may perform an action. An unknown action or role is denied.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} only when the role is explicitly permitted
     */
    public boolean allows(String action, String role) {
        if (action == null || role == null) {
            return false;
        }
        return switch (action) {
            case LAB_ACCESSION, LAB_COLLECT -> SpecialtyRoles.LAB_COLLECTOR.equals(role);
            case LAB_VERIFY_RELEASE -> SpecialtyRoles.LAB_VERIFIER.equals(role);
            case RAD_ASSOCIATE_STUDY -> SpecialtyRoles.RADIOLOGY_STAFF.equals(role);
            case RAD_SIGN_INTERPRETATION -> SpecialtyRoles.RADIOLOGY_INTERPRETER.equals(role);
            case PT_ACCEPT_REFERRAL, PT_SIGN_DISCHARGE -> SpecialtyRoles.PT_CLINICIAN.equals(role);
            case PHARMACY_REVIEW, PHARMACY_STATUS -> SpecialtyRoles.PHARMACY_STAFF.equals(role);
            case ATTEST -> CLINICAL_ATTESTERS.contains(role);
            case ATTESTATION_READ -> CLINICAL_READERS.contains(role);
            case PERMISSIONS_READ -> SpecialtyRoles.ALL.contains(role);
            default -> false;
        };
    }

    /**
     * Project the union of actions permitted by a set of active roles.
     *
     * @param roles active role keys for the current subject
     * @return stable list of permitted action keys, empty when no role is active
     */
    public List<String> allowedActions(List<String> roles) {
        return ALL_ACTIONS.stream()
                .filter(action -> roles.stream().anyMatch(role -> allows(action, role)))
                .toList();
    }

    /**
     * Whether an action represents a clinical decision that can be attested.
     *
     * @param action action key
     * @return {@code true} when the action is an attested PRD-06 decision
     */
    public boolean isAttestable(String action) {
        return ATTESTABLE.containsKey(action);
    }

    /**
     * Return the PRD-06 decision an attestable action implies.
     *
     * @param action attestable action key
     * @return the derived decision
     * @throws IllegalArgumentException when the action is not attestable
     */
    public SpecialtyDecision decisionFor(String action) {
        SpecialtyDecision decision = ATTESTABLE.get(action);
        if (decision == null) {
            throw new IllegalArgumentException("action is not an attestable decision: " + action);
        }
        return decision;
    }
}
