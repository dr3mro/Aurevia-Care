package com.aurevia.messaging.acknowledgment;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Fact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ResolveCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Timeline;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ViewCommand;
import com.aurevia.tenant.TenantContext;

/**
 * Domain service for publication, notification dispatch, first in-app view
 * and explicit patient acknowledgment as four separate append-only facts.
 *
 * <p>Every protected action derives the active organization and subject from
 * the request context, re-checks membership and role, and re-checks the verified
 * chart and active connection on patient actions. A view never acknowledges; an
 * acknowledgment confirms receipt of one exact revision only; a generic
 * notification never carries clinical content. Overdue requested
 * acknowledgments are routed to the unit that owns the instruction.</p>
 */
@Service
public class InstructionFactService {

    /** Business purpose recorded on every audit fact. */
    public static final String PURPOSE = "INSTRUCTION_FACT";
    /** Audited scope for these patient-message facts. */
    public static final String SCOPE = "PATIENT_MESSAGE";
    /** Generic notification key: the only payload a dispatch fact may carry. */
    public static final String GENERIC_NOTIFICATION_KEY = "NEW_INSTRUCTION";

    private static final String PUBLISH = InstructionFactActions.PUBLISH;
    private static final String DISPATCH = InstructionFactActions.DISPATCH;
    private static final String FIRST_VIEW = InstructionFactActions.FIRST_VIEW;
    private static final String ACKNOWLEDGE = InstructionFactActions.ACKNOWLEDGE;
    private static final String FOLLOW_UP_LIST = InstructionFactActions.FOLLOW_UP_LIST;
    private static final String FOLLOW_UP_RESOLVE = InstructionFactActions.FOLLOW_UP_RESOLVE;

