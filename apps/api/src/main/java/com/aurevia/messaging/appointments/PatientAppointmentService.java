package com.aurevia.messaging.appointments;

import java.time.Instant;
import java.util.List;

import com.aurevia.api.ApiException;

/**
 * Patient self-service for appointments, confirmation, generic status pushes
 * and device revocation (PAT-01, ARC-02, OPS-02).
 *
 * <p>Every command first re-checks a verified patient link for the authenticated
 * subject, then scopes all reads and writes by the verified patient. There is
 * no client-supplied patient scope and no staff role shortcut. Confirmation
 * requires a version precondition and uses a server timestamp; a push payload
 * is created from a fixed generic template that accepts no clinical input.
 * Revocation and invalid-token state are consulted at dispatch time.</p>
 *
 * <p>The class is intentionally free of Spring stereotypes: this slice has no
 * persistence adapter yet, so registering it as a bean would break context
 * startup. Wiring is a follow-up once the verified-link and device tables
 * exist.</p>
 */
public final class PatientAppointmentService {

    private final VerifiedPatientLink links;
    private final PatientAppointmentStore store;
    private final DeviceTokenStore devices;
    private final PushGateway gateway;
    private final AuditSink audit;

    /**
     * Create the service with its tenant-scoped ports.
     *
     * @param links   verified patient-link port, re-read on every command
     * @param store   appointment projection store
     * @param devices device registry and invalid-token log
     * @param gateway synthetic push boundary
     * @param audit   append-only audit sink
     */
    public PatientAppointmentService(VerifiedPatientLink links, PatientAppointmentStore store,
                                     DeviceTokenStore devices, PushGateway gateway,
                                     AuditSink audit) {
        this.links = links;
        this.store = store;
        this.devices = devices;
        this.gateway = gateway;
        this.audit = audit;
    }

    /**
     * List the verified patient's appointments with facility time zone and status.
     *
     * @param subject   authenticated patient subject
     * @param patientId patient identifier in the active organization
     * @param at        server instant of the request
     * @return immutable appointment projections
     * @throws ApiException when the link is absent or suspended
     */
    public List<PatientAppointment> listAppointments(String subject, String patientId, Instant at) {
        authorize(subject, patientId, "PATIENT_APPOINTMENT_LIST", at);
        List<PatientAppointment> result = store.forPatient(patientId);
        audit.append(subject, patientId, "PATIENT_APPOINTMENT_LIST", "SUCCESS", at);
        return List.copyOf(result);
    }

