package com.aurevia.scheduling.followup;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.FacilityCode;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Follow-up suggestions, authorized overrides, explicit confirmation and care-team tasks (CLN-06).
 *
 * <p>Every command validates before any I/O, derives the tenant from the request context,
 * re-checks membership and permission, and audits the outcome including denials. Civil-date
 * arithmetic uses the facility's IANA zone so a daylight-saving transition cannot shift the
 * suggested date. Only {@code confirm} calls the booking port, exactly once, and it records
 * the returned booking identifier on a new immutable version of the suggestion.</p>
 *
 * <p>The ports are supplied in production by {@link com.aurevia.scheduling.followup.FollowUpConfiguration},
 * {@link com.aurevia.scheduling.followup.FollowUpAuthorizationGuard} and
 * {@link com.aurevia.scheduling.followup.FollowUpBookingService}, and by fakes in
 * unit tests. Persistence and audit commit through the {@code FollowUpCommandService}
 * transaction boundary; {@code FollowUpController} binds HTTP inputs to these methods.</p>
 */
@Service
public class FollowUpService {

    /** Business purpose recorded on follow-up audit events. */
    public static final String PURPOSE = "FOLLOW_UP";

    private static final String SUGGESTION_RESOURCE = "FOLLOW_UP_SUGGESTION";
    private static final String TASK_RESOURCE = "CARE_TEAM_TASK";
    private static final String SCOPE = "CLINIC";
    private static final int MAX_TEXT = 500;
    private static final int MAX_REASON = 200;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final FollowUpConfig config;
    private final FollowUpStore store;
    private final FollowUpAuth auth;
    private final FollowUpBooking booking;
    private final AuditAppender audit;
    private final ActionPolicy policy;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param config  approved configuration and facility facts
     * @param store   tenant-scoped suggestion and task store
     * @param auth    re-derived membership and roles
     * @param booking explicit-confirmation booking boundary
     * @param audit   transactional audit appender
     * @param policy  action policy
     * @param clock   application clock for provenance
     */
    public FollowUpService(FollowUpConfig config, FollowUpStore store, FollowUpAuth auth,
                           FollowUpBooking booking, AuditAppender audit, ActionPolicy policy,
                           Clock clock) {
        this.config = config;
        this.store = store;
        this.auth = auth;
        this.booking = booking;
        this.audit = audit;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Suggest a follow-up civil date from the approved rule and explain it.
     *
     * @param patientId     tenant-local patient identifier
     * @param facilityCode  tenant-local facility code
     * @param visitType     visit type code
     * @param encounterAt   UTC instant of the encounter
     * @return a stored suggestion with its rule explanation
     * @throws ApiException on invalid input, denial or no usable rule
     */
    public FollowUpSuggestion suggest(String patientId, String facilityCode, String visitType,
                                      String encounterAt) {
        String patient = uuid(patientId, "PATIENT_ID");
        String facility = code(facilityCode, "FACILITY_CODE");
        String visit = code(visitType, "VISIT_TYPE");
        Instant encounter = instant(encounterAt, "ENCOUNTER_AT");
        TenantContext context = requireContext();
        authorize(context, ActionPolicy.APPOINTMENT_READ, SUGGESTION_RESOURCE, patient,
                "FOLLOWUP_SUGGEST");

        ZoneId zone = config.facilityZone(facility);
        LocalDate encounterDate = encounter.atZone(zone).toLocalDate();
        PriorFollowUp prior = config.applicablePriorFollowUp(patient, visit, encounter).orElse(null);
        FollowUpRule rule = FollowUpRule.from(visit,
                config.setting(FollowUpRule.configKey(visit)).orElse(null));
        LocalDate suggested;
        Integer intervalDays;
        String basis;
        String explanation;
        if (prior != null) {
            suggested = prior.date();
            intervalDays = null;
            basis = "PRIOR_FOLLOW_UP";
            explanation = "prior follow-up " + prior.appointmentId() + " on " + prior.date()
                    + " takes precedence over the configured interval" + (rule == null
                    ? "; no configured interval is approved for visit type " + visit
                    : " of " + rule.intervalDays() + " days (" + rule.configKey() + ")");
        } else if (rule != null) {
            suggested = encounterDate.plusDays(rule.intervalDays());
            intervalDays = rule.intervalDays();
            basis = "CONFIGURED_INTERVAL";
            explanation = "visit type " + visit + ": approved interval " + rule.intervalDays()
                    + " days from encounter " + encounterDate + " (config key "
                    + rule.configKey() + ")";
        } else {
            audit.append(context, PURPOSE, "FOLLOWUP_SUGGEST", SUGGESTION_RESOURCE, SCOPE,
                    patient, "NOT_FOUND");
            throw new ApiException(422, "NO_FOLLOW_UP_RULE",
                    "no approved interval and no applicable prior follow-up");
        }
        FollowUpSuggestion suggestion = new FollowUpSuggestion(UUID.randomUUID().toString(),
                context.organizationId(), patient, facility, visit, encounter, zone.getId(),
                suggested, intervalDays, basis, explanation, null, false, null, 1);
        store.saveSuggestion(suggestion);
        audit.append(context, PURPOSE, "FOLLOWUP_SUGGEST", SUGGESTION_RESOURCE, SCOPE,
                suggestion.id(), "SUCCESS");
        return suggestion;
    }

    /**
     * Apply an authorized date override with recorded provenance.
     *
     * @param suggestionId suggestion identifier
     * @param requestedDate replacement civil date
     * @param reason        required override reason
     * @return a new suggestion version carrying the replacement date
     * @throws ApiException on invalid input, denial, concealment or a terminal suggestion
     */
    public FollowUpSuggestion override(String suggestionId, String requestedDate, String reason) {
        String id = uuid(suggestionId, "SUGGESTION_ID");
        LocalDate replacement = date(requestedDate, "REQUESTED_DATE");
        String recordedReason = text(reason, "REASON", MAX_REASON);
        TenantContext context = requireContext();
        authorize(context, ActionPolicy.APPOINTMENT_RESCHEDULE, SUGGESTION_RESOURCE, id,
                "FOLLOWUP_OVERRIDE");
        FollowUpSuggestion current = findSuggestion(context, id, "FOLLOWUP_OVERRIDE");
        if (current.confirmed()) {
            throw conflict(context, "FOLLOWUP_OVERRIDE", SUGGESTION_RESOURCE, id,
                    "ALREADY_CONFIRMED", "a confirmed follow-up cannot be overridden");
        }
        if (current.override() != null) {
            throw conflict(context, "FOLLOWUP_OVERRIDE", SUGGESTION_RESOURCE, id,
                    "ALREADY_OVERRIDDEN", "this suggestion is already overridden");
        }
        FollowUpOverride value = new FollowUpOverride(context.subject(), recordedReason,
                clock.instant(), current.suggestedDate(), replacement);
        FollowUpSuggestion updated = current.withOverride(value);
        store.saveSuggestion(updated);
        audit.append(context, PURPOSE, "FOLLOWUP_OVERRIDE", SUGGESTION_RESOURCE, SCOPE, id,
                "SUCCESS");
        return updated;
    }

    /**
     * Explicitly confirm a suggestion, creating exactly one booking.
     *
     * @param suggestionId    suggestion identifier
     * @param expectedVersion version read by the caller
     * @return the confirmed suggestion with its booking identifier
     * @throws ApiException on invalid input, denial, concealment, a stale version or re-confirmation
     */
    public FollowUpSuggestion confirm(String suggestionId, int expectedVersion) {
        String id = uuid(suggestionId, "SUGGESTION_ID");
        if (expectedVersion < 1) {
            throw new ApiException(422, "INVALID_VERSION", "version must be positive");
        }
        TenantContext context = requireContext();
        authorize(context, ActionPolicy.APPOINTMENT_BOOK, SUGGESTION_RESOURCE, id,
                "FOLLOWUP_CONFIRM");
        FollowUpSuggestion current = findSuggestion(context, id, "FOLLOWUP_CONFIRM");
        if (current.confirmed()) {
            throw conflict(context, "FOLLOWUP_CONFIRM", SUGGESTION_RESOURCE, id,
                    "ALREADY_CONFIRMED", "the follow-up is already confirmed");
        }
        if (current.version() != expectedVersion) {
            audit.append(context, PURPOSE, "FOLLOWUP_CONFIRM", SUGGESTION_RESOURCE, SCOPE, id,
                    "CONFLICT");
            throw new ApiException(412, "STALE_VERSION", "the suggestion changed since it was read");
        }
        BookingIntent intent = new BookingIntent(current.id(), context.organizationId(),
                current.patientId(), current.facilityCode(), current.visitType(),
                current.suggestedDate(), current.timeZone());
        String bookingId = booking.book(intent);
        FollowUpSuggestion confirmed = current.confirmed(bookingId);
        store.saveSuggestion(confirmed);
        audit.append(context, PURPOSE, "FOLLOWUP_CONFIRM", SUGGESTION_RESOURCE, SCOPE, id,
                "SUCCESS");
        return confirmed;
    }

    /**
     * Open a care-team action task with an accountable owner and a due date.
     *
     * @param followUpId  suggestion identifier the task supports
     * @param owner       accountable owner subject
     * @param dueDate     facility-local civil due date
     * @param instruction action for the owner
     * @return the open task
     * @throws ApiException on invalid input, denial or a concealed suggestion
     */
    public CareTeamTask createTask(String followUpId, String owner, String dueDate,
                                   String instruction) {
        String id = uuid(followUpId, "FOLLOW_UP_ID");
        String ownerSubject = text(owner, "OWNER", 64);
        LocalDate due = date(dueDate, "DUE_DATE");
        String detail = text(instruction, "INSTRUCTION", MAX_TEXT);
        TenantContext context = requireContext();
        authorize(context, ActionPolicy.APPOINTMENT_BOOK, TASK_RESOURCE, id,
                "FOLLOWUP_TASK_CREATE");
        findSuggestion(context, id, "FOLLOWUP_TASK_CREATE");
        CareTeamTask task = new CareTeamTask(UUID.randomUUID().toString(),
                context.organizationId(), id, ownerSubject, due, detail, CareTeamTask.OPEN,
                null, context.subject(), clock.instant());
        store.saveTask(task);
        audit.append(context, PURPOSE, "FOLLOWUP_TASK_CREATE", TASK_RESOURCE, SCOPE, task.id(),
                "SUCCESS");
        return task;
    }

    /**
     * Record the outcome of an open care-team task.
     *
     * @param taskId  task identifier
     * @param outcome recorded outcome
     * @return the completed task
     * @throws ApiException on invalid input, denial, concealment or a closed task
     */
    public CareTeamTask completeTask(String taskId, String outcome) {
        String id = uuid(taskId, "TASK_ID");
        String recordedOutcome = text(outcome, "OUTCOME", MAX_REASON);
        TenantContext context = requireContext();
        authorize(context, ActionPolicy.APPOINTMENT_COMPLETE, TASK_RESOURCE, id,
                "FOLLOWUP_TASK_COMPLETE");
        CareTeamTask task = findTask(context, id, "FOLLOWUP_TASK_COMPLETE");
        if (!CareTeamTask.OPEN.equals(task.status())) {
            throw conflict(context, "FOLLOWUP_TASK_COMPLETE", TASK_RESOURCE, id,
                    "ALREADY_COMPLETED", "the task is already closed");
        }
        CareTeamTask completed = task.complete(recordedOutcome);
        store.saveTask(completed);
        audit.append(context, PURPOSE, "FOLLOWUP_TASK_COMPLETE", TASK_RESOURCE, SCOPE, id,
                "SUCCESS");
        return completed;
    }

    /**
     * List the active tenant's tasks for one follow-up suggestion.
     *
     * @param followUpId suggestion identifier
     * @return the visible tasks
     * @throws ApiException on invalid input, denial or a concealed suggestion
     */
    public List<CareTeamTask> tasks(String followUpId) {
        String id = uuid(followUpId, "FOLLOW_UP_ID");
        TenantContext context = requireContext();
        authorize(context, ActionPolicy.APPOINTMENT_READ, TASK_RESOURCE, id, "FOLLOWUP_TASK_READ");
        findSuggestion(context, id, "FOLLOWUP_TASK_READ");
        List<CareTeamTask> result = store.tasksFor(context.organizationId(), id);
        audit.append(context, PURPOSE, "FOLLOWUP_TASK_READ", TASK_RESOURCE, SCOPE, id, "SUCCESS");
        return result;
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "no active tenant context");
        }
        return context;
    }

    private void authorize(TenantContext context, String action, String resourceType,
                           String resourceId, String auditAction) {
        if (!auth.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, auditAction, resourceType, SCOPE, resourceId, "DENIED");
            throw new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (auth.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            audit.append(context, PURPOSE, auditAction, resourceType, SCOPE, resourceId, "DENIED");
            throw new ApiException(403, "FORBIDDEN", "missing action permission");
        }
    }

    private FollowUpSuggestion findSuggestion(TenantContext context, String id, String auditAction) {
        FollowUpSuggestion found = store.findSuggestion(context.organizationId(), id).orElse(null);
        if (found == null) {
            audit.append(context, PURPOSE, auditAction, SUGGESTION_RESOURCE, SCOPE, id, "NOT_FOUND");
            throw new ApiException(404, "NOT_FOUND", "follow-up suggestion not found");
        }
        return found;
    }

    private CareTeamTask findTask(TenantContext context, String id, String auditAction) {
        CareTeamTask found = store.findTask(context.organizationId(), id).orElse(null);
        if (found == null) {
            audit.append(context, PURPOSE, auditAction, TASK_RESOURCE, SCOPE, id, "NOT_FOUND");
            throw new ApiException(404, "NOT_FOUND", "care-team task not found");
        }
        return found;
    }

    private ApiException conflict(TenantContext context, String auditAction, String resourceType,
                                  String resourceId, String code, String message) {
        audit.append(context, PURPOSE, auditAction, resourceType, SCOPE, resourceId, "CONFLICT");
        return new ApiException(409, code, message);
    }

    private static String uuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field, field + " must be a UUID");
        }
        return value;
    }

    private static String code(String value, String field) {
        try {
            return FacilityCode.facilityCode(value).value();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "INVALID_" + field, FacilityCode.INVALID_MESSAGE);
        }
    }

    private static Instant instant(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field, field + " must be a UTC instant");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + field, field + " must be a UTC instant");
        }
    }

    private static LocalDate date(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field, field + " must be an ISO-8601 date");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + field, field + " must be an ISO-8601 date");
        }
    }

    private static String text(String value, String field, int maximum) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > maximum) {
            throw new ApiException(422, "INVALID_" + field,
                    field + " must be 1-" + maximum + " characters");
        }
        return normalized;
    }
}
