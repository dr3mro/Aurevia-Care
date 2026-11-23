package com.aurevia.smartclinic.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import com.aurevia.smartclinic.prescription.rx.PrintablePrescription;
import com.aurevia.smartclinic.prescription.rx.RxActions;
import com.aurevia.smartclinic.prescription.rx.RxChannel;
import com.aurevia.smartclinic.prescription.rx.RxContract.Event;
import com.aurevia.smartclinic.prescription.rx.RxContract.EventType;
import com.aurevia.smartclinic.prescription.rx.RxContract.Prescription;
import com.aurevia.smartclinic.prescription.rx.RxContract.State;
import com.aurevia.smartclinic.prescription.rx.RxContract.TransmitRequest;
import com.aurevia.smartclinic.prescription.rx.RxRepository;
import com.aurevia.smartclinic.prescription.rx.RxService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * RX-02 clause: route an attested prescription through an approved prescribing
 * channel, or produce a locally approved printable prescription.
 *
 * <p>Synthetic, database-free service-boundary proof: the real
 * {@link com.aurevia.smartclinic.prescription.rx.RxService} runs against a mocked repository, audit appender and
 * transaction template that executes the guarded callback inline. Every case
 * asserts an observable result or an exact status and error code, not merely
 * that a mock was called.</p>
 */
