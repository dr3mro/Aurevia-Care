package com.aurevia.tenant.importing;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Resumable checkpoint of a controlled onboarding import (OPS-08).
 *
 * <p>Applied batches and their source ids are tracked so a retry after a failed
 * batch skips work already done and never duplicates a source record. Before
 * sign-off the import is staging-only and reversible; {@code finalized} records
 * that an authorized person signed off and the source of truth has changed.</p>
 *
 * @param manifestVersion    version of the manifest being imported
 * @param committedSequences sequences of batches already applied
 * @param committedSourceIds source ids already staged (deduplicated)
 * @param finalized          true after an authorized sign-off
 */
public record ImportProgress(
        int manifestVersion,
        Set<Integer> committedSequences,
        Set<String> committedSourceIds,
        boolean finalized) {

    /**
     * Freeze the tracking sets so a checkpoint cannot change after it is returned.
     *
     * @throws NullPointerException when either set is null
     */
    public ImportProgress {
        committedSequences = Set.copyOf(committedSequences);
        committedSourceIds = Set.copyOf(committedSourceIds);
    }

    /**
     * Return the immutable set of applied batch sequences.
     *
     * @return applied batch sequences
     */
    @Override
    public Set<Integer> committedSequences() {
        return Set.copyOf(committedSequences);
    }

    /**
     * Return the immutable set of staged source ids.
     *
     * @return source ids already staged
     */
    @Override
    public Set<String> committedSourceIds() {
        return Set.copyOf(committedSourceIds);
    }

    /**
     * Create an empty staging checkpoint for a manifest version.
     *
     * @param manifestVersion version of the manifest being imported
     * @return a fresh, unfinalized checkpoint
     */
    public static ImportProgress empty(int manifestVersion) {
        return new ImportProgress(manifestVersion, Set.of(), Set.of(), false);
    }

    /**
     * Report whether a batch sequence has already been applied.
     *
     * @param sequence batch sequence number
     * @return true when the batch is in this checkpoint
     */
    public boolean hasCommitted(int sequence) {
        return committedSequences.contains(sequence);
    }

    /**
     * Return a checkpoint with one more batch applied.
     *
     * <p>Source ids are stored in a set, so re-applying a batch can never create
     * a duplicate record.</p>
     *
     * @param sequence   batch sequence number
     * @param sourceIds  source ids contained in the batch
     * @return a new checkpoint with the batch recorded
     */
    public ImportProgress recordBatch(int sequence, Collection<String> sourceIds) {
        Set<Integer> sequences = new HashSet<>(committedSequences);
        sequences.add(sequence);
        Set<String> ids = new HashSet<>(committedSourceIds);
        ids.addAll(sourceIds);
        return new ImportProgress(manifestVersion, sequences, ids, finalized);
    }

    /**
     * Return this checkpoint marked as signed off.
     *
     * @return a finalized checkpoint with the same applied work
     */
    public ImportProgress finalizeImport() {
        return new ImportProgress(manifestVersion, committedSequences, committedSourceIds, true);
    }
}
