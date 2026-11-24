package com.aurevia.prescription;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;
import com.aurevia.pharmacyflow.substitution.SubstitutionApplication;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.rx.RxRepository;

/**
 * Applies an approved pharmacy substitution through the RX-01 prescription workflow.
 *
 * <p>This is the authoritative write that was missing when decisions lived only
 * in memory: the signed replacement becomes the draft's selected medication via
 * the same version-guarded repository command the prescriber uses. Ownership,
 * version and market entitlement are re-checked on the locked row inside the
 * caller's transaction, so a revocation between proposal and approval fails
 * closed and rolls the whole decision back. No identity is accepted from the
 * substitution payload; the prescriber is derived from the locked prescription.</p>
 */
@Component
public final class PrescriptionSubstitutionService implements SubstitutionApplication {

    private static final String MARKET_KEY = "rx02.market";
    private static final String ENTITLEMENT_PREFIX = "rx02.entitlement.";

    private final PrescriptionRepository repository;
    private final RxRepository prescribing;

    /**
     * Create the substitution applier over the authoritative prescription boundaries.
     *
     * @param repository  version-guarded RX-01 draft repository
     * @param prescribing market entitlement fold of the RX-02 repository
     */
    public PrescriptionSubstitutionService(PrescriptionRepository repository, RxRepository prescribing) {
        this.repository = repository;
        this.prescribing = prescribing;
    }

    @Override
    public void apply(String organizationId, String subject, String prescriptionId, int expectedVersion,
                      DrugEntry replacement) {
        Draft current = repository.loadForUpdate(prescriptionId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "prescription draft not found"));
        requirePrescriber(subject, current);
        requireVersion(current, expectedVersion);
        requireEntitlement(subject);
        repository.selectMedication(prescriptionId, expectedVersion, replacement.entryId(), replacement.version(),
                replacement.genericName(), current.doseValue(), current.doseUnit(), current.route(),
                current.frequency(), current.quantity(), current.quantityUnit(), current.refills(),
                current.instructions(), repository.currentCatalogVersion().version());
    }

    private static void requirePrescriber(String subject, Draft current) {
        if (!Objects.equals(subject, current.prescriberSubject())) {
            // Ownership is an authorization decision and is checked before any
            // precondition, so a wrong actor cannot probe versions or state.
            throw new ApiException(403, "FORBIDDEN", "only the prescribing clinician may apply a substitution");
        }
    }

    private static void requireVersion(Draft current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the prescription changed since the proposal was read");
        }
    }

    private void requireEntitlement(String subject) {
        prescribing.approvedSetting(MARKET_KEY)
                .orElseThrow(() -> new ApiException(422, "NO_MARKET_POLICY", "no approved market policy"));
        String entitlement = prescribing.approvedSetting(ENTITLEMENT_PREFIX + subject)
                .orElseThrow(() -> new ApiException(422, "NO_ENTITLEMENT", "the prescriber has no market entitlement"));
        if (!"enabled".equals(entitlement)) {
            throw new ApiException(422, "NO_ENTITLEMENT", "the prescriber is not entitled in this market");
        }
    }
}
