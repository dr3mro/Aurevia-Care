package com.aurevia.messaging.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.DefineTemplateRequest;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.ReplaceRequest;
import com.aurevia.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionContract.WithdrawRequest;
import com.aurevia.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.tenant.TenantContext;

/**
 * HTTP adapter acceptance for MSG-04: every route is exercised through its
 * normal entry point with a synthetic tenant context and deterministic
 * content. Version preconditions are parsed at this boundary.
 */
class InstructionControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private InstructionController controller(InMemory store) {
        return new InstructionController(new InstructionService(store, new InstructionActions(),
                java.time.Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), java.time.ZoneOffset.UTC)));
    }

    @Test
    void everyRoutePublishesReadsReplacesAndWithdraws() {
        InMemory store = new InMemory();
        InstructionController controller = controller(store);
        TenantContext.set(new TenantContext(ORG, "admin-a", "MSG04", "corr-1"));
        store.seedMember("admin-a", List.of("tenant_admin"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);

        var template = controller.defineTemplate(new DefineTemplateRequest(UNIT, "collection-v1",
                Kind.COLLECTION, "Collection", "Body.", "en", false, ContextType.ORDER));
        assertEquals(HttpStatus.CREATED, template.getStatusCode());

        TenantContext.set(new TenantContext(ORG, "clinician-a", "MSG04", "corr-1"));
        var created = controller.send(new SendRequest(PATIENT, CONNECTION, UNIT, "collection-v1", null,
                null, null, null, null, ContextType.ORDER, "order-1", List.of()));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String threadId = created.getBody().threadId();

        var replaced = controller.replace(threadId, new ReplaceRequest("Updated", "New body", "ar",
                null, List.of(), "changed by site"), "\"1\"");
        assertEquals(2, replaced.revision());
        assertEquals(2, controller.get(threadId).history().size());
        assertEquals(1, controller.listForPatient(PATIENT).size());

        var withdrawn = controller.withdraw(threadId, new WithdrawRequest("cancelled"), "W/\"2\"");
        assertEquals(State.WITHDRAWN, withdrawn.state());
        assertEquals(1, controller.listForPatient(PATIENT).size());
    }

    @Test
    void irreversibleRoutesRequireAVersionPrecondition() {
        InMemory store = new InMemory();
        InstructionController controller = controller(store);
        TenantContext.set(new TenantContext(ORG, "clinician-a", "MSG04", "corr-1"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
        String threadId = controller.send(new SendRequest(PATIENT, CONNECTION, UNIT, null,
                Kind.GENERAL, "Title", "Body", "en", null, null, null, List.of())).getBody().threadId();

        ApiException missing = assertThrows(ApiException.class, () -> controller.withdraw(threadId,
                new WithdrawRequest("reason"), null));
        assertEquals(428, missing.status());
        assertEquals("PRECONDITION_REQUIRED", missing.code());
        assertEquals(422, assertThrows(ApiException.class, () -> controller.replace(threadId,
                new ReplaceRequest("t", "b", "en", null, List.of(), "reason"), "bogus")).status());
    }

    @Test
    void patientReadsOwnInstructionContentThroughHttpRoutes() {
        InMemory store = new InMemory();
        InstructionController controller = controller(store);
        TenantContext.set(new TenantContext(ORG, "clinician-a", "MSG04", "corr-1"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
        String threadId = controller.send(new SendRequest(PATIENT, CONNECTION, UNIT, null,
                Kind.GENERAL, "Pickup instructions", "Collect after 2pm.", "en", null, null, null,
                List.of())).getBody().threadId();

        store.seedMember("patient-a", List.of("patient"));
        store.linkPatientChart("patient-a", PATIENT);
        TenantContext.set(new TenantContext(ORG, "patient-a", "MSG04", "corr-1"));

        assertEquals("Pickup instructions", controller.get(threadId).current().title());
        assertEquals(1, controller.listForPatient(PATIENT).size());
    }
}
