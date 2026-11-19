package com.aurevia.smartclinic.messaging.s_g3_12;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.AccessibilityCheck;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.AuditEvent;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.Availability;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.Category;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.DowntimeRunbook;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.EvidenceRecord;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.IdempotentCommand;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.JournalEvent;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.LatencySample;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.LoadProfile;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.RestoreResult;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.Snapshot;
import com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.Verdict;

/**
 * Pure evaluators that convert synthetic G3 measurements into evidence records.
 *
 * <p>Each method returns one {@link com.aurevia.smartclinic.messaging.s_g3_12.GateEvidenceContract.EvidenceRecord} carrying a PASS/FAIL verdict
 * and a deterministic detail string: a short metric summary on success or the
 * comma-separated failure codes on failure. No method performs I/O or reads a
 * clock; the caller supplies the observation instant where one is needed.</p>
 */
public final class GateEvidenceEvaluator {

    private static final long RPO_MAX_MINUTES = 15L;
    private static final long RTO_MAX_MINUTES = 240L;
    private static final long P95_READ_MAX_MS = 500L;
    private static final long P95_COMMAND_MAX_MS = 900L;
    private static final long AVAILABILITY_MIN_THOUSANDTHS = 999L;
    private static final List<String> REQUIRED_LOCALES = List.of("en", "ar");
    private static final List<String> REQUIRED_A11Y_CRITERIA = List.of("LABEL", "ROLE", "SCREEN_READER", "RTL");

    /**
     * Evaluate restore and reconciliation evidence (OPS-04, ARC-05).
     *
     * @param snapshot snapshot metadata
     * @param journal  immutable journal events
     * @param restore  observed restore result
     * @param now      evaluation instant
     * @return the restore evidence record
     */
    public EvidenceRecord evaluateRestore(Snapshot snapshot, List<JournalEvent> journal,
                                          RestoreResult restore, Instant now) {
        List<String> failures = new ArrayList<>();

        List<String> hashParts = new ArrayList<>();
        hashParts.add(snapshot.hash());
        for (JournalEvent event : journal) {
            hashParts.add(event.hash());
        }
        if (!String.join("|", hashParts).equals(restore.stateHash())) {
            failures.add("STATE_HASH_MISMATCH");
        }

        Set<String> journalIds = new HashSet<>();
        for (JournalEvent event : journal) {
            journalIds.add(event.eventId());
        }
        Set<String> replayedIds = new HashSet<>(restore.replayedEventIds());
        if (restore.replayedEventIds().size() != replayedIds.size()) {
            failures.add("DUPLICATE_REPLAY");
        }
        for (String eventId : restore.replayedEventIds()) {
            if (!journalIds.contains(eventId)) {
                failures.add("UNKNOWN_EVENT");
                break;
            }
        }
        if (replayedIds.size() != journalIds.size()) {
            failures.add("INCOMPLETE_REPLAY");
        }

        long recoveryPointMinutes = Duration.between(snapshot.capturedAt(), now).toMinutes();
        if (recoveryPointMinutes > RPO_MAX_MINUTES) {
            failures.add("RPO_EXCEEDED");
        }
        long recoveryTimeMinutes = Duration.between(restore.startedAt(), restore.completedAt()).toMinutes();
        if (recoveryTimeMinutes > RTO_MAX_MINUTES) {
            failures.add("RTO_EXCEEDED");
        }
        if (snapshot.capturedAt().isAfter(now)) {
            failures.add("SNAPSHOT_IN_FUTURE");
        }

        String detail = failures.isEmpty()
                ? "PASS rpo=" + recoveryPointMinutes + "m rto=" + recoveryTimeMinutes
                        + "m events=" + replayedIds.size()
                : String.join(",", failures);
        return new EvidenceRecord(Category.RESTORE, detail, verdict(failures), now);
    }

    /**
     * Evaluate downtime runbook and recovery-replay evidence (OPS-05, ARC-05).
     *
     * @param runbook  required downtime procedures
     * @param commands originally submitted idempotent commands
     * @param effects  effects observed after recovery replay
     * @return the downtime evidence record
     */
    public EvidenceRecord evaluateDowntime(DowntimeRunbook runbook, List<IdempotentCommand> commands,
                                           List<IdempotentCommand> effects) {
        List<String> failures = new ArrayList<>();
        if (!runbook.paperCapture()) {
            failures.add("PAPER_CAPTURE_MISSING");
        }
        if (!runbook.patientIdentification()) {
            failures.add("PATIENT_IDENTIFICATION_MISSING");
        }
        if (!runbook.criticalContacts()) {
            failures.add("CRITICAL_CONTACTS_MISSING");
        }
        if (!runbook.supervisedBackEntry()) {
            failures.add("SUPERVISED_BACK_ENTRY_MISSING");
        }

        Map<String, String> commandsByKey = new HashMap<>();
        for (IdempotentCommand command : commands) {
            commandsByKey.put(command.key(), command.digest());
        }
        long distinctPairs = commands.stream()
                .map(command -> command.key() + "\u0000" + command.digest()).distinct().count();
        if (distinctPairs != commandsByKey.size()) {
            failures.add("CONFLICTING_DIGEST");
        }

        Set<String> effectKeys = new HashSet<>();
        for (IdempotentCommand effect : effects) {
            effectKeys.add(effect.key());
        }
        if (effects.size() != effectKeys.size()) {
            failures.add("DUPLICATE_EFFECT");
        }
        if (!commandsByKey.keySet().containsAll(effectKeys)) {
            failures.add("UNKNOWN_REPLAY");
        }
        if (effectKeys.size() != commandsByKey.size()) {
            failures.add("INCOMPLETE_RECOVERY");
        }

        String detail = failures.isEmpty()
                ? "PASS commands=" + commandsByKey.size() + " effects=" + effectKeys.size()
                : String.join(",", failures);
        return new EvidenceRecord(Category.DOWNTIME, detail, verdict(failures), Instant.EPOCH);
    }

