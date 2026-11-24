package com.aurevia.messaging.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.evidence.EvidenceHash.DigestFactory;
import com.aurevia.messaging.evidence.GateEvidenceContract.AccessibilityCheck;
import com.aurevia.messaging.evidence.GateEvidenceContract.AuditEvent;
import com.aurevia.messaging.evidence.GateEvidenceContract.Availability;
import com.aurevia.messaging.evidence.GateEvidenceContract.Category;
import com.aurevia.messaging.evidence.GateEvidenceContract.DowntimeRunbook;
import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceInput;
import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceRecord;
import com.aurevia.messaging.evidence.GateEvidenceContract.IdempotentCommand;
import com.aurevia.messaging.evidence.GateEvidenceContract.JournalEvent;
import com.aurevia.messaging.evidence.GateEvidenceContract.LatencySample;
import com.aurevia.messaging.evidence.GateEvidenceContract.LedgerEntry;
import com.aurevia.messaging.evidence.GateEvidenceContract.LoadProfile;
import com.aurevia.messaging.evidence.GateEvidenceContract.RestoreResult;
import com.aurevia.messaging.evidence.GateEvidenceContract.Snapshot;
import com.aurevia.messaging.evidence.GateEvidenceContract.Verdict;

/**
 * Unit acceptance for the synthetic G3 gate-evidence slice.
 *
 * <p>Every fixture uses synthetic identifiers and instants; no patient data is
 * used. The five categories map to OPS-04, OPS-05, OPS-03, UX-01 and OPS-02.</p>
 */
class GateEvidenceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-19T16:00:00Z");

    private static final Snapshot SNAPSHOT = new Snapshot("snap-hash", "v3", NOW.minusSeconds(300));
    private static final List<JournalEvent> JOURNAL = List.of(
            new JournalEvent("evt-1", "hash-1", NOW.minusSeconds(240)),
            new JournalEvent("evt-2", "hash-2", NOW.minusSeconds(180)),
            new JournalEvent("evt-3", "hash-3", NOW.minusSeconds(60)));
    private static final RestoreResult RESTORE = new RestoreResult(
            "snap-hash|hash-1|hash-2|hash-3", List.of("evt-1", "evt-2", "evt-3"),
            NOW.minusSeconds(30), NOW.minusSeconds(10));

    private static final DowntimeRunbook RUNBOOK = new DowntimeRunbook(true, true, true, true);
    private static final List<IdempotentCommand> COMMANDS = List.of(
            new IdempotentCommand("key-1", "digest-1"), new IdempotentCommand("key-2", "digest-2"));
    private static final List<IdempotentCommand> EFFECTS = List.of(
            new IdempotentCommand("key-1", "digest-1"), new IdempotentCommand("key-2", "digest-2"));

    private static final List<LatencySample> SAMPLES = samples(100, 40, 100, 800);
    private static final Availability AVAILABILITY = new Availability(999, 1000);
    private static final LoadProfile PROFILE = LoadProfile.PILOT;

    private static final List<AccessibilityCheck> ACCESSIBILITY = accessibility();
    private static final List<AuditEvent> AUDIT = List.of(
            new AuditEvent("subject-1", "TREATMENT", "facility-1", "corr-1", "READ", "chart-1",
                    NOW.minusSeconds(5), "SUCCESS"));

    private final GateEvidenceEvaluator evaluator = new GateEvidenceEvaluator();

    private static List<LatencySample> samples(int reads, long readMs, int commands, long commandMs) {
        List<LatencySample> values = new ArrayList<>();
        for (int i = 0; i < reads; i++) {
            values.add(new LatencySample(false, readMs));
        }
        for (int i = 0; i < commands; i++) {
            values.add(new LatencySample(true, commandMs));
        }
        return values;
    }

    private static List<AccessibilityCheck> accessibility() {
        List<AccessibilityCheck> checks = new ArrayList<>();
        for (String locale : List.of("en", "ar")) {
            boolean rtl = "ar".equals(locale);
            for (String criterion : List.of("LABEL", "ROLE", "SCREEN_READER", "RTL")) {
                checks.add(new AccessibilityCheck(criterion, locale, rtl, true, "ux-owner"));
            }
        }
        return checks;
    }

    private static EvidenceInput input() {
        return new EvidenceInput(SNAPSHOT, JOURNAL, RESTORE, RUNBOOK, COMMANDS, EFFECTS,
                PROFILE, SAMPLES, AVAILABILITY, ACCESSIBILITY, AUDIT);
    }

    // ------------------------------------------------------------------ vocabulary

    @Test
    void categoryAndVerdictRoundTripEveryWireValue() {
        for (Category category : Category.values()) {
            assertEquals(category, Category.from(category.wire()));
        }
        assertEquals(5, Category.values().length);
        assertEquals("restore", Category.RESTORE.wire());
        assertEquals("audit", Category.AUDIT.wire());
        for (Verdict verdict : Verdict.values()) {
            assertEquals(verdict, Verdict.from(verdict.wire()));
        }
        assertEquals(2, Verdict.values().length);
        assertEquals("pass", Verdict.PASS.wire());
    }

    @Test
    void unknownCategoryAndVerdictAreRejected() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Category.from("guess"))
                .getMessage().contains("evidence category"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Verdict.from("maybe"))
                .getMessage().contains("evidence verdict"));
    }

    @Test
    void recordsDefensivelyCopyCollections() {
        List<JournalEvent> mutable = new ArrayList<>(JOURNAL);
        EvidenceInput value = new EvidenceInput(SNAPSHOT, mutable, RESTORE, RUNBOOK, COMMANDS, EFFECTS,
                PROFILE, SAMPLES, AVAILABILITY, ACCESSIBILITY, AUDIT);
        mutable.add(new JournalEvent("evt-4", "hash-4", NOW));
        assertEquals(3, value.journal().size());
        assertThrows(UnsupportedOperationException.class, () -> value.journal().add(JOURNAL.get(0)));
        RestoreResult copy = new RestoreResult("h", new ArrayList<>(List.of("e")), NOW, NOW);
        assertEquals(List.of("e"), copy.replayedEventIds());
        assertThrows(UnsupportedOperationException.class, () -> copy.replayedEventIds().add("x"));
        assertEquals(JOURNAL.get(0), new JournalEvent("evt-1", "hash-1", NOW.minusSeconds(240)));
        assertEquals(JOURNAL.get(0).hashCode(),
                new JournalEvent("evt-1", "hash-1", NOW.minusSeconds(240)).hashCode());
        assertTrue(JOURNAL.get(0).toString().contains("evt-1"));
    }

    // ------------------------------------------------------------------ hashing / ledger

    @Test
    void hashIsDeterministicOrderSensitiveAndProducesLowercaseHex() {
        EvidenceHash hash = new EvidenceHash();
        assertEquals(hash.hash("a|b"), hash.hash("a|b"));
        assertNotEquals(hash.hash("a|b"), hash.hash("b|a"));
        assertTrue(hash.hash(" ").matches("[0-9a-f]{64}"));
        DigestFactory missing = () -> { throw new NoSuchAlgorithmException("absent"); };
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new EvidenceHash(missing).hash("a"));
        assertTrue(error.getMessage().contains("SHA-256"));
        assertEquals(1, "x".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void ledgerAppendsAnImmutableHashChainAndDetectsTampering() {
        EvidenceHash hash = new EvidenceHash();
        EvidenceLedger ledger = new EvidenceLedger(hash);
        assertTrue(ledger.verifyChain());
        EvidenceRecord first = new EvidenceRecord(Category.RESTORE, "detail-1", Verdict.PASS, NOW);
        EvidenceRecord second = new EvidenceRecord(Category.AUDIT, "detail-2", Verdict.PASS, NOW);
        LedgerEntry one = ledger.append(first);
        LedgerEntry two = ledger.append(second);
        assertEquals(1, one.sequence());
        assertEquals(2, two.sequence());
        assertEquals("GENESIS", one.previousHash());
        assertEquals(one.hash(), two.previousHash());
        assertTrue(ledger.verifyChain());
        assertTrue(ledger.verify(List.of(one)));
        assertThrows(UnsupportedOperationException.class, () -> ledger.entries().add(two));

        List<LedgerEntry> tamperedRecord = new ArrayList<>(ledger.entries());
        tamperedRecord.set(0, new LedgerEntry(1,
                new EvidenceRecord(Category.RESTORE, "changed", Verdict.PASS, NOW), "GENESIS", one.hash()));
        assertFalse(ledger.verify(tamperedRecord));
        List<LedgerEntry> tamperedPrev = new ArrayList<>(ledger.entries());
        tamperedPrev.set(1, new LedgerEntry(2, second, "wrong-prev", two.hash()));
        assertFalse(ledger.verify(tamperedPrev));
        List<LedgerEntry> tamperedSeq = new ArrayList<>(ledger.entries());
        tamperedSeq.set(1, new LedgerEntry(9, second, one.hash(), two.hash()));
        assertFalse(ledger.verify(tamperedSeq));
    }

    // ------------------------------------------------------------------ restore (OPS-04)

    @Test
    void restorePassesWhenSnapshotJournalAndReplayReconcile() {
        EvidenceRecord record = evaluator.evaluateRestore(SNAPSHOT, JOURNAL, RESTORE, NOW);
        assertEquals(Category.RESTORE, record.category());
        assertEquals(Verdict.PASS, record.verdict());
        assertTrue(record.detail().contains("rpo="));
        assertTrue(record.detail().contains("rto="));
        assertEquals(NOW, record.observedAt());
    }

    @Test
    void restoreFailsOnStaleRecoveryPointExceedingFifteenMinutes() {
        EvidenceRecord record = evaluator.evaluateRestore(SNAPSHOT, JOURNAL, RESTORE, NOW.plusSeconds(16 * 60));
        assertEquals(Verdict.FAIL, record.verdict());
        assertTrue(record.detail().contains("RPO_EXCEEDED"));
    }

    @Test
    void restoreFailsOnRecoveryTimeExceedingFourHoursAndOnFutureSnapshot() {
        RestoreResult slow = new RestoreResult(RESTORE.stateHash(), RESTORE.replayedEventIds(),
                RESTORE.startedAt(), NOW.plusSeconds(60L * 60 * 5));
        EvidenceRecord record = evaluator.evaluateRestore(SNAPSHOT, JOURNAL, slow, NOW);
        assertEquals(Verdict.FAIL, record.verdict());
        assertTrue(record.detail().contains("RTO_EXCEEDED"));
        EvidenceRecord future = evaluator.evaluateRestore(
                new Snapshot("h", "v", NOW.plusSeconds(1)), JOURNAL, RESTORE, NOW);
        assertTrue(future.detail().contains("SNAPSHOT_IN_FUTURE"));
    }

    @Test
    void restoreFailsOnStateHashMismatch() {
        RestoreResult wrong = new RestoreResult("different", RESTORE.replayedEventIds(),
                RESTORE.startedAt(), RESTORE.completedAt());
        assertTrue(evaluator.evaluateRestore(SNAPSHOT, JOURNAL, wrong, NOW).detail()
                .contains("STATE_HASH_MISMATCH"));
    }

    @Test
    void restoreFailsOnDuplicateUnknownOrIncompleteReplay() {
        RestoreResult duplicate = new RestoreResult(RESTORE.stateHash(),
                List.of("evt-1", "evt-1", "evt-2", "evt-3"), RESTORE.startedAt(), RESTORE.completedAt());
        assertTrue(evaluator.evaluateRestore(SNAPSHOT, JOURNAL, duplicate, NOW).detail()
                .contains("DUPLICATE_REPLAY"));
        RestoreResult unknown = new RestoreResult(RESTORE.stateHash(),
                List.of("evt-1", "evt-2", "evt-9"), RESTORE.startedAt(), RESTORE.completedAt());
        assertTrue(evaluator.evaluateRestore(SNAPSHOT, JOURNAL, unknown, NOW).detail()
                .contains("UNKNOWN_EVENT"));
        RestoreResult incomplete = new RestoreResult(RESTORE.stateHash(),
                List.of("evt-1", "evt-2"), RESTORE.startedAt(), RESTORE.completedAt());
        assertTrue(evaluator.evaluateRestore(SNAPSHOT, JOURNAL, incomplete, NOW).detail()
                .contains("INCOMPLETE_REPLAY"));
    }

    @Test
    void restoreUsesSnapshotHashForAnEmptyJournal() {
        Snapshot recent = new Snapshot("snap", "v3", NOW.minusSeconds(60));
        RestoreResult restored = new RestoreResult("snap", List.of(), NOW.minusSeconds(30), NOW);
        assertEquals(Verdict.PASS, evaluator.evaluateRestore(recent, List.of(), restored, NOW).verdict());
        RestoreResult wrongHash = new RestoreResult("other", List.of(), NOW.minusSeconds(30), NOW);
        assertEquals(Verdict.FAIL, evaluator.evaluateRestore(recent, List.of(), wrongHash, NOW).verdict());
    }

    // ------------------------------------------------------------------ downtime (OPS-05)

    @Test
    void downtimePassesWithFullRunbookAndSingleRecoveryEffects() {
        EvidenceRecord record = evaluator.evaluateDowntime(RUNBOOK, COMMANDS, EFFECTS);
        assertEquals(Category.DOWNTIME, record.category());
        assertEquals(Verdict.PASS, record.verdict());
    }

    @Test
    void downtimeFailsForEveryMissingRunbookElement() {
        List<DowntimeRunbook> incomplete = List.of(
                new DowntimeRunbook(false, true, true, true),
                new DowntimeRunbook(true, false, true, true),
                new DowntimeRunbook(true, true, false, true),
                new DowntimeRunbook(true, true, true, false));
        List<String> expected = List.of("PAPER_CAPTURE", "PATIENT_IDENTIFICATION", "CRITICAL_CONTACTS",
                "SUPERVISED_BACK_ENTRY");
        for (int i = 0; i < incomplete.size(); i++) {
            EvidenceRecord record = evaluator.evaluateDowntime(incomplete.get(i), COMMANDS, EFFECTS);
            assertEquals(Verdict.FAIL, record.verdict());
            assertTrue(record.detail().contains(expected.get(i) + "_MISSING"));
        }
    }

    @Test
    void downtimeFailsOnConflictingDigestDuplicateEffectAndUnknownOrIncompleteReplay() {
        List<IdempotentCommand> conflict = List.of(
                new IdempotentCommand("key-1", "digest-1"), new IdempotentCommand("key-1", "different"));
        assertTrue(evaluator.evaluateDowntime(RUNBOOK, conflict, conflict).detail()
                .contains("CONFLICTING_DIGEST"));
        List<IdempotentCommand> duplicateEffect = List.of(
                new IdempotentCommand("key-1", "digest-1"), new IdempotentCommand("key-1", "digest-1"),
                new IdempotentCommand("key-2", "digest-2"));
        assertTrue(evaluator.evaluateDowntime(RUNBOOK, COMMANDS, duplicateEffect).detail()
                .contains("DUPLICATE_EFFECT"));
        List<IdempotentCommand> unknown = List.of(
                new IdempotentCommand("key-1", "digest-1"), new IdempotentCommand("key-9", "digest-9"));
        assertTrue(evaluator.evaluateDowntime(RUNBOOK, COMMANDS, unknown).detail()
                .contains("UNKNOWN_REPLAY"));
        List<IdempotentCommand> missing = List.of(new IdempotentCommand("key-1", "digest-1"));
        assertTrue(evaluator.evaluateDowntime(RUNBOOK, COMMANDS, missing).detail()
                .contains("INCOMPLETE_RECOVERY"));
    }

    // ------------------------------------------------------------------ load (OPS-03)

    @Test
    void loadPassesWhenP95AvailabilityAndPilotProfileMeetTargets() {
        EvidenceRecord record = evaluator.evaluateLoad(PROFILE, SAMPLES, AVAILABILITY);
        assertEquals(Category.LOAD, record.category());
        assertEquals(Verdict.PASS, record.verdict());
    }

    @Test
    void loadFailsOnReadAndCommandP95Breaches() {
        List<LatencySample> slowReads = samples(100, 501, 100, 800);
        assertTrue(evaluator.evaluateLoad(PROFILE, slowReads, AVAILABILITY).detail()
                .contains("READ_P95_EXCEEDED"));
        List<LatencySample> slowCommands = samples(100, 40, 100, 901);
        assertTrue(evaluator.evaluateLoad(PROFILE, slowCommands, AVAILABILITY).detail()
                .contains("COMMAND_P95_EXCEEDED"));
    }

    @Test
    void loadFailsOnMissingSamplesLowAvailabilityAndWrongProfile() {
        assertTrue(evaluator.evaluateLoad(PROFILE, List.of(), AVAILABILITY).detail()
                .contains("NO_READ_SAMPLES"));
        assertTrue(evaluator.evaluateLoad(PROFILE, samples(100, 40, 0, 0), AVAILABILITY).detail()
                .contains("NO_COMMAND_SAMPLES"));
        assertTrue(evaluator.evaluateLoad(PROFILE, SAMPLES, new Availability(998, 1000)).detail()
                .contains("AVAILABILITY_BELOW_TARGET"));
        assertTrue(evaluator.evaluateLoad(PROFILE, SAMPLES, new Availability(0, 0)).detail()
                .contains("AVAILABILITY_BELOW_TARGET"));
        LoadProfile other = new LoadProfile(1, 1, 1, 1, 1);
        assertTrue(evaluator.evaluateLoad(other, SAMPLES, AVAILABILITY).detail()
                .contains("PROFILE_MISMATCH"));
    }

    @Test
    void boundaryLatencyAndAvailabilityValuesPass() {
        List<LatencySample> boundary = samples(100, 500, 100, 900);
        assertEquals(Verdict.PASS, evaluator.evaluateLoad(PROFILE, boundary,
                new Availability(999, 1000)).verdict());
    }

    // ------------------------------------------------------------------ accessibility (UX-01)

    @Test
    void accessibilityPassesWhenEveryRequiredCheckExistsForBothLocales() {
        EvidenceRecord record = evaluator.evaluateAccessibility(ACCESSIBILITY);
        assertEquals(Category.ACCESSIBILITY, record.category());
        assertEquals(Verdict.PASS, record.verdict());
    }

    @Test
    void accessibilityFailsOnFailedCheckMissingOwnerUnknownLocaleAndRtlMismatch() {
        List<AccessibilityCheck> failed = new ArrayList<>(accessibility());
        failed.set(0, new AccessibilityCheck("LABEL", "en", false, false, "ux-owner"));
        assertTrue(evaluator.evaluateAccessibility(failed).detail().contains("LABEL_FAILED"));
        List<AccessibilityCheck> noOwner = new ArrayList<>(accessibility());
        noOwner.set(0, new AccessibilityCheck("LABEL", "en", false, true, ""));
        assertTrue(evaluator.evaluateAccessibility(noOwner).detail().contains("OWNER_MISSING"));
        List<AccessibilityCheck> wrongLocale = new ArrayList<>(accessibility());
        wrongLocale.set(0, new AccessibilityCheck("LABEL", "fr", false, true, "ux-owner"));
        assertTrue(evaluator.evaluateAccessibility(wrongLocale).detail().contains("UNKNOWN_LOCALE"));
        List<AccessibilityCheck> wrongRtl = new ArrayList<>(accessibility());
        wrongRtl.set(0, new AccessibilityCheck("LABEL", "en", true, true, "ux-owner"));
        assertTrue(evaluator.evaluateAccessibility(wrongRtl).detail().contains("RTL_MISMATCH"));
    }

    @Test
    void accessibilityFailsWhenARequiredCheckIsMissingForALocaleOrListIsEmpty() {
        List<AccessibilityCheck> missing = new ArrayList<>(accessibility());
        missing.removeIf(check -> "ROLE".equals(check.criterion()) && "ar".equals(check.locale()));
        assertTrue(evaluator.evaluateAccessibility(missing).detail().contains("MISSING_ROLE_ar"));
        assertTrue(evaluator.evaluateAccessibility(List.of()).detail().contains("NO_CHECKS"));
    }

    // ------------------------------------------------------------------ audit (OPS-02)

    @Test
    void auditPassesWhenEventsArePresentAndChainIsValid() {
        EvidenceRecord record = evaluator.evaluateAudit(AUDIT, true);
        assertEquals(Category.AUDIT, record.category());
        assertEquals(Verdict.PASS, record.verdict());
        assertEquals(Verdict.FAIL, evaluator.evaluateAudit(AUDIT, false).verdict());
    }

    @Test
    void auditFailsOnEmptyEventsAndBlankRequiredFields() {
        assertTrue(evaluator.evaluateAudit(List.of(), true).detail().contains("NO_AUDIT_EVENTS"));
        AuditEvent blank = new AuditEvent("", "", "", "", "", "", null, "");
        String detail = evaluator.evaluateAudit(List.of(blank), true).detail();
        for (String field : List.of("ACTOR", "PURPOSE", "SCOPE", "CORRELATION", "ACTION", "RESOURCE",
                "TIMESTAMP", "OUTCOME")) {
            assertTrue(detail.contains(field + "_MISSING"), field);
        }
        AuditEvent nullFields = new AuditEvent(null, null, null, null, null, null, null, null);
        String nullDetail = evaluator.evaluateAudit(List.of(nullFields), true).detail();
        for (String field : List.of("ACTOR", "PURPOSE", "SCOPE", "CORRELATION", "ACTION", "RESOURCE",
                "OUTCOME")) {
            assertTrue(nullDetail.contains(field + "_MISSING"), field);
        }
    }

    // ------------------------------------------------------------------ service orchestration

    @Test
    void serviceProducesAllFiveRecordsAndAValidChain() {
        GateEvidenceService service = new GateEvidenceService(new EvidenceHash(), fixedClock());
        var report = service.produce(ORG, input());
        assertEquals(ORG, report.organizationId());
        assertEquals(5, report.records().size());
        assertEquals(Verdict.PASS, report.verdict());
        assertEquals(NOW, report.generatedAt());
        assertEquals(5, service.ledger().entries().size());
        assertTrue(service.ledger().verifyChain());
        assertEquals(service.ledger().entries().get(4).hash(), report.chainHead());
        assertEquals(report, report);
        assertEquals(report.hashCode(), report.hashCode());
        assertTrue(report.toString().contains(ORG));
    }

    @Test
    void serviceReportsFailVerdictWhenAnyCategoryFails() {
        EvidenceInput failing = new EvidenceInput(SNAPSHOT, JOURNAL, RESTORE, RUNBOOK, COMMANDS, EFFECTS,
                PROFILE, SAMPLES, AVAILABILITY, List.of(), AUDIT);
        GateEvidenceService service = new GateEvidenceService(new EvidenceHash(), fixedClock());
        assertEquals(Verdict.FAIL, service.produce(ORG, failing).verdict());
    }

    @Test
    void serviceRejectsMissingOrganization() {
        GateEvidenceService service = new GateEvidenceService(new EvidenceHash(), fixedClock());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.produce(null, input())).status());
        assertEquals("INVALID_ORGANIZATION", assertThrows(ApiException.class,
                () -> service.produce("  ", input())).code());
    }

    @Test
    void reportDefensivelyCopiesRecords() {
        List<EvidenceRecord> mutable = new ArrayList<>();
        mutable.add(new EvidenceRecord(Category.RESTORE, "d", Verdict.PASS, NOW));
        var report = new GateEvidenceContract.GateEvidenceReport(ORG, mutable, "head", Verdict.PASS, NOW);
        mutable.add(new EvidenceRecord(Category.AUDIT, "d", Verdict.PASS, NOW));
        assertEquals(1, report.records().size());
        assertThrows(UnsupportedOperationException.class, () -> report.records().clear());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
