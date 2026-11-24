package com.aurevia.pharmacyflow.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.status.PharmacyStatusController.DispenseRequest;
import com.aurevia.pharmacyflow.status.PharmacyStatusController.RejectRequest;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RX-02 HTTP entry point: all four status facts, preconditions and retry dedup. */
class PharmacyStatusControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "pharm-tech-http";
    private static final String PRESCRIPTION = "rx-http";
    private static final String IF_MATCH = "1";
    private static final String KEY = "idem-http-1";

    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        PharmacyStatusService service = new PharmacyStatusService(
                Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC));
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(any())).thenReturn(List.of(SpecialtyRoles.PHARMACY_STAFF));
        PharmacyStatusController controller = new PharmacyStatusController(service, staff);
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

    @Test
    void acceptThroughHttpAppendsFact() throws Exception {
        postFact("/accept", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("pharmacy_accepted"))
                .andExpect(jsonPath("$.actorId").value(SUBJECT))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get("/api/v1/pharmacy/status/{id}", PRESCRIPTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void rejectThroughHttpRequiresReason() throws Exception {
        String body = mapper.writeValueAsString(new RejectRequest("   "));
        mvc.perform(post("/api/v1/pharmacy/status/{id}/reject", PRESCRIPTION)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("If-Match", IF_MATCH)
                        .header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
    }

    @Test
    void rejectWithReasonThroughHttpAppendsFact() throws Exception {
        String body = mapper.writeValueAsString(new RejectRequest("incorrect strength"));
        mvc.perform(post("/api/v1/pharmacy/status/{id}/reject", PRESCRIPTION)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("If-Match", IF_MATCH).header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("pharmacy_rejected"))
                .andExpect(jsonPath("$.detail").value("incorrect strength"));
    }

    @Test
    void clarificationThroughHttpAppendsFact() throws Exception {
        String body = mapper.writeValueAsString(new PharmacyStatusController.ClarificationRequest("confirm dose"));
        mvc.perform(post("/api/v1/pharmacy/status/{id}/clarification", PRESCRIPTION)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("If-Match", IF_MATCH)
                        .header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("clarification"))
                .andExpect(jsonPath("$.detail").value("confirm dose"));
    }

    @Test
    void dispensingEvidenceThroughHttpNeverMarksAccepted() throws Exception {
        String body = mapper.writeValueAsString(new DispenseRequest("pharmacist-ali",
                "2026-11-21T10:05:00Z", "60 tablets"));
        mvc.perform(post("/api/v1/pharmacy/status/{id}/dispensing-evidence", PRESCRIPTION)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("If-Match", IF_MATCH)
                        .header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("dispensing_evidence"))
                .andExpect(jsonPath("$.dispensedBy").value("pharmacist-ali"))
                .andExpect(jsonPath("$.handedOver").value("60 tablets"));

        mvc.perform(get("/api/v1/pharmacy/status/{id}", PRESCRIPTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.dispensed").value(true));
    }

    @Test
    void invalidDispensingTimeIsRejected() throws Exception {
        String bad = mapper.writeValueAsString(new DispenseRequest("pharmacist-ali", "not-a-time", "item"));
        mvc.perform(post("/api/v1/pharmacy/status/{id}/dispensing-evidence", PRESCRIPTION)
                        .contentType(MediaType.APPLICATION_JSON).content(bad)
                        .header("If-Match", IF_MATCH).header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DISPENSED_AT"));

        String missing = mapper.writeValueAsString(new DispenseRequest("pharmacist-ali", null, "item"));
        mvc.perform(post("/api/v1/pharmacy/status/{id}/dispensing-evidence", PRESCRIPTION)
                        .contentType(MediaType.APPLICATION_JSON).content(missing)
                        .header("If-Match", IF_MATCH).header("Idempotency-Key", "idem-http-2")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_DISPENSED_AT"));
    }

    @Test
    void preconditionsAreRequired() throws Exception {
        mvc.perform(post("/api/v1/pharmacy/status/{id}/accept", PRESCRIPTION)
                        .header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mvc.perform(post("/api/v1/pharmacy/status/{id}/accept", PRESCRIPTION)
                        .header("If-Match", IF_MATCH)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void malformedIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/pharmacy/status/{id}/accept", PRESCRIPTION)
                        .header("If-Match", IF_MATCH).header("Idempotency-Key", "bad key")
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void retriedActionThroughHttpRecordsOnce() throws Exception {
        postFact("/accept", null).andExpect(status().isOk());
        postFact("/accept", null).andExpect(status().isOk());

        mvc.perform(get("/api/v1/pharmacy/status/{id}/events", PRESCRIPTION)
                        .header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    private org.springframework.test.web.servlet.ResultActions postFact(String path, String body)
            throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                post("/api/v1/pharmacy/status/{id}" + path, PRESCRIPTION)
                        .header("If-Match", IF_MATCH)
                        .header("Idempotency-Key", KEY)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }
}
