package com.aurevia.radiology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.aurevia.radiology.RadCommands.AccessionCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.ScheduleCommand;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RAD-01 HTTP entry point: imaging intake, study linking and the study-link review queue. */
class ImagingControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "reception-a";
    private static final String ORDER = "order-http-1";
    private static final String PATIENT = "patient-http-1";
    private static final String ACC = "ACC-HTTP-1";
    private static final String ISSUER = "ISSUER-HTTP";
    private static final String UID = "1.2.840.113619.2.55.3.http";
    private static final String WORKSTATION = "pacs-workstation-http";
    private static final String REVIEWER = "reviewer-http";
    private static final String AT = "2026-11-20T10:00:00Z";

    private ImagingService service;
    private ImagingController controller;
    private StaffRepository staff;
    private TransactionTemplate transactions;
    private MockMvc mvc;
    private ObjectMapper mapper;
    private String intakeBody;
    private String linkBody;
    private String wrongLinkBody;
    private String reviewBody;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse(AT), ZoneOffset.UTC);
        service = new ImagingService(
                new RadPolicyCatalog(List.of(Modality.values()), List.of()), clock);
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new ImagingController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
        ImagingController.IntakeRequest request = new ImagingController.IntakeRequest(
                ORDER, PATIENT, "enc-1", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referring-clinic", "radiology", "clinician-a", "rad-work-queue", "referrer-a",
                Modality.CT);
        intakeBody = mapper.writeValueAsString(request);
        linkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                3, UID, PATIENT, ACC, WORKSTATION));
        wrongLinkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                3, UID, "patient-other", ACC, WORKSTATION));
        reviewBody = mapper.writeValueAsString(new ImagingController.StudyReviewRequest(
                ORDER, UID, "labels corrected after review"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void accessionedOrder() {
        ImagingOrder order = service.intake(new IntakeCommand(ORG, SUBJECT, Set.of("radiology_staff"),
                ORDER, PATIENT, "enc-1", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referring-clinic", "radiology", "clinician-a", "rad-work-queue", "referrer-a",
                Modality.CT));
        order = service.schedule(new ScheduleCommand(ORG, SUBJECT, Set.of("radiology_staff"), ORDER,
                order.version(), Instant.parse(AT).plus(Duration.ofDays(1)), Modality.CT));
        service.accession(new AccessionCommand(ORG, SUBJECT, Set.of("radiology_staff"), ORDER,
                order.version(), ACC, ISSUER));
    }

    @Test
    void intakesAndReadsAnOrderThroughHttp() throws Exception {
        mvc.perform(post("/api/v1/imaging/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(intakeBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.modality").value("CT"))
                .andExpect(jsonPath("$.urgency").value("ROUTINE"))
                .andExpect(jsonPath("$.clinicalQuestion").value("rule out pulmonary embolism"));
        mvc.perform(get("/api/v1/imaging/orders/{id}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(PATIENT));
    }

    @Test
    void autowiredConstructorBuildsAControllerWithAnApprovedModalityCatalog() {
        ImagingController wired = new ImagingController(
                Clock.fixed(Instant.parse(AT), ZoneOffset.UTC), staff, transactions);
        assertNotNull(wired);
        TenantContext.clear();
        assertThrows(ApiException.class, () -> wired.read(ORDER));
    }

    @Test
    void intakeWithoutModalityStillWorksThroughHttp() throws Exception {
        ImagingController.IntakeRequest legacy = new ImagingController.IntakeRequest(
                ORDER, PATIENT, "enc-1", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referring-clinic", "radiology", "clinician-a", "rad-work-queue", "referrer-a");
        assertNull(legacy.modality());
        mvc.perform(post("/api/v1/imaging/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(legacy))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER));
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(post("/api/v1/imaging/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(intakeBody)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        assertThrows(ApiException.class, () -> controller.read(ORDER));
    }

    @Test
    void wrongRoleIsForbidden() throws Exception {
        when(staff.activeRoles("no-role")).thenReturn(List.of());
        mvc.perform(post("/api/v1/imaging/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(intakeBody)
                        .header("X-Synthetic-Subject", "no-role")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void mismatchQueuesAReviewAndReLinkAfterReviewThroughHttp() throws Exception {
        accessionedOrder();
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("radiology_staff"));
        when(staff.activeRoles(REVIEWER)).thenReturn(List.of("radiology_staff"));

        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongLinkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_MISMATCH"));

        mvc.perform(get("/api/v1/imaging/orders/study-link-reviews")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].studyInstanceUid").value(UID));

        mvc.perform(post("/api/v1/imaging/orders/study-link-reviews/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody)
                        .header("X-Synthetic-Subject", REVIEWER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STUDY_ASSOCIATED"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void linkingValidatesInputRoleAndTenantThroughHttp() throws Exception {
        accessionedOrder();
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("radiology_staff"));

        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                                3, " ", PATIENT, ACC, WORKSTATION)))
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STUDY"));

        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized());

        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void reviewResolutionValidatesInputAndMissingReviewThroughHttp() throws Exception {
        accessionedOrder();
        when(staff.activeRoles(REVIEWER)).thenReturn(List.of("radiology_staff"));

        mvc.perform(post("/api/v1/imaging/orders/study-link-reviews/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ImagingController.StudyReviewRequest(
                                ORDER, UID, " ")))
                        .header("X-Synthetic-Subject", REVIEWER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESOLUTION_REQUIRED"));

        mvc.perform(post("/api/v1/imaging/orders/study-link-reviews/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content(reviewBody)
                        .header("X-Synthetic-Subject", REVIEWER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }
}
