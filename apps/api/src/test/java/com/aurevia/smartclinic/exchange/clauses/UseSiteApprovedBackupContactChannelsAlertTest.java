package com.aurevia.smartclinic.exchange.clauses;

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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.exchange.critical.CriticalActions;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.AssignRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.EscalateRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.EventType;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.Result;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.State;
import com.aurevia.smartclinic.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.smartclinic.exchange.critical.CriticalResultRepository;
import com.aurevia.smartclinic.exchange.critical.CriticalResultService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * XCH-05 clause 3 (API test): an overdue alert, or one whose primary system is
 * down, must use the site-approved backup channel and recipient; every other
 * channel, role, version and state is refused.
 *
 * <p>Every case enters through {@link com.aurevia.smartclinic.exchange.critical.CriticalResultService#escalate},
 * the command invoked by {@code CriticalResultController} at
 * {@code POST /api/v1/critical-results/{id}/escalate}. The mocked repository and
 * audit appender are paired with a transaction template that runs the real
 * guarded callback synchronously, so each assertion observes the exact event and
 * audit facts the command would commit. Two fixed clocks drive the overdue and
 * in-hours sides deterministically and all identifiers and notes are synthetic;
 * no patient data, credential or timing race is used.</p>
 */
class UseSiteApprovedBackupContactChannelsAlertTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "tenantadmin-xch05";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String OWNER = "clinician-xch05";
    private static final String BACKUP = "backup-xch05";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP_CHANNEL = "ONCALL_PHONE";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final Instant DUE = NOW.plusSeconds(3600);

    private CriticalResultRepository repository;
    private AuditAppender audit;
    private CriticalResultService service;
    private CriticalResultService lateService;
    private AtomicReference<Result> current;

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
        TenantContext.set(new TenantContext(ORG, ACTOR, "CRITICAL_RESULT", "corr-xch05-backup"));

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

    /** An overdue alert is routed to the approved backup and records one attempt. */
    @Test
    void overdueAlertUsesApprovedBackupChannelAndRecordsAttempt() {
        String id = driveToAwaitingAck();

        Result attempt = lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic backup delivery", false), 3);

        assertEquals(State.AWAITING_ACK, attempt.state(), "an attempt is a fact, not a state change");
        assertEquals(3, attempt.version());
        assertBackupAttemptWritten(id);
        assertEscalationAudited("SUCCESS");
    }

    /** A down primary system switches to the approved backup even before the deadline. */
    @Test
    void systemDownAlertUsesApprovedBackupChannelBeforeDeadline() {
        String id = driveToAwaitingAck();

        Result attempt = service.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic offline delivery", true), 3);

        assertEquals(State.AWAITING_ACK, attempt.state());
        assertEquals(3, attempt.version());
        assertBackupAttemptWritten(id);
        assertEscalationAudited("SUCCESS");
    }

    /** An overdue alert refuses the primary channel and the primary owner. */
    @Test
    void overdueAlertRefusesPrimaryChannelAndOwner() {
        String id = driveToAwaitingAck();

        ApiException primary = reject(() -> lateService.escalate(id,
                new EscalateRequest(PRIMARY, OWNER, "delivered", "synthetic", false), 3));
        assertEquals(422, primary.status());
        assertEquals("BACKUP_CHANNEL_REQUIRED", primary.code());

        ApiException wrongRecipient = reject(() -> lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, OWNER, "delivered", "synthetic", false), 3));
        assertEquals(422, wrongRecipient.status());
        assertEquals("BACKUP_CHANNEL_REQUIRED", wrongRecipient.code());
        assertNoEscalationAttemptWritten();
        assertNoEscalationAudited();
    }

    /** An in-hours alert refuses the backup channel before the deadline. */
    @Test
    void inHoursAlertRefusesBackupChannel() {
        String id = driveToAwaitingAck();

        ApiException failure = reject(() -> service.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", false), 3));

        assertEquals(422, failure.status());
        assertEquals("PRIMARY_CHANNEL_REQUIRED", failure.code());
        assertNoEscalationAttemptWritten();
        assertNoEscalationAudited();
    }

    /** A revoked backup recipient fails closed with a durable roster-gap fact. */
    @Test
    void escalationIsRefusedWhenBackupRecipientIsNotActive() {
        String id = driveToAwaitingAck();
        when(repository.hasActiveMembership(BACKUP)).thenReturn(false);

        ApiException failure = reject(() -> lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", false), 3));

        assertEquals(409, failure.status());
        assertEquals("ROSTER_GAP", failure.code());
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ROSTER_GAP), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("failed"), eq("no active backup clinician"));
        verify(repository, never()).appendEvent(anyString(), anyInt(), eq(EventType.ESCALATION_ATTEMPT),
                any(), any(), any(), any());
        assertEscalationAudited("CONFLICT");
    }

    /** An actor without the escalation permission is refused and audited as denied. */
    @Test
    void escalationByUnauthorizedRoleIsForbidden() {
        String id = driveToAwaitingAck();
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("lab_verifier"));

        ApiException failure = reject(() -> service.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", true), 3));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertNoEscalationAttemptWritten();
        assertEscalationAudited("DENIED");
    }

    /** An out-of-date If-Match version cannot record an escalation attempt. */
    @Test
    void staleVersionEscalationIsRefused() {
        String id = driveToAwaitingAck();

        ApiException failure = reject(() -> lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", false), 2));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        assertNoEscalationAttemptWritten();
    }

    /** An alert outside AWAITING_ACK cannot be escalated. */
    @Test
    void escalationOfAlertNotAwaitingAckIsRefused() {
        String id = driveToAwaitingAck();
        current.set(result(id, 3, State.UNVERIFIED, OWNER, DUE, ACTOR, NOW));

        ApiException failure = reject(() -> lateService.escalate(id,
                new EscalateRequest(BACKUP_CHANNEL, BACKUP, "delivered", "synthetic", false), 3));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        assertNoEscalationAttemptWritten();
    }

    /** Register, assign and verify a synthetic finding so the alert awaits acknowledgment at version 3. */
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

    /** Synthetic awaiting-acknowledgment projection with the approved policy snapshot. */
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

    /** Assert that one delivered attempt reached the approved backup recipient and channel. */
    private void assertBackupAttemptWritten(String id) {
        verify(repository).appendEvent(eq(id), eq(3), eq(EventType.ESCALATION_ATTEMPT), eq(BACKUP),
                eq(BACKUP_CHANNEL), eq("delivered"), anyString());
    }

    /** Assert that no escalation attempt was appended by a refused command. */
    private void assertNoEscalationAttemptWritten() {
        verify(repository, never()).appendEvent(anyString(), anyInt(), eq(EventType.ESCALATION_ATTEMPT),
                any(), any(), any(), any());
    }

    /** Assert that an escalation outcome was audited with the exact action and result. */
    private void assertEscalationAudited(String outcome) {
        verify(audit, atLeastOnce()).append(any(TenantContext.class), eq("CRITICAL_ESCALATE"),
                eq("CRITICAL_ESCALATE"), eq("CRITICAL_RESULT"), eq("CRITICAL_RESULT"), anyString(),
                eq(outcome));
    }

    /** Assert that no audit row was written for a refused escalation. */
    private void assertNoEscalationAudited() {
        verify(audit, never()).append(any(TenantContext.class), eq("CRITICAL_ESCALATE"),
                eq("CRITICAL_ESCALATE"), eq("CRITICAL_RESULT"), eq("CRITICAL_RESULT"), anyString(),
                anyString());
    }
}
