package com.aurevia.therapy;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;
import com.aurevia.therapy.TherapyContract.ReferralRequest;

import tools.jackson.databind.ObjectMapper;

/** PT-01 HTTP entry point: referral acceptance and episode read are reachable through protected routes. */
class TherapyControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "pt-clinician-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String REFERRAL = "referral-http-1";

    private TherapyController controller;
    private StaffRepository staff;
    private MockMvc mvc;
    private String referralBody;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        TherapyService service = new TherapyService(new InMemoryTherapyStore(), clock);
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("pt_clinician"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new TherapyController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        referralBody = new ObjectMapper().writeValueAsString(
                new ReferralRequest(REFERRAL, PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void acceptsReferralAndReadsEpisodeThroughHttp() throws Exception {
        String response = mvc.perform(post("/api/v1/therapy/episodes")
                        .contentType(MediaType.APPLICATION_JSON).content(referralBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referralId").value(REFERRAL))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String episodeId = new ObjectMapper().readTree(response).get("id").asString();
        mvc.perform(get("/api/v1/therapy/episodes/{id}", episodeId)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episode.patientId").value(PATIENT));
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/therapy/episodes")
                        .contentType(MediaType.APPLICATION_JSON).content(referralBody)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        assertThrows(ApiException.class, () -> controller.read("episode-1"));
    }

    @Test
    void wrongRoleIsForbidden() throws Exception {
        when(staff.activeRoles("no-role")).thenReturn(List.of());
        mvc.perform(post("/api/v1/therapy/episodes")
                        .contentType(MediaType.APPLICATION_JSON).content(referralBody)
                        .header("X-Synthetic-Subject", "no-role")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
