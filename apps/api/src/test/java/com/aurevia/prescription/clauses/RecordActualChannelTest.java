package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.prescription.rx.PrintablePrescription;
import com.aurevia.prescription.rx.RxActions;
import com.aurevia.prescription.rx.RxChannel;
import com.aurevia.prescription.rx.RxContract.Event;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxContract.TransmitRequest;
import com.aurevia.prescription.rx.RxRepository;
import com.aurevia.prescription.rx.RxService;
import com.aurevia.tenant.TenantContext;

/**
 * RX-02 clause 3: the channel actually used is recorded on the prescription and
 * its event trail, and a channel that was not used or not approved is refused.
 *
 * <p>Every fixture is synthetic (opaque UUIDs and a bounded subject key); no
 * patient data or credential is present. The repository is a synchronous mock:
 * the channel and submission id written by the service are captured from the
 * write calls, and the read-back snapshot is built from those captures, so an
 * assertion on the returned prescription reflects the write rather than a
 * hand-built fixture. The clause's end-to-end HTTP proof lives in
 * RxTransmissionFlowTest; this class isolates the service boundary.</p>
 */
class RecordActualChannelTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-1";
    private static final String OTHER_PRESCRIBER = "other-clinician";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final Instant ATTESTED_AT = Instant.parse("2026-11-20T10:15:30Z");

    private RxRepository repository;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private RxService service;

    private AtomicReference<String> recordedChannel;
    private AtomicReference<String> ledgerSubmissionId;
    private AtomicReference<String> rowSubmissionId;

    /**
     * Build a synchronous mocked service and capture every channel-stamping write.
     */
    @BeforeEach
    void setUp() {
        repository = mock(RxRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new RxService(repository, new RxActions(), transactions, audit);
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_TRANSMISSION", "corr-1"));

        recordedChannel = new AtomicReference<>();
        ledgerSubmissionId = new AtomicReference<>();
        rowSubmissionId = new AtomicReference<>();
        // Capture the arguments the service actually writes; a read-back that
        // ignores them would assert the fixture instead of the behaviour.
        doAnswer(invocation -> {
            ledgerSubmissionId.set(invocation.getArgument(0, String.class));
            recordedChannel.set(invocation.getArgument(2, String.class));
            return 1;
        }).when(repository).insertSubmission(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            rowSubmissionId.set(invocation.getArgument(3, String.class));
            recordedChannel.set(invocation.getArgument(2, String.class));
            return 1;
        }).when(repository).transmit(eq(ID), anyInt(), anyString(), anyString(), any(State.class));
        doAnswer(invocation -> {
            recordedChannel.set(invocation.getArgument(2, String.class));
            return 1;
        }).when(repository).transmitPrinted(eq(ID), anyInt(), anyString());
        when(repository.find(ID)).thenAnswer(invocation -> Optional.of(currentSnapshot()));
    }

    /** Clear the tenant context so it cannot leak into another test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void recordsTheApprovedChannelOnElectronicTransmit() {
        authorized();
        entitled("enabled");
        channels(RxChannel.E_PRESCRIBE.wire() + ", " + RxChannel.PRINTABLE_LOCAL.wire());
        loadedAttested();

        Prescription transmitted = service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "submitted"), 2);

        String ledgerId = ledgerSubmissionId.get();
        String rowId = rowSubmissionId.get();
        assertNotNull(ledgerId);
        assertEquals(ledgerId, rowId, "one stable submission id is reused by the row and the ledger");
        verify(repository).insertSubmission(ledgerId, ID, RxChannel.E_PRESCRIBE.wire());
        verify(repository).transmit(ID, 2, RxChannel.E_PRESCRIBE.wire(), rowId, State.SUBMITTED);
        ArgumentCaptor<String> eventSubmission = ArgumentCaptor.forClass(String.class);
        verify(repository).appendEvent(eq(ID), eq(3), eq(EventType.SUBMITTED),
                eq(RxChannel.E_PRESCRIBE.wire()), eventSubmission.capture(), eq("submitted"), eq("market=US"));
        assertEquals(ledgerId, eventSubmission.getValue(), "the event carries the same stable submission id");
        assertEquals(RxChannel.E_PRESCRIBE.wire(), transmitted.channel());
        assertEquals(rowId, transmitted.submissionId());
        verify(audit).append(any(), any(), eq(RxActions.TRANSMIT), any(), any(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void recordsTheApprovedPrintableChannelOnPrintTransmit() {
        authorized();
        entitled("enabled");
        channels(RxChannel.PRINTABLE_LOCAL.wire());
        loadedAttested();

        Prescription printed = service.transmit(ID,
                new TransmitRequest(RxChannel.PRINTABLE_LOCAL.wire(), "printed"), 2);

        verify(repository).transmitPrinted(ID, 2, RxChannel.PRINTABLE_LOCAL.wire());
        verify(repository).appendEvent(ID, 3, EventType.PRINTED, RxChannel.PRINTABLE_LOCAL.wire(),
                null, "printed", "market=US");
        verify(repository, never()).insertSubmission(anyString(), anyString(), anyString());
        verify(repository, never()).transmit(anyString(), anyInt(), anyString(), anyString(), any(State.class));
        assertEquals(RxChannel.PRINTABLE_LOCAL.wire(), printed.channel());
        verify(audit).append(any(), any(), eq(RxActions.TRANSMIT), any(), any(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void copiesTheRecordedChannelOntoThePrintableDocument() {
        authorized();
        entitled("enabled");
        channels(RxChannel.PRINTABLE_LOCAL.wire());
        loadedAttested();
        when(repository.events(ID)).thenReturn(List.of(attestation()));

        service.transmit(ID, new TransmitRequest(RxChannel.PRINTABLE_LOCAL.wire(), "printed"), 2);
        PrintablePrescription document = service.printable(ID);

        assertEquals(RxChannel.PRINTABLE_LOCAL.wire(), recordedChannel.get());
        assertEquals(recordedChannel.get(), document.channel(),
                "the document copies the channel actually stamped by the print write");
    }

    @Test
    void refusesAnUnapprovedChannel() {
        authorized();
        entitled("enabled");
        channels(RxChannel.PRINTABLE_LOCAL.wire());
        loadedAttested();

        ApiException failure = assertThrows(ApiException.class, () -> service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "submitted"), 2));

        assertEquals(422, failure.status());
        assertEquals("UNAPPROVED_CHANNEL", failure.code());
        verifyNoWrites();
    }

    @Test
    void refusesAnUnknownChannelBeforeAnyTransaction() {
        ApiException failure = assertThrows(ApiException.class, () -> service.transmit(ID,
                new TransmitRequest("SNEAKER_NET", "submitted"), 2));

        assertEquals(422, failure.status());
        assertEquals("INVALID_CHANNEL", failure.code());
        verify(transactions, never()).execute(any());
        verifyNoInteractions(repository, audit);
    }

    @Test
    void refusesAPharmacyRoleRouting() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("pharmacy_staff"));

        ApiException failure = assertThrows(ApiException.class, () -> service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "submitted"), 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
        verify(repository, never()).loadForUpdate(anyString());
        verifyNoWrites();
    }

    @Test
    void refusesTheWrongPrescriber() {
        authorized();
        // No entitlement or channel policy is stubbed: ownership is checked
        // first, so a non-prescriber is refused before either policy is read.
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(prescribedBy(OTHER_PRESCRIBER)));

        ApiException failure = assertThrows(ApiException.class, () -> service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "submitted"), 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("only the prescribing clinician may attest or route"));
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
        verifyNoWrites();
    }

    @Test
    void refusesAStaleVersion() {
        authorized();
        loadedAttested();

        ApiException failure = assertThrows(ApiException.class, () -> service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "submitted"), 1));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verifyNoWrites();
    }

    @Test
    void refusesAPrescriptionThatIsNotAttested() {
        authorized();
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(draft()));

        ApiException failure = assertThrows(ApiException.class, () -> service.transmit(ID,
                new TransmitRequest(RxChannel.E_PRESCRIBE.wire(), "submitted"), 2));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        verifyNoWrites();
    }

    /**
     * Return the read-back view built from the channel and submission id the
     * service actually wrote, so a returned-value assertion proves the write.
     */
    private Prescription currentSnapshot() {
        String channel = recordedChannel.get();
        if (channel == null) {
            return attested();
        }
        State state = RxChannel.PRINTABLE_LOCAL.wire().equals(channel) ? State.PRINTED : State.SUBMITTED;
        return new Prescription(ID, 3, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, ACTOR, "pharmacy-x", state, channel, rowSubmissionId.get(), false, ATTESTED_AT);
    }

    /** Grant active membership and the clinician role to the default actor. */
    private void authorized() {
        authorized(ACTOR);
    }

    /** Grant active membership and the clinician role to the given subject. */
    private void authorized(String subject) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of("clinician"));
    }

    /** Approve the market and the default actor's entitlement. */
    private void entitled(String status) {
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(repository.approvedSetting("rx02.entitlement." + ACTOR)).thenReturn(Optional.of(status));
    }

    /** Approve the given comma-separated channel policy. */
    private void channels(String value) {
        when(repository.approvedSetting("rx02.approved_channels")).thenReturn(Optional.of(value));
    }

    /** Return an attested, version-aligned row owned by the default actor. */
    private void loadedAttested() {
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(attested()));
    }

    /** Assert that no channel write and no success audit escaped a refusal. */
    private void verifyNoWrites() {
        verify(repository, never()).insertSubmission(anyString(), anyString(), anyString());
        verify(repository, never()).transmit(anyString(), anyInt(), anyString(), anyString(), any(State.class));
        verify(repository, never()).transmitPrinted(anyString(), anyInt(), anyString());
        verify(repository, never()).appendEvent(anyString(), anyInt(), any(EventType.class), any(), any(), any(), any());
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("SUCCESS"));
    }

    /** An attested version-2 prescription owned by the default prescriber. */
    private static Prescription attested() {
        return prescribedBy(ACTOR);
    }

    /** An attested version-2 prescription owned by the given prescriber. */
    private static Prescription prescribedBy(String subject) {
        return new Prescription(ID, 2, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, subject, "pharmacy-x", State.ATTESTED, null, null, false, ATTESTED_AT);
    }

    /** A draft version-2 prescription, valid for the version guard but not for transmit. */
    private static Prescription draft() {
        return new Prescription(ID, 2, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, ACTOR, "pharmacy-x", State.DRAFT, null, null, false, ATTESTED_AT);
    }

    /** The synthetic prescriber attestation the printable document requires. */
    private static Event attestation() {
        return new Event("cccccccc-0000-0000-0000-000000000001", ID, 2, EventType.ATTESTED,
                ACTOR, null, null, "market=US", "synthetic attestation", ATTESTED_AT);
    }
}
