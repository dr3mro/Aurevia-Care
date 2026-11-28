package com.aurevia.pharmacyflow.status;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.AcceptCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.ClarificationCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.DispenseCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.RejectCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusContract.StatusEvent;
import com.aurevia.pharmacyflow.status.PharmacyStatusContract.StatusView;
import com.aurevia.prescription.rx.RxContract.EventType;

/**
 * Pharmacy status domain kernel.
 *
 * <p>The kernel owns append-only status trails and immutable facts. It has no
 * HTTP or persistence dependency: a later story with a migration persists the
 * returned events inside one guarded transaction. The caller supplies verified
 * membership roles, the active tenant and an {@code If-Match} version
 * precondition and must re-check them on every command. An
 * {@code Idempotency-Key} replays the original fact so a retried action records
 * once.</p>
 *
 * <p>Acceptance, rejection, clarification and dispensing evidence are separate
 * facts. Recording dispensing evidence never marks a prescription accepted:
 * acceptance is derived only from a real acceptance fact. No inventory or stock
 * concept is held here; a dispensing fact only records who dispensed, when and
 * what was handed over.</p>
 */
@Service
public final class PharmacyStatusService {

    private static final String COMMAND_ACCEPT = "accept";
    private static final String COMMAND_REJECT = "reject";
    private static final String COMMAND_CLARIFICATION = "clarification";
    private static final String COMMAND_DISPENSE = "dispensing-evidence";
    private static final String IDEM_SEPARATOR = "|";

    private final Clock clock;
    private final Map<String, Map<String, List<StatusEvent>>> trails = new HashMap<>();
    private final Map<String, StatusEvent> idempotency = new HashMap<>();

    /**
     * Create the status kernel.
     *
     * @param clock application clock used for event occurrence times
     */
    public PharmacyStatusService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Record a pharmacy acceptance.
     *
     * @param command accept command with authenticated actor and precondition
     * @return the appended acceptance fact
     * @throws ApiException on invalid input, missing permission, stale version or a replay conflict
     */
    public StatusEvent accept(AcceptCommand command) {
        return record(command.organizationId(), command.subject(), command.roles(),
                command.prescriptionId(), command.expectedVersion(), command.idempotencyKey(),
                COMMAND_ACCEPT, EventType.PHARMACY_ACCEPTED, null, null, null, null);
    }

    /**
     * Record a pharmacy rejection after validating the mandatory reason.
     *
     * @param command reject command carrying the required reason
     * @return the appended rejection fact
     * @throws ApiException on invalid or missing reason, denial, stale version or a replay conflict
     */
    public StatusEvent reject(RejectCommand command) {
        PharmacyStatusInputs.requireText(command.reason(), "REASON_REQUIRED",
                "a rejection reason is required");
        return record(command.organizationId(), command.subject(), command.roles(),
                command.prescriptionId(), command.expectedVersion(), command.idempotencyKey(),
                COMMAND_REJECT, EventType.PHARMACY_REJECTED, command.reason(), null, null, null);
    }

    /**
     * Record a clarification request after validating its question.
     *
     * @param command clarification command carrying the required question
     * @return the appended clarification fact
     * @throws ApiException on invalid or missing question, denial, stale version or a replay conflict
     */
    public StatusEvent askClarification(ClarificationCommand command) {
        PharmacyStatusInputs.requireText(command.question(), "QUESTION_REQUIRED",
                "a clarification question is required");
        return record(command.organizationId(), command.subject(), command.roles(),
                command.prescriptionId(), command.expectedVersion(), command.idempotencyKey(),
                COMMAND_CLARIFICATION, EventType.CLARIFICATION, command.question(), null, null, null);
    }

    /**
     * Record dispensing evidence after validating who dispensed, when and what was handed over.
     *
     * <p>The fact is deliberately distinct from acceptance and never sets the
     * accepted flag on the projection.</p>
     *
     * @param command dispensing command carrying actor, time and handed-over detail
     * @return the appended dispensing fact
     * @throws ApiException on invalid evidence, denial, stale version or a replay conflict
     */
    public StatusEvent recordDispensing(DispenseCommand command) {
        PharmacyStatusInputs.requireText(command.dispensedBy(), "INVALID_DISPENSER",
                "the dispensing actor is required");
        PharmacyStatusInputs.requireInstant(command.dispensedAt(), "INVALID_DISPENSED_AT",
                "the dispensing time is required");
        PharmacyStatusInputs.requireText(command.handedOver(), "INVALID_HANDED_OVER",
                "the handed-over detail is required");
        return record(command.organizationId(), command.subject(), command.roles(),
                command.prescriptionId(), command.expectedVersion(), command.idempotencyKey(),
                COMMAND_DISPENSE, EventType.DISPENSING_EVIDENCE, null,
                command.dispensedBy(), command.dispensedAt(), command.handedOver());
    }

