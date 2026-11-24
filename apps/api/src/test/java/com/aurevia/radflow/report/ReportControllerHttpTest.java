package com.aurevia.radflow.report;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import com.aurevia.radflow.report.ReportController.AddendumRequest;
import com.aurevia.radflow.report.ReportController.PublishRequest;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RAD-01 HTTP entry point: signed report delivery, addendum notification and acknowledgment. */
class ReportControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SIGNER = "interpreter-http";
    private static final String REFERRER = "referrer-http";
    private static final String OTHER_REFERRER = "referrer-other-http";
    private static final String ORDER = "order-http";
    private static final String PATIENT = "patient-http";
    private static final String UID = "1.2.840.113619.2.55.3.report.http";
    private static final String AT = "2026-11-21T10:00:00Z";
    private static final String WINDOW = "PT24H";

    /** Deterministic clock whose instant can be advanced to age an unacknowledged addendum. */
    private static final class TestClock extends Clock {
        private Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private TestClock clock;
    private ReportService service;
    private ReportController controller;
    private StaffRepository staff;
    private MockMvc mvc;
    private ObjectMapper mapper;
    private PublishRequest publishBody;
    private AddendumRequest addendumBody;
    private String publishJson;
    private String addendumJson;

    @BeforeEach
    void setUp() throws Exception {
        clock = new TestClock(Instant.parse(AT));
        service = new ReportService(clock, Duration.parse(WINDOW));
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SIGNER)).thenReturn(List.of("radiology_interpreter"));
        when(staff.activeRoles("no-role")).thenReturn(List.of());
        when(staff.activeRoles("staff-only")).thenReturn(List.of("radiology_staff"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new ReportController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
        publishBody = new PublishRequest(ORDER, REFERRER, PATIENT, UID, "CT chest", "prior CT",
                "no pulmonary embolism", "unremarkable", "none", "signed by interpreter", AT,
                "template-1");
        addendumBody = new AddendumRequest(1, PATIENT, UID, "CT chest", "prior CT", "additional finding",
                "revised impression", "none", "signed by interpreter", AT, "template-1",
                "additional finding");
        publishJson = mapper.writeValueAsString(publishBody);
        addendumJson = mapper.writeValueAsString(addendumBody);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void publishThroughHttp(String org) throws Exception {
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(publishJson)
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", org))
                .andExpect(status().isCreated());
    }

    private void addendumThroughHttp() throws Exception {
        mvc.perform(post("/api/v1/radiology/reports/{id}/addenda", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(addendumJson)
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());
    }

    @Test
    void signed_interpretation_reaches_the_referrer_over_http() throws Exception {
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(publishJson)
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.referrer").value(REFERRER))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisions[0].technique").value("CT chest"))
                .andExpect(jsonPath("$.revisions[0].comparison").value("prior CT"))
                .andExpect(jsonPath("$.revisions[0].findings").value("no pulmonary embolism"))
                .andExpect(jsonPath("$.revisions[0].impression").value("unremarkable"))
                .andExpect(jsonPath("$.revisions[0].signer").value(SIGNER));

        mvc.perform(get("/api/v1/radiology/reports/events")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'INTERPRETATION_DELIVERED' && @.destination == 'referrer-http')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'REFERRER_NOTIFIED' && @.destination == 'referrer-http')]").exists());
    }

    @Test
    void addendum_notifies_the_referrer_and_acknowledgment_closes_it_over_http() throws Exception {
        publishThroughHttp(ORG);
        addendumThroughHttp();

        mvc.perform(get("/api/v1/radiology/reports/addenda/open")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].state").value("OPEN"));

        mvc.perform(post("/api/v1/radiology/reports/{id}/addenda/{version}/acknowledge", ORDER, 2)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addenda[0].state").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.addenda[0].acknowledgedBy").value(REFERRER));

        mvc.perform(get("/api/v1/radiology/reports/addenda/open")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/radiology/reports/events")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'ADDENDUM_ACKNOWLEDGED')]").exists());
    }

    @Test
    void unacknowledged_addenda_age_into_an_overdue_list_over_http() throws Exception {
        publishThroughHttp(ORG);
        addendumThroughHttp();

        mvc.perform(get("/api/v1/radiology/reports/addenda/overdue")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        clock.advance(Duration.parse(WINDOW).plusNanos(1));

        mvc.perform(get("/api/v1/radiology/reports/addenda/overdue")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(2));

        mvc.perform(post("/api/v1/radiology/reports/{id}/addenda/{version}/acknowledge", ORDER, 2)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/radiology/reports/addenda/overdue")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unsigned_reports_are_never_sent_over_http() throws Exception {
        PublishRequest unsigned = new PublishRequest(ORDER, REFERRER, PATIENT, UID, "CT chest", "prior CT",
                "no pulmonary embolism", "unremarkable", "none", "signed by interpreter", null,
                "template-1");
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(unsigned))
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSIGNED_REPORT"));

        mvc.perform(get("/api/v1/radiology/reports/events")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'REFERRER_NOTIFIED')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.type == 'INTERPRETATION_DELIVERED')]").doesNotExist());

        mvc.perform(get("/api/v1/radiology/reports/{id}", ORDER)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound());
    }

    @Test
    void only_the_verified_referrer_may_read_and_acknowledge_over_http() throws Exception {
        publishThroughHttp(ORG);
        addendumThroughHttp();

        mvc.perform(get("/api/v1/radiology/reports/{id}", ORDER)
                        .header("X-Synthetic-Subject", OTHER_REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/api/v1/radiology/reports/{id}/revisions", ORDER)
                        .header("X-Synthetic-Subject", OTHER_REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(post("/api/v1/radiology/reports/{id}/addenda/{version}/acknowledge", ORDER, 2)
                        .header("X-Synthetic-Subject", OTHER_REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/api/v1/radiology/reports/{id}", ORDER)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void another_tenant_cannot_see_the_report_or_its_addenda_over_http() throws Exception {
        publishThroughHttp(ORG);
        addendumThroughHttp();

        mvc.perform(get("/api/v1/radiology/reports/{id}", ORDER)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/radiology/reports/addenda/open")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/radiology/reports/events")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void validates_authentication_role_and_invalid_signature_time_over_http() throws Exception {
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(publishJson)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(publishJson)
                        .header("X-Synthetic-Subject", "no-role")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // Radiology staff may not sign a report: signing is reserved for interpreters.
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(publishJson)
                        .header("X-Synthetic-Subject", "staff-only")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        PublishRequest malformed = new PublishRequest(ORDER, REFERRER, PATIENT, UID, "CT chest", "prior CT",
                "findings", "impression", "none", "signed by interpreter", "not-a-timestamp",
                "template-1");
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(malformed))
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNED_AT"));

        PublishRequest blankTime = new PublishRequest(ORDER, REFERRER, PATIENT, UID, "CT chest", "prior CT",
                "findings", "impression", "none", "signed by interpreter", " ",
                "template-1");
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(blankTime))
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSIGNED_REPORT"));

        TenantContext.clear();
        ApiException denied = assertThrows(ApiException.class, () -> controller.addendum(ORDER, addendumBody));
        assertEquals(401, denied.status());
        assertEquals("UNAUTHENTICATED", denied.code());
    }

    @Test
    void reads_revisions_of_a_delivered_report_over_http() throws Exception {
        publishThroughHttp(ORG);
        addendumThroughHttp();

        mvc.perform(get("/api/v1/radiology/reports/{id}/revisions", ORDER)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("INITIAL"))
                .andExpect(jsonPath("$[1].kind").value("ADDENDUM"));
    }
}
