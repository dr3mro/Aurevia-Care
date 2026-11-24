package com.aurevia.scheduling;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Tenant administration of scheduling reference data: visit durations, protected
 * clinician and room resources, and their weekly availability windows.
 *
 * <p>These are operational rows rather than approval-gated policy, which is why
 * they live beside the appointment tables instead of the configuration fold. The
 * booking rules that do change policy behaviour are read from the approved
 * configuration fold (see {@code BookingRules}).</p>
 *
 * <p>A denied command appends its audit event and still commits it, so the
 * denial survives; command failures roll the transaction back.</p>
 */
@Service
public class SchedulingAdminService {

    private static final String SCOPE = "SCHEDULING";
    private static final String RESOURCE_TYPE = "SCHEDULING_RESOURCE";
    private static final List<String> KINDS = List.of("clinician", "room");
    private static final int MAX_NAME = 200;

    private final SchedulingRepository scheduling;
    private final ActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;

    /**
     * Create the service.
     *
     * @param scheduling   RLS-bounded reference-data repository
     * @param policy       action policy
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     */
    public SchedulingAdminService(SchedulingRepository scheduling, ActionPolicy policy,
                                  TransactionTemplate transactions, AuditAppender audit) {
        this.scheduling = scheduling;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Define a visit type and its appointment duration.
     *
     * @param code            tenant-local visit type code
     * @param durationMinutes appointment length in minutes
     * @return the created visit type
     * @throws ApiException on invalid input, denial or a duplicate code
     */
    public VisitTypeView createVisitType(String code, Integer durationMinutes) {
        String validCode = SchedulingInputs.code(code, "code");
        int duration = SchedulingInputs.duration(durationMinutes);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        VisitTypeView created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            if (authorize(context, failure)) {
                return null;
            }
            scheduling.insertVisitType(validCode, duration);
            audit.append(context, "VISIT_TYPE_CREATE", "VISIT_TYPE_CREATE", "VISIT_TYPE", SCOPE,
                    validCode, "SUCCESS");
            return new VisitTypeView(validCode, duration);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Define a protected clinician or room resource inside a facility.
     *
     * @param facilityCode tenant-local facility code
     * @param kind         {@code clinician} or {@code room}
     * @param code         tenant-local resource code
     * @param displayName  display name
     * @return the created resource
     * @throws ApiException on invalid input, denial, unknown facility or duplicate code
     */
    public ResourceView createResource(String facilityCode, String kind, String code,
                                       String displayName) {
        String validFacility = SchedulingInputs.code(facilityCode, "facilityCode");
        if (!KINDS.contains(kind)) {
            throw new ApiException(422, "INVALID_KIND", "kind must be clinician or room");
        }
        String validCode = SchedulingInputs.code(code, "code");
        String validName = SchedulingInputs.text(displayName, "displayName", MAX_NAME);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ResourceView created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            if (authorize(context, failure)) {
                return null;
            }
            FacilityInfo facility = scheduling.facility(validFacility)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "facility not found"));
            String id = SchedulingInputs.newId();
            scheduling.insertResource(id, facility.id(), kind, validCode, validName);
            audit.append(context, "RESOURCE_CREATE", "RESOURCE_CREATE", RESOURCE_TYPE, SCOPE,
                    validCode, "SUCCESS");
            return new ResourceView(id, facility.code(), kind, validCode, validName);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Add one weekly availability window to a resource.
     *
     * @param resourceCode tenant-local resource code
     * @param weekday      ISO-8601 day of week, 1 = Monday
     * @param startLocal   local window start, {@code HH:mm}
     * @param endLocal     local window end, {@code HH:mm}
     * @return the created window
     * @throws ApiException on invalid input, denial, unknown resource or an
     *                      inverted window
     */
    public AvailabilityView addAvailability(String resourceCode, Integer weekday, String startLocal,
                                            String endLocal) {
        String validCode = SchedulingInputs.code(resourceCode, "resourceCode");
        int day = SchedulingInputs.weekday(weekday);
        LocalTime start = SchedulingInputs.localTime(startLocal, "startLocal");
        LocalTime end = SchedulingInputs.localTime(endLocal, "endLocal");
        if (!end.isAfter(start)) {
            throw new ApiException(422, "INVALID_WINDOW", "endLocal must be later than startLocal");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        AvailabilityView created = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            if (authorize(context, failure)) {
                return null;
            }
            ResourceView resource = scheduling.resourceByCode(validCode)
                    .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "resource not found"));
            scheduling.insertAvailability(resource.id(), day, start.toString(), end.toString());
            audit.append(context, "AVAILABILITY_CREATE", "AVAILABILITY_CREATE", RESOURCE_TYPE,
                    SCOPE, validCode, "SUCCESS");
            return new AvailabilityView(resource.facilityCode(), validCode, day,
                    start.toString(), end.toString());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * List the resources of one facility.
     *
     * @param facilityCode tenant-local facility code
     * @return visible resources ordered by kind and code
     * @throws ApiException on invalid input or denial
     */
    public List<ResourceView> resources(String facilityCode) {
        String validFacility = SchedulingInputs.code(facilityCode, "facilityCode");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<ResourceView> found = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            if (authorize(context, failure)) {
                return List.of();
            }
            List<ResourceView> resources = scheduling.resources(validFacility);
            audit.append(context, "RESOURCE_READ", "RESOURCE_READ", RESOURCE_TYPE, SCOPE,
                    validFacility, "SUCCESS");
            return resources;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return found;
    }

    /**
     * Re-check membership and the administration permission inside the transaction.
     *
     * @param context active tenant context
     * @param failure receives the denial so the audit row can commit
     * @return {@code true} when the caller was denied
     */
    private boolean authorize(TenantContext context, AtomicReference<ApiException> failure) {
        if (!scheduling.hasActiveMembership(context.subject())) {
            audit.append(context, ActionPolicy.SCHEDULING_ADMIN, ActionPolicy.SCHEDULING_ADMIN,
                    RESOURCE_TYPE, SCOPE, "ADMIN", "DENIED");
            failure.set(new ApiException(403, "FORBIDDEN", "no active membership"));
            return true;
        }
        if (scheduling.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(ActionPolicy.SCHEDULING_ADMIN, role))) {
            audit.append(context, ActionPolicy.SCHEDULING_ADMIN, ActionPolicy.SCHEDULING_ADMIN,
                    RESOURCE_TYPE, SCOPE, "ADMIN", "DENIED");
            failure.set(new ApiException(403, "FORBIDDEN", "missing scheduling administration permission"));
            return true;
        }
        return false;
    }
}
