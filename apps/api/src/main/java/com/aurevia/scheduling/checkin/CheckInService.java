package com.aurevia.scheduling.checkin;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.SchedulingRepository;
import com.aurevia.tenant.TenantContext;

import com.aurevia.scheduling.checkin.CheckInCommands.Assign;
import com.aurevia.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.scheduling.checkin.CheckInCommands.Intake;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;

/**
 * Check-in, worklist and handoff commands for CLN-02.
 *
 * <p>Every command runs inside one tenant-guarded transaction. Roles and
 * membership are re-derived on each request; a deactivated member fails closed
 * on the next protected action (IAM-02). Authorization, evidence and audit
 * commit together so a refusal leaves no partial verification or assignment.</p>
 *
 * <p>The booked to checked-in and checked-in to in-progress transitions stay
 * the responsibility of the shared
 * {@link com.aurevia.scheduling.AppointmentService}, which owns the
 * V8 state machine and history. This slice adds the identity evidence and the
 * worklist projection around those transitions rather than duplicating them.</p>
 */
@Service
public class CheckInService {

    private static final String SCOPE = "CHECK_IN_WORKLIST";
    private static final String RESOURCE_TYPE = "WORKLIST_ITEM";
    private static final String PRIORITY_PREFIX = "clinic.worklist.priority.";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final WorklistRepository repository;
    private final AppointmentService appointments;
    private final SchedulingRepository scheduling;
    private final WorklistPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded CLN-02 repository
     * @param appointments shared owner of the V8 appointment state machine
     * @param scheduling   source of live membership, roles and configuration
     * @param policy       local CLN-02 action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        application clock for assignment and verification times
     */
    public CheckInService(WorklistRepository repository, AppointmentService appointments,
                          SchedulingRepository scheduling, WorklistPolicy policy,
                          TransactionTemplate transactions, AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.appointments = appointments;
        this.scheduling = scheduling;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Record the identity evidence presented at check-in.
     *
     * @param request verification command
     * @return the immutable verification record
     * @throws ApiException on invalid input, denial, an unknown appointment or a duplicate record
     */
    public Verification verify(VerifyIdentity request) {
        String appointmentId = WorkflowInputs.uuid(request.appointmentId(), "appointmentId");
        String method = WorkflowInputs.method(request.method());
        boolean alternate = "documented_alternate".equals(method);
        List<String> identifiers = alternate ? List.of()
                : WorkflowInputs.identifiers(request.identifiers());
        String alternateCode = alternate
                ? WorkflowInputs.alternate(request.alternateCode(), request.alternateReason()) : null;
        String alternateReason = alternate
                ? WorkflowInputs.text(request.alternateReason(), "alternateReason") : null;
        String id = WorkflowInputs.newId();
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Verification result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.IDENTITY_VERIFY, appointmentId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Appointment appointment = appointments.get(appointmentId).appointment();
            ApiException scopeDenial = facilityDenial(context, WorklistPolicy.IDENTITY_VERIFY,
                    appointmentId, appointment.facilityCode());
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            if (repository.hasVerification(appointmentId)) {
                throw new ApiException(409, "DUPLICATE_VERIFICATION",
                        "identity was already verified for this appointment");
            }
            String facilityId = repository.facilityId(appointment.facilityCode())
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            try {
                repository.insertVerification(id, appointmentId, appointment.patientId(), facilityId,
                        method, identifiers, alternateCode, alternateReason);
            } catch (DataIntegrityViolationException exception) {
                // A concurrent verifier may win between the existence check and the
                // insert; the UNIQUE(org, appointment) constraint is the authority.
                throw new ApiException(409, "DUPLICATE_VERIFICATION",
                        "identity was already verified for this appointment");
            }
            audit.append(context, WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.IDENTITY_VERIFY,
                    RESOURCE_TYPE, SCOPE, appointmentId, "SUCCESS");
            return new Verification(id, appointmentId, appointment.patientId(),
                    appointment.facilityCode(), method, identifiers, alternateCode, alternateReason,
                    context.subject(), clock.instant());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Check a verified patient in, moving the appointment to {@code checked_in}.
     *
     * @param appointmentId appointment identifier
     * @param expectedVersion version read by the caller
     * @return the worklist entry created for the checked-in arrival
     * @throws ApiException on denial, missing verification, a stale version or an illegal transition
     */
    public WorklistItem checkIn(String appointmentId, int expectedVersion) {
        String id = WorkflowInputs.uuid(appointmentId, "appointmentId");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        WorklistItem result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.CHECK_IN, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.hasVerification(id)) {
                throw new ApiException(409, "VERIFICATION_REQUIRED",
                        "identity must be verified before check-in");
            }
            Appointment checkedIn = appointments.checkIn(id, expectedVersion);
            ApiException scopeDenial = facilityDenial(context, WorklistPolicy.CHECK_IN, id,
                    checkedIn.facilityCode());
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            int priority = priorityFor(checkedIn.visitType());
            String facilityId = repository.facilityId(checkedIn.facilityCode())
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            String entryId = repository.insertItem(id, null, checkedIn.patientId(), facilityId,
                    priority, null, null);
            audit.append(context, "VERIFIED_CHECK_IN", "VERIFIED_CHECK_IN", RESOURCE_TYPE, SCOPE,
                    id, "SUCCESS");
            return item(entryId, id, null, checkedIn, priority, null, null, 1);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Read the ordered worklist of one facility.
     *
     * @param facilityCode facility code that scopes the worklist
     * @param limit        optional bounded page size
     * @return worklist items ordered by scheduled time then configured priority
     * @throws ApiException on invalid input or denial
     */
    public List<WorklistItem> worklist(String facilityCode, Integer limit) {
        String code = WorkflowInputs.code(facilityCode, "facilityCode");
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be 1-" + MAX_LIMIT);
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<WorklistItem> result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.WORKLIST_READ, code);
            if (denial != null) {
                failure.set(denial);
                return List.<WorklistItem>of();
            }
            ApiException scopeDenial = facilityDenial(context, WorklistPolicy.WORKLIST_READ, code, code);
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return List.<WorklistItem>of();
            }
            List<WorklistItem> items = repository.listWorklist(code, size);
            audit.append(context, "WORKLIST_READ", "WORKLIST_READ", RESOURCE_TYPE, SCOPE, code,
                    "SUCCESS");
            return List.copyOf(items);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Assign an unassigned worklist item to an active member.
     *
     * @param itemId          worklist entry identifier
     * @param request         assignee subject
     * @param expectedVersion version read by the caller
     * @return the assigned entry with a fresh assignment timestamp
     * @throws ApiException on denial, an unknown entry, a stale version or an inactive assignee
     */
    public WorklistItem assign(String itemId, Assign request, int expectedVersion) {
        String id = WorkflowInputs.uuid(itemId, "itemId");
        String assignee = WorkflowInputs.text(request.assigneeSubject(), "assigneeSubject");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        WorklistItem result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.WORKLIST_ASSIGN, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            WorklistItem current = repository.findItemForUpdate(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "worklist item not found"));
            ApiException scopeDenial = facilityDenial(context, WorklistPolicy.WORKLIST_ASSIGN, id,
                    current.facilityCode());
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            requireVersion(current.version(), expectedVersion);
            if (current.assigneeSubject() != null) {
                throw new ApiException(409, "ALREADY_ASSIGNED", "the item already has an assignee");
            }
            requireActiveMember(assignee);
            Instant now = clock.instant();
            requireUpdated(repository.updateItem(id, expectedVersion, assignee, now, current.priority()));
            repository.insertHandoff(new HandoffEvent(WorkflowInputs.newId(), id, null, assignee,
                    null, context.subject(), now));
            audit.append(context, WorklistPolicy.WORKLIST_ASSIGN, WorklistPolicy.WORKLIST_ASSIGN,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return new WorklistItem(current.id(), current.appointmentId(), current.intakeReason(),
                    current.patientId(), current.facilityCode(), current.startsAt(), current.priority(),
                    assignee, now, expectedVersion + 1);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Transfer an assigned worklist item to another active member.
     *
     * @param itemId          worklist entry identifier
     * @param request         receiving subject and recorded reason
     * @param expectedVersion version read by the caller
     * @return the transferred entry
     * @throws ApiException on denial, no active source assignee, an inactive target or a stale version
     */
    public WorklistItem handoff(String itemId, Handoff request, int expectedVersion) {
        String id = WorkflowInputs.uuid(itemId, "itemId");
        String to = WorkflowInputs.text(request.toSubject(), "toSubject");
        String reason = WorkflowInputs.text(request.reason(), "reason");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        WorklistItem result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.HANDOFF, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            WorklistItem current = repository.findItemForUpdate(id)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "worklist item not found"));
            ApiException scopeDenial = facilityDenial(context, WorklistPolicy.HANDOFF, id,
                    current.facilityCode());
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            requireVersion(current.version(), expectedVersion);
            if (current.assigneeSubject() == null) {
                throw new ApiException(409, "NO_ACTIVE_ASSIGNEE",
                        "an item must have an active assignee before a handoff");
            }
            if (!scheduling.hasActiveMembership(current.assigneeSubject())) {
                throw new ApiException(409, "ASSIGNEE_INACTIVE",
                        "the current assignee is no longer an active member");
            }
            requireActiveMember(to);
            if (current.assigneeSubject().equals(to)) {
                throw new ApiException(409, "INVALID_HANDOFF", "the receiving member is already assigned");
            }
            Instant now = clock.instant();
            requireUpdated(repository.updateItem(id, expectedVersion, to, now, current.priority()));
            repository.insertHandoff(new HandoffEvent(WorkflowInputs.newId(), id,
                    current.assigneeSubject(), to, reason, context.subject(), now));
            audit.append(context, WorklistPolicy.HANDOFF, WorklistPolicy.HANDOFF, RESOURCE_TYPE,
                    SCOPE, id, "SUCCESS");
            return new WorklistItem(current.id(), current.appointmentId(), current.intakeReason(),
                    current.patientId(), current.facilityCode(), current.startsAt(), current.priority(),
                    to, now, expectedVersion + 1);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Link an unscheduled arrival to intake and place it on the worklist.
     *
     * @param request intake command
     * @return the created worklist entry
     * @throws ApiException on invalid input, denial, or an unknown patient or facility
     */
    public WorklistItem linkIntake(Intake request) {
        String facilityCode = WorkflowInputs.code(request.facilityCode(), "facilityCode");
        String patientId = WorkflowInputs.uuid(request.patientId(), "patientId");
        String reason = WorkflowInputs.text(request.reason(), "reason");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        WorklistItem result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.INTAKE_LINK, patientId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ApiException scopeDenial = facilityDenial(context, WorklistPolicy.INTAKE_LINK, patientId,
                    facilityCode);
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            String facilityId = repository.facilityId(facilityCode)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            int priority = priorityFor(null);
            String id = repository.insertItem(null, reason, patientId, facilityId, priority, null, null);
            audit.append(context, WorklistPolicy.INTAKE_LINK, WorklistPolicy.INTAKE_LINK,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return new WorklistItem(id, null, reason, patientId, facilityCode, null, priority,
                    null, null, 1);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Open the clinical encounter for a verified, checked-in appointment.
     *
     * @param appointmentId appointment identifier
     * @param expectedVersion version read by the caller
     * @return the in-progress appointment
     * @throws ApiException on denial, missing verification, a stale version or an illegal transition
     */
    public Appointment openEncounter(String appointmentId, int expectedVersion) {
        String id = WorkflowInputs.uuid(appointmentId, "appointmentId");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Appointment result = transactions.execute(status -> {
            TenantContext context = requireContext();
            ApiException denial = authorize(context, WorklistPolicy.ENCOUNTER_OPEN, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.hasVerification(id)) {
                throw new ApiException(409, "VERIFICATION_REQUIRED",
                        "identity must be verified before the encounter opens");
            }
            Appointment opened = appointments.start(id, expectedVersion);
            audit.append(context, WorklistPolicy.ENCOUNTER_OPEN, WorklistPolicy.ENCOUNTER_OPEN,
                    RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return opened;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private int priorityFor(String visitType) {
        String key = visitType == null ? "clinic.worklist.default_priority"
                : PRIORITY_PREFIX + visitType;
        return WorkflowInputs.priority(scheduling.setting(key).orElse(null));
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "FORBIDDEN", "no authenticated tenant context");
        }
        return context;
    }

    /**
     * Authorize a command, returning (not throwing) the denial so the caller can
     * commit the DENIED audit row before returning the failure to the client.
     * Throwing inside the guarded transaction would roll that audit row back.
     *
     * @param context    active tenant context
     * @param action     action key from
     *                   {@link com.aurevia.scheduling.checkin.WorklistPolicy}
     * @param resourceId resource the action targets
     * @return the denial to throw after commit, or {@code null} when allowed
     */
    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!scheduling.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (scheduling.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing worklist permission");
        }
        return null;
    }

    /**
     * Apply the IAM-01 facility scope to a facility-aware command. The denial is
     * returned, not thrown, so the guarded transaction still commits its DENIED
     * audit row before the caller reports the failure to the client.
     *
     * @param context      active tenant context
     * @param action       action key being authorized
     * @param resourceId   resource addressed by the command, for the audit row
     * @param facilityCode facility the resource belongs to
     * @return the denial to throw after commit, or {@code null} when in scope
     */
    private ApiException facilityDenial(TenantContext context, String action, String resourceId,
                                        String facilityCode) {
        if (repository.outsideAssignedFacilities(context.subject(), facilityCode)) {
            audit.append(context, action, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "facility is outside the assigned scope");
        }
        return null;
    }

    private void requireActiveMember(String subject) {
        if (!scheduling.hasActiveMembership(subject)) {
            throw new ApiException(422, "INVALID_ASSIGNEE",
                    "assignee must have active membership in the active organization");
        }
    }

    private void requireVersion(int actual, int expected) {
        if (actual != expected) {
            throw new ApiException(412, "STALE_VERSION", "the worklist item changed since it was read");
        }
    }

    private void requireUpdated(int rows) {
        if (rows == 0) {
            throw new ApiException(412, "STALE_VERSION", "the worklist item changed since it was read");
        }
    }

    private WorklistItem item(String id, String appointmentId, String intakeReason,
                              Appointment appointment, int priority, String assignee,
                              Instant assignedAt, int version) {
        return new WorklistItem(id, appointmentId, intakeReason, appointment.patientId(),
                appointment.facilityCode(), appointment.startsAt(), priority, assignee, assignedAt,
                version);
    }
}