    /**
     * Read the current status projection of one prescription in the active tenant.
     *
     * @param organizationId active tenant
     * @param prescriptionId tenant-local prescription identifier
     * @return the current projection with an empty trail when nothing was recorded
     * @throws ApiException when the tenant is missing
     */
    public StatusView view(String organizationId, String prescriptionId) {
        PharmacyStatusInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        PharmacyStatusInputs.requireText(prescriptionId, "INVALID_PRESCRIPTION",
                "prescription identifier is required");
        List<StatusEvent> trail = trail(organizationId, prescriptionId);
        boolean accepted = trail.stream().anyMatch(event -> event.type() == EventType.PHARMACY_ACCEPTED);
        boolean dispensed = trail.stream().anyMatch(event -> event.type() == EventType.DISPENSING_EVIDENCE);
        return new StatusView(organizationId, prescriptionId, trail.size() + 1, accepted, dispensed, trail);
    }

    /**
     * Read the complete append-only fact trail of one prescription.
     *
     * @param organizationId active tenant
     * @param prescriptionId tenant-local prescription identifier
     * @return immutable facts in append order, or an empty list
     * @throws ApiException when the tenant is missing
     */
    public List<StatusEvent> history(String organizationId, String prescriptionId) {
        PharmacyStatusInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        PharmacyStatusInputs.requireText(prescriptionId, "INVALID_PRESCRIPTION",
                "prescription identifier is required");
        return List.copyOf(trail(organizationId, prescriptionId));
    }

    private synchronized StatusEvent record(String organizationId, String subject, Set<String> roles,
                                            String prescriptionId, int expectedVersion,
                                            String idempotencyKey, String command, EventType type,
                                            String detail, String dispensedBy, Instant dispensedAt,
                                            String handedOver) {
        PharmacyStatusInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        PharmacyStatusInputs.requireText(subject, "INVALID_ACTOR", "actor is required");
        PharmacyStatusInputs.requireText(prescriptionId, "INVALID_PRESCRIPTION",
                "prescription identifier is required");
        PharmacyStatusInputs.requireText(idempotencyKey, "INVALID_IDEMPOTENCY_KEY",
                "an idempotency key is required");
        PharmacyStatusInputs.requirePositive(expectedVersion, "INVALID_VERSION",
                "version must be a positive integer");
        if (!PharmacyStatusRules.mayRecord(roles)) {
            throw new ApiException(403, "FORBIDDEN", "missing pharmacy status permission");
        }
        String key = idempotencyKey(organizationId, subject, command, idempotencyKey);
        StatusEvent previous = idempotency.get(key);
        if (previous != null) {
            return previous;
        }
        List<StatusEvent> events = trails.computeIfAbsent(organizationId, ignored -> new HashMap<>())
                .computeIfAbsent(prescriptionId, ignored -> new ArrayList<>());
        int currentVersion = events.size() + 1;
        if (expectedVersion != currentVersion) {
            throw new ApiException(412, "STALE_VERSION", "the prescription status changed since it was read");
        }
        StatusEvent event = new StatusEvent(PharmacyStatusInputs.newId(), organizationId, prescriptionId,
                currentVersion, type, subject, clock.instant(), detail, dispensedBy, dispensedAt, handedOver);
        events.add(event);
        idempotency.put(key, event);
        return event;
    }

    private List<StatusEvent> trail(String organizationId, String prescriptionId) {
        Map<String, List<StatusEvent>> tenant = trails.get(organizationId);
        List<StatusEvent> events = tenant == null ? null : tenant.get(prescriptionId);
        return events == null ? List.of() : events;
    }

    private static String idempotencyKey(String organizationId, String subject, String command,
                                         String idempotencyKey) {
        return organizationId + IDEM_SEPARATOR + subject + IDEM_SEPARATOR + command
                + IDEM_SEPARATOR + idempotencyKey;
    }
}
