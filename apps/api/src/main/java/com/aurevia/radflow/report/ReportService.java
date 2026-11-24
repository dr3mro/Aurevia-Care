package com.aurevia.radflow.report;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radflow.report.ReportCommands.AcknowledgeCommand;
import com.aurevia.radflow.report.ReportCommands.AddendumCommand;
import com.aurevia.radflow.report.ReportCommands.PublishCommand;
import com.aurevia.radflow.report.ReportTypes.AcknowledgmentState;
import com.aurevia.radflow.report.ReportTypes.ReportEventType;

/**
 * RAD-01 report delivery and addendum acknowledgment domain kernel.
 *
 * <p>The kernel owns append-only report revisions, immutable acknowledgment
 * receipts and immutable delivery facts. It deliberately has no HTTP, persistence
 * or outbox dependency: a later story with a migration persists the returned
 * revisions, receipts and events inside one guarded transaction. The caller
 * supplies verified membership roles, the active tenant and the authenticated
 * subject and must re-check them on every command.</p>
 *
 * <p>An initial signed interpretation is delivered once to its referring clinician.
 * Every addendum is a new revision that is delivered again, opens a fresh receipt
 * and stays open until that exact revision is acknowledged by the same referrer.
 * An unsigned or unattested interpretation is refused before any delivery fact is
 * recorded. Reports are confined to their tenant on every read and command, and
 * referrer-facing reads and queues are confined to the verified referrer as well.</p>
 */
@Service
public final class ReportService {

    private final Clock clock;
    private final Duration acknowledgmentWindow;
    private final Map<String, Map<String, ReportDelivery>> reports = new HashMap<>();
    private final Map<String, List<ReportEvent>> events = new HashMap<>();

    /**
     * Create the report delivery service.
     *
     * @param clock                application clock used for notification and acknowledgment times
     * @param acknowledgmentWindow how long an unacknowledged addendum may stay open before it is overdue
     */
    public ReportService(Clock clock,
                         @Value("${radflow.report.acknowledgment-window:PT24H}") Duration acknowledgmentWindow) {
        this.clock = clock;
        this.acknowledgmentWindow = acknowledgmentWindow;
    }

