package com.aurevia.messaging.flows;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * HTTP acceptance for the patient-flow presentation entry point.
 *
 * <p>Every request uses synthetic subject, organization, membership and policy
 * fixtures. The test drives the real controller, service and store through the
 * Spring MVC request pipeline, including the tenant interceptor and API error
 * handler, so an unreachable or tenant-leaking service cannot pass.</p>
 */
class PatientFlowPresentationControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-user";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryFlowPolicyStore store;
    private MockMvc mvc;

    /** Wire a real service over a synthetic store through the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryFlowPolicyStore();
        PatientFlowPresentationService service =
                new PatientFlowPresentationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new PatientFlowPresentationController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        store.addMember(ORG, SUBJECT, true);
        store.setPolicyVersion(ORG, AppLocale.EN, AppPlatform.ANDROID, 7);
        store.setApprovedNotices(ORG, List.of(NoticeKind.NEW_MESSAGE));
    }

    /** Clear the thread-bound tenant context after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder headers(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG);
    }

    @Test
    void presentationIsReachableOverHttpWithTenantScopedPolicy() throws Exception {
        mvc.perform(headers(get("/api/v1/patient-flow/presentation")
                        .param("locale", "en").param("platform", "android")
                        .param("timeZone", "Europe/London")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(ORG))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.platform").value("android"))
                .andExpect(jsonPath("$.direction").value("ltr"))
                .andExpect(jsonPath("$.policyVersion").value(7))
                .andExpect(jsonPath("$.timeZoneContext").value("Europe/London"))
                .andExpect(jsonPath("$.accessibilityProfile").value("WCAG-2.2-AA"))
                .andExpect(jsonPath("$.noticeKinds[0]").value("new_message"));
    }

    @Test
    void notificationTextIsReachableOverHttp() throws Exception {
        mvc.perform(headers(get("/api/v1/patient-flow/notices/{kind}", "new_message")
                        .param("locale", "en")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("You have a new message."));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mvc.perform(get("/api/v1/patient-flow/presentation")
                        .header("X-Active-Organization", ORG)
                        .param("locale", "en").param("platform", "android").param("timeZone", "UTC"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void inactiveMembershipIsDeniedOverHttp() throws Exception {
        store.addMember(ORG, SUBJECT, false);
        mvc.perform(headers(get("/api/v1/patient-flow/presentation")
                        .param("locale", "en").param("platform", "android").param("timeZone", "UTC")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidPresentationInputIsRejectedOverHttp() throws Exception {
        mvc.perform(headers(get("/api/v1/patient-flow/presentation")
                        .param("locale", "fr").param("platform", "android").param("timeZone", "UTC")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void missingPresentationParametersUseTheSharedInvalidInputContract() throws Exception {
        mvc.perform(headers(get("/api/v1/patient-flow/presentation")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void unapprovedNoticeIsRejectedOverHttp() throws Exception {
        mvc.perform(headers(get("/api/v1/patient-flow/notices/{kind}", "new_instruction")
                        .param("locale", "en")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
