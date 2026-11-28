package com.aurevia.prescription.rx;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.prescription.rx.RxContract.AttestRequest;
import com.aurevia.prescription.rx.RxContract.CancelRequest;
import com.aurevia.prescription.rx.RxContract.ConfirmRequest;
import com.aurevia.prescription.rx.RxContract.CreateRequest;
import com.aurevia.prescription.rx.RxContract.DispensingRequest;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.PharmacyResponseRequest;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.RenewRequest;
import com.aurevia.prescription.rx.RxContract.RetryRequest;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxContract.TransmitRequest;
import com.aurevia.prescription.rx.RxContract.VerifyRequest;
import com.aurevia.prescription.rx.RxContract.View;
import com.aurevia.tenant.TenantContext;

/**
 * Controlled prescription transmission workflow.
 *
 * <p>Each command runs in one tenant-guarded transaction: authorization is
 * re-derived, the state precondition is checked, the row change, its immutable
 * event and the audit event are committed together. Prescriber attestation and
 * market entitlement are re-derived on the commands that change transmission or
 * produce a printable document, so a revocation between attest and transmit
 * fails closed. A stable submission id
 * is created once; an ambiguous transmission enters reconciliation and cannot
 * be retried until the partner is queried and reports the submission absent.
 * The retry reuses the same submission id and never creates a second send.</p>
 */
@Service
public class RxService {

    private static final String SCOPE = "PRESCRIPTION_TRANSMISSION";
    private static final String RESOURCE_TYPE = "PRESCRIPTION";
    private static final String MARKET_KEY = "rx02.market";
    private static final String CHANNELS_KEY = "rx02.approved_channels";
    private static final String ENTITLEMENT_PREFIX = "rx02.entitlement.";
    private static final int MAX_ATTEMPTS = 3;

