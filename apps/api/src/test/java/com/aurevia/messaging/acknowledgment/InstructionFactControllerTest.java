package com.aurevia.messaging.acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Fact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ResolveCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Timeline;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ViewCommand;

/**
 * Unit acceptance for the thin MSG-05 HTTP adapter. The adapter binds fields
 * and delegates; it never makes an authorization or state decision.
 */
class InstructionFactControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String INSTRUCTION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InstructionFactService service;
    private InstructionFactController controller;

    @BeforeEach
    void setUp() {
        service = mock(InstructionFactService.class);
        controller = new InstructionFactController(service);
    }

    @Test
    void publishRouteDelegatesUnchanged() {
        PublishCommand command = new PublishCommand(INSTRUCTION, "patient-1", UNIT, 1,
                "artifact-1", true, NOW.toString(), true, "client-1");
        Publication publication = new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW);
        when(service.publish(command)).thenReturn(publication);
        assertEquals(publication, controller.publish(command));
        verify(service).publish(command);
    }

    @Test
    void retryRouteDelegatesUnchanged() {
        RetryCommand command = new RetryCommand(INSTRUCTION, 1, "retry-1");
        Dispatch dispatch = new Dispatch(ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-1", NOW);
        when(service.retryDispatch(command)).thenReturn(dispatch);
        assertEquals(dispatch, controller.retry(command));
        verify(service).retryDispatch(command);
    }

    @Test
    void firstViewRouteDelegatesUnchanged() {
        ViewCommand command = new ViewCommand(INSTRUCTION, 1, "view-1");
        FirstView view = new FirstView(ORG, INSTRUCTION, 1, "patient-1", NOW);
        when(service.markFirstView(command)).thenReturn(view);
        assertEquals(view, controller.firstView(command));
        verify(service).markFirstView(command);
    }

    @Test
    void acknowledgeRouteDelegatesUnchanged() {
        AcknowledgeCommand command = new AcknowledgeCommand(INSTRUCTION, 1, "ack-1");
        Acknowledgment acknowledgment = new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW);
        when(service.acknowledge(command)).thenReturn(acknowledgment);
        assertEquals(acknowledgment, controller.acknowledge(command));
        verify(service).acknowledge(command);
    }

    @Test
    void factsRouteDelegatesTheIdentifier() {
        Timeline timeline = new Timeline(List.of((Fact) new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW)));
        when(service.facts(INSTRUCTION)).thenReturn(timeline);
        assertEquals(timeline, controller.facts(INSTRUCTION));
        verify(service).facts(INSTRUCTION);
    }

    @Test
    void overdueRouteDelegatesTheUnit() {
        List<FollowUp> followUps = List.of(new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW,
                FollowUpState.ROUTED, null, null, null));
        when(service.overdueFollowUps(UNIT)).thenReturn(followUps);
        assertEquals(followUps, controller.overdue(UNIT));
        verify(service).overdueFollowUps(UNIT);
    }

    @Test
    void resolveRouteDelegatesUnchanged() {
        ResolveCommand command = new ResolveCommand(INSTRUCTION, 1, "owner contacted");
        FollowUp resolved = new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW, FollowUpState.RESOLVED,
                "staff-1", NOW, "owner contacted");
        when(service.resolveFollowUp(command)).thenReturn(resolved);
        assertEquals(resolved, controller.resolve(command));
        verify(service).resolveFollowUp(command);
    }
}
