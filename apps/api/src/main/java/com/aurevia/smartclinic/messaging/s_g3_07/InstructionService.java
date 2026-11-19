package com.aurevia.smartclinic.messaging.s_g3_07;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.ContextType;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.DefineTemplateRequest;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.Fact;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.FactType;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.Kind;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.ReplaceRequest;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.Revision;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.SendRequest;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.State;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.Template;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.View;
import com.aurevia.smartclinic.messaging.s_g3_07.InstructionContract.WithdrawRequest;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-04 typed patient instructions.
 *
 * <p>Every command runs in one guarded operation: membership and roles are
 * re-derived for the active tenant, the connection and unit assignment are
 * re-checked, and the version precondition is verified before any state change.
 * A sent revision is immutable; a replacement or withdrawal links a new fact
 * and marks the prior revision superseded or withdrawn so future patient
 * access follows the current revision.</p>
 */
@Service
public class InstructionService {

    private final InstructionStore store;
    private final InstructionActions actions;
    private final Clock clock;

    /**
     * Create the service with the application clock.
     *
     * @param store   tenant-scoped instruction store
     * @param actions bounded action matrix
     */
    @Autowired
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public InstructionService(InstructionStore store, InstructionActions actions) {
        this(store, actions, Clock.systemUTC());
    }

    /**
     * Create the service with an injected clock for deterministic tests.
     *
     * @param store   tenant-scoped instruction store
     * @param actions bounded action matrix
     * @param clock   application clock
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    InstructionService(InstructionStore store, InstructionActions actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Approve a unit-owned instruction template version.
     *
     * @param request definition command
     * @return the approved template with approver and approval time stamped
     * @throws ApiException on denial or invalid input
     */
    public Template defineTemplate(DefineTemplateRequest request) {
        String unitId = InstructionInputs.key(request.unitId(), "unitId");
        String templateId = InstructionInputs.key(request.templateId(), "templateId");
        Kind kind = request.kind();
        String title = InstructionInputs.text(request.title(), "title", 120);
        String body = InstructionInputs.text(request.body(), "body", 2000);
        String language = InstructionInputs.language(request.language());
        ContextType contextType = request.contextType();
        return execute(InstructionActions.DEFINE_TEMPLATE, templateId, () -> {
            TenantContext context = context();
            Template template = new Template(templateId, unitId, kind, title, body, language, 1,
                    context.subject(), clock.instant(), request.clinical(), contextType);
            store.saveTemplate(template);
            return template;
        });
    }

