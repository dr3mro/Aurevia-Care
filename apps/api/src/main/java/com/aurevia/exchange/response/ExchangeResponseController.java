package com.aurevia.exchange.response;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeEvent;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeResponseCommands.OrderView;
import com.aurevia.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.exchange.response.ExchangeResponseCommands.AcceptRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.CancellationResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ChangeResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ClarifyRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ProposeChangeRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ReasonRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.RevokeRequest;

/**
 * Thin HTTP adapter for XCH-03 order responses (ARC-03, ARC-04).
 *
 * <p>Every irreversible command requires an {@code If-Match} version, parsed here
 * into a machine-readable failure before the domain service compares it.
 * Authorization, state transitions, retained copies, notifications and audit
 * all live in {@link com.aurevia.exchange.response.ExchangeResponseService}.</p>
 */
@RestController
@RequestMapping("/api/v1/xch03/orders")
public class ExchangeResponseController {

    private final ExchangeResponseService service;

    /**
     * Create the controller.
     *
     * @param service XCH-03 domain service
     */
    public ExchangeResponseController(ExchangeResponseService service) {
        this.service = service;
    }

    /**
     * Read one order with its append-only ledgers.
     *
     * @param id order identifier
     * @return the order view
     */
    @GetMapping("/{id}")
    public OrderView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Read the response history.
     *
     * @param id order identifier
     * @return the append-only events
     */
    @GetMapping("/{id}/history")
    public java.util.List<ExchangeEvent> history(@PathVariable String id) {
        return service.history(id);
    }

    /**
     * Retrieve the current released revision; revoked releases return 410.
     *
     * @param id order identifier
     * @return the released revision
     */
    @GetMapping("/{id}/retrieve")
    public Revision retrieve(@PathVariable String id) {
        return service.retrieve(id);
    }

    /**
     * Accept with a verified local association and retained purpose.
     *
     * @param id      order identifier
     * @param request accept command
     * @param ifMatch expected version
     * @return the accepted order
     */
    @PostMapping("/{id}/accept")
    public ExchangeOrder accept(@PathVariable String id, @RequestBody AcceptRequest request,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.accept(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Reject with a recorded reason.
     *
     * @param id      order identifier
     * @param request reason
     * @param ifMatch expected version
     * @return the rejected order
     */
    @PostMapping("/{id}/reject")
    public ExchangeOrder reject(@PathVariable String id, @RequestBody ReasonRequest request,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.reject(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Request clarification with a recorded question.
     *
     * @param id      order identifier
     * @param request question
     * @param ifMatch expected version
     * @return the order awaiting clarification
     */
    @PostMapping("/{id}/clarify")
    public ExchangeOrder clarify(@PathVariable String id, @RequestBody ClarifyRequest request,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.clarify(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Propose a linked change revision after acceptance.
     *
     * @param id      order identifier
     * @param request change summary and checksum
     * @param ifMatch expected version
     * @return the order with a change awaiting response
     */
    @PostMapping("/{id}/propose-change")
    public ExchangeOrder proposeChange(@PathVariable String id, @RequestBody ProposeChangeRequest request,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.proposeChange(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Respond to a linked change.
     *
     * @param id      order identifier
     * @param request decision and optional reason
     * @param ifMatch expected version
     * @return the resolved order
     */
    @PostMapping("/{id}/change-response")
    public ExchangeOrder changeResponse(@PathVariable String id,
                                        @RequestBody ChangeResponseRequest request,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.changeResponse(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Request cancellation after acceptance.
     *
     * @param id      order identifier
     * @param request reason
     * @param ifMatch expected version
     * @return the order with a cancellation awaiting response
     */
    @PostMapping("/{id}/request-cancellation")
    public ExchangeOrder requestCancellation(@PathVariable String id, @RequestBody ReasonRequest request,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.requestCancellation(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Respond to a cancellation request.
     *
     * @param id      order identifier
     * @param request decision and optional reason
     * @param ifMatch expected version
     * @return the resolved order
     */
    @PostMapping("/{id}/cancellation-response")
    public ExchangeOrder cancellationResponse(@PathVariable String id,
                                              @RequestBody CancellationResponseRequest request,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.cancellationResponse(id, IfMatch.parse(ifMatch), request);
    }

    /**
     * Revoke a release and notify downstream recipients.
     *
     * @param id      order identifier
     * @param request reason, policy and notification details
     * @param ifMatch expected version
     * @return the revoked order
     */
    @PostMapping("/{id}/revoke")
    public ExchangeOrder revoke(@PathVariable String id, @RequestBody RevokeRequest request,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.revoke(id, IfMatch.parse(ifMatch), request);
    }
}
