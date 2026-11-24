package com.aurevia.acceptance.specialty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.prescription.rx.RxActions;
import com.aurevia.prescription.rx.RxChannel;
import com.aurevia.prescription.rx.RxContract.AttestRequest;
import com.aurevia.prescription.rx.RxContract.CreateRequest;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.RetryRequest;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxContract.TransmitRequest;
import com.aurevia.prescription.rx.RxContract.VerifyRequest;
import com.aurevia.prescription.rx.RxRepository;
import com.aurevia.prescription.rx.RxService;
import com.aurevia.tenant.TenantContext;

/**
 * Gate G4 acceptance for ambiguous prescription transmission.
 *
 * <p>Requirements proved: RX-02 (stable submission IDs, separate submitted,
 * partner-status-verified and retry facts, and verification of partner status
 * before retrying an ambiguous transmission) and G4 (an ambiguous transmission
 * reconciles without sending a duplicate). The retry reuses the original stable
 * submission id and never inserts a second submission. The repository mock holds
 * only synthetic domain state; no real patient data or partner credential is
 * used.</p>
 */
class PharmacyAmbiguousTransmissionAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-g4";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String PHARMACY = "pharmacy-x";
    private static final Instant CREATED = Instant.parse("2026-11-21T10:00:00Z");

    private RxRepository repository;
    private RxService service;
    private AtomicReference<String> submissionId;
    private AtomicReference<State> state;
    private AtomicInteger version;
    private AtomicReference<Boolean> retryAllowed;

    @BeforeEach
    void setUp() {
        repository = mock(RxRepository.class);
        AuditAppender audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new RxService(repository, new RxActions(), transactions, audit);
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_TRANSMISSION", "corr-g4-rx02"));

        submissionId = new AtomicReference<>();
        state = new AtomicReference<>(State.DRAFT);
        version = new AtomicInteger(1);
        retryAllowed = new AtomicReference<>(false);

        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(repository.approvedSetting("rx02.entitlement." + ACTOR)).thenReturn(Optional.of("enabled"));
        when(repository.approvedSetting("rx02.approved_channels"))
                .thenReturn(Optional.of(RxChannel.E_PRESCRIBE.wire()));

        org.mockito.stubbing.Answer<Optional<Prescription>> current =
                invocation -> Optional.of(snapshot());
        when(repository.find(ID)).thenAnswer(current);
        when(repository.loadForUpdate(ID)).thenAnswer(current);

        when(repository.insert(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyInt(), anyString(), anyString())).thenReturn(1);
        doAnswer(invocation -> {
            state.set(State.ATTESTED);
            version.incrementAndGet();
            return 1;
        }).when(repository).attest(eq(ID), anyInt());
        doAnswer(invocation -> {
            submissionId.set(invocation.getArgument(0));
            return 1;
        }).when(repository).insertSubmission(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            State next = invocation.getArgument(4);
            submissionId.set(invocation.getArgument(3));
            state.set(next);
            version.incrementAndGet();
            return 1;
        }).when(repository).transmit(eq(ID), anyInt(), anyString(), anyString(), any(State.class));
        doAnswer(invocation -> {
            boolean allowed = invocation.getArgument(2);
            State next = invocation.getArgument(3);
            retryAllowed.set(allowed);
            state.set(next);
            version.incrementAndGet();
            return 1;
        }).when(repository).reconcile(eq(ID), anyInt(), anyBoolean(), any(State.class));
        doAnswer(invocation -> {
            State next = invocation.getArgument(2);
            retryAllowed.set(false);
            state.set(next);
            version.incrementAndGet();
            return 1;
        }).when(repository).retrySubmission(eq(ID), anyInt(), any(State.class));
        when(repository.submissionAttempts(anyString())).thenReturn(1);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ambiguousTransmissionIsReconciledWithoutASecondSubmission() {
        // RX-02 / G4: one electronic send with an unknown outcome leaves the
        // prescription AMBIGUOUS; an unknown partner status keeps it there and
        // disables retry, and only a verified-absent status authorizes a retry.
        service.create(new CreateRequest(ID, PATIENT, "amoxicillin", "500 mg", "1 capsule",
                "oral", "twice daily", 30, ACTOR, PHARMACY));
        service.attest(ID, new AttestRequest("synthetic attestation"), 1);

        Prescription ambiguous = service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "ambiguous"), 2);
        assertEquals(State.AMBIGUOUS, ambiguous.state());
        String sid = ambiguous.submissionId();
        assertNotNull(sid);
        assertEquals(sid, submissionId.get());

        Prescription unknown = service.verifyPartnerStatus(ID, new VerifyRequest(sid, "unknown"), 3);
        assertEquals(State.AMBIGUOUS, unknown.state());
        assertFalse(unknown.retryAllowed());
        ApiException blocked = assertThrows(ApiException.class,
                () -> service.retry(ID, new RetryRequest(sid, "submitted"), 4));
        assertEquals(409, blocked.status());
        assertEquals("RETRY_NOT_ALLOWED", blocked.code());

        Prescription reconciled = service.verifyPartnerStatus(ID, new VerifyRequest(sid, "absent"), 4);
        assertTrue(reconciled.retryAllowed());
        Prescription retried = service.retry(ID, new RetryRequest(sid, "submitted"), 5);
        assertEquals(State.SUBMITTED, retried.state());
        assertEquals(sid, retried.submissionId());

        // RX-02 proof: the interaction verifies below are the assertion of
        // "no duplicate send"; the in-test atoms are only a deterministic state
        // holder for the concrete repository. Stale-version and retry-limit
        // enforcement are covered by RxServiceTest / RxTransmissionFlowTest.
        // The stable submission id is created exactly once; the retry only
        // appends another SUBMITTED fact against that same id.
        verify(repository, times(1)).insertSubmission(eq(sid), eq(ID), eq(RxChannel.E_PRESCRIBE.wire()));
        verify(repository).reconcile(eq(ID), eq(3), eq(false), eq(State.AMBIGUOUS));
        verify(repository).reconcile(eq(ID), eq(4), eq(true), eq(State.AMBIGUOUS));
        verify(repository).retrySubmission(eq(ID), eq(5), eq(State.SUBMITTED));
        verify(repository, times(2)).appendEvent(eq(ID), anyInt(), eq(EventType.SUBMITTED),
                anyString(), eq(sid), anyString(), anyString());
    }

    @Test
    void aPresentPartnerStatusReturnsToSubmittedWithoutARetry() {
        // RX-02: a partner that already has the submission moves the
        // prescription back to SUBMITTED; no retry or second send is issued.
        service.create(new CreateRequest(ID, PATIENT, "amoxicillin", "500 mg", "1 capsule",
                "oral", "twice daily", 30, ACTOR, PHARMACY));
        service.attest(ID, new AttestRequest("synthetic attestation"), 1);
        Prescription ambiguous = service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "ambiguous"), 2);
        String sid = ambiguous.submissionId();

        Prescription present = service.verifyPartnerStatus(ID, new VerifyRequest(sid, "present"), 3);
        assertEquals(State.SUBMITTED, present.state());
        assertFalse(present.retryAllowed());
        verify(repository, times(1)).insertSubmission(eq(sid), eq(ID), eq(RxChannel.E_PRESCRIBE.wire()));
        verify(repository, times(0)).retrySubmission(eq(ID), anyInt(), any(State.class));
    }

    private Prescription snapshot() {
        return new Prescription(ID, version.get(), PATIENT, "amoxicillin", "500 mg", "1 capsule",
                "oral", "twice daily", 30, ACTOR, PHARMACY, state.get(),
                submissionId.get() == null ? null : RxChannel.E_PRESCRIBE.wire(),
                submissionId.get(), retryAllowed.get(), CREATED);
    }
}
