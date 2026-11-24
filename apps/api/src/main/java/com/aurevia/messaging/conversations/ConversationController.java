package com.aurevia.messaging.conversations;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.messaging.conversations.ConversationContract.AssignRequest;
import com.aurevia.messaging.conversations.ConversationContract.CloseRequest;
import com.aurevia.messaging.conversations.ConversationContract.Conversation;
import com.aurevia.messaging.conversations.ConversationContract.Inbox;
import com.aurevia.messaging.conversations.ConversationContract.OpenRequest;
import com.aurevia.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.messaging.conversations.ConversationContract.ResolveRequest;
import com.aurevia.messaging.conversations.ConversationContract.TransferRequest;
import com.aurevia.messaging.conversations.ConversationContract.View;
import com.aurevia.messaging.conversations.ConversationContract.WorkQueue;

/**
 * Thin HTTP adapter for the MSG-02 conversation lifecycle (ARC-03, ARC-04).
 *
 * <p>Every mutating command requires an {@code If-Match} version, parsed here.
 * Authorization, state, unit and relationship invariants live in the domain
 * service; this controller only binds JSON and headers.</p>
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService service;

    /**
     * Create the controller.
     *
     * @param service conversation domain service
     */
    public ConversationController(ConversationService service) {
        this.service = service;
    }

    /**
     * Open a conversation.
     *
     * @param request open command
     * @return HTTP 201 with the created conversation
     */
    @PostMapping
    public ResponseEntity<Conversation> open(@RequestBody OpenRequest request) {
        Conversation created = service.open(request);
        return ResponseEntity.created(URI.create("/api/v1/conversations/" + created.id())).body(created);
    }

    /**
     * Read the caller's patient inbox; declared before the id route.
     *
     * @return the inbox with unread counts
     */
    @GetMapping("/inbox")
    public Inbox inbox() {
        return service.inbox();
    }

    /**
     * Read the role-scoped staff work queue; declared before the id route.
     *
     * @return the work queue
     */
    @GetMapping("/work-queue")
    public WorkQueue workQueue() {
        return service.workQueue();
    }

    /**
     * Read one thread with both trails.
     *
     * @param id conversation identifier
     * @return the thread view
     */
    @GetMapping("/{id}")
    public View get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Post a reply.
     *
     * @param id          conversation identifier
     * @param request     reply command
     * @param ifMatch     expected version
     * @return the updated thread
     */
    @PostMapping("/{id}/reply")
    public View reply(@PathVariable String id, @RequestBody ReplyRequest request,
                      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.reply(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Assign the work item.
     *
     * @param id      conversation identifier
     * @param request assignment command
     * @param ifMatch expected version
     * @return the updated conversation
     */
    @PostMapping("/{id}/assign")
    public Conversation assign(@PathVariable String id, @RequestBody AssignRequest request,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.assign(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Transfer the work item inside the same unit.
     *
     * @param id      conversation identifier
     * @param request transfer command
     * @param ifMatch expected version
     * @return the updated conversation
     */
    @PostMapping("/{id}/transfer")
    public Conversation transfer(@PathVariable String id, @RequestBody TransferRequest request,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.transfer(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Resolve the work item.
     *
     * @param id      conversation identifier
     * @param request resolve command
     * @param ifMatch expected version
     * @return the updated conversation
     */
    @PostMapping("/{id}/resolve")
    public Conversation resolve(@PathVariable String id, @RequestBody ResolveRequest request,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.resolve(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Close the thread, making it read-only.
     *
     * @param id      conversation identifier
     * @param request close command
     * @param ifMatch expected version
     * @return the updated conversation
     */
    @PostMapping("/{id}/close")
    public Conversation close(@PathVariable String id, @RequestBody CloseRequest request,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.close(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record that the caller viewed the thread.
     *
     * @param id      conversation identifier
     * @param ifMatch expected version
     * @return the updated conversation
     */
    @PostMapping("/{id}/read")
    public Conversation markRead(@PathVariable String id,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.markRead(id, IfMatch.parse(ifMatch));
    }
}
