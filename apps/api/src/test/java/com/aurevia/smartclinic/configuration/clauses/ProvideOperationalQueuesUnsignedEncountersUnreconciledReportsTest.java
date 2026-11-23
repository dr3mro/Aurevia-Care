package com.aurevia.smartclinic.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision;
import com.aurevia.smartclinic.clinical.notes.NoteState;
import com.aurevia.smartclinic.clinical.queues.EncounterNoteFact;
import com.aurevia.smartclinic.clinical.queues.UnsignedEncounterQueue;
import com.aurevia.smartclinic.config.queues.OperationalQueueItem;
import com.aurevia.smartclinic.config.queues.OperationalWorkState;
import com.aurevia.smartclinic.config.rosters.CoverageRosterFact;
import com.aurevia.smartclinic.config.rosters.MissingCoverageQueue;

/**
 * ADM-02 clause 1 (API evidence): the seven operational queues for unsigned encounters,
 * unreconciled reports, unacknowledged critical findings, rejected exchanges, document
 * failures, missing coverage and revoked accounts.
 *
 * <p>The tests prove the part that exists today: two tenant-scoped, read-derived
 * projections — {@code UnsignedEncounterQueue} and {@code MissingCoverageQueue} — return
 * open {@code OperationalQueueItem} values and refuse a signed encounter, another tenant's
 * fact and a fully staffed roster. Both are pure domain functions over caller-supplied
 * facts, so these are unit tests with no Spring context, no database and no HTTP server.</p>
 *
 * <p>The uncovered part of the clause is recorded in the clause document's Gap: there is
 * no HTTP aggregate over all seven catalog queues, no durable store, no operator allowlist
 * or next-action revocation and no audit-id enforcement. A future wiring task supplies the
 * running HTTP server, seeded two-org operator fixtures, a real audit event and its own
 * HTTP-level integration test. Fixtures are synthetic — two organizations with local-only
 * ids, no patient data or credentials.</p>
 */
