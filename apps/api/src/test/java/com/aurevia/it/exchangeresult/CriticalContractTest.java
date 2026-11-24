package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.critical.CriticalActions;
import com.aurevia.exchange.critical.CriticalContract;
import com.aurevia.exchange.critical.CriticalInputs;

/**
 * Fast, database-free acceptance for the pure XCH-05 contract: state machine,
 * event vocabulary, bounded inputs and the action/role matrix. Tagged
 * intentionally without {@code integration} so this suite runs in the DB-less
 * swarm fast gate; the HTTP and PostgreSQL behaviour is covered by
 * {@link CriticalResultFlowTest}.
 */
class CriticalContractTest {

    @Test
    void stateWireAndParseRoundTrip() {
        for (CriticalContract.State state : CriticalContract.State.values()) {
            assertEquals(state, CriticalContract.State.from(state.wire()));
        }
        assertEquals("unverified", CriticalContract.State.UNVERIFIED.wire());
        assertEquals("awaiting_ack", CriticalContract.State.AWAITING_ACK.wire());
        assertEquals("acknowledged", CriticalContract.State.ACKNOWLEDGED.wire());
        assertEquals("closed", CriticalContract.State.CLOSED.wire());
        assertThrows(IllegalArgumentException.class, () -> CriticalContract.State.from("bogus"));
    }

