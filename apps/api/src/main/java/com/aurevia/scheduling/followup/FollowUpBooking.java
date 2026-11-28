package com.aurevia.scheduling.followup;

/**
 * Booking boundary used only by the explicit confirm command.
 *
 * <p>The adapter delegates to the scheduling service so protected-slot and
 * availability rules are not duplicated here.</p>
 */
public interface FollowUpBooking {

    /**
     * Create the appointment for a confirmed follow-up.
     *
     * @param intent bounded booking details
     * @return the created appointment identifier
     */
    String book(BookingIntent intent);
}
