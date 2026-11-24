package com.aurevia.prescription.rx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.aurevia.prescription.rx.RxContract.Event;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.tenant.TenantContext;

/** BUILD-10 (RX-02): site policy gates the printable prescription at the service boundary. */
class RxPrintableServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-1";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";
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
        TenantContext.set(new TenantContext(ORG, ACTOR, "PRESCRIPTION_TRANSMISSION", "corr-1"));
        when(repository.find(ID)).thenReturn(Optional.of(printed()));
        when(repository.events(ID)).thenReturn(List.of(attestation()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void refusesWithoutAnActiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("DENIED"));
    }

    @Test
    void refusesWithoutAReadingPermission() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of());

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq("DENIED"));
    }

    @Test
    void concealsAPrescriptionThatIsNotVisible() {
        authorized();
        when(repository.find(ID)).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        verify(repository, never()).events(any());
    }

    @Test
    void refusesWhenNoMarketPolicyHasBeenApproved() {
        authorized();
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(422, failure.status());
        assertEquals("NO_MARKET_POLICY", failure.code());
        verify(repository, never()).events(any());
    }

    @Test
    void refusesWhenThePrescriberIsNotEntitled() {
        authorized();
        entitled("disabled");

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(422, failure.status());
        assertEquals("NO_ENTITLEMENT", failure.code());
        verify(repository, never()).events(any());
    }

    @Test
    void refusesWhenThePrescriberHasNoEntitlementSetting() {
        authorized();
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(repository.approvedSetting("rx02.entitlement." + ACTOR)).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(422, failure.status());
        assertEquals("NO_ENTITLEMENT", failure.code());
    }

    @Test
    void refusesWhenNoChannelPolicyHasBeenApproved() {
        authorized();
        entitled("enabled");
        when(repository.approvedSetting("rx02.approved_channels")).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(422, failure.status());
        assertEquals("NO_CHANNEL_POLICY", failure.code());
    }

    @Test
    void refusesWhenTheLocalPrintChannelIsNotApproved() {
        authorized();
        entitled("enabled");
        channels("E_PRESCRIBE");

        ApiException failure = assertThrows(ApiException.class, () -> service.printable(ID));

        assertEquals(422, failure.status());
        assertEquals("UNAPPROVED_CHANNEL", failure.code());
        verify(repository, never()).events(any());
    }

    @Test
    void issuesTheDocumentWhenEntitlementAndLocalPrintAreApproved() {
        authorized();
        entitled("enabled");
        channels("E_PRESCRIBE, PRINTABLE_LOCAL");

        PrintablePrescription document = service.printable(ID);

        assertEquals(ID, document.prescriptionId());
        assertEquals("PRINTABLE_LOCAL", document.channel(), "the channel is recorded exactly as used");
        assertEquals("synthetic attestation", document.attestationNote());
        assertEquals(ATTESTED_AT, document.attestedAt());
        verify(audit).append(any(), any(), org.mockito.ArgumentMatchers.eq(RxActions.READ), any(), any(),
                org.mockito.ArgumentMatchers.eq(ID), org.mockito.ArgumentMatchers.eq("SUCCESS"));
    }

    private void authorized() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of("clinician"));
    }

    private void entitled(String status) {
        when(repository.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(repository.approvedSetting("rx02.entitlement." + ACTOR)).thenReturn(Optional.of(status));
    }

    private void channels(String value) {
        when(repository.approvedSetting("rx02.approved_channels")).thenReturn(Optional.of(value));
    }

    private static Prescription printed() {
        return new Prescription(ID, 3, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, ACTOR, "pharmacy-x", State.PRINTED,
                RxChannel.PRINTABLE_LOCAL.wire(), null, false, Instant.parse("2026-11-20T10:00:00Z"));
    }

    private static Event attestation() {
        return new Event("cccccccc-0000-0000-0000-000000000001", ID, 2, EventType.ATTESTED,
                ACTOR, null, null, "market=US", "synthetic attestation", ATTESTED_AT);
    }
}