    @Test
    void stateAllowsOnlyTheSafetyTransitionsAndNoOp() {
        Map<CriticalContract.State, Map<CriticalContract.State, Boolean>> expected = Map.of(
                CriticalContract.State.UNVERIFIED,
                Map.of(CriticalContract.State.UNVERIFIED, true,
                        CriticalContract.State.AWAITING_ACK, true,
                        CriticalContract.State.ACKNOWLEDGED, false,
                        CriticalContract.State.CLOSED, false),
                CriticalContract.State.AWAITING_ACK,
                Map.of(CriticalContract.State.UNVERIFIED, false,
                        CriticalContract.State.AWAITING_ACK, true,
                        CriticalContract.State.ACKNOWLEDGED, true,
                        CriticalContract.State.CLOSED, false),
                CriticalContract.State.ACKNOWLEDGED,
                Map.of(CriticalContract.State.UNVERIFIED, false,
                        CriticalContract.State.AWAITING_ACK, false,
                        CriticalContract.State.ACKNOWLEDGED, true,
                        CriticalContract.State.CLOSED, true),
                CriticalContract.State.CLOSED,
                Map.of(CriticalContract.State.UNVERIFIED, false,
                        CriticalContract.State.AWAITING_ACK, false,
                        CriticalContract.State.ACKNOWLEDGED, false,
                        CriticalContract.State.CLOSED, true));
        for (CriticalContract.State from : CriticalContract.State.values()) {
            for (CriticalContract.State to : CriticalContract.State.values()) {
                assertEquals(expected.get(from).get(to), from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test
    void eventTypeWireAndParseRoundTrip() {
        for (CriticalContract.EventType type : CriticalContract.EventType.values()) {
            assertEquals(type, CriticalContract.EventType.from(type.wire()));
        }
        assertEquals("escalation_attempt", CriticalContract.EventType.ESCALATION_ATTEMPT.wire());
        assertThrows(IllegalArgumentException.class, () -> CriticalContract.EventType.from("bogus"));
    }

    @Test
    void uuidValidationIsBounded() {
        String valid = "11111111-1111-1111-1111-111111111111";
        assertEquals(valid, CriticalInputs.uuid(valid, "patientId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.uuid(null, "patientId")).status());
        assertEquals("INVALID_PATIENTID", assertThrows(ApiException.class,
                () -> CriticalInputs.uuid("nope", "patientId")).code());
    }

    @Test
    void instantValidationIsBounded() {
        String valid = "2026-11-19T10:00:00Z";
        assertEquals(Instant.parse(valid), CriticalInputs.instant(valid, "dueAt"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.instant(null, "dueAt")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.instant("tomorrow", "dueAt")).status());
    }

    @Test
    void textValidationTrimsAndBoundsLength() {
        assertEquals("critical potassium", CriticalInputs.text("  critical potassium  ", "summary", 200));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.text(null, "summary", 200)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.text("   ", "summary", 200)).status());
        assertEquals("INVALID_SUMMARY", assertThrows(ApiException.class,
                () -> CriticalInputs.text("x".repeat(201), "summary", 200)).code());
    }

    @Test
    void subjectValidationMatchesSyntheticSubjectShape() {
        assertEquals("clinician-a", CriticalInputs.subject("clinician-a", "ownerSubject"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.subject(null, "ownerSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.subject("A", "ownerSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.subject("Bad_Case", "ownerSubject")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.subject("a".repeat(65), "ownerSubject")).status());
    }

    @Test
    void channelValidationMatchesApprovedChannelShape() {
        assertEquals("ONCALL_PHONE", CriticalInputs.channel("ONCALL_PHONE", "channel"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.channel(null, "channel")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.channel("oncall", "channel")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.channel("A".repeat(33), "channel")).status());
    }

    @Test
    void outcomeIsDeliveredOrFailed() {
        assertEquals("delivered", CriticalInputs.outcome("delivered"));
        assertEquals("failed", CriticalInputs.outcome("failed"));
        assertEquals(422, assertThrows(ApiException.class, () -> CriticalInputs.outcome(null)).status());
        assertEquals("INVALID_OUTCOME", assertThrows(ApiException.class,
                () -> CriticalInputs.outcome("sent")).code());
    }

    @Test
    void flagAndNoteHandleAbsentValues() {
        assertFalse(CriticalInputs.flag(null));
        assertTrue(CriticalInputs.flag(true));
        assertFalse(CriticalInputs.flag(false));
        assertEquals(null, CriticalInputs.note(null, 500));
        assertEquals(null, CriticalInputs.note("   ", 500), "a blank note is absent");
        assertEquals("reviewed", CriticalInputs.note(" reviewed ", 500));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.note("x".repeat(501), 500)).status());
    }

    @Test
    void dueAtMustBeInTheFuture() {
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        assertEquals(Instant.parse("2026-11-19T11:00:00Z"),
                CriticalInputs.dueAt("2026-11-19T11:00:00Z", now));
        assertEquals(422, assertThrows(ApiException.class,
                () -> CriticalInputs.dueAt("2026-11-19T09:00:00Z", now)).status());
        ApiException notFuture = assertThrows(ApiException.class,
                () -> CriticalInputs.dueAt("2026-11-19T10:00:00Z", now));
        assertEquals(422, notFuture.status());
        assertEquals("INVALID_DUE_AT", notFuture.code());
    }

    @Test
    void newIdIsOpaque() {
        String id = CriticalInputs.newId();
        assertNotNull(id);
        assertTrue(java.util.UUID.fromString(id).toString().equals(id));
    }

    @Test
    void actionMatrixSeparatesLabClinicalAndAdministrativeDuties() {
        CriticalActions actions = new CriticalActions();
        Map<String, List<String>> allowed = Map.of(
                CriticalActions.REGISTER, List.of("lab_verifier"),
                CriticalActions.VERIFY, List.of("lab_verifier"),
                CriticalActions.ASSIGN, List.of("clinician", "tenant_admin"),
                CriticalActions.ESCALATE, List.of("clinician", "tenant_admin"),
                CriticalActions.READ_BACK, List.of("clinician"),
                CriticalActions.ACKNOWLEDGE, List.of("clinician"),
                CriticalActions.FOLLOW_UP, List.of("clinician"),
                CriticalActions.RELEASE, List.of("tenant_admin"),
                CriticalActions.READ, List.of("clinician", "tenant_admin", "privacy_auditor"));
        List<String> roles = List.of("lab_verifier", "clinician", "tenant_admin",
                "privacy_auditor", "reception");
        for (Map.Entry<String, List<String>> entry : allowed.entrySet()) {
            for (String role : roles) {
                assertEquals(entry.getValue().contains(role), actions.allows(entry.getKey(), role),
                        entry.getKey() + " / " + role);
            }
        }
        assertFalse(actions.allows("UNKNOWN_ACTION", "clinician"));
        assertFalse(actions.allows(CriticalActions.ACKNOWLEDGE, "reception"));
    }

    @Test
    void recordValueSemanticsAreStable() {
        CriticalContract.Result result = result();
        CriticalContract.Event event = event();
        assertEquals(result, result());
        assertEquals(event, event());
        assertFalse(result.equals(event));
        assertEquals(result.hashCode(), result().hashCode());
        assertEquals(event.hashCode(), event().hashCode());
        assertNotNull(result.toString());
        assertNotNull(event.toString());
        CriticalContract.View view = new CriticalContract.View(result, List.of(event));
        CriticalContract.Report report = new CriticalContract.Report(List.of(result), List.of(event), List.of());
        assertNotNull(view.toString());
        assertNotNull(report.toString());
        assertEquals(view, new CriticalContract.View(result, List.of(event)));
        assertEquals(report, new CriticalContract.Report(List.of(result), List.of(event), List.of()));
    }

    @Test
    void listBearingRecordsDefensivelyCopy() {
        List<CriticalContract.Event> events = new ArrayList<>();
        events.add(event());
        CriticalContract.View view = new CriticalContract.View(null, events);
        events.clear();
        assertEquals(1, view.events().size());

        List<CriticalContract.Result> overdue = new ArrayList<>();
        overdue.add(result());
        List<CriticalContract.Event> gaps = new ArrayList<>();
        gaps.add(event());
        CriticalContract.Report report = new CriticalContract.Report(overdue, gaps, gaps);
        overdue.clear();
        gaps.clear();
        assertEquals(1, report.overdue().size());
        assertEquals(1, report.rosterGaps().size());
        assertEquals(1, report.failedNotifications().size());
    }

    private static CriticalContract.Result result() {
        return new CriticalContract.Result("id", 1, "patient", "LAB", "SRC", null, null,
                "summary", "7.2", "mmol/L", true, CriticalContract.State.UNVERIFIED,
                null, null, null, null, "PRIMARY", "BACKUP", "backup-a", null);
    }

    private static CriticalContract.Event event() {
        return new CriticalContract.Event("event-id", "id", 1,
                CriticalContract.EventType.REGISTERED, "actor", null, null, null, null, null);
    }
}
