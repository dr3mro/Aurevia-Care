package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Legacy compatibility seam: every stateless port denies or stays empty without holding data. */
class FailClosedPatientAppointmentPortsTest {

    @Test
    void linksNeverGrantAccess() {
        assertFalse(FailClosedPatientAppointmentPorts.DenyAllLinks.INSTANCE
                .allows("patient-a", "patient-1"));
    }

    @Test
    void appointmentPortReturnsNoFactsAndNoConfirmation() {
        PatientAppointmentStore store = FailClosedPatientAppointmentPorts.EmptyAppointments.INSTANCE;
        assertTrue(store.forPatient("patient-1").isEmpty());
        assertTrue(store.find("appt-1", "patient-1").isEmpty());
        assertEquals(0, store.confirm("appt-1", "patient-1", 1, Instant.EPOCH));
    }

    @Test
    void devicePortReturnsNoRegistrationsAndRecordsNothing() {
        DeviceTokenStore store = FailClosedPatientAppointmentPorts.EmptyDevices.INSTANCE;
        store.save(new DeviceToken("device-1", "token", "patient-a", "patient-1", DeviceState.ACTIVE));
        assertTrue(store.find("device-1", "patient-1").isEmpty());
        assertTrue(store.findByTokenId("device-1").isEmpty());
        assertTrue(store.active("patient-1").isEmpty());
        store.appendInvalidTokenLog("device-1", "patient-1", "INVALID_TOKEN", Instant.EPOCH);
    }

    @Test
    void gatewayAndAuditAreInert() {
        assertEquals(PushGateway.Outcome.DELIVERED,
                FailClosedPatientAppointmentPorts.InertGateway.INSTANCE.send("token",
                        GenericPushNotice.appointmentUpdate()));
        FailClosedPatientAppointmentPorts.InertAudit.INSTANCE.append("patient-a", "patient-1",
                "DEVICE_REGISTER", "SUCCESS", Instant.EPOCH);
    }

    @Test
    void deniedServiceRefusesEveryCommandBeforeAnyRead() {
        PatientAppointmentService service = FailClosedPatientAppointmentPorts.deniedService();
        ApiException denied = assertThrows(ApiException.class,
                () -> service.listAppointments("patient-a", "patient-1", Instant.EPOCH));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
    }
}