    /**
     * Send an instruction from an approved template or bounded free text.
     *
     * @param request send command
     * @return the published revision
     * @throws ApiException on denial, missing template, invalid link or invalid input
     */
    public Revision send(SendRequest request) {
        String patientId = InstructionInputs.uuid(request.patientId(), "patientId");
        String connectionId = InstructionInputs.key(request.connectionId(), "connectionId");
        String unitId = InstructionInputs.key(request.unitId(), "unitId");
        String templateId = request.templateId() == null ? null
                : InstructionInputs.key(request.templateId(), "templateId");
        List<String> attachments = InstructionInputs.attachments(request.attachmentIds());
        Instant dueAt = InstructionInputs.dueAt(request.actionDueAt(), clock.instant());
        ContextType contextType = request.contextType();
        String contextId = contextType == null ? null : InstructionInputs.key(request.contextId(), "contextId");
        return execute(InstructionActions.SEND, connectionId, () -> {
            TenantContext context = context();
            List<String> roles = store.activeRoles(context.subject());
            String title;
            String body;
            String language;
            Kind kind;
            if (templateId != null) {
                Template template = store.approvedTemplate(unitId, templateId)
                        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "template not found"));
                if (template.clinical() && !actions.allows(InstructionActions.CLINICAL_SEND, anyRole(roles))) {
                    throw forbidden("a clinical template requires clinician authorization");
                }
                if (template.contextType() != null && template.contextType() != contextType) {
                    throw new ApiException(422, "CONTEXT_REQUIRED",
                            "this template requires " + template.contextType().wire());
                }
                title = template.title();
                body = template.body();
                language = template.language();
                kind = template.kind();
            } else {
                if (!actions.allows(InstructionActions.CLINICAL_SEND, anyRole(roles))) {
                    throw forbidden("free text requires clinician authorization");
                }
                kind = request.kind();
                if (kind == null) {
                    throw new ApiException(422, "INVALID_KIND", "a kind is required for free text");
                }
                title = InstructionInputs.text(request.title(), "title", 120);
                body = InstructionInputs.text(request.text(), "text", 2000);
                language = InstructionInputs.language(request.language());
            }
            requireConnection(connectionId, patientId, unitId);
            Revision revision = new Revision(InstructionInputs.newId(), 1, templateId, kind, title, body,
                    language, context.subject(), anyRole(roles), unitId, connectionId, patientId,
                    clock.instant(), dueAt, contextType, contextId, attachments, State.SENT,
                    null, null, null);
            store.save(revision);
            store.append(new Fact(revision.threadId(), 1, FactType.SENT, patientId, clock.instant(), title));
            store.append(new Fact(revision.threadId(), 1, FactType.DISPATCHED, patientId,
                    clock.instant(), null));
            return revision;
        });
    }

    /**
     * Publish a linked replacement revision and supersede the prior one.
     *
     * @param threadId       owning thread
     * @param request        replacement content
     * @param expectedVersion version read by the caller
     * @return the new current revision
     * @throws ApiException on denial, stale version or illegal transition
     */
    public Revision replace(String threadId, ReplaceRequest request, int expectedVersion) {
        String thread = InstructionInputs.key(threadId, "threadId");
        String title = InstructionInputs.text(request.title(), "title", 120);
        String body = InstructionInputs.text(request.text(), "text", 2000);
        String language = InstructionInputs.language(request.language());
        String reason = InstructionInputs.text(request.reason(), "reason", 500);
        List<String> attachments = InstructionInputs.attachments(request.attachmentIds());
        Instant dueAt = InstructionInputs.dueAt(request.actionDueAt(), clock.instant());
        return execute(InstructionActions.WITHDRAW, thread, () -> {
            TenantContext context = context();
            Revision current = requireCurrent(thread, expectedVersion, State.SUPERSEDED);
            int number = current.revision() + 1;
            Revision replacement = new Revision(current.threadId(), number, current.templateId(),
                    current.kind(), title, body, language, context.subject(),
                    anyRole(store.activeRoles(context.subject())), current.unitId(),
                    current.connectionId(), current.patientId(), clock.instant(), dueAt,
                    current.contextType(), current.contextId(), attachments, State.SENT, null,
                    current.revision(), null);
            store.save(replacement);
            store.supersede(thread, current.revision(), number);
            store.append(new Fact(thread, number, FactType.REPLACED, current.patientId(),
                    clock.instant(), reason));
            store.append(new Fact(thread, current.revision(), FactType.SUPERSEDED, null,
                    clock.instant(), "superseded by revision " + number));
            store.append(new Fact(thread, number, FactType.DISPATCHED, current.patientId(),
                    clock.instant(), null));
            return store.current(thread).orElseThrow();
        });
    }

    /**
     * Withdraw the current revision and stop future patient access to it.
     *
     * @param threadId       owning thread
     * @param request        withdrawal command
     * @param expectedVersion version read by the caller
     * @return the withdrawn revision
     * @throws ApiException on denial, stale version or illegal transition
     */
    public Revision withdraw(String threadId, WithdrawRequest request, int expectedVersion) {
        String thread = InstructionInputs.key(threadId, "threadId");
        String reason = InstructionInputs.text(request.reason(), "reason", 500);
        return execute(InstructionActions.WITHDRAW, thread, () -> {
            Revision current = requireCurrent(thread, expectedVersion, State.WITHDRAWN);
            store.withdraw(thread, current.revision(), reason);
            store.append(new Fact(thread, current.revision(), FactType.WITHDRAWN, current.patientId(),
                    clock.instant(), reason));
            return store.current(thread).orElseThrow();
        });
    }

    /**
     * Read one thread with its full revision history and fact trail.
     *
     * @param threadId owning thread
     * @return the read model
     * @throws ApiException on denial or an invisible thread
     */
    public View get(String threadId) {
        String thread = InstructionInputs.key(threadId, "threadId");
        return execute(InstructionActions.READ, thread, () -> {
            Revision current = store.current(thread).orElseThrow(this::notFound);
            if (!store.staffAssignedToUnit(context().subject(), current.unitId())) {
                throw forbidden("the reader is not assigned to the care unit");
            }
            return new View(current, store.history(thread), store.facts(thread));
        });
    }

    /**
     * List current instructions visible to a verified patient.
     *
     * @param patientId verified patient identifier
     * @return current revisions, including withdrawn ones with their state
     * @throws ApiException on denial or invalid input
     */
    public List<Revision> listForPatient(String patientId) {
        String patient = InstructionInputs.uuid(patientId, "patientId");
        return execute(InstructionActions.READ, patient, () -> store.forPatient(patient));
    }

    private <T> T execute(String action, String resourceId, Supplier<T> work) {
        TenantContext context = context();
        if (context == null) {
            throw forbidden("no active tenant context");
        }
        List<String> roles = store.activeRoles(context.subject());
        if (roles.isEmpty()) {
            throw forbidden("no active membership");
        }
        if (roles.stream().noneMatch(role -> actions.allows(action, role))) {
            throw forbidden("missing " + action + " permission");
        }
        return work.get();
    }

    private Revision requireCurrent(String threadId, int expectedVersion, State target) {
        Revision current = store.current(threadId).orElseThrow(this::notFound);
        if (current.revision() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the instruction changed since it was read");
        }
        if (!current.state().canTransitionTo(target)) {
            throw new ApiException(409, "INVALID_TRANSITION",
                    "an instruction cannot change from " + current.state().wire());
        }
        return current;
    }

    private void requireConnection(String connectionId, String patientId, String unitId) {
        if (!store.connectionActive(connectionId, patientId, unitId)) {
            throw forbidden("the patient-unit connection is not active for this context");
        }
        if (!store.staffAssignedToUnit(context().subject(), unitId)) {
            throw forbidden("the sender is not assigned to the care unit");
        }
    }

    private String anyRole(List<String> roles) {
        return roles.stream().findFirst().orElse("");
    }

    private TenantContext context() {
        return TenantContext.current();
    }

    private ApiException forbidden(String message) {
        return new ApiException(403, "FORBIDDEN", message);
    }

    private ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "instruction thread not found");
    }
}