    /**
     * Deliver a signed initial interpretation to the referring clinician (RAD-01).
     *
     * @param command publish command with authenticated actor and verified referrer
     * @return the initial delivered report revision
     * @throws ApiException on invalid input, missing permission, an unsigned interpretation or a duplicate
     */
    public ReportDelivery publish(PublishCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.referrer(), "INVALID_REFERRER", "referrer is required");
        if (!ReportRules.mayDeliver(command.roles())) {
            throw forbidden("report delivery");
        }
        if (!ReportRules.isSigned(command.interpretation())) {
            emitRefusal(command.organizationId(), command.orderId(), command.referrer(), command.subject());
            throw new ApiException(422, "UNSIGNED_REPORT", "an unsigned report is never sent");
        }
        Interpretation interpretation = command.interpretation();
        if (!command.organizationId().equals(interpretation.organizationId())
                || !command.orderId().equals(interpretation.orderId())) {
            throw new ApiException(422, "REPORT_IDENTITY_MISMATCH", "report tenant and order must match the command");
        }
        if (interpretation.kind() != InterpretationKind.INITIAL) {
            throw new ApiException(422, "INVALID_REPORT_KIND", "the first report revision must be initial");
        }
        Map<String, ReportDelivery> tenant = reports.computeIfAbsent(
                command.organizationId(), key -> new HashMap<>());
        if (tenant.containsKey(command.orderId())) {
            throw new ApiException(409, "DUPLICATE_REPORT", "the report identifier already exists");
        }
        ReportDelivery delivery = new ReportDelivery(command.organizationId(), command.orderId(),
                interpretation.patientId(), command.referrer(), interpretation.version(),
                List.of(interpretation), List.of());
        tenant.put(command.orderId(), delivery);
        emit(delivery, ReportEventType.INTERPRETATION_DELIVERED, command.subject());
        emit(delivery, ReportEventType.REFERRER_NOTIFIED, command.subject());
        return delivery;
    }

    /**
     * Link a signed addendum revision, deliver it and open its acknowledgment receipt (RAD-01).
     *
     * <p>The caller supplies the revision the addendum supersedes. The kernel binds
     * it to the current revision so an acknowledgment can never vouch for a stale
     * or different report version. No signed revision is ever edited.</p>
     *
     * @param command addendum command with the superseded version and authenticated actor
     * @return the updated delivered report with the new open receipt
     * @throws ApiException when denied, concealed, stale, unsigned, invalid or mismatched
     */
    public ReportDelivery addendum(AddendumCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        ReportDelivery current = load(command.organizationId(), command.orderId());
        if (!ReportRules.isSigned(command.addendum())) {
            emitRefusal(command.organizationId(), command.orderId(), current.referrer(), command.subject());
            throw new ApiException(422, "UNSIGNED_REPORT", "an unsigned addendum is never sent");
        }
        if (!ReportRules.mayDeliver(command.roles())) {
            throw forbidden("addendum");
        }
        Interpretation addendum = command.addendum();
        if (addendum.kind() != InterpretationKind.ADDENDUM) {
            throw new ApiException(422, "INVALID_REPORT_KIND", "a later revision must be an addendum");
        }
        if (addendum.supersedesVersion() == null || addendum.supersedesVersion() != current.version()
                || addendum.version() != current.version() + 1) {
            throw new ApiException(409, "INVALID_SUPERSEDES", "an addendum must supersede the current revision");
        }
        if (!current.patientId().equals(addendum.patientId())) {
            throw new ApiException(409, "PATIENT_MISMATCH", "an addendum must belong to the same patient");
        }
        if (!command.organizationId().equals(addendum.organizationId())
                || !command.orderId().equals(addendum.orderId())) {
            throw new ApiException(422, "REPORT_IDENTITY_MISMATCH", "addendum tenant and order must match the command");
        }
        List<Interpretation> revisions = new ArrayList<>(current.revisions());
        revisions.add(addendum);
        List<AddendumReceipt> receipts = new ArrayList<>(current.addenda());
        Instant notifiedAt = clock.instant();
        receipts.add(new AddendumReceipt(command.organizationId(), command.orderId(), current.version() + 1,
                current.referrer(), notifiedAt, AcknowledgmentState.OPEN, null, null));
        ReportDelivery updated = new ReportDelivery(command.organizationId(), command.orderId(),
                current.patientId(), current.referrer(), current.version() + 1, revisions, receipts);
        reports.get(command.organizationId()).put(command.orderId(), updated);
        emit(updated, ReportEventType.ADDENDUM_DELIVERED, command.subject());
        emit(updated, ReportEventType.REFERRER_NOTIFIED, command.subject());
        return updated;
    }

    /**
     * Record the referring clinician's acknowledgment of one exact addendum revision (RAD-01).
     *
     * @param command acknowledgment command binding the addendum version
     * @return the updated delivered report with the receipt closed
     * @throws ApiException when denied, concealed, missing, stale or already acknowledged
     */
    public ReportDelivery acknowledge(AcknowledgeCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        ReportDelivery current = load(command.organizationId(), command.orderId());
        if (!ReportRules.isReferrer(command.subject(), current.referrer())) {
            throw forbidden("acknowledgment");
        }
        AddendumReceipt receipt = current.addenda().stream()
                .filter(item -> item.version() == command.version())
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "ADDENDUM_NOT_FOUND", "addendum revision not found"));
        if (receipt.state() != AcknowledgmentState.OPEN) {
            throw new ApiException(409, "ALREADY_ACKNOWLEDGED", "this addendum was already acknowledged");
        }
        List<AddendumReceipt> receipts = current.addenda().stream()
                .map(item -> item.version() == command.version() ? acknowledged(item, command.subject()) : item)
                .toList();
        ReportDelivery updated = new ReportDelivery(command.organizationId(), command.orderId(),
                current.patientId(), current.referrer(), current.version(), current.revisions(), receipts);
        reports.get(command.organizationId()).put(command.orderId(), updated);
        emit(updated, ReportEventType.ADDENDUM_ACKNOWLEDGED, command.subject());
        return updated;
    }

    /**
     * Read the current delivered report in the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return the current delivered report
     * @throws ApiException when the report is not found in this tenant
     */
    public ReportDelivery read(String organizationId, String orderId) {
        return load(organizationId, orderId);
    }

    /**
     * Read the report only when the caller is its verified referrer (RAD-01).
     *
     * @param organizationId active tenant
     * @param subject        authenticated subject
     * @param orderId        tenant-local order identifier
     * @return the current delivered report
     * @throws ApiException when the subject is not the referrer or the report is absent
     */
    public ReportDelivery readForReferrer(String organizationId, String subject, String orderId) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(subject, "INVALID_ACTOR", "actor is required");
        ReportDelivery current = load(organizationId, orderId);
        if (!ReportRules.isReferrer(subject, current.referrer())) {
            throw forbidden("report read");
        }
        return current;
    }

    /**
     * Read the interpretation revisions only when the caller is the verified referrer (RAD-01).
     *
     * @param organizationId active tenant
     * @param subject        authenticated subject
     * @param orderId        tenant-local order identifier
     * @return the revisions oldest first
     * @throws ApiException when the subject is not the referrer or the report is absent
     */
    public List<Interpretation> revisionsForReferrer(String organizationId, String subject, String orderId) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(subject, "INVALID_ACTOR", "actor is required");
        ReportDelivery current = load(organizationId, orderId);
        if (!ReportRules.isReferrer(subject, current.referrer())) {
            throw forbidden("report revisions read");
        }
        return List.copyOf(current.revisions());
    }

    /**
     * List the referring clinician's addenda that still await acknowledgment (ADM-02).
     *
     * @param organizationId active tenant
     * @param subject        authenticated referring clinician
     * @return immutable open receipts for that referrer
     */
    public List<AddendumReceipt> openAddenda(String organizationId, String subject) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(subject, "INVALID_ACTOR", "actor is required");
        return receiptsFor(organizationId, subject, AddendumReceipt::isOpen);
    }

    /**
     * List the referring clinician's open addenda that aged past the acknowledgment window (ADM-02).
     *
     * @param organizationId active tenant
     * @param subject        authenticated referring clinician
     * @return immutable overdue receipts for that referrer
     */
    public List<AddendumReceipt> overdueAddenda(String organizationId, String subject) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(subject, "INVALID_ACTOR", "actor is required");
        Instant now = clock.instant();
        return receiptsFor(organizationId, subject, receipt -> receipt.isOverdue(now, acknowledgmentWindow));
    }

    /**
     * Return the immutable delivery facts emitted for one tenant (ARC-02).
     *
     * @param organizationId active tenant
     * @return that tenant's events in emission order, or an empty list
     */
    public List<ReportEvent> events(String organizationId) {
        return List.copyOf(events.getOrDefault(organizationId, List.of()));
    }

    /**
     * Return the delivery facts addressed to one referring clinician in the active tenant.
     *
     * @param organizationId active tenant
     * @param subject        authenticated referring clinician
     * @return immutable facts for that referrer in emission order
     */
    public List<ReportEvent> eventsForReferrer(String organizationId, String subject) {
        requireText(organizationId, "INVALID_ORGANIZATION", "active organization is required");
        requireText(subject, "INVALID_ACTOR", "actor is required");
        return events.getOrDefault(organizationId, List.of()).stream()
                .filter(event -> ReportRules.isReferrer(subject, event.referrer()))
                .toList();
    }

    private List<AddendumReceipt> receiptsFor(String organizationId, String subject,
                                              Predicate<AddendumReceipt> filter) {
        Map<String, ReportDelivery> tenant = reports.get(organizationId);
        if (tenant == null) {
            return List.of();
        }
        List<AddendumReceipt> result = new ArrayList<>();
        for (ReportDelivery delivery : tenant.values()) {
            if (!ReportRules.isReferrer(subject, delivery.referrer())) {
                continue;
            }
            for (AddendumReceipt receipt : delivery.addenda()) {
                if (filter.test(receipt)) {
                    result.add(receipt);
                }
            }
        }
        return List.copyOf(result);
    }

    private AddendumReceipt acknowledged(AddendumReceipt receipt, String subject) {
        return new AddendumReceipt(receipt.organizationId(), receipt.orderId(), receipt.version(),
                receipt.referrer(), receipt.notifiedAt(), AcknowledgmentState.ACKNOWLEDGED,
                clock.instant(), subject);
    }

    private void emit(ReportDelivery delivery, ReportEventType type, String actor) {
        events.computeIfAbsent(delivery.organizationId(), key -> new ArrayList<>())
                .add(new ReportEvent(delivery.organizationId(), type, delivery.orderId(), delivery.version(),
                        delivery.patientId(), delivery.referrer(), actor, delivery.referrer(), clock.instant()));
    }

    private void emitRefusal(String organizationId, String orderId, String referrer, String actor) {
        events.computeIfAbsent(organizationId, key -> new ArrayList<>())
                .add(new ReportEvent(organizationId, ReportEventType.UNSIGNED_REPORT_REFUSED, orderId, 0,
                        null, referrer, actor, referrer, clock.instant()));
    }

    private ReportDelivery load(String organizationId, String orderId) {
        Map<String, ReportDelivery> tenant = reports.get(organizationId);
        ReportDelivery delivery = tenant == null ? null : tenant.get(orderId);
        if (delivery == null) {
            throw new ApiException(404, "NOT_FOUND", "imaging report not found");
        }
        return delivery;
    }

    private static void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }

    private static ApiException forbidden(String action) {
        return new ApiException(403, "FORBIDDEN", "missing permission for " + action);
    }
}
