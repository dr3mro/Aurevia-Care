package com.aurevia.smartclinic.messaging.s_g3_05;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.AssignRequest;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.CloseRequest;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.Conversation;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.Inbox;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.Message;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.OpenRequest;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.ReplyRequest;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.ResolveRequest;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.SenderKind;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.State;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.TransferRequest;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.UnitKey;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.View;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.WorkEventType;
import com.aurevia.smartclinic.messaging.s_g3_05.ConversationContract.WorkQueue;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-02 conversation lifecycle orchestration.
 *
 * <p>Each command runs in one tenant-guarded transaction: membership and roles
 * are re-derived, the action and unit capability are checked, the row is locked,
 * the version and lifecycle state are verified, and the conversation change,
 * its append-only facts and the audit event commit together. A patient reply to
 * a resolved thread reopens the work item only while the verified patient link
 * is still active; a closed thread is read-only.</p>
 */
@Service
public class ConversationService {

    private static final String SCOPE = "CONVERSATION";
    private static final String RESOURCE_TYPE = "CONVERSATION";

    private final ConversationRepository repository;
    private final ConversationActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded conversation repository
     * @param actions      bounded action and unit matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public ConversationService(ConversationRepository repository, ConversationActions actions,
                               TransactionTemplate transactions, AuditAppender audit) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Open a conversation through a verified patient connection.
     *
     * @param request open command
     * @return the created conversation
     * @throws ApiException on invalid input, denial or an invisible patient or link
     */
    public Conversation open(OpenRequest request) {
        String patientId = ConversationInputs.uuid(request.patientId(), "patientId");
        String patientSubject = ConversationInputs.subject(request.patientSubject(), "patientSubject");
        UnitKey unit = ConversationInputs.unitKey(request.unitKey());
        String connectionId = ConversationInputs.opaqueId(request.connectionId(), "connectionId");
        String topic = ConversationInputs.topic(request.topic());
        String id = ConversationInputs.newId();
        return execute(ConversationActions.CONVERSATION_OPEN, id, caller -> {
            if (!repository.patientExists(patientId)) {
                throw notFound("patient not found");
            }
            if (!repository.patientLinkActive(patientSubject, patientId)) {
                throw notFound("verified patient link not found");
            }
            if (caller.patient() && !Objects.equals(caller.subject(), patientSubject)) {
                throw forbidden("a patient may only open their own conversation");
            }
            if (!caller.patient() && !actions.allowsUnit(unit, caller.role())) {
                throw forbidden("the caller is not eligible for this care unit");
            }
            State state = caller.patient() ? State.AWAITING_STAFF : State.AWAITING_PATIENT;
            repository.insertConversation(id, patientId, patientSubject, unit.wire(), connectionId,
                    topic, state.wire());
            repository.appendEvent(id, 1, WorkEventType.CREATED.wire(), caller.subject(), null,
                    state.wire(), "conversation opened");
            success(ConversationActions.CONVERSATION_OPEN, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Post a reply. An inbound patient reply to a resolved thread reopens it.
     *
     * @param id              conversation identifier
     * @param request         reply command
     * @param expectedVersion version read by the caller
     * @return the conversation and both trails
     * @throws ApiException on denial, stale version, a closed thread or invalid input
     */
    public View reply(String id, ReplyRequest request, int expectedVersion) {
        String body = ConversationInputs.body(request.body());
        return execute(ConversationActions.CONVERSATION_REPLY, id, caller -> {
            Conversation current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireWritable(current);
            requireAccess(caller, current);
            if (caller.patient()
                    && !repository.patientLinkActive(current.patientSubject(), current.patientId())) {
                throw notFound("verified patient link not found");
            }
            SenderKind kind = caller.patient() ? SenderKind.PATIENT : SenderKind.STAFF;
            State next = caller.patient() ? State.AWAITING_STAFF : State.AWAITING_PATIENT;
            int sequence = repository.nextSequence(id);
            repository.insertMessage(id, sequence, kind.wire(), caller.subject(), caller.role(), body);
            repository.updateState(id, expectedVersion, next.wire());
            if (current.state() == State.RESOLVED) {
                repository.appendEvent(id, expectedVersion + 1, WorkEventType.REOPENED.wire(), caller.subject(),
                        current.state().wire(), next.wire(), "reply reopened the work item");
            }
            success(ConversationActions.CONVERSATION_REPLY, id);
            return view(id);
        });
    }

    /**
     * Assign an open work item to an eligible staff subject in the same unit.
     *
     * @param id              conversation identifier
     * @param request         assignment command
     * @param expectedVersion version read by the caller
     * @return the updated conversation
     * @throws ApiException on denial, stale version, a closed thread or an invalid assignee
     */
    public Conversation assign(String id, AssignRequest request, int expectedVersion) {
        return reassign(id, request.assigneeSubject(), request.reason(), expectedVersion,
                WorkEventType.ASSIGNED, ConversationActions.WORK_ASSIGN);
    }

    /**
     * Transfer a work item to another eligible subject in the same unit.
     *
     * @param id              conversation identifier
     * @param request         transfer command
     * @param expectedVersion version read by the caller
     * @return the updated conversation
     * @throws ApiException on denial, stale version, a closed thread or an invalid target
     */
    public Conversation transfer(String id, TransferRequest request, int expectedVersion) {
        return reassign(id, request.toSubject(), request.reason(), expectedVersion,
                WorkEventType.TRANSFERRED, ConversationActions.WORK_TRANSFER);
    }

    private Conversation reassign(String id, String target, String reason, int expectedVersion,
                                  WorkEventType eventType, String action) {
        String assignee = ConversationInputs.subject(target, "assigneeSubject");
        String why = ConversationInputs.reason(reason);
        return execute(action, id, caller -> {
            Conversation current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireWritable(current);
            requireAccess(caller, current);
            UnitKey unit = UnitKey.from(current.unitKey());
            requireEligibleStaff(assignee, unit);
            repository.assign(id, expectedVersion, assignee);
            repository.appendEvent(id, expectedVersion + 1, eventType.wire(), caller.subject(),
                    current.state().wire(), current.state().wire(), why);
            success(action, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Resolve a work item with a reason. A later reply can reopen it.
     *
     * @param id              conversation identifier
     * @param request         resolve command
     * @param expectedVersion version read by the caller
     * @return the updated conversation
     * @throws ApiException on denial, stale version, a closed thread or invalid input
     */
    public Conversation resolve(String id, ResolveRequest request, int expectedVersion) {
        String why = ConversationInputs.reason(request.reason());
        return transitionWithReason(id, why, expectedVersion, State.RESOLVED,
                WorkEventType.RESOLVED, ConversationActions.WORK_RESOLVE);
    }

    /**
     * Close a thread with a reason; it becomes read-only for every viewer.
     *
     * @param id              conversation identifier
     * @param request         close command
     * @param expectedVersion version read by the caller
     * @return the updated conversation
     * @throws ApiException on denial, stale version, an already closed thread or invalid input
     */
    public Conversation close(String id, CloseRequest request, int expectedVersion) {
        String why = ConversationInputs.reason(request.reason());
        return transitionWithReason(id, why, expectedVersion, State.CLOSED,
                WorkEventType.CLOSED, ConversationActions.WORK_CLOSE);
    }

    private Conversation transitionWithReason(String id, String why, int expectedVersion,
                                              State target, WorkEventType eventType, String action) {
        return execute(action, id, caller -> {
            Conversation current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireWritable(current);
            requireAccess(caller, current);
            repository.updateState(id, expectedVersion, target.wire());
            repository.appendEvent(id, expectedVersion + 1, eventType.wire(), caller.subject(),
                    current.state().wire(), target.wire(), why);
            success(action, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Record that the caller has viewed the thread, advancing their unread watermark.
     *
     * @param id              conversation identifier
     * @param expectedVersion version read by the caller
     * @return the updated conversation
     * @throws ApiException on denial, stale version or an invisible conversation
     */
    public Conversation markRead(String id, int expectedVersion) {
        return execute(ConversationActions.CONVERSATION_READ, id, caller -> {
            Conversation current = repository.loadForUpdate(id).orElseThrow(notFound());
            requireVersion(current, expectedVersion);
            requireAccess(caller, current);
            SenderKind opposite = caller.patient() ? SenderKind.STAFF : SenderKind.PATIENT;
            int sequence = repository.messages(id).stream()
                    .filter(message -> message.senderKind() == opposite)
                    .mapToInt(Message::sequence)
                    .max().orElse(0);
            if (caller.patient()) {
                repository.markPatientRead(id, expectedVersion, sequence);
            } else {
                repository.markStaffRead(id, expectedVersion, sequence);
            }
            success(ConversationActions.CONVERSATION_READ, id);
            return repository.find(id).orElseThrow();
        });
    }

    /**
     * Read one conversation with its ordered message and work-item trails.
     *
     * @param id conversation identifier
     * @return the thread view
     * @throws ApiException on denial or an invisible conversation
     */
    public View get(String id) {
        return execute(ConversationActions.CONVERSATION_READ, id, caller -> {
            Conversation current = repository.find(id).orElseThrow(notFound());
            requireAccess(caller, current);
            success(ConversationActions.CONVERSATION_READ, id);
            return view(id);
        });
    }

    /**
     * Read the verified patient's inbox with derived unread counts.
     *
     * @return the inbox
     * @throws ApiException when the caller has no patient inbox permission
     */
    public Inbox inbox() {
        return execute(ConversationActions.INBOX_READ, "INBOX", caller -> {
            Inbox inbox = new Inbox(repository.inbox(caller.subject()));
            success(ConversationActions.INBOX_READ, "INBOX");
            return inbox;
        });
    }

    /**
     * Read the role-scoped staff work queue.
     *
     * @return conversations in the units the caller may work
     * @throws ApiException when the caller has no work-queue permission
     */
    public WorkQueue workQueue() {
        return execute(ConversationActions.WORK_QUEUE_READ, "WORK_QUEUE", caller -> {
            List<String> units = new ArrayList<>();
            for (UnitKey unit : UnitKey.values()) {
                if (actions.allowsUnit(unit, caller.role())) {
                    units.add(unit.wire());
                }
            }
            WorkQueue queue = new WorkQueue(repository.workQueue(units));
            success(ConversationActions.WORK_QUEUE_READ, "WORK_QUEUE");
            return queue;
        });
    }

    private View view(String id) {
        return new View(repository.find(id).orElseThrow(), repository.messages(id), repository.events(id));
    }

    private <T> T execute(String action, String resourceId, Function<Caller, T> work) {
        Sink sink = new Sink();
        T result = transactions.execute(status -> {
            TenantContext context = context();
            Caller caller = authorize(context, action, resourceId, sink);
            return caller == null ? null : work.apply(caller);
        });
        if (sink.failure != null) {
            throw sink.failure;
        }
        return result;
    }

    private Caller authorize(TenantContext context, String action, String resourceId, Sink sink) {
        String subject = context.subject();
        if (!repository.hasActiveMembership(subject)) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            sink.failure = forbidden("no active membership");
            return null;
        }
        List<String> roles = repository.activeRoles(subject);
        String role = roles.stream().filter(available -> actions.allows(action, available)).findFirst().orElse(null);
        if (role == null) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            sink.failure = forbidden("missing " + action + " permission");
            return null;
        }
        return new Caller(subject, role, "patient".equals(role));
    }

    private void requireAccess(Caller caller, Conversation current) {
        if (caller.patient()) {
            if (!Objects.equals(caller.subject(), current.patientSubject())) {
                throw notFound().get();
            }
            return;
        }
        if (!actions.allowsUnit(UnitKey.from(current.unitKey()), caller.role())) {
            throw forbidden("the caller is not eligible for this care unit");
        }
    }

    private void requireEligibleStaff(String subject, UnitKey unit) {
        if (!repository.hasActiveMembership(subject)) {
            throw new ApiException(422, "INVALID_ASSIGNEE", "the target must be an active staff member");
        }
        List<String> roles = repository.activeRoles(subject);
        boolean eligible = roles.stream()
                .anyMatch(role -> ConversationActions.STAFF_ROLES.contains(role) && actions.allowsUnit(unit, role));
        if (!eligible) {
            throw new ApiException(422, "INVALID_ASSIGNEE", "the target is not eligible for this care unit");
        }
    }

    private void requireVersion(Conversation current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the conversation changed since it was read");
        }
    }

    private void requireWritable(Conversation current) {
        if (current.state() == State.CLOSED) {
            throw new ApiException(409, "CLOSED_THREAD", "a closed conversation is read-only");
        }
    }

    private void success(String action, String resourceId) {
        audit.append(context(), action, action, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
    }

    private TenantContext context() {
        return TenantContext.current();
    }

    private java.util.function.Supplier<ApiException> notFound() {
        return () -> new ApiException(404, "NOT_FOUND", "conversation not found");
    }

    private ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    private ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    /**
     * Authenticated caller capability derived inside the protected transaction.
     *
     * @param subject authenticated subject
     * @param role    the single action-eligible role used for unit checks and snapshots
     * @param patient whether the caller is a verified patient
     */
    private record Caller(String subject, String role, boolean patient) {
    }

    /**
     * Carries a durable denial out of a committed transaction so its audit survives.
     */
    private static final class Sink {
        private ApiException failure;
    }
}