    private final RxRepository repository;
    private final RxActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final LocalPrescriptionPrint printer = new LocalPrescriptionPrint();

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded prescription repository
     * @param actions      bounded action matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public RxService(RxRepository repository, RxActions actions, TransactionTemplate transactions,
                     AuditAppender audit) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Register a transmission record for a prescription draft id.
     *
     * @param request registration command
     * @return the created draft
     * @throws ApiException on invalid input, denial or an invisible patient
     */
    public Prescription create(CreateRequest request) {
        String id = RxInputs.uuid(request.prescriptionId(), "prescriptionId");
        String patientId = RxInputs.uuid(request.patientId(), "patientId");
        String medication = RxInputs.text(request.medication(), "medication", 128);
        String strength = RxInputs.text(request.strength(), "strength", 64);
        String dose = RxInputs.text(request.dose(), "dose", 64);
        String route = RxInputs.text(request.route(), "route", 64);
        String frequency = RxInputs.text(request.frequency(), "frequency", 64);
        int quantity = RxInputs.quantity(request.quantity());
        String prescriber = RxInputs.subject(request.prescriberSubject(), "prescriberSubject");
        String destination = request.destinationPharmacy() == null ? null
                : RxInputs.text(request.destinationPharmacy(), "destinationPharmacy", 128);
        return execute(RxActions.CREATE, id, sink -> {
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            repository.insert(id, patientId, medication, strength, dose, route, frequency, quantity,
                    prescriber, destination);
            success(RxActions.CREATE, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record the prescriber's attestation after re-checking entitlement.
     *
     * @param id              prescription identifier
     * @param request         attestation command
     * @param expectedVersion version read by the caller
     * @return the attested prescription
     * @throws ApiException on denial, stale version, wrong state or missing entitlement
     */
    public Prescription attest(String id, AttestRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String note = RxInputs.detail(request.note(), 500);
        return execute(RxActions.ATTEST, id, sink -> {
            Prescription current = lockedByPrescriber(id, expectedVersion, State.DRAFT);
            String market = requireEntitlement(current.prescriberSubject());
            repository.attest(id, expectedVersion);
            append(id, expectedVersion + 1, EventType.ATTESTED, null, null, "market=" + market, note);
            success(RxActions.ATTEST, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Route an attested prescription through an approved channel.
     *
     * @param id              prescription identifier
     * @param request         transmission command
     * @param expectedVersion version read by the caller
     * @return the transmitted prescription
     * @throws ApiException on denial, stale version, wrong state, unapproved channel or missing entitlement
     */
    public Prescription transmit(String id, TransmitRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String channelKey = RxInputs.channel(request.channel(), "channel");
        RxChannel channel = parseChannel(channelKey);
        String outcome = channel.isElectronic()
                ? RxInputs.transmissionOutcome(request.outcome())
                : RxInputs.printOutcome(request.outcome());
        return execute(RxActions.TRANSMIT, id, sink -> {
            Prescription current = lockedByPrescriber(id, expectedVersion, State.ATTESTED);
            String market = requireEntitlement(current.prescriberSubject());
            requireApprovedChannel(channelKey);
            if (channel.isElectronic()) {
                String submissionId = RxInputs.newSubmissionId();
                repository.insertSubmission(submissionId, id, channelKey);
                State next = "ambiguous".equals(outcome) ? State.AMBIGUOUS : State.SUBMITTED;
                repository.transmit(id, expectedVersion, channelKey, submissionId, next);
                append(id, expectedVersion + 1, EventType.SUBMITTED, channelKey, submissionId, outcome,
                        "market=" + market);
            } else {
                repository.transmitPrinted(id, expectedVersion, channelKey);
                append(id, expectedVersion + 1, EventType.PRINTED, channelKey, null, "printed",
                        "market=" + market);
            }
            success(RxActions.TRANSMIT, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Confirm delivery of the exact stable submission id.
     *
     * @param id              prescription identifier
     * @param request         confirmation command
     * @param expectedVersion version read by the caller
     * @return the prescription awaiting a pharmacy response
     * @throws ApiException on denial, stale version, wrong state or wrong submission id
     */
    public Prescription confirmDelivery(String id, ConfirmRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String submissionId = RxInputs.uuid(request.submissionId(), "submissionId");
        return execute(RxActions.CONFIRM, id, sink -> {
            Prescription current = locked(id, expectedVersion, State.SUBMITTED);
            requireSubmission(current, submissionId);
            repository.setState(id, expectedVersion, State.DELIVERY_CONFIRMED);
            append(id, expectedVersion + 1, EventType.DELIVERY_CONFIRMED, current.channel(), submissionId,
                    "delivered", null);
            success(RxActions.CONFIRM, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Reconcile an ambiguous transmission using a three-valued partner status.
     *
     * <p>Only an {@code absent} status authorizes a later retry. {@code present}
     * returns the prescription to SUBMITTED for a distinct delivery receipt;
     * {@code unknown} keeps it in reconciliation with retry disabled.</p>
     *
     * @param id              prescription identifier
     * @param request         verification command
     * @param expectedVersion version read by the caller
     * @return the reconciled prescription
     * @throws ApiException on denial, stale version, wrong state or wrong submission id
     */
    public Prescription verifyPartnerStatus(String id, VerifyRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String submissionId = RxInputs.uuid(request.submissionId(), "submissionId");
        String status = RxInputs.partnerStatus(request.status());
        return execute(RxActions.VERIFY, id, sink -> {
            Prescription current = lockedByPrescriber(id, expectedVersion, State.AMBIGUOUS);
            requireEntitlement(current.prescriberSubject());
            requireSubmission(current, submissionId);
            State next = "present".equals(status) ? State.SUBMITTED : State.AMBIGUOUS;
            repository.reconcile(id, expectedVersion, "absent".equals(status), next);
            append(id, expectedVersion + 1, EventType.PARTNER_STATUS_VERIFIED, current.channel(), submissionId,
                    status, null);
            success(RxActions.VERIFY, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Retry a verified-absent submission, reusing the original submission id.
     *
     * @param id              prescription identifier
     * @param request         retry command
     * @param expectedVersion version read by the caller
     * @return the resubmitted prescription
     * @throws ApiException on denial, stale version, wrong state, no verification or attempt limit
     */
    public Prescription retry(String id, RetryRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String submissionId = RxInputs.uuid(request.submissionId(), "submissionId");
        String outcome = RxInputs.transmissionOutcome(request.outcome());
        return execute(RxActions.RETRY, id, sink -> {
            Prescription current = lockedByPrescriber(id, expectedVersion, State.AMBIGUOUS);
            requireEntitlement(current.prescriberSubject());
            requireSubmission(current, submissionId);
            // A retry is blocked until the partner reports the submission absent;
            // the original submission id is reused so the retry cannot send twice.
            if (!current.retryAllowed()) {
                throw new ApiException(409, "RETRY_NOT_ALLOWED",
                        "partner status must be verified absent before a retry");
            }
            int attempts = repository.submissionAttempts(submissionId);
            if (attempts >= MAX_ATTEMPTS) {
                throw new ApiException(409, "RETRY_LIMIT", "the submission retry limit was reached");
            }
            State next = "ambiguous".equals(outcome) ? State.AMBIGUOUS : State.SUBMITTED;
            repository.retrySubmission(id, expectedVersion, next);
            append(id, expectedVersion + 1, EventType.SUBMITTED, current.channel(), submissionId, outcome,
                    "attempt=" + (attempts + 1));
            success(RxActions.RETRY, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record a pharmacy acceptance, rejection or clarification request.
     *
     * @param id              prescription identifier
     * @param request         pharmacy response command
     * @param expectedVersion version read by the caller
     * @return the prescription in the resulting response state
     * @throws ApiException on denial, stale version, wrong state or wrong submission id
     */
    public Prescription pharmacyResponse(String id, PharmacyResponseRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String submissionId = RxInputs.uuid(request.submissionId(), "submissionId");
        String outcome = RxInputs.pharmacyOutcome(request.outcome());
        String note = RxInputs.detail(request.note(), 500);
        return execute(RxActions.PHARMACY_RESPONSE, id, sink -> {
            Prescription current = locked(id, expectedVersion);
            if (current.state() != State.DELIVERY_CONFIRMED && current.state() != State.CLARIFICATION) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "a pharmacy response needs a delivered prescription");
            }
            requireSubmission(current, submissionId);
            State next;
            EventType type;
            switch (outcome) {
                case "accepted" -> {
                    next = State.ACCEPTED;
                    type = EventType.PHARMACY_ACCEPTED;
                }
                case "rejected" -> {
                    next = State.REJECTED;
                    type = EventType.PHARMACY_REJECTED;
                }
                default -> {
                    next = State.CLARIFICATION;
                    type = EventType.CLARIFICATION;
                }
            }
            repository.setState(id, expectedVersion, next);
            append(id, expectedVersion + 1, type, current.channel(), submissionId, outcome, note);
            success(RxActions.PHARMACY_RESPONSE, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record partner-supplied dispensing evidence, distinct from acceptance.
     *
     * @param id              prescription identifier
     * @param request         dispensing command
     * @param expectedVersion version read by the caller
     * @return the dispensed prescription
     * @throws ApiException on denial, stale version, wrong state or wrong submission id
     */
    public Prescription recordDispensing(String id, DispensingRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String submissionId = RxInputs.uuid(request.submissionId(), "submissionId");
        String note = RxInputs.detail(request.note(), 500);
        return execute(RxActions.DISPENSE, id, sink -> {
            Prescription current = locked(id, expectedVersion, State.ACCEPTED);
            requireSubmission(current, submissionId);
            repository.setState(id, expectedVersion, State.DISPENSED);
            append(id, expectedVersion + 1, EventType.DISPENSING_EVIDENCE, current.channel(), submissionId,
                    "dispensed", note);
            success(RxActions.DISPENSE, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record a renewal linked to the original submission without resetting it.
     *
     * @param id              prescription identifier
     * @param request         renewal command
     * @param expectedVersion version read by the caller
     * @return the prescription with a renewal fact
     * @throws ApiException on denial, stale version or a draft that was never transmitted
     */
    public Prescription renew(String id, RenewRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String note = RxInputs.detail(request.note(), 500);
        return execute(RxActions.RENEW, id, sink -> {
            Prescription current = lockedByPrescriber(id, expectedVersion, null);
            if (current.state() == State.DRAFT) {
                throw new ApiException(409, "INVALID_TRANSITION", "a draft cannot be renewed");
            }
            requireEntitlement(current.prescriberSubject());
            repository.bumpVersion(id, expectedVersion);
            append(id, expectedVersion + 1, EventType.RENEWAL, current.channel(), current.submissionId(),
                    "renewed-from=" + (current.submissionId() == null ? "none" : current.submissionId()), note);
            success(RxActions.RENEW, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Cancel a live prescription with a reason.
     *
     * @param id              prescription identifier
     * @param request         cancellation command
     * @param expectedVersion version read by the caller
     * @return the cancelled prescription
     * @throws ApiException on denial, stale version or a state that cannot be cancelled
     */
    public Prescription cancel(String id, CancelRequest request, int expectedVersion) {
        RxInputs.uuid(id, "id");
        String reason = RxInputs.text(request.reason(), "reason", 500);
        return execute(RxActions.CANCEL, id, sink -> {
            Prescription current = lockedByPrescriber(id, expectedVersion, null);
            if (!current.state().canTransitionTo(State.CANCELLED)) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "a " + current.state().wire() + " prescription cannot be cancelled");
            }
            repository.setState(id, expectedVersion, State.CANCELLED);
            append(id, expectedVersion + 1, EventType.CANCELLED, current.channel(), current.submissionId(),
                    "cancelled", reason);
            success(RxActions.CANCEL, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Read one prescription with its complete event trail.
     *
     * @param id prescription identifier
     * @return the prescription and its immutable facts
     * @throws ApiException on denial or an invisible prescription
     */
    public View get(String id) {
        RxInputs.uuid(id, "id");
        return execute(RxActions.READ, id, sink -> {
            Prescription prescription = repository.find(id).orElseThrow(notFound());
            success(RxActions.READ, id);
            return new View(prescription, repository.events(id));
        });
    }

    /**
     * Produce the printable local prescription for an attested, locally routed
     * prescription.
     *
     * <p>Market entitlement and channel approval are re-derived inside the same
     * tenant-guarded transaction as the document, so a revocation after the
     * original print instruction fails closed. The channel on the document is
     * the one recorded on the prescription; no submission or pharmacy outcome is
     * created by reading the printable form.</p>
     *
     * @param id prescription identifier
     * @return the immutable print-ready prescription with the prescriber attestation
     * @throws ApiException on denial, invisible prescription, missing entitlement,
     *         an unapproved local print channel or a prescription that was not
     *         printed locally
     */
    public PrintablePrescription printable(String id) {
        RxInputs.uuid(id, "id");
        return execute(RxActions.READ, id, sink -> {
            Prescription prescription = repository.find(id).orElseThrow(notFound());
            requireEntitlement(prescription.prescriberSubject());
            requireApprovedChannel(RxChannel.PRINTABLE_LOCAL.wire());
            PrintablePrescription document = printer.issue(
                    new View(prescription, repository.events(id)));
            success(RxActions.READ, id);
            return document;
        });
    }

    private <T> T execute(String action, String resourceId, Function<Sink, T> work) {
        Sink sink = new Sink();
        T value = transactions.execute(status -> {
            TenantContext context = context();
            ApiException denial = authorize(context, action, resourceId);
            if (denial != null) {
                sink.failure = denial;
                return null;
            }
            try {
                return work.apply(sink);
            } catch (ApiException refusal) {
                // Preconditions raise before any row mutation, so the only
                // committed write on this refusal is the DENIED audit event.
                // Carry the original failure out on the sink: throwing here
                // would roll the audit row back with the failed command.
                audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
                sink.failure = refusal;
                return null;
            }
        });
        if (sink.failure != null) {
            throw sink.failure;
        }
        return value;
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> actions.allows(action, role))) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
        return null;
    }

    private Prescription locked(String id, int expectedVersion) {
        Prescription current = repository.loadForUpdate(id).orElseThrow(notFound());
        requireVersion(current, expectedVersion);
        return current;
    }

    private Prescription locked(String id, int expectedVersion, State expected) {
        Prescription current = locked(id, expectedVersion);
        requireState(current, expected);
        return current;
    }

    /**
     * Load a prescriber-owned row and check ownership before any precondition.
     *
     * <p>Ownership is an authorization decision: a caller who is not the
     * prescribing clinician must be denied regardless of the version or state
     * they present. Checking it first gives a stable 403 for a wrong actor and
     * keeps a stale version distinguishable as 412 for the real prescriber.
     * The row is still loaded under forced RLS first, so a foreign id remains
     * concealed as 404. A {@code null} expected state leaves state checking to
     * the command, as renewal and cancellation permit more than one source state.</p>
     *
     * @param id              prescription identifier
     * @param expectedVersion version read by the caller
     * @param expected        required state, or {@code null} when the command decides
     * @return the locked and authorized prescription
     */
    private Prescription lockedByPrescriber(String id, int expectedVersion, State expected) {
        Prescription current = repository.loadForUpdate(id).orElseThrow(notFound());
        requirePrescriber(context(), current);
        requireVersion(current, expectedVersion);
        if (expected != null) {
            requireState(current, expected);
        }
        return current;
    }

    private void requirePrescriber(TenantContext context, Prescription current) {
        if (!Objects.equals(context.subject(), current.prescriberSubject())) {
            throw new ApiException(403, "FORBIDDEN", "only the prescribing clinician may attest or route");
        }
    }

    private String requireEntitlement(String subject) {
        String market = repository.approvedSetting(MARKET_KEY)
                .orElseThrow(() -> new ApiException(422, "NO_MARKET_POLICY", "no approved market policy"));
        String entitlement = repository.approvedSetting(ENTITLEMENT_PREFIX + subject)
                .orElseThrow(() -> new ApiException(422, "NO_ENTITLEMENT", "the prescriber has no market entitlement"));
        if (!"enabled".equals(entitlement)) {
            throw new ApiException(422, "NO_ENTITLEMENT", "the prescriber is not entitled in this market");
        }
        return market;
    }

    private void requireApprovedChannel(String channelKey) {
        Set<String> approved = repository.approvedSetting(CHANNELS_KEY)
                .map(value -> Arrays.stream(value.split(",")).map(String::trim).collect(Collectors.toSet()))
                .orElseThrow(() -> new ApiException(422, "NO_CHANNEL_POLICY", "no approved channel policy"));
        if (!approved.contains(channelKey)) {
            throw new ApiException(422, "UNAPPROVED_CHANNEL", "the channel is not approved for this market");
        }
    }

    private RxChannel parseChannel(String channelKey) {
        try {
            return RxChannel.from(channelKey);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_CHANNEL", "unknown prescription channel");
        }
    }

    private void requireSubmission(Prescription current, String submissionId) {
        if (!Objects.equals(current.submissionId(), submissionId)) {
            throw new ApiException(422, "SUBMISSION_MISMATCH", "the submission id does not match this prescription");
        }
    }

    private void requireVersion(Prescription current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the prescription changed since it was read");
        }
    }

    private void requireState(Prescription current, State expected) {
        if (current.state() != expected) {
            throw new ApiException(409, "INVALID_TRANSITION",
                    "the prescription cannot be changed from " + current.state().wire());
        }
    }

    private void append(String id, int version, EventType type, String channel, String submissionId,
                        String outcome, String detail) {
        repository.appendEvent(id, version, type, channel, submissionId, outcome, detail);
    }

    private void success(String action, String resourceId) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
    }

    private TenantContext context() {
        return TenantContext.current();
    }

    private Supplier<ApiException> notFound() {
        return () -> new ApiException(404, "NOT_FOUND", "prescription not found");
    }

    /**
     * Carries a durable authorization denial out of a committed transaction so
     * its audit record survives while the caller still receives an error.
     */
    private static final class Sink {
        private ApiException failure;
    }
}
