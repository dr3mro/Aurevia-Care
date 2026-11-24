package com.aurevia.messaging.instructions;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.DefineTemplateRequest;
import com.aurevia.messaging.instructions.InstructionContract.Fact;
import com.aurevia.messaging.instructions.InstructionContract.FactType;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.ReplaceRequest;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.messaging.instructions.InstructionContract.View;
import com.aurevia.messaging.instructions.InstructionContract.WithdrawRequest;
import com.aurevia.tenant.TenantContext;

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
            boolean clinicalContent;
            if (templateId != null) {
                Template template = store.approvedTemplate(unitId, templateId)
                        .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "template not found"));
                clinicalContent = template.clinical();
                if (clinicalContent && !allowsAny(roles, InstructionActions.CLINICAL_SEND)) {
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
                if (!allowsAny(roles, InstructionActions.CLINICAL_SEND)) {
                    throw forbidden("free text requires clinician authorization");
                }
                clinicalContent = true;
                kind = request.kind();
                if (kind == null) {
                    throw new ApiException(422, "INVALID_KIND", "a kind is required for free text");
                }
                title = InstructionInputs.text(request.title(), "title", 120);
                body = InstructionInputs.text(request.text(), "text", 2000);
                language = InstructionInputs.language(request.language());
            }
            requireConnection(connectionId, patientId, unitId);
            String authorRole = authorRoleFor(roles,
                    clinicalContent ? InstructionActions.CLINICAL_SEND : InstructionActions.SEND);
            Revision revision = new Revision(InstructionInputs.newId(), 1, templateId, kind, title, body,
                    language, context.subject(), authorRole, unitId, connectionId, patientId,
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
            requireOwnership(current);
            List<String> roles = store.activeRoles(context.subject());
            // A replacement carries new free text, so it needs the same clinical
            // authorization as an original free-text send (MSG-04).
            if (!allowsAny(roles, InstructionActions.CLINICAL_SEND)) {
                throw forbidden("replacement text requires clinician authorization");
            }
            int number = current.revision() + 1;
            Revision replacement = new Revision(current.threadId(), number, null,
                    current.kind(), title, body, language, context.subject(),
                    authorRoleFor(roles, InstructionActions.CLINICAL_SEND), current.unitId(),
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
            requireOwnership(current);
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
            String subject = context().subject();
            Revision current = store.current(thread).orElseThrow(this::notFound);
            if (isPatient()) {
                requirePatientChartAccess(subject, current);
            } else if (!store.staffAssignedToUnit(subject, current.unitId())) {
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
        return execute(InstructionActions.READ, patient, () -> {
            String subject = context().subject();
            if (isPatient()) {
                // A patient is bound to its own verified charts (IAM-05): the
                // caller-supplied chart identifier is checked against the charts
                // linked to the authenticated account, never trusted on its own.
                if (!store.verifiedPatientCharts(subject).contains(patient)) {
                    throw notFound();
                }
                return store.forPatient(patient).stream()
                        .filter(revision -> store.connectionActive(
                                revision.connectionId(), revision.patientId(), revision.unitId()))
                        .toList();
            }
            // READ permission is not a care relationship. Mirror get() and the
            // mutation guards: a caller only sees revisions from units they are
            // assigned to over a still-active, verified patient connection
            // (IAM-01, MSG-01, MSG-07, ARC-02).
            return store.forPatient(patient).stream()
                    .filter(revision -> store.staffAssignedToUnit(subject, revision.unitId()))
                    .filter(revision -> store.connectionActive(
                            revision.connectionId(), revision.patientId(), revision.unitId()))
                    .toList();
        });
    }

    /** @return whether the authenticated subject holds the bounded patient role */
    private boolean isPatient() {
        return store.activeRoles(context().subject()).stream().anyMatch("patient"::equals);
    }

    /**
     * Bind a patient caller to a chart linked to its verified account and to an
     * active patient-unit connection before releasing instruction content.
     * Another patient's chart is concealed (404, ARC-04) and a revoked
     * connection denies (403), per IAM-05, MSG-01, MSG-07 and PAT-02.
     *
     * @param subject  authenticated patient account subject
     * @param revision current revision being read
     */
    private void requirePatientChartAccess(String subject, Revision revision) {
        if (!store.verifiedPatientCharts(subject).contains(revision.patientId())) {
            throw notFound();
        }
        if (!store.connectionActive(revision.connectionId(), revision.patientId(), revision.unitId())) {
            throw forbidden("the patient-unit connection is not active for this context");
        }
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

    /**
     * Re-check that the caller may still mutate an existing thread: they must be
     * assigned to its care unit over an active, verified patient connection.
     * Without this, a role that can withdraw could rewrite another unit's
     * instruction merely by knowing its thread identifier (ARC-02, ARC-07).
     *
     * @param revision current revision of the thread
     */
    private void requireOwnership(Revision revision) {
        if (!store.staffAssignedToUnit(context().subject(), revision.unitId())) {
            throw forbidden("the actor is not assigned to the care unit");
        }
        if (!store.connectionActive(revision.connectionId(), revision.patientId(), revision.unitId())) {
            throw forbidden("the patient-unit connection is not active for this context");
        }
    }

    /** @return whether any active role permits the given action */
    private boolean allowsAny(List<String> roles, String action) {
        return roles.stream().anyMatch(role -> actions.allows(action, role));
    }

    /**
     * Pick a deterministic authoring role that actually permits the gate action,
     * independent of role ordering in the membership fold (IAM-01).
     *
     * @param roles  active role keys
     * @param action gate the recording role must satisfy
     * @return the first matching role, or an empty string when none matches
     */
    private String authorRoleFor(List<String> roles, String action) {
        return roles.stream().filter(role -> actions.allows(action, role)).findFirst().orElse("");
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
