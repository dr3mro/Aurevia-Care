package com.aurevia.messaging.instructions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.instructions.InstructionContract.ContextType;
import com.aurevia.messaging.instructions.InstructionContract.FactType;
import com.aurevia.messaging.instructions.InstructionContract.Kind;
import com.aurevia.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.messaging.instructions.InstructionContract.State;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.messaging.instructions.InstructionStore.InMemory;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-04 acceptance for the typed-instruction service using only synthetic
 * fixtures. Every command runs against a fresh tenant-keyed in-memory store,
 * so authorization, revision, withdrawal, notification and isolation outcomes
 * are observed without a database. The durable adapter and its SQL are the
 * integrator's concern; these are the fast unit tests.
 */
class InstructionServiceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONNECTION = "conn-lab-1";
    private static final String UNIT = "unit-lab";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private InMemory store;
    private InstructionService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void fixture() {
        store = new InMemory();
        service = new InstructionService(store, new InstructionActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        // Seeding is itself tenant-scoped: bind a context before storing fixtures.
        bind(ORG_A, "admin-a");
        store.seedMember("admin-a", List.of("tenant_admin"));
        store.seedMember("clinician-a", List.of("clinician"));
        store.seedMember("assistant-a", List.of("clinical_assistant"));
        store.seedMember("reception-a", List.of("reception"));
        store.seedUnitStaff("clinician-a", UNIT);
        store.seedUnitStaff("assistant-a", UNIT);
        store.seedConnection(CONNECTION, PATIENT, UNIT);
    }

    private void bind(String org, String subject) {
        TenantContext.set(new TenantContext(org, subject, "MSG04", "corr-1"));
    }

    private SendRequest sendRequest() {
        return new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.COLLECTION,
                "Morning collection", "Please fast before 9am, 5 mL sample.", "en",
                "2026-11-20T09:00:00Z", ContextType.ORDER, "order-1", List.of("doc-a"));
    }

    @Test
    void defineTemplateRequiresAdministrationAndRecordsApproval() {
        fixture();
        bind(ORG_A, "reception-a");
        ApiException denial = assertThrows(ApiException.class, () -> service.defineTemplate(
                new InstructionContract.DefineTemplateRequest(UNIT, "collection-v1", Kind.COLLECTION,
                        "Morning collection", "Please fast before 9am.", "en", false, ContextType.ORDER)));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());

        bind(ORG_A, "admin-a");
        Template template = service.defineTemplate(new InstructionContract.DefineTemplateRequest(
                UNIT, "collection-v1", Kind.COLLECTION, "Morning collection", "Please fast before 9am.",
                "en", false, ContextType.ORDER));
        assertEquals("collection-v1", template.id());
        assertEquals(UNIT, template.unitId());
        assertEquals(Kind.COLLECTION, template.kind());
        assertEquals("admin-a", template.approvedBy());
        assertEquals(NOW, template.approvedAt());
        assertEquals(1, template.version());
        assertFalse(template.clinical());
    }

    @Test
    void sendFromApprovedTemplateCreatesSentRevisionAndPatientFacts() {
        fixture();
        bind(ORG_A, "admin-a");
        service.defineTemplate(new InstructionContract.DefineTemplateRequest(UNIT, "collection-v1",
                Kind.COLLECTION, "Collection prep", "Please fast before 9am.", "ar", false,
                ContextType.ORDER));

        bind(ORG_A, "clinician-a");
        var sent = service.send(new SendRequest(PATIENT, CONNECTION, UNIT, "collection-v1", null,
                null, null, null, null, ContextType.ORDER, "order-1", List.of()));
        assertEquals(1, sent.revision());
        assertEquals(State.SENT, sent.state());
        assertEquals("collection-v1", sent.templateId());
        assertEquals(Kind.COLLECTION, sent.kind());
        assertEquals("Collection prep", sent.title());
        assertEquals("ar", sent.language());
        assertEquals("clinician-a", sent.authorSubject());
        assertEquals(NOW, sent.issuedAt());
        assertEquals(List.of(), sent.attachmentIds());

        List<InstructionContract.Fact> facts = store.facts(sent.threadId());
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.SENT
                && PATIENT.equals(fact.recipient())));
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.DISPATCHED));
        assertEquals(1, service.listForPatient(PATIENT).size());
    }

    @Test
    void sendFreeTextBindsTypedContentUnitsAttachmentsAndDueDate() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());
        assertEquals(Kind.COLLECTION, sent.kind());
        assertEquals("Morning collection", sent.title());
        assertEquals("Please fast before 9am, 5 mL sample.", sent.text());
        assertEquals("en", sent.language());
        assertEquals(ContextType.ORDER, sent.contextType());
        assertEquals("order-1", sent.contextId());
        assertEquals(List.of("doc-a"), sent.attachmentIds());
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), sent.actionDueAt());
    }

    @Test
    void freeTextRequiresClinicalScopeAndClinicalTemplateRequiresClinician() {
        fixture();
        bind(ORG_A, "assistant-a");
        ApiException denial = assertThrows(ApiException.class, () -> service.send(sendRequest()));
        assertEquals(403, denial.status());

        bind(ORG_A, "admin-a");
        service.defineTemplate(new InstructionContract.DefineTemplateRequest(UNIT, "clinical-v1",
                Kind.THERAPY_HOME, "Home exercises", "Clinical instruction body.", "en", true, null));

        bind(ORG_A, "assistant-a");
        ApiException clinical = assertThrows(ApiException.class, () -> service.send(new SendRequest(
                PATIENT, CONNECTION, UNIT, "clinical-v1", null, null, null, null, null, null, null,
                List.of())));
        assertEquals(403, clinical.status());
    }

    @Test
    void replacementSupersedesPriorRevisionAndNotifiesPatient() {
        fixture();
        bind(ORG_A, "clinician-a");
        var first = service.send(sendRequest());

        var second = service.replace(first.threadId(), new InstructionContract.ReplaceRequest(
                "Updated collection", "Please fast after midnight instead.", "en", null, List.of(),
                "site rescheduled the collection"), 1);
        assertEquals(2, second.revision());
        assertEquals(State.SENT, second.state());
        assertEquals(Integer.valueOf(1), second.replacesRevision());
        assertEquals("Updated collection", second.title());

        var prior = store.current(first.threadId()).flatMap(current -> store.history(first.threadId())
                .stream().filter(revision -> revision.revision() == 1).findFirst()).orElseThrow();
        assertEquals(State.SUPERSEDED, prior.state());
        assertEquals(Integer.valueOf(2), prior.supersededBy());

        assertEquals(2, store.history(first.threadId()).size());
        List<InstructionContract.Fact> facts = store.facts(first.threadId());
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.REPLACED));
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.SUPERSEDED));
        assertTrue(facts.stream().anyMatch(fact -> fact.type() == FactType.DISPATCHED
                && fact.revision() == 2));
        assertEquals(1, service.listForPatient(PATIENT).size());
        assertEquals(2, service.get(first.threadId()).history().size());
    }

    @Test
    void withdrawalMarksCurrentRevisionWithdrawnAndNotifiesPatient() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());
        var withdrawn = service.withdraw(sent.threadId(),
                new InstructionContract.WithdrawRequest("ordered in error"), 1);
        assertEquals(State.WITHDRAWN, withdrawn.state());
        assertEquals("ordered in error", withdrawn.withdrawalReason());
        assertTrue(store.facts(sent.threadId()).stream()
                .anyMatch(fact -> fact.type() == FactType.WITHDRAWN && fact.revision() == 1
                        && PATIENT.equals(fact.recipient())));
        assertEquals(State.WITHDRAWN, service.listForPatient(PATIENT).get(0).state());
    }

    @Test
    void staleVersionUnknownThreadAndIllegalTransitionFail() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());
        assertEquals(412, assertThrows(ApiException.class, () -> service.withdraw(sent.threadId(),
                new InstructionContract.WithdrawRequest("reason"), 2)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get("missing-thread")).status());

        service.withdraw(sent.threadId(), new InstructionContract.WithdrawRequest("reason"), 1);
        // Withdrawal keeps the same revision number, so version 1 is still current.
        ApiException transition = assertThrows(ApiException.class, () -> service.replace(sent.threadId(),
                new InstructionContract.ReplaceRequest("t", "x", "en", null, List.of(), "reason"), 1));
        assertEquals(409, transition.status());
        assertEquals("INVALID_TRANSITION", transition.code());
    }

    @Test
    void unitAssignmentAndConnectionAreValidatedOnEveryCommand() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());

        store.seedMember("stranger", List.of("clinician"));
        bind(ORG_A, "stranger");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.get(sent.threadId())).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(sendRequest())).status());

        store.seedMember("unassigned", List.of("clinician"));
        store.seedUnitStaff("unassigned", "another-unit");
        bind(ORG_A, "unassigned");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(PATIENT, CONNECTION, UNIT, null, Kind.GENERAL,
                        "title", "text", "en", null, null, null, List.of()))).status());

        bind(ORG_A, "clinician-a");
        store.seedConnection("inactive-connection", PATIENT, UNIT);
        store.deactivateConnection("inactive-connection");
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(PATIENT, "inactive-connection", UNIT, null,
                        Kind.GENERAL, "title", "text", "en", null, null, null, List.of()))).status());
    }

    @Test
    void replaceRequiresClinicianForNewFreeTextEvenWhenWithdrawIsAllowed() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());

        // A clinical assistant may withdraw (staffSender) but must not replace
        // content with arbitrary free text — MSG-04 clinical authorization.
        bind(ORG_A, "assistant-a");
        assertEquals(403, assertThrows(ApiException.class, () -> service.replace(sent.threadId(),
                new InstructionContract.ReplaceRequest("New", "Clinical free text", "en", null,
                        List.of(), "reason"), 1)).status());
        // Withdrawal itself remains available to the same non-clinical role.
        assertEquals(State.WITHDRAWN, service.withdraw(sent.threadId(),
                new InstructionContract.WithdrawRequest("cancelled"), 1).state());
    }

    @Test
    void existingThreadMutationRequiresUnitAssignmentAndActiveConnection() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());

        // Withdraw permission without unit assignment cannot touch the thread.
        store.seedMember("roaming", List.of("clinical_assistant"));
        bind(ORG_A, "roaming");
        assertEquals(403, assertThrows(ApiException.class, () -> service.withdraw(sent.threadId(),
                new InstructionContract.WithdrawRequest("reason"), 1)).status());

        // A deactivated connection blocks both rewrite and withdrawal (MSG-07).
        bind(ORG_A, "clinician-a");
        store.deactivateConnection(CONNECTION);
        assertEquals(403, assertThrows(ApiException.class, () -> service.replace(sent.threadId(),
                new InstructionContract.ReplaceRequest("t", "b", "en", null, List.of(), "reason"), 1)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.withdraw(sent.threadId(),
                new InstructionContract.WithdrawRequest("reason"), 1)).status());
    }

    @Test
    void historyIsAscendingAndCurrentIsTheHighestRevision() {
        fixture();
        bind(ORG_A, "clinician-a");
        var first = service.send(sendRequest());
        service.replace(first.threadId(), new InstructionContract.ReplaceRequest(
                "Updated", "New body", "en", null, List.of(), "reason"), 1);

        // The store and View contracts promise ascending revision order, and
        // the patient-side projection sorts on that same source of truth.
        assertEquals(List.of(1, 2), store.history(first.threadId()).stream()
                .map(InstructionContract.Revision::revision).toList());
        assertEquals(2, store.current(first.threadId()).orElseThrow().revision());
    }

    @Test
    void listForPatientConcealsThreadsFromUnassignedUnitsAndRevokedConnections() {
        fixture();
        bind(ORG_A, "clinician-a");
        service.send(sendRequest());

        // READ permission alone is not a care relationship. A privacy auditor
        // has INSTRUCTION_READ but is not assigned to the sending unit, so it
        // must not enumerate another unit's patient instructions (IAM-01, ARC-02).
        store.seedMember("auditor", List.of("privacy_auditor"));
        bind(ORG_A, "auditor");
        assertTrue(service.listForPatient(PATIENT).isEmpty());

        // Revoking the connection ends future access even for an assigned,
        // clinically authorized sender on the next protected read (MSG-07).
        store.deactivateConnection(CONNECTION);
        bind(ORG_A, "clinician-a");
        assertTrue(service.listForPatient(PATIENT).isEmpty());
    }

    @Test
    void clinicalGateUsesAnyActiveRoleAndRecordsAMatchingAuthorRole() {
        fixture();
        // The clinician subtag is not the first role in the membership fold.
        store.seedMember("multi-role", List.of("clinical_assistant", "clinician"));
        store.seedUnitStaff("multi-role", UNIT);
        bind(ORG_A, "multi-role");
        var sent = service.send(sendRequest());
        assertEquals("clinician", sent.authorRole());
        assertEquals(State.SENT, sent.state());

        // A non-clinician can still send an approved non-clinical template.
        bind(ORG_A, "admin-a");
        service.defineTemplate(new InstructionContract.DefineTemplateRequest(UNIT, "nonclinical-v1",
                Kind.COLLECTION, "Collection", "Body.", "en", false, ContextType.ORDER));
        bind(ORG_A, "assistant-a");
        var templated = service.send(new SendRequest(PATIENT, CONNECTION, UNIT, "nonclinical-v1", null,
                null, null, null, null, ContextType.ORDER, "order-1", List.of()));
        assertEquals("clinical_assistant", templated.authorRole());
    }

    @Test
    void missingContextAndCrossTenantLocalIdentifiersAreIsolated() {
        fixture();
        TenantContext.clear();
        assertEquals(403, assertThrows(ApiException.class, () -> service.send(sendRequest())).status());

        // Seeding is tenant-scoped; bind organization B before storing its fixtures.
        bind(ORG_B, "clinician-b");
        store.seedMember("admin-b", List.of("tenant_admin"));
        store.seedMember("clinician-b", List.of("clinician"));
        store.seedUnitStaff("clinician-b", UNIT);
        // The same local connection and patient identifiers exist in both tenants.
        store.seedConnectionOrg(ORG_B, CONNECTION, PATIENT, UNIT);

        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());
        assertNotNull(sent.threadId());

        bind(ORG_B, "clinician-b");
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(sent.threadId())).status());
        assertTrue(service.listForPatient(PATIENT).isEmpty());

        // Approving a template in one tenant does not expose it to the other.
        bind(ORG_A, "admin-a");
        service.defineTemplate(new InstructionContract.DefineTemplateRequest(UNIT, "shared-key",
                Kind.GENERAL, "title", "body", "en", false, null));
        bind(ORG_B, "clinician-b");
        assertEquals(404, assertThrows(ApiException.class, () -> service.send(new SendRequest(
                PATIENT, CONNECTION, UNIT, "shared-key", null, null, null, null, null, null, null,
                List.of()))).status());
        assertNull(store.findTemplateOrg(ORG_B, "shared-key").orElse(null));
    }

    @Test
    void guardsForTemplatesKindsMembershipAndStoreFailClosed() {
        fixture();
        final String otherPatient = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        assertFalse(store.connectionActive("unknown-connection", PATIENT, UNIT));
        assertFalse(store.connectionActive(CONNECTION, otherPatient, UNIT));
        assertFalse(store.connectionActive(CONNECTION, PATIENT, "other-unit"));
        store.deactivateConnection("unknown-connection");

        bind(ORG_A, "admin-a");
        service.defineTemplate(new InstructionContract.DefineTemplateRequest(UNIT, "clinical-v2",
                Kind.THERAPY_HOME, "Home plan", "Clinical body.", "en", true, null));
        service.defineTemplate(new InstructionContract.DefineTemplateRequest(UNIT, "order-v1",
                Kind.COLLECTION, "Collection", "Body.", "en", false, ContextType.ORDER));

        bind(ORG_A, "clinician-a");
        var clinical = service.send(new SendRequest(PATIENT, CONNECTION, UNIT, "clinical-v2", null,
                null, null, null, null, null, null, List.of()));
        assertEquals(Kind.THERAPY_HOME, clinical.kind());
        assertEquals(422, assertThrows(ApiException.class, () -> service.send(new SendRequest(
                PATIENT, CONNECTION, UNIT, "order-v1", null, null, null, null, null, null, null,
                List.of()))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.send(new SendRequest(
                PATIENT, CONNECTION, "other-unit", "order-v1", null, null, null, null, null,
                ContextType.ORDER, "order-1", List.of()))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.send(new SendRequest(
                PATIENT, CONNECTION, UNIT, null, null, "title", "body", "en", null, null, null,
                List.of()))).status());

        store.seedMember("ghost", List.of());
        bind(ORG_A, "ghost");
        assertEquals(403, assertThrows(ApiException.class, () -> service.send(sendRequest())).status());

        assertEquals(404, assertThrows(ApiException.class,
                () -> store.supersede("unknown-thread", 1, 2)).status());
        TenantContext.clear();
        assertEquals(403, assertThrows(ApiException.class, () -> store.history("x")).status());
        TenantContext.set(new TenantContext(null, "clinician-a", "MSG04", "corr-1"));
        assertEquals(403, assertThrows(ApiException.class, () -> store.history("x")).status());
    }

    @Test
    void patientReadsOwnInstructionContentAndOwnThread() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());

        // The patient account is linked to the verified chart (IAM-05) and the
        // connection is active, so a patient subject may read its own content.
        store.seedMember("patient-a", List.of("patient"));
        store.linkPatientChart("patient-a", PATIENT);
        bind(ORG_A, "patient-a");

        var view = service.get(sent.threadId());
        assertEquals(sent.threadId(), view.current().threadId());
        assertEquals("Morning collection", view.current().title());
        assertEquals("Please fast before 9am, 5 mL sample.", view.current().text());
        assertEquals(List.of(sent.threadId()), service.listForPatient(PATIENT).stream()
                .map(InstructionContract.Revision::threadId).toList());
    }

    @Test
    void patientCannotReadAnotherPatientsInstructionContent() {
        fixture();
        bind(ORG_A, "clinician-a");
        var own = service.send(sendRequest());

        String otherPatient = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        store.seedConnection("other-conn", otherPatient, UNIT);
        var other = service.send(new SendRequest(otherPatient, "other-conn", UNIT, null,
                Kind.GENERAL, "Other title", "Other body", "en", null, null, null, List.of()));

        store.seedMember("patient-a", List.of("patient"));
        // Linked only to PATIENT, never to otherPatient.
        store.linkPatientChart("patient-a", PATIENT);
        bind(ORG_A, "patient-a");

        // Own content remains readable, but a thread on a chart the account is
        // not linked to is concealed and another chart's list is denied (404).
        assertEquals(own.threadId(), service.get(own.threadId()).current().threadId());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get(other.threadId())).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.listForPatient(otherPatient)).status());
    }

    @Test
    void patientReadRequiresAnActiveVerifiedConnection() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());

        store.seedMember("patient-a", List.of("patient"));
        store.linkPatientChart("patient-a", PATIENT);
        bind(ORG_A, "patient-a");

        store.deactivateConnection(CONNECTION);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.get(sent.threadId())).status());
        assertTrue(service.listForPatient(PATIENT).isEmpty());
    }

    @Test
    void multiRolePatientCallerStillUsesTheStricterPatientBranch() {
        fixture();
        bind(ORG_A, "clinician-a");
        var own = service.send(sendRequest());
        String otherPatient = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        store.seedConnection("other-conn", otherPatient, UNIT);
        var other = service.send(new SendRequest(otherPatient, "other-conn", UNIT, null,
                Kind.GENERAL, "Other title", "Other body", "en", null, null, null, List.of()));

        // A subject holding both roles is unit-assigned, so the staff branch
        // would let it read any thread in the unit. The patient role wins and
        // forces verified-chart binding: only PATIENT is linked to this account.
        store.seedMember("dual-a", List.of("clinician", "patient"));
        store.seedUnitStaff("dual-a", UNIT);
        store.linkPatientChart("dual-a", PATIENT);
        bind(ORG_A, "dual-a");

        assertEquals(own.threadId(), service.get(own.threadId()).current().threadId());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get(other.threadId())).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.listForPatient(otherPatient)).status());
    }

    @Test
    void patientAccountLinkedInOneTenantCannotReadTheSameLocalChartInAnother() {
        fixture();
        bind(ORG_A, "clinician-a");
        var sent = service.send(sendRequest());

        // Identical local subject, chart and connection identifiers in tenant B:
        // the chart is verified-linked there too, but the thread belongs to A.
        bind(ORG_B, "patient-b");
        store.seedMember("patient-b", List.of("patient"));
        store.linkPatientChart("patient-b", PATIENT);
        store.seedConnectionOrg(ORG_B, CONNECTION, PATIENT, UNIT);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.get(sent.threadId())).status());
        assertTrue(service.listForPatient(PATIENT).isEmpty());
    }
}