    /**
     * Evaluate load and capacity evidence (OPS-03).
     *
     * @param profile      profile under test
     * @param samples      measured latencies
     * @param availability measured availability
     * @return the load evidence record
     */
    public EvidenceRecord evaluateLoad(LoadProfile profile, List<LatencySample> samples,
                                       Availability availability) {
        List<Long> readLatencies = new ArrayList<>();
        List<Long> commandLatencies = new ArrayList<>();
        for (LatencySample sample : samples) {
            if (sample.command()) {
                commandLatencies.add(sample.durationMs());
            } else {
                readLatencies.add(sample.durationMs());
            }
        }

        List<String> failures = new ArrayList<>();
        if (readLatencies.isEmpty()) {
            failures.add("NO_READ_SAMPLES");
        } else if (p95(readLatencies) > P95_READ_MAX_MS) {
            failures.add("READ_P95_EXCEEDED");
        }
        if (commandLatencies.isEmpty()) {
            failures.add("NO_COMMAND_SAMPLES");
        } else if (p95(commandLatencies) > P95_COMMAND_MAX_MS) {
            failures.add("COMMAND_P95_EXCEEDED");
        }
        if (availability.total() == 0
                || availability.successes() * 1000L < availability.total() * AVAILABILITY_MIN_THOUSANDTHS) {
            failures.add("AVAILABILITY_BELOW_TARGET");
        }
        if (!LoadProfile.PILOT.equals(profile)) {
            failures.add("PROFILE_MISMATCH");
        }

        String detail = failures.isEmpty()
                ? "PASS samples=" + samples.size() + " availability=" + availability.successes()
                        + "/" + availability.total()
                : String.join(",", failures);
        return new EvidenceRecord(Category.LOAD, detail, verdict(failures), Instant.EPOCH);
    }

    /**
     * Evaluate UX-01 accessibility evidence for both supported locales.
     *
     * @param checks accessibility checks
     * @return the accessibility evidence record
     */
    public EvidenceRecord evaluateAccessibility(List<AccessibilityCheck> checks) {
        List<String> failures = new ArrayList<>();
        if (checks.isEmpty()) {
            failures.add("NO_CHECKS");
        } else {
            for (AccessibilityCheck check : checks) {
                if (!"en".equals(check.locale()) && !"ar".equals(check.locale())) {
                    failures.add("UNKNOWN_LOCALE");
                }
            }
            for (String locale : REQUIRED_LOCALES) {
                boolean expectedRtl = "ar".equals(locale);
                for (String criterion : REQUIRED_A11Y_CRITERIA) {
                    List<AccessibilityCheck> matches = new ArrayList<>();
                    for (AccessibilityCheck check : checks) {
                        if (locale.equals(check.locale()) && criterion.equals(check.criterion())) {
                            matches.add(check);
                        }
                    }
                    if (matches.isEmpty()) {
                        failures.add("MISSING_" + criterion + "_" + locale);
                    } else {
                        for (AccessibilityCheck check : matches) {
                            if (check.rtlCapable() != expectedRtl) {
                                failures.add("RTL_MISMATCH");
                            }
                            if (!check.passed()) {
                                failures.add(criterion + "_FAILED");
                            }
                            if (check.owner().isBlank()) {
                                failures.add("OWNER_MISSING");
                            }
                        }
                    }
                }
            }
        }
        String detail = failures.isEmpty()
                ? "PASS checks=" + checks.size() + " locales=" + REQUIRED_LOCALES.size()
                : String.join(",", failures);
        return new EvidenceRecord(Category.ACCESSIBILITY, detail, verdict(failures), Instant.EPOCH);
    }

    /**
     * Evaluate audit completeness and evidence-chain validity (OPS-02).
     *
     * @param events    audit events to inspect
     * @param chainValid whether the append-only evidence chain verified
     * @return the audit evidence record
     */
    public EvidenceRecord evaluateAudit(List<AuditEvent> events, boolean chainValid) {
        List<String> failures = new ArrayList<>();
        if (events.isEmpty()) {
            failures.add("NO_AUDIT_EVENTS");
        } else {
            for (AuditEvent event : events) {
                if (blank(event.actor())) {
                    failures.add("ACTOR_MISSING");
                }
                if (blank(event.purpose())) {
                    failures.add("PURPOSE_MISSING");
                }
                if (blank(event.scope())) {
                    failures.add("SCOPE_MISSING");
                }
                if (blank(event.correlationId())) {
                    failures.add("CORRELATION_MISSING");
                }
                if (blank(event.action())) {
                    failures.add("ACTION_MISSING");
                }
                if (blank(event.resourceId())) {
                    failures.add("RESOURCE_MISSING");
                }
                if (event.timestamp() == null) {
                    failures.add("TIMESTAMP_MISSING");
                }
                if (blank(event.outcome())) {
                    failures.add("OUTCOME_MISSING");
                }
            }
        }
        if (!chainValid) {
            failures.add("CHAIN_INVALID");
        }
        String detail = failures.isEmpty()
                ? "PASS events=" + events.size() + " chain=valid"
                : String.join(",", failures);
        return new EvidenceRecord(Category.AUDIT, detail, verdict(failures), Instant.EPOCH);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Verdict verdict(List<String> failures) {
        return failures.isEmpty() ? Verdict.PASS : Verdict.FAIL;
    }

    private static long p95(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(index);
    }
}
