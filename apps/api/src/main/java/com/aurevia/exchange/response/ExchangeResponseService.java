package com.aurevia.exchange.response;

import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeEvent;
import com.aurevia.exchange.response.ExchangeResponseCommands.OrderView;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.SqlFailureTranslator;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-03 recipient-response invariants (XCH-01, XCH-02, XCH-03, ARC-03, OPS-02).
 *
 * <p>Each command runs in one tenant-guarded transaction: authorization is
 * re-derived, the order row is locked and its expected version checked, the
 * transition is validated, and the state change, immutable ledger writes,
 * response event and audit event commit together. A transmission fact can never
 * move this state machine; only an explicit authorized command can.</p>
 */
@Service
public class ExchangeResponseService {

    private static final String SCOPE = "XCH03";
    private static final String RESOURCE_TYPE = "EXCHANGE_ORDER";
    private static final int MAX_REASON = 500;
    private static final int MAX_ASSIGNEE = 128;
    private static final Set<String> ACCEPT_DECISIONS = Set.of("accept", "reject");
    private static final Set<String> CANCEL_DECISIONS = Set.of("confirm", "decline");

    private final ExchangeResponseRepository repository;
    private final ExchangeResponseActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded order repository
     * @param policy       local XCH-03 action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public ExchangeResponseService(ExchangeResponseRepository repository, ExchangeResponseActionPolicy policy,
                        TransactionTemplate transactions, AuditAppender audit) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Accept a released order against a locally verified patient association and
     * retain a bounded recipient copy with its permitted purpose.
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request verified association, assignee, purpose and retention policy
     * @return the accepted order
     * @throws ApiException on invalid input, denial, concealment, staleness or illegal transition
     */
    public ExchangeOrder accept(String id, int version, ExchangeResponseCommands.AcceptRequest request) {
        String patient = ExchangeResponseInputs.uuid(request.localPatientId(), "localPatientId");
        String assignee = ExchangeResponseInputs.text(request.assignee(), "assignee", MAX_ASSIGNEE, false);
        String purpose = ExchangeResponseInputs.code(request.permittedPurpose(), "permittedPurpose");
        String retention = ExchangeResponseInputs.retentionPolicy(request.retentionPolicy());
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_ACCEPT, "accept", current -> {
            requireState(current, ExchangeState.REQUESTED);
            TenantContext context = TenantContext.current();
            repository.insertRetained(context.organizationId(), ExchangeResponseInputs.newId(), current.id(),
                    current.revision(), current.contentChecksum(), purpose, retention,
                    "LEGAL_HOLD".equals(retention), context.subject());
            return new Decision(ExchangeState.ACCEPTED, patient, assignee, null, null, purpose,
                    retention, current.revision());
        });
    }

    /**
     * Reject an order with a durable reason.
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request recorded reason
     * @return the rejected order
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder reject(String id, int version, ExchangeResponseCommands.ReasonRequest request) {
        String reason = ExchangeResponseInputs.text(request.reason(), "reason", MAX_REASON, true);
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_REJECT, "reject", current -> {
            requireState(current, ExchangeState.REQUESTED);
            return new Decision(ExchangeState.REJECTED, current.localPatientId(), current.assignee(),
                    reason, current.question(), current.permittedPurpose(), current.retentionPolicy(),
                    current.revision());
        });
    }

    /**
     * Request clarification with a recorded question.
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request recorded question
     * @return the order awaiting clarification
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder clarify(String id, int version, ExchangeResponseCommands.ClarifyRequest request) {
        String question = ExchangeResponseInputs.text(request.question(), "question", MAX_REASON, true);
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_CLARIFY, "clarify", current -> {
            requireState(current, ExchangeState.REQUESTED);
            return new Decision(ExchangeState.CLARIFICATION_REQUESTED, current.localPatientId(),
                    current.assignee(), question, question, current.permittedPurpose(),
                    current.retentionPolicy(), current.revision());
        });
    }

    /**
     * Propose a change after acceptance as a new immutable linked revision. The
     * accepted snapshot is never rewritten and the recipient must respond. The
     * proposed revision lives only in the revision ledger until that response:
     * retrieval keeps serving the last accepted revision in the meantime, so a
     * rejected change can never serve unretained content (XCH-03, ARC-03).
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request change summary and checksum of the new content
     * @return the order with a change awaiting a recipient response
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder proposeChange(String id, int version, ExchangeResponseCommands.ProposeChangeRequest request) {
        String summary = ExchangeResponseInputs.text(request.changeSummary(), "changeSummary", MAX_REASON, true);
        String checksum = ExchangeResponseInputs.checksum(request.contentChecksum());
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_PROPOSE_CHANGE, "propose_change", current -> {
            // XCH-03 scopes changes to *after* acceptance: only an accepted order has
            // a verified patient, permitted purpose and retained recipient copy.
            requireState(current, ExchangeState.ACCEPTED);
            int next = current.revision() + 1;
            TenantContext context = TenantContext.current();
            repository.insertRevision(context.organizationId(), ExchangeResponseInputs.newId(), current.id(),
                    next, current.revision(), checksum, summary, context.subject());
            return new Decision(ExchangeState.CHANGE_PENDING, current.localPatientId(), current.assignee(),
                    null, current.question(), current.permittedPurpose(), current.retentionPolicy(),
                    current.revision());
        });
    }

    /**
     * Record the recipient response to a linked change. Accepting promotes the
     * linked revision to current and retains it under the purpose captured at the
     * original acceptance; rejecting leaves the accepted revision current so no
     * unretained content is ever served through retrieval (XCH-03, ARC-03).
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request accept or reject decision and reason when rejected
     * @return the resolved order
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder changeResponse(String id, int version,
                                        ExchangeResponseCommands.ChangeResponseRequest request) {
        String decision = decision(request.decision(), ACCEPT_DECISIONS);
        String reason = "reject".equals(decision)
                ? ExchangeResponseInputs.text(request.reason(), "reason", MAX_REASON, true) : null;
        String eventAction = "reject".equals(decision) ? "change_reject" : "change_accept";
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_RESPOND, eventAction, current -> {
            requireState(current, ExchangeState.CHANGE_PENDING);
            if ("reject".equals(decision)) {
                return new Decision(ExchangeState.REJECTED, current.localPatientId(), current.assignee(),
                        reason, current.question(), current.permittedPurpose(),
                        current.retentionPolicy(), current.revision());
            }
            String purpose = current.permittedPurpose();
            String retention = current.retentionPolicy();
            if (purpose == null || retention == null) {
                throw invalidTransition();
            }
            int next = current.revision() + 1;
            TenantContext context = TenantContext.current();
            Revision revision = repository.currentRevision(current.id(), next)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "released revision not found"));
            repository.insertRetained(context.organizationId(), ExchangeResponseInputs.newId(), current.id(),
                    next, revision.contentChecksum(), purpose, retention,
                    "LEGAL_HOLD".equals(retention), context.subject());
            return new Decision(ExchangeState.ACCEPTED, current.localPatientId(), current.assignee(),
                    null, current.question(), purpose, retention, next);
        });
    }

    /**
     * Request cancellation after acceptance; the recipient must still respond.
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request recorded cancellation reason
     * @return the order with a cancellation awaiting a recipient response
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder requestCancellation(String id, int version, ExchangeResponseCommands.ReasonRequest request) {
        String reason = ExchangeResponseInputs.text(request.reason(), "reason", MAX_REASON, true);
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_REQUEST_CANCEL, "request_cancellation", current -> {
            requireState(current, ExchangeState.ACCEPTED);
            return new Decision(ExchangeState.CANCELLATION_PENDING, current.localPatientId(),
                    current.assignee(), reason, current.question(), current.permittedPurpose(),
                    current.retentionPolicy(), current.revision());
        });
    }

    /**
     * Confirm or decline a cancellation request as the recipient.
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request confirm or decline decision and reason when declined
     * @return the resolved order
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder cancellationResponse(String id, int version,
                                              ExchangeResponseCommands.CancellationResponseRequest request) {
        String decision = decision(request.decision(), CANCEL_DECISIONS);
        String reason = "decline".equals(decision)
                ? ExchangeResponseInputs.text(request.reason(), "reason", MAX_REASON, true) : null;
        String eventAction = "confirm".equals(decision) ? "cancellation_confirm" : "cancellation_decline";
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_RESPOND, eventAction, current -> {
            requireState(current, ExchangeState.CANCELLATION_PENDING);
            ExchangeState target = "confirm".equals(decision)
                    ? ExchangeState.CANCELLED : ExchangeState.ACCEPTED;
            return new Decision(target, current.localPatientId(), current.assignee(), reason,
                    current.question(), current.permittedPurpose(), current.retentionPolicy(),
                    current.revision());
        });
    }

    /**
     * Revoke a release, block sender-hosted retrieval and record a downstream
     * notification with a durable event id.
     *
     * @param id      order identifier
     * @param version version read by the caller
     * @param request reason, policy and downstream notification details
     * @return the revoked order
     * @throws ApiException on the same boundaries as {@code accept}
     */
    public ExchangeOrder revoke(String id, int version, ExchangeResponseCommands.RevokeRequest request) {
        String reason = ExchangeResponseInputs.text(request.reason(), "reason", MAX_REASON, true);
        String retention = ExchangeResponseInputs.retentionPolicy(request.retentionPolicy());
        String target = ExchangeResponseInputs.text(request.target(), "target", MAX_ASSIGNEE, true);
        String channel = ExchangeResponseInputs.text(request.channel(), "channel", 32, true);
        String eventId = ExchangeResponseInputs.text(request.eventId(), "eventId", 64, true);
        return run(id, version, ExchangeResponseActionPolicy.EXCHANGE_REVOKE, "revoke", current -> {
            if (current.state().isTerminal()) {
                throw invalidTransition();
            }
            TenantContext context = TenantContext.current();
            repository.insertNotification(context.organizationId(), ExchangeResponseInputs.newId(), current.id(),
                    eventId, target, channel, "NOTIFIED", context.subject());
            return new Decision(ExchangeState.REVOKED, current.localPatientId(), current.assignee(),
                    reason, current.question(), current.permittedPurpose(), retention, current.revision());
        });
    }

    /**
     * Retrieve the current released revision. Revocation is checked here on every
     * call so no cached or out-of-date view can bypass it.
     *
     * @param id order identifier
     * @return the current released revision
     * @throws ApiException {@code 410} when the release was revoked, {@code 404}
     *                      when concealed, or on denial and staleness
     */
    public Revision retrieve(String id) {
        ExchangeResponseInputs.uuid(id, "id");
        AtomicReference<ApiException> denial = new AtomicReference<>();
        Revision result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException refused = authorize(context, ExchangeResponseActionPolicy.EXCHANGE_RETRIEVE, id);
            if (refused != null) {
                denial.set(refused);
                return null;
            }
            ExchangeOrder current = repository.findForUpdate(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "order not found"));
            if (current.state() == ExchangeState.REVOKED) {
                audit.append(context, ExchangeResponseActionPolicy.EXCHANGE_RETRIEVE,
                        ExchangeResponseActionPolicy.EXCHANGE_RETRIEVE, RESOURCE_TYPE, SCOPE, id, "DENIED");
                throw new ApiException(410, "RETRIEVAL_REVOKED", "the release has been revoked");
            }
            Revision revision = repository.currentRevision(id, current.revision())
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "released revision not found"));
            audit.append(context, ExchangeResponseActionPolicy.EXCHANGE_RETRIEVE,
                    ExchangeResponseActionPolicy.EXCHANGE_RETRIEVE, RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return revision;
        });
        if (denial.get() != null) {
            throw denial.get();
        }
        return result;
    }

    /**
     * Read one work item with its append-only ledgers.
     *
     * @param id order identifier
     * @return the order view
     * @throws ApiException on invalid id, denial or concealment
     */
    public OrderView get(String id) {
        ExchangeResponseInputs.uuid(id, "id");
        AtomicReference<ApiException> denial = new AtomicReference<>();
        OrderView result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException refused = authorize(context, ExchangeResponseActionPolicy.EXCHANGE_READ, id);
            if (refused != null) {
                denial.set(refused);
                return null;
            }
            ExchangeOrder order = repository.find(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "order not found"));
            audit.append(context, ExchangeResponseActionPolicy.EXCHANGE_READ, ExchangeResponseActionPolicy.EXCHANGE_READ,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return new OrderView(order, repository.history(id), repository.revisions(id),
                    repository.retained(id), repository.notifications(id));
        });
        if (denial.get() != null) {
            throw denial.get();
        }
        return result;
    }

    /**
     * Read the append-only response history of one work item.
     *
     * @param id order identifier
     * @return the response events, oldest first
     * @throws ApiException on the same boundaries as {@code get}
     */
    public java.util.List<ExchangeEvent> history(String id) {
        return get(id).history();
    }

    private ExchangeOrder run(String id, int version, String action, String eventAction,
                              Function<ExchangeOrder, Decision> decision) {
        ExchangeResponseInputs.uuid(id, "id");
        AtomicReference<ApiException> denial = new AtomicReference<>();
        ExchangeOrder result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException refused = authorize(context, action, id);
            if (refused != null) {
                denial.set(refused);
                return null;
            }
            ExchangeOrder current = repository.findForUpdate(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "order not found"));
            if (current.version() != version) {
                throw stale();
            }
            try {
                Decision next = decision.apply(current);
                int updated = repository.update(id, version, next.state(), next.localPatientId(),
                        next.assignee(), next.reason(), next.question(), next.permittedPurpose(),
                        next.retentionPolicy(), next.revision());
                if (updated == 0) {
                    throw stale();
                }
                repository.appendEvent(context.organizationId(), ExchangeResponseInputs.newId(), id, version + 1,
                        eventAction, current.state().wire(), next.state().wire(),
                        next.reason(), context.subject());
                audit.append(context, action, action, RESOURCE_TYPE, SCOPE, id, "SUCCESS");
                return repository.find(id).orElseThrow();
            } catch (DataIntegrityViolationException exception) {
                // A locally verified patient association and every ledger reference are
                // enforced by tenant-scoped database constraints. An unresolvable reference
                // is concealed as not found; any other integrity refusal is a conflict —
                // never an unhandled 500 (XCH-03, ARC-03, ARC-04).
                throw SqlFailureTranslator.translate(exception);
            }
        });
        if (denial.get() != null) {
            throw denial.get();
        }
        return result;
    }

    private ApiException authorize(TenantContext context, String action, String id) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, id, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, id, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing exchange permission");
        }
        return null;
    }

    private static void requireState(ExchangeOrder current, ExchangeState expected) {
        if (current.state() != expected) {
            throw invalidTransition();
        }
    }

    private static ApiException invalidTransition() {
        return new ApiException(409, "INVALID_TRANSITION", "the order cannot perform that command in its current state");
    }

    private static ApiException stale() {
        return new ApiException(412, "STALE_VERSION", "the order changed since it was read");
    }

    private static String decision(String value, Set<String> allowed) {
        String normalized = ExchangeResponseInputs.text(value, "decision", 16, true).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ApiException(422, "INVALID_DECISION", "decision is not allowed for this command");
        }
        return normalized;
    }

    /**
     * The mutable projection written by one command.
     *
     * @param state           resulting state
     * @param localPatientId  verified patient association
     * @param assignee        receiving assignee
     * @param reason          recorded reason
     * @param question        recorded question
     * @param permittedPurpose retained-copy purpose
     * @param retentionPolicy retained-copy policy
     * @param revision        current released revision
     */
    private record Decision(ExchangeState state, String localPatientId, String assignee, String reason,
                            String question, String permittedPurpose, String retentionPolicy, int revision) {
    }
}
