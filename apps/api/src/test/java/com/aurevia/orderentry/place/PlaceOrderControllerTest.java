package com.aurevia.orderentry.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** HTTP entry point: draft, edit and place with If-Match and Idempotency-Key through normal routes. */
class PlaceOrderControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "clinician-http";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private OrderPlacementService service;
    private PlaceOrderController controller;
    private StaffRepository staff;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementService(Clock.fixed(AT, ZoneOffset.UTC));
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new PlaceOrderController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String requestBody(DeliveryMode mode) throws Exception {
        return mapper.writeValueAsString(new PlacedOrderRequest("patient-http", "encounter-http",
                "facility-http", List.of(new OrderSelection("item-cbc", 2)), "rule out anaemia",
                "urgent", "fast", "partner-http", mode));
    }

    private String saveDraft(String mode, String org) throws Exception {
        String location = mvc.perform(post("/api/v1/order-entry/drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(DeliveryMode.valueOf(mode)))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", org))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    void saves_edits_and_places_a_draft_over_http() throws Exception {
        String draftId = saveDraft("BOTH", ORG);

        mvc.perform(put("/api/v1/order-entry/drafts/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody(DeliveryMode.PRINT))
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.request.deliveryMode").value("PRINT"));

        String placed = mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("If-Match", "2")
                        .header("Idempotency-Key", "http-place-key")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.deliveryMode").value("PRINT"))
                .andExpect(jsonPath("$.placedBy").value(SUBJECT))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        String orderId = mapper.readTree(placed).get("id").asString();

        mvc.perform(get("/api/v1/order-entry/orders/{id}", orderId)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId));
    }

    @Test
    void places_every_delivery_mode_over_http() throws Exception {
        for (DeliveryMode mode : DeliveryMode.values()) {
            String draftId = saveDraft(mode.name(), ORG);
            mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                            .header("If-Match", "1")
                            .header("Idempotency-Key", "http-mode-" + mode.name())
                            .header("X-Synthetic-Subject", SUBJECT)
                            .header("X-Active-Organization", ORG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.request.deliveryMode").value(mode.name()));
        }
    }

    @Test
    void a_retried_placement_over_http_creates_one_order() throws Exception {
        String draftId = saveDraft("ELECTRONIC", ORG);
        String first = mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("If-Match", "1").header("Idempotency-Key", "http-retry")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("If-Match", "1").header("Idempotency-Key", "http-retry")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(first).get("id").asString(),
                mapper.readTree(retry).get("id").asString());
    }

    @Test
    void a_wrong_role_is_forbidden_and_a_foreign_tenant_is_concealed() throws Exception {
        String draftId = saveDraft("BOTH", ORG);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
        mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("If-Match", "1").header("Idempotency-Key", "role-key")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden());

        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        PlacedOrder placed = service.place(ORG, SUBJECT, Set.of("clinician"), draftId, 1,
                com.aurevia.api.contract.IdempotencyKey.parse("tenant-key"));
        mvc.perform(get("/api/v1/order-entry/orders/{id}", placed.id())
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound());
    }

    @Test
    void placement_requires_both_preconditions() throws Exception {
        String draftId = saveDraft("BOTH", ORG);
        mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("Idempotency-Key", "no-if-match")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post("/api/v1/order-entry/drafts/{id}/place", draftId)
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionRequired());
    }

    @Test
    void a_request_without_context_is_unauthenticated() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, () -> controller.get("any")).status());
    }
}
