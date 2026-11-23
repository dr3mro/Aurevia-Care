package com.aurevia.smartclinic.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.exchange.critical.CriticalActions;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.AcknowledgeRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.AssignRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.EscalateRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.EventType;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.Result;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.State;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalResultRepository;
import com.aurevia.smartclinic.exchange.critical.CriticalResultService;

/**
 * Cross-cutting acceptance for a critical result whose owner is unavailable.
 *
 * <p>Requirement IAM-01: only an active, authorized clinician may own or receive
 * a critical alert. Spec section 11 "Missed coverage": an overdue alert moves to
 * the approved, staffed backup recipient; each attempt is an immutable fact that
 * records the channel, receipt and original deadline; a primary-channel attempt
 * before the deadline and a silent reassignment to an unstaffed recipient are
 * both refused. All identifiers and notes are synthetic.</p>
 */
class MissedCoverageAcceptanceTest extends FirstSliceFixture {

    private static final String OWNER = "syn-owner-clinician";
    private static final String INACTIVE_OWNER = "syn-inactive-owner";
    private static final String BACKUP = "syn-staffed-backup";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP_CHANNEL = "ONCALL_PHONE";
    private static final Instant DUE = NOW.plusSeconds(3600);

    private CriticalResultRepository repository;
    private CriticalResultService service;
    private CriticalResultService lateService;
    private AtomicReference<Result> current;

