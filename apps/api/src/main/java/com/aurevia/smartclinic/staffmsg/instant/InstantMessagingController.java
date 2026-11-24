package com.aurevia.smartclinic.staffmsg.instant;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.AddParticipantRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.ConversationView;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.CreateGroupRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.MarkReadRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.MessageView;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.OpenDirectRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.SendRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.UnreadView;

/**
 * Thin HTTP adapter for instant staff conversations inside a facility (MSG-02, ARC-07).
 *
 * <p>The active organization and actor come from the request context; a caller
 * only names a facility and resource identifiers. Authorization, membership
 * history and idempotency live in the domain service. The send endpoint takes
 * the durable client message id from the {@code Idempotency-Key} header.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/instant")
public class InstantMessagingController {

    private final InstantMessagingService service;

    /**
     * Create the controller with its injected domain service.
     *
     * @param service instant messaging service
     */
    public InstantMessagingController(InstantMessagingService service) {
        this.service = service;
    }

    /**
     * Open or find the direct conversation with a peer.
     *
     * @param request facility and peer
     * @return the conversation view
     */
    @PostMapping("/direct")
    public ResponseEntity<ConversationView> openDirect(@RequestBody OpenDirectRequest request) {
        return ResponseEntity.status(201).body(service.openDirect(request));
    }

    /**
     * Create a group conversation.
     *
     * @param request facility, topic and peers
     * @return the conversation view
     */
    @PostMapping("/groups")
    public ResponseEntity<ConversationView> createGroup(@RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(201).body(service.createGroup(request));
    }

    /**
     * Add a participant to a group.
     *
     * @param request facility, conversation and subject
     * @return the updated conversation view
     */
    @PostMapping("/participants")
    public ConversationView addParticipant(@RequestBody AddParticipantRequest request) {
        return service.addParticipant(request);
    }

    /**
     * Close a participant's membership while keeping history.
     *
     * @param conversationId owning conversation
     * @param subject        participant to remove
     * @param facilityId     facility the conversation must belong to
     * @return the updated conversation view
     */
    @DeleteMapping("/{conversationId}/participants/{subject}")
    public ConversationView removeParticipant(@PathVariable String conversationId,
                                              @PathVariable String subject,
                                              @RequestParam String facilityId) {
        return service.removeParticipant(facilityId, conversationId, subject);
    }

    /**
     * Publish a message to the conversation in the route, using the header key to
     * deduplicate retries. The path id is forwarded as the authorization and write
     * target; the body is passed through unchanged so the service can reject a
     * mismatched client id before any state changes.
     *
     * @param conversationId owning conversation from the path
     * @param request        facility, matching conversation id and body
     * @param idempotencyKey durable client message id
     * @return the published message
     */
    @PostMapping("/{conversationId}/messages")
    public MessageView send(@PathVariable String conversationId, @RequestBody SendRequest request,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.send(conversationId, request, idempotencyKey);
    }

    /**
     * List messages visible to the caller.
     *
     * @param conversationId owning conversation
     * @param facilityId     facility the conversation must belong to
     * @return messages ordered by sequence
     */
    @GetMapping("/{conversationId}/messages")
    public List<MessageView> messages(@PathVariable String conversationId,
                                      @RequestParam String facilityId) {
        return service.messages(facilityId, conversationId);
    }

    /**
     * Advance the caller's read cursor up to a sequence.
     *
     * @param request facility, conversation and sequence
     * @return the remaining unread count
     */
    @PostMapping("/read")
    public UnreadView markRead(@RequestBody MarkReadRequest request) {
        return service.markRead(request);
    }

    /**
     * List unread counts for the caller in a facility.
     *
     * @param facilityId facility filter
     * @return one count per conversation
     */
    @GetMapping("/unread")
    public List<UnreadView> unread(@RequestParam String facilityId) {
        return service.unreadCounts(facilityId);
    }

    /**
     * List the caller's conversations with their unread counts.
     *
     * @param facilityId facility filter
     * @return conversation views
     */
    @GetMapping("/conversations")
    public List<ConversationView> conversations(@RequestParam String facilityId) {
        return service.conversations(facilityId);
    }
}
