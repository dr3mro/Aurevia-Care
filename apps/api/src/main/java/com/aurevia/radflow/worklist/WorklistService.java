package com.aurevia.radflow.worklist;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.radflow.worklist.WorklistCommands.LinkStudyCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ReceiveCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ReportCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ScheduleCommand;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistEventType;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistStatus;

/**
 * Performing-site worklist domain kernel.
 *
 * <p>The kernel owns append-only worklist revisions and immutable domain facts.
 * It deliberately has no HTTP, persistence or outbox dependency: a later story
 * with a migration persists the returned revisions and events inside one guarded
 * transaction. The caller supplies verified membership roles, the active tenant
 * and a version precondition and must re-check them on every command.</p>
 *
 * <p>The external study is linked only after the supplied patient and accession
 * labels match the order, reusing the radiology patient-and-accession check. A
 * mismatch is recorded as a refused-link fact and changes no order revision.
 * Linking a valid study marks the order performed and emits the hand-off fact
 * that the reporting stage consumes. Tenant scoping applies to every read;
 * site scoping is enforced by the site-aware read methods and commands.</p>
 */
@Service
public final class WorklistService {

    private final Clock clock;
    private final Map<String, Map<String, List<WorklistOrder>>> orders = new HashMap<>();
    private final Map<String, List<WorklistEvent>> events = new HashMap<>();

    /**
     * Create the worklist service.
     *
     * @param clock application clock used for occurrence times and hand-off time
     */
    public WorklistService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Receive an imaging order onto the performing-site worklist.
     *
     * @param command receive command with authenticated actor and matched patient
     * @return the initial {@code RECEIVED} order revision
     * @throws ApiException on invalid input, missing permission or a duplicate order identifier
     */
    public WorklistOrder receive(ReceiveCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.performingSiteId(), "INVALID_SITE", "performing site is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.orderCode(), "INVALID_ORDER_CODE", "order code is required");
        requireText(command.clinicalQuestion(), "NO_CLINICAL_QUESTION", "a clinical question is required");
        requireText(command.referrer(), "INVALID_REFERRER", "referrer is required");
        if (command.urgency() == null) {
            throw new ApiException(422, "INVALID_URGENCY", "urgency is required");
        }
        if (!WorklistRules.mayReceive(command.roles())) {
            throw forbidden("receive");
        }
        Map<String, List<WorklistOrder>> tenant = orders.computeIfAbsent(
                command.organizationId(), key -> new HashMap<>());
        if (tenant.containsKey(command.orderId())) {
            throw new ApiException(409, "DUPLICATE_ORDER", "the order identifier already exists");
        }
        WorklistOrder revision = new WorklistOrder(command.organizationId(), command.orderId(),
                command.performingSiteId(), command.patientId(), command.orderCode(), command.urgency(),
                command.clinicalQuestion(), command.referrer(), WorklistStatus.RECEIVED, 1,
                null, null, null, null, null, null, null, null, null, null);
        appendOrder(tenant, revision);
        emit(revision, WorklistEventType.ORDER_RECEIVED, null, command.subject());
        return revision;
    }

