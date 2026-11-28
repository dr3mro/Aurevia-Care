package com.aurevia.staffmsg.mail;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.api.contract.IdempotencyKey;
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
import com.aurevia.staffmsg.mail.MailContract.ThreadView;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for email-like staff messages.
 *
 * <p>The controller binds paths, headers and command bodies only. The active
 * tenant and actor always come from the request context; every decision is made
 * by the injected domain service. Write commands require an
 * {@code Idempotency-Key}, draft saves require an {@code If-Match} version and
 * send requires the confirmed recipient list in the body.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/mail")
public final class MailController {

    private final MailService service;

    /**
     * Create the route over the injected mail kernel.
     *
     * @param service mail domain service
     */
    @Autowired
    public MailController(MailService service) {
        this.service = service;
    }

    /**
     * Start a private draft.
     *
     * @param request compose body with recipients, subject and body
     * @return the version-one draft
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/drafts")
    public DraftView create(@RequestBody ComposeRequest request) {
        TenantContext context = context();
        return service.createDraft(new CreateDraftCommand(context.organizationId(), request.facilityId(),
                context.subject(), request.subject(), request.body(), request.to(), request.cc(), request.bcc(),
                request.attachmentDocumentIds()));
    }

    /**
     * Save a new version of a draft.
     *
     * @param id       draft identifier from the path
     * @param ifMatch  version the caller read
     * @param request  replacement draft content
     * @return the advanced draft
     */
    @PutMapping("/drafts/{id}")
    public DraftView save(@PathVariable String id,
                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                          @RequestBody ComposeRequest request) {
        TenantContext context = context();
        return service.saveDraft(new SaveDraftCommand(context.organizationId(), context.subject(), id,
                IfMatch.parse(ifMatch), request.subject(), request.body(), request.to(), request.cc(),
                request.bcc(), request.attachmentDocumentIds()));
    }

    /**
     * Publish a draft after confirming the resolved recipients.
     *
     * @param id      draft identifier from the path
     * @param idem    retry key for the publication
     * @param request body carrying the confirmed recipient list
     * @return the published message
     */
    @PostMapping("/drafts/{id}/send")
    public MailView send(@PathVariable String id,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                         @RequestBody SendRequest request) {
        TenantContext context = context();
        return service.send(new SendCommand(context.organizationId(), context.subject(), id,
                request.confirmedRecipients(), IdempotencyKey.parse(idem).value()));
    }

