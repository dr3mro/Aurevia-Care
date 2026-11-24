package com.aurevia.clinical.notes;

import java.util.List;

/**
 * Current revision and the complete append-only history of one note (CLN-05).
 *
 * @return this record
 */
public record NoteView(ClinicalNoteRevision current, List<ClinicalNoteRevision> history) {

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
    public List<ClinicalNoteRevision> history() {
        return List.copyOf(history);
    }
}
