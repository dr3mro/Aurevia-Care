package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.instructions.InstructionActions;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.DefineTemplateRequest;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.Kind;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.ReplaceRequest;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.Revision;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.smartclinic.messaging.instructions.InstructionContract.State;
import com.aurevia.smartclinic.messaging.instructions.InstructionService;
import com.aurevia.smartclinic.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-04 clause 2 acceptance: an instruction carries the care relationship it
 * was issued for, and refuses a relationship that does not match its template
 * or that is not a bounded key.
 *
 * <p>All fixtures are synthetic and tenant-keyed. The in-memory store is the
 * same fast-tier adapter used by the neighbouring instruction tests, so these
 * assertions observe the service's binding rules without a database. The HTTP
 * route that reaches {@code InstructionService.send} is exercised by its own
 * controller suite; this class makes no HTTP or persistence claim.</p>
 */
class TieInstructionsRelevantAppointmentOrderReferralPrescriptionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";

    /** One allowed care relationship paired with a distinct synthetic local key. */
    private record ContextBinding(ContextType type, String key) {
    }

    private static final List<ContextBinding> BINDINGS = List.of(
            new ContextBinding(ContextType.APPOINTMENT, "appt-1"),
            new ContextBinding(ContextType.ORDER, "order-1"),
            new ContextBinding(ContextType.REFERRAL, "ref-1"),
            new ContextBinding(ContextType.PRESCRIPTION, "rx-1"),
            new ContextBinding(ContextType.ENCOUNTER, "enc-1"));

    private InMemory store;
    private InstructionService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void fixture() {
        store = new InMemory();
        // The public constructor uses the application clock; no assertion here
        // depends on a timestamp, only on the bound relationship and its key.
        service = new InstructionService(store, new InstructionActions());
        // Seeding is tenant-scoped, so a context must be bound before fixtures.
        bind(ORG_A, "admin-a");
        store.seedMember("admin-a", List.of("tenant_admin"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
    }

    private void bind(String organization, String subject) {
        TenantContext.set(new TenantContext(organization, subject, "MSG04", "corr-1"));
    }

    private SendRequest freeText(ContextType type, String contextId) {
        return new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.COLLECTION,
                "Morning collection", "Please fast before the collection.", "en", null,
                type, contextId, List.of());
    }

    private SendRequest templated(String templateId, ContextType type, String contextId) {
        return new SendRequest(PATIENT, CONNECTION, UNIT, templateId, null, null, null, null, null,
                type, contextId, List.of());
    }

    private void assertBinding(Revision revision, ContextType type, String key) {
        assertEquals(type, revision.contextType());
        assertEquals(key, revision.contextId());
        assertEquals(State.SENT, revision.state());
    }

    @Test
    void sendBindsEachRelevantContextTypeAndIdToThePublishedRevision() {
        fixture();
        bind(ORG_A, "clinician-a");

        for (ContextBinding binding : BINDINGS) {
            Revision sent = service.send(freeText(binding.type(), binding.key()));

            // Three surfaces: the command result, the stored revision and the
            // read model. Removing the stamping in send() fails all three.
            assertBinding(sent, binding.type(), binding.key());
            assertBinding(store.current(sent.threadId()).orElseThrow(), binding.type(), binding.key());
            assertBinding(service.get(sent.threadId()).current(), binding.type(), binding.key());
        }
    }

    @Test
    void sendWithTemplateRequiringADifferentContextIsRejected() {
        fixture();
        bind(ORG_A, "admin-a");
        service.defineTemplate(new DefineTemplateRequest(UNIT, "collection-v1", Kind.COLLECTION,
                "Collection prep", "Please fast before 9am.", "en", false, ContextType.ORDER));

        bind(ORG_A, "clinician-a");
        // The key is valid on purpose: the refusal must come from the template
        // gate, not from the earlier InstructionInputs.key check.
        ApiException mismatch = assertThrows(ApiException.class,
                () -> service.send(templated("collection-v1", ContextType.APPOINTMENT, "appt-1")));
        assertEquals(422, mismatch.status());
        assertEquals("CONTEXT_REQUIRED", mismatch.code());
        assertEquals(List.of(), service.listForPatient(PATIENT));

        Revision accepted = service.send(templated("collection-v1", ContextType.ORDER, "order-1"));
        assertBinding(accepted, ContextType.ORDER, "order-1");
        assertBinding(store.current(accepted.threadId()).orElseThrow(), ContextType.ORDER, "order-1");
    }

    @Test
    void sendWithMalformedContextIdIsRejected() {
        fixture();
        bind(ORG_A, "clinician-a");
        // Arrays.asList keeps the null case; List.of rejects a null element.
        for (String invalid : Arrays.asList("bad id!", null)) {
            ApiException refused = assertThrows(ApiException.class,
                    () -> service.send(freeText(ContextType.ORDER, invalid)));
            assertEquals(422, refused.status());
            assertEquals("INVALID_CONTEXTID", refused.code());
            assertEquals(List.of(), service.listForPatient(PATIENT));
        }
    }

    @Test
    void sendWithoutContextIsRejectedWhenTemplateRequiresOne() {
        fixture();
        bind(ORG_A, "admin-a");
        service.defineTemplate(new DefineTemplateRequest(UNIT, "collection-v1", Kind.COLLECTION,
                "Collection prep", "Please fast before 9am.", "en", false, ContextType.ORDER));

        bind(ORG_A, "clinician-a");
        ApiException refused = assertThrows(ApiException.class,
                () -> service.send(templated("collection-v1", null, null)));
        assertEquals(422, refused.status());
        assertEquals("CONTEXT_REQUIRED", refused.code());
        assertEquals(List.of(), service.listForPatient(PATIENT));
    }

    @Test
    void replacementKeepsTheOriginalContextBinding() {
        fixture();
        bind(ORG_A, "clinician-a");
        Revision first = service.send(freeText(ContextType.ORDER, "order-1"));

        Revision replacement = service.replace(first.threadId(), new ReplaceRequest(
                "Updated collection", "Please fast after midnight.", "en", null, List.of(),
                "site rescheduled the collection"), 1);
        assertBinding(replacement, ContextType.ORDER, "order-1");
        assertBinding(store.current(first.threadId()).orElseThrow(), ContextType.ORDER, "order-1");
        assertEquals(2, replacement.revision());
        assertEquals(Integer.valueOf(1), replacement.replacesRevision());
    }

    @Test
    void contextBindingIsTenantScopedForIdenticalLocalConnections() {
        fixture();
        bind(ORG_A, "clinician-a");
        Revision own = service.send(freeText(ContextType.ORDER, "order-a"));

        // ORG_B owns an identically named connection, patient and unit. Its
        // clinician must be seeded under ORG_B before it can send anything.
        bind(ORG_B, "clinician-b");
        store.seedMember("clinician-b", List.of("clinician"));
        store.seedUnitStaff("clinician-b", UNIT);
        store.seedConnectionOrg(ORG_B, CONNECTION, PATIENT, UNIT);
        Revision other = service.send(freeText(ContextType.ORDER, "order-b"));

        // Bound to ORG_B: only its own context binding is visible here.
        assertBinding(other, ContextType.ORDER, "order-b");
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get(own.threadId())).status());
        assertEquals(List.of(other.threadId()), service.listForPatient(PATIENT).stream()
                .map(revision -> revision.threadId()).toList());

        bind(ORG_A, "clinician-a");
        assertBinding(service.get(own.threadId()).current(), ContextType.ORDER, "order-a");
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get(other.threadId())).status());
        assertEquals(List.of(own.threadId()), service.listForPatient(PATIENT).stream()
                .map(revision -> revision.threadId()).toList());
    }
}
