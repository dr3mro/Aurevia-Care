package com.aurevia.messaging.appointments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped persistence port for the patient appointment projection
 * (PAT-01, ARC-01). The adapter must scope every operation by the active
 * organization and by the verified patient, never by a client-supplied filter.
 */
public interface PatientAppointmentStore {

    /**
     * List the appointments belonging to one verified patient.
     *
     * @param patientId verified patient identifier
     * @return immutable projection list, ordered by start time
     */
    List<PatientAppointment> forPatient(String patientId);

    /**
     * Find one appointment within the verified patient's scope.
     *
     * @param appointmentId appointment identifier
     * @param patientId     verified patient identifier
     * @return the appointment or empty when it is outside the scope
     */
    Optional<PatientAppointment> find(String appointmentId, String patientId);

    /**
     * Record patient confirmation with a server timestamp and version
     * precondition in one atomic update.
     *
     * @param appointmentId  appointment identifier
     * @param patientId      verified patient identifier
     * @param expectedVersion version the caller read
     * @param confirmedAt    server instant of confirmation
     * @return number of rows updated; zero means the version changed
     */
    int confirm(String appointmentId, String patientId, int expectedVersion, Instant confirmedAt);
}
