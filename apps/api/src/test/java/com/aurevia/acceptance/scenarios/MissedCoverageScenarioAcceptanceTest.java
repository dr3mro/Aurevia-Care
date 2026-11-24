package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.aurevia.exchange.critical.CriticalContract.Result;
import com.aurevia.exchange.critical.CriticalContract.State;
import com.aurevia.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.exchange.critical.CriticalResultRepository;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.tenant.TenantContext;

import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLINIC_ALPHA;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLOCK;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.NOW;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PARTNER_LAB;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.assertDenied;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.bind;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.clear;

/**
 * Section 11 cross-cutting scenario: missed coverage (XCH-05, XCH-04).
 *
 * <p>A critical result owned by a clinician at the first synthetic clinic is
 * read from the partner laboratory. When the acknowledgment deadline passes,
 * escalation routes to the site-approved staffed on-call backup. Each attempt
 * is an immutable fact that records the channel, the recipient, the delivery
 * receipt and the original deadline; no result is silently reassigned to an
 * unstaffed queue. Delivery and patient release never satisfy the human
 * acknowledgment. The mock repository holds only synthetic domain state.</p>
 */
class MissedCoverageScenarioAcceptanceTest {

    private static final String ACTOR = ScenarioSupport.LAB_VERIFIER;
    private static final String OWNER = "syn-owner-clinician";
    private static final String BACKUP = "syn-staffed-oncall";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP_CHANNEL = "ONCALL_PHONE";
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
        bind(CLINIC_ALPHA, ScenarioSupport.CLINICIAN);

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
            current.set(result(current.get().id(), 2, State.UNVERIFIED,
                    invocation.getArgument(2), invocation.getArgument(3), null, null));
            return 1;
        }).when(repository).assign(anyString(), anyInt(), anyString(), any(), anyString(), anyString(),
                anyString());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 3, State.AWAITING_ACK,
                    OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).verify(anyString(), anyInt(), anyString(), any());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 4, State.ACKNOWLEDGED,
                    OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).acknowledge(anyString(), anyInt());
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void overdueCriticalResultRoutesToStaffedBackupWithReceiptAndDeadline() {
        // XCH-05: before the deadline the primary channel is required and no
        // backup escalation is allowed, so coverage cannot silently fall through.
        String id = driveToAwaitingAck();
        Result awaiting = current.get();
        assertEquals(OWNER, awaiting.ownerSubject());
        assertEquals(DUE, awaiting.dueAt());

        assertDenied(422, "PRIMARY_CHANNEL_REQUIRED", () -> service.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", false), 3));
        assertDenied(422, "BACKUP_CHANNEL_REQUIRED", () -> lateService.escalate(id,
                new EscalateRequest(PRIMARY, OWNER, "delivered", "synthetic", false), 3));

        // Once overdue, the approved staffed backup is used. The attempt is a
        // recorded fact (state unchanged) carrying the receipt and the owner's
        // original deadline; the clinical owner remains accountable.
        Result attempt = lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic backup receipt", false), 3);
        assertEquals(State.AWAITING_ACK, attempt.state());
        assertEquals(3, attempt.version());
        assertEquals(OWNER, attempt.ownerSubject());
        assertEquals(DUE, attempt.dueAt());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("delivered"), anyString());
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq("CRITICAL_ESCALATE"),
                eq("CRITICAL_ESCALATE"), eq("CRITICAL_RESULT"), eq("CRITICAL_RESULT"),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void deliveryAndReleaseNeverSubstituteForTheHumanAcknowledgment() {
        // XCH-04: only the named clinician's acknowledgment closes the critical
        // result, bound to the exact revision they handled.
        String id = driveToAwaitingAck();
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic backup receipt", false), 3);

        Result released = service.release(id,
                new com.aurevia.exchange.critical.CriticalContract.ReleaseRequest(
                        "synthetic patient release"), 3);
        assertEquals(State.AWAITING_ACK, released.state());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.PATIENT_RELEASED),
                any(), any(), eq("released"), anyString());

        Result acknowledged = service.acknowledge(id,
                new AcknowledgeRequest("synthetic clinician acknowledgment"), 3);
        assertEquals(State.ACKNOWLEDGED, acknowledged.state());
        assertEquals(4, acknowledged.version());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ACKNOWLEDGED), eq(OWNER),
                any(), eq("acknowledged"), anyString());
    }

    private String driveToAwaitingAck() {
        Result registered = service.register(new RegisterRequest(PATIENT, PARTNER_LAB, "OBS-1", "ORD-1",
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
        return new Result(id, version, PATIENT, PARTNER_LAB, "OBS-1", "ORD-1", "ACC-1",
                "synthetic critical finding", "7.2", "mmol/L", false, state,
                verifiedBy, verifiedAt, owner, due, PRIMARY, BACKUP_CHANNEL, BACKUP, NOW);
    }
}
