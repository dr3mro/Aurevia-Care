package com.aurevia.labflow.rejection;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.AccessionEvent;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.lab.accession.LabInputs;
import com.aurevia.tenant.TenantContext;

/**
 * Specimen rejection, notification and linked recollection invariants (LAB-01, ARC-02, OPS-02).
 *
 * <p>A rejection keeps a bounded reason (with detail for {@code other}), records
 * the ordering clinician as the notification target, notifies that clinician
 * and, only when site policy allows, the patient. Every change runs in one
 * tenant-guarded transaction: the state update, the immutable history event and
 * the audit facts commit together, so a rejected specimen always keeps its full
 * history and no notification is recorded without the rejection.</p>
 *
 * <p>Recollection mints a new specimen identifier but reuses the rejected
 * specimen's patient and source order, so both specimens stay on one order. The
 * rejected specimen becomes terminal ({@code RECOLLECTED}) rather than being
 * removed.</p>
 */
@Service
public class SpecimenRejectionService {

    /** Audit purpose stamped on rejection, notification and recollection events. */
    public static final String PURPOSE = "LAB_SPECIMEN_REJECTION";
    private static final String RESOURCE_TYPE = "SPECIMEN";
    private static final String NOTIFICATION_RESOURCE_TYPE = "NOTIFICATION";
    private static final String SCOPE = "LAB";
    private static final String NOTIFY_ACTION = "LAB_SPECIMEN_REJECTION_NOTIFY";
    private static final String CLINICIAN_PREFIX = "clinician:";
    private static final String PATIENT_PREFIX = "patient:";
    private static final Pattern DETAIL = Pattern.compile("[\\p{L}\\p{M}0-9 '.,/:@_-]{2,160}");

