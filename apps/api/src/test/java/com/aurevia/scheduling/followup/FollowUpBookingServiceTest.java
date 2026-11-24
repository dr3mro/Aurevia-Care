package com.aurevia.scheduling.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.BookingRequest;
import com.aurevia.scheduling.ResourceView;
import com.aurevia.scheduling.SchedulingRepository;

/**
 * CLN-06 booking adapter: confirmation delegates to the shared scheduling service so
 * protected-slot and availability rules are not duplicated, and the civil date is
 * interpreted in the facility zone rather than shifted by a DST transition.
 */
class FollowUpBookingServiceTest {

    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private AppointmentService appointments;
    private SchedulingRepository scheduling;
    private FollowUpBookingService booking;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentService.class);
        scheduling = mock(SchedulingRepository.class);
        booking = new FollowUpBookingService(appointments, scheduling);
    }

    @Test
    void booksTheSuggestedCivilDateAtLocalNineInTheFacilityZone() {
        when(scheduling.resources("CLINIC")).thenReturn(List.of(
                new ResourceView("room-id", "CLINIC", "room", "ROOM", "Room"),
                new ResourceView("clinician-id", "CLINIC", "clinician", "DR-A", "Doctor")));
        when(appointments.book(org.mockito.ArgumentMatchers.any(BookingRequest.class)))
                .thenReturn(new Appointment("booking-1", 1, "patient", "CLINIC", "DR-A", "ROOM",
                        "FOLLOW", Instant.parse("2026-03-20T13:00:00Z"),
                        Instant.parse("2026-03-20T13:15:00Z"),
                        com.aurevia.scheduling.AppointmentState.BOOKED,
                        null, null, null));

        BookingIntent intent = new BookingIntent(ID, "org", "patient", "CLINIC", "FOLLOW",
                LocalDate.of(2026, 3, 20), "America/New_York");
        assertEquals("booking-1", booking.book(intent));

        // 09:00 on 2026-03-20 in New York is 13:00Z (EDT, after the spring-forward).
        BookingRequest expected = new BookingRequest("CLINIC", "patient", "FOLLOW", "DR-A", null,
                "2026-03-20T13:00:00Z", "follow-up confirmation");
        verify(appointments).book(expected);
    }

    @Test
    void failsClosedWhenTheFacilityHasNoClinician() {
        when(scheduling.resources("CLINIC")).thenReturn(List.of(
                new ResourceView("room-id", "CLINIC", "room", "ROOM", "Room")));
        BookingIntent intent = new BookingIntent(ID, "org", "patient", "CLINIC", "FOLLOW",
                LocalDate.of(2026, 3, 20), "America/New_York");
        assertEquals(422, assertThrows(ApiException.class, () -> booking.book(intent)).status());
    }
}
