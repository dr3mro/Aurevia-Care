package com.aurevia.smartclinic.pharmacyflow.substitution;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.smartclinic.prescription.PrescriptionContract.Draft;
import com.aurevia.smartclinic.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.smartclinic.prescription.PrescriptionRepository;

/**
 * Tenant-guarded JDBC implementation of the substitution read port (RX-01, ARC-02).
 *
 * <p>Every read runs in the shared tenant-guarded transaction, so forced row
 * level security confines the result to the active organization. The adapter is
 * deliberately read-only and exposes no prescribing action, which is why it can
 * serve a pharmacy-staff propose flow without widening the clinician-gated
 * catalog search action.</p>
 */
@Component
public final class JdbcSubstitutionStore implements SubstitutionCatalog {

    private final PrescriptionRepository repository;
    private final TransactionTemplate transactions;

    /**
     * Create the adapter over the existing prescription SQL boundary.
     *
     * @param repository    RLS-bounded prescription repository
     * @param transactions tenant-guarded transaction template
     */
    public JdbcSubstitutionStore(PrescriptionRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
    }

    @Override
    public Draft draft(String prescriptionId) {
        return transactions.execute(status -> repository.find(prescriptionId).orElse(null));
    }

    @Override
    public List<DrugEntry> entries() {
        return transactions.execute(status -> repository.loadCatalog());
    }

    @Override
    public AllergyAssessment allergies(String patientId) {
        return transactions.execute(status -> repository.allergyAssessment(patientId));
    }

    @Override
    public String route(String entryId, int version) {
        return transactions.execute(status -> repository.catalogRoute(entryId, version));
    }
}
