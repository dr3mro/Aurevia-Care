package com.aurevia.messaging.enrollment;

import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.enrollment.EnrollmentContract.OpenCaseRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * PAT-03 HTTP adapter coverage. Every route derives the caller from the bound
 * tenant context plus freshly resolved active roles and delegates to the real
 * domain service over an in-memory store, so the assertions prove both adapter
 * wiring and unchanged domain behavior. All fixtures are synthetic.
 */
class PatientAccessControllerTest {

    private static final String BASE = "/api/v1/patient/access";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "syn-patient";
    private static final String STAFF = "syn-staff";
    private static final String PRIVACY = "syn-privacy";
    private static final String SUPPORT = "syn-support";
    private static final String ADMIN = "syn-admin";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACTS = "site-verified-facts";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private MockMvc mvc;
    private MockMvc noContextMvc;
    private String invitationCode;
    private String accountId;
    private String recoveryToken;
    private String reauthToken;
    private String openCaseId;
    private String reviewCaseId;

    @BeforeEach
    void setUp() {
        InMemoryPatientAccessStore store = new InMemoryPatientAccessStore();
        store.seedChart(ORG, CHART);
        PatientAccessService service = new PatientAccessService(store, new EnrollmentActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(anyString())).thenAnswer(invocation -> {
            String subject = invocation.getArgument(0);
            if (STAFF.equals(subject)) {
                return List.of(EnrollmentActions.RECEPTION);
            }
            if (PRIVACY.equals(subject)) {
                return List.of(EnrollmentActions.PRIVACY_AUDITOR);
            }
            if (SUPPORT.equals(subject)) {
                return List.of(EnrollmentActions.SUPPORT_AGENT);
            }
            if (ADMIN.equals(subject)) {
                return List.of(EnrollmentActions.TENANT_ADMIN);
            }
            return List.of(EnrollmentActions.PATIENT);
        });
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));

        PatientAccessController controller = new PatientAccessController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        noContextMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        invitationCode = service.issueInvitation(actor(STAFF),
                new com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest(
                        CHART, new RelationshipRef("enc", true), FACTS, null)).code();
        accountId = service.enroll(actor(SUBJECT),
                new com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest(
                        invitationCode, FACTS)).id();
        recoveryToken = service.issueRecovery(actor(PRIVACY), accountId).token();
        reauthToken = service.issueReauth(actor(SUBJECT), "change_contact").token();
        openCaseId = service.openCase(actor(SUBJECT),
                new OpenCaseRequest(SUBJECT,
                        com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind.RECORD_ACCESS,
                        "a valid summary")).id();
        reviewCaseId = service.openCase(actor(SUBJECT),
                new OpenCaseRequest(SUBJECT,
                        com.aurevia.messaging.enrollment.EnrollmentContract.SupportKind.ACCESSIBILITY,
                        "another valid summary")).id();
        service.assignCase(actor(SUPPORT), reviewCaseId, "owner");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static PatientAccessActor actor(String subject) {
        String role;
        if (STAFF.equals(subject)) {
            role = EnrollmentActions.RECEPTION;
        } else if (PRIVACY.equals(subject)) {
            role = EnrollmentActions.PRIVACY_AUDITOR;
        } else if (SUPPORT.equals(subject)) {
            role = EnrollmentActions.SUPPORT_AGENT;
        } else if (ADMIN.equals(subject)) {
            role = EnrollmentActions.TENANT_ADMIN;
        } else {
            role = EnrollmentActions.PATIENT;
        }
        return PatientAccessActor.of(ORG, subject, role);
    }

    private static MockHttpServletRequestBuilder authed(String subject,
                                                        MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", subject).header("X-Active-Organization", ORG);
    }

    private static MockHttpServletRequestBuilder json(String subject,
                                                      MockHttpServletRequestBuilder builder, String body) {
        return authed(subject, builder).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void missingTenantContextFailsClosed() throws Exception {
        TenantContext.clear();
        noContextMvc.perform(get(BASE + "/audit"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invitationIssueAndRedeemReachTheDomainService() throws Exception {
        mvc.perform(json(STAFF, post(BASE + "/invitations"),
                        "{\"chartId\":\"" + CHART + "\",\"relationship\":{\"relationshipId\":\"enc\",\"siteVerified\":true},"
                                + "\"factDigest\":\"" + FACTS + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("pending"));

        mvc.perform(json(SUBJECT, post(BASE + "/invitations/redeem"),
                        "{\"code\":\"missing-code\",\"factDigest\":\"" + FACTS + "\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(json(SUBJECT, post(BASE + "/invitations/redeem"),
                        "{\"code\":\"" + invitationCode + "\",\"factDigest\":\"" + FACTS + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void recoveryUnlockReauthAndSensitiveChangeReachTheDomainService() throws Exception {
        mvc.perform(authed(PRIVACY, post(BASE + "/accounts/" + accountId + "/recovery")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("recovery"));
        mvc.perform(json(SUBJECT, post(BASE + "/accounts/recovery/redeem"),
                        "{\"accountId\":\"" + accountId + "\",\"code\":\"" + recoveryToken
                                + "\",\"factDigest\":\"" + FACTS + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(authed(ADMIN, post(BASE + "/accounts/" + accountId + "/unlock")))
                .andExpect(status().isOk());
        mvc.perform(authed(SUBJECT, post(BASE + "/reauth").param("purpose", "change_contact")))
                .andExpect(status().isCreated());
        mvc.perform(json(SUBJECT, post(BASE + "/accounts/sensitive-change"),
                        "{\"accountId\":\"" + accountId + "\",\"action\":\"change_contact\","
                                + "\"token\":\"" + reauthToken + "\",\"value\":\"" + FACTS + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void contactChartAccessAndCaseLifecycleReachTheDomainService() throws Exception {
        mvc.perform(json(STAFF, post(BASE + "/accounts/contact"),
                        "{\"accountId\":\"" + accountId + "\",\"contactDigest\":\"" + FACTS + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(authed(SUBJECT, get(BASE + "/accounts/" + accountId + "/chart-access").param("chartId", CHART)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(json(SUBJECT, post(BASE + "/cases"),
                        "{\"patientId\":\"" + SUBJECT + "\",\"kind\":\"record_access\",\"summary\":\"a valid summary\"}"))
                .andExpect(status().isCreated());
        mvc.perform(authed(SUPPORT, post(BASE + "/cases/" + openCaseId + "/assign").param("assignee", "owner")))
                .andExpect(status().isOk());
        mvc.perform(authed(PRIVACY, post(BASE + "/cases/" + reviewCaseId + "/resolve").param("note", "reviewed")))
                .andExpect(status().isOk());
    }

    @Test
    void readsAreRoleScoped() throws Exception {
        mvc.perform(authed(PRIVACY, get(BASE + "/accounts/" + accountId + "/view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.subject").value(SUBJECT));
        mvc.perform(authed(PRIVACY, get(BASE + "/audit")))
                .andExpect(status().isOk());
        mvc.perform(authed(SUPPORT, get(BASE + "/notifications")))
                .andExpect(status().isOk());
        mvc.perform(authed(SUBJECT, get(BASE + "/audit")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
