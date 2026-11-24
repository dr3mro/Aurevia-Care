package com.aurevia.messaging.evidence;

import java.time.Instant;
import java.util.List;

/**
 * Domain vocabulary for synthetic G3 gate evidence.
 *
 * <p>The types here are pure values: they carry no transport or persistence
 * concerns. Every collection-bearing record freezes its list in the compact
 * constructor so a caller cannot mutate an evidence record after it has been
 * evaluated and chained. All fixtures are synthetic; no patient data is used.</p>
 */
public final class GateEvidenceContract {

    private GateEvidenceContract() {
    }

    /**
     * The five G3 evidence categories required by the specification gates.
     */
    public enum Category {
        /** Restore and reconciliation evidence (OPS-04, ARC-05). */
        RESTORE("restore"),
        /** Downtime, runbook and replay evidence (OPS-05). */
        DOWNTIME("downtime"),
        /** Load and capacity evidence (OPS-03). */
        LOAD("load"),
        /** Accessibility and localization evidence (UX-01). */
        ACCESSIBILITY("accessibility"),
        /** Audit completeness and chain evidence (OPS-02). */
        AUDIT("audit");

        private final String wire;

        /**


         * @param wire


         */


        Category(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable lowercase key.
         *
         * @return machine-readable category key
         */
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied category key.
         *
         * @return the matching category
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Category from(String value) {
            for (Category candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown evidence category: " + value);
        }
    }

    /**
     * Outcome of a single evidence category or of the whole report.
     */
    public enum Verdict {
        /** Every check in scope passed. */
        PASS("pass"),
        /** At least one check in scope failed. */
        FAIL("fail");

        private final String wire;

        /**


         * @param wire


         */


        Verdict(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable lowercase key.
         *
         * @return machine-readable verdict key
         */
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied verdict key.
         *
         * @return the matching verdict
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Verdict from(String value) {
            for (Verdict candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown evidence verdict: " + value);
        }
    }

    /**
     * Immutable snapshot metadata used by the restore drill.
     *
     */
    public record Snapshot(String hash, String version, Instant capturedAt) {
    }

    /**
     * One immutable journal event in the synthetic restore scenario.
     *
 * @return this record
 */
public record JournalEvent(String eventId, String hash, Instant occurredAt) {
    }

    /**
     * Observed result of a synthetic restore and journal replay.
     *
 * @return this record
 */
public record RestoreResult(String stateHash, List<String> replayedEventIds,
                                Instant startedAt, Instant completedAt) {
        /**
         * Freeze the replayed event identifiers.
         */
        public RestoreResult {
            replayedEventIds = List.copyOf(replayedEventIds);
        }
    }

    /**
     * The four required downtime runbook elements (OPS-05).
     *
 * @return this record
 */
public record DowntimeRunbook(boolean paperCapture, boolean patientIdentification,
                                  boolean criticalContacts, boolean supervisedBackEntry) {
    }

    /**
     * One idempotent replay command (ARC-05, OPS-05).
     *
 * @return this record
 */
public record IdempotentCommand(String key, String digest) {
    }

    /**
     * One measured latency sample.
     *
 * @return this record
 */
public record LatencySample(boolean command, long durationMs) {
    }

    /**
     * Availability measurement over a fixed request population.
     *
 * @return this record
 */
public record Availability(long successes, long total) {
    }

    /**
     * The approved pilot load profile (OPS-03).
     *
 * @return this record
 */
public record LoadProfile(int organizations, int charts, int concurrentStaff,
                              int requestsPerSecond, int durationMinutes) {
        /** The specification's pilot profile: 20/100000/200/50/30. */
        public static final LoadProfile PILOT = new LoadProfile(20, 100_000, 200, 50, 30);
    }

    /**
     * One UX-01 accessibility check for a locale.
     *
 * @return this record
 */
public record AccessibilityCheck(String criterion, String locale, boolean rtlCapable,
                                     boolean passed, String owner) {
    }

    /**
     * One audit event with the mandatory OPS-02 fields.
     *
 * @return this record
 */
public record AuditEvent(String actor, String purpose, String scope, String correlationId,
                             String action, String resourceId, Instant timestamp, String outcome) {
    }

    /**
     * All synthetic fixture families needed to produce the G3 evidence report.
     *
 * @return this record
 */
public record EvidenceInput(Snapshot snapshot, List<JournalEvent> journal, RestoreResult restore,
                                DowntimeRunbook runbook, List<IdempotentCommand> commands,
                                List<IdempotentCommand> effects, LoadProfile profile,
                                List<LatencySample> samples, Availability availability,
                                List<AccessibilityCheck> accessibility, List<AuditEvent> audit) {
        /**
         * Freeze every collection-bearing fixture.
         */
        public EvidenceInput {
            journal = List.copyOf(journal);
            commands = List.copyOf(commands);
            effects = List.copyOf(effects);
            samples = List.copyOf(samples);
            accessibility = List.copyOf(accessibility);
            audit = List.copyOf(audit);
        }
    }

    /**
     * The evidence produced for one category.
     *
 * @return this record
 */
public record EvidenceRecord(Category category, String detail, Verdict verdict, Instant observedAt) {
    }

    /**
     * One append-only ledger entry chaining an evidence record to its predecessor.
     *
 * @return this record
 */
public record LedgerEntry(long sequence, EvidenceRecord record, String previousHash, String hash) {
    }

    /**
     * The immutable composite G3 evidence report.
     *
 * @return this record
 */
public record GateEvidenceReport(String organizationId, List<EvidenceRecord> records,
                                     String chainHead, Verdict verdict, Instant generatedAt) {
        /**
         * Freeze the category records.
         */
        public GateEvidenceReport {
            records = List.copyOf(records);
        }
    }
}
