package com.aurevia.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentPage;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.AppointmentView;
import com.aurevia.scheduling.BookingRequest;
import com.aurevia.scheduling.RescheduleRequest;
import com.aurevia.scheduling.SeriesRequest;

/**
 * Thin HTTP adapter for appointments (CLN-01, ARC-03).
 *
 * <p>Every irreversible command requires an {@code If-Match} version, which is
 * parsed here into {@code 428} or {@code 422} before the domain service compares
 * it. Authorization, availability, slot exclusivity and history all live in
 * {@link com.aurevia.scheduling.AppointmentService}.</p>
 */
@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    private final AppointmentService service;

    /**
     * Create the controller.
     *
     * @param service appointment domain service
     */
    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    /**
     * Book a confirmed appointment.
     *
     * @param request booking command
     * @return HTTP 201 with the created appointment
     */
    @PostMapping
    public ResponseEntity<Appointment> book(@RequestBody BookingRequest request) {
        Appointment created = service.book(request);
        return ResponseEntity.created(URI.create("/api/v1/appointments/" + created.id())).body(created);
    }

    /**
     * Create a temporary hold on a slot.
     *
     * @param request booking command
     * @return HTTP 201 with the held appointment and its expiry
     */
    @PostMapping("/holds")
    public ResponseEntity<Appointment> hold(@RequestBody BookingRequest request) {
        Appointment created = service.hold(request);
        return ResponseEntity.created(URI.create("/api/v1/appointments/" + created.id())).body(created);
    }

    /**
     * Create a weekly series.
     *
     * @param request series command
     * @return HTTP 201 with every created appointment
     */
    @PostMapping("/series")
    public ResponseEntity<List<Appointment>> series(@RequestBody SeriesRequest request) {
        return ResponseEntity.status(201).body(service.bookSeries(request));
    }

    /**
     * List appointments in the active organization by scheduled-start range.
     *
     * @param cursor   opaque cursor of the previous page's last row
     * @param limit    page size, default 50 and at most 200
     * @param from     inclusive lower UTC instant of the range
     * @param to       inclusive upper UTC instant, after from and at most 31 days later
     * @param resource optional clinician or room resource code
     * @param state    optional appointment state filter
     * @return one page of appointments ordered by scheduled start ascending
     */
    @GetMapping
    public AppointmentPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String state) {
        return service.list(cursor, limit, from, to, resource, state);
    }

    /**
     * Read one appointment with its history.
     *
     * @param id appointment identifier
     * @return the appointment view
     */
    @GetMapping("/{id}")
    public AppointmentView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Convert a hold into a confirmed appointment.
     *
     * @param id      appointment identifier
     * @param ifMatch expected version
     * @return the confirmed appointment
     */
    @PostMapping("/{id}/confirm")
    public Appointment confirm(@PathVariable String id,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.confirm(id, IfMatch.parse(ifMatch));
    }

    /**
     * Reschedule an appointment.
     *
     * @param id      appointment identifier
     * @param request new slot
     * @param ifMatch expected version
     * @return the rescheduled appointment
     */
    @PostMapping("/{id}/reschedule")
    public Appointment reschedule(@PathVariable String id, @RequestBody RescheduleRequest request,
                                  @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.reschedule(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Check the patient in.
     *
     * @param id      appointment identifier
     * @param ifMatch expected version
     * @return the checked-in appointment
     */
    @PostMapping("/{id}/check-in")
    public Appointment checkIn(@PathVariable String id,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.checkIn(id, IfMatch.parse(ifMatch));
    }

    /**
     * Start the encounter.
     *
     * @param id      appointment identifier
     * @param ifMatch expected version
     * @return the in-progress appointment
     */
    @PostMapping("/{id}/start")
    public Appointment start(@PathVariable String id,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.start(id, IfMatch.parse(ifMatch));
    }

    /**
     * Complete the encounter.
     *
     * @param id      appointment identifier
     * @param ifMatch expected version
     * @return the completed appointment
     */
    @PostMapping("/{id}/complete")
    public Appointment complete(@PathVariable String id,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.complete(id, IfMatch.parse(ifMatch));
    }

    /**
     * Cancel an appointment with a reason.
     *
     * @param id      appointment identifier
     * @param request recorded reason
     * @param ifMatch expected version
     * @return the cancelled appointment
     */
    @PostMapping("/{id}/cancel")
    public Appointment cancel(@PathVariable String id, @RequestBody ReasonRequest request,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.cancel(id, IfMatch.parse(ifMatch), request.reason());
    }

    /**
     * Record a no-show with a reason.
     *
     * @param id      appointment identifier
     * @param request recorded reason
     * @param ifMatch expected version
     * @return the no-show appointment
     */
    @PostMapping("/{id}/no-show")
    public Appointment noShow(@PathVariable String id, @RequestBody ReasonRequest request,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.noShow(id, IfMatch.parse(ifMatch), request.reason());
    }

    /**
     * Recorded reason for cancel and no-show.
     *
     * @param reason free-text reason required by CLN-01
     * @return the recorded reason
     */
    public record ReasonRequest(String reason) {
    }
}
