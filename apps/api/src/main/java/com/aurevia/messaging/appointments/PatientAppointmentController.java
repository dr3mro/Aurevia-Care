package com.aurevia.messaging.appointments;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for patient appointment self-service (PAT-01, PAT-03).
 *
 * <p>The authenticated subject comes from the request context; the patient
 * identifier is checked against a freshly verified link on every command inside
 * {@link com.aurevia.messaging.appointments.PatientAppointmentService}.
 * Push payloads are generic and contain no clinical detail. The synthetic ports
 * make the workflow runnable with fixtures until durable IAM-05 adapters land.</p>
 */
@RestController
@RequestMapping("/api/v1/patient/appointments")
public final class PatientAppointmentController {

    private final PatientAppointmentService service;
    private final Clock clock;

    /**
     * Create the route over the synthetic in-memory ports.
     *
     * @param clock shared application clock
     */
    @Autowired
    public PatientAppointmentController(Clock clock) {
        this(new SyntheticPatientAppointmentPorts(), clock);
    }

    /**
     * Create the route over seeded synthetic ports, used by HTTP tests.
     *
     * @param ports  synthetic patient-service ports
     * @param clock  application clock
     */
    PatientAppointmentController(SyntheticPatientAppointmentPorts ports, Clock clock) {
        this.service = new PatientAppointmentService(ports.links, ports.appointments,
                ports.devices, ports.gateway, ports.audit);
        this.clock = clock;
    }

    /**
     * List the verified patient's appointments.
     *
     * @param patientId linked patient identifier
     * @return appointment projections with facility time zone and status
     */
    @GetMapping
    public List<PatientAppointment> list(@RequestParam String patientId) {
        return service.listAppointments(subject(), patientId, now());
    }

    /**
     * Confirm one appointment against the caller's version.
     *
     * @param appointmentId appointment identifier
     * @param request       patient and version fields
     * @return the confirmed projection
     */
    @PostMapping("/{appointmentId}/confirm")
    public PatientAppointment confirm(@PathVariable String appointmentId,
                                      @RequestBody ConfirmRequest request) {
        return service.confirm(subject(), request.patientId(), appointmentId,
                request.expectedVersion(), now());
    }

    /**
     * Dispatch a generic status notice to the patient's active devices.
     *
     * @param appointmentId appointment that changed
     * @param request       patient and authoritative status
     * @return HTTP 204 when the command completes
     */
    @PostMapping("/{appointmentId}/notify")
    public ResponseEntity<Void> notify(@PathVariable String appointmentId,
                                       @RequestBody NotifyRequest request) {
        service.notifyStatusChange(subject(), request.patientId(), appointmentId,
                request.status(), now());
        return ResponseEntity.noContent().build();
    }

    /**
     * Register a device for generic status notifications.
     *
     * @param request patient, device identifier and provider token
     * @return HTTP 204 when the command completes
     */
    @PostMapping("/devices")
    public ResponseEntity<Void> register(@RequestBody DeviceRequest request) {
        service.registerDevice(subject(), request.patientId(), request.tokenId(),
                request.token(), now());
        return ResponseEntity.noContent().build();
    }

    /**
     * Revoke a device so it receives no further pushes.
     *
     * @param tokenId   device identifier
     * @param patientId linked patient identifier
     * @return HTTP 204 when the command completes
     */
    @DeleteMapping("/devices/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable String tokenId,
                                       @RequestParam String patientId) {
        service.revokeDevice(subject(), patientId, tokenId, now());
        return ResponseEntity.noContent().build();
    }

    /**
     * Record a provider's invalid-token disposition.
     *
     * @param tokenId   device identifier
     * @param patientId linked patient identifier
     * @param reason    non-sensitive disposition code
     * @return HTTP 204 when the command completes
     */
    @PostMapping("/devices/{tokenId}/invalid")
    public ResponseEntity<Void> invalidToken(@PathVariable String tokenId,
                                             @RequestParam String patientId,
                                             @RequestParam String reason) {
        service.recordInvalidToken(subject(), patientId, tokenId, reason, now());
        return ResponseEntity.noContent().build();
    }

    /**
     * Read the authenticated subject, failing closed when no context is bound.
     *
     * @return the authenticated subject
     */
    private static String subject() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context.subject();
    }

    /**
     * Read the server instant for this command.
     *
     * @return the current instant
     */
    private Instant now() {
        return clock.instant();
    }

    /**
     * Command to confirm one appointment.
     *
     * @param patientId       linked patient identifier
     * @param expectedVersion version the caller read
     * @return the confirmation request
     */
    public record ConfirmRequest(String patientId, int expectedVersion) {
    }

    /**
     * Command to dispatch a generic status notice.
     *
     * @param patientId linked patient identifier
     * @param status    authoritative patient-visible status
     * @return the notification request
     */
    public record NotifyRequest(String patientId, AppointmentStatus status) {
    }

    /**
     * Command to register a device.
     *
     * @param patientId linked patient identifier
     * @param tokenId   opaque device identifier
     * @param token     provider push token, never logged
     * @return the device registration request
     */
    public record DeviceRequest(String patientId, String tokenId, String token) {
    }
}
