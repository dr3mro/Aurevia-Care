package com.aurevia.radflow.worklist;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistStatus;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RAD-01 HTTP entry point: performing-site worklist, hand-off and guarded study link. */
class WorklistControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "tech-radflow";
    private static final String SITE_A = "site-a";
    private static final String SITE_B = "site-b";
    private static final String ORDER = "order-http";
    private static final String OTHER_ORDER = "order-http-b";
    private static final String PATIENT = "patient-http";
    private static final String ACC = "ACC-HTTP";
    private static final String ISSUER = "ISSUER-HTTP";
    private static final String UID = "1.2.840.113619.2.55.3.http";
    private static final String WORKSTATION = "pacs-workstation-http";
    private static final String AT = "2026-11-21T10:00:00Z";

    private WorklistService service;
    private WorklistController controller;
    private StaffRepository staff;
    private TransactionTemplate transactions;
    private MockMvc mvc;
    private ObjectMapper mapper;
    private String receiveBody;
    private String scheduleBody;
    private String linkBody;
    private String wrongLinkBody;
    private String reportBody;

    @BeforeEach
    void setUp() throws Exception {
        service = new WorklistService(Clock.fixed(Instant.parse(AT), ZoneOffset.UTC));
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("radiology_staff"));
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new WorklistController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
        receiveBody = mapper.writeValueAsString(new WorklistController.ReceiveRequest(
                ORDER, SITE_A, PATIENT, "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referrer-http"));
        scheduleBody = mapper.writeValueAsString(new WorklistController.ScheduleRequest(
                1, SITE_A, AT, Modality.CT, ACC, ISSUER));
        linkBody = mapper.writeValueAsString(new WorklistController.StudyLinkRequest(
                2, SITE_A, UID, PATIENT, ACC, WORKSTATION));
        wrongLinkBody = mapper.writeValueAsString(new WorklistController.StudyLinkRequest(
                2, SITE_A, UID, "patient-other", ACC, WORKSTATION));
        reportBody = mapper.writeValueAsString(new WorklistController.ReportRequest(3, SITE_A));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void receiveThroughHttp(String orderId, String site, String org) throws Exception {
        String body = mapper.writeValueAsString(new WorklistController.ReceiveRequest(
                orderId, site, PATIENT, "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referrer-http"));
        mvc.perform(post("/api/v1/radiology/worklist/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", org))
                .andExpect(status().isCreated());
    }

    private void progressThroughHttp() throws Exception {
        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/schedule", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(scheduleBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accession").value(ACC))
                .andExpect(jsonPath("$.modality").value("CT"));
        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PERFORMED"));
    }

    @Test
    void receives_schedules_links_reports_and_exposes_a_handoff_event() throws Exception {
        mvc.perform(post("/api/v1/radiology/worklist/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(receiveBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.status").value("RECEIVED"));

        progressThroughHttp();

        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/report", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(reportBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPORTED"));

        mvc.perform(get("/api/v1/radiology/worklist")
                        .param("site", SITE_A)
                        .param("status", "REPORTED")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(ORDER))
                .andExpect(jsonPath("$[0].status").value("REPORTED"));

        mvc.perform(get("/api/v1/radiology/worklist/events")
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'HANDOFF_RECORDED' && @.destination == 'referrer-http')]").exists());
    }

    @Test
    void another_site_and_another_tenant_cannot_see_the_order() throws Exception {
        receiveThroughHttp(ORDER, SITE_A, ORG);
        receiveThroughHttp(OTHER_ORDER, SITE_B, ORG);

        mvc.perform(get("/api/v1/radiology/worklist")
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(ORDER));

        mvc.perform(get("/api/v1/radiology/worklist")
                        .param("site", SITE_B)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(OTHER_ORDER));

        mvc.perform(get("/api/v1/radiology/worklist")
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/radiology/worklist/events")
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(ORDER));

        mvc.perform(get("/api/v1/radiology/worklist/events")
                        .param("site", SITE_B)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(OTHER_ORDER));

        mvc.perform(get("/api/v1/radiology/worklist/orders/{id}", ORDER)
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound());

        // A second site in the same tenant cannot read the first site's order detail.
        mvc.perform(get("/api/v1/radiology/worklist/orders/{id}", ORDER)
                        .param("site", SITE_B)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/schedule", ORDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new WorklistController.ScheduleRequest(
                                1, SITE_B, AT, Modality.CT, ACC, ISSUER)))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void linking_a_study_for_the_wrong_patient_is_refused_over_http() throws Exception {
        receiveThroughHttp(ORDER, SITE_A, ORG);
        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/schedule", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(scheduleBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongLinkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_MISMATCH"));

        mvc.perform(get("/api/v1/radiology/worklist/orders/{id}", ORDER)
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.studyInstanceUid").doesNotExist());

        mvc.perform(get("/api/v1/radiology/worklist/events")
                        .param("site", SITE_A)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'STUDY_LINK_REFUSED')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'HANDOFF_RECORDED')]").doesNotExist());
    }

    @Test
    void validates_authentication_role_and_invalid_timestamps() throws Exception {
        mvc.perform(post("/api/v1/radiology/worklist/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(receiveBody)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        when(staff.activeRoles("no-role")).thenReturn(List.of());
        mvc.perform(post("/api/v1/radiology/worklist/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(receiveBody)
                        .header("X-Synthetic-Subject", "no-role")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        TenantContext.clear();
        ApiException worklistDenied = assertThrows(ApiException.class,
                () -> controller.worklist(SITE_A, null));
        assertEquals(401, worklistDenied.status());
        assertEquals("UNAUTHENTICATED", worklistDenied.code());
        ApiException eventsDenied = assertThrows(ApiException.class, () -> controller.events(SITE_A));
        assertEquals(401, eventsDenied.status());
        assertEquals("UNAUTHENTICATED", eventsDenied.code());
    }

    @Test
    void invalid_schedule_timestamp_is_a_validation_error() throws Exception {
        receiveThroughHttp(ORDER, SITE_A, ORG);
        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/schedule", ORDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new WorklistController.ScheduleRequest(
                                1, SITE_A, "not-a-timestamp", Modality.CT, ACC, ISSUER)))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));

        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/schedule", ORDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new WorklistController.ScheduleRequest(
                                1, SITE_A, null, Modality.CT, ACC, ISSUER)))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));

        mvc.perform(post("/api/v1/radiology/worklist/orders/{id}/schedule", ORDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new WorklistController.ScheduleRequest(
                                1, SITE_A, " ", Modality.CT, ACC, ISSUER)))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));
    }
}
