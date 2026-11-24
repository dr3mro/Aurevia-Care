package com.aurevia.exchange.conformance;

import java.util.List;

/**
 * Immutable snapshot of the final report state of one aggregate after a fixture replay.
 *
 * <p>Two wire adapters that carry the same fixture events must produce equal
 * final reports. The snapshot exposes the committed revision, its preserved
 * payload, the review state and the reconciliation and dead-letter outcomes so
 * an older revision or a duplicate can be proven not to have overwritten the
 * latest committed report (ARC-05, XCH-04).</p>
 *
 * @param aggregateKey        local aggregate the report belongs to
 * @param lastRevision        latest committed per-aggregate version, or -1 when unknown
 * @param revisionCount       number of preserved applied revisions
 * @param latestPayload       payload of the latest preserved revision, or null when none
 * @param reviewOpen           whether a clinical review is currently open
 * @param releasedVersion      immutable revision a patient release is bound to, or null
 * @param reconciliationCount events held for identifier reconciliation across the ledger
 * @param deadLetterCount     events whose retry budget was exhausted across the ledger
 * @return this record
 */
public record FinalReport(String aggregateKey, long lastRevision, int revisionCount,
                          String latestPayload, boolean reviewOpen, Long releasedVersion,
                          int reconciliationCount, int deadLetterCount) {

    /**
     * Derive a report snapshot from the observable state of a conformance ledger.
     *
     * @param ledger        ledger that processed the fixture events
     * @param aggregateKey  local aggregate to snapshot
     * @return immutable final report state
     */
    public static FinalReport of(ConformanceLedger ledger, String aggregateKey) {
        List<RevisionRecord> revisions = ledger.revisions(aggregateKey);
        String latest = revisions.isEmpty() ? null : revisions.get(revisions.size() - 1).payload();
        return new FinalReport(aggregateKey, ledger.lastRevision(aggregateKey), revisions.size(),
                latest, ledger.reviewOpen(aggregateKey), ledger.releasedRevision(aggregateKey),
                ledger.reconciliation().size(), ledger.deadLetters().size());
    }
}
