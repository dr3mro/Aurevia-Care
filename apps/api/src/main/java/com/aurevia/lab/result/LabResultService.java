package com.aurevia.lab.result;

import com.aurevia.lab.result.LabCommands.CaptureCommand;
import com.aurevia.lab.result.LabCommands.CorrectCommand;
import com.aurevia.lab.result.LabCommands.ErrorCommand;
import com.aurevia.lab.result.LabCommands.VerifyCommand;
import com.aurevia.lab.result.LabPolicyCatalog.CriticalPolicy;
import com.aurevia.lab.result.LabPolicyCatalog.ReferenceRange;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.TriggerType;
import com.aurevia.lab.result.LabTypes.VerificationState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aurevia.api.ApiException;

/**
 * LAB-02 commands for observations, verification states and issued revisions.
 *
 * <p>This is a tenant-scoped domain kernel: it owns append-only revisions and the
 * immutable XCH-04/05 trigger facts, and it evaluates observations only against
 * approved, effective configuration. It deliberately has no HTTP, persistence or
 * outbox dependency; a later story with a migration and controller persists the
 * returned revisions and events inside one guarded transaction. The caller
 * supplies verified membership roles and the active tenant, and must re-check
 * them on every command.</p>
 */
public final class LabResultService {

    private final LabPolicyCatalog policies;
    private final Clock clock;
    private final Map<String, Map<String, List<ReportRevision>>> ledger = new HashMap<>();
    private final Map<String, List<TriggerEvent>> events = new HashMap<>();

