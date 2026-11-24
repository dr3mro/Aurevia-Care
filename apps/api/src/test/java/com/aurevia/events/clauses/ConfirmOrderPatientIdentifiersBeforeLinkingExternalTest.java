package com.aurevia.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.events.DeliveryActions;
import com.aurevia.events.DeliveryController;
import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.ExternalEvent;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.LinkRequest;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.DurableEventService;
import com.aurevia.events.InMemoryDeliveryStore;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * ARC-05 clause evidence: external data is linked only after order and patient
 * identifiers are confirmed against an accepted receipt in the active tenant.
 *
 * <p>Every case uses synthetic identifiers, a fixed clock and the shared in-memory
 * delivery store. No production code, contract or migration is changed by this
 * class. Alongside the service-level matrix it proves the thin controller entry
 * point and the {@code POST /api/v1/delivery/external-links} route through
 * MockMvc. Evidence is from executed tests, not a clinical or human reviewer.</p>
 */
class ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ORDER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_ORDER = "abababab-abab-abab-abab-abababababab";
    private static final String OTHER_PATIENT = "cdcdcdcd-cdcd-cdcd-cdcd-cdcdcdcdcdcd";
    private static final String MISSING = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String GHOST_ORDER = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String GHOST_PATIENT = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String LINKS_ROUTE = "/api/v1/delivery/external-links";

    private InMemoryDeliveryStore store;
    private DurableEventService service;
    private DeliveryController controller;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        store = new InMemoryDeliveryStore();
        store.addMember(ORG, "worker", "worker", true);
        store.addMember(ORG, "clinician", "clinician", true);
        store.addMember(ORG, "admin", "integration_admin", true);
        // A subject authorized to link is required in the second tenant; reusing
        // a worker would fail with 403 instead of exercising tenant concealment.
        store.addMember(OTHER_ORG, "clinician", "clinician", true);
        store.addOrder(ORG, ORDER, PATIENT);
        store.addPatient(ORG, PATIENT);
        // A second existing pair lets the mismatch tests isolate identifier
        // equality: the supplied ids exist, so only the equality guard can fail.
        store.addOrder(ORG, OTHER_ORDER, OTHER_PATIENT);
        store.addPatient(ORG, OTHER_PATIENT);
        service = new DurableEventService(store, new DeliveryActions(), Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new DeliveryController(service);
        mapper = new ObjectMapper();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        as("worker", ORG);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void as(String subject, String organizationId) {
        TenantContext.set(new TenantContext(organizationId, subject, "DELIVERY", "corr-1"));
    }

    private ExternalEvent inbound(String externalId, String order, String patient) {
        return new ExternalEvent(externalId, "lab-partner", "observation.result", "partner-lab",
                AGGREGATE, 1, order, patient, DIGEST);
    }

    private ConsumerReceipt acceptedReceipt(String organizationId, String externalId,
                                            String order, String patient) {
        return new ConsumerReceipt("receipt-" + externalId, organizationId, externalId, "lab-partner",
                "observation.result", "partner-lab", AGGREGATE, 1, order, patient,
                ProcessingOutcome.ACCEPTED, null, NOW, "worker");
    }

    private LinkRequest link(String externalId, String order, String patient) {
        return new LinkRequest(externalId, order, patient, AGGREGATE);
    }

    private DeliveryAuditEvent lastAudit(String organizationId) {
        List<DeliveryAuditEvent> events = store.auditEvents(organizationId);
        return events.get(events.size() - 1);
    }

    @Test
    void clinicianLinksExternalDataAfterAnAcceptedReceiptConfirmsBothIdentifiers() {
        service.consume(inbound("ext-accepted", ORDER, PATIENT));
        as("clinician", ORG);

        ExternalLink link = service.linkExternal(link("ext-accepted", ORDER, PATIENT));

        assertNotNull(link.linkId());
        assertEquals(ORG, link.organizationId());
        assertEquals("ext-accepted", link.externalEventId());
        assertEquals(ORDER, link.orderId());
        assertEquals(PATIENT, link.patientId());
        assertEquals(AGGREGATE, link.targetAggregateId());
        assertEquals("clinician", link.linkedBy());
        assertEquals(NOW, link.linkedAt());
        assertEquals(AuditAction.LINK_EXTERNAL, lastAudit(ORG).action());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
        assertEquals("corr-1", lastAudit(ORG).correlationId());
    }

    @Test
    void integrationAdminMayAlsoLinkAfterConfirmation() {
        service.consume(inbound("ext-admin", ORDER, PATIENT));
        as("admin", ORG);

        ExternalLink link = service.linkExternal(link("ext-admin", ORDER, PATIENT));

        assertEquals("admin", link.linkedBy());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
    }

    @Test
    void controllerLinkExternalBindsTheCommandAndEnforcesTheSameConfirmation() {
        service.consume(inbound("ext-controller", ORDER, PATIENT));
        as("clinician", ORG);

        ExternalLink link = controller.linkExternal(link("ext-controller", ORDER, PATIENT));

        assertEquals("ext-controller", link.externalEventId());
        assertEquals(ORDER, link.orderId());
        assertEquals(PATIENT, link.patientId());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
        as("worker", ORG);
        assertEquals(403, assertThrows(ApiException.class,
                () -> controller.linkExternal(link("ext-controller", ORDER, PATIENT))).status());
    }

    @Test
    void externalLinksRouteLinksOnlyAnAuthorizedClinicianAfterConfirmation() throws Exception {
        service.consume(inbound("ext-http", ORDER, PATIENT));

        String response = mvc.perform(post(LINKS_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Synthetic-Subject", "clinician")
                        .header("X-Active-Organization", ORG)
                        .header("X-Correlation-Id", "corr-http")
                        .content(mapper.writeValueAsString(link("ext-http", ORDER, PATIENT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalEventId").value("ext-http"))
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.linkedBy").value("clinician"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(mapper.readTree(response).get("linkId"));
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());

        mvc.perform(post(LINKS_ROUTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Synthetic-Subject", "worker")
                        .header("X-Active-Organization", ORG)
                        .content(mapper.writeValueAsString(link("ext-http", ORDER, PATIENT))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
    }

    @Test
    void workerIsDeniedBeforeAnyReceiptLookup() {
        service.consume(inbound("ext-role", ORDER, PATIENT));
        as("worker", ORG);

        ApiException denied = assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-role", ORDER, PATIENT)));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
        // Authorization runs before the receipt lookup, so even a missing event
        // must be refused as forbidden rather than leaking a 404 to the caller.
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-absent", ORDER, PATIENT))).status());
    }

    @Test
    void linkExternalConcealsAnUnprocessedExternalEvent() {
        as("clinician", ORG);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-unprocessed", ORDER, PATIENT)));
        assertEquals(404, failure.status());
        assertEquals("NOT_FOUND", failure.code());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }

    @Test
    void linkExternalRejectsAnOrderIdThatDiffersFromTheReceipt() {
        service.consume(inbound("ext-order-mismatch", ORDER, PATIENT));
        as("clinician", ORG);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-order-mismatch", OTHER_ORDER, PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }

    @Test
    void linkExternalRejectsAPatientIdThatDiffersFromTheReceipt() {
        service.consume(inbound("ext-patient-mismatch", ORDER, PATIENT));
        as("clinician", ORG);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-patient-mismatch", ORDER, OTHER_PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }

    @Test
    void linkExternalRejectsAReceiptWithRejectedOutcome() {
        assertEquals(ProcessingOutcome.REJECTED,
                service.consume(inbound("ext-rejected", MISSING, PATIENT)).outcome());
        // A rejected receipt whose identifiers all exist: the outcome guard is
        // then the only conjunct that can refuse the link.
        store.putReceipt(ORG, new ConsumerReceipt("receipt-rejected-valid", ORG, "ext-rejected-valid",
                "lab-partner", "observation.result", "partner-lab", AGGREGATE, 1, ORDER, PATIENT,
                ProcessingOutcome.REJECTED, "order_not_found", NOW, "worker"));
        as("clinician", ORG);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-rejected", MISSING, PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-rejected-valid", ORDER, PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }

    @Test
    void linkExternalConcealsAReceiptOwnedByAnotherTenant() {
        service.consume(inbound("ext-org-a", ORDER, PATIENT));
        store.putReceipt(OTHER_ORG, acceptedReceipt(OTHER_ORG, "ext-org-b", ORDER, PATIENT));

        as("clinician", OTHER_ORG);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-org-a", ORDER, PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, store.auditEvents(OTHER_ORG).get(0).outcome());
        assertTrue(store.auditEvents(ORG).stream()
                .noneMatch(audit -> audit.action() == AuditAction.LINK_EXTERNAL));

        as("clinician", ORG);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-org-b", ORDER, PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }

    @Test
    void linkExternalRejectsReceiptWhenTheConfirmedOrderNoLongerExists() {
        store.putReceipt(ORG, acceptedReceipt(ORG, "ext-ghost-order", GHOST_ORDER, PATIENT));
        as("clinician", ORG);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-ghost-order", GHOST_ORDER, PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }

    @Test
    void linkExternalRejectsReceiptWhenTheConfirmedPatientNoLongerExists() {
        store.putReceipt(ORG, acceptedReceipt(ORG, "ext-ghost-patient", ORDER, GHOST_PATIENT));
        as("clinician", ORG);

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.linkExternal(link("ext-ghost-patient", ORDER, GHOST_PATIENT))).status());
        assertEquals(Outcome.NOT_FOUND, lastAudit(ORG).outcome());
    }
}