class ProvideOperationalQueuesUnsignedEncountersUnreconciledReportsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String T0 = "2026-11-21T09:00:00Z";
    private static final String T1 = "2026-11-21T10:00:00Z";
    private static final String T2 = "2026-11-21T11:00:00Z";
    private static final Instant CREATED = Instant.parse(T0);

    /** An unsigned draft encounter yields one open item tagged with the oldest revision. */
    @Test
    void anUnsignedEncounterAppearsAsAnOpenOperationalQueueItem() {
        // Deliberately out of time order so the assertion proves "oldest wins", not input order.
        List<OperationalQueueItem> items = UnsignedEncounterQueue.query(ORG, List.of(
                fact(ORG, note("note-3", NoteState.DRAFT, "encounter-1", T2)),
                fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T1)),
                fact(ORG, note("note-2", NoteState.DRAFT, "encounter-1", T0))));

        assertEquals(1, items.size());
        OperationalQueueItem item = items.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals("encounter-1", item.id());
        assertEquals("unsigned_encounters", item.queue());
        assertEquals(Instant.parse(T0), item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertNull(item.owner());
        assertNull(item.reason());
        assertTrue(item.actions().isEmpty());
    }

    /** A signature anywhere in the encounter suppresses the unsigned-encounter item. */
    @Test
    void aSignedEncounterIsAbsentFromTheUnsignedEncounterQueue() {
        EncounterNoteFact draft = fact(ORG, note("note-1", NoteState.DRAFT, "encounter-1", T0));

        // Positive control: the item exists before the signature, so the empty result
        // below is caused by the signature rather than by a malformed fixture.
        assertEquals(1, UnsignedEncounterQueue.query(ORG, List.of(draft)).size());

        List<EncounterNoteFact> afterSigning = List.of(draft,
                fact(ORG, note("note-2", NoteState.SIGNED, "encounter-1", T1)));
        assertTrue(UnsignedEncounterQueue.query(ORG, afterSigning).isEmpty());
    }

    /** A fact owned by another tenant never enters this tenant's queue. */
    @Test
    void anotherTenantsUnsignedEncounterIsNotReturned() {
        List<EncounterNoteFact> facts = List.of(
                fact(ORG, note("note-1", NoteState.DRAFT, "same-encounter", T0)),
                fact(OTHER_ORG, note("note-2", NoteState.DRAFT, "same-encounter", T0)),
                fact(OTHER_ORG, note("note-3", NoteState.DRAFT, "other-encounter", T1)));

        List<OperationalQueueItem> orgItems = UnsignedEncounterQueue.query(ORG, facts);
        List<OperationalQueueItem> otherItems = UnsignedEncounterQueue.query(OTHER_ORG, facts);

        assertEquals(Set.of("same-encounter"), idSet(orgItems));
        assertEquals(Set.of("same-encounter", "other-encounter"), idSet(otherItems));
        assertTrue(orgItems.stream().allMatch(item -> ORG.equals(item.organizationId())));
        assertTrue(otherItems.stream().allMatch(item -> OTHER_ORG.equals(item.organizationId())));
    }

    /** A roster with no named owner yields one open missing-coverage work item. */
    @Test
    void aRosterWithNoOwnerAppearsAsAnOpenMissingCoverageItem() {
        List<OperationalQueueItem> items = MissingCoverageQueue.query(ORG, List.of(
                roster(ORG, "unit-1", null, "backup-1", CREATED)));

        assertEquals(1, items.size());
        OperationalQueueItem item = items.get(0);
        assertEquals(ORG, item.organizationId());
        assertEquals("unit-1", item.id());
        assertEquals("missing_coverage", item.queue());
        assertEquals(CREATED, item.createdAt());
        assertEquals(OperationalWorkState.OPEN, item.state());
        assertTrue(item.actions().isEmpty());
    }

    /** A roster with both a named owner and backup has no coverage gap to work. */
    @Test
    void aFullyStaffedRosterIsAbsentFromTheMissingCoverageQueue() {
        CoverageRosterFact staffed = roster(ORG, "staffed", "owner-1", "backup-1", CREATED);

        assertTrue(MissingCoverageQueue.query(ORG, List.of(staffed)).isEmpty());
        // Mixed-list control: the empty result is due to the staffed roster, not the query.
        assertEquals(List.of("gap"), ids(MissingCoverageQueue.query(ORG, List.of(staffed,
                roster(ORG, "gap", null, "backup-1", CREATED)))));
    }

    /**
     * Return the item ids in projection order.
     *
     * @param items projected work items
     * @return their local ids
     */
    private static List<String> ids(List<OperationalQueueItem> items) {
        return items.stream().map(OperationalQueueItem::id).toList();
    }

    /**
     * Return item ids as a set so tenant assertions do not depend on encounter order.
     *
     * @param items projected work items
     * @return their distinct local ids
     */
    private static Set<String> idSet(List<OperationalQueueItem> items) {
        return Set.copyOf(ids(items));
    }

    /**
     * Wrap a note revision with an explicit owning tenant.
     *
     * @param organizationId owning tenant
     * @param revision       synthetic note revision
     * @return the tenant-scoped fact
     */
    private static EncounterNoteFact fact(String organizationId, ClinicalNoteRevision revision) {
        return new EncounterNoteFact(organizationId, revision);
    }

    /**
     * Build a synthetic note revision; null signature fields model an unsigned draft.
     *
     * @param id          synthetic note id
     * @param state       lifecycle state under test
     * @param encounterId optional encounter context
     * @param createdAt   ingestion instant
     * @return the immutable synthetic revision
     */
    private static ClinicalNoteRevision note(String id, NoteState state, String encounterId, String createdAt) {
        return new ClinicalNoteRevision(id, 1, state, PATIENT, encounterId, "Synthetic title",
                "Synthetic content", "en", "synthetic-template", 1, "clinician-1", "clinician-1",
                createdAt, null, null, null, "synthetic-checksum", null, null, null, "[]", createdAt);
    }

    /**
     * Build a synthetic coverage roster fact.
     *
     * @param organizationId owning tenant
     * @param unitId         synthetic unit id
     * @param owner          primary owner, or {@code null} for a gap
     * @param backup         backup owner, or {@code null} for a gap
     * @param createdAt      instant the roster fact was recorded
     * @return the immutable synthetic fact
     */
    private static CoverageRosterFact roster(String organizationId, String unitId, String owner,
                                             String backup, Instant createdAt) {
        return new CoverageRosterFact(organizationId, unitId, owner, backup, createdAt);
    }
}
