package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** S-G3-01 service branches: patient self-access, confirmation, generic push and devices (PAT-01). */
class PatientAppointmentServiceTest {

    private static final String SUBJECT = "patient-user-a";
    private static final String PATIENT = "patient-1";
    private static final String APPOINTMENT = "appt-1";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private VerifiedPatientLink links;
    private PatientAppointmentStore store;
    private DeviceTokenStore devices;
    private PushGateway gateway;
    private AuditSink audit;
    private PatientAppointmentService service;

    @BeforeEach
    void setUp() {
        links = mock(VerifiedPatientLink.class);
        store = mock(PatientAppointmentStore.class);
        devices = mock(DeviceTokenStore.class);
        gateway = mock(PushGateway.class);
        audit = mock(AuditSink.class);
        service = new PatientAppointmentService(links, store, devices, gateway, audit);
    }

    private void linked() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(true);
    }

    private static PatientAppointment appointment() {
        return new PatientAppointment(APPOINTMENT, 1, PATIENT, "MAIN", "Asia/Riyadh",
                Instant.parse("2026-12-01T07:00:00Z"), AppointmentStatus.BOOKED, true, null);
    }

    private static DeviceToken activeToken() {
        return new DeviceToken("dev-1", "synthetic-token", SUBJECT, PATIENT, DeviceState.ACTIVE);
    }

    // --- list: authorization and tenant/patient scoping ---

    @Test
    void listsOnlyTheVerifiedPatientsAppointments() {
        linked();
        when(store.forPatient(PATIENT)).thenReturn(List.of(appointment()));
        List<PatientAppointment> result = service.listAppointments(SUBJECT, PATIENT, NOW);
        assertEquals(1, result.size());
        assertEquals("Asia/Riyadh", result.get(0).facilityTimeZone());
        assertEquals(AppointmentStatus.BOOKED, result.get(0).status());
        assertThrows(UnsupportedOperationException.class, () -> result.add(appointment()));
        verify(store).forPatient(PATIENT);
        verify(audit).append(SUBJECT, PATIENT, "PATIENT_APPOINTMENT_LIST", "SUCCESS", NOW);
    }

    @Test
    void listWithNoAppointmentsStillAuditsSuccess() {
        linked();
        when(store.forPatient(PATIENT)).thenReturn(List.of());
        assertTrue(service.listAppointments(SUBJECT, PATIENT, NOW).isEmpty());
        verify(audit).append(SUBJECT, PATIENT, "PATIENT_APPOINTMENT_LIST", "SUCCESS", NOW);
    }

    @Test
    void unlinkedSubjectIsDeniedBeforeAnyRead() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(false);
        ApiException error = assertThrows(ApiException.class,
                () -> service.listAppointments(SUBJECT, PATIENT, NOW));
        assertEquals(403, error.status());
        assertEquals("FORBIDDEN", error.code());
        verify(store, never()).forPatient(any());
        verify(audit).append(SUBJECT, PATIENT, "PATIENT_APPOINTMENT_LIST", "DENIED", NOW);
    }

    @Test
    void nullSubjectIsDenied() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.listAppointments(null, PATIENT, NOW));
        assertEquals(403, error.status());
        verify(links, never()).allows(any(), any());
    }

    @Test
    void blankSubjectIsDenied() {
        assertThrows(ApiException.class, () -> service.listAppointments("  ", PATIENT, NOW));
    }

    @Test
    void nullPatientIsDenied() {
        assertThrows(ApiException.class, () -> service.listAppointments(SUBJECT, null, NOW));
    }

    @Test
    void blankPatientIsDenied() {
        assertThrows(ApiException.class, () -> service.listAppointments(SUBJECT, "", NOW));
    }

    // --- confirmation ---

    @Test
    void confirmRecordsTimestampAndReturnsTheNewRevision() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()),
                Optional.of(appointment().confirmed(NOW, 2)));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        when(devices.active(PATIENT)).thenReturn(List.of());
        PatientAppointment result = service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW);
        assertEquals(NOW, result.confirmedAt());
        assertEquals(2, result.version());
        verify(store).confirm(APPOINTMENT, PATIENT, 1, NOW);
        verify(audit).append(SUBJECT, PATIENT, "PATIENT_APPOINTMENT_CONFIRM", "SUCCESS", NOW);
    }

    @Test
    void confirmConcealsAnAppointmentOutsideTheVerifiedLink() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.empty());
        ApiException error = assertThrows(ApiException.class,
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW));
        assertEquals(404, error.status());
        assertEquals("NOT_FOUND", error.code());
        verify(store, never()).confirm(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void confirmRejectsDisabledConfirmation() {
        linked();
        PatientAppointment disabled = new PatientAppointment(APPOINTMENT, 1, PATIENT, "MAIN", "UTC",
                NOW, AppointmentStatus.BOOKED, false, null);
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(disabled));
        assertThrowsApi(422, "NOT_CONFIRMABLE",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW));
    }

    @Test
    void confirmRejectsAnAppointmentPastTheConfirmableStatus() {
        linked();
        PatientAppointment checkedIn = new PatientAppointment(APPOINTMENT, 1, PATIENT, "MAIN", "UTC",
                NOW, AppointmentStatus.CHECKED_IN, true, null);
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(checkedIn));
        assertThrowsApi(422, "NOT_CONFIRMABLE",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW));
    }

    @Test
    void confirmRejectsAnAlreadyConfirmedAppointment() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment().confirmed(NOW, 2)));
        assertThrowsApi(422, "NOT_CONFIRMABLE",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 2, NOW));
    }

    @Test
    void confirmRejectsAStaleVersionBeforeWriting() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()));
        assertThrowsApi(412, "STALE_VERSION",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 7, NOW));
        verify(store, never()).confirm(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void confirmTreatsAConcurrentWriteAsStale() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(0);
        assertThrowsApi(412, "STALE_VERSION",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW));
    }

    @Test
    void confirmConcealsAnAppointmentThatDisappearsBeforeTheResponse() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()), Optional.empty());
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        when(devices.active(PATIENT)).thenReturn(List.of());
        assertThrowsApi(404, "NOT_FOUND",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW));
    }

    @Test
    void confirmDispatchesOnlyGenericTextToActiveTokens() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()),
                Optional.of(appointment().confirmed(NOW, 2)));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        when(devices.active(PATIENT)).thenReturn(List.of(activeToken()));
        when(gateway.send(any(), any())).thenReturn(PushGateway.Outcome.DELIVERED);
        service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW);
        verify(gateway).send(eq("synthetic-token"), any(GenericPushNotice.class));
        verify(audit).append(SUBJECT, PATIENT, "PUSH_CONFIRM_SENT", "SUCCESS", NOW);
        verify(devices, never()).save(any());
    }

    @Test
    void confirmInvalidatesAndLogsARejectedToken() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()),
                Optional.of(appointment().confirmed(NOW, 2)));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        when(devices.active(PATIENT)).thenReturn(List.of(activeToken()));
        when(gateway.send(any(), any())).thenReturn(PushGateway.Outcome.INVALID_TOKEN);
        service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW);
        verify(devices).save(activeToken().invalidate());
        verify(devices).appendInvalidTokenLog("dev-1", PATIENT, "INVALID_TOKEN", NOW);
        verify(audit).append(SUBJECT, PATIENT, "PUSH_CONFIRM_INVALID_TOKEN", "SUCCESS", NOW);
    }

    @Test
    void dispatchSkipsANonDeliverableTokenReturnedByTheStore() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()),
                Optional.of(appointment().confirmed(NOW, 2)));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        when(devices.active(PATIENT)).thenReturn(List.of(activeToken().revoke()));
        service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW);
        verify(gateway, never()).send(any(), any());
        verify(devices, never()).save(any());
    }

    // --- status-change notification ---

    @Test
    void statusChangeConcealsAnUnknownAppointment() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.empty());
        assertThrowsApi(404, "NOT_FOUND", () -> service.notifyStatusChange(
                SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CANCELLED, NOW));
        verify(gateway, never()).send(any(), any());
    }

    @Test
    void statusChangeNotifiesWithGenericTextAndAudits() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()));
        when(devices.active(PATIENT)).thenReturn(List.of(activeToken()));
        when(gateway.send(any(), any())).thenReturn(PushGateway.Outcome.DELIVERED);
        service.notifyStatusChange(SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CHECKED_IN, NOW);
        verify(gateway).send(eq("synthetic-token"), any(GenericPushNotice.class));
        verify(audit).append(SUBJECT, PATIENT, "PUSH_NOTIFY_CHECKED_IN_SENT", "SUCCESS", NOW);
        verify(audit).append(SUBJECT, PATIENT, "PATIENT_NOTIFY_STATUS_CHANGE", "SUCCESS", NOW);
    }

    // --- devices ---

    @Test
    void registerStoresAnActiveToken() {
        linked();
        service.registerDevice(SUBJECT, PATIENT, "dev-1", "synthetic-token", NOW);
        verify(devices).save(activeToken());
        verify(audit).append(SUBJECT, PATIENT, "DEVICE_REGISTER", "SUCCESS", NOW);
    }

    @Test
    void registerRejectsBlankIdentifiers() {
        linked();
        assertThrowsApi(422, "INVALID_DEVICE",
                () -> service.registerDevice(SUBJECT, PATIENT, " ", "synthetic-token", NOW));
        assertThrowsApi(422, "INVALID_DEVICE",
                () -> service.registerDevice(SUBJECT, PATIENT, "dev-1", " ", NOW));
        verify(devices, never()).save(any());
    }

    @Test
    void revokeMarksAnActiveTokenRevoked() {
        linked();
        when(devices.find("dev-1", PATIENT)).thenReturn(Optional.of(activeToken()));
        service.revokeDevice(SUBJECT, PATIENT, "dev-1", NOW);
        verify(devices).save(activeToken().revoke());
        verify(audit).append(SUBJECT, PATIENT, "DEVICE_REVOKE", "SUCCESS", NOW);
    }

    @Test
    void revokeIsIdempotentForAnAlreadyRevokedToken() {
        linked();
        when(devices.find("dev-1", PATIENT)).thenReturn(Optional.of(activeToken().revoke()));
        service.revokeDevice(SUBJECT, PATIENT, "dev-1", NOW);
        verify(devices, never()).save(any());
    }

    @Test
    void revokeConcealsAForeignToken() {
        linked();
        when(devices.find("dev-1", PATIENT)).thenReturn(Optional.empty());
        assertThrowsApi(404, "NOT_FOUND",
                () -> service.revokeDevice(SUBJECT, PATIENT, "dev-1", NOW));
    }

    @Test
    void invalidTokenResponseIsLoggedAndTokenMarkedInvalid() {
        linked();
        when(devices.find("dev-1", PATIENT)).thenReturn(Optional.of(activeToken()));
        service.recordInvalidToken(SUBJECT, PATIENT, "dev-1", "provider-rejected", NOW);
        verify(devices).save(activeToken().invalidate());
        verify(devices).appendInvalidTokenLog("dev-1", PATIENT, "provider-rejected", NOW);
        verify(audit).append(SUBJECT, PATIENT, "DEVICE_INVALID_TOKEN", "SUCCESS", NOW);
    }

    @Test
    void invalidTokenConcealsAForeignToken() {
        linked();
        when(devices.find("dev-1", PATIENT)).thenReturn(Optional.empty());
        assertThrowsApi(404, "NOT_FOUND",
                () -> service.recordInvalidToken(SUBJECT, PATIENT, "dev-1", "reason", NOW));
    }

    @Test
    void unlinkedSubjectCannotRegisterOrRevokeADevice() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(false);
        assertThrowsApi(403, "FORBIDDEN",
                () -> service.registerDevice(SUBJECT, PATIENT, "dev-1", "synthetic-token", NOW));
        assertThrowsApi(403, "FORBIDDEN",
                () -> service.revokeDevice(SUBJECT, PATIENT, "dev-1", NOW));
        verify(devices, never()).save(any());
    }

    // --- fail-closed link recheck on every command (ARC-02) ---

    @Test
    void confirmDeniesAnUnlinkedSubject() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(false);
        assertThrowsApi(403, "FORBIDDEN",
                () -> service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW));
        verify(store, never()).find(any(), any());
        verify(store, never()).confirm(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(gateway, never()).send(any(), any());
    }

    @Test
    void statusChangeDeniesAnUnlinkedSubject() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(false);
        assertThrowsApi(403, "FORBIDDEN", () -> service.notifyStatusChange(
                SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CHECKED_IN, NOW));
        verify(store, never()).find(any(), any());
        verify(gateway, never()).send(any(), any());
    }

    @Test
    void recordInvalidTokenDeniesAnUnlinkedSubject() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(false);
        assertThrowsApi(403, "FORBIDDEN",
                () -> service.recordInvalidToken(SUBJECT, PATIENT, "dev-1", "provider-rejected", NOW));
        verify(devices, never()).save(any());
        verify(devices, never()).appendInvalidTokenLog(any(), any(), any(), any());
    }

    // --- audit attribution and idempotency ---

    @Test
    void pushAuditNamesTheActingSubjectNotTheDeviceOwner() {
        linked();
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()),
                Optional.of(appointment().confirmed(NOW, 2)));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        DeviceToken otherOwner = new DeviceToken("dev-1", "synthetic-token", "other-subject",
                PATIENT, DeviceState.ACTIVE);
        when(devices.active(PATIENT)).thenReturn(List.of(otherOwner));
        when(gateway.send(any(), any())).thenReturn(PushGateway.Outcome.DELIVERED);
        service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW);
        verify(audit).append(SUBJECT, PATIENT, "PUSH_CONFIRM_SENT", "SUCCESS", NOW);
        verify(audit, never()).append(eq("other-subject"), any(), any(), any(), any());
    }

    @Test
    void recordInvalidTokenDoesNotLogADuplicateDisposition() {
        linked();
        when(devices.find("dev-1", PATIENT)).thenReturn(Optional.of(activeToken().invalidate()));
        service.recordInvalidToken(SUBJECT, PATIENT, "dev-1", "provider-rejected", NOW);
        verify(devices, never()).save(any());
        verify(devices, never()).appendInvalidTokenLog(any(), any(), any(), any());
        verify(audit).append(SUBJECT, PATIENT, "DEVICE_INVALID_TOKEN", "SUCCESS", NOW);
    }

    // --- device registration must not rebind another patient's id (IAM-05) ---

    @Test
    void registerAllowsReRegisteringTheSamePatientsDeviceId() {
        linked();
        DeviceToken own = new DeviceToken("dev-1", "old-token", SUBJECT, PATIENT, DeviceState.ACTIVE);
        when(devices.findByTokenId("dev-1")).thenReturn(Optional.of(own));
        service.registerDevice(SUBJECT, PATIENT, "dev-1", "synthetic-token", NOW);
        verify(devices).save(new DeviceToken("dev-1", "synthetic-token", SUBJECT, PATIENT,
                DeviceState.ACTIVE));
    }

    @Test
    void registerRejectsADeviceIdOwnedByAnotherPatient() {
        linked();
        DeviceToken foreign = new DeviceToken("dev-1", "synthetic-token", "other-subject",
                "patient-2", DeviceState.ACTIVE);
        when(devices.findByTokenId("dev-1")).thenReturn(Optional.of(foreign));
        assertThrowsApi(409, "DEVICE_CONFLICT",
                () -> service.registerDevice(SUBJECT, PATIENT, "dev-1", "synthetic-token", NOW));
        verify(devices, never()).save(any());
    }

    private static void assertThrowsApi(int status, String code, Runnable action) {
        ApiException error = assertThrows(ApiException.class, action::run);
        assertEquals(status, error.status());
        assertEquals(code, error.code());
    }
}
