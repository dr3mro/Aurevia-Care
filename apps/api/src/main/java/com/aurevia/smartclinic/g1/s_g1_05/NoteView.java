package com.aurevia.smartclinic.g1.s_g1_05;

import java.util.List;

/**
 * Current revision and the complete append-only history of one note (CLN-05).
 *
 * @param current the current revision
 * @param history every revision ordered by version ascending
 */
public record NoteView(NoteRevision current, List<NoteRevision> history) {

    /**
     * Freeze the history so callers cannot mutate the in-memory projection.
     *
     * @throws NullPointerException when history is null
     */
    public NoteView {
        history = List.copyOf(history);
    }

    /**
     * Return the immutable revision history.
     *
     * @return revisions ordered by version ascending
     */
    @Override
    public List<NoteRevision> history() {
        return List.copyOf(history);
    }
}
