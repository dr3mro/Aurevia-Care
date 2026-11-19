package com.aurevia.smartclinic.patient.consolidation;

import java.util.List;

import com.aurevia.smartclinic.patient.Patient;

/**
 * Everything an authorized reviewer sees before approving a consolidation (IAM-04).
 *
 * <p>The two identities, their conflicting demographics, blocking dependencies and
 * a textual dry run are computed on read. Nothing here changes patient data.</p>
 *
 * @param review            the open review ticket, when one exists
 * @param survivor          effective surviving identity
 * @param duplicate         effective duplicate identity
 * @param conflicts         demographic fields that differ between the identities
 * @param dependencies      orders, patient-account links or grants on the duplicate
 * @param dryRun            non-mutating description of the planned association
 * @param chartVisibilityId effective patient id the duplicate chart resolves to now
 */
public record ReviewDetail(DuplicateReview review, Patient survivor, Patient duplicate,
                           List<Conflict> conflicts, List<Dependency> dependencies,
                           String dryRun, String chartVisibilityId) {

    /**
     * Freeze the conflict and dependency lists on construction.
     *
     * @throws NullPointerException when either list is null
     */
    public ReviewDetail {
        conflicts = List.copyOf(conflicts);
        dependencies = List.copyOf(dependencies);
    }

    /**
     * Return the immutable conflict list.
     *
     * @return conflicting demographic fields
     */
    @Override
    public List<Conflict> conflicts() {
        return List.copyOf(conflicts);
    }

    /**
     * Return the immutable dependency list.
     *
     * @return blocking or resolved dependencies on the duplicate
     */
    @Override
    public List<Dependency> dependencies() {
        return List.copyOf(dependencies);
    }

    /**
     * One conflicting demographic field shown side by side (IAM-04).
     *
     * @param field          stable field name
     * @param survivorValue  value on the surviving identity
     * @param duplicateValue value on the duplicate identity
     */
    public record Conflict(String field, String survivorValue, String duplicateValue) {
    }

    /**
     * A same-tenant dependency that must be resolved before consolidation (IAM-04).
     *
     * @param id        opaque dependency identifier
     * @param kind      {@code ORDER}, {@code PATIENT_LINK} or {@code GRANT}
     * @param reference source reference of the ambiguous order, link or grant
     * @param ambiguous whether the dependency cannot be attributed confidently
     * @param resolved  whether an authorized workflow has already resolved it
     */
    public record Dependency(String id, String kind, String reference,
                             boolean ambiguous, boolean resolved) {
    }
}
