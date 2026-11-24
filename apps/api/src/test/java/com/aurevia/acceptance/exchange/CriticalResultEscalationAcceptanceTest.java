package com.aurevia.acceptance.exchange;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.exchange.critical.CriticalActions;
import com.aurevia.exchange.critical.CriticalContract.AcknowledgeRequest;
import com.aurevia.exchange.critical.CriticalContract.AssignRequest;
import com.aurevia.exchange.critical.CriticalContract.EscalateRequest;
import com.aurevia.exchange.critical.CriticalContract.EventType;
import com.aurevia.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.exchange.critical.CriticalContract.ReleaseRequest;
import com.aurevia.exchange.critical.CriticalContract.Result;
import com.aurevia.exchange.critical.CriticalContract.State;
import com.aurevia.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.exchange.critical.CriticalResultRepository;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G2 acceptance for critical-result escalation and acknowledgment
 * (XCH-05, XCH-04, G2).
 *
 * <p>When the acknowledgment deadline passes, escalation switches to the
 * site-approved backup channel and a delivered attempt becomes an immutable
 * fact; delivery and patient release never satisfy a clinician acknowledgment,
 * which binds to the exact revision the human read. Escalation before the
 * deadline is refused with the primary channel requirement. All identifiers and
 * notes are synthetic; no patient data or credential is used.</p>
 */
class CriticalResultEscalationAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "tenantadmin-g2";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String OWNER = "clinician-g2";
    private static final String BACKUP = "backup-g2";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP_CHANNEL = "ONCALL_PHONE";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant DUE = NOW.plusSeconds(3600);

    private CriticalResultRepository repository;
    private AuditAppender audit;
    private CriticalResultService service;
    private CriticalResultService lateService;
    private AtomicReference<Result> current;

    @BeforeEach
    void setUp() {
        repository = mock(CriticalResultRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new CriticalResultService(repository, new CriticalActions(), transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lateService = new CriticalResultService(repository, new CriticalActions(), transactions, audit,
                Clock.fixed(NOW.plusSeconds(7200), ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "CRITICAL_RESULT", "corr-g2-critical"));

        current = new AtomicReference<>();
        when(repository.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        when(repository.loadForUpdate(anyString())).thenAnswer(
                invocation -> Optional.ofNullable(current.get()));
        when(repository.patientExists(anyString())).thenReturn(true);
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(
                List.of("lab_verifier", "clinician", "tenant_admin"));
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
                    invocation.getArgument(2), invocation.getArgument(3), null, null));
            return 1;
        }).when(repository).assign(anyString(), anyInt(), anyString(), any(), anyString(), anyString(),
                anyString());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 3, State.AWAITING_ACK, OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).verify(anyString(), anyInt(), anyString(), any());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 4, State.ACKNOWLEDGED, OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).acknowledge(anyString(), anyInt());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void criticalResultEscalatesAtItsDeadlineViaTheApprovedBackupChannel() {
        // XCH-05 / G2: before the deadline the primary channel is required; once
        // overdue the approved backup channel and recipient are mandatory.
        String id = driveToAwaitingAck();

        ApiException tooEarly = assertThrows(ApiException.class, () -> service.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", false), 3));
        assertEquals(422, tooEarly.status());
        assertEquals("PRIMARY_CHANNEL_REQUIRED", tooEarly.code());

        ApiException wrongOverdueChannel = assertThrows(ApiException.class, () -> lateService.escalate(id,
                new EscalateRequest(PRIMARY, OWNER, "delivered", "synthetic", false), 3));
        assertEquals(422, wrongOverdueChannel.status());
        assertEquals("BACKUP_CHANNEL_REQUIRED", wrongOverdueChannel.code());

        Result attempt = lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic backup delivery", false), 3);
        assertEquals(State.AWAITING_ACK, attempt.state(), "an attempt is a fact, not a state change");
        assertEquals(3, attempt.version());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("delivered"), anyString());
        audited("CRITICAL_ESCALATE");
    }

    @Test
    void humanAcknowledgmentIsRecordedSeparatelyFromDeliveryAndRelease() {
        // XCH-05 / G2: a delivered escalation and a patient release never
        // acknowledge; only an authorized clinician's acknowledgment does, bound
        // to the exact revision the human handled.
        String id = driveToAwaitingAck();
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic backup delivery", false), 3);

        Result released = service.release(id, new ReleaseRequest("synthetic patient release"), 3);
        assertEquals(State.AWAITING_ACK, released.state(), "patient release never acknowledges");
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.PATIENT_RELEASED),
                any(), any(), eq("released"), anyString());

        Result acknowledged = service.acknowledge(id,
                new AcknowledgeRequest("synthetic clinician acknowledgment"), 3);
        assertEquals(State.ACKNOWLEDGED, acknowledged.state());
        assertEquals(4, acknowledged.version());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ACKNOWLEDGED), eq(OWNER),
                any(), eq("acknowledged"), anyString());
        audited("CRITICAL_ACKNOWLEDGE");
    }

    private String driveToAwaitingAck() {
        Result registered = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false));
        assertEquals(State.UNVERIFIED, registered.state());
        String id = registered.id();
        service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1);
        Result verified = service.verify(id, new VerifyRequest("synthetic verification"), 2);
        assertEquals(State.AWAITING_ACK, verified.state());
        return id;
    }

    private static Result result(String id, int version, State state, String owner, Instant due,
                                 String verifiedBy, Instant verifiedAt) {
        return new Result(id, version, PATIENT, "LAB", "OBS-1", "ORD-1", "ACC-1",
                "synthetic critical finding", "7.2", "mmol/L", false, state,
                verifiedBy, verifiedAt, owner, due, PRIMARY, BACKUP_CHANNEL, BACKUP, NOW);
    }

    private void audited(String action) {
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq(action), eq(action),
                eq("CRITICAL_RESULT"), eq("CRITICAL_RESULT"), anyString(), eq("SUCCESS"));
    }
}