    /**
     * Record the study time, a supplied modality and the accession at scheduling.
     *
     * @param command schedule command with the expected order version
     * @return the new {@code SCHEDULED} order revision
     * @throws ApiException when denied, concealed, stale, invalid or illegally transitioned
     */
    public WorklistOrder schedule(ScheduleCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.performingSiteId(), "INVALID_SITE", "performing site is required");
        if (command.scheduledAt() == null) {
            throw new ApiException(422, "INVALID_SCHEDULE", "a scheduled time is required");
        }
        if (command.modality() == null) {
            throw new ApiException(422, "INVALID_MODALITY", "an approved modality is required");
        }
        requireText(command.accession(), "INVALID_ACCESSION", "accession is required");
        requireText(command.accessionIssuer(), "INVALID_ISSUER", "accession issuer is required");
        if (!WorklistRules.maySchedule(command.roles())) {
            throw forbidden("schedule");
        }
        WorklistOrder current = load(command.organizationId(), command.orderId());
        requireSite(current, command.performingSiteId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, WorklistStatus.SCHEDULED);
        WorklistOrder scheduled = new WorklistOrder(current.organizationId(), current.orderId(),
                current.performingSiteId(), current.patientId(), current.orderCode(), current.urgency(),
                current.clinicalQuestion(), current.referrer(), WorklistStatus.SCHEDULED,
                current.version() + 1, command.scheduledAt(), command.modality(), command.accession(),
                command.accessionIssuer(), null, null, null, null, null, null);
        appendOrder(orders.get(command.organizationId()), scheduled);
        emit(scheduled, WorklistEventType.ORDER_SCHEDULED, command.modality().wire(), command.subject());
        return scheduled;
    }

    /**
     * Link an external study after the patient and accession labels match, marking it performed.
     *
     * <p>The caller supplies labels already verified upstream. A mismatch is
     * recorded as a refused-link fact and throws without changing the order
     * revision, so a wrong-patient study can never enter the performed stage. A
     * successful link emits both the performance fact and the hand-off fact.</p>
     *
     * @param command link command with the verified patient and accession
     * @return the new {@code PERFORMED} order revision
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public WorklistOrder linkStudy(LinkStudyCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.performingSiteId(), "INVALID_SITE", "performing site is required");
        requireText(command.studyInstanceUid(), "INVALID_STUDY", "study instance UID is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.accession(), "INVALID_ACCESSION", "accession is required");
        requireText(command.workstationRef(), "INVALID_WORKSTATION", "partner workstation is required");
        if (!WorklistRules.mayLinkStudy(command.roles())) {
            throw forbidden("study link");
        }
        WorklistOrder current = load(command.organizationId(), command.orderId());
        requireSite(current, command.performingSiteId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, WorklistStatus.PERFORMED);
        // A label mismatch is emitted as a refusal fact without changing the order
        // revision, so a bad external link cannot advance the worklist.
        if (!matches(current, command.patientId(), command.accession())) {
            emit(current, WorklistEventType.STUDY_LINK_REFUSED, command.studyInstanceUid(), command.subject());
            throw new ApiException(422, "LABEL_MISMATCH", "patient and accession must match the order");
        }
        WorklistOrder performed = new WorklistOrder(current.organizationId(), current.orderId(),
                current.performingSiteId(), current.patientId(), current.orderCode(), current.urgency(),
                current.clinicalQuestion(), current.referrer(), WorklistStatus.PERFORMED,
                current.version() + 1, current.scheduledAt(), current.modality(), current.accession(),
                current.accessionIssuer(), command.studyInstanceUid(), command.workstationRef(),
                command.patientId(), command.accession(), clock.instant(), null);
        appendOrder(orders.get(command.organizationId()), performed);
        emit(performed, WorklistEventType.STUDY_PERFORMED, command.studyInstanceUid(), command.subject());
        emit(performed, WorklistEventType.HANDOFF_RECORDED, current.referrer(), command.subject());
        return performed;
    }

    /**
     * Hand a performed study off as reported once its study is linked.
     *
     * @param command report command with the expected order version
     * @return the new {@code REPORTED} order revision
     * @throws ApiException when denied, concealed, stale or illegally transitioned
     */
    public WorklistOrder report(ReportCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.performingSiteId(), "INVALID_SITE", "performing site is required");
        if (!WorklistRules.mayReport(command.roles())) {
            throw forbidden("report");
        }
        WorklistOrder current = load(command.organizationId(), command.orderId());
        requireSite(current, command.performingSiteId());
        requireVersion(current, command.expectedVersion());
        requireTransition(current, WorklistStatus.REPORTED);
        WorklistOrder reported = new WorklistOrder(current.organizationId(), current.orderId(),
                current.performingSiteId(), current.patientId(), current.orderCode(), current.urgency(),
                current.clinicalQuestion(), current.referrer(), WorklistStatus.REPORTED,
                current.version() + 1, current.scheduledAt(), current.modality(), current.accession(),
                current.accessionIssuer(), current.studyInstanceUid(), current.workstationRef(),
                current.verifiedPatientId(), current.verifiedAccession(), current.performedAt(),
                clock.instant());
        appendOrder(orders.get(command.organizationId()), reported);
        emit(reported, WorklistEventType.ORDER_REPORTED, null, command.subject());
        return reported;
    }

    /**
     * Read the worklist of one performing site in the active tenant.
     *
     * <p>Both the tenant and the performing site are mandatory scopes: an order
     * is returned only when both match. An optional status narrows the result to
     * one step of the workflow. A site or tenant with no orders gets an empty
     * worklist rather than another site's data.</p>
     *
     * @param organizationId  active tenant
     * @param performingSiteId performing site whose worklist is requested
     * @param status          optional exact status filter, or {@code null} for all
     * @return immutable current worklist entries
     */
    public List<WorklistEntry> worklist(String organizationId, String performingSiteId,
                                        WorklistStatus status) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(performingSiteId, "INVALID_SITE", "performing site is required");
        Map<String, List<WorklistOrder>> tenant = orders.get(organizationId);
        if (tenant == null) {
            return List.of();
        }
        List<WorklistEntry> result = new ArrayList<>();
        for (List<WorklistOrder> revisions : tenant.values()) {
            WorklistOrder current = revisions.get(revisions.size() - 1);
            if (!current.performingSiteId().equals(performingSiteId)) {
                continue;
            }
            if (status != null && current.status() != status) {
                continue;
            }
            result.add(WorklistEntry.from(current));
        }
        return List.copyOf(result);
    }

    /**
     * Read the current worklist order revision in the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return the current order revision
     * @throws ApiException when the order is not found in this tenant
     */
    public WorklistOrder read(String organizationId, String orderId) {
        return load(organizationId, orderId);
    }

    /**
     * Read the current order revision only when it belongs to the given performing site.
     *
     * <p>This is the HTTP read entry point: both the tenant and the performing
     * site must match, so another site in the same tenant cannot see an order's
     * detail. A mismatch is concealed as a forbidden outcome rather than leaking
     * the order.</p>
     *
     * @param organizationId   active tenant
     * @param performingSiteId performing site the caller is acting for
     * @param orderId          tenant-local order identifier
     * @return the current order revision
     * @throws ApiException when the site differs, the order is absent, or unauthenticated
     */
    public WorklistOrder readForSite(String organizationId, String performingSiteId, String orderId) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(performingSiteId, "INVALID_SITE", "performing site is required");
        WorklistOrder current = load(organizationId, orderId);
        requireSite(current, performingSiteId);
        return current;
    }

    /**
     * Read the append-only revision history of one worklist order.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return revisions oldest first
     * @throws ApiException when the order is not found in this tenant
     */
    public List<WorklistOrder> history(String organizationId, String orderId) {
        return List.copyOf(loadRevisions(organizationId, orderId));
    }

    /**
     * Return the immutable domain facts emitted for one tenant.
     *
     * @param organizationId active tenant
     * @return that tenant's events in emission order, or an empty list
     */
    public List<WorklistEvent> events(String organizationId) {
        return List.copyOf(events.getOrDefault(organizationId, List.of()));
    }

    /**
     * Read the domain facts of one performing site in the active tenant.
     *
     * <p>Facts carry patient and accession identifiers, so they are scoped to the
     * performing site as well as the tenant. A caller cannot list another site's
     * facts by switching a query parameter within the same tenant.</p>
     *
     * @param organizationId   active tenant
     * @param performingSiteId performing site whose facts are requested
     * @return immutable facts for that site in emission order
     */
    public List<WorklistEvent> eventsForSite(String organizationId, String performingSiteId) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(performingSiteId, "INVALID_SITE", "performing site is required");
        return events.getOrDefault(organizationId, List.of()).stream()
                .filter(event -> performingSiteId.equals(event.performingSiteId()))
                .toList();
    }

    private void appendOrder(Map<String, List<WorklistOrder>> tenant, WorklistOrder revision) {
        tenant.computeIfAbsent(revision.orderId(), key -> new ArrayList<>()).add(revision);
    }

    private void emit(WorklistOrder order, WorklistEventType type, String destination, String actor) {
        events.computeIfAbsent(order.organizationId(), key -> new ArrayList<>())
                .add(new WorklistEvent(order.organizationId(), type, order.orderId(), order.version(),
                        order.patientId(), order.performingSiteId(), order.accession(), actor,
                        destination, clock.instant()));
    }

    private WorklistOrder load(String organizationId, String orderId) {
        List<WorklistOrder> revisions = loadRevisions(organizationId, orderId);
        return revisions.get(revisions.size() - 1);
    }

    private List<WorklistOrder> loadRevisions(String organizationId, String orderId) {
        Map<String, List<WorklistOrder>> tenant = orders.get(organizationId);
        List<WorklistOrder> revisions = tenant == null ? null : tenant.get(orderId);
        if (revisions == null) {
            throw notFound();
        }
        return revisions;
    }

    private static void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }

    private static void requireVersion(WorklistOrder current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the order changed since it was read");
        }
    }

    // The worklist advances only forward and REPORTED is terminal; an older
    // revision cannot be reopened by a late scheduling or reporting command.
    private static void requireTransition(WorklistOrder current, WorklistStatus target) {
        if (!current.status().canTransitionTo(target)) {
            throw new ApiException(409, "INVALID_TRANSITION", "the order cannot enter that status");
        }
    }

    private static void requireSite(WorklistOrder current, String performingSiteId) {
        if (!current.performingSiteId().equals(performingSiteId)) {
            throw forbidden("performing site");
        }
    }

    private static boolean matches(WorklistOrder current, String patientId, String accession) {
        return current.patientId().equals(patientId) && current.accession().equals(accession);
    }

    private static ApiException forbidden(String action) {
        return new ApiException(403, "FORBIDDEN", "missing permission for " + action);
    }

    private static ApiException notFound() {
        return new ApiException(404, "NOT_FOUND", "imaging worklist order not found");
    }
}
