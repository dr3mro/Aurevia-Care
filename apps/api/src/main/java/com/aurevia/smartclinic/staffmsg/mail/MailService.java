package com.aurevia.smartclinic.staffmsg.mail;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.CreateDraftCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.FlagCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.ForwardCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.MoveCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.ReplyCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.SaveDraftCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.SendCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.DraftView;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.MailView;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.Recipient;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.RecipientKind;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.SentMail;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.ThreadView;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.MailFolder;

/**
 * Domain kernel for email-like staff messages (MSG-02, ARC-07).
 *
 * <p>The kernel owns versioned drafts, an immutable message ledger, per-person
 * folders, flags and threads grouped by normalized subject and a shared
 * reference. Every command carries the active tenant and an authenticated actor;
 * neither is ever accepted from a request body. A role recipient is expanded
 * through {@link com.aurevia.smartclinic.staffmsg.mail.RecipientDirectory} at
 * send time and the concrete subjects are frozen on the published message, so a
 * later role change cannot silently redirect an already sent mail. Sending also
 * requires the sender to confirm the resolved recipient list, and there is no
 * blind-copy field anywhere in the API.</p>
 */
@Service
public final class MailService {

    private static final String SENDER_ROLE = "staff";

    private final Clock clock;
    private final RecipientDirectory directory;
    private final Map<String, TenantState> tenants = new HashMap<>();

    /**
     * Create the service with the production staff directory.
     *
     * @param clock        application clock for server timestamps
     * @param staff        authoritative staff membership and role source
     * @param transactions tenant-guarded transaction template for role lookups
     */
    @Autowired
    public MailService(Clock clock, StaffRepository staff, TransactionTemplate transactions) {
        this(clock, new StaffRecipientDirectory(staff, transactions));
    }

    /**
     * Create the service with an explicit recipient directory.
     *
     * @param clock     application clock for server timestamps
     * @param directory role-holder resolver used when a message is sent
     */
    public MailService(Clock clock, RecipientDirectory directory) {
        this.clock = clock;
        this.directory = directory;
    }

    /**
     * Start a private, version-one draft.
     *
     * @param command compose command with verified tenant, facility and actor
     * @return the new draft
     * @throws ApiException on invalid input, including any attempt at blind copy
     */
    public synchronized DraftView createDraft(CreateDraftCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        String facilityId = MailInputs.requireText(command.facilityId(), "INVALID_FACILITY",
                "facility is required");
        if (command.bcc() != null && !command.bcc().isEmpty()) {
            throw new ApiException(422, "BLIND_COPY_NOT_ALLOWED", "blind copy is not available for staff mail");
        }
        DraftView draft = new DraftView(organizationId, MailInputs.newId(), facilityId, command.actor(), 1,
                command.subject(), command.body(), MailInputs.orEmpty(command.to()),
                MailInputs.orEmpty(command.cc()), MailInputs.orEmpty(command.attachmentDocumentIds()),
                null, clock.instant());
        state(organizationId).drafts.put(draft.id(), draft);
        return draft;
    }

    /**
     * Save a new version of a draft, refusing a stale precondition.
     *
     * @param command save command carrying the version the caller read
     * @return the advanced draft
     * @throws ApiException on invalid input, an invisible draft or a stale version
     */
    public synchronized DraftView saveDraft(SaveDraftCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        int expected = MailInputs.requirePositive(command.expectedVersion(), "INVALID_VERSION",
                "version must be a positive integer");
        if (command.bcc() != null && !command.bcc().isEmpty()) {
            throw new ApiException(422, "BLIND_COPY_NOT_ALLOWED", "blind copy is not available for staff mail");
        }
        TenantState tenant = state(organizationId);
        DraftView current = draft(tenant, command.draftId(), actor);
        if (current.version() != expected) {
            throw new ApiException(412, "STALE_VERSION", "the mail draft changed since it was read");
        }
        DraftView updated = new DraftView(organizationId, current.id(), current.facilityId(), actor,
                current.version() + 1, command.subject(), command.body(), MailInputs.orEmpty(command.to()),
                MailInputs.orEmpty(command.cc()), MailInputs.orEmpty(command.attachmentDocumentIds()),
                current.threadId(), clock.instant());
        tenant.drafts.put(current.id(), updated);
        return updated;
    }

