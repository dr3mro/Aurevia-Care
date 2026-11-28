package com.aurevia.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.SqlFailureTranslator;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Booking invariants for appointment slots, holds and series.
 *
 * <p>One command runs inside one tenant-guarded transaction: authorization is
 * re-checked, the requested slot is validated against the facility's local
 * availability windows and the approved booking rules, the protected resources
 * are locked in a deterministic order, lapsed holds overlapping the slot are
 * released, and the appointment, its history event and the audit event commit
 * together.</p>
 *
 * <p>Slot exclusivity is owned by two partial GiST exclusion constraints in V8,
 * so two concurrent commands cannot both commit the same protected slot even if
 * the application lock is bypassed. A losing command surfaces as {@code 409}.</p>
 */
@Service
public class AppointmentService {

    private static final String SCOPE = "SCHEDULING";
    private static final String RESOURCE_TYPE = "APPOINTMENT";
    private static final int MAX_OCCURRENCES = 52;
    private static final int MAX_REPEAT_WEEKS = 4;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final Pattern CURSOR = Pattern.compile(
            "(.+):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private final AppointmentRepository appointments;
    private final SchedulingRepository scheduling;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param appointments RLS-bounded appointment repository
     * @param scheduling   RLS-bounded reference-data repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        application clock used by the booking rules
     */
    public AppointmentService(AppointmentRepository appointments, SchedulingRepository scheduling,
                              ActionPolicy policy, TransactionTemplate transactions,
                              AuditAppender audit, Clock clock) {
        this.appointments = appointments;
        this.scheduling = scheduling;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Create a confirmed appointment.
     *
     * @param request booking command
     * @return the booked appointment
     * @throws ApiException on invalid input, denial, unknown reference data,
     *                      an availability or rule violation, or a slot conflict
     */
    public Appointment book(BookingRequest request) {
        return create(request, AppointmentState.BOOKED);
    }

    /**
     * Create a temporary hold that occupies the slot until it expires.
     *
     * @param request booking command
     * @return the held appointment with its expiry
     * @throws ApiException on the same boundaries as {@code book(BookingRequest)}
     */
    public Appointment hold(BookingRequest request) {
        return create(request, AppointmentState.HELD);
    }

    /**
     * Create a recurrence series in one tenant-guarded transaction.
     *
     * <p>Occurrences are expanded from the facility-local clock time, so a
     * daylight-saving change keeps the same local appointment time. Every
     * occurrence is checked against the protected resources before any row is
     * inserted; the whole series is rejected with the complete clash list when
     * even one slot is taken. The template, series, occurrences, appointments
     * and their history commit atomically.</p>
     *
     * @param request series command
     * @return the created appointments in occurrence order
     * @throws ApiException       on invalid input or a denial
     * @throws SeriesConflictException when any occurrence overlaps a protected resource
     */
    public List<Appointment> bookSeries(SeriesRequest request) {
        int repeatWeeks = request.repeatWeeks() == null ? 0 : request.repeatWeeks();
        if (repeatWeeks < 1 || repeatWeeks > MAX_REPEAT_WEEKS) {
            throw new ApiException(422, "INVALID_REPEAT", "repeatWeeks must be 1-4");
        }
        String endDateRaw = request.endDate();
        boolean byEndDate = endDateRaw != null && !endDateRaw.isBlank();
        Integer occurrences = request.occurrences();
        if (byEndDate) {
            if (occurrences != null) {
                throw new ApiException(422, "INVALID_OCCURRENCES", "supply occurrences or endDate, not both");
            }
        } else if (occurrences == null || occurrences < 1 || occurrences > MAX_OCCURRENCES) {
            throw new ApiException(422, "INVALID_OCCURRENCES", "occurrences must be 1-52");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<Appointment> created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ActionPolicy.APPOINTMENT_BOOK, RESOURCE_TYPE);
            if (denial != null) {
                failure.set(denial);
                return List.of();
            }
            String facilityCode = SchedulingInputs.code(request.facilityCode(), "facilityCode");
            FacilityInfo facility = scheduling.facility(facilityCode)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            RecurrenceTemplate template = RecurrenceTemplate.of(repeatWeeks,
                    byEndDate ? null : occurrences, endDateRaw,
                    SchedulingInputs.instant(request.startsAt()), facility.timeZone());
            List<Instant> starts = template.occurrenceInstants();
            VisitTypeView visitType = scheduling.visitType(
                    SchedulingInputs.code(request.visitType(), "visitType"))
                    .orElseThrow(() -> new ApiException(422, "INVALID_VISIT_TYPE", "unknown visit type"));
            ResourceView clinician = resolve(scheduling.resource(facility.id(), "clinician",
                    SchedulingInputs.code(request.clinicianCode(), "clinicianCode")), "clinician");
            String roomCode = request.roomCode() == null ? null
                    : SchedulingInputs.code(request.roomCode(), "roomCode");
            ResourceView room = roomCode == null ? null
                    : resolve(scheduling.resource(facility.id(), "room", roomCode), "room");

            List<SeriesClash> clashes = new ArrayList<>();
            for (int index = 0; index < starts.size(); index++) {
                Instant start = starts.get(index);
                Instant end = start.plus(Duration.ofMinutes(visitType.durationMinutes()));
                for (String conflict : appointments.findConflicts(clinician.id(),
                        room == null ? null : room.id(), start, end)) {
                    clashes.add(new SeriesClash(index + 1, conflict, start.toString()));
                }
            }
            if (!clashes.isEmpty()) {
                throw new SeriesConflictException(clashes);
            }

            String seriesId = SchedulingInputs.newId();
            String templateId = SchedulingInputs.newId();
            appointments.insertRecurrenceTemplate(templateId, repeatWeeks,
                    byEndDate ? null : occurrences, byEndDate ? endDateRaw.trim() : null,
                    template.localTime().toString(), template.timeZone().getId());
            appointments.insertAppointmentSeries(seriesId, templateId,
                    SchedulingInputs.uuid(request.patientId(), "patientId"), facility.id(),
                    clinician.id(), room == null ? null : room.id(), visitType.code(), starts.get(0));
            List<Appointment> series = new ArrayList<>();
            for (int index = 0; index < starts.size(); index++) {
                Instant start = starts.get(index);
                series.add(createOne(context, new BookingRequest(request.facilityCode(),
                        request.patientId(), request.visitType(), request.clinicianCode(),
                        request.roomCode(), start.toString(), request.overrideReason()),
                        AppointmentState.BOOKED, seriesId));
                appointments.insertSeriesOccurrence(SchedulingInputs.newId(), seriesId,
                        series.get(index).id(), index + 1, start);
            }
            audit.append(context, "SERIES_BOOK", "SERIES_BOOK", RESOURCE_TYPE, SCOPE, seriesId, "SUCCESS");
            return List.copyOf(series);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Read one appointment with its history.
     *
     * @param id appointment identifier
     * @return the current appointment and its history
     * @throws ApiException when the id is malformed, denied or concealed
     */
    public AppointmentView get(String id) {
        SchedulingInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        AppointmentView view = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ActionPolicy.APPOINTMENT_READ, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Appointment appointment = appointments.find(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "appointment not found"));
            List<AppointmentEvent> history = appointments.history(id);
            audit.append(context, "APPOINTMENT_READ", "APPOINTMENT_READ", RESOURCE_TYPE, SCOPE, id,
                    "SUCCESS");
            return new AppointmentView(appointment, history);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    /**
     * Read one cursor-paginated page of appointments in the active organization.
     *
     * <p>The single-record read action is reused: a caller who may read one
     * appointment may list the same tenant's appointments. Validation happens
     * before any transaction and the read itself is audited inside the guarded
     * transaction. The range is inclusive at both ends and at most 31 days wide;
     * a resource code matches either the clinician or the room of an appointment.</p>
     *
     * @param cursor   opaque {@code <startsAt>:<id>} of the previous page's last row, or {@code null}
     * @param limit    page size, or {@code null} for the default of 50
     * @param from     required inclusive lower UTC instant of the scheduled-start range
     * @param to       required inclusive upper UTC instant, after {@code from} and at most 31 days later
     * @param resource optional tenant-local clinician or room code
     * @param state    optional appointment state wire value
     * @return one page with an opaque next cursor when more rows exist
     * @throws ApiException on invalid input or denied access
     */
    public AppointmentPage list(String cursor, String limit, String from, String to,
                                String resource, String state) {
        AppointmentCursor parsedCursor = parseCursor(cursor);
        int parsedLimit = parseLimit(limit);
        Instant parsedFrom = requireInstant(from);
        Instant parsedTo = requireInstant(to);
        validateRange(parsedFrom, parsedTo);
        String parsedResource = parseResource(resource);
        AppointmentState parsedState = parseState(state);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        AppointmentPage page = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ActionPolicy.APPOINTMENT_READ, "ALL");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<AppointmentRepository.AppointmentRow> rows = appointments.query(
                    parsedFrom, parsedTo, parsedResource, parsedState,
                    parsedCursor == null ? null : parsedCursor.startsAt(),
                    parsedCursor == null ? null : parsedCursor.id(), parsedLimit + 1);
            boolean hasMore = rows.size() > parsedLimit;
            List<AppointmentRepository.AppointmentRow> pageRows = hasMore ? rows.subList(0, parsedLimit) : rows;
            List<AppointmentView> items = pageRows.stream()
                    .map(row -> new AppointmentView(row.appointment(),
                            appointments.history(row.appointment().id())))
                    .toList();
            String next = hasMore
                    ? pageRows.get(pageRows.size() - 1).appointment().startsAt() + ":"
                            + pageRows.get(pageRows.size() - 1).id()
                    : null;
            audit.append(context, ActionPolicy.APPOINTMENT_READ, ActionPolicy.APPOINTMENT_READ,
                    RESOURCE_TYPE, SCOPE, "ALL", "SUCCESS");
            return new AppointmentPage(items, next);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return page;
    }

    /**
     * Convert a hold into a confirmed appointment.
     *
     * @param id              appointment identifier
     * @param expectedVersion version read by the caller
     * @return the confirmed appointment
     * @throws ApiException when denied, stale, expired or illegally transitioned
     */
    public Appointment confirm(String id, int expectedVersion) {
        return transition(id, expectedVersion, AppointmentState.BOOKED,
                ActionPolicy.APPOINTMENT_CONFIRM, null);
    }

    /**
     * Record that the patient arrived.
     *
     * @param id              appointment identifier
     * @param expectedVersion version read by the caller
     * @return the checked-in appointment
     * @throws ApiException when denied, stale or illegally transitioned
     */
    public Appointment checkIn(String id, int expectedVersion) {
        return transition(id, expectedVersion, AppointmentState.CHECKED_IN,
                ActionPolicy.APPOINTMENT_CHECK_IN, null);
    }

    /**
     * Start the encounter.
     *
     * @param id              appointment identifier
     * @param expectedVersion version read by the caller
     * @return the in-progress appointment
     * @throws ApiException when denied, stale or illegally transitioned
     */
    public Appointment start(String id, int expectedVersion) {
        return transition(id, expectedVersion, AppointmentState.IN_PROGRESS,
                ActionPolicy.APPOINTMENT_START, null);
    }

    /**
     * Complete the encounter.
     *
     * @param id              appointment identifier
     * @param expectedVersion version read by the caller
     * @return the completed appointment
     * @throws ApiException when denied, stale or illegally transitioned
     */
    public Appointment complete(String id, int expectedVersion) {
        return transition(id, expectedVersion, AppointmentState.COMPLETED,
                ActionPolicy.APPOINTMENT_COMPLETE, null);
    }

    /**
     * Cancel an appointment with a recorded reason.
     *
     * @param id              appointment identifier
     * @param expectedVersion version read by the caller
     * @param reason          recorded cancellation reason
     * @return the cancelled appointment
     * @throws ApiException when the reason is missing, denied, stale or illegal
     */
    public Appointment cancel(String id, int expectedVersion, String reason) {
        return transition(id, expectedVersion, AppointmentState.CANCELLED,
                ActionPolicy.APPOINTMENT_CANCEL, reason);
    }

    /**
     * Record that the patient did not attend, with a reason.
     *
     * @param id              appointment identifier
     * @param expectedVersion version read by the caller
     * @param reason          recorded no-show reason
     * @return the no-show appointment
     * @throws ApiException when the reason is missing, denied, stale or illegal
     */
    public Appointment noShow(String id, int expectedVersion, String reason) {
        return transition(id, expectedVersion, AppointmentState.NO_SHOW,
                ActionPolicy.APPOINTMENT_NO_SHOW, reason);
    }

    /**
     * Move an appointment to another slot, keeping its history.
     *
     * @param id              appointment identifier
     * @param request         reschedule command
     * @param expectedVersion version read by the caller
     * @return the rescheduled appointment
     * @throws ApiException when the version is stale, denied, the target slot is
     *                      outside availability or already taken
     */
    public Appointment reschedule(String id, RescheduleRequest request, int expectedVersion) {
        SchedulingInputs.uuid(id, "id");
        Instant startsAt = SchedulingInputs.instant(request.startsAt());
        String overrideReason = SchedulingInputs.reason(request.overrideReason());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Appointment moved = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ActionPolicy.APPOINTMENT_RESCHEDULE, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Appointment current = appointments.loadForUpdate(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "appointment not found"));
            requireVersion(current, expectedVersion);
            if (current.state().isTerminal()) {
                throw new ApiException(409, "INVALID_TRANSITION", "a finished appointment cannot move");
            }
            FacilityInfo facility = scheduling.facility(current.facilityCode())
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            VisitTypeView visitType = scheduling.visitType(current.visitType())
                    .orElseThrow(() -> new ApiException(422, "INVALID_VISIT_TYPE", "unknown visit type"));
            ResourceView clinician = resolve(scheduling.resource(facility.id(), "clinician",
                    request.clinicianCode() == null
                            ? current.clinicianCode()
                            : SchedulingInputs.code(request.clinicianCode(), "clinicianCode")),
                    "clinician");
            String roomCode = request.roomCode() == null
                    ? current.roomCode() : SchedulingInputs.code(request.roomCode(), "roomCode");
            String roomId = roomCode == null ? null
                    : resolve(scheduling.resource(facility.id(), "room", roomCode), "room").id();
            Instant endsAt = startsAt.plus(Duration.ofMinutes(visitType.durationMinutes()));
            checkAvailability(facility, clinician.id(), roomId, startsAt, endsAt, overrideReason);
            lockAndRelease(clock.instant(), clinician.id(), roomId, startsAt, endsAt);
            try {
                int updated = appointments.updateSlot(id, expectedVersion, clinician.id(), roomId,
                        startsAt, endsAt, overrideReason);
                if (updated == 0) {
                    throw staleVersion();
                }
            } catch (DataIntegrityViolationException exception) {
                throw SqlFailureTranslator.translate(exception);
            }
            appointments.appendEvent(id, expectedVersion + 1, "rescheduled", current.state(),
                    current.state(), overrideReason, current.startsAt(), current.endsAt());
            audit.append(context, "APPOINTMENT_RESCHEDULE", "APPOINTMENT_RESCHEDULE", RESOURCE_TYPE,
                    SCOPE, id, "SUCCESS");
            return appointments.find(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return moved;
    }

    private Appointment create(BookingRequest request, AppointmentState target) {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Appointment created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context,
                    target == AppointmentState.HELD
                            ? ActionPolicy.APPOINTMENT_HOLD : ActionPolicy.APPOINTMENT_BOOK,
                    RESOURCE_TYPE);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            return createOne(context, request, target, null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    private Appointment createOne(TenantContext context, BookingRequest request,
                                  AppointmentState target, String seriesId) {
        String facilityCode = SchedulingInputs.code(request.facilityCode(), "facilityCode");
        String patientId = SchedulingInputs.uuid(request.patientId(), "patientId");
        String visitTypeCode = SchedulingInputs.code(request.visitType(), "visitType");
        String clinicianCode = SchedulingInputs.code(request.clinicianCode(), "clinicianCode");
        String overrideReason = SchedulingInputs.reason(request.overrideReason());
        Instant startsAt = SchedulingInputs.instant(request.startsAt());

        FacilityInfo facility = scheduling.facility(facilityCode)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
        VisitTypeView visitType = scheduling.visitType(visitTypeCode)
                .orElseThrow(() -> new ApiException(422, "INVALID_VISIT_TYPE", "unknown visit type"));
        if (!appointments.patientExists(patientId)) {
            throw new ApiException(404, "NOT_FOUND", "patient not found");
        }
        ResourceView clinician = resolve(
                scheduling.resource(facility.id(), "clinician", clinicianCode), "clinician");
        String roomId = request.roomCode() == null ? null
                : resolve(scheduling.resource(facility.id(), "room",
                        SchedulingInputs.code(request.roomCode(), "roomCode")), "room").id();

        Instant endsAt = startsAt.plus(Duration.ofMinutes(visitType.durationMinutes()));
        BookingRules rules = BookingRules.from(scheduling::setting);
        Instant now = clock.instant();
        if (startsAt.isBefore(now.plus(Duration.ofMinutes(rules.minNoticeMinutes())))) {
            throw new ApiException(422, "MIN_NOTICE_NOT_MET", "the slot is inside the minimum notice");
        }
        if (startsAt.isAfter(now.plus(Duration.ofDays(rules.maxAdvanceDays())))) {
            throw new ApiException(422, "MAX_ADVANCE_EXCEEDED", "the slot is too far in the future");
        }
        checkAvailability(facility, clinician.id(), roomId, startsAt, endsAt, overrideReason);

        Instant expiresAt = target == AppointmentState.HELD
                ? now.plus(Duration.ofMinutes(rules.holdMinutes())) : null;
        lockAndRelease(now, clinician.id(), roomId, startsAt, endsAt);
        String id = SchedulingInputs.newId();
        try {
            appointments.insert(context.organizationId(), id, patientId, facility.id(), clinician.id(),
                    roomId, visitType.code(), startsAt, endsAt, target, seriesId, expiresAt,
                    overrideReason);
        } catch (DataIntegrityViolationException exception) {
            throw SqlFailureTranslator.translate(exception);
        }
        appointments.appendEvent(id, 1, target.wire(), null, target, null, null, null);
        if (seriesId == null) {
            audit.append(context, target == AppointmentState.HELD
                    ? "APPOINTMENT_HOLD" : "APPOINTMENT_BOOK",
                    target == AppointmentState.HELD ? "APPOINTMENT_HOLD" : "APPOINTMENT_BOOK",
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
        }
        return appointments.find(id).orElseThrow();
    }

    private Appointment transition(String id, int expectedVersion, AppointmentState target,
                                   String action, String reason) {
        SchedulingInputs.uuid(id, "id");
        String recordedReason = SchedulingInputs.reason(reason);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Appointment result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, action, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Appointment current = appointments.loadForUpdate(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "appointment not found"));
            requireVersion(current, expectedVersion);
            if (target.requiresReason() && recordedReason == null) {
                throw new ApiException(422, "REASON_REQUIRED", "a reason is required for this transition");
            }
            if (!current.state().canTransitionTo(target)) {
                throw new ApiException(409, "INVALID_TRANSITION",
                        "the appointment cannot move to " + target.wire());
            }
            int updated = appointments.updateState(id, expectedVersion, target);
            if (updated == 0) {
                throw staleVersion();
            }
            appointments.appendEvent(id, expectedVersion + 1, target.eventAction(), current.state(),
                    target, recordedReason, null, null);
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return appointments.find(id).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private ResourceView resolve(java.util.Optional<ResourceView> resource, String kind) {
        return resource.orElseThrow(() -> new ApiException(404, "NOT_FOUND", kind + " not found"));
    }

    private void requireVersion(Appointment current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw staleVersion();
        }
    }

    private ApiException staleVersion() {
        return new ApiException(412, "STALE_VERSION", "the appointment changed since it was read");
    }

    private void checkAvailability(FacilityInfo facility, String clinicianId, String roomId,
                                   Instant startsAt, Instant endsAt, String overrideReason) {
        ZoneId zone = SchedulingInputs.zone(facility.timeZone());
        boolean outside = !scheduling.withinAvailability(clinicianId, zone.getId(), startsAt, endsAt);
        if (!outside && roomId != null) {
            outside = !scheduling.withinAvailability(roomId, zone.getId(), startsAt, endsAt);
        }
        if (outside && overrideReason == null) {
            throw new ApiException(422, "OUTSIDE_AVAILABILITY",
                    "the slot is outside the approved availability windows");
        }
    }

    private void lockAndRelease(Instant now, String clinicianId, String roomId, Instant startsAt,
                                Instant endsAt) {
        List<String> resources = new ArrayList<>();
        resources.add(clinicianId);
        if (roomId != null) {
            resources.add(roomId);
        }
        appointments.lockResources(resources);
        appointments.expireStaleHolds(now, clinicianId, roomId, startsAt, endsAt);
    }

    private AppointmentCursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw invalidCursor();
        }
        Instant startsAt;
        try {
            startsAt = Instant.parse(matcher.group(1));
        } catch (DateTimeParseException exception) {
            throw invalidCursor();
        }
        return new AppointmentCursor(startsAt, matcher.group(2));
    }

    private static ApiException invalidCursor() {
        return new ApiException(422, "INVALID_CURSOR", "cursor must be <startsAt>:<id>");
    }

    private static int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(limit);
            if (value < 1 || value > MAX_LIMIT) {
                throw new NumberFormatException("limit out of range");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-200");
        }
    }

    private static Instant requireInstant(String value) {
        if (value == null || value.isBlank()) {
            throw invalidRange();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidRange();
        }
    }

    private static void validateRange(Instant from, Instant to) {
        if (!to.isAfter(from) || to.isAfter(from.plus(MAX_RANGE))) {
            throw invalidRange();
        }
    }

    private static ApiException invalidRange() {
        return new ApiException(422, "INVALID_RANGE",
                "from and to must be ISO-8601 instants, with to after from and at most 31 days later");
    }

    private static String parseResource(String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        return SchedulingInputs.code(resource, "resource");
    }

    private static AppointmentState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return AppointmentState.from(state);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_STATE", "state must be an appointment state");
        }
    }

    /** Parsed opaque cursor: the exclusive scheduled-start bound and the immutable row id. */
    private record AppointmentCursor(Instant startsAt, String id) {
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!scheduling.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (scheduling.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing scheduling permission");
        }
        return null;
    }
}
