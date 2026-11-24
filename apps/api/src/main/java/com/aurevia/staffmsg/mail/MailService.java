package com.aurevia.staffmsg.mail;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.attachments.StaffAttachmentPublication;
import com.aurevia.staffmsg.directory.DirectoryPolicy;
import com.aurevia.staffmsg.directory.StaffDirectoryService;
import com.aurevia.staffmsg.mail.MailCommands.CreateDraftCommand;
import com.aurevia.staffmsg.mail.MailCommands.FlagCommand;
import com.aurevia.staffmsg.mail.MailCommands.ForwardCommand;
import com.aurevia.staffmsg.mail.MailCommands.MoveCommand;
import com.aurevia.staffmsg.mail.MailCommands.ReplyCommand;
import com.aurevia.staffmsg.mail.MailCommands.SaveDraftCommand;
import com.aurevia.staffmsg.mail.MailCommands.SendCommand;
import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.MailView;
import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.staffmsg.mail.MailContract.RecipientKind;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.mail.MailContract.ThreadView;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/**
 * Domain kernel for email-like staff messages (MSG-02, ARC-07).
 *
 * <p>The kernel owns versioned drafts, an immutable message ledger, per-person
 * folders, flags and threads grouped by normalized subject and a shared
 * reference. Every command carries the active tenant and an authenticated actor;
 * neither is ever accepted from a request body. A role recipient is expanded
 * through {@link com.aurevia.staffmsg.mail.RecipientDirectory} at
 * send time and the concrete subjects are frozen on the published message, so a
 * later role change cannot silently redirect an already sent mail. Sending also
 * requires the sender to confirm the resolved recipient list, and there is no
 * blind-copy field anywhere in the API. All state lives in the injected
 * {@link com.aurevia.staffmsg.mail.MailStore}, so drafts, messages,
 * folders and retry keys survive the loss of a service instance.</p>
 */
@Service
public final class MailService {

    private static final String SENDER_ROLE = "staff";

    private final Clock clock;
    private final RecipientDirectory directory;
    private final MailStore store;
    private final StaffAttachmentPublication attachments;

    /**
     * Create the service with the production directory and durable store.
     *
     * @param clock        application clock for server timestamps
     * @param staff        authoritative staff membership and role source
     * @param transactions tenant-guarded transaction template for role lookups
     * @param directory    tenant-scoped facility resolver for the selected draft facility
     * @param access       authoritative facility-scope fold for candidate role holders
     * @param policy       same-organization authorization rule
     * @param store        durable mail persistence port
     * @param attachments  publication seam for files bound to the draft
     */
    @Autowired
    public MailService(Clock clock, StaffRepository staff, TransactionTemplate transactions,
                       StaffDirectoryService directory, AccessRepository access, DirectoryPolicy policy,
                       MailStore store, StaffAttachmentPublication attachments) {
        this(clock, new StaffRecipientDirectory(staff, transactions, directory, access, policy), store,
                attachments);
    }

    /**
     * Create the service with an explicit recipient directory and store.
     *
     * @param clock       application clock for server timestamps
     * @param directory   role-holder resolver used when a message is sent
     * @param store       mail persistence port
     * @param attachments publication seam for files bound to the draft
     */
    public MailService(Clock clock, RecipientDirectory directory, MailStore store,
                       StaffAttachmentPublication attachments) {
        this.clock = clock;
        this.directory = directory;
        this.store = store;
        this.attachments = attachments;
    }

    /**
     * Create the production wiring without attachment publication, for fixtures
     * that exercise mail behavior in isolation.
     *
     * @param clock        application clock for server timestamps
     * @param staff        authoritative staff membership and role source
     * @param transactions tenant-guarded transaction template for role lookups
     * @param directory    tenant-scoped facility resolver
     * @param access       authoritative facility-scope fold
     * @param policy       same-organization authorization rule
     * @param store        durable mail persistence port
     */
    public MailService(Clock clock, StaffRepository staff, TransactionTemplate transactions,
                       StaffDirectoryService directory, AccessRepository access, DirectoryPolicy policy,
                       MailStore store) {
        this(clock, staff, transactions, directory, access, policy, store, StaffAttachmentPublication.none());
    }

