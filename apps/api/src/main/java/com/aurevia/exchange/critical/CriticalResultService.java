package com.aurevia.exchange.critical;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.critical.CriticalContract.AcknowledgeRequest;
import com.aurevia.exchange.critical.CriticalContract.AssignRequest;
import com.aurevia.exchange.critical.CriticalContract.EscalateRequest;
import com.aurevia.exchange.critical.CriticalContract.EventType;
import com.aurevia.exchange.critical.CriticalContract.FollowUpRequest;
import com.aurevia.exchange.critical.CriticalContract.ReadBackRequest;
import com.aurevia.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.exchange.critical.CriticalContract.ReleaseRequest;
import com.aurevia.exchange.critical.CriticalContract.Report;
import com.aurevia.exchange.critical.CriticalContract.Result;
import com.aurevia.exchange.critical.CriticalContract.State;
import com.aurevia.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.exchange.critical.CriticalContract.View;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-05 critical-result workflow.
 *
 * <p>Each command runs in one tenant-guarded transaction: authorization is
 * re-derived, the state precondition is checked, the row change and its
 * immutable event are committed together with the audit event. Verification,
 * assignment, escalation, read-back, acknowledgment, follow-up and patient
 * release are separate facts. Delivery and patient release deliberately never
 * satisfy a clinician acknowledgment.</p>
 */
@Service
public class CriticalResultService {

    private static final String SCOPE = "CRITICAL_RESULT";
    private static final String RESOURCE_TYPE = "CRITICAL_RESULT";
    private static final String PRIMARY_CHANNEL_KEY = "critical.escalation.primary_channel";
    private static final String BACKUP_CHANNEL_KEY = "critical.escalation.backup_channel";
    private static final String BACKUP_RECIPIENT_KEY = "critical.escalation.backup_recipient";
    private static final String CLINICIAN_ROLE = "clinician";