class RouteThroughApprovedPrescribingChannelProduceLocallyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-1";
    private static final String OTHER_PRESCRIBER = "other-clinician";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final Instant CREATED_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant ATTESTED_AT = Instant.parse("2026-11-20T10:15:30Z");

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
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_TRANSMISSION", "corr-rx02-c02"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void routesAnAttestedPrescriptionThroughAnApprovedElectronicChannel() {
        approvedElectronicSetup();
        AtomicReference<String> generatedSubmission = new AtomicReference<>();
        doAnswer(invocation -> {
            generatedSubmission.set(invocation.getArgument(0));
            return 1;
        }).when(repository).insertSubmission(anyString(), anyString(), anyString());
        when(repository.find(ID)).thenAnswer(invocation -> Optional.of(submitted(generatedSubmission.get())));

        Prescription result = service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 2);

        assertEquals(State.SUBMITTED, result.state(), "an approved electronic channel submits the prescription");
        assertEquals(RxChannel.E_PRESCRIBE.wire(), result.channel(), "the actual channel is recorded");
        assertNotNull(result.submissionId(), "a stable submission id is produced");
        assertEquals(result.submissionId(), generatedSubmission.get(),
                "the returned submission id is the one persisted by the send");
        verify(repository).insertSubmission(result.submissionId(), ID, RxChannel.E_PRESCRIBE.wire());
        verify(repository).transmit(eq(ID), eq(2), eq(RxChannel.E_PRESCRIBE.wire()),
                eq(result.submissionId()), eq(State.SUBMITTED));
        verify(repository).appendEvent(eq(ID), eq(3), eq(EventType.SUBMITTED),
                eq(RxChannel.E_PRESCRIBE.wire()), eq(result.submissionId()), eq("submitted"), any());
        verify(repository).approvedSetting("rx02.market");
        verify(repository).approvedSetting("rx02.entitlement." + ACTOR);
        verify(repository).approvedSetting("rx02.approved_channels");
        verify(audit).append(any(), any(), eq(RxActions.TRANSMIT), any(), any(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void issuesALocallyApprovedPrintablePrescription() {
        authorized();
        entitled();
        channels("E_PRESCRIBE,PRINTABLE_LOCAL");
        when(repository.find(ID)).thenReturn(Optional.of(printed()));
        when(repository.events(ID)).thenReturn(List.of(attestation()));

        PrintablePrescription document = service.printable(ID);

        assertEquals(ID, document.prescriptionId());
        assertEquals("amoxicillin", document.medication());
        assertEquals(RxChannel.PRINTABLE_LOCAL.wire(), document.channel(), "the local channel is copied verbatim");
        assertEquals("synthetic attestation", document.attestationNote());
        assertEquals(ATTESTED_AT, document.attestedAt(), "the attestation instant is carried onto the document");
        verify(repository, never()).insertSubmission(anyString(), anyString(), anyString());
        verify(repository, never()).transmit(anyString(), anyInt(), anyString(), anyString(), any(State.class));
        verify(repository).approvedSetting("rx02.market");
        verify(repository).approvedSetting("rx02.entitlement." + ACTOR);
        verify(repository).approvedSetting("rx02.approved_channels");
        verify(audit).append(any(), any(), eq(RxActions.READ), any(), any(), eq(ID), eq("SUCCESS"));
    }

    @Test
    void refusesAnUnapprovedChannel() {
        authorized();
        entitled();
        channels("PRINTABLE_LOCAL");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(attested()));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 2));

        assertEquals(422, failure.status());
        assertEquals("UNAPPROVED_CHANNEL", failure.code());
        verify(repository, never()).insertSubmission(anyString(), anyString(), anyString());
        verify(repository, never()).transmit(anyString(), anyInt(), anyString(), anyString(), any(State.class));
    }

    @Test
    void refusesAWrongPrescriber() {
        authorized();
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(attestedOwnedBy(OTHER_PRESCRIBER)));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code(), "only the prescribing clinician may route");
        verify(repository, never()).insertSubmission(anyString(), anyString(), anyString());
    }

    @Test
    void refusesACallerWithoutAnActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), any(), eq(RxActions.TRANSMIT), any(), any(), eq(ID), eq("DENIED"));
        verify(repository, never()).loadForUpdate(anyString());
    }

    @Test
    void refusesACallerWithoutTheTransmitRole() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), any(), eq(RxActions.TRANSMIT), any(), any(), eq(ID), eq("DENIED"));
        verify(repository, never()).loadForUpdate(anyString());
    }

    @Test
    void refusesAStaleVersion() {
        authorized();
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(attested()));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 7));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(repository, never()).transmit(anyString(), anyInt(), anyString(), anyString(), any(State.class));
    }

    @Test
    void refusesADraftThatWasNeverAttested() {
        authorized();
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(draft()));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.transmit(ID, new TransmitRequest("E_PRESCRIBE", "submitted"), 2));

        assertEquals(409, failure.status());
        assertEquals("INVALID_TRANSITION", failure.code());
        verify(repository, never()).transmit(anyString(), anyInt(), anyString(), anyString(), any(State.class));
    }

    @Test
    void refusesPrintWithoutAttestation() {
        authorized();
        entitled();
        channels("E_PRESCRIBE,PRINTABLE_LOCAL");
        when(repository.find(ID)).thenReturn(Optional.of(printed()));
        when(repository.events(ID)).thenReturn(List.of());

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(409, failure.status());
        assertEquals("ATTESTATION_REQUIRED", failure.code());
    }

    private void approvedElectronicSetup() {
        authorized();
        entitled();
        channels("E_PRESCRIBE,PRINTABLE_LOCAL");
        when(repository.loadForUpdate(ID)).thenReturn(Optional.of(attested()));
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
    }

    private void entitled() {
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(repository.approvedSetting("rx02.entitlement." + ACTOR)).thenReturn(Optional.of("enabled"));
    }

    private void channels(String value) {
        when(repository.approvedSetting("rx02.approved_channels")).thenReturn(Optional.of(value));
    }

    private static Prescription attested() {
        return attestedOwnedBy(ACTOR);
    }

    private static Prescription attestedOwnedBy(String prescriber) {
        return new Prescription(ID, 2, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, prescriber, "pharmacy-x", State.ATTESTED, null, null, false, CREATED_AT);
    }

    private static Prescription draft() {
        return new Prescription(ID, 2, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, ACTOR, "pharmacy-x", State.DRAFT, null, null, false, CREATED_AT);
    }

    private static Prescription printed() {
        return new Prescription(ID, 3, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, ACTOR, "pharmacy-x", State.PRINTED,
                RxChannel.PRINTABLE_LOCAL.wire(), null, false, CREATED_AT);
    }

    private static Prescription submitted(String submissionId) {
        return new Prescription(ID, 3, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, ACTOR, "pharmacy-x", State.SUBMITTED,
                RxChannel.E_PRESCRIBE.wire(), submissionId, false, CREATED_AT);
    }

    private static Event attestation() {
        return new Event("cccccccc-0000-0000-0000-000000000001", ID, 2, EventType.ATTESTED,
                ACTOR, null, null, "market=US", "synthetic attestation", ATTESTED_AT);
    }
}
