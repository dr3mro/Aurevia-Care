package com.aurevia.pharmacyflow.substitution;

import java.util.List;

import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;

/**
 * Pharmacy-authorized read boundary for the data a substitution review needs.
 *
 * <p>The prescription action matrix reserves catalog search and allergy decision
 * support for prescribing roles, so a pharmacy-only propose flow cannot call
 * those services. This port exposes only the narrow reads a substitution needs,
 * implemented over the tenant-guarded repository; it never writes and never
 * relaxes row-level security. The domain kernel depends on this interface rather
 * than on the clinician-gated services, so the propose flow is reachable by the
 * pharmacy staff it belongs to.</p>
 */
public interface SubstitutionCatalog {

    /**
     * Read the prescription draft that owns the medication being substituted.
     *
     * @param prescriptionId tenant-local prescription identifier
     * @return the draft, or {@code null} when it is not visible in the active tenant
     */
    Draft draft(String prescriptionId);

    /**
     * Load every stored version of the tenant's drug catalog.
     *
     * @return all catalog entries, in any order, including superseded versions
     */
    List<DrugEntry> entries();

    /**
     * Fold the patient's latest verified allergy assessment.
     *
     * @param patientId owning patient
     * @return the assessment with its statements, never silently empty
     */
    AllergyAssessment allergies(String patientId);

    /**
     * Read the market-approved route for a catalog entry version.
     *
     * @param entryId catalog entry key
     * @param version approved entry version
     * @return the declared route, or the legacy sentinel {@code unspecified}
     */
    String route(String entryId, int version);
}
