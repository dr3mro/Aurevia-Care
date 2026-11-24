package com.aurevia.smartclinic.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.orderentry.place.DeliveryMode;
import com.aurevia.smartclinic.orderentry.place.OrderPlacementService;
import com.aurevia.smartclinic.orderentry.place.OrderSelection;
import com.aurevia.smartclinic.orderentry.place.PlaceOrderController;
import com.aurevia.smartclinic.orderentry.place.PlacedOrderRequest;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * ARC-03 clause 7 proof: a single idempotency key cannot carry two different
 * payloads through the order-placement HTTP entry point.
 *
 * <p>The real {@link com.aurevia.smartclinic.orderentry.place.PlaceOrderController}
 * and its domain service are driven through the Spring MVC pipeline with the
 * synthetic-auth profile, so an unbound header or a conflict that exists only in
 * the domain kernel cannot pass. Two distinct drafts carry distinct payloads;
 * placing the first with key K succeeds, and placing the second with the same K
 * must answer 409 IDEMPOTENCY_CONFLICT without consuming the second draft or
 * creating a second order. Every subject, identifier and payload is synthetic.</p>
 */
class DifferentPayloadsUsingSameKeyYieldConflictTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "clinician-clause";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private StaffRepository staff;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the real controller and placement kernel behind the normal HTTP pipeline. */
    @BeforeEach
    void setUp() {
        OrderPlacementService service = new OrderPlacementService(Clock.fixed(AT, ZoneOffset.UTC));
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new PlaceOrderController(service, staff, transactions))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void differentPayloadsUnderOneKeyConflict() throws Exception {
        // The two drafts differ in catalog item, clinical question and delivery mode.
        String draftA = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);
        String draftB = saveDraft("item-ferritin", "check iron stores", DeliveryMode.PRINT, ORG);
        String first = place(draftA, "1", "shared-key", ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.clinicalQuestion").value("rule out anaemia"))
                .andExpect(jsonPath("$.request.deliveryMode").value("ELECTRONIC"))
                .andReturn().getResponse().getContentAsString();
        String orderA = mapper.readTree(first).get("id").asString();

        // A different draft carrying different content must not replay order A.
        place(draftB, "1", "shared-key", ORG)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // The refusal left the first order intact, did not consume draft B and
        // did not claim the key for a second order. Replaying the same key still
        // returns the first payload, not draft B's payload.
        assertOrderVisible(orderA, ORG);
        String replay = place(draftA, "1", "shared-key", ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.clinicalQuestion").value("rule out anaemia"))
                .andExpect(jsonPath("$.request.deliveryMode").value("ELECTRONIC"))
                .andReturn().getResponse().getContentAsString();
        assertEquals(orderA, mapper.readTree(replay).get("id").asString());
        String orderB = placeAndReturnOrderId(draftB, "1", "fresh-key", ORG);
        assertNotEquals(orderA, orderB);
        assertOrderVisible(orderB, ORG);
    }

    @Test
    void sameKeyAndPayloadReplayTheFirstOrder() throws Exception {
        String draftId = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);
        String first = placeAndReturnOrderId(draftId, "1", "replay-key", ORG);

        String retry = placeAndReturnOrderId(draftId, "1", "replay-key", ORG);

        assertEquals(first, retry);
        assertOrderVisible(first, ORG);
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        String draftId = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);

        mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        // The refusal consumed no claim, so the same draft can still be placed.
        assertOrderVisible(placeAndReturnOrderId(draftId, "1", "after-missing", ORG), ORG);
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        String draftId = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);

        place(draftId, "1", "bad key!", ORG)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertOrderVisible(placeAndReturnOrderId(draftId, "1", "after-malformed", ORG), ORG);
    }

    @Test
    void aClinicianWithoutOrderingRoleCannotPlace() throws Exception {
        String draftId = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("reception"));

        place(draftId, "1", "wrong-role-key", ORG)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Restoring the role and reusing the SAME key still places the draft,
        // so the forbidden attempt neither consumed the draft nor claimed the key.
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        assertOrderVisible(placeAndReturnOrderId(draftId, "1", "wrong-role-key", ORG), ORG);
    }

    @Test
    void aPeerTenantCannotReuseTheKey() throws Exception {
        String draftA = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);
        String orderA = placeAndReturnOrderId(draftA, "1", "tenant-key", ORG);
        String peerDraft = saveDraft("item-ferritin", "check iron stores", DeliveryMode.PRINT, OTHER_ORG);

        // The key is scoped to the active tenant, so the peer tenant's own draft
        // gets a distinct order rather than replaying or conflicting with A.
        String peerOrder = placeAndReturnOrderId(peerDraft, "1", "tenant-key", OTHER_ORG);
        assertNotEquals(orderA, peerOrder);
        assertOrderVisible(orderA, ORG);
        assertOrderVisible(peerOrder, OTHER_ORG);

        // The foreign order is concealed from the tenant that owns the key.
        mvc.perform(get("/api/v1/order-entry/orders/{id}", orderA)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void staleVersionOnFirstPlacementIsRefused() throws Exception {
        String draftId = saveDraft("item-cbc", "rule out anaemia", DeliveryMode.ELECTRONIC, ORG);

        place(draftId, "2", "first-placement-key", ORG)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // Correcting the version with the SAME key succeeds, proving the stale
        // refusal made no idempotency claim.
        assertOrderVisible(placeAndReturnOrderId(draftId, "1", "first-placement-key", ORG), ORG);
    }

    private String saveDraft(String itemId, String question, DeliveryMode mode, String org)
            throws Exception {
        PlacedOrderRequest request = new PlacedOrderRequest("patient-clause", "encounter-clause",
                "facility-clause", List.of(new OrderSelection(itemId, 2)), question, "urgent",
                "fast", "partner-clause", mode);
        String location = mvc.perform(post("/api/v1/order-entry/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", org))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private ResultActions place(String draftId, String version, String key, String org) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/order-entry/drafts/{id}/place", draftId)
                .header("If-Match", version)
                .header("Idempotency-Key", key)
                .header("X-Synthetic-Subject", SUBJECT)
                .header("X-Active-Organization", org);
        return mvc.perform(request);
    }

    private String placeAndReturnOrderId(String draftId, String version, String key, String org)
            throws Exception {
        String body = place(draftId, version, key, org)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    private void assertOrderVisible(String orderId, String org) throws Exception {
        mvc.perform(get("/api/v1/order-entry/orders/{id}", orderId)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", org))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));
    }
}
