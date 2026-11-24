package com.aurevia.messaging.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.FactType;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.State;

/**
 * Fast, database-free acceptance for the pure MSG-04 vocabulary: typed
 * instruction kinds, context links, revision states, fact types, bounded
 * inputs and the action/role matrix. This suite is deliberately not tagged
 * {@code integration} so it runs in the DB-less swarm fast gate; the HTTP and
 * service behaviour is covered by {@code InstructionServiceTest}.
 */
class InstructionContractTest {

    @Test
    void kindWireAndParseRoundTrip() {
        for (Kind kind : Kind.values()) {
            assertEquals(kind, Kind.from(kind.wire()));
        }
        assertEquals("imaging_preparation", Kind.IMAGING_PREPARATION.wire());
        assertEquals("general", Kind.GENERAL.wire());
        assertThrows(IllegalArgumentException.class, () -> Kind.from("bogus"));
    }

    @Test
    void contextWireAndParseRoundTrip() {
        for (ContextType context : ContextType.values()) {
            assertEquals(context, ContextType.from(context.wire()));
        }
        assertEquals("appointment", ContextType.APPOINTMENT.wire());
        assertThrows(IllegalArgumentException.class, () -> ContextType.from("bogus"));
    }

    @Test
    void factWireAndParseRoundTrip() {
        for (FactType fact : FactType.values()) {
            assertEquals(fact, FactType.from(fact.wire()));
        }
        assertEquals("withdrawn", FactType.WITHDRAWN.wire());
        assertThrows(IllegalArgumentException.class, () -> FactType.from("bogus"));
    }

    @Test
    void stateWireAndParseRoundTrip() {
        for (State state : State.values()) {
            assertEquals(state, State.from(state.wire()));
        }
        assertEquals("draft", State.DRAFT.wire());
        assertEquals("sent", State.SENT.wire());
        assertEquals("superseded", State.SUPERSEDED.wire());
        assertEquals("withdrawn", State.WITHDRAWN.wire());
        assertThrows(IllegalArgumentException.class, () -> State.from("bogus"));
    }

    @Test
    void revisionStateAllowsOnlyTheSafetyTransitions() {
        for (State state : State.values()) {
            assertTrue(state.canTransitionTo(state), state + " is a no-op");
        }
        assertTrue(State.SENT.canTransitionTo(State.SUPERSEDED));
        assertTrue(State.SENT.canTransitionTo(State.WITHDRAWN));
        assertFalse(State.SENT.canTransitionTo(State.DRAFT));
        assertFalse(State.SUPERSEDED.canTransitionTo(State.SENT));
        assertFalse(State.WITHDRAWN.canTransitionTo(State.SENT));
        assertFalse(State.DRAFT.canTransitionTo(State.SUPERSEDED));
    }

    @Test
    void uuidValidationIsBounded() {
        String valid = "11111111-1111-1111-1111-111111111111";
        assertEquals(valid, InstructionInputs.uuid(valid, "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> InstructionInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void textValidationTrimsAndBoundsLength() {
        assertEquals("please fast", InstructionInputs.text("  please fast  ", "text", 200));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.text(null, "text", 200)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.text("   ", "text", 200)).status());
        assertEquals("INVALID_TEXT", assertThrows(ApiException.class,
                () -> InstructionInputs.text("x".repeat(201), "text", 200)).code());
    }

    @Test
    void keyValidationIsLowercaseAndBounded() {
        assertEquals("collection-v1", InstructionInputs.key("collection-v1", "unitId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.key(null, "unitId")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.key("Bad_Key", "unitId")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.key("a".repeat(65), "unitId")).status());
    }

    @Test
    void languageDefaultsToEnglishAndIsBounded() {
        assertEquals("en", InstructionInputs.language(null));
        assertEquals("ar", InstructionInputs.language("ar"));
        assertEquals("en-US", InstructionInputs.language("en-US"));
        assertEquals("en", InstructionInputs.language("   "));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.language("english")).status());
    }

    @Test
    void instantAndDueAtValidation() {
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"),
                InstructionInputs.instant("2026-11-20T10:00:00Z", "issuedAt"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.instant("tomorrow", "issuedAt")).status());
        assertNull(InstructionInputs.dueAt(null, now));
        assertEquals(Instant.parse("2026-11-21T10:00:00Z"),
                InstructionInputs.dueAt("2026-11-21T10:00:00Z", now));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.dueAt("2026-11-18T10:00:00Z", now)).status());
    }

    @Test
    void attachmentListIsBoundedAndImmutable() {
        List<String> empty = InstructionInputs.attachments(null);
        assertTrue(empty.isEmpty());
        List<String> values = InstructionInputs.attachments(List.of("doc-a", "img-b"));
        assertEquals(List.of("doc-a", "img-b"), values);
        assertThrows(UnsupportedOperationException.class, () -> values.add("doc-c"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.attachments(List.of("Bad_Key"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> InstructionInputs.attachments(List.of("a", "b", "c", "d"))).status());
    }

    @Test
    void identifiersAreOpaqueUniqueStrings() {
        String first = InstructionInputs.newId();
        String second = InstructionInputs.newId();
        assertFalse(first.isBlank());
        assertFalse(first.equals(second));
        assertEquals(36, first.length());
    }

    @Test
    void actionMatrixSeparatesAdministrationClinicalAndRead() {
        InstructionActions actions = new InstructionActions();
        assertTrue(actions.allows(InstructionActions.DEFINE_TEMPLATE, "tenant_admin"));
        assertFalse(actions.allows(InstructionActions.DEFINE_TEMPLATE, "clinician"));
        assertTrue(actions.allows(InstructionActions.SEND, "clinician"));
        assertTrue(actions.allows(InstructionActions.SEND, "lab_collector"));
        assertFalse(actions.allows(InstructionActions.SEND, "reception"));
        assertTrue(actions.allows(InstructionActions.CLINICAL_SEND, "clinician"));
        assertFalse(actions.allows(InstructionActions.CLINICAL_SEND, "clinical_assistant"));
        assertTrue(actions.allows(InstructionActions.WITHDRAW, "pt_clinician"));
        assertTrue(actions.allows(InstructionActions.READ, "privacy_auditor"));
        assertTrue(actions.allows(InstructionActions.READ, "tenant_admin"));
        // A verified patient may read its own instruction content (MSG-04/05, PAT-02).
        assertTrue(actions.allows(InstructionActions.READ, "patient"));
        assertTrue(actions.allows(InstructionActions.SEND, "clinical_assistant"));
        assertTrue(actions.allows(InstructionActions.SEND, "lab_collector"));
        assertTrue(actions.allows(InstructionActions.SEND, "radiology_staff"));
        assertTrue(actions.allows(InstructionActions.SEND, "pt_clinician"));
        assertTrue(actions.allows(InstructionActions.SEND, "pharmacy_staff"));
        assertFalse(actions.allows(InstructionActions.READ, "finance"));
        assertFalse(actions.allows(InstructionActions.CLINICAL_SEND, "lab_collector"));
        assertFalse(actions.allows("UNKNOWN", "clinician"));
    }
}
