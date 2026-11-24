package com.aurevia.clinical.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.clinical.notes.ClinicalNoteRevision;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.config.queues.OperationalQueueItem;
import com.aurevia.config.queues.OperationalWorkState;

/**
 * ADM-02 unsigned-encounter queue acceptance with synthetic note revisions only.
 *
 * <p>An encounter enters the queue while it has an editable draft and no
 * signature-bearing revision. Signing it removes it. Tenant scope comes from the
 * fact wrapper; notes of another organization are never read.</p>
 */
class UnsignedEncounterQueueTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String T0 = "2026-11-21T09:00:00Z";
    private static final String T1 = "2026-11-21T10:00:00Z";
    private static final String T2 = "2026-11-21T11:00:00Z";

    @Test
    void aDraftEncounterAppearsAsAnOpenItem() {
        List<OperationalQueueItem> items = UnsignedEncounterQueue.query(ORG, List.of(
                fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T0))));

        assertEquals(1, items.size());
        OperationalQueueItem item = items.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals("encounter-1", item.id());
        assertEquals("unsigned_encounters", item.queue());
        assertEquals(Instant.parse(T0), item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertTrue(item.actions().isEmpty());
    }

    @Test
    void aSignedEncounterLeavesTheQueue() {
        EncounterNoteFact draft = fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T0));

        assertEquals(1, UnsignedEncounterQueue.query(ORG, List.of(draft)).size());

        List<EncounterNoteFact> afterSigning = List.of(draft,
                fact(ORG, note("note-1", NoteState.SIGNED, "encounter-1", T1)));
        assertTrue(UnsignedEncounterQueue.query(ORG, afterSigning).isEmpty());
    }

    @Test
    void anAmendmentDraftWithASignedOriginalLeavesTheQueue() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.SIGNED, "encounter-1", T0)),
                fact(ORG, note("note-2", NoteState.AMENDMENT_DRAFT, "encounter-1", T1)));

        assertTrue(UnsignedEncounterQueue.query(ORG, facts).isEmpty());
    }

    @Test
    void aSignatureBearingAddendumAlsoRemovesTheEncounter() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.SIGNED, "encounter-1", T0)),
                fact(ORG, note("note-2", NoteState.ADDENDUM, "encounter-1", T1)),
                fact(ORG, note("note-3", NoteState.DRAFT, "encounter-1", T2)));

        assertTrue(UnsignedEncounterQueue.query(ORG, facts).isEmpty());
    }

    @Test
    void anEnteredInErrorOnlyEncounterLeavesTheQueue() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.ENTERED_IN_ERROR, "encounter-1", T0)));

        assertTrue(UnsignedEncounterQueue.query(ORG, facts).isEmpty());
    }

    @Test
    void aNoteWithoutAnEncounterIsIgnored() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.DRAFT, null, T0)));

        assertTrue(UnsignedEncounterQueue.query(ORG, facts).isEmpty());
    }

    @Test
    void tenantsNeverSeeEachOthersUnsignedEncounters() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.DRAFT, "same-encounter", T0)),
                fact(OTHER_ORG, note("note-2", NoteState.DRAFT, "same-encounter", T0)),
                fact(OTHER_ORG, note("note-3", NoteState.DRAFT, "other-encounter", T0)));

        List<OperationalQueueItem> orgItems = UnsignedEncounterQueue.query(ORG, facts);
        List<OperationalQueueItem> otherItems = UnsignedEncounterQueue.query(OTHER_ORG, facts);

        assertEquals(List.of("same-encounter"), ids(orgItems));
        assertEquals(List.of("same-encounter", "other-encounter"), ids(otherItems));
        assertEquals(ORG, orgItems.get(0).organizationId());
        assertTrue(otherItems.stream().allMatch(item -> item.organizationId().equals(OTHER_ORG)));
    }

    @Test
    void theOldestDraftRevisionSetsTheCreatedAt() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-3", NoteState.DRAFT, "encounter-1", T2)),
                fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T1)),
                fact(ORG, note("note-2", NoteState.DRAFT, "encounter-1", T0)));

        List<OperationalQueueItem> items = UnsignedEncounterQueue.query(ORG, facts);

        assertEquals(1, items.size());
        assertEquals(Instant.parse(T0), items.get(0).createdAt());
    }

    @Test
    void anEmptyFactListProducesAnEmptyQueue() {
        assertTrue(UnsignedEncounterQueue.query(ORG, List.of()).isEmpty());
    }

    @Test
    void theResultIsFrozenAgainstCallerMutation() {
        List<EncounterNoteFact> facts = new ArrayList<>();
        facts.add(fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T0)));

        List<OperationalQueueItem> items = UnsignedEncounterQueue.query(ORG, facts);
        facts.clear();

        assertEquals(1, items.size());
        assertThrows(UnsupportedOperationException.class, () -> items.clear());
    }

    private static List<String> ids(List<OperationalQueueItem> items) {
        return items.stream().map(OperationalQueueItem::id).toList();
    }

    private static EncounterNoteFact fact(String organizationId, ClinicalNoteRevision revision) {
        return new EncounterNoteFact(organizationId, revision);
    }

    private static ClinicalNoteRevision note(String id, NoteState state, String encounterId, String createdAt) {
        return new ClinicalNoteRevision(id, 1, state, PATIENT, encounterId, "Synthetic title",
                "Synthetic content", "en", "synthetic-template", 1, "clinician-1", "clinician-1",
                createdAt, null, null, null, "synthetic-checksum", null, null, null, "[]", createdAt);
    }
}
