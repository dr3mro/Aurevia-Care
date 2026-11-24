package com.aurevia.messaging.conversations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.messaging.conversations.ConversationContract.AssignRequest;
import com.aurevia.messaging.conversations.ConversationContract.CloseRequest;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.Inbox;
import com.aurevia.messaging.conversations.ConversationContract.Message;
import com.aurevia.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.messaging.conversations.ConversationContract.ResolveRequest;
import com.aurevia.messaging.conversations.ConversationContract.SenderKind;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.TransferRequest;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.conversations.ConversationContract.View;
import com.aurevia.messaging.conversations.ConversationContract.WorkEventType;
import com.aurevia.messaging.conversations.ConversationContract.WorkQueue;
import com.aurevia.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-02 conversation lifecycle orchestration.
 *
 * <p>Each command runs in one tenant-guarded transaction: membership and roles
 * are re-derived, the action and unit capability are checked, the row is locked,
 * the version and lifecycle state are verified, and the conversation change,
 * its append-only facts and the audit event commit together. A patient reply to
 * a resolved thread reopens the work item only while the verified patient link
 * is still active; a closed thread is read-only.</p>
 *
 * <p>A patient must address a unit through a verified, active connection: the
 * patient, unit and connection identities stored on the conversation are derived
 * from that connection, never from the request body alone. The connection is
 * re-resolved on every reply so a revoked connection stops new messaging, and a
 * unit category that no longer matches the verified unit cannot post a message
 * (MSG-01, MSG-02, MSG-07, ARC-07). A staff caller is authorized by the bounded
 * role and unit matrix.</p>
 */
@Service
public class ConversationService {

    private static final String SCOPE = "CONVERSATION";
    private static final String RESOURCE_TYPE = "CONVERSATION";

    private final ConversationRepository repository;
    private final ConversationActions actions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final PatientConnectionVerifier connections;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded conversation repository
     * @param actions      bounded action and unit matrix
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param connections  verified patient-unit connection resolver
     */
    public ConversationService(ConversationRepository repository, ConversationActions actions,
                               TransactionTemplate transactions, AuditAppender audit,
                               PatientConnectionVerifier connections) {
        this.repository = repository;
        this.actions = actions;
        this.transactions = transactions;
        this.audit = audit;
        this.connections = connections;
    }

