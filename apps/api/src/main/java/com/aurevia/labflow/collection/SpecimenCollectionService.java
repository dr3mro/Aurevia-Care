package com.aurevia.labflow.collection;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.lab.accession.LabInputs;
import com.aurevia.patient.Patient;
import com.aurevia.patient.PatientRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Specimen collection invariants and label production (LAB-01, ARC-02, OPS-02).
 *
 * <p>Collection is only recorded after the presented patient and accession
 * identity match the stored requisition. A mismatch writes an audit row and then
 * refuses the command; the audit is committed with the otherwise empty unit of
 * work so an attempted mislabelled collection is always attributable. Reads,
 * writes and patient lookups all run under forced row-level security, so a peer
 * tenant's specimen is concealed as a not-found result.</p>
 *
 * <p>Invariant: an outcome that must be audited writes the row and then returns
 * {@code null} from the guarded callback, deferring the exception until after
 * commit; an outcome that writes no audit (stale version, illegal state, lost
 * update) may throw inside the callback. Never place an audit append immediately
 * before an in-callback {@code throw} — the rollback would discard the audit.</p>
 */
@Service
public class SpecimenCollectionService {

    /** Audit purpose stamped on collection and label events. */
    public static final String PURPOSE = "LAB_SPECIMEN_COLLECTION";
    private static final String RESOURCE_TYPE = "SPECIMEN";
    private static final String SCOPE = "LAB";
    private static final String MISMATCH_OUTCOME = "MISMATCH";

    private final LabAccessionRepository accessions;
    private final PatientRepository patients;
    private final LabActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param accessions   RLS-bounded accession repository
     * @param patients     RLS-bounded patient repository
     * @param policy       bounded laboratory action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public SpecimenCollectionService(LabAccessionRepository accessions, PatientRepository patients,
                                     LabActionPolicy policy, TransactionTemplate transactions,
                                     AuditAppender audit) {
        this.accessions = accessions;
        this.patients = patients;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Record collection after confirming the patient and accession match.
     *
     * @param id      opaque specimen identifier
     * @param request bounded collection command
     * @return the collected specimen projection
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment,
     *                                                  mismatch, stale version or illegal state
     */
    public LabAccession collect(String id, CollectRequest request) {
        String specimenId = LabInputs.id(id);
        String patientId = LabInputs.patientId(request.patientId());
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        String specimenType = LabInputs.specimenType(request.specimenType());
        String siteCode = LabInputs.siteCode(request.siteCode());
        String collector = LabInputs.collector(request.collector());
        String collectedAt = LabInputs.instant(request.collectedAt(), "collectedAt").toString();
        String quality = LabInputs.quality(request.quality());
        int expectedVersion = LabInputs.version(request.expectedVersion());

        AtomicReference<ApiException> failure = new AtomicReference<>();
        LabAccession result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.SPECIMEN_COLLECT, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession current = accessions.findById(specimenId).orElse(null);
            if (current == null) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_COLLECT, RESOURCE_TYPE,
                        SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "accession not found"));
                return null;
            }
            if (!matches(current, patientId, number, issuer)) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_COLLECT, RESOURCE_TYPE,
                        SCOPE, specimenId, MISMATCH_OUTCOME);
                failure.set(new ApiException(422, "PATIENT_ACCESSION_MISMATCH",
                        "patient and accession identity do not match the stored requisition"));
                return null;
            }
            if (current.version() != expectedVersion) {
                throw staleVersion();
            }
            if (current.state() != AccessionState.ACCESSIONED) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "only an accessioned specimen can be collected");
            }
            int updated = accessions.update(context.organizationId(), specimenId, expectedVersion,
                    AccessionState.COLLECTED, specimenType, siteCode, collector, collectedAt,
                    null, quality, null, null);
            if (updated == 0) {
                throw staleVersion();
            }
            accessions.appendEvent(specimenId, expectedVersion + 1, "collected", current.state(),
                    AccessionState.COLLECTED, null, patientId);
            audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_COLLECT, RESOURCE_TYPE,
                    SCOPE, specimenId, "SUCCESS");
            return accessions.findById(specimenId).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Return print-ready label data for one collected specimen.
     *
     * @param id opaque specimen identifier
     * @return the accession, patient and collection label fields
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment or
     *                                                  when the specimen has not been collected
     */
    public SpecimenLabelData labelData(String id) {
        String specimenId = LabInputs.id(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        SpecimenLabelData result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.ACCESSION_READ, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession accession = accessions.findById(specimenId).orElse(null);
            if (accession == null) {
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                        SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "accession not found"));
                return null;
            }
            if (accession.state() == AccessionState.ACCESSIONED) {
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                        SCOPE, specimenId, "CONFLICT");
                failure.set(new ApiException(409, "INVALID_STATE",
                        "specimen has not been collected; label data is incomplete"));
                return null;
            }
            Patient patient = patients.findById(accession.patientId()).orElse(null);
            if (patient == null) {
                audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                        SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                    SCOPE, specimenId, "SUCCESS");
            return new SpecimenLabelData(accession.accessionNo(), accession.accessionIssuer(),
                    patient.displayName(), patient.mrn(), patient.mrnIssuer(),
                    accession.specimenType(), accession.collectedAt());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private boolean matches(LabAccession current, String patientId, String number, String issuer) {
        return current.patientId().equals(patientId)
                && current.accessionNo().equals(number)
                && current.accessionIssuer().equals(issuer);
    }

    private ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the specimen changed since it was read");
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!accessions.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        List<String> roles = accessions.activeRoles(context.subject());
        if (roles.stream().noneMatch(role -> policy.allows(action, role))) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing laboratory permission");
        }
        return null;
    }
}
