package com.aurevia.clinical.queues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aurevia.clinical.notes.NoteState;
import com.aurevia.config.queues.OperationalQueueItem;

/**
 * Derives the ADM-02 {@code unsigned_encounters} queue from note revisions (CLN-05).
 *
 * <p>The queue is a projection, not stored state. An encounter is open while it
 * has an editable draft and no signature-bearing revision; signing the draft
 * therefore removes the item without any deletion or queue write. Notes without
 * an encounter context and notes of another tenant are ignored. The oldest
 * contributing revision supplies the item timestamp.</p>
 *
 * <p><em>Declared assumption:</em> the suppression rule is encounter-wide. A
 * signed revision or addendum anywhere in the encounter removes the item, even
 * when a newer amendment draft is still unsigned. This follows the explicit
 * acceptance that a signed encounter leaves the queue; it is narrower than a
 * per-note reading of the ADM-02 catalog and needs a site ruling before live
 * enablement.</p>
 */
public final class UnsignedEncounterQueue {

    /** Stable ADM-02 queue name. */
    public static final String QUEUE = "unsigned_encounters";

    private UnsignedEncounterQueue() {
    }

    /**
     * List the active tenant's encounters that still have an unsigned draft.
     *
     * @param organizationId active tenant
     * @param facts          note facts available to the caller
     * @return immutable, tenant-scoped open work items, one per encounter
     */
    public static List<OperationalQueueItem> query(String organizationId, List<EncounterNoteFact> facts) {
        Map<String, List<EncounterNoteFact>> byEncounter = new LinkedHashMap<>();
        for (EncounterNoteFact fact : facts) {
            String encounterId = fact.revision().encounterId();
            if (encounterId == null || !fact.organizationId().equals(organizationId)) {
                continue;
            }
            byEncounter.computeIfAbsent(encounterId, key -> new ArrayList<>()).add(fact);
        }

        List<OperationalQueueItem> items = new ArrayList<>();
        for (Map.Entry<String, List<EncounterNoteFact>> entry : byEncounter.entrySet()) {
            boolean hasEditableDraft = false;
            boolean hasSignature = false;
            Instant oldest = null;
            for (EncounterNoteFact fact : entry.getValue()) {
                NoteState state = fact.revision().state();
                if (state.editable()) {
                    hasEditableDraft = true;
                }
                if (state.signatureBearing()) {
                    hasSignature = true;
                }
                Instant created = Instant.parse(fact.revision().createdAt());
                if (oldest == null || created.isBefore(oldest)) {
                    oldest = created;
                }
            }
            if (hasEditableDraft && !hasSignature) {
                items.add(OperationalQueueItem.open(organizationId, entry.getKey(), QUEUE, oldest));
            }
        }
        return List.copyOf(items);
    }
}
