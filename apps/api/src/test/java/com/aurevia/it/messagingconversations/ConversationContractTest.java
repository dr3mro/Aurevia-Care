package com.aurevia.it.messagingconversations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.conversations.ConversationActions;
import com.aurevia.messaging.conversations.ConversationContract;
import com.aurevia.messaging.conversations.ConversationContract.Inbox;
import com.aurevia.messaging.conversations.ConversationContract.InboxEntry;
import com.aurevia.messaging.conversations.ConversationContract.SenderKind;
import com.aurevia.messaging.conversations.ConversationContract.State;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.conversations.ConversationContract.View;
import com.aurevia.messaging.conversations.ConversationContract.WorkEventType;
import com.aurevia.messaging.conversations.ConversationContract.WorkQueue;
import com.aurevia.messaging.conversations.ConversationInputs;

/**
 * Fast, database-free acceptance for the pure MSG-02 contract: lifecycle state,
 * unit and sender vocabularies, bounded inputs, the action/unit matrix and
 * immutable record semantics. Tagged intentionally without {@code integration}
 * so this suite runs in the DB-less swarm fast gate; the HTTP and PostgreSQL
 * behaviour is covered by {@link ConversationFlowTest}.
 */
class ConversationContractTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void stateWireAndParseRoundTrip() {
        for (State state : State.values()) {
            assertEquals(state, State.from(state.wire()));
        }
        assertEquals("awaiting_staff", State.AWAITING_STAFF.wire());
        assertEquals("awaiting_patient", State.AWAITING_PATIENT.wire());
        assertEquals("resolved", State.RESOLVED.wire());
        assertEquals("closed", State.CLOSED.wire());
        assertThrows(IllegalArgumentException.class, () -> State.from("bogus"));
    }

    @Test
    void stateAllowsEveryForwardTransitionButLeavingClosed() {
        Map<State, Map<State, Boolean>> expected = Map.of(
                State.AWAITING_STAFF, Map.of(
                        State.AWAITING_STAFF, true, State.AWAITING_PATIENT, true,
                        State.RESOLVED, true, State.CLOSED, true),
                State.AWAITING_PATIENT, Map.of(
                        State.AWAITING_STAFF, true, State.AWAITING_PATIENT, true,
                        State.RESOLVED, true, State.CLOSED, true),
                State.RESOLVED, Map.of(
                        State.AWAITING_STAFF, true, State.AWAITING_PATIENT, true,
                        State.RESOLVED, true, State.CLOSED, true),
                State.CLOSED, Map.of(
                        State.AWAITING_STAFF, false, State.AWAITING_PATIENT, false,
                        State.RESOLVED, false, State.CLOSED, true));
        for (State from : State.values()) {
            for (State to : State.values()) {
                assertEquals(expected.get(from).get(to), from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test
    void unitKeyWireAndParseRoundTrip() {
        for (UnitKey unit : UnitKey.values()) {
            assertEquals(unit, UnitKey.from(unit.wire()));
        }
        assertEquals("clinic", UnitKey.CLINIC.wire());
        assertEquals("lab", UnitKey.LAB.wire());
        assertEquals("radiology", UnitKey.RADIOLOGY.wire());
        assertEquals("pt", UnitKey.PT.wire());
        assertEquals("pharmacy", UnitKey.PHARMACY.wire());
        assertThrows(IllegalArgumentException.class, () -> UnitKey.from("bogus"));
    }

    @Test
    void senderKindWireAndParseRoundTrip() {
        for (SenderKind kind : SenderKind.values()) {
            assertEquals(kind, SenderKind.from(kind.wire()));
        }
        assertEquals("patient", SenderKind.PATIENT.wire());
        assertEquals("staff", SenderKind.STAFF.wire());
        assertThrows(IllegalArgumentException.class, () -> SenderKind.from("bogus"));
    }

    @Test
    void workEventTypeWireAndParseRoundTrip() {
        for (WorkEventType type : WorkEventType.values()) {
            assertEquals(type, WorkEventType.from(type.wire()));
        }
        assertEquals("created", WorkEventType.CREATED.wire());
        assertEquals("reopened", WorkEventType.REOPENED.wire());
        assertThrows(IllegalArgumentException.class, () -> WorkEventType.from("bogus"));
    }

    @Test
    void uuidValidationIsBounded() {
        assertEquals(ID, ConversationInputs.uuid(ID, "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> ConversationInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void opaqueIdValidationIsBounded() {
        assertEquals("conn-123.A", ConversationInputs.opaqueId("conn-123.A", "connectionId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.opaqueId(null, "connectionId")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.opaqueId("bad id", "connectionId")).status());
        assertEquals("INVALID_CONNECTIONID", assertThrows(ApiException.class,
                () -> ConversationInputs.opaqueId("x".repeat(129), "connectionId")).code());
    }

    @Test
    void subjectValidationMatchesSyntheticShape() {
        assertEquals("patient-a", ConversationInputs.subject("patient-a", "patientSubject"));
        assertEquals("patient.one", ConversationInputs.subject("patient.one", "patientSubject"));
        assertEquals("patient_one", ConversationInputs.subject("patient_one", "patientSubject"));
        assertEquals("a".repeat(64), ConversationInputs.subject("a".repeat(64), "patientSubject"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.subject("مريض", "patientSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.subject(null, "patientSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.subject("A", "patientSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.subject("a".repeat(65), "patientSubject")).status());
    }

    @Test
    void bodyValidationTrimsAndBoundsLength() {
        assertEquals("hello", ConversationInputs.body("  hello  "));
        assertEquals(422, assertThrows(ApiException.class, () -> ConversationInputs.body(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ConversationInputs.body("   ")).status());
        assertEquals("INVALID_BODY", assertThrows(ApiException.class,
                () -> ConversationInputs.body("x".repeat(4001))).code());
    }

    @Test
    void reasonValidationIsRequiredAndBounded() {
        assertEquals("resolved", ConversationInputs.reason(" resolved "));
        assertEquals(422, assertThrows(ApiException.class, () -> ConversationInputs.reason(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ConversationInputs.reason(" ")).status());
        assertEquals("INVALID_REASON", assertThrows(ApiException.class,
                () -> ConversationInputs.reason("x".repeat(501))).code());
    }

    @Test
    void topicValidationNormalizesOptionalValue() {
        assertEquals(null, ConversationInputs.topic(null));
        assertEquals(null, ConversationInputs.topic("  "));
        assertEquals("follow up", ConversationInputs.topic(" follow up "));
        assertEquals("INVALID_TOPIC", assertThrows(ApiException.class,
                () -> ConversationInputs.topic("x".repeat(129))).code());
    }

    @Test
    void unitKeyValidationAcceptsApprovedUnitsOnly() {
        assertEquals(UnitKey.LAB, ConversationInputs.unitKey("lab"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> ConversationInputs.unitKey(null)).status());
        assertEquals("INVALID_UNITKEY", assertThrows(ApiException.class,
                () -> ConversationInputs.unitKey("bogus")).code());
    }

    @Test
    void newIdCreatesDistinctUuids() {
        String first = ConversationInputs.newId();
        assertNotNull(first);
        assertEquals(first, ConversationInputs.uuid(first, "id"));
        assertNotEquals(first, ConversationInputs.newId());
    }

    @Test
    void actionMatrixSeparatesPatientStaffAndAdminPermissions() {
        ConversationActions actions = new ConversationActions();
        String[] actionKeys = {
                ConversationActions.CONVERSATION_OPEN, ConversationActions.CONVERSATION_REPLY,
                ConversationActions.CONVERSATION_READ, ConversationActions.INBOX_READ,
                ConversationActions.WORK_QUEUE_READ, ConversationActions.WORK_ASSIGN,
                ConversationActions.WORK_TRANSFER, ConversationActions.WORK_RESOLVE,
                ConversationActions.WORK_CLOSE
        };
        String[] staffRoles = ConversationActions.STAFF_ROLES.toArray(String[]::new);
        for (String action : actionKeys) {
            assertTrue(actions.allows(action, "patient") == isPatientAction(action), action + " patient");
            for (String role : staffRoles) {
                assertTrue(actions.allows(action, role) == isStaffAction(action), action + " " + role);
            }
            assertEquals(ConversationActions.WORK_QUEUE_READ.equals(action),
                    actions.allows(action, "tenant_admin"), action + " admin");
            assertFalse(actions.allows(action, "privacy_auditor"));
        }
        assertFalse(actions.allows("UNKNOWN", "tenant_admin"));
        assertFalse(actions.allows(ConversationActions.CONVERSATION_READ, "unknown"));
    }

    private static boolean isPatientAction(String action) {
        return ConversationActions.CONVERSATION_OPEN.equals(action)
                || ConversationActions.CONVERSATION_REPLY.equals(action)
                || ConversationActions.CONVERSATION_READ.equals(action)
                || ConversationActions.INBOX_READ.equals(action);
    }

    private static boolean isStaffAction(String action) {
        return !ConversationActions.INBOX_READ.equals(action);
    }

    @Test
    void tenantAdminHasOnlyWorkQueueOversightPermission() {
        ConversationActions actions = new ConversationActions();
        for (String action : List.of(ConversationActions.CONVERSATION_OPEN,
                ConversationActions.CONVERSATION_REPLY, ConversationActions.CONVERSATION_READ,
                ConversationActions.INBOX_READ, ConversationActions.WORK_ASSIGN,
                ConversationActions.WORK_TRANSFER, ConversationActions.WORK_RESOLVE,
                ConversationActions.WORK_CLOSE)) {
            assertFalse(actions.allows(action, "tenant_admin"), action);
        }
        assertTrue(actions.allows(ConversationActions.WORK_QUEUE_READ, "tenant_admin"));
    }

    @Test
    void unitCapabilityMatrixSeparatesStaffUnits() {
        ConversationActions actions = new ConversationActions();
        Map<UnitKey, String> eligible = Map.of(
                UnitKey.CLINIC, "reception",
                UnitKey.LAB, "lab_verifier",
                UnitKey.RADIOLOGY, "radiology_staff",
                UnitKey.PT, "pt_clinician",
                UnitKey.PHARMACY, "pharmacy_staff");
        for (UnitKey unit : UnitKey.values()) {
            for (Map.Entry<UnitKey, String> entry : eligible.entrySet()) {
                assertEquals(unit == entry.getKey(), actions.allowsUnit(unit, entry.getValue()),
                        unit + " / " + entry.getValue());
            }
            assertTrue(actions.allowsUnit(unit, "tenant_admin"));
            assertTrue(actions.allowsUnit(unit, "privacy_auditor"));
            assertFalse(actions.allowsUnit(unit, "patient"));
            assertFalse(actions.allowsUnit(unit, "unknown"));
        }
    }

    @Test
    void recordsKeepValueSemanticsAndDefensiveCopies() {
        var conversation = new ConversationContract.Conversation(ID, 1, ID, "patient-a", "lab",
                "conn-1", "topic", State.AWAITING_STAFF, null, 0, 0, Instant.parse("2026-11-19T10:00:00Z"));
        var message = new ConversationContract.Message(ID, ID, 1, SenderKind.PATIENT,
                "patient-a", "patient", "hello", Instant.parse("2026-11-19T10:00:00Z"));
        var event = new ConversationContract.WorkItemEvent(ID, ID, 1, WorkEventType.CREATED,
                "patient-a", null, "awaiting_staff", "opened", Instant.parse("2026-11-19T10:00:00Z"));
        assertEquals(conversation, new ConversationContract.Conversation(ID, 1, ID, "patient-a", "lab",
                "conn-1", "topic", State.AWAITING_STAFF, null, 0, 0, Instant.parse("2026-11-19T10:00:00Z")));
        assertEquals(message, new ConversationContract.Message(ID, ID, 1, SenderKind.PATIENT,
                "patient-a", "patient", "hello", Instant.parse("2026-11-19T10:00:00Z")));
        assertEquals(event, new ConversationContract.WorkItemEvent(ID, ID, 1, WorkEventType.CREATED,
                "patient-a", null, "awaiting_staff", "opened", Instant.parse("2026-11-19T10:00:00Z")));
        assertNotNull(conversation.toString());
        assertNotNull(message.toString());
        assertNotNull(event.toString());

        List<ConversationContract.Message> messages = new ArrayList<>();
        messages.add(message);
        List<ConversationContract.WorkItemEvent> events = new ArrayList<>();
        events.add(event);
        View view = new View(conversation, messages, event == null ? List.of() : events);
        messages.clear();
        events.clear();
        assertEquals(1, view.messages().size());
        assertEquals(1, view.events().size());
        assertThrows(UnsupportedOperationException.class, () -> view.messages().clear());

        List<InboxEntry> inboxEntries = new ArrayList<>();
        inboxEntries.add(new InboxEntry(conversation, 2));
        Inbox inbox = new Inbox(inboxEntries);
        inboxEntries.clear();
        assertEquals(1, inbox.entries().size());
        assertEquals(2, inbox.entries().get(0).unreadCount());
        assertThrows(UnsupportedOperationException.class, () -> inbox.entries().clear());

        List<ConversationContract.Conversation> queueEntries = new ArrayList<>();
        queueEntries.add(conversation);
        WorkQueue queue = new WorkQueue(queueEntries);
        queueEntries.clear();
        assertEquals(1, queue.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> queue.entries().clear());
    }
}