    /**
     * Confirm an appointment the patient is permitted to confirm, recording the
     * server timestamp and dispatching a generic notice.
     *
     * @param subject         authenticated patient subject
     * @param patientId       patient identifier in the active organization
     * @param appointmentId   appointment identifier
     * @param expectedVersion version read by the caller
     * @param at              server instant of the confirmation
     * @return the confirmed projection
     * @throws ApiException on denial, concealment, ineligibility or a stale version
     */
    public PatientAppointment confirm(String subject, String patientId, String appointmentId,
                                      int expectedVersion, Instant at) {
        authorize(subject, patientId, "PATIENT_APPOINTMENT_CONFIRM", at);
        PatientAppointment current = store.find(appointmentId, patientId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "appointment not found"));
        if (!current.confirmable()) {
            throw new ApiException(422, "NOT_CONFIRMABLE",
                    "the appointment is not open to patient confirmation");
        }
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the appointment changed since it was read");
        }
        if (store.confirm(appointmentId, patientId, expectedVersion, at) == 0) {
            throw new ApiException(412, "STALE_VERSION", "the appointment changed since it was read");
        }
        dispatch(subject, patientId, at, "PUSH_CONFIRM");
        audit.append(subject, patientId, "PATIENT_APPOINTMENT_CONFIRM", "SUCCESS", at);
        return store.find(appointmentId, patientId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "appointment not found"));
    }

    /**
     * Dispatch a generic notice after an appointment status change. The status
     * argument is recorded in the audit key only; it never enters push text.
     *
     * @param subject       authenticated patient subject
     * @param patientId     patient identifier in the active organization
     * @param appointmentId appointment that changed
     * @param status        authoritative new status
     * @param at            server instant of the notification
     * @throws ApiException when the link is absent or the appointment is concealed
     */
    public void notifyStatusChange(String subject, String patientId, String appointmentId,
                                   AppointmentStatus status, Instant at) {
        authorize(subject, patientId, "PATIENT_NOTIFY_STATUS_CHANGE", at);
        store.find(appointmentId, patientId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "appointment not found"));
        dispatch(subject, patientId, at, "PUSH_NOTIFY_" + status.name());
        audit.append(subject, patientId, "PATIENT_NOTIFY_STATUS_CHANGE", "SUCCESS", at);
    }

    /**
     * Register a device for the verified patient.
     *
     * @param subject   authenticated patient subject
     * @param patientId patient identifier in the active organization
     * @param tokenId   opaque device identifier safe to log
     * @param token     provider push token, never written to an audit sink
     * @param at        server instant of registration
     * @throws ApiException on denial, blank identifiers or an id owned by another patient
     */
    public void registerDevice(String subject, String patientId, String tokenId, String token,
                               Instant at) {
        authorize(subject, patientId, "DEVICE_REGISTER", at);
        if (isBlank(tokenId) || isBlank(token)) {
            throw new ApiException(422, "INVALID_DEVICE", "a device id and token are required");
        }
        DeviceToken existing = devices.findByTokenId(tokenId).orElse(null);
        if (existing != null && !patientId.equals(existing.patientId())) {
            throw new ApiException(409, "DEVICE_CONFLICT",
                    "this device id is registered to another patient");
        }
        devices.save(new DeviceToken(tokenId, token, subject, patientId, DeviceState.ACTIVE));
        audit.append(subject, patientId, "DEVICE_REGISTER", "SUCCESS", at);
    }

    /**
     * Revoke a device so it receives no further pushes. Revocation is idempotent.
     *
     * @param subject   authenticated patient subject
     * @param patientId patient identifier in the active organization
     * @param tokenId   opaque device identifier
     * @param at        server instant of revocation
     * @throws ApiException on denial or a concealed device
     */
    public void revokeDevice(String subject, String patientId, String tokenId, Instant at) {
        authorize(subject, patientId, "DEVICE_REVOKE", at);
        DeviceToken current = devices.find(tokenId, patientId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "device not found"));
        if (current.state() != DeviceState.REVOKED) {
            devices.save(current.revoke());
        }
        audit.append(subject, patientId, "DEVICE_REVOKE", "SUCCESS", at);
    }

    /**
     * Record a push provider's invalid-token disposition and invalidate the device.
     *
     * @param subject   authenticated patient subject
     * @param patientId patient identifier in the active organization
     * @param tokenId   opaque device identifier
     * @param reason    non-sensitive disposition code
     * @param at        server instant of the disposition
     * @throws ApiException on denial or a concealed device
     */
    public void recordInvalidToken(String subject, String patientId, String tokenId, String reason,
                                   Instant at) {
        authorize(subject, patientId, "DEVICE_INVALID_TOKEN", at);
        DeviceToken current = devices.find(tokenId, patientId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "device not found"));
        // A repeated provider disposition must not create duplicate log rows.
        if (current.state() != DeviceState.INVALID) {
            devices.save(current.invalidate());
            devices.appendInvalidTokenLog(tokenId, patientId, reason, at);
        }
        audit.append(subject, patientId, "DEVICE_INVALID_TOKEN", "SUCCESS", at);
    }

    private void dispatch(String subject, String patientId, Instant at, String action) {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        for (DeviceToken token : devices.active(patientId)) {
            if (!token.state().deliverable()) {
                continue;
            }
            if (gateway.send(token.token(), notice) == PushGateway.Outcome.INVALID_TOKEN) {
                devices.save(token.invalidate());
                devices.appendInvalidTokenLog(token.tokenId(), patientId, "INVALID_TOKEN", at);
                audit.append(subject, patientId, action + "_INVALID_TOKEN", "SUCCESS", at);
            } else {
                audit.append(subject, patientId, action + "_SENT", "SUCCESS", at);
            }
        }
    }

    private void authorize(String subject, String patientId, String action, Instant at) {
        if (isBlank(subject) || isBlank(patientId) || !links.allows(subject, patientId)) {
            audit.append(subject, patientId, action, "DENIED", at);
            throw new ApiException(403, "FORBIDDEN", "no verified patient link");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
