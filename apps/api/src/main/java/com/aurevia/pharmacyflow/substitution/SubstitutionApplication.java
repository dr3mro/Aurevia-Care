package com.aurevia.pharmacyflow.substitution;

import com.aurevia.prescription.PrescriptionContract.DrugEntry;

/**
 * Boundary that applies an approved substitution to the authoritative prescription (RX-02).
 *
 * <p>The substitution kernel decides *whether* a replacement is safe; this port
 * performs the durable prescription change. It is called inside the substitution
 * decision's tenant-guarded transaction, so a failed application rolls the whole
 * decision back. The named prescriber's active membership, version precondition
 * and market entitlement are re-derived there rather than trusted from the
 * proposal, which may have been read before a permission was revoked. The
 * production adapter is
 * {@link com.aurevia.prescription.PrescriptionSubstitutionService}.</p>
 */
public interface SubstitutionApplication {

    /**
     * Apply a signed replacement to a prescription.
     *
     * @param organizationId         active tenant
     * @param subject                approving prescriber
     * @param prescriptionId         authoritative prescription identifier
     * @param expectedVersion        prescription version read by the caller
     * @param replacement            replacement catalog entry
     * @throws com.aurevia.api.ApiException on denial, a stale version or missing entitlement
     */
    void apply(String organizationId, String subject, String prescriptionId, int expectedVersion,
               DrugEntry replacement);
}
