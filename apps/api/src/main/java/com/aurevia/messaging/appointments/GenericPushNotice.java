package com.aurevia.messaging.appointments;

/**
 * The only push payload this slice may emit (PAT-01, OPS-02).
 *
 * <p>The factory accepts no appointment, patient, facility or time value, so a
 * notification cannot carry clinical detail even by accident. The app retrieves
 * the detail only after the patient authenticates. The deep link is a fixed
 * route without an appointment identifier for the same reason.</p>
 *
 * @param title    fixed generic title
 * @param body     fixed generic body without numbers or identifiers
 * @param deepLink fixed in-app route containing no resource identifier
 * @return this record
 */
public record GenericPushNotice(String title, String body, String deepLink) {

    /** Fixed generic title; no patient or appointment data. */
    public static final String GENERIC_TITLE = "Appointment update";
    /** Fixed generic body; no patient or appointment data. */
    public static final String GENERIC_BODY =
            "You have an update about your appointment. Open the app to view details.";
    /** Fixed in-app route; no appointment identifier is placed in a notification. */
    public static final String GENERIC_LINK = "aurevia://appointments";

    /**
     * Return the single allowed status-change notification.
     *
     * @return the generic notice
     */
    public static GenericPushNotice appointmentUpdate() {
        return new GenericPushNotice(GENERIC_TITLE, GENERIC_BODY, GENERIC_LINK);
    }

    /**
     * Report whether a caller-supplied detail would be exposed by this notice.
     *
     * <p>Callers and tests use this to prove that patient, facility, clinician,
     * time and status values never appear in a notification.</p>
     *
     * @param detail candidate sensitive value, possibly {@code null}
     * @return {@code true} when the value appears in the title, body or link
     */
    public boolean reveals(String detail) {
        if (detail == null || detail.isBlank()) {
            return false;
        }
        return title.contains(detail) || body.contains(detail) || deepLink.contains(detail);
    }
}
