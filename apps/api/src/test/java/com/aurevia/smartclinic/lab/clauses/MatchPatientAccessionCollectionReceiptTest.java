package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.lab.accession.AccessionCommands.CollectRequest;
import com.aurevia.smartclinic.lab.accession.AccessionCommands.ReceiveRequest;
import com.aurevia.smartclinic.lab.accession.AccessionState;
import com.aurevia.smartclinic.lab.accession.LabAccession;
import com.aurevia.smartclinic.lab.accession.LabAccessionRepository;
import com.aurevia.smartclinic.lab.accession.LabAccessionService;
import com.aurevia.smartclinic.lab.accession.LabActionPolicy;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * LAB-01 clause 3 proof: patient and accession identity are matched both when a
 * specimen is collected and when it is received. Both commands are driven through
 * their normal entry points on {@link com.aurevia.smartclinic.lab.accession.LabAccessionService}.
 * The positive cases assert the transition, the immutable event that carries the
 * matched patient id, and the transactional audit row. Negative cases assert that
 * each identity field is independently rechecked on each touchpoint, that a role
 * without the matching duty is refused, and that stale or forbidden transitions
 * are refused before any state change. All data is synthetic.
 */
class MatchPatientAccessionCollectionReceiptTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "lab-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "33333333-3333-3333-3333-333333333333";
    private static final String OTHER_PATIENT = "55555555-5555-5555-5555-555555555555";
    private static final String COLLECTED_AT = "2026-11-19T10:15:30Z";
    private static final String RECEIVED_AT = "2026-11-19T11:15:30Z";

    private LabAccessionRepository repository;
    private AuditAppender audit;
    private LabAccessionService service;

    /** Set up a tenant-guarded service with mocked repository, transaction and audit boundaries. */
    @BeforeEach
    void setUp() {
        repository = mock(LabAccessionRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        audit = mock(AuditAppender.class);
        service = new LabAccessionService(repository, new LabActionPolicy(), transactions, audit);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "LAB_ACCESSION", "corr-clause"));
    }

    /** Clear the thread-bound tenant so tests do not leak context. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant one active role to the laboratory subject in the active organization. */
    private void authorized(String role) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(role));
    }

    /** The receiving duty belongs to the verifier, never to the collector. */
    private void authorizedVerifier() {
        authorized("lab_verifier");
    }

    /** Standard collection command with a fully matching requisition identity. */
    private static CollectRequest collectRequest() {
        return new CollectRequest(PATIENT, "ACC-1", "LAB-A", "PLASMA", "SITE-A",
                "Sam Collector", COLLECTED_AT, 1);
    }

    /** Standard receipt command with a fully matching requisition identity. */
    private static ReceiveRequest receiveRequest() {
        return new ReceiveRequest(PATIENT, "ACC-1", "LAB-A", "acceptable", RECEIVED_AT, 2);
    }

    /** Build a synthetic accession projection in the requested lifecycle state and version. */
    private static LabAccession accession(AccessionState state, int version) {
        boolean received = state == AccessionState.RECEIVED || state == AccessionState.REJECTED
                || state == AccessionState.RECOLLECTED;
        boolean rejected = state == AccessionState.REJECTED || state == AccessionState.RECOLLECTED;
        return new LabAccession(ID, version, "ACC-1", "LAB-A", PATIENT, "ORD/1", state,
                "PLASMA", "SITE-A", "Sam Collector", COLLECTED_AT,
                received ? RECEIVED_AT : null,
                received ? "acceptable" : null,
                rejected ? "hemolysed sample" : null,
                rejected ? "lab-a@example.test" : null,
                null);
    }

    /** Stub a successful optimistic-locked update of one row. */
    private void updateSucceeds() {
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
    }

    /** Assert the command is rejected with the given status and machine-readable code. */
    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void collectMatchesPatientAndAccessionThenTransitions() {
        authorized("lab_collector");
        when(repository.findById(ID)).thenReturn(
                Optional.of(accession(AccessionState.ACCESSIONED, 1)),
                Optional.of(accession(AccessionState.COLLECTED, 2)));
        updateSucceeds();

        LabAccession collected = service.collect(ID, collectRequest());

        assertEquals(AccessionState.COLLECTED, collected.state());
        assertEquals(PATIENT, collected.patientId());
        verify(repository).update(eq(ORG), eq(ID), eq(1), eq(AccessionState.COLLECTED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(null), eq(null), eq(null), eq(null));
        verify(repository).appendEvent(ID, 2, "collected", AccessionState.ACCESSIONED,
                AccessionState.COLLECTED, null, PATIENT);
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("SUCCESS"));
    }

    @Test
    void receiveMatchesPatientAndAccessionThenRecordsReceipt() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(
                Optional.of(accession(AccessionState.COLLECTED, 2)),
                Optional.of(accession(AccessionState.RECEIVED, 3)));
        updateSucceeds();

        LabAccession received = service.receive(ID, receiveRequest());

        assertEquals(AccessionState.RECEIVED, received.state());
        assertEquals("acceptable", received.quality());
        verify(repository).update(eq(ORG), eq(ID), eq(2), eq(AccessionState.RECEIVED),
                eq("PLASMA"), eq("SITE-A"), eq("Sam Collector"), eq(COLLECTED_AT),
                eq(RECEIVED_AT), eq("acceptable"), eq(null), eq(null));
        verify(repository).appendEvent(ID, 3, "received", AccessionState.COLLECTED,
                AccessionState.RECEIVED, null, PATIENT);
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.SPECIMEN_RECEIVE),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("SUCCESS"));
    }

    @Test
    void collectRefusesMismatchedPatientAccessionOrIssuer() {
        authorized("lab_collector");
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 1)));

        // One field changes at a time so each operand is the deciding check once.
        ApiException patient = reject(() -> service.collect(ID,
                new CollectRequest(OTHER_PATIENT, "ACC-1", "LAB-A", "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, 1)));
        assertEquals(422, patient.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", patient.code());

        ApiException accessionNumber = reject(() -> service.collect(ID,
                new CollectRequest(PATIENT, "ACC-9", "LAB-A", "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, 1)));
        assertEquals(422, accessionNumber.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", accessionNumber.code());

        ApiException issuer = reject(() -> service.collect(ID,
                new CollectRequest(PATIENT, "ACC-1", "LAB-B", "PLASMA", "SITE-A",
                        "Sam Collector", COLLECTED_AT, 1)));
        assertEquals(422, issuer.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", issuer.code());

        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(repository, never()).appendEvent(anyString(), anyInt(), anyString(), any(), any(),
                any(), any());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void receiveRefusesMismatchedPatientAccessionOrIssuer() {
        authorizedVerifier();
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 2)));

        ApiException patient = reject(() -> service.receive(ID,
                new ReceiveRequest(OTHER_PATIENT, "ACC-1", "LAB-A", "acceptable", RECEIVED_AT, 2)));
        assertEquals(422, patient.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", patient.code());

        ApiException accessionNumber = reject(() -> service.receive(ID,
                new ReceiveRequest(PATIENT, "ACC-9", "LAB-A", "acceptable", RECEIVED_AT, 2)));
        assertEquals(422, accessionNumber.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", accessionNumber.code());

        ApiException issuer = reject(() -> service.receive(ID,
                new ReceiveRequest(PATIENT, "ACC-1", "LAB-B", "acceptable", RECEIVED_AT, 2)));
        assertEquals(422, issuer.status());
        assertEquals("PATIENT_ACCESSION_MISMATCH", issuer.code());

        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(repository, never()).appendEvent(anyString(), anyInt(), anyString(), any(), any(),
                any(), any());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }

    @Test
    void collectRefusesActorWithoutCollectorPermission() {
        // A verifier has active membership but the collection duty is not its role.
        authorizedVerifier();

        ApiException denial = reject(() -> service.collect(ID, collectRequest()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).findById(anyString());
        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.SPECIMEN_COLLECT),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("DENIED"));
    }

    @Test
    void receiveRefusesActorWithoutVerifierPermission() {
        // A collector has active membership but the receipt duty is not its role.
        authorized("lab_collector");

        ApiException denial = reject(() -> service.receive(ID, receiveRequest()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).findById(anyString());
        verify(repository, never()).update(anyString(), anyString(), anyInt(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any());
        verify(audit).append(any(), eq("LAB_ACCESSION"), eq(LabActionPolicy.SPECIMEN_RECEIVE),
                eq("ACCESSION"), eq("LAB"), eq(ID), eq("DENIED"));
    }

    @Test
    void collectAndReceiveRefuseActorWithoutActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException collectDenial = reject(() -> service.collect(ID, collectRequest()));
        assertEquals(403, collectDenial.status());
        assertEquals("FORBIDDEN", collectDenial.code());
        ApiException receiveDenial = reject(() -> service.receive(ID, receiveRequest()));
        assertEquals(403, receiveDenial.status());
        assertEquals("FORBIDDEN", receiveDenial.code());

        verify(repository, never()).findById(anyString());
        verify(audit, times(2)).append(any(), eq("LAB_ACCESSION"), anyString(), eq("ACCESSION"),
                eq("LAB"), eq(ID), eq("DENIED"));
    }

    @Test
    void receiveRefusesStaleVersionForbiddenStateOrLostUpdate() {
        authorizedVerifier();

        // ACCESSIONED cannot move straight to RECEIVED.
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.ACCESSIONED, 2)));
        ApiException state = reject(() -> service.receive(ID, receiveRequest()));
        assertEquals(409, state.status());
        assertEquals("INVALID_TRANSITION", state.code());

        // The stored version has moved past the version the caller read.
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 3)));
        ApiException stale = reject(() -> service.receive(ID, receiveRequest()));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());

        // A concurrent update won the optimistic lock between read and write.
        when(repository.findById(ID)).thenReturn(Optional.of(accession(AccessionState.COLLECTED, 2)));
        when(repository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(0);
        ApiException lostUpdate = reject(() -> service.receive(ID, receiveRequest()));
        assertEquals(412, lostUpdate.status());
        assertEquals("STALE_VERSION", lostUpdate.code());

        verify(repository, never()).appendEvent(anyString(), anyInt(), anyString(), any(), any(),
                any(), any());
        verify(audit, never()).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("SUCCESS"));
    }
}