    /**
     * Publish a draft after expanding recipients and checking the sender's
     * confirmation. A retry with the same client message id returns the first
     * publication instead of creating a second message (ARC-07).
     *
     * @param command send command carrying the confirmed recipient list
     * @return the published message in the sender's sent folder
     * @throws ApiException on invalid input, an invisible draft, a missing or
     *         mismatched confirmation, or a missing subject or recipient
     */
    public synchronized MailView send(SendCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        String clientMessageId = MailInputs.requireText(command.clientMessageId(),
                "INVALID_IDEMPOTENCY_KEY", "an idempotency key is required");
        TenantState tenant = state(organizationId);
        String idempotency = idempotencyKey(organizationId, actor, clientMessageId);
        String replay = tenant.sendIdempotency.get(idempotency);
        if (replay != null) {
            return new MailView(tenant.messages.get(replay), tenant.folders.getOrDefault(
                    folderKey(replay, actor), MailFolder.SENT));
        }
        DraftView draft = draft(tenant, command.draftId(), actor);
        MailInputs.requireText(draft.subject(), "SUBJECT_REQUIRED", "a subject is required to send");
        List<Recipient> recipients = new ArrayList<>(draft.to());
        recipients.addAll(draft.cc());
        if (recipients.isEmpty()) {
            throw new ApiException(422, "NO_RECIPIENT", "at least one recipient is required to send");
        }
        List<String> expanded = expand(organizationId, draft, recipients);
        if (command.confirmedRecipients() == null) {
            throw new ApiException(428, "CONFIRMATION_REQUIRED", "confirm the recipient list before sending");
        }
        if (!MailRules.confirmationMatches(command.confirmedRecipients(), expanded)) {
            throw new ApiException(409, "RECIPIENT_CONFIRMATION_MISMATCH",
                    "the confirmed recipient list does not match the current recipients");
        }
        String id = MailInputs.newId();
        String threadId = draft.threadId() == null ? id : draft.threadId();
        SentMail message = new SentMail(organizationId, id, draft.facilityId(), threadId, draft.subject(),
                draft.body(), actor, SENDER_ROLE, clock.instant(), draft.to(), draft.cc(), expanded,
                draft.attachmentDocumentIds(), List.of());
        tenant.messages.put(id, message);
        tenant.folders.put(folderKey(id, actor), MailFolder.SENT);
        for (String recipient : expanded) {
            tenant.folders.putIfAbsent(folderKey(id, recipient), MailFolder.INBOX);
        }
        tenant.drafts.remove(draft.id());
        tenant.sendIdempotency.put(idempotency, id);
        return new MailView(message, MailFolder.SENT);
    }

    /**
     * Create a reply draft, either to the sender alone or to every original
     * participant except the person replying.
     *
     * @param command reply command with the original message id
     * @return a draft in the same thread as the original
     * @throws ApiException on invalid input or an invisible message
     */
    public synchronized DraftView reply(ReplyCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        SentMail original = visible(state(organizationId), command.messageId(), actor);
        List<Recipient> recipients = new ArrayList<>();
        if (command.replyAll()) {
            List<String> participants = new ArrayList<>();
            participants.add(original.senderSubject());
            participants.addAll(original.resolvedRecipients());
            for (String subject : MailRules.replyAll(participants, actor)) {
                recipients.add(Recipient.person(subject));
            }
        } else {
            recipients.add(Recipient.person(original.senderSubject()));
        }
        DraftView draft = new DraftView(organizationId, MailInputs.newId(), original.facilityId(), actor, 1,
                "Re: " + MailRules.normalizeSubject(original.subject()), command.body(), recipients,
                List.of(), List.of(), original.threadId(), clock.instant());
        state(organizationId).drafts.put(draft.id(), draft);
        return draft;
    }

    /**
     * Create a forward draft that keeps the original's attachment links and
     * thread reference.
     *
     * @param command forward command with new recipients
     * @return a draft carrying the original document links
     * @throws ApiException on invalid input or an invisible message
     */
    public synchronized DraftView forward(ForwardCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        SentMail original = visible(state(organizationId), command.messageId(), actor);
        DraftView draft = new DraftView(organizationId, MailInputs.newId(), original.facilityId(), actor, 1,
                "Fwd: " + MailRules.normalizeSubject(original.subject()), command.body(),
                MailInputs.orEmpty(command.to()), MailInputs.orEmpty(command.cc()),
                original.attachmentDocumentIds(), original.threadId(), clock.instant());
        state(organizationId).drafts.put(draft.id(), draft);
        return draft;
    }

    /**
     * Move a message into a folder belonging to the acting person only.
     *
     * @param command move command with the target folder
     * @return the message with the viewer's new folder
     * @throws ApiException on invalid input or an invisible message
     */
    public synchronized MailView move(MoveCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        if (command.folder() == null) {
            throw new ApiException(422, "INVALID_FOLDER", "a target folder is required");
        }
        TenantState tenant = state(organizationId);
        SentMail message = visible(tenant, command.messageId(), actor);
        tenant.folders.put(folderKey(message.id(), actor), command.folder());
        return new MailView(message, command.folder());
    }

    /**
     * Set or clear one flag on a message visible to the acting person.
     *
     * @param command flag command carrying the flag and desired state
     * @return the message with its updated flags
     * @throws ApiException on invalid input or an invisible message
     */
    public synchronized MailView flag(FlagCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        String flag = MailInputs.requireText(command.flag(), "INVALID_FLAG", "a flag is required");
        TenantState tenant = state(organizationId);
        SentMail message = visible(tenant, command.messageId(), actor);
        List<String> flags = new ArrayList<>(message.flags());
        if (command.enabled()) {
            if (!flags.contains(flag)) {
                flags.add(flag);
            }
        } else {
            flags.remove(flag);
        }
        SentMail updated = new SentMail(message.organizationId(), message.id(), message.facilityId(),
                message.threadId(), message.subject(), message.body(), message.senderSubject(),
                message.senderRole(), message.sentAt(), message.to(), message.cc(),
                message.resolvedRecipients(), message.attachmentDocumentIds(), List.copyOf(flags));
        tenant.messages.put(message.id(), updated);
        return new MailView(updated, folderFor(tenant, message.id(), actor));
    }

