package com.aurevia.acceptance.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import org.mockito.ArgumentCaptor;

import com.aurevia.messaging.appointments.AppointmentStatus;
import com.aurevia.messaging.appointments.AuditSink;
import com.aurevia.messaging.appointments.DeviceState;
import com.aurevia.messaging.appointments.DeviceToken;
import com.aurevia.messaging.appointments.DeviceTokenStore;
import com.aurevia.messaging.appointments.GenericPushNotice;
import com.aurevia.messaging.appointments.PatientAppointment;
import com.aurevia.messaging.appointments.PatientAppointmentService;
import com.aurevia.messaging.appointments.PatientAppointmentStore;
import com.aurevia.messaging.appointments.PushGateway;
import com.aurevia.messaging.appointments.VerifiedPatientLink;

/**
 * Gate G3 acceptance for patient notifications.
 *
 * <p>Requirements proved: PAT-01 (push notifications carry generic text only;
 * the app retrieves detail after authentication, invalid tokens are logged and
 * devices can be revoked) and OPS-02 (every dispatch decision is audited). All
 * fixtures are synthetic.</p>
 */
class PatientNotificationAcceptanceTest {

    private static final String SUBJECT = "syn-patient-notifications";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String APPOINTMENT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String TOKEN_ID = "device-1";
    private static final String TOKEN_VALUE = "provider-token-1";
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private VerifiedPatientLink links;
    private PatientAppointmentStore appointments;
    private DeviceTokenStore devices;
    private PushGateway gateway;
    private AuditSink audit;
    private PatientAppointmentService service;

    @BeforeEach
    void setUp() {
        links = mock(VerifiedPatientLink.class);
        appointments = mock(PatientAppointmentStore.class);
        devices = mock(DeviceTokenStore.class);
        gateway = mock(PushGateway.class);
        audit = mock(AuditSink.class);
        service = new PatientAppointmentService(links, appointments, devices, gateway, audit);
        when(links.allows(SUBJECT, PATIENT)).thenReturn(true);
        when(appointments.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()));
        when(gateway.send(anyString(), any(GenericPushNotice.class))).thenReturn(PushGateway.Outcome.DELIVERED);
    }

    @Test
    void statusChangeNotificationCarriesNoClinicalText() {
        // PAT-01: the status is recorded in the audit key but no patient,
        // facility, clinician or time detail enters the push payload.
        DeviceToken token = new DeviceToken(TOKEN_ID, TOKEN_VALUE, SUBJECT, PATIENT, DeviceState.ACTIVE);
        when(devices.active(PATIENT)).thenReturn(List.of(token));

        service.notifyStatusChange(SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CHECKED_IN, NOW);

        ArgumentCaptor<GenericPushNotice> captured = ArgumentCaptor.forClass(GenericPushNotice.class);
        verify(gateway).send(eq(TOKEN_VALUE), captured.capture());
        GenericPushNotice notice = captured.getValue();
        assertEquals(GenericPushNotice.appointmentUpdate(), notice);
        for (String detail : List.of(PATIENT, "Synthetic clinic", "checked_in", "Dr Synthetic", "10:30")) {
            assertFalse(notice.reveals(detail), "notification must not reveal " + detail);
        }
        verify(audit).append(eq(SUBJECT), eq(PATIENT), eq("PATIENT_NOTIFY_STATUS_CHANGE"), eq("SUCCESS"), eq(NOW));
    }

    @Test
    void revokedDeviceReceivesNoFurtherPush() {
        // PAT-01: revocation is re-read at dispatch time, so a previously
        // registered device stops receiving messages immediately.
        DeviceToken active = new DeviceToken(TOKEN_ID, TOKEN_VALUE, SUBJECT, PATIENT, DeviceState.ACTIVE);
        when(devices.find(TOKEN_ID, PATIENT)).thenReturn(Optional.of(active));

        service.revokeDevice(SUBJECT, PATIENT, TOKEN_ID, NOW);
        verify(devices).save(active.revoke());

        when(devices.active(PATIENT)).thenReturn(List.of(active.revoke()));
        service.notifyStatusChange(SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CHECKED_IN, NOW);
        verify(gateway, never()).send(anyString(), any(GenericPushNotice.class));
    }

    @Test
    void invalidTokenIsLoggedWithoutExposingTheProviderToken() {
        // PAT-01 / OPS-02: the provider disposition invalidates the device and
        // records only the opaque id and a non-sensitive reason code.
        DeviceToken active = new DeviceToken(TOKEN_ID, TOKEN_VALUE, SUBJECT, PATIENT, DeviceState.ACTIVE);
        when(devices.active(PATIENT)).thenReturn(List.of(active));
        when(gateway.send(eq(TOKEN_VALUE), any(GenericPushNotice.class)))
                .thenReturn(PushGateway.Outcome.INVALID_TOKEN);

        service.notifyStatusChange(SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CHECKED_IN, NOW);

        verify(devices).save(active.invalidate());
        verify(devices).appendInvalidTokenLog(TOKEN_ID, PATIENT, "INVALID_TOKEN", NOW);
        verify(audit).append(eq(SUBJECT), eq(PATIENT),
                eq("PUSH_NOTIFY_CHECKED_IN_INVALID_TOKEN"), eq("SUCCESS"), eq(NOW));
    }

    private static PatientAppointment appointment() {
        return new PatientAppointment(APPOINTMENT, 1, PATIENT, "Synthetic clinic", "America/New_York",
                NOW.plusSeconds(3600), AppointmentStatus.BOOKED, true, null);
    }
}