    /** Compose the critical-result service twice: in hours and past the deadline. */
    @BeforeEach
    void setUpCriticalResult() {
        repository = mock(CriticalResultRepository.class);
        service = new CriticalResultService(repository, new CriticalActions(), transactions, audit, clock());
        lateService = new CriticalResultService(repository, new CriticalActions(), transactions, audit,
                clockAt(NOW.plusSeconds(7200)));
        bind(CLINIC_A, OWNER);

        current = new AtomicReference<>();
        when(repository.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        when(repository.loadForUpdate(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(current.get()));
        when(repository.patientExists(anyString())).thenReturn(true);
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of("lab_verifier", "clinician"));
        when(repository.approvedSetting("critical.escalation.primary_channel")).thenReturn(Optional.of(PRIMARY));
        when(repository.approvedSetting("critical.escalation.backup_channel"))
                .thenReturn(Optional.of(BACKUP_CHANNEL));
        when(repository.approvedSetting("critical.escalation.backup_recipient")).thenReturn(Optional.of(BACKUP));
        when(repository.hasDeliveredAttempt(anyString(), anyInt())).thenReturn(true);

        doAnswer(invocation -> {
            current.set(result(invocation.getArgument(0), 1, State.UNVERIFIED, null, null, null, null));
            return 1;
        }).when(repository).insert(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyString(), anyString(), anyBoolean());
        doAnswer(invocation -> {
            current.set(result(invocation.getArgument(0), 2, State.UNVERIFIED,
                    invocation.getArgument(2), DUE, null, null));
            return 1;
        }).when(repository).assign(anyString(), anyInt(), anyString(), any(), anyString(), anyString(),
                anyString());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 3, State.AWAITING_ACK, OWNER, DUE, OWNER, NOW));
            return 1;
        }).when(repository).verify(anyString(), anyInt(), anyString(), any());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 4, State.ACKNOWLEDGED, OWNER, DUE, OWNER, NOW));
            return 1;
        }).when(repository).acknowledge(anyString(), anyInt());
    }

    @Test
    void overdueResultUsesTheStaffedBackupAndRecordsEveryAttemptDeadlineAndReceipt() {
        // Spec 11 / IAM-01: the deadline is recorded at assignment, an in-hours
        // attempt must stay on the primary channel, and only an overdue attempt
        // on the approved backup channel is accepted as a fact.
        String id = driveToAwaitingAck();
        assertEquals(DUE, current.get().dueAt(), "the original deadline survives assignment and verification");

        ApiException tooEarly = assertThrows(ApiException.class, () -> service.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic early note", false), 3));
        assertEquals(422, tooEarly.status());
        assertEquals("PRIMARY_CHANNEL_REQUIRED", tooEarly.code());

        ApiException wrongOverdueChannel = assertThrows(ApiException.class, () -> lateService.escalate(id,
                new EscalateRequest(PRIMARY, OWNER, "delivered", "synthetic wrong channel", false), 3));
        assertEquals(422, wrongOverdueChannel.status());
        assertEquals("BACKUP_CHANNEL_REQUIRED", wrongOverdueChannel.code());

        Result attempt = lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic backup receipt", false), 3);
        assertEquals(State.AWAITING_ACK, attempt.state(), "an attempt is a fact, not an acknowledgment");
        assertEquals(3, attempt.version());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("delivered"), eq("synthetic backup receipt"));

        Result acknowledged = service.acknowledge(id,
                new AcknowledgeRequest("synthetic delivered-attempt acknowledgment"), 3);
        assertEquals(State.ACKNOWLEDGED, acknowledged.state());
        assertEquals(4, acknowledged.version());
        verify(audit, atLeastOnce()).append(any(com.aurevia.smartclinic.tenant.TenantContext.class),
                eq(CriticalActions.ESCALATE), eq(CriticalActions.ESCALATE), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
        verify(audit, atLeastOnce()).append(any(com.aurevia.smartclinic.tenant.TenantContext.class),
                eq(CriticalActions.ACKNOWLEDGE), eq(CriticalActions.ACKNOWLEDGE), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void unstaffedRecipientsAreRefusedAndNoResultIsSilentlyReassigned() {
        // Spec 11 / IAM-01: neither the owner nor the backup may be an inactive
        // clinician, and an overdue alert must never be handed to an unstaffed
        // queue. A roster gap is recorded as a failed fact while the caller sees
        // an error and the result stays in its previous state.
        register();
        when(repository.activeRoles(INACTIVE_OWNER)).thenReturn(List.of("lab_verifier"));
        ApiException inactiveOwner = assertThrows(ApiException.class,
                () -> service.assign(current.get().id(), new AssignRequest(INACTIVE_OWNER, DUE.toString()), 1));
        assertEquals(422, inactiveOwner.status());
        assertEquals("INVALID_OWNER", inactiveOwner.code());
        verify(repository, never()).assign(anyString(), anyInt(), anyString(), any(), anyString(),
                anyString(), anyString());

        when(repository.activeRoles(BACKUP)).thenReturn(List.of("reception"));
        ApiException rosterGap = assertThrows(ApiException.class,
                () -> service.assign(current.get().id(), new AssignRequest(OWNER, DUE.toString()), 1));
        assertEquals(422, rosterGap.status());
        assertEquals("BACKUP_ROSTER_GAP", rosterGap.code());
        verify(repository, never()).assign(anyString(), anyInt(), anyString(), any(), anyString(),
                anyString(), anyString());
        verify(repository).appendEvent(eq(current.get().id()), eq(1), eq(EventType.ROSTER_GAP), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("failed"), eq("no active backup clinician"));
        assertEquals(State.UNVERIFIED, current.get().state(), "no assignment survived the roster gap");

        when(repository.activeRoles(BACKUP)).thenReturn(List.of("clinician"));
        String id = driveToAwaitingAck();
        when(repository.activeRoles(BACKUP)).thenReturn(List.of("reception"));
        ApiException lateGap = assertThrows(ApiException.class, () -> lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic unstaffed", false), 3));
        assertEquals(409, lateGap.status());
        assertEquals("ROSTER_GAP", lateGap.code());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.ESCALATION_ATTEMPT),
                anyString(), anyString(), anyString(), anyString());

        when(repository.rosterGaps()).thenReturn(List.of(event(EventType.ROSTER_GAP, "failed")));
        when(repository.failedNotifications()).thenReturn(List.of(event(EventType.ROSTER_GAP, "failed")));
        when(repository.overdue(any())).thenReturn(List.of(current.get()));
        com.aurevia.smartclinic.exchange.critical.CriticalContract.Report report = service.report();
        assertEquals(1, report.overdue().size());
        assertEquals(1, report.rosterGaps().size());
        assertEquals(1, report.failedNotifications().size());
    }

    private String driveToAwaitingAck() {
        String id = register();
        service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1);
        Result verified = service.verify(id, new VerifyRequest("synthetic verification"), 2);
        assertEquals(State.AWAITING_ACK, verified.state());
        return id;
    }

    private String register() {
        Result registered = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false));
        assertEquals(State.UNVERIFIED, registered.state());
        return registered.id();
    }

    private static com.aurevia.smartclinic.exchange.critical.CriticalContract.Event event(
            EventType type, String outcome) {
        return new com.aurevia.smartclinic.exchange.critical.CriticalContract.Event(
                "event-1", "result-1", 1, type, BACKUP, BACKUP, BACKUP_CHANNEL, outcome, "synthetic", NOW);
    }

    private static Result result(String id, int version, State state, String owner, Instant due,
                                 String verifiedBy, Instant verifiedAt) {
        return new Result(id, version, PATIENT, "LAB", "OBS-1", "ORD-1", "ACC-1",
                "synthetic critical finding", "7.2", "mmol/L", false, state, verifiedBy, verifiedAt,
                owner, due, PRIMARY, BACKUP_CHANNEL, BACKUP, NOW);
    }
}
