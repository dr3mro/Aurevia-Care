package com.aurevia.pharmacyflow.clarify;

import static org.mockito.ArgumentMatchers.anyString;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.clarify.ClarificationController.AnswerRequest;
import com.aurevia.pharmacyflow.clarify.ClarificationController.OpenRequest;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.View;
import com.aurevia.prescription.rx.RxService;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RX-02 / MSG-04 HTTP entry point: open, answer, view, overdue and party isolation. */
class ClarificationControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "99999999-9999-9999-9999-999999999999";
    private static final String PHARMACIST = "pharm-tech-http";
    private static final String PRESCRIBER = "dr-prescriber-http";
    private static final String OTHER_CLINICIAN = "dr-other-http";
    private static final String PRESCRIPTION = "rx-http";
    private static final String AT_TEXT = "2026-11-21T10:00:00Z";
    private static final Duration LIMIT = Duration.ofHours(24);
    private static final String BASE = "/api/v1/pharmacy/clarifications";

    private MockMvc mvc;
    private ObjectMapper mapper;
    private AdvancingClock clock;

    @BeforeEach
    void setUp() {
        clock = new AdvancingClock(Instant.parse(AT_TEXT));
        mvc = buildMvc(clock);
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockMvc buildMvc(Clock clock) {
        RxService prescriptions = mock(RxService.class);
        Instant at = Instant.parse(AT_TEXT);
        View view = new View(new Prescription(PRESCRIPTION, 1, "patient-1", "paracetamol", "500mg",
                "1 tablet", "oral", "twice daily", 60, PRESCRIBER, "pharmacy-a",
                com.aurevia.prescription.rx.RxContract.State.DELIVERY_CONFIRMED,
                "partner", "submission-1", false, at), List.of());
        when(prescriptions.get(anyString())).thenReturn(view);

        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(anyString())).thenAnswer(invocation -> {
            String subject = invocation.getArgument(0);
            if (PRESCRIBER.equals(subject) || OTHER_CLINICIAN.equals(subject)) {
                return List.of(SpecialtyRoles.CLINICIAN);
            }
            if (PHARMACIST.equals(subject) || "pharm-other".equals(subject)) {
                return List.of(SpecialtyRoles.PHARMACY_STAFF);
            }
            return List.of(SpecialtyRoles.RECEPTION);
        });
        ClarificationService service = new ClarificationService(clock, LIMIT, prescriptions);
        ClarificationController controller = new ClarificationController(service, staff);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request, String subject, String org) {
        return request.header("X-Synthetic-Subject", subject).header("X-Active-Organization", org);
    }

    private ResultActions openThread(String subject, String key) throws Exception {
        String body = mapper.writeValueAsString(new OpenRequest("confirm the dose"));
        return mvc.perform(headers(post(BASE + "/{id}/request", PRESCRIPTION), subject, ORG)
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", key));
    }

    private String extractThreadId(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        int start = json.indexOf("\"id\":\"");
        int from = start + "\"id\":\"".length();
        return json.substring(from, json.indexOf('\"', from));
    }

    private ResultActions answer(String threadId, String subject, String body, String revision,
                                 String ifMatch, String key) throws Exception {
        String json = mapper.writeValueAsString(new AnswerRequest(body, revision));
        return mvc.perform(headers(post(BASE + "/{id}/answer", threadId), subject, ORG)
                .contentType(MediaType.APPLICATION_JSON).content(json)
                .header("If-Match", ifMatch).header("Idempotency-Key", key));
    }

    private ResultActions view(String threadId, String subject, String org) throws Exception {
        return mvc.perform(headers(get(BASE + "/{id}", threadId), subject, org));
    }

    @Test
    void openThreadThroughHttpAddressesThePrescriber() throws Exception {
        openThread(PHARMACIST, "open-http-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.state").value("open"))
                .andExpect(jsonPath("$.thread.prescriptionId").value(PRESCRIPTION))
                .andExpect(jsonPath("$.thread.prescriberSubject").value(PRESCRIBER))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].authorParty").value("pharmacy"));

        String threadId = extractThreadId(openThread(PHARMACIST, "open-http-2"));
        view(threadId, PRESCRIBER, ORG).andExpect(status().isOk());
    }

    @Test
    void textAnswerThroughHttpClosesAndReachesPharmacy() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-answer"));

        answer(threadId, PRESCRIBER, "give 250mg in the morning", null, "1", "answer-http-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.state").value("answered"))
                .andExpect(jsonPath("$.thread.answerBody").value("give 250mg in the morning"))
                .andExpect(jsonPath("$.entries[1].authorParty").value("prescriber"));

        view(threadId, PHARMACIST, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.state").value("answered"))
                .andExpect(jsonPath("$.thread.answerBody").value("give 250mg in the morning"));
    }

    @Test
    void amendedRevisionAnswerThroughHttpLinksRevision() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-revision"));

        answer(threadId, PRESCRIBER, null, "revision-9", "1", "answer-rev-http")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thread.state").value("answered"))
                .andExpect(jsonPath("$.thread.amendedRevisionId").value("revision-9"))
                .andExpect(jsonPath("$.entries[1].amendedRevisionId").value("revision-9"));
    }

    @Test
    void thirdPartyAndOtherTenantCannotViewThreadThroughHttp() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-isolation"));

        view(threadId, OTHER_CLINICIAN, ORG).andExpect(status().isNotFound());
        view(threadId, PHARMACIST, OTHER_ORG).andExpect(status().isNotFound());
    }

    @Test
    void wrongRoleCannotOpenOrAnswerThroughHttp() throws Exception {
        String body = mapper.writeValueAsString(new OpenRequest("confirm the dose"));
        mvc.perform(headers(post(BASE + "/{id}/request", PRESCRIPTION), "reception-user", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "open-wrong-role"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String threadId = extractThreadId(openThread(PHARMACIST, "open-answer-role"));
        answer(threadId, PHARMACIST, "self answer", null, "1", "answer-wrong-role")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void nonAddresseeClinicianCannotAnswerThroughHttp() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-other-clinician"));

        answer(threadId, OTHER_CLINICIAN, "not my prescription", null, "1", "answer-other")
                .andExpect(status().isNotFound());
    }

    @Test
    void missingContentAndBlankQuestionAreRejectedThroughHttp() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-invalid-content"));
        answer(threadId, PRESCRIBER, "  ", "  ", "1", "answer-empty")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANSWER_REQUIRED"));

        String blank = mapper.writeValueAsString(new OpenRequest("  "));
        mvc.perform(headers(post(BASE + "/{id}/request", PRESCRIPTION), PHARMACIST, ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(blank)
                        .header("Idempotency-Key", "open-blank"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void staleVersionAndClosedThreadAreRejectedThroughHttp() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-stale"));
        answer(threadId, PRESCRIBER, "text", null, "2", "answer-stale")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        answer(threadId, PRESCRIBER, "text", null, "1", "answer-first").andExpect(status().isOk());
        answer(threadId, PRESCRIBER, "again", null, "2", "answer-second")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLOSED_THREAD"));
    }

    @Test
    void idempotentAnswerReplayThroughHttpRecordsOnce() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-replay"));
        answer(threadId, PRESCRIBER, "text", null, "1", "answer-replay-key").andExpect(status().isOk());
        answer(threadId, PRESCRIBER, "text", null, "1", "answer-replay-key").andExpect(status().isOk());

        view(threadId, PHARMACIST, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.thread.version").value(2));
    }

    @Test
    void preconditionsAreRequiredThroughHttp() throws Exception {
        String body = mapper.writeValueAsString(new OpenRequest("confirm the dose"));
        mvc.perform(headers(post(BASE + "/{id}/request", PRESCRIPTION), PHARMACIST, ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        String threadId = extractThreadId(openThread(PHARMACIST, "open-preconditions"));
        String answer = mapper.writeValueAsString(new AnswerRequest("text", null));
        mvc.perform(headers(post(BASE + "/{id}/answer", threadId), PRESCRIBER, ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(answer)
                        .header("Idempotency-Key", "answer-no-ifmatch"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void malformedIdempotencyKeyIsRejectedThroughHttp() throws Exception {
        String body = mapper.writeValueAsString(new OpenRequest("confirm the dose"));
        mvc.perform(headers(post(BASE + "/{id}/request", PRESCRIPTION), PHARMACIST, ORG)
                        .contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Idempotency-Key", "bad key"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void overdueFlagSurfacesThroughHttp() throws Exception {
        String threadId = extractThreadId(openThread(PHARMACIST, "open-overdue"));
        view(threadId, PHARMACIST, ORG).andExpect(jsonPath("$.overdue").value(false));

        clock.advance(LIMIT.plusNanos(1));
        view(threadId, PHARMACIST, ORG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue").value(true));

        mvc.perform(headers(get(BASE + "/overdue"), PHARMACIST, ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].overdue").value(true));
    }

    /** Mutable test clock so one service instance can cross the overdue boundary. */
    private static final class AdvancingClock extends Clock {
        private Instant now;

        private AdvancingClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
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
            return now;
        }
    }
}
