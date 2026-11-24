package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** S-G3-01: eligibility and immutable confirmation of a patient appointment (PAT-01). */
class PatientAppointmentTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private static PatientAppointment appointment(boolean enabled, AppointmentStatus status,
                                                  Instant confirmedAt) {
        return new PatientAppointment("appt-1", 1, "patient-1", "MAIN", "Asia/Riyadh",
                Instant.parse("2026-12-01T07:00:00Z"), status, enabled, confirmedAt);
    }

    @Test
    void bookedEnabledAndUnconfirmedIsConfirmable() {
        assertTrue(appointment(true, AppointmentStatus.BOOKED, null).confirmable());
    }

    @Test
    void disabledConfirmationIsNotConfirmable() {
        assertFalse(appointment(false, AppointmentStatus.BOOKED, null).confirmable());
    }

    @Test
    void nonBookedStatusIsNotConfirmableEvenWhenEnabled() {
        assertFalse(appointment(true, AppointmentStatus.CHECKED_IN, null).confirmable());
    }

    @Test
    void alreadyConfirmedAppointmentIsNotConfirmableAgain() {
        assertFalse(appointment(true, AppointmentStatus.BOOKED, NOW).confirmable());
    }

    @Test
    void confirmedReturnsANewRevisionWithTheTimestamp() {
        PatientAppointment original = appointment(true, AppointmentStatus.BOOKED, null);
        PatientAppointment confirmed = original.confirmed(NOW, 2);
        assertEquals(NOW, confirmed.confirmedAt());
        assertEquals(2, confirmed.version());
        assertEquals("appt-1", confirmed.id());
        assertEquals("patient-1", confirmed.patientId());
        assertEquals("MAIN", confirmed.facilityCode());
        assertEquals("Asia/Riyadh", confirmed.facilityTimeZone());
        assertEquals(original.startsAt(), confirmed.startsAt());
        assertEquals(AppointmentStatus.BOOKED, confirmed.status());
        assertTrue(original.confirmedAt() == null);
    }
}
