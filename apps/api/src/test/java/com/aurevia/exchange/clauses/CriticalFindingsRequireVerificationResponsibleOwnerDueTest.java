package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * XCH-05 clause 1 (API test): a critical finding can be activated only after it
 * is verified and carries a responsible owner, a future due time and an approved
 * escalation policy. The four facts are recorded as separate immutable events.
 *
 * <p>Every case enters through {@link com.aurevia.exchange.critical.CriticalResultService},
 * the same domain service the HTTP controller injects, with a mocked repository,
 * a transaction template that runs the real guarded callback, a fixed clock and
 * a synthetic tenant context. The negative cases assert the status and stable
 * error code, not merely that an exception was thrown. No patient data or
 * credential is used.</p>
 */
class CriticalFindingsRequireVerificationResponsibleOwnerDueTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String OWNER = "clinician-a";
    private static final String BACKUP = "backup-a";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP_CHANNEL = "ONCALL_PHONE";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant DUE = NOW.plusSeconds(3600);

    private CriticalResultRepository repository;
    private AuditAppender audit;
    private CriticalResultService service;
    private AtomicReference<Result> current;

    /** Build the service and the synthetic tenant, policy and clock fixtures. */
    @BeforeEach
    void setUp() {
        repository = mock(CriticalResultRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new CriticalResultService(repository, new CriticalActions(), transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "CRITICAL_RESULT", "corr-clause-1"));

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
    }

    /** Remove the synthetic tenant context so other tests are unaffected. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Synthetic result projection with the approved policy stamped at assignment. */
    private static Result result(String id, int version, State state, String owner, Instant due,
                                 String verifiedBy, Instant verifiedAt) {
        return new Result(id, version, PATIENT, "LAB", "OBS-1", "ORD-1", "ACC-1",
                "synthetic critical finding", "7.2", "mmol/L", false, state,
                verifiedBy, verifiedAt, owner, due, PRIMARY, BACKUP_CHANNEL, BACKUP, NOW);
    }

    /** Capture the ApiException raised by a refused command. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert that the domain service wrote an audit fact for an action. */
    private void audited(String action) {
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq(action), eq(action),
                eq("CRITICAL_RESULT"), eq("CRITICAL_RESULT"), anyString(), eq("SUCCESS"));
    }

    /** Register, assign owner and due time, then verify the finding. */
    private String driveToAwaitingAck() {
        String id = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false)).id();
        service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1);
        assertEquals(State.AWAITING_ACK,
                service.verify(id, new VerifyRequest("synthetic verification"), 2).state());
        return id;
    }

    /** A verified, owned, due and policy-backed finding may be escalated as three separate facts. */
    @Test
    void criticalFindingIsVerifiedOwnedDueAndEscalatedUnderApprovedPolicy() {
        Result registered = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false));
        assertEquals(State.UNVERIFIED, registered.state());
        String id = registered.id();

        Result assigned = service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1);
        assertEquals(OWNER, assigned.ownerSubject());
        assertEquals(DUE, assigned.dueAt());
        assertEquals(PRIMARY, assigned.primaryChannel());
        assertEquals(BACKUP_CHANNEL, assigned.backupChannel());
        assertEquals(BACKUP, assigned.backupRecipient());
        assertEquals(2, assigned.version());

        Result verified = service.verify(id, new VerifyRequest("synthetic verification"), 2);
        assertEquals(State.AWAITING_ACK, verified.state());
        assertEquals(ACTOR, verified.verifiedBySubject());
        assertEquals(NOW, verified.verifiedAt());
        assertEquals(3, verified.version());

        Result escalated = service.escalate(id,
                new EscalateRequest(PRIMARY, OWNER, "delivered", "synthetic delivery", false), 3);
        assertEquals(State.AWAITING_ACK, escalated.state(), "an attempt is a fact, not a state change");
        assertEquals(3, escalated.version());

        verify(repository).appendEvent(eq(id), eq(2), eq(EventType.OWNER_ASSIGNED), eq(OWNER),
                eq(PRIMARY), eq("assigned"), eq(DUE.toString()));
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.VERIFIED), eq(OWNER),
                isNull(), eq("verified"), eq("synthetic verification"));
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT), eq(OWNER),
                eq(PRIMARY), eq("delivered"), eq("synthetic delivery"));
        audited(CriticalActions.VERIFY);
    }

    /** An unassigned finding has neither owner nor due time and cannot be verified. */
    @Test
    void verificationIsRefusedWithoutOwnerAndDueTime() {
        String id = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false)).id();

        ApiException failure = reject(() -> service.verify(id, new VerifyRequest("synthetic verification"), 1));

        assertEquals(409, failure.status());
        assertEquals("OWNER_REQUIRED", failure.code());
        verify(repository, never()).verify(anyString(), anyInt(), anyString(), any());
        verify(repository, never()).appendEvent(anyString(), anyInt(), eq(EventType.VERIFIED),
                any(), any(), any(), any());
    }

    /** Escalation must wait for verification; an owned unverified finding is refused. */
    @Test
    void escalationIsRefusedBeforeVerification() {
        String id = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false)).id();
        service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1);

        ApiException failure = reject(() -> service.escalate(id,
                new EscalateRequest(PRIMARY, OWNER, "delivered", "synthetic delivery", false), 2));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        verify(repository, never()).appendEvent(anyString(), anyInt(), eq(EventType.ESCALATION_ATTEMPT),
                any(), any(), any(), any());
    }

    /** Assignment cannot stamp an escalation policy the site has not approved. */
    @Test
    void assignmentIsRefusedWithoutApprovedEscalationPolicy() {
        when(repository.approvedSetting("critical.escalation.primary_channel")).thenReturn(Optional.empty());
        String id = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false)).id();

        ApiException failure = reject(() -> service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1));

        assertEquals(422, failure.status());
        assertEquals("NO_ESCALATION_POLICY", failure.code());
        verify(repository, never()).assign(anyString(), anyInt(), anyString(), any(),
                anyString(), anyString(), anyString());
        verify(repository, never()).appendEvent(anyString(), anyInt(), eq(EventType.OWNER_ASSIGNED),
                any(), any(), any(), any());
    }

    /** A missing active backup recipient is a roster gap, recorded as its own event. */
    @Test
    void assignmentIsRefusedWithoutApprovedBackupRecipient() {
        when(repository.hasActiveMembership(BACKUP)).thenReturn(false);
        String id = service.register(new RegisterRequest(PATIENT, "LAB", "OBS-1", "ORD-1",
                "ACC-1", "synthetic critical finding", "7.2", "mmol/L", false)).id();

        ApiException failure = reject(() -> service.assign(id, new AssignRequest(OWNER, DUE.toString()), 1));

        assertEquals(422, failure.status());
        assertEquals("BACKUP_ROSTER_GAP", failure.code());
        verify(repository).appendEvent(eq(id), eq(1), eq(EventType.ROSTER_GAP), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("failed"), eq("no active backup clinician"));
        verify(repository, never()).assign(anyString(), anyInt(), anyString(), any(),
                anyString(), anyString(), anyString());
    }

    /** Only an active clinician may acknowledge; a verifier-only role is denied with a status and code. */
    @Test
    void acknowledgeIsRefusedForARoleWithoutClinicalPermission() {
        String id = driveToAwaitingAck();
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_verifier"));

        ApiException failure = reject(() -> service.acknowledge(id,
                new AcknowledgeRequest("synthetic clinician acknowledgment"), 3));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).acknowledge(anyString(), anyInt());
    }

    /** A patient release is a separate fact and never acknowledges the critical finding. */
    @Test
    void patientReleaseDoesNotAcknowledgeCriticalFinding() {
        String id = driveToAwaitingAck();

        Result released = service.release(id, new ReleaseRequest("synthetic patient release"), 3);

        assertEquals(State.AWAITING_ACK, released.state());
        assertEquals(3, released.version());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.PATIENT_RELEASED),
                isNull(), isNull(), eq("released"), eq("synthetic patient release"));
        verify(repository, never()).appendEvent(anyString(), anyInt(), eq(EventType.ACKNOWLEDGED),
                any(), any(), any(), any());
    }
}
