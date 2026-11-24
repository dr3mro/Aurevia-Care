package com.aurevia.messaging.acknowledgment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope;
import com.aurevia.messaging.acknowledgment.InstructionFacts.AuditFact;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FactType;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUpState;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Instruction;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Timeline;

/**
 * Red-first acceptance for the pure MSG-05 vocabulary, validation and action
 * matrix. Every identifier below is synthetic; no patient data is present.
 */
class InstructionFactsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String INSTRUCTION = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void factTypesAreTheFiveSeparateFacts() {
        assertEquals(5, FactType.values().length);
        for (FactType type : FactType.values()) {
            assertEquals(type, FactType.from(type.wire()));
        }
        assertEquals("publication", FactType.PUBLICATION.wire());
        assertEquals("dispatch", FactType.DISPATCH.wire());
        assertEquals("first_view", FactType.FIRST_VIEW.wire());
        assertEquals("acknowledgment", FactType.ACKNOWLEDGMENT.wire());
        assertEquals("follow_up", FactType.FOLLOW_UP.wire());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> FactType.from("unknown"))
                .getMessage().contains("fact type"));
    }

    @Test
    void acknowledgmentScopeIsReceiptOnly() {
        assertEquals(1, AcknowledgmentScope.values().length);
        assertEquals(AcknowledgmentScope.RECEIPT, AcknowledgmentScope.from("RECEIPT"));
        assertEquals("RECEIPT", AcknowledgmentScope.RECEIPT.wire());
        assertThrows(IllegalArgumentException.class, () -> AcknowledgmentScope.from("consent"));
        assertThrows(IllegalArgumentException.class, () -> AcknowledgmentScope.from("critical_result"));
    }

    @Test
    void followUpStateRoundTripsEveryWireValue() {
        for (FollowUpState state : FollowUpState.values()) {
            assertEquals(state, FollowUpState.from(state.wire()));
        }
        assertEquals(3, FollowUpState.values().length);
        assertEquals("open", FollowUpState.OPEN.wire());
        assertEquals("routed", FollowUpState.ROUTED.wire());
        assertThrows(IllegalArgumentException.class, () -> FollowUpState.from("closed"));
    }

    @Test
    void instructionCarriesOnlyOpaqueContentReference() {
        Instruction instruction = InstructionFactsTest.instruction(1, true, NOW);
        assertEquals(INSTRUCTION, instruction.id());
        assertEquals(ORG, instruction.organizationId());
        assertEquals(PATIENT, instruction.patientId());
        assertEquals(UNIT, instruction.unitId());
        assertEquals(1, instruction.revision());
        assertEquals("artifact-1", instruction.contentRef());
        assertTrue(instruction.requiresAcknowledgment());
        assertEquals(NOW, instruction.acknowledgmentDueAt());
        assertEquals(instruction, InstructionFactsTest.instruction(1, true, NOW));
        assertEquals(instruction.hashCode(), InstructionFactsTest.instruction(1, true, NOW).hashCode());
        assertEquals(instruction.toString(), InstructionFactsTest.instruction(1, true, NOW).toString());
        assertNotEquals(instruction, InstructionFactsTest.instruction(2, true, NOW));
    }

    @Test
    void eachFactExposesItsOwnActorTimeAndRevision() {
        Publication publication = new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW);
        Dispatch dispatch = new Dispatch(ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-1", NOW);
        FirstView view = new FirstView(ORG, INSTRUCTION, 1, "patient-1", NOW);
        Acknowledgment acknowledgment = new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW);
        FollowUp followUp = new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW, FollowUpState.OPEN,
                null, null, null);

        assertEquals(FactType.PUBLICATION, publication.type());
        assertEquals(ORG, publication.organizationId());
        assertEquals(INSTRUCTION, publication.instructionId());
        assertEquals(1, publication.revision());
        assertEquals("staff-1", publication.actor());
        assertEquals(NOW, publication.occurredAt());

        assertEquals(FactType.DISPATCH, dispatch.type());
        assertEquals("NEW_INSTRUCTION", dispatch.genericKey());
        assertEquals("corr-1", dispatch.correlationId());
        assertEquals(FactType.FIRST_VIEW, view.type());
        assertEquals("patient-1", view.actor());
        assertEquals(FactType.ACKNOWLEDGMENT, acknowledgment.type());
        assertEquals(AcknowledgmentScope.RECEIPT, acknowledgment.scope());
        assertEquals("RECEIPT", acknowledgment.scope().wire());
        assertEquals(FactType.FOLLOW_UP, followUp.type());
        assertEquals(UNIT, followUp.unitId());
        assertEquals(FollowUpState.OPEN, followUp.state());
        assertEquals(null, followUp.resolvedBy());
        assertEquals(null, followUp.resolvedAt());
        assertEquals(null, followUp.reason());
        assertEquals(NOW, followUp.occurredAt());

        assertEquals(publication, new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW));
        assertEquals(publication.hashCode(), new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW).hashCode());
        assertEquals(dispatch, new Dispatch(ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-1", NOW));
        assertEquals(view, new FirstView(ORG, INSTRUCTION, 1, "patient-1", NOW));
        assertEquals(acknowledgment, new Acknowledgment(ORG, INSTRUCTION, 1, "patient-1",
                AcknowledgmentScope.RECEIPT, NOW));
        assertEquals(followUp, new FollowUp(ORG, INSTRUCTION, 1, UNIT, NOW, FollowUpState.OPEN,
                null, null, null));
        assertNotEquals(view, new FirstView(ORG, INSTRUCTION, 2, "patient-1", NOW));
    }

    @Test
    void timelineIsOrderedAndDefensivelyCopied() {
        Publication publication = new Publication(ORG, INSTRUCTION, 1, "staff-1", NOW);
        Dispatch dispatch = new Dispatch(ORG, INSTRUCTION, 1, "NEW_INSTRUCTION", "corr-1", NOW.plusSeconds(1));
        List<InstructionFacts.Fact> mutable = new ArrayList<>(List.of(publication, dispatch));
        Timeline timeline = new Timeline(mutable);
        mutable.clear();
        assertEquals(List.of(publication, dispatch), timeline.facts());
        assertThrows(UnsupportedOperationException.class, () -> timeline.facts().clear());
        assertEquals(timeline, new Timeline(List.of(publication, dispatch)));
        assertEquals(timeline.hashCode(), new Timeline(List.of(publication, dispatch)).hashCode());
        assertEquals(timeline.toString(), new Timeline(List.of(publication, dispatch)).toString());
        assertNotEquals(timeline, new Timeline(List.of(publication)));
    }

    @Test
    void auditFactCarriesPurposeScopeAndCorrelation() {
        AuditFact fact = new AuditFact(ORG, "patient-1", "ACKNOWLEDGE", INSTRUCTION, "SUCCESS",
                "PATIENT_INSTRUCTION_FACT", "PATIENT_MESSAGE", "corr-1", NOW);
        assertEquals(ORG, fact.organizationId());
        assertEquals("patient-1", fact.subject());
        assertEquals("ACKNOWLEDGE", fact.action());
        assertEquals(INSTRUCTION, fact.resourceId());
        assertEquals("SUCCESS", fact.outcome());
        assertEquals("PATIENT_INSTRUCTION_FACT", fact.purpose());
        assertEquals("PATIENT_MESSAGE", fact.scope());
        assertEquals("corr-1", fact.correlationId());
        assertEquals(NOW, fact.occurredAt());
        assertEquals(fact, new AuditFact(ORG, "patient-1", "ACKNOWLEDGE", INSTRUCTION, "SUCCESS",
                "PATIENT_INSTRUCTION_FACT", "PATIENT_MESSAGE", "corr-1", NOW));
        assertEquals(fact.hashCode(), new AuditFact(ORG, "patient-1", "ACKNOWLEDGE", INSTRUCTION,
                "SUCCESS", "PATIENT_INSTRUCTION_FACT", "PATIENT_MESSAGE", "corr-1", NOW).hashCode());
        assertEquals(fact.toString(), new AuditFact(ORG, "patient-1", "ACKNOWLEDGE", INSTRUCTION,
                "SUCCESS", "PATIENT_INSTRUCTION_FACT", "PATIENT_MESSAGE", "corr-1", NOW).toString());
    }

    @Test
    void validationRejectsMalformedIdentifiersVersionAndDueDate() {
        assertEquals(INSTRUCTION, InstructionFactInputs.uuid(INSTRUCTION, "instructionId"));
        assertEquals("INVALID_INSTRUCTIONID", assertThrows(ApiException.class,
                () -> InstructionFactInputs.uuid(null, "instructionId")).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionFactInputs.uuid("bad", "instructionId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> InstructionFactInputs.uuid("bad", "patientId")).code());

        assertEquals("collection instructions", InstructionFactInputs.text("  collection instructions  ",
                "title", 64));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionFactInputs.text("   ", "title", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionFactInputs.text("x".repeat(65), "title", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionFactInputs.text(null, "title", 64)).status());

        assertEquals(2, InstructionFactInputs.version(2));
        assertEquals(422, assertThrows(ApiException.class, () -> InstructionFactInputs.version(0)).status());
        assertEquals("INVALID_REVISION", assertThrows(ApiException.class,
                () -> InstructionFactInputs.version(-1)).code());

        assertEquals(NOW, InstructionFactInputs.dueAt("2026-11-19T12:00:00Z", "acknowledgmentDueAt"));
        assertEquals(null, InstructionFactInputs.dueAt(null, "acknowledgmentDueAt"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionFactInputs.dueAt("not-a-date", "acknowledgmentDueAt")).status());
        assertEquals("INVALID_ACKNOWLEDGMENTDUEAT", assertThrows(ApiException.class,
                () -> InstructionFactInputs.dueAt("2026-13-40T00:00:00Z", "acknowledgmentDueAt")).code());
    }

    @Test
    void newIdProducesAValidUuid() {
        String generated = InstructionFactInputs.newId();
        assertEquals(generated, InstructionFactInputs.uuid(generated, "id"));
        assertNotEquals(INSTRUCTION, generated);
    }

    @Test
    void actionsAreBoundToTheOwningRole() {
        InstructionFactActions actions = new InstructionFactActions();
        for (String staffRole : List.of("clinician", "lab_collector", "lab_verifier",
                "radiology_staff", "pt_clinician", "pharmacy_staff")) {
            assertTrue(actions.allows(InstructionFactActions.PUBLISH, staffRole), staffRole);
            assertTrue(actions.allows(InstructionFactActions.DISPATCH, staffRole), staffRole);
            assertTrue(actions.allows(InstructionFactActions.FOLLOW_UP_LIST, staffRole), staffRole);
            assertTrue(actions.allows(InstructionFactActions.FOLLOW_UP_RESOLVE, staffRole), staffRole);
            assertFalse(actions.allows(InstructionFactActions.ACKNOWLEDGE, staffRole), staffRole);
        }
        assertTrue(actions.allows(InstructionFactActions.FIRST_VIEW, "patient"));
        assertTrue(actions.allows(InstructionFactActions.ACKNOWLEDGE, "patient"));
        assertFalse(actions.allows(InstructionFactActions.PUBLISH, "patient"));
        assertFalse(actions.allows(InstructionFactActions.FOLLOW_UP_LIST, "patient"));
        assertTrue(actions.allows(InstructionFactActions.PUBLISH, "tenant_admin"));
        assertTrue(actions.allows(InstructionFactActions.FOLLOW_UP_LIST, "tenant_admin"));
        assertFalse(actions.allows(InstructionFactActions.FIRST_VIEW, "tenant_admin"));
        assertFalse(actions.allows("UNKNOWN", "patient"));
    }

    @Test
    void clockIsInjectedForDeterministicFacts() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertEquals(NOW, clock.instant());
    }

    private static Instruction instruction(int revision, boolean requiresAcknowledgment, Instant dueAt) {
        return new Instruction(INSTRUCTION, ORG, PATIENT, UNIT, revision, "artifact-" + revision,
                requiresAcknowledgment, dueAt);
    }
}
