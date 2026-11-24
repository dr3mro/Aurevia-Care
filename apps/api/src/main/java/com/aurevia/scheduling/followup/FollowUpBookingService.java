package com.aurevia.scheduling.followup;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.BookingRequest;
import com.aurevia.scheduling.ResourceView;
import com.aurevia.scheduling.SchedulingRepository;

/**
 * Booking boundary used only by the explicit follow-up confirmation (CLN-06).
 *
 * <p>The domain chooses the civil date; this adapter chooses the first available
 * clinician in the facility and interprets the date at the fixed morning slot in
 * the facility's zone, then delegates to the shared scheduling service so
 * protected-slot and availability rules are never duplicated here. A reason is
 * recorded on the booking so an out-of-hours slot is an explicit, audited choice.</p>
 */
@Component
public class FollowUpBookingService implements FollowUpBooking {

    /** Fixed local slot for a suggested follow-up visit. */
    public static final LocalTime SLOT_TIME = LocalTime.of(9, 0);
    /** Provenance stamped on the appointment when confirmation books a follow-up. */
    public static final String BOOKING_REASON = "follow-up confirmation";

    private final AppointmentService appointments;
    private final SchedulingRepository scheduling;

    /**
     * Create the adapter.
     *
     * @param appointments shared owner of the appointment booking invariants
     * @param scheduling   tenant scheduling reference data
     */
    public FollowUpBookingService(AppointmentService appointments, SchedulingRepository scheduling) {
        this.appointments = appointments;
        this.scheduling = scheduling;
    }

    @Override
    public String book(BookingIntent intent) {
        ResourceView clinician = scheduling.resources(intent.facilityCode()).stream()
                .filter(resource -> "clinician".equals(resource.kind()))
                .findFirst()
                .orElseThrow(() -> new ApiException(422, "NO_CLINICIAN",
                        "the facility has no clinician available for a follow-up"));
        Instant startsAt = intent.followUpDate().atTime(SLOT_TIME)
                .atZone(ZoneId.of(intent.timeZone())).toInstant();
        Appointment booked = appointments.book(new BookingRequest(
                intent.facilityCode(), intent.patientId(), intent.visitType(),
                clinician.code(), null, startsAt.toString(), BOOKING_REASON));
        return booked.id();
    }
}