    /**
     * List the acting person's messages in one folder.
     *
     * @param organizationId active tenant
     * @param actor          authenticated subject
     * @param folder         folder to list
     * @return the visible messages placed in that folder by this person
     * @throws ApiException on invalid input
     */
    public synchronized List<MailView> listFolder(String organizationId, String actor, MailFolder folder) {
        MailInputs.requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(actor, "INVALID_ACTOR", "actor is required");
        if (folder == null) {
            throw new ApiException(422, "INVALID_FOLDER", "a folder is required");
        }
        TenantState tenant = state(organizationId);
        List<MailView> result = new ArrayList<>();
        for (SentMail message : tenant.messages.values()) {
            if (folder == tenant.folders.get(folderKey(message.id(), actor))) {
                result.add(new MailView(message, folder));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Group the acting person's visible messages by thread.
     *
     * @param organizationId active tenant
     * @param actor          authenticated subject
     * @return threads ordered by first message, each with its shared subject
     * @throws ApiException on invalid input
     */
    public synchronized List<ThreadView> listThreads(String organizationId, String actor) {
        MailInputs.requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(actor, "INVALID_ACTOR", "actor is required");
        TenantState tenant = state(organizationId);
        Map<String, List<MailView>> grouped = new LinkedHashMap<>();
        Map<String, String> references = new HashMap<>();
        for (SentMail message : tenant.messages.values()) {
            if (visibleTo(actor, message)) {
                String key = MailRules.normalizeSubject(message.subject()) + "|" + message.threadId();
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new MailView(message, folderFor(tenant, message.id(), actor)));
                references.put(key, message.threadId());
            }
        }
        List<ThreadView> result = new ArrayList<>();
        for (Map.Entry<String, List<MailView>> entry : grouped.entrySet()) {
            List<MailView> messages = entry.getValue();
            result.add(new ThreadView(references.get(entry.getKey()),
                    MailRules.normalizeSubject(messages.get(0).message().subject()), messages));
        }
        return List.copyOf(result);
    }

    /**
     * Read one message visible to the acting person.
     *
     * @param organizationId active tenant
     * @param actor          authenticated subject
     * @param messageId      tenant-local message identifier
     * @return the message with the reader's folder placement
     * @throws ApiException on invalid input or an invisible message
     */
    public synchronized MailView view(String organizationId, String actor, String messageId) {
        MailInputs.requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(actor, "INVALID_ACTOR", "actor is required");
        TenantState tenant = state(organizationId);
        SentMail message = visible(tenant, messageId, actor);
        return new MailView(message, folderFor(tenant, messageId, actor));
    }

    private List<String> expand(String organizationId, DraftView draft, List<Recipient> recipients) {
        Set<String> result = new LinkedHashSet<>();
        for (Recipient recipient : recipients) {
            if (recipient.kind() == RecipientKind.ROLE) {
                result.addAll(directory.holders(organizationId, draft.facilityId(), recipient.value()));
            } else {
                result.add(recipient.value());
            }
        }
        return List.copyOf(result);
    }

    private static DraftView draft(TenantState tenant, String draftId, String actor) {
        DraftView draft = tenant.drafts.get(draftId);
        if (draft == null || !draft.ownerSubject().equals(actor)) {
            throw new ApiException(404, "NOT_FOUND", "mail draft not found");
        }
        return draft;
    }

    private static SentMail visible(TenantState tenant, String messageId, String actor) {
        SentMail message = tenant.messages.get(messageId);
        if (message == null || !visibleTo(actor, message)) {
            throw new ApiException(404, "NOT_FOUND", "mail message not found");
        }
        return message;
    }

    private static boolean visibleTo(String actor, SentMail message) {
        return actor.equals(message.senderSubject()) || message.resolvedRecipients().contains(actor);
    }

    private static MailFolder folderFor(TenantState tenant, String messageId, String actor) {
        return tenant.folders.getOrDefault(folderKey(messageId, actor), MailFolder.INBOX);
    }

    private static String folderKey(String messageId, String actor) {
        return messageId + "|" + actor;
    }

    private static String idempotencyKey(String organizationId, String actor, String clientMessageId) {
        return organizationId + "|" + actor + "|" + clientMessageId;
    }

    private TenantState state(String organizationId) {
        return tenants.computeIfAbsent(organizationId, ignored -> new TenantState());
    }

    private static final class TenantState {
        private final Map<String, DraftView> drafts = new LinkedHashMap<>();
        private final Map<String, SentMail> messages = new LinkedHashMap<>();
        private final Map<String, MailFolder> folders = new HashMap<>();
        private final Map<String, String> sendIdempotency = new HashMap<>();
    }
}