    private final CriticalResultRepository repository;
    private final CriticalActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded critical-result repository
     * @param actions      bounded action matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        application clock for deadlines and escalation
     */
    public CriticalResultService(CriticalResultRepository repository, CriticalActions actions,
                                 TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Register a critical finding in the unverified state.
     *
     * @param request registration command
     * @return the created result
     * @throws ApiException on invalid input, denial or an invisible patient
     */
    public Result register(RegisterRequest request) {
        String patientId = CriticalInputs.uuid(request.patientId(), "patientId");
        String sourceSystem = CriticalInputs.text(request.sourceSystem(), "sourceSystem", 64);
        String sourceId = CriticalInputs.text(request.sourceId(), "sourceId", 128);
        String orderReference = request.orderReference() == null ? null
                : CriticalInputs.text(request.orderReference(), "orderReference", 128);
        String accession = request.accession() == null ? null
                : CriticalInputs.text(request.accession(), "accession", 128);
        String summary = CriticalInputs.text(request.resultSummary(), "resultSummary", 500);
        String observedValue = CriticalInputs.text(request.observedValue(), "observedValue", 64);
        String unit = CriticalInputs.text(request.unit(), "unit", 32);
        boolean readBack = CriticalInputs.flag(request.readBackRequired());
        String id = CriticalInputs.newId();
        return execute(CriticalActions.REGISTER, id, sink -> {
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            repository.insert(id, patientId, sourceSystem, sourceId, orderReference, accession,
                    summary, observedValue, unit, readBack);
            repository.appendEvent(id, 1, EventType.REGISTERED, null, null, null, summary);
            success(CriticalActions.REGISTER, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Assign a responsible owner, a future deadline and the approved escalation policy.
     *
     * @param id              result identifier
     * @param request         assignment command
     * @param expectedVersion version read by the caller
     * @return the assigned result
     * @throws ApiException on denial, stale version, illegal state or a roster gap
     */
    public Result assign(String id, AssignRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String ownerSubject = CriticalInputs.subject(request.ownerSubject(), "ownerSubject");
        Instant dueAt = CriticalInputs.dueAt(request.dueAt(), clock.instant());
        return execute(CriticalActions.ASSIGN, id, sink -> {
            TenantContext context = context();
            Result current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, State.UNVERIFIED);
            if (!isActiveClinician(ownerSubject)) {
                throw new ApiException(422, "INVALID_OWNER", "the owner must be an active clinician");
            }
            String primary = requiredPolicy(PRIMARY_CHANNEL_KEY, "no approved primary channel");
            String backup = requiredPolicy(BACKUP_CHANNEL_KEY, "no approved backup channel");
            String backupRecipient = requiredPolicy(BACKUP_RECIPIENT_KEY, "no approved backup recipient");
            if (!isActiveClinician(backupRecipient)) {
                repository.appendEvent(id, current.version(), EventType.ROSTER_GAP, backupRecipient,
                        backup, "failed", "no active backup clinician");
                conflict(sink, CriticalActions.ASSIGN, id, 422, "BACKUP_ROSTER_GAP",
                        "the approved backup clinician is not active");
                return null;
            }
            // The row is locked and requireVersion already proved the version,
            // so this guarded update cannot lose a concurrent change.
            repository.assign(id, expectedVersion, ownerSubject, dueAt, primary, backup, backupRecipient);
            repository.appendEvent(id, expectedVersion + 1, EventType.OWNER_ASSIGNED, ownerSubject,
                    primary, "assigned", dueAt.toString());
            success(CriticalActions.ASSIGN, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record verifier confirmation and activate the alert.
     *
     * @param id              result identifier
     * @param request         verification command
     * @param expectedVersion version read by the caller
     * @return the activated result
     * @throws ApiException when the finding is unverified prerequisites or state are missing
     */
    public Result verify(String id, VerifyRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String note = CriticalInputs.note(request.note(), 500);
        return execute(CriticalActions.VERIFY, id, sink -> {
            TenantContext context = context();
            Result current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, State.UNVERIFIED);
            if (current.ownerSubject() == null || current.dueAt() == null) {
                throw new ApiException(409, "OWNER_REQUIRED", "verification needs an owner and a due time");
            }
            repository.verify(id, expectedVersion, context.subject(), clock.instant());
            repository.appendEvent(id, expectedVersion + 1, EventType.VERIFIED, current.ownerSubject(),
                    null, "verified", note);
            success(CriticalActions.VERIFY, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record one escalation notification attempt using the policy-approved channel.
     *
     * @param id              result identifier
     * @param request         escalation command
     * @param expectedVersion version read by the caller
     * @return the unchanged result; an attempt is a fact, not a state change
     * @throws ApiException on denial, stale version, wrong channel or a roster gap
     */
    public Result escalate(String id, EscalateRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String channel = CriticalInputs.channel(request.channel(), "channel");
        String recipient = CriticalInputs.subject(request.recipient(), "recipient");
        String deliveryOutcome = CriticalInputs.outcome(request.outcome());
        String detail = CriticalInputs.note(request.detail(), 500);
        boolean systemDown = CriticalInputs.flag(request.systemDown());
        return execute(CriticalActions.ESCALATE, id, sink -> {
            TenantContext context = context();
            Result current = repository.find(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, State.AWAITING_ACK);
            boolean overdueOrDown = systemDown || !clock.instant().isBefore(current.dueAt());
            if (overdueOrDown) {
                if (!isActiveClinician(current.backupRecipient())) {
                    repository.appendEvent(id, current.version(), EventType.ROSTER_GAP,
                            current.backupRecipient(), current.backupChannel(), "failed",
                            "no active backup clinician");
                    conflict(sink, CriticalActions.ESCALATE, id, 409, "ROSTER_GAP",
                            "no active approved backup recipient");
                    return null;
                }
                if (!Objects.equals(current.backupChannel(), channel)
                        || !Objects.equals(current.backupRecipient(), recipient)) {
                    throw new ApiException(422, "BACKUP_CHANNEL_REQUIRED",
                            "an overdue or offline alert must use the approved backup channel");
                }
            } else if (!Objects.equals(current.primaryChannel(), channel)
                    || !Objects.equals(current.ownerSubject(), recipient)) {
                throw new ApiException(422, "PRIMARY_CHANNEL_REQUIRED",
                        "an in-hours alert must use the approved primary channel and owner");
            }
            repository.appendEvent(id, current.version(), EventType.ESCALATION_ATTEMPT, recipient,
                    channel, deliveryOutcome, detail);
            success(CriticalActions.ESCALATE, id);
            return current;
        });
    }

    /**
     * Record the receiver's verbatim read-back of the critical value.
     *
     * @param id              result identifier
     * @param request         read-back command
     * @param expectedVersion version read by the caller
     * @return the unchanged result
     * @throws ApiException when read-back is not required, not possible or already recorded
     */
    public Result readBack(String id, ReadBackRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String repeated = CriticalInputs.text(request.repeatedValue(), "repeatedValue", 64);
        return execute(CriticalActions.READ_BACK, id, sink -> {
            Result current = repository.find(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, State.AWAITING_ACK);
            if (!current.readBackRequired()) {
                throw new ApiException(409, "READ_BACK_NOT_REQUIRED", "this result did not require read-back");
            }
            if (!repository.hasDeliveredAttempt(id, current.version())) {
                throw new ApiException(409, "NO_DELIVERED_ATTEMPT", "no delivered attempt to read back");
            }
            if (repository.hasEvent(id, EventType.READ_BACK)) {
                throw new ApiException(409, "ALREADY_READ_BACK", "read-back was already recorded");
            }
            repository.appendEvent(id, current.version(), EventType.READ_BACK, current.ownerSubject(),
                    null, "success", repeated);
            success(CriticalActions.READ_BACK, id);
            return current;
        });
    }

    /**
     * Acknowledge the exact revision as an authorized clinician.
     *
     * @param id              result identifier
     * @param request         acknowledgment command
     * @param expectedVersion version read by the caller
     * @return the acknowledged result
     * @throws ApiException when delivery, required read-back or state preconditions are missing
     */
    public Result acknowledge(String id, AcknowledgeRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String note = CriticalInputs.note(request.note(), 500);
        return execute(CriticalActions.ACKNOWLEDGE, id, sink -> {
            Result current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, State.AWAITING_ACK);
            if (!repository.hasDeliveredAttempt(id, current.version())) {
                throw new ApiException(409, "NO_DELIVERED_ATTEMPT",
                        "delivery alone is not a clinical acknowledgment");
            }
            if (current.readBackRequired() && !repository.hasEvent(id, EventType.READ_BACK)) {
                throw new ApiException(409, "READ_BACK_REQUIRED", "a verbatim read-back is required first");
            }
            repository.acknowledge(id, expectedVersion);
            // The fact is bound to the exact revision the clinician acknowledged.
            repository.appendEvent(id, expectedVersion, EventType.ACKNOWLEDGED, current.ownerSubject(),
                    null, "acknowledged", note);
            success(CriticalActions.ACKNOWLEDGE, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record clinical follow-up and close the alert.
     *
     * @param id              result identifier
     * @param request         follow-up command
     * @param expectedVersion version read by the caller
     * @return the closed result
     * @throws ApiException on denial, stale version or an unacknowledged alert
     */
    public Result followUp(String id, FollowUpRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String note = CriticalInputs.note(request.note(), 500);
        return execute(CriticalActions.FOLLOW_UP, id, sink -> {
            Result current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireState(current, State.ACKNOWLEDGED);
            repository.close(id, expectedVersion);
            repository.appendEvent(id, expectedVersion + 1, EventType.FOLLOW_UP, current.ownerSubject(),
                    null, "followed_up", note);
            success(CriticalActions.FOLLOW_UP, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record a patient release. This never advances clinical acknowledgment.
     *
     * @param id              result identifier
     * @param request         release command
     * @param expectedVersion version read by the caller
     * @return the unchanged result
     * @throws ApiException on denial, stale version or an unknown result
     */
    public Result release(String id, ReleaseRequest request, int expectedVersion) {
        CriticalInputs.uuid(id, "id");
        String note = CriticalInputs.note(request.note(), 500);
        return execute(CriticalActions.RELEASE, id, sink -> {
            Result current = repository.find(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            repository.appendEvent(id, current.version(), EventType.PATIENT_RELEASED, null,
                    null, "released", note);
            success(CriticalActions.RELEASE, id);
            return current;
        });
    }

    /**
     * Read one result with its complete event trail.
     *
     * @param id result identifier
     * @return the result and its immutable facts
     * @throws ApiException on denial or an invisible result
     */
    public View get(String id) {
        CriticalInputs.uuid(id, "id");
        return execute(CriticalActions.READ, id, sink -> {
            Result result = repository.find(id).orElseThrow(notFound());
            success(CriticalActions.READ, id);
            return new View(result, repository.events(id));
        });
    }

    /**
     * Build the operational report of overdue alerts, roster gaps and failed notifications.
     *
     * @return the XCH-05 report
     * @throws ApiException when the caller lacks read permission
     */
    public Report report() {
        return execute(CriticalActions.READ, "REPORT", sink -> {
            Report report = new Report(repository.overdue(clock.instant()), repository.rosterGaps(),
                    repository.failedNotifications());
            success(CriticalActions.READ, "REPORT");
            return report;
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
            return work.apply(sink);
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

    private boolean isActiveClinician(String subject) {
        // Both folds are cheap and tenant-bound. Evaluate them together so a
        // revoked membership cannot leave a stale role grant authoritative, and
        // so the authorization decision has no hidden short-circuit branch.
        return Boolean.logicalAnd(
                repository.hasActiveMembership(subject),
                repository.activeRoles(subject).contains(CLINICIAN_ROLE));
    }

    private String requiredPolicy(String key, String message) {
        return repository.approvedSetting(key)
                .orElseThrow(() -> new ApiException(422, "NO_ESCALATION_POLICY", message));
    }

    private void requireVersion(Result current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw staleVersion();
        }
    }

    private void requireState(Result current, State expected) {
        if (current.state() != expected) {
            throw new ApiException(409, "INVALID_TRANSITION",
                    "the critical result cannot be changed from " + current.state().wire());
        }
    }

    private void success(String action, String resourceId) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
    }

    private void conflict(Sink sink, String action, String resourceId, int status, String code,
                          String message) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "CONFLICT");
        sink.failure = new ApiException(status, code, message);
    }

    private TenantContext context() {
        return TenantContext.current();
    }

    private Supplier<ApiException> notFound() {
        return () -> new ApiException(404, "NOT_FOUND", "critical result not found");
    }

    private ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the critical result changed since it was read");
    }

    /**
     * Carries a durable failure out of a committed transaction so its audit and
     * roster-gap event survive while the caller still receives an error.
     */
    private static final class Sink {
        private ApiException failure;
    }
}