    private final LabAccessionRepository accessions;
    private final LabActionPolicy policy;
    private final RejectionNotificationPolicy notificationPolicy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param accessions         RLS-bounded accession repository
     * @param policy             bounded laboratory action policy
     * @param notificationPolicy patient-notification policy gate
     * @param transactions       tenant-guarded transaction template
     * @param audit              transactional audit appender
     */
    public SpecimenRejectionService(LabAccessionRepository accessions, LabActionPolicy policy,
                                    RejectionNotificationPolicy notificationPolicy,
                                    TransactionTemplate transactions, AuditAppender audit) {
        this.accessions = accessions;
        this.policy = policy;
        this.notificationPolicy = notificationPolicy;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Reject a specimen, retain its reason and notify the ordering clinician.
     *
     * @param id      opaque specimen identifier
     * @param request bounded rejection command
     * @return the rejection outcome, including who was notified
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment,
     *                                                  stale version or illegal state
     */
    public RejectionOutcome reject(String id, RejectSpecimenRequest request) {
        String specimenId = LabInputs.id(id);
        RejectionReason reason = RejectionReason.fromWire(request.reason());
        String detail = requireDetail(reason, request.detail());
        String clinician = LabInputs.notification(request.orderingClinician());
        int expectedVersion = LabInputs.version(request.expectedVersion());
        boolean notifyPatient = request.notifyPatient() && notificationPolicy.mayNotifyPatient();

        AtomicReference<ApiException> failure = new AtomicReference<>();
        RejectionOutcome outcome = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.SPECIMEN_REJECT, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession current = accessions.findById(specimenId).orElse(null);
            if (current == null) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_REJECT, RESOURCE_TYPE,
                        SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "accession not found"));
                return null;
            }
            if (current.version() != expectedVersion) {
                throw staleVersion();
            }
            if (!current.state().canTransitionTo(AccessionState.REJECTED)) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "the specimen cannot be rejected from its current state");
            }
            String retainedReason = retain(reason, detail);
            int updated = accessions.update(context.organizationId(), specimenId, expectedVersion,
                    AccessionState.REJECTED, current.specimenType(), current.siteCode(), current.collector(),
                    current.collectedAt(), current.receivedAt(), current.quality(), retainedReason, clinician);
            if (updated == 0) {
                throw staleVersion();
            }
            accessions.appendEvent(specimenId, expectedVersion + 1, "rejected", current.state(),
                    AccessionState.REJECTED, retainedReason, null);
            audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_REJECT, RESOURCE_TYPE,
                    SCOPE, specimenId, "SUCCESS");
            audit.append(context, PURPOSE, NOTIFY_ACTION, NOTIFICATION_RESOURCE_TYPE, SCOPE,
                    CLINICIAN_PREFIX + clinician, "SUCCESS");
            if (notifyPatient) {
                audit.append(context, PURPOSE, NOTIFY_ACTION, NOTIFICATION_RESOURCE_TYPE, SCOPE,
                        PATIENT_PREFIX + current.patientId(), "SUCCESS");
            }
            return new RejectionOutcome(specimenId, AccessionState.REJECTED, expectedVersion + 1,
                    retainedReason, clinician, true, notifyPatient);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return outcome;
    }

    /**
     * Create a linked recollection of a rejected specimen on the same order.
     *
     * @param id      rejected specimen identifier
     * @param request bounded recollection command
     * @return the new accession with its link to the rejected specimen
     * @throws com.aurevia.api.ApiException on invalid input, denial, duplicate number,
     *                                                  stale version or illegal state
     */
    public RecollectionOutcome recollect(String id, RecollectSpecimenRequest request) {
        String specimenId = LabInputs.id(id);
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        int expectedVersion = LabInputs.version(request.expectedVersion());

        AtomicReference<ApiException> failure = new AtomicReference<>();
        RecollectionOutcome outcome = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, LabActionPolicy.SPECIMEN_RECOLLECT, specimenId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession current = accessions.findById(specimenId).orElse(null);
            if (current == null) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_RECOLLECT, RESOURCE_TYPE,
                        SCOPE, specimenId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "accession not found"));
                return null;
            }
            if (current.version() != expectedVersion) {
                throw staleVersion();
            }
            if (!current.state().canTransitionTo(AccessionState.RECOLLECTED)) {
                throw new ApiException(409, "INVALID_TRANSITION", "only a rejected specimen can be recollected");
            }
            if (accessions.accessionExists(issuer, number)) {
                audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_RECOLLECT, RESOURCE_TYPE,
                        SCOPE, specimenId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "accession number already exists for this issuer"));
                return null;
            }
            String newId = UUID.randomUUID().toString();
            try {
                accessions.insert(context.organizationId(), newId, number, issuer, current.patientId(),
                        current.sourceOrderRef(), AccessionState.ACCESSIONED, specimenId);
            } catch (DuplicateKeyException exception) {
                // A 23505 aborts the PostgreSQL transaction, so no further statement —
                // including an audit insert — can run in this callback. The ordinary
                // duplicate case is audited by the pre-check above; this rare race just
                // returns a clean conflict after the aborted transaction is rolled back.
                failure.set(new ApiException(409, "CONFLICT", "accession number already exists for this issuer"));
                return null;
            }
            int updated = accessions.update(context.organizationId(), specimenId, expectedVersion,
                    AccessionState.RECOLLECTED, current.specimenType(), current.siteCode(), current.collector(),
                    current.collectedAt(), current.receivedAt(), current.quality(),
                    current.rejectionReason(), current.rejectionNotification());
            if (updated == 0) {
                throw staleVersion();
            }
            accessions.appendEvent(specimenId, expectedVersion + 1, "recollected", AccessionState.REJECTED,
                    AccessionState.RECOLLECTED, null, null);
            accessions.appendEvent(newId, 1, "accessioned", null, AccessionState.ACCESSIONED, null, null);
            audit.append(context, PURPOSE, LabActionPolicy.SPECIMEN_RECOLLECT, RESOURCE_TYPE,
                    SCOPE, newId, "SUCCESS");
            return new RecollectionOutcome(newId, specimenId, current.sourceOrderRef(),
                    AccessionState.ACCESSIONED, 1);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return outcome;
    }

    /**
     * Read the immutable history of a specimen, including its rejection.
     *
     * @param id opaque specimen identifier
     * @return the events in occurrence order
     * @throws com.aurevia.api.ApiException on invalid input, denial or concealment
     */
    public List<AccessionEvent> history(String id) {
        String specimenId = LabInputs.id(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<AccessionEvent> events = transactions.execute(status -> {
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
            List<AccessionEvent> history = accessions.history(specimenId);
            audit.append(context, PURPOSE, LabActionPolicy.ACCESSION_READ, RESOURCE_TYPE,
                    SCOPE, specimenId, "SUCCESS");
            return history;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return events;
    }

    private static String requireDetail(RejectionReason reason, String detail) {
        if (reason.requiresDetail()) {
            if (detail == null || !DETAIL.matcher(detail).matches()) {
                throw new ApiException(422, "DETAIL_REQUIRED",
                        "a bounded free-text detail is required for the other reason");
            }
            return detail;
        }
        return null;
    }

    private static String retain(RejectionReason reason, String detail) {
        return LabInputs.reason(reason.requiresDetail() ? reason.wire() + ": " + detail : reason.wire());
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
