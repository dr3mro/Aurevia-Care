package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G3-01: appointment status wire form and patient-confirm eligibility (PAT-01). */
class AppointmentStatusTest {

    @Test
    void wireFormIsLowerCaseForEveryStatus() {
        assertEquals("booked", AppointmentStatus.BOOKED.wire());
        assertEquals("checked_in", AppointmentStatus.CHECKED_IN.wire());
        assertEquals("in_progress", AppointmentStatus.IN_PROGRESS.wire());
        assertEquals("completed", AppointmentStatus.COMPLETED.wire());
        assertEquals("cancelled", AppointmentStatus.CANCELLED.wire());
        assertEquals("no_show", AppointmentStatus.NO_SHOW.wire());
    }

    @Test
    void onlyBookedCanBeConfirmedByThePatient() {
        assertTrue(AppointmentStatus.BOOKED.patientCanConfirm());
        assertFalse(AppointmentStatus.CHECKED_IN.patientCanConfirm());
        assertFalse(AppointmentStatus.IN_PROGRESS.patientCanConfirm());
        assertFalse(AppointmentStatus.COMPLETED.patientCanConfirm());
        assertFalse(AppointmentStatus.CANCELLED.patientCanConfirm());
        assertFalse(AppointmentStatus.NO_SHOW.patientCanConfirm());
    }

    @Test
    void terminalAndParsingAreConsistent() {
        assertTrue(AppointmentStatus.COMPLETED.terminal());
        assertTrue(AppointmentStatus.CANCELLED.terminal());
        assertTrue(AppointmentStatus.NO_SHOW.terminal());
        assertFalse(AppointmentStatus.BOOKED.terminal());
        assertFalse(AppointmentStatus.CHECKED_IN.terminal());
        assertFalse(AppointmentStatus.IN_PROGRESS.terminal());
        assertEquals(AppointmentStatus.BOOKED, AppointmentStatus.from("booked"));
        assertEquals(AppointmentStatus.NO_SHOW, AppointmentStatus.from("NO_SHOW"));
    }
}