    private final InstructionFactStore store;
    private final InstructionFactActions actions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store   tenant-scoped fact store
     * @param actions bounded action matrix
     * @param clock   injected clock for deterministic fact timestamps
     */
    public InstructionFactService(InstructionFactStore store, InstructionFactActions actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Publish one instruction revision and optionally record a generic dispatch fact.
     *
     * @param command validated publish command
     * @return the authoritative publication fact
     * @throws ApiException on invalid input, denial, concealment or idempotency conflict
     */
    public Publication publish(PublishCommand command) {
        String id = InstructionFactInputs.uuid(command.instructionId(), "instructionId");
        String patientId = InstructionFactInputs.uuid(command.patientId(), "patientId");
        String unitId = InstructionFactInputs.uuid(command.unitId(), "unitId");
        int revision = InstructionFactInputs.version(command.revision());
        String contentRef = InstructionFactInputs.text(command.contentRef(), "contentRef", 128);
        Instant dueAt = InstructionFactInputs.dueAt(command.acknowledgmentDueAt(), "acknowledgmentDueAt");
        String clientId = InstructionFactInputs.text(command.clientId(), "clientId", 128);
        if (command.requiresAcknowledgment() && dueAt == null) {
            throw new ApiException(422, "ACKNOWLEDGMENT_DUE_DATE_REQUIRED",
                    "a requested acknowledgment needs a due instant for overdue routing");
        }
        TenantContext context = requireContext();
        authorize(context, PUBLISH, id);
        if (!store.isStaffAssignedToUnit(context.organizationId(), context.subject(), unitId)) {
            throw deny(context, PUBLISH, id, 403, "FORBIDDEN", "the unit is not assigned to this actor");
        }
        String digest = String.join("|", id, patientId, unitId, Integer.toString(revision), contentRef,
                Boolean.toString(command.requiresAcknowledgment()), String.valueOf(dueAt),
                Boolean.toString(command.sendNotification()));
        Optional<String> recorded = recordedClientDigest(context, PUBLISH, id, clientId, digest);
        if (recorded.isEmpty()) {
            Instruction latest = store.latestInstruction(context.organizationId(), id).orElse(null);
            if (latest != null && (!patientId.equals(latest.patientId())
                    || !unitId.equals(latest.unitId()))) {
                throw deny(context, PUBLISH, id, 409, "INSTRUCTION_CONTEXT_CONFLICT",
                        "a revision cannot change the verified patient or owning unit");
            }
            // An older revision is refused; an equal revision is accepted only
            // when its content matches the stored revision exactly.
            if (latest != null && revision < latest.revision()) {
                throw deny(context, PUBLISH, id, 409, "STALE_REVISION",
                        "a revision must not be older than the latest stored revision");
            }
            if (!store.hasActiveConnection(context.organizationId(), patientId, unitId)) {
                throw deny(context, PUBLISH, id, 403, "FORBIDDEN",
                        "no active connection for this patient and unit");
            }
        }
        if (recorded.isPresent()) {
            return committedPublication(context, id, revision);
        }
        reserveClientDigest(context, PUBLISH, id, clientId, digest);
        Instruction candidate = new Instruction(id, context.organizationId(), patientId, unitId,
                revision, contentRef, command.requiresAcknowledgment(), dueAt);
        Instruction stored = store.putInstruction(context.organizationId(), candidate);
        if (!candidate.equals(stored)) {
            throw deny(context, PUBLISH, id, 409, "REVISION_CONFLICT",
                    "this revision was already published with different content");
        }
        Publication publication = store.putPublication(context.organizationId(),
                new Publication(context.organizationId(), id, revision, context.subject(), clock.instant()));
        if (command.sendNotification()) {
            store.appendDispatch(context.organizationId(), new Dispatch(context.organizationId(), id,
                    revision, GENERIC_NOTIFICATION_KEY, context.correlationId(), clock.instant()));
        }
        audit(context, PUBLISH, id, "SUCCESS");
        return publication;
    }

    /**
     * Retry a generic notification dispatch as a new, separate fact.
     *
     * @param command validated retry command
     * @return the authoritative dispatch fact
     * @throws ApiException on invalid input, denial, concealment or idempotency conflict
     */
    public Dispatch retryDispatch(RetryCommand command) {
        String id = InstructionFactInputs.uuid(command.instructionId(), "instructionId");
        int revision = InstructionFactInputs.version(command.revision());
        String clientId = InstructionFactInputs.text(command.clientId(), "clientId", 128);
        TenantContext context = requireContext();
        authorize(context, DISPATCH, id);
        Instruction instruction = publishedInstruction(context, id, revision, DISPATCH);
        if (!store.isStaffAssignedToUnit(context.organizationId(), context.subject(), instruction.unitId())) {
            throw deny(context, DISPATCH, id, 403, "FORBIDDEN", "the unit is not assigned to this actor");
        }
        String digest = id + "|" + revision;
        Optional<String> recorded = recordedClientDigest(context, DISPATCH, id, clientId, digest);
        if (recorded.isPresent()) {
            return committedDispatch(context, id, revision);
        }
        reserveClientDigest(context, DISPATCH, id, clientId, digest);
        Dispatch dispatch = store.appendDispatch(context.organizationId(), new Dispatch(
                context.organizationId(), id, revision, GENERIC_NOTIFICATION_KEY,
                context.correlationId(), clock.instant()));
        audit(context, DISPATCH, id, "SUCCESS");
        return dispatch;
    }

    /**
     * Record the first in-app view of one revision. Repeated views return the
     * original fact and never produce an acknowledgment.
     *
     * @param command validated view command
     * @return the authoritative first-view fact
     * @throws ApiException on invalid input, denial or concealment
     */
    public FirstView markFirstView(ViewCommand command) {
        String id = InstructionFactInputs.uuid(command.instructionId(), "instructionId");
        int revision = InstructionFactInputs.version(command.revision());
        InstructionFactInputs.text(command.clientId(), "clientId", 128);
        TenantContext context = requireContext();
        authorize(context, FIRST_VIEW, id);
        Instruction instruction = publishedInstruction(context, id, revision, FIRST_VIEW);
        requirePatientAccess(context, instruction, FIRST_VIEW);
        FirstView firstView = store.putFirstViewIfAbsent(context.organizationId(), new FirstView(
                context.organizationId(), id, revision, context.subject(), clock.instant()));
        audit(context, FIRST_VIEW, id, "SUCCESS");
        return firstView;
    }

    /**
     * Acknowledge receipt of one exact instruction revision.
     *
     * <p>The acknowledgment is receipt-only: it is not consent, task completion
     * or a critical-result acknowledgment. A stale revision fails closed and a
     * reused client key with a different revision conflicts. The receipt is
     * attributed to the authenticated patient account; several verified
     * accounts may link to one chart, so each records its own fact.</p>
     *
     * @param command validated acknowledgment command
     * @return the authoritative receipt-only acknowledgment
     * @throws ApiException on invalid input, denial, concealment, a stale revision or conflict
     */
    public Acknowledgment acknowledge(AcknowledgeCommand command) {
        String id = InstructionFactInputs.uuid(command.instructionId(), "instructionId");
        int revision = InstructionFactInputs.version(command.revision());
        String clientId = InstructionFactInputs.text(command.clientId(), "clientId", 128);
        TenantContext context = requireContext();
        authorize(context, ACKNOWLEDGE, id);
        Instruction instruction = publishedInstruction(context, id, revision, ACKNOWLEDGE);
        requirePatientAccess(context, instruction, ACKNOWLEDGE);
        if (!instruction.requiresAcknowledgment()) {
            throw deny(context, ACKNOWLEDGE, id, 422, "ACKNOWLEDGMENT_NOT_REQUESTED",
                    "this revision did not request an acknowledgment");
        }
        String digest = id + "|" + revision;
        Optional<String> recorded = recordedClientDigest(context, ACKNOWLEDGE, id, clientId, digest);
        if (recorded.isEmpty()) {
            Instruction latest = store.latestInstruction(context.organizationId(), id).orElseThrow();
            if (latest.revision() != revision) {
                throw deny(context, ACKNOWLEDGE, id, 409, "STALE_REVISION",
                        "a newer instruction revision exists");
            }
        }
        if (recorded.isPresent()) {
            return committedAcknowledgment(context, id, revision);
        }
        reserveClientDigest(context, ACKNOWLEDGE, id, clientId, digest);
        Acknowledgment acknowledgment = store.putAcknowledgmentIfAbsent(context.organizationId(),
                new Acknowledgment(context.organizationId(), id, revision, context.subject(),
                        AcknowledgmentScope.RECEIPT, clock.instant()));
        audit(context, ACKNOWLEDGE, id, "SUCCESS");
        return acknowledgment;
    }

    /**
     * Detect and route overdue requested acknowledgments to their owning unit.
     *
     * @param unitId the unit whose follow-up queue is read
     * @return open, unacknowledged follow-ups for that unit
     * @throws ApiException on invalid input, denial or concealment
     */
    public List<FollowUp> overdueFollowUps(String unitId) {
        String unit = InstructionFactInputs.uuid(unitId, "unitId");
        TenantContext context = requireContext();
        authorize(context, FOLLOW_UP_LIST, unit);
        if (!store.isStaffAssignedToUnit(context.organizationId(), context.subject(), unit)) {
            throw deny(context, FOLLOW_UP_LIST, unit, 403, "FORBIDDEN",
                    "the unit is not assigned to this actor");
        }
        Instant now = clock.instant();
        for (Instruction candidate : store.overdueCandidates(context.organizationId(), now)) {
            store.putFollowUpIfAbsent(context.organizationId(), new FollowUp(
                    context.organizationId(), candidate.id(), candidate.revision(), candidate.unitId(),
                    now, FollowUpState.ROUTED, null, null, null));
        }
        List<FollowUp> open = store.allFollowUps(context.organizationId()).stream()
                .filter(followUp -> unit.equals(followUp.unitId()))
                .filter(followUp -> followUp.state() != FollowUpState.RESOLVED)
                .filter(followUp -> store.acknowledgments(context.organizationId(),
                        followUp.instructionId(), followUp.revision()).isEmpty())
                .filter(followUp -> store.instruction(context.organizationId(),
                        followUp.instructionId(), followUp.revision())
                        .map(Instruction::requiresAcknowledgment).orElse(false))
                // A superseded revision can no longer be acknowledged by the
                // patient, so its follow-up must never appear as open work.
                .filter(followUp -> store.latestInstruction(context.organizationId(),
                        followUp.instructionId())
                        .map(latest -> latest.revision() == followUp.revision()).orElse(false))
                .sorted(Comparator.comparing(FollowUp::instructionId)
                        .thenComparingInt(FollowUp::revision))
                .toList();
        audit(context, FOLLOW_UP_LIST, unit, "SUCCESS");
        return open;
    }

    /**
     * Resolve an overdue follow-up with a recorded reason.
     *
     * @param command validated resolve command
     * @return the resolved follow-up
     * @throws ApiException on invalid input, denial, concealment or a stale state
     */
    public FollowUp resolveFollowUp(ResolveCommand command) {
        String id = InstructionFactInputs.uuid(command.instructionId(), "instructionId");
        int revision = InstructionFactInputs.version(command.revision());
        String reason = InstructionFactInputs.text(command.reason(), "reason", 256);
        TenantContext context = requireContext();
        authorize(context, FOLLOW_UP_RESOLVE, id);
        FollowUp current = store.followUps(context.organizationId(), id, revision)
                .orElseThrow(() -> deny(context, FOLLOW_UP_RESOLVE, id, 404, "NOT_FOUND",
                        "follow-up not found"));
        if (!store.isStaffAssignedToUnit(context.organizationId(), context.subject(), current.unitId())) {
            throw deny(context, FOLLOW_UP_RESOLVE, id, 404, "NOT_FOUND", "follow-up not found");
        }
        if (current.state() == FollowUpState.RESOLVED) {
            throw deny(context, FOLLOW_UP_RESOLVE, id, 409, "CONFLICT", "follow-up is already resolved");
        }
        FollowUp resolved = store.resolveFollowUp(context.organizationId(), id, revision,
                context.subject(), reason, clock.instant());
        audit(context, FOLLOW_UP_RESOLVE, id, "SUCCESS");
        return resolved;
    }

    /**
     * Read the fact timeline of one instruction.
     *
     * @param instructionId instruction identifier
     * @return immutable facts ordered by occurrence time then fact type
     * @throws ApiException on invalid input, denial or concealment
     */
    public Timeline facts(String instructionId) {
        String id = InstructionFactInputs.uuid(instructionId, "instructionId");
        TenantContext context = requireContext();
        Instruction latest = store.latestInstruction(context.organizationId(), id)
                .orElseThrow(() -> deny(context, FOLLOW_UP_LIST, id, 404, "NOT_FOUND",
                        "instruction not found"));
        if (store.activeRoles(context.organizationId(), context.subject()).contains("patient")) {
            requirePatientAccess(context, latest, FOLLOW_UP_LIST);
        } else {
            authorize(context, FOLLOW_UP_LIST, id);
            if (!store.isStaffAssignedToUnit(context.organizationId(), context.subject(), latest.unitId())) {
                throw deny(context, FOLLOW_UP_LIST, id, 404, "NOT_FOUND", "instruction not found");
            }
        }
        List<Fact> facts = store.allFacts(context.organizationId(), id).stream()
                .sorted(Comparator.comparing(Fact::occurredAt)
                        .thenComparingInt(fact -> fact.type().ordinal()))
                .toList();
        audit(context, FOLLOW_UP_LIST, id, "SUCCESS");
        return new Timeline(facts);
    }

    private Instruction publishedInstruction(TenantContext context, String id, int revision, String action) {
        Instruction instruction = store.instruction(context.organizationId(), id, revision)
                .orElseThrow(() -> deny(context, action, id, 404, "NOT_FOUND", "instruction not found"));
        if (store.publications(context.organizationId(), id, revision).isEmpty()) {
            throw deny(context, action, id, 404, "NOT_FOUND", "instruction was not published");
        }
        return instruction;
    }

    private void requirePatientAccess(TenantContext context, Instruction instruction, String action) {
        if (!store.verifiedPatientCharts(context.organizationId(), context.subject())
                .contains(instruction.patientId())) {
            throw deny(context, action, instruction.id(), 404, "NOT_FOUND", "instruction not found");
        }
        if (!store.hasActiveConnection(context.organizationId(), instruction.patientId(),
                instruction.unitId())) {
            throw deny(context, action, instruction.id(), 403, "FORBIDDEN",
                    "no active connection for this patient and unit");
        }
    }

    private Optional<String> recordedClientDigest(TenantContext context, String action, String id,
                                                  String clientId, String digest) {
        Optional<String> recorded = store.clientDigest(context.organizationId(), context.subject(),
                action, clientId);
        if (recorded.isPresent() && !digest.equals(recorded.get())) {
            throw deny(context, action, id, 409, "IDEMPOTENCY_CONFLICT",
                    "the idempotency key was used with a different request");
        }
        return recorded;
    }

    private void reserveClientDigest(TenantContext context, String action, String id,
                                     String clientId, String digest) {
        if (!store.putClientDigest(context.organizationId(), context.subject(), action, clientId, digest)) {
            throw deny(context, action, id, 409, "CONFLICT",
                    "the idempotency key was already recorded");
        }
    }

    private Publication committedPublication(TenantContext context, String id, int revision) {
        return store.publications(context.organizationId(), id, revision).stream().findFirst()
                .orElseThrow(() -> deny(context, PUBLISH, id, 409, "CONFLICT",
                        "the recorded command has no committed publication"));
    }

    private Dispatch committedDispatch(TenantContext context, String id, int revision) {
        return store.dispatches(context.organizationId(), id, revision).stream().findFirst()
                .orElseThrow(() -> deny(context, DISPATCH, id, 409, "CONFLICT",
                        "the recorded command has no committed dispatch"));
    }

    private Acknowledgment committedAcknowledgment(TenantContext context, String id, int revision) {
        return store.acknowledgments(context.organizationId(), id, revision).stream()
                .filter(acknowledgment -> context.subject().equals(acknowledgment.actor()))
                .findFirst()
                .orElseThrow(() -> deny(context, ACKNOWLEDGE, id, 409, "CONFLICT",
                        "the recorded command has no committed acknowledgment"));
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private void authorize(TenantContext context, String action, String resourceId) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())
                || store.activeRoles(context.organizationId(), context.subject()).stream()
                        .noneMatch(role -> actions.allows(action, role))) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private ApiException deny(TenantContext context, String action, String resourceId, int status,
                              String code, String message) {
        String outcome = switch (status) {
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 422 -> code;
            default -> "DENIED";
        };
        audit(context, action, resourceId, outcome);
        return new ApiException(status, code, message);
    }

    private void audit(TenantContext context, String action, String resourceId, String outcome) {
        store.appendAudit(new AuditFact(context.organizationId(), context.subject(), action,
                resourceId, outcome, PURPOSE, SCOPE, context.correlationId(), clock.instant()));
    }
}
