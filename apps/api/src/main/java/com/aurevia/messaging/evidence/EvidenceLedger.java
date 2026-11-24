package com.aurevia.messaging.evidence;

import java.util.ArrayList;
import java.util.List;

import com.aurevia.messaging.evidence.GateEvidenceContract.EvidenceRecord;
import com.aurevia.messaging.evidence.GateEvidenceContract.LedgerEntry;

/**
 * Append-only, tamper-evident chain of synthetic evidence records.
 *
 * <p>Each entry commits the category, detail, verdict and evaluation instant
 * into a hash that also links to its predecessor. {@link com.aurevia.messaging.evidence.EvidenceLedger#verifyChain()} and
 * {@code verify(List)} recompute every link so an altered record, predecessor
 * or sequence is detected. The chain is in-memory and deterministic; a
 * production adapter must persist it with the same append-only guarantees.</p>
 */
public final class EvidenceLedger {

    /** Predecessor hash of the first entry. */
    static final String GENESIS = "GENESIS";

    private final EvidenceHash hash;
    private final List<LedgerEntry> entries = new ArrayList<>();

    /**
     * Create an empty ledger.
     *
     * @param hash deterministic evidence hasher
     */
    public EvidenceLedger(EvidenceHash hash) {
        this.hash = hash;
    }

    /**
     * Append one evidence record and return its chained entry.
     *
     * @param record record to append
     * @return the persisted ledger entry
     */
    public LedgerEntry append(EvidenceRecord record) {
        String previous = entries.isEmpty() ? GENESIS : entries.get(entries.size() - 1).hash();
        long sequence = entries.size() + 1L;
        LedgerEntry entry = new LedgerEntry(sequence, record, previous,
                hash.hash(canonical(record, previous)));
        entries.add(entry);
        return entry;
    }

    /**
     * Return a snapshot of the appended entries.
     *
     * @return immutable copy of the chain
     */
    public List<LedgerEntry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Verify the stored chain.
     *
     * @return {@code true} when every link and checksum is intact
     */
    public boolean verifyChain() {
        return verify(entries());
    }

    /**
     * Verify an arbitrary candidate chain without mutating this ledger.
     *
     * @param candidate entries to verify in order
     * @return {@code true} when sequence, predecessor and checksum all match
     */
    public boolean verify(List<LedgerEntry> candidate) {
        String expectedPrevious = GENESIS;
        long expectedSequence = 1L;
        for (LedgerEntry entry : candidate) {
            if (entry.sequence() != expectedSequence
                    || !expectedPrevious.equals(entry.previousHash())
                    || !hash.hash(canonical(entry.record(), expectedPrevious)).equals(entry.hash())) {
                return false;
            }
            expectedPrevious = entry.hash();
            expectedSequence++;
        }
        return true;
    }

    private static String canonical(EvidenceRecord record, String previousHash) {
        return previousHash + "|" + record.category().wire() + "|" + record.detail() + "|"
                + record.verdict().wire() + "|" + record.observedAt().toEpochMilli();
    }
}