    /**
     * Open a conversation through a verified patient connection.
     *
     * <p>A patient caller must present a verified, active connection to the
     * named unit; the unit and connection identity stored on the conversation
     * are derived from that connection, never from the request body alone. A
     * staff caller is authorized by the bounded role and unit matrix.</p>
     *
     * @param request open command
     * @return the created conversation
     * @throws ApiException on invalid input, denial or an invisible patient, link or connection
     */
    public Conversation open(OpenRequest request) {
        String patientId = ConversationInputs.uuid(request.patientId(), "patientId");
        String patientSubject = ConversationInputs.subject(request.patientSubject(), "patientSubject");
        UnitKey unit = ConversationInputs.unitKey(request.unitKey());
        String connectionId = ConversationInputs.opaqueId(request.connectionId(), "connectionId");
        String topic = ConversationInputs.topic(request.topic());
        String id = ConversationInputs.newId();
        return execute(ConversationActions.CONVERSATION_OPEN, id, caller -> {
            // Check domain authorization before any existence lookup so a
            // patient cannot probe which patients or links exist in the tenant.
            if (caller.patient() && !Objects.equals(caller.subject(), patientSubject)) {
                throw notFound("conversation not found");
            }
            if (!repository.patientExists(patientId)) {
                throw notFound("patient not found");
            }
            if (!repository.patientLinkActive(patientSubject, patientId)) {
                throw notFound("verified patient link not found");
            }
            if (!caller.patient() && !actions.allowsUnit(unit, caller.role())) {
                throw forbidden("the caller is not eligible for this care unit");
            }
            String persistedPatient = patientId;
            String persistedUnit = unit.wire();
            String persistedConnection = connectionId;
            if (caller.patient()) {
                // MSG-01: a patient may only address a unit through a verified
                // connection. Persist the identities the server resolved from
                // that connection, never the ones the client merely named.
                VerifiedConnection verified = connections.requireActive(patientId, unit.wire(), connectionId);
                persistedPatient = verified.patientId();
                persistedUnit = verified.unitKey();
                persistedConnection = verified.connectionId();
            }
            State state = caller.patient() ? State.AWAITING_STAFF : State.AWAITING_PATIENT;
            repository.insertConversation(id, persistedPatient, patientSubject, persistedUnit,
                    persistedConnection, topic, state.wire());
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
        String conversationId = ConversationInputs.uuid(id, "id");
        String body = ConversationInputs.body(request.body());
        return execute(ConversationActions.CONVERSATION_REPLY, conversationId, caller -> {
            Conversation current = repository.loadForUpdate(conversationId).orElseThrow(notFound());
            // Authorize before version/state checks so a foreign thread is never
            // distinguished by a 412 or 409 (ARC-04 concealment).
            requireAccess(caller, current);
            requireVersion(current, expectedVersion);
            requireWritable(current);
            // Every reply is new messaging, from either side. MSG-02 reopens a
            // resolved thread only while the verified relationship still permits
            // messaging, and MSG-07 stops messaging entirely once it is revoked.
            if (!repository.patientLinkActive(current.patientSubject(), current.patientId())) {
                throw notFound("verified patient link not found");
            }
            if (caller.patient()) {
                // Re-resolve the stored connection on every patient reply. The
                // unit category is derived from the tenant directory, so a
                // revoked connection or a route that no longer matches the
                // verified unit cannot post a new message from either side.
                connections.requireActive(current.patientId(), current.unitKey(), current.connectionId());
            }
            SenderKind kind = caller.patient() ? SenderKind.PATIENT : SenderKind.STAFF;
            State next = caller.patient() ? State.AWAITING_STAFF : State.AWAITING_PATIENT;
            int sequence = repository.nextSequence(conversationId);
            repository.insertMessage(conversationId, sequence, kind.wire(), caller.subject(), caller.role(), body);
            repository.updateState(conversationId, expectedVersion, next.wire());
            if (current.state() == State.RESOLVED) {
                repository.appendEvent(conversationId, expectedVersion + 1, WorkEventType.REOPENED.wire(),
                        caller.subject(), current.state().wire(), next.wire(), "reply reopened the work item");
            }
            success(ConversationActions.CONVERSATION_REPLY, conversationId);
            return view(conversationId);
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
        String conversationId = ConversationInputs.uuid(id, "id");
        String assignee = ConversationInputs.subject(target, "assigneeSubject");
        String why = ConversationInputs.reason(reason);
        return execute(action, conversationId, caller -> {
            Conversation current = repository.loadForUpdate(conversationId).orElseThrow(notFound());
            requireAccess(caller, current);
            requireVersion(current, expectedVersion);
            requireWritable(current);
            UnitKey unit = UnitKey.from(current.unitKey());
            requireEligibleStaff(assignee, unit);
            repository.assign(conversationId, expectedVersion, assignee);
            repository.appendEvent(conversationId, expectedVersion + 1, eventType.wire(), caller.subject(),
                    current.state().wire(), current.state().wire(), why);
            success(action, conversationId);
            return repository.find(conversationId).orElseThrow();
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
        String conversationId = ConversationInputs.uuid(id, "id");
        return execute(action, conversationId, caller -> {
            Conversation current = repository.loadForUpdate(conversationId).orElseThrow(notFound());
            requireAccess(caller, current);
            requireVersion(current, expectedVersion);
            requireWritable(current);
            repository.updateState(conversationId, expectedVersion, target.wire());
            repository.appendEvent(conversationId, expectedVersion + 1, eventType.wire(), caller.subject(),
                    current.state().wire(), target.wire(), why);
            success(action, conversationId);
            return repository.find(conversationId).orElseThrow();
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
        String conversationId = ConversationInputs.uuid(id, "id");
        return execute(ConversationActions.CONVERSATION_READ, conversationId, caller -> {
            Conversation current = repository.loadForUpdate(conversationId).orElseThrow(notFound());
            requireAccess(caller, current);
            requireVersion(current, expectedVersion);
            SenderKind opposite = caller.patient() ? SenderKind.STAFF : SenderKind.PATIENT;
            int sequence = repository.messages(conversationId).stream()
                    .filter(message -> message.senderKind() == opposite)
                    .mapToInt(Message::sequence)
                    .max().orElse(0);
            if (caller.patient()) {
                repository.markPatientRead(conversationId, expectedVersion, sequence);
            } else {
                repository.markStaffRead(conversationId, expectedVersion, sequence);
            }
            success(ConversationActions.CONVERSATION_READ, conversationId);
            return repository.find(conversationId).orElseThrow();
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
        String conversationId = ConversationInputs.uuid(id, "id");
        return execute(ConversationActions.CONVERSATION_READ, conversationId, caller -> {
            Conversation current = repository.find(conversationId).orElseThrow(notFound());
            requireAccess(caller, current);
            success(ConversationActions.CONVERSATION_READ, conversationId);
            return view(conversationId);
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
