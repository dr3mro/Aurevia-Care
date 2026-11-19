package com.aurevia.smartclinic.messaging.evidence;

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
         * @param value key to parse
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
         * @param value key to parse
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
     * @param hash       synthetic snapshot checksum
     * @param version    synthetic snapshot version label
     * @param capturedAt instant the snapshot was taken
     */
    public record Snapshot(String hash, String version, Instant capturedAt) {
    }

    /**
     * One immutable journal event in the synthetic restore scenario.
     *
     * @param eventId    opaque event identifier
     * @param hash       synthetic event checksum
     * @param occurredAt authoritative event instant
     */
    public record JournalEvent(String eventId, String hash, Instant occurredAt) {
    }

    /**
     * Observed result of a synthetic restore and journal replay.
     *
     * @param stateHash         checksum of the restored state
     * @param replayedEventIds  event identifiers observed after replay
     * @param startedAt         restore start instant
     * @param completedAt       restore completion instant
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
     * @param paperCapture          paper/manual capture procedure exists
     * @param patientIdentification manual patient identification procedure exists
     * @param criticalContacts      approved critical contact channels exist
     * @param supervisedBackEntry   supervised back-entry procedure exists
     */
    public record DowntimeRunbook(boolean paperCapture, boolean patientIdentification,
                                  boolean criticalContacts, boolean supervisedBackEntry) {
    }

    /**
     * One idempotent replay command (ARC-05, OPS-05).
     *
     * @param key    idempotency key scoped to tenant, caller and command
     * @param digest request digest bound to that key
     */
    public record IdempotentCommand(String key, String digest) {
    }

    /**
     * One measured latency sample.
     *
     * @param command   {@code true} for a command, {@code false} for a read
     * @param durationMs server-side duration in milliseconds
     */
    public record LatencySample(boolean command, long durationMs) {
    }

    /**
     * Availability measurement over a fixed request population.
     *
     * @param successes successful requests
     * @param total     total requests
     */
    public record Availability(long successes, long total) {
    }

    /**
     * The approved pilot load profile (OPS-03).
     *
     * @param organizations     concurrent organizations
     * @param charts            total patient charts
     * @param concurrentStaff   concurrently active staff
     * @param requestsPerSecond mixed API request rate
     * @param durationMinutes   test duration
     */
    public record LoadProfile(int organizations, int charts, int concurrentStaff,
                              int requestsPerSecond, int durationMinutes) {
        /** The specification's pilot profile: 20/100000/200/50/30. */
        public static final LoadProfile PILOT = new LoadProfile(20, 100_000, 200, 50, 30);
    }

    /**
     * One UX-01 accessibility check for a locale.
     *
     * @param criterion   stable check key (LABEL, ROLE, SCREEN_READER, RTL)
     * @param locale      supported locale key (en or ar)
     * @param rtlCapable  whether the element is laid out for right-to-left
     * @param passed      whether the check passed
     * @param owner       named accountable reviewer
     */
    public record AccessibilityCheck(String criterion, String locale, boolean rtlCapable,
                                     boolean passed, String owner) {
    }

    /**
     * One audit event with the mandatory OPS-02 fields.
     *
     * @param actor         authenticated actor
     * @param purpose       recorded business purpose
     * @param scope         facility or organization scope
     * @param correlationId request correlation identifier
     * @param action        audited action
     * @param resourceId    affected resource identifier
     * @param timestamp     authoritative event instant
     * @param outcome       SUCCESS, DENIED or FAILURE
     */
    public record AuditEvent(String actor, String purpose, String scope, String correlationId,
                             String action, String resourceId, Instant timestamp, String outcome) {
    }

    /**
     * All synthetic fixture families needed to produce the G3 evidence report.
     *
     * @param snapshot      restore snapshot metadata
     * @param journal       restore journal events
     * @param restore       observed restore result
     * @param runbook       downtime runbook elements
     * @param commands      original idempotent commands
     * @param effects       effects observed after replay
     * @param profile       load profile under test
     * @param samples       latency samples
     * @param availability  availability measurement
     * @param accessibility accessibility checks
     * @param audit         audit events
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
     * @param category  evidence category
     * @param detail    stable human-readable detail or failure codes
     * @param verdict   category outcome
     * @param observedAt evaluation instant
     */
    public record EvidenceRecord(Category category, String detail, Verdict verdict, Instant observedAt) {
    }

    /**
     * One append-only ledger entry chaining an evidence record to its predecessor.
     *
     * @param sequence      one-based sequence number
     * @param record        chained evidence record
     * @param previousHash  hash of the preceding entry or {@code GENESIS}
     * @param hash          checksum of this entry
     */
    public record LedgerEntry(long sequence, EvidenceRecord record, String previousHash, String hash) {
    }

    /**
     * The immutable composite G3 evidence report.
     *
     * @param organizationId owning synthetic organization
     * @param records        one record per category
     * @param chainHead      hash of the final ledger entry
     * @param verdict        composite outcome
     * @param generatedAt    report generation instant
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
