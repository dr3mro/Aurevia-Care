package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeEvent;
import com.aurevia.exchange.response.ExchangeResponseCommands.ExchangeOrder;
import com.aurevia.exchange.response.ExchangeState;
import com.aurevia.exchange.response.ExchangeResponseCommands.OrderView;
import com.aurevia.exchange.response.ExchangeResponseCommands.Revision;
import com.aurevia.exchange.response.ExchangeResponseCommands.AcceptRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.CancellationResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ChangeResponseRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ClarifyRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ProposeChangeRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.ReasonRequest;
import com.aurevia.exchange.response.ExchangeResponseCommands.RevokeRequest;
import com.aurevia.exchange.response.ExchangeResponseController;
import com.aurevia.exchange.response.ExchangeResponseService;

/** HTTP adapter delegation and version-precondition parsing for XCH-03. */
class ExchangeResponseControllerTest {

    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private ExchangeResponseService service;
    private ExchangeResponseController controller;
    private ExchangeOrder order;

    @BeforeEach
    void setUp() {
        service = mock(ExchangeResponseService.class);
        controller = new ExchangeResponseController(service);
        order = new ExchangeOrder(ID, 2, "LAB-REQ-1", ExchangeState.ACCEPTED, 1, "a".repeat(64),
                null, null, null, null, null, null);
        when(service.get(anyString())).thenReturn(new OrderView(order, List.of(), List.of(),
                List.of(), List.of()));
        when(service.history(anyString())).thenReturn(List.<ExchangeEvent>of());
        when(service.retrieve(anyString())).thenReturn(new Revision("rev", 1, null, "a".repeat(64),
                null, "actor", java.time.Instant.parse("2026-11-19T12:00:00Z")));
        when(service.accept(anyString(), anyInt(), any())).thenReturn(order);
        when(service.reject(anyString(), anyInt(), any())).thenReturn(order);
        when(service.clarify(anyString(), anyInt(), any())).thenReturn(order);
        when(service.proposeChange(anyString(), anyInt(), any())).thenReturn(order);
        when(service.changeResponse(anyString(), anyInt(), any())).thenReturn(order);
        when(service.requestCancellation(anyString(), anyInt(), any())).thenReturn(order);
        when(service.cancellationResponse(anyString(), anyInt(), any())).thenReturn(order);
        when(service.revoke(anyString(), anyInt(), any())).thenReturn(order);
    }

    @Test
    void delegatesReadEndpoints() {
        assertEquals(ID, controller.get(ID).order().id());
        assertEquals(0, controller.history(ID).size());
        assertEquals("rev", controller.retrieve(ID).id());
        verify(service).get(ID);
        verify(service).history(ID);
        verify(service).retrieve(ID);
    }

    @Test
    void delegatesEveryCommandWithTheParsedVersion() {
        AcceptRequest accept = new AcceptRequest("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                null, "RESULT_REVIEW", "STANDARD");
        ReasonRequest reason = new ReasonRequest("why");
        assertEquals(ID, controller.accept(ID, accept, "1").id());
        assertEquals(ID, controller.reject(ID, reason, "W/\"2\"").id());
        assertEquals(ID, controller.clarify(ID, new ClarifyRequest("question"), "\"3\"").id());
        assertEquals(ID, controller.proposeChange(ID,
                new ProposeChangeRequest("change", "a".repeat(64)), "1").id());
        assertEquals(ID, controller.changeResponse(ID,
                new ChangeResponseRequest("accept", null), "1").id());
        assertEquals(ID, controller.requestCancellation(ID, reason, "1").id());
        assertEquals(ID, controller.cancellationResponse(ID,
                new CancellationResponseRequest("confirm", null), "1").id());
        assertEquals(ID, controller.revoke(ID, new RevokeRequest("reason", "STANDARD", "target",
                "channel", "event-1"), "1").id());

        verify(service).accept(ID, 1, accept);
        verify(service).reject(ID, 2, reason);
        verify(service).clarify(ID, 3, new ClarifyRequest("question"));
        verify(service).proposeChange(ID, 1, new ProposeChangeRequest("change", "a".repeat(64)));
        verify(service).changeResponse(ID, 1, new ChangeResponseRequest("accept", null));
        verify(service).requestCancellation(ID, 1, reason);
        verify(service).cancellationResponse(ID, 1, new CancellationResponseRequest("confirm", null));
        verify(service).revoke(ID, 1, new RevokeRequest("reason", "STANDARD", "target",
                "channel", "event-1"));
    }

    @Test
    void rejectsMissingAndMalformedPreconditionsBeforeTheService() {
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.accept(ID, new AcceptRequest(null, null, null, null), null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.reject(ID, new ReasonRequest("why"), "abc")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.clarify(ID, new ClarifyRequest("q"), "0")).status());
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.proposeChange(ID,
                        new ProposeChangeRequest("c", "a".repeat(64)), null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> controller.changeResponse(ID,
                        new ChangeResponseRequest("accept", null), "nope")).status());
        assertEquals(428, assertThrows(ApiException.class,
                () -> controller.requestCancellation(ID, new ReasonRequest("why"), null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> controller.cancellationResponse(ID,
                new CancellationResponseRequest("confirm", null), "-1")).status());
        assertEquals(428, assertThrows(ApiException.class, () -> controller.revoke(ID,
                new RevokeRequest("r", "STANDARD", "t", "c", "e"), null)).status());
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .accept(anyString(), anyInt(), any());
    }

    @Test
    void passesTheParsedVersionThroughToEveryCommand() {
        ReasonRequest reason = new ReasonRequest("why");
        controller.reject(ID, reason, eqVersion(4));
        verify(service).reject(eq(ID), eq(4), eq(reason));
    }

    private static String eqVersion(int version) {
        return "\"" + version + "\"";
    }
}
