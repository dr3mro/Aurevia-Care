package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.aurevia.messaging.instructions.InstructionActions;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionService;
import com.aurevia.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-04 clause 3 proof using only synthetic fixtures: the five named instruction
 * examples are real typed kinds that survive a send as published revisions and
 * appear in the instruction list for the patient-unit connection. This suite is
 * deliberately not tagged {@code integration} so it runs in the database-free
 * swarm fast gate. The
 * assertions cover only this clause; template approval, clinical authorization,
 * context binding and revision correction belong to adjacent clauses.
 */
class ExamplesIncludeCollectionImagingPreparationTherapyHomeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";

    /** The five instruction kinds named verbatim by this clause. */
    private static final List<Kind> NAMED_EXAMPLES = List.of(Kind.COLLECTION,
            Kind.IMAGING_PREPARATION, Kind.THERAPY_HOME, Kind.VISIT_FOLLOW_UP, Kind.PHARMACY_PICKUP);

    private InMemory store;
    private InstructionService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Seed one verified patient-unit connection and one clinician in the active tenant. */
    private void fixture() {
        store = new InMemory();
        service = new InstructionService(store, new InstructionActions());
        // Seeding is tenant-scoped: bind a context before storing synthetic fixtures.
        bind("admin-a");
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG_A, subject, "MSG04", "corr-1"));
    }

    /** Build a bounded free-text send command that carries exactly one typed example kind. */
    private SendRequest requestFor(Kind kind) {
        return new SendRequest(PATIENT, CONNECTION, UNIT, null, kind,
                "Example instruction", "Synthetic body for " + kind.wire() + ".", "en",
                null, null, null, List.of());
    }

    @Test
    void sendsEveryNamedExampleInstructionKind() {
        fixture();
        bind("clinician-a");
        List<Revision> sent = new ArrayList<>();
        for (Kind kind : NAMED_EXAMPLES) {
            Revision revision = service.send(requestFor(kind));
            assertEquals(kind, revision.kind(), "sent revision must keep the named example kind");
            assertEquals(State.SENT, revision.state(), "the instruction must be published");
            assertNull(revision.templateId(), "the named kind must be carried by a free-text send");
            sent.add(revision);
        }
        assertEquals(NAMED_EXAMPLES.size(), sent.stream().map(Revision::threadId).distinct().count(),
                "each named example must be its own instruction thread");
        List<Revision> visible = service.listForPatient(PATIENT);
        assertEquals(NAMED_EXAMPLES.size(), visible.size(),
                "each named example must appear in the instruction list for the connection");
        assertEquals(Set.copyOf(NAMED_EXAMPLES),
                visible.stream().map(Revision::kind).collect(Collectors.toSet()),
                "the list must contain exactly the five named example kinds");
    }

    @Test
    void refusesAnUnsupportedExampleInstructionKind() {
        assertThrows(IllegalArgumentException.class, () -> Kind.from("unsupported_example"),
                "an arbitrary example wire key must not join the typed vocabulary");
        assertEquals("collection", Kind.COLLECTION.wire());
        assertEquals("imaging_preparation", Kind.IMAGING_PREPARATION.wire());
        assertEquals("therapy_home", Kind.THERAPY_HOME.wire());
        assertEquals("visit_follow_up", Kind.VISIT_FOLLOW_UP.wire());
        assertEquals("pharmacy_pickup", Kind.PHARMACY_PICKUP.wire());
        for (Kind kind : NAMED_EXAMPLES) {
            assertEquals(kind, Kind.from(kind.wire()),
                    "each named example wire key must round-trip to its typed kind");
        }
    }
}
