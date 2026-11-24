package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.prescription.rx.RxActions;
import com.aurevia.prescription.rx.RxContract.DispensingRequest;
import com.aurevia.prescription.rx.RxContract.Event;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxContract.View;
import com.aurevia.prescription.rx.RxRepository;
import com.aurevia.prescription.rx.RxService;
import com.aurevia.tenant.TenantContext;

/**
 * RX-02 clause proof: dispensing evidence is a fact distinct from pharmacy
 * acceptance. The test drives the real {@link com.aurevia.prescription.rx.RxService}
 * command with a mocked persistence boundary, so it proves the domain rule and
 * its authorization, state and version guards without a database. All fixtures
 * are synthetic; no patient or credential data is used.
 */
class DistinguishDispensingEvidenceAcceptanceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PHARMACIST = "pharmacy-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String SUBMISSION = "cccccccc-0000-0000-0000-000000000001";
    private static final String OTHER_SUBMISSION = "cccccccc-0000-0000-0000-000000000002";
    private static final String ACCEPTANCE_EVENT_ID = "dddddddd-0000-0000-0000-000000000001";
    private static final String DISPENSING_EVENT_ID = "dddddddd-0000-0000-0000-000000000002";
    private static final String CHANNEL = "E_PRESCRIBE";
    private static final String NOTE = "synthetic dispensing evidence";
    private static final int VERSION = 5;
    private static final Instant CREATED_AT = Instant.parse("2026-11-20T10:00:00Z");

    private RxRepository repository;
    private AuditAppender audit;
    private RxService service;

    @BeforeEach
    void setUp() {
        repository = mock(RxRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new RxService(repository, new RxActions(), transactions, audit);
        setActor(PHARMACIST);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void pharmacyStaffRecordsDispensingEvidenceDistinctFromAcceptance() {
        authorizedAs(PHARMACIST, "pharmacy_staff");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.ACCEPTED, VERSION)));
        when(repository.find(ID)).thenReturn(Optional.of(prescription(State.DISPENSED, VERSION + 1)));
        when(repository.events(ID)).thenReturn(List.of(acceptanceEvent(), dispensingEvent()));

        Prescription result = service.recordDispensing(ID, new DispensingRequest(SUBMISSION, NOTE), VERSION);

        assertEquals(State.DISPENSED, result.state());
        assertEquals(VERSION + 1, result.version());
        verify(repository).setState(ID, VERSION, State.DISPENSED);
        verify(repository).appendEvent(eq(ID), eq(VERSION + 1), eq(EventType.DISPENSING_EVIDENCE),
                eq(CHANNEL), eq(SUBMISSION), eq("dispensed"), eq(NOTE));
        verify(repository, never()).appendEvent(any(), anyInt(), eq(EventType.PHARMACY_ACCEPTED),
                any(), any(), any(), any());
        verify(audit).append(any(), any(), eq(RxActions.DISPENSE), any(), any(), eq(ID), eq("SUCCESS"));

        // The pre-existing acceptance fact is retained alongside the new dispensing
        // fact; recording evidence never rewrites or replaces the acceptance event.
        View trail = service.get(ID);
        assertEquals(List.of(EventType.PHARMACY_ACCEPTED, EventType.DISPENSING_EVIDENCE),
                trail.events().stream().map(Event::eventType).toList());
    }

    @Test
    void clinicianCannotRecordDispensingEvidence() {
        setActor(CLINICIAN);
        authorizedAs(CLINICIAN, "clinician");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.ACCEPTED, VERSION)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.recordDispensing(ID, new DispensingRequest(SUBMISSION, NOTE), VERSION));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).loadForUpdate(any());
        verify(repository, never()).setState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
        verify(audit).append(any(), any(), eq(RxActions.DISPENSE), any(), any(), eq(ID), eq("DENIED"));
    }

    @Test
    void refusesDispensingBeforeAcceptance() {
        authorizedAs(PHARMACIST, "pharmacy_staff");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.DELIVERY_CONFIRMED, VERSION)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.recordDispensing(ID, new DispensingRequest(SUBMISSION, NOTE), VERSION));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        verify(repository, never()).setState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesDispensingForAnotherSubmission() {
        authorizedAs(PHARMACIST, "pharmacy_staff");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.ACCEPTED, VERSION)));

        ApiException failure = assertThrows(ApiException.class, () -> service.recordDispensing(ID,
                new DispensingRequest(OTHER_SUBMISSION, NOTE), VERSION));

        assertEquals(422, failure.status());
        assertEquals("SUBMISSION_MISMATCH", failure.code());
        verify(repository, never()).setState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesDispensingOnStaleVersion() {
        authorizedAs(PHARMACIST, "pharmacy_staff");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescription(State.ACCEPTED, VERSION - 1)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.recordDispensing(ID, new DispensingRequest(SUBMISSION, NOTE), VERSION));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(repository, never()).setState(any(), anyInt(), any());
        verify(repository, never()).appendEvent(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesWithoutActiveMembership() {
        when(repository.hasActiveMembership(PHARMACIST)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.recordDispensing(ID, new DispensingRequest(SUBMISSION, NOTE), VERSION));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(repository, never()).loadForUpdate(any());
        verify(audit).append(any(), any(), eq(RxActions.DISPENSE), any(), any(), eq(ID), eq("DENIED"));
    }

    private void setActor(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PRESCRIPTION_TRANSMISSION", "corr-1"));
    }

    private void authorizedAs(String subject, String role) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(role));
    }

    private static Prescription prescription(State state, int version) {
        return new Prescription(ID, version, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, CLINICIAN, "pharmacy-x", state, CHANNEL, SUBMISSION, false, CREATED_AT);
    }

    private static Event acceptanceEvent() {
        return new Event(ACCEPTANCE_EVENT_ID, ID, VERSION, EventType.PHARMACY_ACCEPTED, PHARMACIST,
                CHANNEL, SUBMISSION, "accepted", "synthetic acceptance", CREATED_AT);
    }

    private static Event dispensingEvent() {
        return new Event(DISPENSING_EVENT_ID, ID, VERSION + 1, EventType.DISPENSING_EVIDENCE, PHARMACIST,
                CHANNEL, SUBMISSION, "dispensed", NOTE, CREATED_AT.plusSeconds(1));
    }
}
