package com.aurevia.staffmsg.governance;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.messaging.governance.MessagingGovernanceInputs;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.ExportRequest;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.HideRequest;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.MessageView;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.RecordActionRequest;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.SearchHit;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.SearchPage;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.SearchQuery;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;
import com.aurevia.tenant.TenantContext;

/**
 * Governance domain service for staff-to-staff messages (MSG-07, ARC-02, OPS-02).
 *
 * <p>Every protected method derives organization and subject from the request
 * context, re-checks active membership and role through the store, and appends a
 * separate non-sensitive audit fact for the action and its outcome. Failures
 * append DENIED, NOT_FOUND or CONFLICT before throwing, so a caller cannot
 * self-report a success. A user deletion changes the person's folder to
 * {@link com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder#TRASH}
 * without deleting the durable message; an active legal
 * hold always blocks retention; search never crosses a tenant or a participant
 * boundary; and a conversation export is available only to records officers.</p>
 */
@Service
public class StaffMessageGovernanceService {

    /** Purpose stamped on every staff-message governance audit fact (OPS-02). */
    public static final String PURPOSE = "STAFFMSG_GOVERNANCE";

    private static final String SYSTEM_SCOPE = "SYSTEM";
    private static final String SEARCH_SCOPE = "SEARCH";
    private static final String EXPORT_SCOPE = "conversation";
    private static final String SYSTEM_RESOURCE = "AUDIT";
    private static final int MAX_TEXT_LENGTH = 256;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_REASON_LENGTH = 256;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;
    private static final long EXPORT_TTL_SECONDS = 3600L;

    /**
     * Actions the generic recorder may legitimately record. Governance commands
     * are performed and audited only by their dedicated endpoints, so a caller of
     * the recorder cannot self-report a SUCCESS fact for a hold, export,
     * retention run or audit review that never happened (OPS-02).
     */
    private static final Set<StaffAction> WORKFLOW_ACTIONS = Set.of(
            StaffAction.OPEN, StaffAction.SEND, StaffAction.FORWARD, StaffAction.ATTACH,
            StaffAction.DOWNLOAD, StaffAction.FOLDER_MOVE, StaffAction.DELETE);