    /**
     * Create the service.
     *
     * @param policies approved effective-dated reference ranges and critical policies
     * @param clock    application clock used for effective time and event occurrence
     */
    public LabResultService(LabPolicyCatalog policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    /**
     * Capture observations as a preliminary revision of a tenant-local report.
     *
     * @param command capture command with authenticated actor and matched identifiers
     * @return the preliminary revision
     * @throws ApiException on invalid input, missing permission or a duplicate report identifier
     */
    public ReportRevision capture(CaptureCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.reportId(), "INVALID_REPORT", "report identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.specimenId(), "INVALID_SPECIMEN", "specimen identifier is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        if (command.observations().isEmpty()) {
            throw new ApiException(422, "NO_OBSERVATIONS", "at least one observation is required");
        }
        if (!LabRules.mayCapture(command.roles())) {
            throw forbidden();
        }
        Map<String, List<ReportRevision>> tenant = ledger.computeIfAbsent(
                command.organizationId(), key -> new HashMap<>());
        if (tenant.containsKey(command.reportId())) {
            throw new ApiException(409, "DUPLICATE_REPORT", "the report identifier already exists");
        }
        ReportRevision revision = new ReportRevision(command.organizationId(), command.reportId(), 1, null,
                command.patientId(), command.specimenId(), command.orderId(),
                VerificationState.PRELIMINARY, command.observations(), command.subject(), null,
                null, null, null, null);
        tenant.put(command.reportId(), new ArrayList<>(List.of(revision)));
        return revision;
    }

    /**
     * Verify a preliminary revision, evaluate observations and issue it as final.
     *
     * @param command verify command carrying the expected version and matched identifiers
     * @return the new final revision; the preliminary revision is preserved
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public ReportRevision verify(VerifyCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.reportId(), "INVALID_REPORT", "report identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.specimenId(), "INVALID_SPECIMEN", "specimen identifier is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.verifier(), "VERIFIER_REQUIRED", "a verifier is required");
        requireText(command.owner(), "OWNER_REQUIRED", "a named owner is required");
        requireText(command.queue(), "QUEUE_REQUIRED", "a coverage queue is required");
        if (!LabRules.mayVerify(command.roles())) {
            throw forbidden();
        }
        List<ReportRevision> revisions = load(command.organizationId(), command.reportId());
        ReportRevision current = current(revisions);
        requireVersion(current, command.expectedVersion());
        requireMatch(current, command.patientId(), command.specimenId(), command.orderId());
        if (!current.state().canTransitionTo(VerificationState.FINAL)) {
            throw invalidTransition();
        }
        Instant at = clock.instant();
        Evaluation evaluation = evaluate(current.observations(), command.verifier(), at);
        ReportRevision issued = new ReportRevision(command.organizationId(), command.reportId(),
                current.version() + 1, current.version(), command.patientId(), command.specimenId(),
                command.orderId(), VerificationState.FINAL, evaluation.observations(),
                command.subject(), command.verifier(), at, null,
                evaluation.rangeVersion(), evaluation.criticalVersion());
        append(revisions, issued);
        String digest = digest(issued);
        emit(issued, TriggerType.RESULT_ISSUED, command.owner(), command.queue(), null, null,
                digest, at, null);
        emitCritical(issued, evaluation, digest, at);
        return issued;
    }

    /**
     * Append a linked correction, preserving the issued revision and reopening review.
     *
     * @param command correction command with reason, verifier and expected version
     * @return the new corrected revision
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public ReportRevision correct(CorrectCommand command) {
        return correct(command, List.of());
    }

    /**
     * Append a linked correction with replacement observations, preserving the issued revision.
     *
     * <p>An empty replacement list re-evaluates the current observations; a non-empty
     * list represents a true value correction such as a changed result. Both forms
     * require a reason and a verifier and reopen review with a new event identity.</p>
     *
     * @param command      correction command with reason, verifier and expected version
     * @param observations replacement observations, or empty to keep the current ones
     * @return the new corrected revision
     * @throws ApiException when denied, concealed, stale, mismatched or illegally transitioned
     */
    public ReportRevision correct(CorrectCommand command, List<LabObservation> observations) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.reportId(), "INVALID_REPORT", "report identifier is required");
        requireText(command.patientId(), "INVALID_PATIENT", "patient identifier is required");
        requireText(command.specimenId(), "INVALID_SPECIMEN", "specimen identifier is required");
        requireText(command.orderId(), "INVALID_ORDER", "order identifier is required");
        requireText(command.verifier(), "VERIFIER_REQUIRED", "a verifier is required");
        requireText(command.owner(), "OWNER_REQUIRED", "a named owner is required");
        requireText(command.queue(), "QUEUE_REQUIRED", "a coverage queue is required");
        requireText(command.reason(), "REASON_REQUIRED", "a correction reason is required");
        if (!LabRules.mayVerify(command.roles())) {
            throw forbidden();
        }
        List<ReportRevision> revisions = load(command.organizationId(), command.reportId());
        ReportRevision current = current(revisions);
        requireVersion(current, command.expectedVersion());
        requireMatch(current, command.patientId(), command.specimenId(), command.orderId());
        if (!current.state().canTransitionTo(VerificationState.CORRECTED)) {
            throw invalidTransition();
        }
        Instant at = clock.instant();
        List<LabObservation> source = observations.isEmpty() ? current.observations() : List.copyOf(observations);
        Evaluation evaluation = evaluate(source, command.verifier(), at);
        ReportRevision corrected = new ReportRevision(command.organizationId(), command.reportId(),
                current.version() + 1, current.version(), command.patientId(), command.specimenId(),
                command.orderId(), VerificationState.CORRECTED, evaluation.observations(),
                command.subject(), command.verifier(), at, command.reason(),
                evaluation.rangeVersion(), evaluation.criticalVersion());
        append(revisions, corrected);
        String digest = digest(corrected);
        emit(corrected, TriggerType.RESULT_CORRECTED, command.owner(), command.queue(),
                null, null, digest, at, null);
        emitCritical(corrected, evaluation, digest, at);
        return corrected;
    }

    /**
     * Append an entered-in-error revision and withdraw the current one.
     *
     * @param command command carrying the required reason and expected version
     * @return the terminal revision
     * @throws ApiException when denied, concealed, stale, missing a reason or illegally transitioned
     */
    public ReportRevision enterInError(ErrorCommand command) {
        requireText(command.organizationId(), "INVALID_ORGANIZATION", "active organization is required");
        requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        requireText(command.reportId(), "INVALID_REPORT", "report identifier is required");
        requireText(command.reason(), "REASON_REQUIRED", "a reason is required");
        if (!LabRules.mayVerify(command.roles())) {
            throw forbidden();
        }
        List<ReportRevision> revisions = load(command.organizationId(), command.reportId());
        ReportRevision current = current(revisions);
        requireVersion(current, command.expectedVersion());
        if (!current.state().canTransitionTo(VerificationState.ENTERED_IN_ERROR)) {
            throw invalidTransition();
        }
        Instant at = clock.instant();
        ReportRevision errored = new ReportRevision(current.organizationId(), current.reportId(),
                current.version() + 1, current.version(), current.patientId(), current.specimenId(),
                current.orderId(), VerificationState.ENTERED_IN_ERROR, current.observations(),
                command.subject(), current.verifier(), at, command.reason(),
                current.rangeVersion(), current.criticalPolicyVersion());
        append(revisions, errored);
        emit(errored, TriggerType.RESULT_WITHDRAWN, null, null, null, null, digest(errored), at, null);
        return errored;
    }

    /**
     * Read the current revision of a report in the active tenant.
     *
     * @param organizationId active tenant
     * @param reportId       tenant-local report identifier
     * @return the current revision
     * @throws ApiException when concealed or not found
     */
    public ReportRevision read(String organizationId, String reportId) {
        return current(load(organizationId, reportId));
    }

    /**
     * Read the append-only revision history of a report in the active tenant.
     *
     * @param organizationId active tenant
     * @param reportId       tenant-local report identifier
     * @return revisions oldest first
     * @throws ApiException when concealed or not found
     */
    public List<ReportRevision> revisions(String organizationId, String reportId) {
        return List.copyOf(load(organizationId, reportId));
    }

    /**
     * Return only the observations eligible for numeric trending (XCH-07).
     *
     * @param organizationId active tenant
     * @param reportId       tenant-local report identifier
     * @return eligible observations from the current revision, oldest first
     * @throws ApiException when concealed or not found
     */
    public List<LabObservation> comparable(String organizationId, String reportId) {
        return current(load(organizationId, reportId)).observations().stream()
                .filter(LabRules::eligible).toList();
    }

    /**
     * Return the immutable trigger facts emitted for one tenant (ARC-02).
     *
     * @param organizationId active tenant
     * @return that tenant's events in emission order, or an empty list
     */
    public List<TriggerEvent> events(String organizationId) {
        return List.copyOf(events.getOrDefault(organizationId, List.of()));
    }

    private Evaluation evaluate(List<LabObservation> observations, String verifier, Instant at) {
        List<LabObservation> evaluated = new ArrayList<>();
        List<CriticalPolicy> criticals = new ArrayList<>();
        Integer rangeVersion = null;
        for (LabObservation observation : observations) {
            LabObservation result = observation;
            if (observation.presence() == ObservationPresence.PRESENT
                    && observation.kind() == ObservationKind.NUMERIC) {
                AbnormalFlag flag = observation.abnormalFlag();
                Optional<ReferenceRange> range = policies.effectiveRange(
                        observation.code(), observation.unit(), at);
                if (range.isPresent()) {
                    rangeVersion = range.orElseThrow().version();
                    flag = range.orElseThrow().flag(observation.value());
                }
                Optional<CriticalPolicy> policy = policies.effectiveCritical(observation.code(), at);
                if (policy.isPresent() && policy.orElseThrow().isCritical(observation.value())) {
                    criticals.add(policy.orElseThrow());
                    flag = AbnormalFlag.CRITICAL;
                }
                result = observation.verified(verifier, flag);
            } else {
                // An unverifiable interpretation cannot be backed by an owner or an escalation event.
                result = observation.verified(verifier, AbnormalFlag.NONE);
            }
            evaluated.add(result);
        }
        return new Evaluation(List.copyOf(evaluated), rangeVersion, List.copyOf(criticals));
    }

    private static void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }

    private static void requireVersion(ReportRevision current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new ApiException(412, "STALE_VERSION", "the report changed since it was read");
        }
    }

    private static void requireMatch(ReportRevision current, String patientId, String specimenId,
                                     String orderId) {
        if (!current.patientId().equals(patientId) || !current.specimenId().equals(specimenId)
                || !current.orderId().equals(orderId)) {
            throw new ApiException(422, "LABEL_MISMATCH", "patient, specimen and order must match");
        }
    }

    private static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "missing laboratory permission");
    }

    private static ApiException invalidTransition() {
        return new ApiException(409, "INVALID_TRANSITION", "the report cannot enter that state");
    }

    private List<ReportRevision> load(String organizationId, String reportId) {
        Map<String, List<ReportRevision>> tenant = ledger.get(organizationId);
        List<ReportRevision> revisions = tenant == null ? null : tenant.get(reportId);
        if (revisions == null) {
            throw new ApiException(404, "NOT_FOUND", "report not found");
        }
        return revisions;
    }

    private static ReportRevision current(List<ReportRevision> revisions) {
        return revisions.get(revisions.size() - 1);
    }

    private static void append(List<ReportRevision> revisions, ReportRevision revision) {
        revisions.add(revision);
    }

    private void emit(ReportRevision revision, TriggerType type, String owner, String queue,
                      Duration dueAfter, String escalation, String digest, Instant at,
                      String findingCode) {
        events.computeIfAbsent(revision.organizationId(), key -> new ArrayList<>())
                .add(new TriggerEvent(revision.organizationId(), type, revision.reportId(),
                        revision.version(), revision.patientId(), revision.specimenId(),
                        revision.orderId(), owner, queue, revision.effectiveAt(), dueAfter,
                        escalation, digest, at, findingCode));
    }

    private void emitCritical(ReportRevision revision, Evaluation evaluation, String digest, Instant at) {
        for (CriticalPolicy critical : evaluation.criticalPolicies()) {
            emit(revision, TriggerType.CRITICAL_RESULT, critical.owner(), critical.queue(),
                    critical.dueAfter(), critical.escalation(), digest, at, critical.code());
        }
    }

    private static String digest(ReportRevision revision) {
        StringBuilder builder = new StringBuilder();
        append(builder, revision.organizationId());
        append(builder, revision.reportId());
        append(builder, String.valueOf(revision.version()));
        append(builder, revision.state().wire());
        append(builder, revision.reason());
        append(builder, revision.verifier());
        append(builder, revision.effectiveAt().toString());
        append(builder, String.valueOf(revision.rangeVersion()));
        append(builder, String.valueOf(revision.criticalPolicyVersion()));
        builder.append(revision.observations().size()).append(':');
        for (LabObservation observation : revision.observations()) {
            append(builder, observation.code());
            append(builder, observation.display());
        }
        return builder.toString();
    }

    private static void append(StringBuilder builder, String value) {
        String text = value == null ? "" : value;
        builder.append(text.length()).append(':').append(text);
    }

    /**
     * Result of evaluating one revision against approved configuration.
     *
     * @param observations     verified observations with recorded flags
     * @param rangeVersion     newest approved reference-range version applied, or {@code null}
     * @param criticalPolicies one policy per critical finding, so each finding keeps its own escalation
     */
    private record Evaluation(List<LabObservation> observations, Integer rangeVersion,
                              List<CriticalPolicy> criticalPolicies) {

        /**
         * Return the critical policy version stamped on the revision.
         *
         * @return the first critical finding's version, or {@code null} when none fired
         */
        Integer criticalVersion() {
            return criticalPolicies.isEmpty() ? null : criticalPolicies.get(0).version();
        }
    }
}