    /**
     * Create the service without attachment publication, for callers that do not
     * own a message send (unit fixtures and clause-level checks).
     *
     * @param clock     application clock for server timestamps
     * @param directory role-holder resolver used when a message is sent
     * @param store     mail persistence port
     */
    public MailService(Clock clock, RecipientDirectory directory, MailStore store) {
        this(clock, directory, store, StaffAttachmentPublication.none());
    }

    /**
     * Start a private, version-one draft.
     *
     * @param command compose command with verified tenant, facility and actor
     * @return the new draft
     * @throws ApiException on invalid input, including any attempt at blind copy
     */
    public DraftView createDraft(CreateDraftCommand command) {
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
        store.saveDraft(organizationId, draft);
        return draft;
    }

    /**
     * Save a new version of a draft, refusing a stale precondition.
     *
     * @param command save command carrying the version the caller read
     * @return the advanced draft
     * @throws ApiException on invalid input, an invisible draft or a stale version
     */
    public DraftView saveDraft(SaveDraftCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        int expected = MailInputs.requirePositive(command.expectedVersion(), "INVALID_VERSION",
                "version must be a positive integer");
        if (command.bcc() != null && !command.bcc().isEmpty()) {
            throw new ApiException(422, "BLIND_COPY_NOT_ALLOWED", "blind copy is not available for staff mail");
        }
        DraftView current = draft(organizationId, command.draftId(), actor);
        if (current.version() != expected) {
            throw new ApiException(412, "STALE_VERSION", "the mail draft changed since it was read");
        }
        DraftView updated = new DraftView(organizationId, current.id(), current.facilityId(), actor,
                current.version() + 1, command.subject(), command.body(), MailInputs.orEmpty(command.to()),
                MailInputs.orEmpty(command.cc()), MailInputs.orEmpty(command.attachmentDocumentIds()),
                current.threadId(), clock.instant());
        store.saveDraft(organizationId, updated);
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
    public MailView send(SendCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        String clientMessageId = MailInputs.requireText(command.clientMessageId(),
                "INVALID_IDEMPOTENCY_KEY", "an idempotency key is required");
        var replayed = store.replay(organizationId, actor, clientMessageId);
        if (replayed.isPresent()) {
            SentMail original = storedMessage(organizationId, replayed.get());
            MailFolder placement = store.folders(organizationId, actor)
                    .getOrDefault(replayed.get(), MailFolder.SENT);
            return new MailView(original, placement);
        }
        DraftView draft = draft(organizationId, command.draftId(), actor);
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
        // Validate the files before the message row is committed: a quarantined
        // file must prevent the whole publication, not leave an orphaned message.
        List<String> prepared = attachments.requirePublishable(organizationId, actor, draft.id());
        String id = MailInputs.newId();
        String threadId = draft.threadId() == null ? id : draft.threadId();
        SentMail message = new SentMail(organizationId, id, draft.facilityId(), threadId, draft.subject(),
                draft.body(), actor, SENDER_ROLE, clock.instant(), draft.to(), draft.cc(), expanded,
                draft.attachmentDocumentIds(), List.of());
        String published = store.publish(organizationId, actor, clientMessageId, draft.id(), message, expanded);
        attachments.publish(organizationId, actor, draft.id(), published, Set.copyOf(expanded), prepared);
        return new MailView(storedMessage(organizationId, published), MailFolder.SENT);
    }

    /**
     * Create a reply draft, either to the sender alone or to every original
     * participant except the person replying.
     *
     * @param command reply command with the original message id
     * @return a draft in the same thread as the original
     * @throws ApiException on invalid input or an invisible message
     */
    public DraftView reply(ReplyCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        SentMail original = visible(organizationId, command.messageId(), actor);
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
        store.saveDraft(organizationId, draft);
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
    public DraftView forward(ForwardCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        SentMail original = visible(organizationId, command.messageId(), actor);
        DraftView draft = new DraftView(organizationId, MailInputs.newId(), original.facilityId(), actor, 1,
                "Fwd: " + MailRules.normalizeSubject(original.subject()), command.body(),
                MailInputs.orEmpty(command.to()), MailInputs.orEmpty(command.cc()),
                original.attachmentDocumentIds(), original.threadId(), clock.instant());
        store.saveDraft(organizationId, draft);
        return draft;
    }

    /**
     * Move a message into a folder belonging to the acting person only.
     *
     * @param command move command with the target folder
     * @return the message with the viewer's new folder
     * @throws ApiException on invalid input or an invisible message
     */
    public MailView move(MoveCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        if (command.folder() == null) {
            throw new ApiException(422, "INVALID_FOLDER", "a target folder is required");
        }
        SentMail message = visible(organizationId, command.messageId(), actor);
        store.setFolder(organizationId, message.id(), actor, command.folder());
        return new MailView(message, command.folder());
    }

    /**
     * Set or clear one flag on a message visible to the acting person.
     *
     * @param command flag command carrying the flag and desired state
     * @return the message with its updated flags
     * @throws ApiException on invalid input or an invisible message
     */
    public MailView flag(FlagCommand command) {
        String organizationId = MailInputs.requireText(command.organizationId(),
                "INVALID_ORGANIZATION", "active organization is required");
        String actor = MailInputs.requireText(command.actor(), "INVALID_ACTOR", "actor is required");
        String flag = MailInputs.requireText(command.flag(), "INVALID_FLAG", "a flag is required");
        SentMail message = visible(organizationId, command.messageId(), actor);
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
        store.setFlags(organizationId, message.id(), flags);
        return new MailView(updated, folderFor(organizationId, message.id(), actor));
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
    public List<MailView> listFolder(String organizationId, String actor, MailFolder folder) {
        MailInputs.requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(actor, "INVALID_ACTOR", "actor is required");
        if (folder == null) {
            throw new ApiException(422, "INVALID_FOLDER", "a folder is required");
        }
        Map<String, MailFolder> folders = store.folders(organizationId, actor);
        List<MailView> result = new ArrayList<>();
        for (SentMail message : store.messages(organizationId)) {
            if (folder == folders.get(message.id())) {
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
    public List<ThreadView> listThreads(String organizationId, String actor) {
        MailInputs.requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(actor, "INVALID_ACTOR", "actor is required");
        Map<String, MailFolder> folders = store.folders(organizationId, actor);
        Map<String, List<MailView>> grouped = new LinkedHashMap<>();
        Map<String, String> references = new LinkedHashMap<>();
        for (SentMail message : store.messages(organizationId)) {
            if (visibleTo(actor, message)) {
                String key = MailRules.normalizeSubject(message.subject()) + "|" + message.threadId();
                MailFolder placement = folders.getOrDefault(message.id(), MailFolder.INBOX);
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new MailView(message, placement));
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
    public MailView view(String organizationId, String actor, String messageId) {
        MailInputs.requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        MailInputs.requireText(actor, "INVALID_ACTOR", "actor is required");
        SentMail message = visible(organizationId, messageId, actor);
        return new MailView(message, folderFor(organizationId, messageId, actor));
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

    private DraftView draft(String organizationId, String draftId, String actor) {
        return store.draft(organizationId, draftId, actor)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "mail draft not found"));
    }

    private SentMail storedMessage(String organizationId, String messageId) {
        return store.message(organizationId, messageId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "mail message not found"));
    }

    private SentMail visible(String organizationId, String messageId, String actor) {
        SentMail message = storedMessage(organizationId, messageId);
        if (!visibleTo(actor, message)) {
            throw new ApiException(404, "NOT_FOUND", "mail message not found");
        }
        return message;
    }

    private static boolean visibleTo(String actor, SentMail message) {
        return actor.equals(message.senderSubject()) || message.resolvedRecipients().contains(actor);
    }

    private MailFolder folderFor(String organizationId, String messageId, String actor) {
        return store.folders(organizationId, actor).getOrDefault(messageId, MailFolder.INBOX);
    }
}