    private final StaffMessageGovernanceStore store;
    private final StaffMessageGovernanceActions actions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store   tenant-scoped governance store
     * @param actions bounded role matrix
     * @param clock   application clock for deterministic timestamps and retention
     */
    public StaffMessageGovernanceService(StaffMessageGovernanceStore store,
                                         StaffMessageGovernanceActions actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Record one audited conversation or file action with a server-derived outcome.
     *
     * @param request validated action command
     * @return the appended audit fact
     * @throws ApiException on invalid input, missing context or denial
     */
    public AuditEvent recordAction(RecordActionRequest request) {
        StaffAction action = parseAction(request.action());
        String resourceId = MessagingGovernanceInputs.uuid(request.resourceId(), "resourceId");
        String unitId = MessagingGovernanceInputs.text(request.unitId(), "unitId", MAX_IDENTIFIER_LENGTH);
        TenantContext context = requireContext();
        authorize(context, action, resourceId);
        return append(context, action, resourceId, unitId, Outcome.SUCCESS);
    }

    /**
     * Hide one message in the actor's folder. The durable row is never deleted;
     * retention and legal hold still govern its eventual fate.
     *
     * @param request validated hide command
     * @return the appended audit fact
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public AuditEvent hideMessage(HideRequest request) {
        String messageId = MessagingGovernanceInputs.uuid(request.messageId(), "messageId");
        String conversationId = MessagingGovernanceInputs.uuid(request.conversationId(), "conversationId");
        TenantContext context = requireContext();
        authorize(context, StaffAction.DELETE, messageId);
        if (store.conversation(context.organizationId(), conversationId).isEmpty()) {
            throw deny(context, StaffAction.DELETE, messageId, 404, "NOT_FOUND", "conversation not found");
        }
        if (!isParticipant(context, conversationId)) {
            throw deny(context, StaffAction.DELETE, messageId, 403, "FORBIDDEN",
                    "only a conversation participant may hide its messages");
        }
        if (store.messages(context.organizationId()).stream()
                .noneMatch(message -> messageId.equals(message.messageId())
                        && conversationId.equals(message.conversationId()))) {
            throw deny(context, StaffAction.DELETE, messageId, 404, "NOT_FOUND", "message not found");
        }
        store.setFolder(context.organizationId(), messageId, context.subject(), MailFolder.TRASH);
        // Name the hidden message on every arm of this flow so a resource-scoped
        // trail query sees both the decision and its denial facts together.
        return append(context, StaffAction.DELETE, messageId, SYSTEM_SCOPE, Outcome.SUCCESS);
    }

    /**
     * Decide whether a conversation may be removed by retention.
     *
     * <p>A missing approved retention policy fails closed: the resource is never
     * reported as eligible without a configured tenant window.</p>
     *
     * @param conversationId conversation identifier
     * @return an explicit decision with a stable reason code
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public RetentionDecision retentionDecision(String conversationId) {
        String id = MessagingGovernanceInputs.uuid(conversationId, "conversationId");
        TenantContext context = requireContext();
        authorize(context, StaffAction.AUDIT_READ, id);
        StaffConversation conversation = store.conversation(context.organizationId(), id)
                .orElseThrow(() -> deny(context, StaffAction.AUDIT_READ, id, 404,
                        "NOT_FOUND", "conversation not found"));
        RetentionPolicy policy = store.retentionPolicy(context.organizationId(), EXPORT_SCOPE)
                .orElseThrow(() -> deny(context, StaffAction.AUDIT_READ, id, 422,
                        "NO_RETENTION_POLICY", "no approved retention policy for conversations"));
        RetentionDecision decision;
        if (isHeld(context, id)) {
            decision = new RetentionDecision(id, false, "LEGAL_HOLD");
        } else if (conversation.createdAt().plus(policy.retentionDays(), ChronoUnit.DAYS)
                .isAfter(clock.instant())) {
            decision = new RetentionDecision(id, false, "RETENTION_WINDOW");
        } else {
            decision = new RetentionDecision(id, true, "ELIGIBLE");
        }
        append(context, StaffAction.AUDIT_READ, id, SYSTEM_SCOPE, Outcome.SUCCESS);
        return decision;
    }

    /**
     * Place a legal hold that suspends retention for one conversation.
     *
     * @param request validated hold command
     * @return the created active hold
     * @throws ApiException on invalid input, missing context, denial, concealment or duplicate
     */
    public LegalHold placeHold(PlaceHoldRequest request) {
        String resourceId = MessagingGovernanceInputs.uuid(request.resourceId(), "resourceId");
        String reason = MessagingGovernanceInputs.text(request.reason(), "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, StaffAction.HOLD_PLACE, resourceId);
        if (store.conversation(context.organizationId(), resourceId).isEmpty()) {
            throw deny(context, StaffAction.HOLD_PLACE, resourceId, 404, "NOT_FOUND", "conversation not found");
        }
        for (LegalHold existing : store.legalHolds(context.organizationId())) {
            if (resourceId.equals(existing.resourceId()) && existing.active()) {
                throw deny(context, StaffAction.HOLD_PLACE, resourceId, 409, "HOLD_EXISTS",
                        "an active legal hold already covers this conversation");
            }
        }
        LegalHold hold = new LegalHold(MessagingGovernanceInputs.newId(), context.organizationId(),
                resourceId, reason, context.subject(), clock.instant(), null, null, null);
        store.putLegalHold(context.organizationId(), hold);
        append(context, StaffAction.HOLD_PLACE, resourceId, SYSTEM_SCOPE, Outcome.SUCCESS);
        return hold;
    }

    /**
     * Release a legal hold, recording the releasing actor and instant.
     *
     * @param holdId hold identifier
     * @param reason non-sensitive release reason
     * @return the released hold
     * @throws ApiException on invalid input, missing context, denial, concealment or repeat release
     */
    public LegalHold releaseHold(String holdId, String reason) {
        String id = MessagingGovernanceInputs.uuid(holdId, "holdId");
        String releaseReason = MessagingGovernanceInputs.text(reason, "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, StaffAction.HOLD_RELEASE, id);
        LegalHold current = store.legalHolds(context.organizationId()).stream()
                .filter(hold -> id.equals(hold.id()))
                .findFirst()
                .orElseThrow(() -> deny(context, StaffAction.HOLD_RELEASE, id, 404,
                        "NOT_FOUND", "legal hold not found"));
        if (!current.active()) {
            throw deny(context, StaffAction.HOLD_RELEASE, id, 409, "HOLD_ALREADY_RELEASED",
                    "the legal hold is already released");
        }
        LegalHold released = new LegalHold(current.id(), current.organizationId(), current.resourceId(),
                current.reason(), current.placedBy(), current.placedAt(), context.subject(),
                clock.instant(), releaseReason);
        store.putLegalHold(context.organizationId(), released);
        append(context, StaffAction.HOLD_RELEASE, id, SYSTEM_SCOPE, Outcome.SUCCESS);
        return released;
    }

    /**
     * Search the actor's own conversations and folders. Every candidate must be
     * in the active tenant, in a conversation the actor participates in and
     * outside the hidden TRASH folder before any text filter is applied.
     *
     * @param query validated search query
     * @return a cursor-paged result with a stable sent-at ordering
     * @throws ApiException on invalid input, missing context or denial
     */
    public SearchPage search(SearchQuery query) {
        int limit = validateLimit(query.limit());
        String cursor = validateCursor(query.cursor());
        String sender = query.senderSubject() == null ? null
                : MessagingGovernanceInputs.text(query.senderSubject(), "senderSubject", MAX_IDENTIFIER_LENGTH);
        String subject = query.subject() == null ? null
                : MessagingGovernanceInputs.text(query.subject(), "subject", MAX_TEXT_LENGTH);
        String text = query.text() == null ? null
                : MessagingGovernanceInputs.text(query.text(), "text", MAX_TEXT_LENGTH);
        TenantContext context = requireContext();
        authorize(context, StaffAction.OPEN, SEARCH_SCOPE);

        List<MessageView> ordered = new ArrayList<>();
        for (MessageView message : store.messages(context.organizationId())) {
            if (!isParticipant(context, message.conversationId())) {
                continue;
            }
            MailFolder folder = store.folders(context.organizationId(), context.subject())
                    .getOrDefault(message.messageId(), MailFolder.INBOX);
            if (folder == MailFolder.TRASH) {
                continue;
            }
            if (matches(message, sender, subject, text, query.from(), query.to())) {
                ordered.add(message);
            }
        }
        ordered.sort(Comparator.comparing(MessageView::sentAt).thenComparing(MessageView::messageId).reversed());

        List<MessageView> page = ordered;
        if (cursor != null) {
            int cursorIndex = -1;
            for (int index = 0; index < ordered.size(); index++) {
                if (cursor.equals(ordered.get(index).messageId())) {
                    cursorIndex = index;
                    break;
                }
            }
            page = cursorIndex < 0 ? List.of() : ordered.subList(cursorIndex + 1, ordered.size());
        }
        List<SearchHit> hits = new ArrayList<>();
        String nextCursor = null;
        for (int index = 0; index < page.size(); index++) {
            if (index == limit) {
                nextCursor = page.get(index - 1).messageId();
                break;
            }
            MessageView message = page.get(index);
            hits.add(new SearchHit(message.messageId(), message.conversationId(), message.senderSubject(),
                    message.subject(), message.sentAt(), message.body(), folderOf(context, message).wire()));
        }
        append(context, StaffAction.AUDIT_READ, SEARCH_SCOPE, SYSTEM_SCOPE, Outcome.SUCCESS);
        return new SearchPage(List.copyOf(hits), nextCursor);
    }

    /**
     * Produce a conversation export for a records officer, carrying every
     * attachment checksum assembled from the durable attachment links.
     *
     * @param request validated export command
     * @return the export with its checksum snapshot
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public ConversationExport exportConversation(ExportRequest request) {
        String conversationId = MessagingGovernanceInputs.uuid(request.conversationId(), "conversationId");
        TenantContext context = requireContext();
        authorize(context, StaffAction.EXPORT, conversationId);
        ExportProvenance provenance = validatedProvenance(request.provenance(), context);
        if (!conversationId.equals(provenance.sourceReference())) {
            throw deny(context, StaffAction.EXPORT, conversationId, 422, "INVALID_PROVENANCE",
                    "provenance source must be the exported conversation");
        }
        // validatedProvenance already proved this conversation exists in the
        // active tenant and is the provenance source, so no second lookup.
        List<String> checksums = new ArrayList<>();
        for (MessageView message : store.messages(context.organizationId())) {
            if (conversationId.equals(message.conversationId())) {
                for (var link : store.attachmentLinks(context.organizationId(), message.messageId())) {
                    checksums.add(link.checksum());
                }
            }
        }
        Instant now = clock.instant();
        ConversationExport export = new ConversationExport(MessagingGovernanceInputs.newId(),
                context.organizationId(), conversationId, context.subject(), now,
                now.plusSeconds(EXPORT_TTL_SECONDS), checksums, provenance);
        store.putExport(context.organizationId(), export);
        append(context, StaffAction.EXPORT, conversationId, SYSTEM_SCOPE, Outcome.SUCCESS);
        return export;
    }

    /**
     * Read the tenant's audit trail, recording the read itself as an audit fact.
     *
     * @param resourceId optional exact resource filter, or {@code null} for all
     * @return audit facts in insertion order
     * @throws ApiException on invalid input, missing context or denial
     */
    public List<AuditEvent> auditTrail(String resourceId) {
        String filter = resourceId == null ? null : MessagingGovernanceInputs.uuid(resourceId, "resourceId");
        TenantContext context = requireContext();
        String resource = filter == null ? SYSTEM_RESOURCE : filter;
        authorize(context, StaffAction.AUDIT_READ, resource);
        append(context, StaffAction.AUDIT_READ, resource, SYSTEM_SCOPE, Outcome.SUCCESS);
        List<AuditEvent> events = store.auditEvents(context.organizationId());
        return filter == null ? events
                : events.stream().filter(event -> filter.equals(event.resourceId())).toList();
    }

    /**
     * Validate the provenance an export must carry, binding its source to a
     * conversation in the active tenant and reducing free text to allowlisted
     * codes and bounded identifier tokens (OPS-07).
     *
     * @param provenance caller-supplied provenance
     * @param context    active tenant used for the source binding
     * @return validated provenance
     * @throws ApiException when provenance is absent, malformed or foreign
     */
    private ExportProvenance validatedProvenance(ExportProvenance provenance, TenantContext context) {
        if (provenance == null) {
            throw new ApiException(422, "INVALID_PROVENANCE", "export provenance is required");
        }
        String sourceReference = MessagingGovernanceInputs.uuid(provenance.sourceReference(), "sourceReference");
        String sourceRevision = MessagingGovernanceInputs.exportRevision(provenance.sourceRevision());
        String purpose = MessagingGovernanceInputs.exportPurpose(provenance.purpose());
        if (store.conversation(context.organizationId(), sourceReference).isEmpty()) {
            throw deny(context, StaffAction.EXPORT, sourceReference, 404, "NOT_FOUND",
                    "export source conversation not found in the active organization");
        }
        return new ExportProvenance(sourceReference, sourceRevision, purpose);
    }

    /**
     * Apply every optional message filter. Each guard is a single decision so
     * the true and false sides are independently testable.
     */
    private boolean matches(MessageView message, String sender, String subject, String text,
                            Instant from, Instant to) {
        if (sender != null && !sender.equals(message.senderSubject())) {
            return false;
        }
        if (subject != null && (message.subject() == null
                || !message.subject().toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (text != null && !message.body().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (from != null && message.sentAt().isBefore(from)) {
            return false;
        }
        return to == null || !message.sentAt().isAfter(to);
    }

    /**
     * Re-read the visible folder for a hit so the projection never reports TRASH.
     */
    private MailFolder folderOf(TenantContext context, MessageView message) {
        return store.folders(context.organizationId(), context.subject())
                .getOrDefault(message.messageId(), MailFolder.INBOX);
    }

    /**
     * Check that the actor has a membership row in the conversation.
     */
    private boolean isParticipant(TenantContext context, String conversationId) {
        List<StaffParticipant> participants = store.participants(context.organizationId(), conversationId);
        for (StaffParticipant participant : participants) {
            if (context.subject().equals(participant.subject())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether an active legal hold covers a resource.
     */
    private boolean isHeld(TenantContext context, String resourceId) {
        for (LegalHold hold : store.legalHolds(context.organizationId())) {
            if (resourceId.equals(hold.resourceId()) && hold.active()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse an action wire key into the governed vocabulary.
     */
    private StaffAction parseAction(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_ACTION", "action is required");
        }
        StaffAction action;
        try {
            action = StaffAction.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_ACTION", "unknown staff action");
        }
        if (!WORKFLOW_ACTIONS.contains(action)) {
            throw new ApiException(422, "INVALID_ACTION", "action is not a recordable workflow action");
        }
        return action;
    }

    /**
     * Validate and bound the page size.
     */
    private int validateLimit(Integer limit) {
        if (limit == null || limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be between 1 and 100");
        }
        return limit;
    }

    /**
     * Validate an optional opaque cursor token.
     */
    private String validateCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        String token = MessagingGovernanceInputs.text(cursor, "cursor", MAX_IDENTIFIER_LENGTH);
        return MessagingGovernanceInputs.uuid(token, "cursor");
    }

    /**
     * Require a bound tenant context or fail closed.
     */
    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    /**
     * Check active membership and role on every request.
     */
    private void authorize(TenantContext context, StaffAction action, String resourceId) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "no active membership");
        }
        boolean permitted = store.activeRoles(context.organizationId(), context.subject()).stream()
                .anyMatch(role -> actions.allows(action, role));
        if (!permitted) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    /**
     * Append a denial fact and build the command failure.
     */
    private ApiException deny(TenantContext context, StaffAction action, String resourceId,
                              int status, String code, String message) {
        Outcome outcome = status == 404 ? Outcome.NOT_FOUND
                : status == 409 ? Outcome.CONFLICT : Outcome.DENIED;
        append(context, action, resourceId, SYSTEM_SCOPE, outcome);
        return new ApiException(status, code, message);
    }

    /**
     * Append one non-sensitive audit fact with a server-stamped outcome.
     */
    private AuditEvent append(TenantContext context, StaffAction action, String resourceId,
                              String unitId, Outcome outcome) {
        AuditEvent event = new AuditEvent(MessagingGovernanceInputs.newId(), context.organizationId(),
                context.subject(), PURPOSE, action, resourceId, unitId, context.correlationId(),
                outcome, clock.instant());
        store.appendAudit(event);
        return event;
    }
}
