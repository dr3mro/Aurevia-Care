package com.aurevia.messaging.appointments;

import java.time.Instant;

/**
 * The appointment fields a verified patient may see, with facility time-zone
 * context and the server-recorded confirmation timestamp (PAT-01, ARC-01).
 *
 * <p>No clinical narrative, clinician note or internal identifier crosses this
 * projection. The facility time zone is IANA so a mobile client can render the
 * local wall-clock time without guessing from an offset.</p>
 *
 * @param id                 opaque appointment identifier
 * @param version            optimistic-lock version for the confirmation precondition
 * @param patientId          patient identifier in the active organization
 * @param facilityCode       tenant-local facility code
 * @param facilityTimeZone   facility IANA time-zone identifier
 * @param startsAt           UTC slot start
 * @param status             authoritative patient-visible status
 * @param confirmationEnabled whether the site enabled patient confirmation
 * @param confirmedAt        server instant of patient confirmation, or {@code null}
 * @return this record
 */
public record PatientAppointment(String id, int version, String patientId, String facilityCode,
                                 String facilityTimeZone, Instant startsAt,
                                 AppointmentStatus status, boolean confirmationEnabled,
                                 Instant confirmedAt) {

    /**
     * Decide whether this appointment can be confirmed by the patient.
     *
     * <p>Enablement is server-owned; a client flag is never consulted.</p>
     *
     * @return {@code true} when enabled, still booked and not yet confirmed
     */
    public boolean confirmable() {
        return confirmationEnabled && status.patientCanConfirm() && confirmedAt == null;
    }

    /**
     * Return a new revision carrying the server confirmation timestamp.
     *
     * @param at         server instant of confirmation
     * @param nextVersion version of the returned revision
     * @return an immutable confirmed projection
     */
    public PatientAppointment confirmed(Instant at, int nextVersion) {
        return new PatientAppointment(id, nextVersion, patientId, facilityCode, facilityTimeZone,
                startsAt, status, confirmationEnabled, at);
    }
}
