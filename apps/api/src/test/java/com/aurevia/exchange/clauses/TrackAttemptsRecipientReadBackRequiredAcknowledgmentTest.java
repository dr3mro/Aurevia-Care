package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
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
import com.aurevia.exchange.critical.CriticalContract.FollowUpRequest;
import com.aurevia.exchange.critical.CriticalContract.ReadBackRequest;
import com.aurevia.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.exchange.critical.CriticalContract.ReleaseRequest;
import com.aurevia.exchange.critical.CriticalContract.Result;
import com.aurevia.exchange.critical.CriticalContract.State;
import com.aurevia.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.exchange.critical.CriticalResultRepository;
import com.aurevia.exchange.critical.CriticalResultService;
import com.aurevia.tenant.TenantContext;

/**
 * XCH-05 clause 2 (API test): attempts, recipient, required read-back,
 * acknowledgment and follow-up are recorded as separate immutable events.
 *
 * <p>Every case enters through a command on
 * {@link com.aurevia.exchange.critical.CriticalResultService}, the
 * domain service the critical-result controller injects. The fixture follows the
 * established clause style: a mocked repository and audit appender, a
 * transaction template that runs the real guarded callback, a fixed clock and a
 * synthetic tenant context. Each fact is asserted on its own append call so a
 * merged or duplicated event cannot satisfy the clause. Recipients, channels and
 * outcomes are checked exactly; the actor and tenant are checked on the success
 * audit row because the append-only ledger derives the actor from the tenant
 * context in the database. All identifiers and notes are synthetic; no patient
 * data or credential is used.</p>
 */
class TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "tenantadmin-clause2";
    private static final String CORRELATION = "corr-xch05-clause2";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String OWNER = "clinician-clause2";
    private static final String BACKUP = "backup-clause2";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP_CHANNEL = "ONCALL_PHONE";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Instant DUE = NOW.plusSeconds(3600);

    private CriticalResultRepository repository;
    private AuditAppender audit;
    private CriticalResultService service;
    private CriticalResultService lateService;
    private AtomicReference<Result> current;
    private AtomicBoolean readBackRecorded;

    /** Build the service with the tenant context and fixed clocks the clause needs. */
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
        TenantContext.set(new TenantContext(ORG, ACTOR, "CRITICAL_RESULT", CORRELATION));

        current = new AtomicReference<>();
        readBackRecorded = new AtomicBoolean(false);
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
        // A delivered attempt is a precondition for read-back and acknowledgment;
        // individual tests override it to prove the refusal boundary.
        when(repository.hasDeliveredAttempt(anyString(), anyInt())).thenReturn(true);
        // Reflect the ledger: once a READ_BACK fact is appended, hasEvent sees it.
        when(repository.hasEvent(anyString(), eq(EventType.READ_BACK)))
                .thenAnswer(invocation -> readBackRecorded.get());

        doAnswer(invocation -> {
            boolean required = invocation.getArgument(9, Boolean.class);
            current.set(result(invocation.getArgument(0), 1, State.UNVERIFIED, required, null, null, null, null));
            return 1;
        }).when(repository).insert(anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyString(), anyString(), anyBoolean());
        doAnswer(invocation -> {
            current.set(result(invocation.getArgument(0), 2, State.UNVERIFIED,
                    current.get().readBackRequired(), invocation.getArgument(2), invocation.getArgument(3),
                    null, null));
            return 1;
        }).when(repository).assign(anyString(), anyInt(), anyString(), any(), anyString(), anyString(),
                anyString());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 3, State.AWAITING_ACK,
                    current.get().readBackRequired(), OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).verify(anyString(), anyInt(), anyString(), any());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 4, State.ACKNOWLEDGED,
                    current.get().readBackRequired(), OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).acknowledge(anyString(), anyInt());
        doAnswer(invocation -> {
            current.set(result(current.get().id(), 5, State.CLOSED,
                    current.get().readBackRequired(), OWNER, DUE, ACTOR, NOW));
            return 1;
        }).when(repository).close(anyString(), anyInt());
        doAnswer(invocation -> {
            if (invocation.getArgument(2) == EventType.READ_BACK) {
                readBackRecorded.set(true);
            }
            return null;
        }).when(repository).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Every fact is its own event: two attempts, a read-back, acknowledgment and follow-up. */
    @Test
    void eachCriticalFactIsAppendedAsItsOwnEvent() {
        String id = driveToAwaitingAck(true);

        Result firstAttempt = lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic first delivery", false), 3);
        assertEquals(State.AWAITING_ACK, firstAttempt.state(), "an attempt is a fact, not a state change");
        assertEquals(3, firstAttempt.version());
        Result secondAttempt = lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "failed", "synthetic second delivery", false), 3);
        assertEquals(State.AWAITING_ACK, secondAttempt.state(), "a second attempt is a separate fact");
        assertEquals(3, secondAttempt.version());

        Result readBack = service.readBack(id, new ReadBackRequest("7.2 mmol/L"), 3);
        assertEquals(State.AWAITING_ACK, readBack.state());

        Result acknowledged = service.acknowledge(id,
                new AcknowledgeRequest("synthetic clinician acknowledgment"), 3);
        assertEquals(State.ACKNOWLEDGED, acknowledged.state());
        assertEquals(4, acknowledged.version());

        Result closed = service.followUp(id, new FollowUpRequest("synthetic follow-up"), 4);
        assertEquals(State.CLOSED, closed.state());
        assertEquals(5, closed.version());

        verify(repository).appendEvent(eq(id), eq(1), eq(EventType.REGISTERED),
                isNull(), isNull(), isNull(), eq("synthetic critical finding"));
        verify(repository).appendEvent(eq(id), eq(2), eq(EventType.OWNER_ASSIGNED),
                eq(OWNER), eq(PRIMARY), eq("assigned"), anyString());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.VERIFIED),
                eq(OWNER), isNull(), eq("verified"), anyString());
        verify(repository, times(2)).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT),
                eq(BACKUP), eq(BACKUP_CHANNEL), anyString(), anyString());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT),
                eq(BACKUP), eq(BACKUP_CHANNEL), eq("delivered"), eq("synthetic first delivery"));
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT),
                eq(BACKUP), eq(BACKUP_CHANNEL), eq("failed"), eq("synthetic second delivery"));
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.READ_BACK),
                eq(OWNER), isNull(), eq("success"), eq("7.2 mmol/L"));
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ACKNOWLEDGED),
                eq(OWNER), isNull(), eq("acknowledged"), anyString());
        verify(repository).appendEvent(eq(id), eq(5), eq(EventType.FOLLOW_UP),
                eq(OWNER), isNull(), eq("followed_up"), anyString());
        verify(repository, times(8)).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
        audited("CRITICAL_FOLLOW_UP");
    }

    /** A required read-back is a hard precondition for clinician acknowledgment. */
    @Test
    void acknowledgeIsRefusedUntilTheRequiredReadBackIsRecorded() {
        String id = driveToAwaitingAck(true);
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic delivery", false), 3);

        ApiException failure = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic acknowledgment"), 3));

        assertEquals(409, failure.status());
        assertEquals("READ_BACK_REQUIRED", failure.code());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.ACKNOWLEDGED),
                any(), any(), any(), any());
    }

    /** A result that did not require read-back must refuse one rather than record a spurious fact. */
    @Test
    void readBackIsRefusedWhenItWasNotRequired() {
        String id = driveToAwaitingAck(false);
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic delivery", false), 3);

        ApiException failure = reject(() -> service.readBack(id, new ReadBackRequest("7.2 mmol/L"), 3));

        assertEquals(409, failure.status());
        assertEquals("READ_BACK_NOT_REQUIRED", failure.code());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.READ_BACK),
                any(), any(), any(), any());
    }

    /** A read-back needs a delivered attempt to repeat, not merely a registered alert. */
    @Test
    void readBackIsRefusedWithoutADeliveredAttempt() {
        String id = driveToAwaitingAck(true);
        when(repository.hasDeliveredAttempt(id, 3)).thenReturn(false);

        ApiException failure = reject(() -> service.readBack(id, new ReadBackRequest("7.2 mmol/L"), 3));

        assertEquals(409, failure.status());
        assertEquals("NO_DELIVERED_ATTEMPT", failure.code());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.READ_BACK),
                any(), any(), any(), any());
    }

    /** The read-back fact is single: a second one is refused, not appended twice. */
    @Test
    void readBackIsRefusedWhenRecordedTwice() {
        String id = driveToAwaitingAck(true);
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic delivery", false), 3);
        service.readBack(id, new ReadBackRequest("7.2 mmol/L"), 3);

        ApiException failure = reject(() -> service.readBack(id, new ReadBackRequest("7.2 mmol/L"), 3));

        assertEquals(409, failure.status());
        assertEquals("ALREADY_READ_BACK", failure.code());
        verify(repository, times(1)).appendEvent(eq(id), anyInt(), eq(EventType.READ_BACK),
                any(), any(), any(), any());
    }

    /** Neither a missing delivery nor a patient release can satisfy clinician acknowledgment. */
    @Test
    void releaseNeverSatisfiesClinicianAcknowledgment() {
        String id = driveToAwaitingAck(true);
        when(repository.hasDeliveredAttempt(anyString(), anyInt())).thenReturn(false);

        ApiException noDelivery = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic acknowledgment"), 3));
        assertEquals(409, noDelivery.status());
        assertEquals("NO_DELIVERED_ATTEMPT", noDelivery.code());

        Result released = service.release(id, new ReleaseRequest("synthetic patient release"), 3);
        assertEquals(State.AWAITING_ACK, released.state(), "patient release never acknowledges");
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.PATIENT_RELEASED),
                isNull(), isNull(), eq("released"), anyString());

        ApiException stillNoDelivery = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic acknowledgment"), 3));
        assertEquals(409, stillNoDelivery.status());
        assertEquals("NO_DELIVERED_ATTEMPT", stillNoDelivery.code());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.ACKNOWLEDGED),
                any(), any(), any(), any());

        // Delivery alone still does not acknowledge: the required read-back is owed.
        when(repository.hasDeliveredAttempt(anyString(), anyInt())).thenReturn(true);
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic delivery", false), 3);
        ApiException noReadBack = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic acknowledgment"), 3));
        assertEquals(409, noReadBack.status());
        assertEquals("READ_BACK_REQUIRED", noReadBack.code());
    }

    /** Acknowledgment is reserved for an active clinician, not a member of another role. */
    @Test
    void acknowledgeIsRefusedForARoleWithoutPermission() {
        String id = driveToAwaitingAck(true);
        lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic delivery", false), 3);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("reception"));

        ApiException failure = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic acknowledgment"), 3));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).acknowledge(anyString(), anyInt());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.ACKNOWLEDGED),
                any(), any(), any(), any());
    }

    /** An acknowledgment bound to a stale revision is refused. */
    @Test
    void staleVersionIsRefused() {
        String id = driveToAwaitingAck(true);

        ApiException failure = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic acknowledgment"), 2));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(repository, never()).acknowledge(anyString(), anyInt());
    }

    /** Follow-up is only legal after acknowledgment. */
    @Test
    void followUpIsRefusedBeforeAcknowledgment() {
        String id = driveToAwaitingAck(true);

        ApiException failure = reject(() -> service.followUp(id,
                new FollowUpRequest("synthetic follow-up"), 3));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        verify(repository, never()).close(anyString(), anyInt());
        verify(repository, never()).appendEvent(eq(id), anyInt(), eq(EventType.FOLLOW_UP),
                any(), any(), any(), any());
    }

    /** Register, assign and verify a synthetic result at the requested read-back policy. */
    private String driveToAwaitingAck(boolean readBackRequired) {
        Result registered = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", readBackRequired));
        assertEquals(State.UNVERIFIED, registered.state());
        String id = registered.id();
        service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1);
        Result verified = service.verify(id, new VerifyRequest("synthetic verification"), 2);
        assertEquals(State.AWAITING_ACK, verified.state());
        return id;
    }

    /** Synthetic result projection; the read-back flag follows the registration command. */
    private static Result result(String id, int version, State state, boolean readBackRequired,
                                 String owner, Instant due, String verifiedBy, Instant verifiedAt) {
        return new Result(id, version, PATIENT, "LAB", "OBS-1", "ORD-1", "ACC-1",
                "synthetic critical finding", "7.2", "mmol/L", readBackRequired, state,
                verifiedBy, verifiedAt, owner, due, PRIMARY, BACKUP_CHANNEL, BACKUP, NOW);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert the success audit row carries the synthetic actor and tenant, never a caller-supplied one. */
    private void audited(String action) {
        verify(audit).append(eq(new TenantContext(ORG, ACTOR, "CRITICAL_RESULT", CORRELATION)),
                eq(action), eq(action), eq("CRITICAL_RESULT"), eq("CRITICAL_RESULT"), anyString(),
                eq("SUCCESS"));
    }
}
