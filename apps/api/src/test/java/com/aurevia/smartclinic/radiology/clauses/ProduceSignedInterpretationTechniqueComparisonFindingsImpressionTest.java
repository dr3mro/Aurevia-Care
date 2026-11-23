package com.aurevia.smartclinic.radiology.clauses;

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

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.radflow.report.ReportController;
import com.aurevia.smartclinic.radflow.report.ReportController.PublishRequest;
import com.aurevia.smartclinic.radflow.report.ReportService;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * RAD-01 clause 3 HTTP proof: a credentialed interpreter delivers one signed
 * interpretation carrying every narrative section, and the delivery boundary
 * refuses a signer without imaging privileges or a report that was never signed.
 *
 * <p>The fixtures are synthetic and mirror {@code ReportControllerHttpTest}:
 * a standalone MockMvc over the real controller and domain service, a fixed
 * clock, and membership resolved from the request tenant context. No signer
 * name is taken from the request body; the signature binds to the authenticated
 * subject supplied in {@code X-Synthetic-Subject}.</p>
 */
class ProduceSignedInterpretationTechniqueComparisonFindingsImpressionTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SIGNER = "interpreter-clause3";
    private static final String STAFF_ONLY = "staff-only-clause3";
    private static final String REFERRER = "referrer-clause3";
    private static final String ORDER = "order-clause3";
    private static final String PATIENT = "patient-clause3";
    private static final String UID = "1.2.840.113619.2.55.3.report.clause3";
    private static final String TECHNIQUE = "CT chest with contrast";
    private static final String COMPARISON = "prior CT from 2026-01-01";
    private static final String FINDINGS = "no pulmonary embolism";
    private static final String IMPRESSION = "unremarkable";
    private static final String FOLLOW_UP = "clinical review in two weeks";
    private static final String ATTESTATION = "signed by interpreter";
    private static final String AT = "2026-11-21T10:00:00Z";
    private static final String TEMPLATE = "template-1";
    private static final String WINDOW = "PT24H";

    /** Deterministic clock so the signing instant and notification times are fixed. */
    private static final class TestClock extends Clock {
        private final Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
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

    private MockMvc mvc;
    private ObjectMapper mapper;
    private PublishRequest signedBody;

    @BeforeEach
    void setUp() throws Exception {
        ReportService service = new ReportService(new TestClock(Instant.parse(AT)),
                Duration.parse(WINDOW));
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SIGNER)).thenReturn(List.of("radiology_interpreter"));
        when(staff.activeRoles(STAFF_ONLY)).thenReturn(List.of("radiology_staff"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        ReportController controller = new ReportController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
        signedBody = new PublishRequest(ORDER, REFERRER, PATIENT, UID, TECHNIQUE, COMPARISON,
                FINDINGS, IMPRESSION, FOLLOW_UP, ATTESTATION, AT, TEMPLATE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Deliver one signed interpretation through the real HTTP entry point. */
    private void publish(String subject, PublishRequest body) throws Exception {
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))
                        .header("X-Synthetic-Subject", subject)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated());
    }

    @Test
    void signedInterpretationCarriesAllFiveNarrativeSections() throws Exception {
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(signedBody))
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.referrer").value(REFERRER))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.revisions.length()").value(1))
                .andExpect(jsonPath("$.revisions[0].version").value(1))
                .andExpect(jsonPath("$.revisions[0].technique").value(TECHNIQUE))
                .andExpect(jsonPath("$.revisions[0].comparison").value(COMPARISON))
                .andExpect(jsonPath("$.revisions[0].findings").value(FINDINGS))
                .andExpect(jsonPath("$.revisions[0].impression").value(IMPRESSION))
                .andExpect(jsonPath("$.revisions[0].followUp").value(FOLLOW_UP))
                .andExpect(jsonPath("$.revisions[0].signer").value(SIGNER))
                .andExpect(jsonPath("$.revisions[0].attestation").value(ATTESTATION))
                .andExpect(jsonPath("$.revisions[0].signedAt").value(AT))
                .andExpect(jsonPath("$.revisions[0].templateVersion").value(TEMPLATE));
    }

    @Test
    void staffOnlyActorCannotProduceASignedInterpretation() throws Exception {
        // Signing is reserved for an interpreter with imaging privileges; a
        // radiology staff role may accession a study but must never sign it.
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(signedBody))
                        .header("X-Synthetic-Subject", STAFF_ONLY)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        publish(SIGNER, signedBody);
        mvc.perform(get("/api/v1/radiology/reports/{id}", ORDER)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions[0].signer").value(SIGNER));
    }

    @Test
    void unsignedInterpretationIsRefusedBeforeAnyDeliveryFact() throws Exception {
        PublishRequest unsigned = new PublishRequest(ORDER, REFERRER, PATIENT, UID, TECHNIQUE,
                COMPARISON, FINDINGS, IMPRESSION, FOLLOW_UP, ATTESTATION, null, TEMPLATE);

        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(unsigned))
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSIGNED_REPORT"));

        // The refusal is recorded, but no report is delivered and the referrer
        // is never notified, so an unsigned draft cannot reach the chart.
        mvc.perform(get("/api/v1/radiology/reports/events")
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'UNSIGNED_REPORT_REFUSED')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'INTERPRETATION_DELIVERED')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.type == 'REFERRER_NOTIFIED')]").doesNotExist());

        mvc.perform(get("/api/v1/radiology/reports/{id}", ORDER)
                        .header("X-Synthetic-Subject", REFERRER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSecondSignedReportForTheSameOrderIsRefused() throws Exception {
        publish(SIGNER, signedBody);

        // A signed interpretation is an immutable first revision, not an
        // overwritable draft: replaying the POST must not create a second report.
        mvc.perform(post("/api/v1/radiology/reports")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(signedBody))
                        .header("X-Synthetic-Subject", SIGNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REPORT"));
    }
}