    /**
     * Create a reply draft to the sender.
     *
     * @param id      original message identifier
     * @param request reply body
     * @return the reply draft
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/messages/{id}/reply")
    public DraftView reply(@PathVariable String id, @RequestBody TextRequest request) {
        TenantContext context = context();
        return service.reply(new ReplyCommand(context.organizationId(), context.subject(), id, false,
                request.body()));
    }

    /**
     * Create a reply-all draft addressing every original participant.
     *
     * @param id      original message identifier
     * @param request reply body
     * @return the reply-all draft
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/messages/{id}/reply-all")
    public DraftView replyAll(@PathVariable String id, @RequestBody TextRequest request) {
        TenantContext context = context();
        return service.reply(new ReplyCommand(context.organizationId(), context.subject(), id, true,
                request.body()));
    }

    /**
     * Create a forward draft keeping the original attachment links.
     *
     * @param id      original message identifier
     * @param request new recipients and optional body
     * @return the forward draft
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/messages/{id}/forward")
    public DraftView forward(@PathVariable String id, @RequestBody ComposeRequest request) {
        TenantContext context = context();
        return service.forward(new ForwardCommand(context.organizationId(), context.subject(), id,
                request.to(), request.cc(), request.body()));
    }

    /**
     * Move a message into the acting person's folder.
     *
     * @param id      message identifier
     * @param request target folder
     * @return the message in its new folder
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/messages/{id}/move")
    public MailView move(@PathVariable String id, @RequestBody FolderRequest request) {
        TenantContext context = context();
        return service.move(new MoveCommand(context.organizationId(), context.subject(), id,
                parseFolder(request.folder())));
    }

    /**
     * Set or clear a flag on a message.
     *
     * @param id      message identifier
     * @param request flag name and desired state
     * @return the message with its updated flags
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/messages/{id}/flag")
    public MailView flag(@PathVariable String id, @RequestBody FlagRequest request) {
        TenantContext context = context();
        return service.flag(new FlagCommand(context.organizationId(), context.subject(), id,
                request.flag(), request.enabled()));
    }

    /**
     * List the acting person's messages in one folder.
     *
     * @param folder folder name from the path
     * @return the messages in that folder
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @GetMapping("/folders/{folder}")
    public List<MailView> folder(@PathVariable String folder) {
        TenantContext context = context();
        return service.listFolder(context.organizationId(), context.subject(), parseFolder(folder));
    }

    /**
     * Read one message visible to the acting person.
     *
     * @param id message identifier
     * @return the message with the reader's folder
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @GetMapping("/messages/{id}")
    public MailView message(@PathVariable String id) {
        TenantContext context = context();
        return service.view(context.organizationId(), context.subject(), id);
    }

    /**
     * List the acting person's threads.
     *
     * @return threads grouped by normalized subject and reference
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @GetMapping("/threads")
    public List<ThreadView> threads() {
        TenantContext context = context();
        return service.listThreads(context.organizationId(), context.subject());
    }

    private static MailFolder parseFolder(String value) {
        try {
            return MailFolder.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_FOLDER", "unknown mail folder: " + value);
        }
    }

    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "an active tenant context is required");
        }
        return context;
    }

    /**
     * Compose or save body. {@code bcc} is accepted only so it can be refused.
     *
     * @return the immutable body
     */
    public record ComposeRequest(String facilityId, String subject, String body, List<Recipient> to,
                                 List<Recipient> cc, List<String> bcc, List<String> attachmentDocumentIds) {

        /**
         * Freeze every client list.
         */
        public ComposeRequest {
            to = MailInputs.freeze(to);
            cc = MailInputs.freeze(cc);
            bcc = MailInputs.freeze(bcc);
            attachmentDocumentIds = MailInputs.freeze(attachmentDocumentIds);
        }

        /**
         * @return immutable to recipients
         */
        @Override
        public List<Recipient> to() {
            return MailInputs.freeze(to);
        }

        /**
         * @return immutable cc recipients
         */
        @Override
        public List<Recipient> cc() {
            return MailInputs.freeze(cc);
        }

        /**
         * @return immutable blind-copy field, rejected by the service
         */
        @Override
        public List<String> bcc() {
            return MailInputs.freeze(bcc);
        }

        /**
         * @return immutable attachment identifiers
         */
        @Override
        public List<String> attachmentDocumentIds() {
            return MailInputs.freeze(attachmentDocumentIds);
        }
    }

    /**
     * Send body carrying the explicitly confirmed recipient list.
     *
     * @return the immutable body
     * @param confirmedRecipients value supplied by the caller
     */
    public record SendRequest(List<String> confirmedRecipients) {

        /**
         * Freeze the confirmation list, keeping an omitted field null.
         */
        public SendRequest {
            confirmedRecipients = confirmedRecipients == null ? null : List.copyOf(confirmedRecipients);
        }

        /**
         * @return immutable confirmed recipients, or null when omitted
         */
        @Override
        public List<String> confirmedRecipients() {
            return confirmedRecipients == null ? null : List.copyOf(confirmedRecipients);
        }
    }

    /**
     * Reply body with the new text.
     *
     * @return the immutable body
     * @param body value supplied by the caller
     */
    public record TextRequest(String body) {
    }

    /**
     * Move body with the target folder name.
     *
     * @return the immutable body
     * @param folder value supplied by the caller
     */
    public record FolderRequest(String folder) {
    }

    /**
     * Flag body with the flag name and desired state.
     *
     * @return the immutable body
     * @param flag value supplied by the caller
     * @param enabled value supplied by the caller
     */
    public record FlagRequest(String flag, boolean enabled) {
    }
}
