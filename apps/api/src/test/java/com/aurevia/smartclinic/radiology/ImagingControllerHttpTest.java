package com.aurevia.smartclinic.radiology;

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

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.radiology.RadTypes.Modality;
import com.aurevia.smartclinic.radiology.RadTypes.Urgency;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** RAD-01 HTTP entry point: imaging intake and read are reachable through protected routes. */
class ImagingControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "reception-a";
    private static final String ORDER = "order-http-1";
    private static final String PATIENT = "patient-http-1";

    private ImagingController controller;
    private StaffRepository staff;
    private MockMvc mvc;
    private String intakeBody;

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        ImagingService service = new ImagingService(
                new RadPolicyCatalog(List.of(Modality.values()), List.of()), clock);
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new ImagingController(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        ImagingController.IntakeRequest request = new ImagingController.IntakeRequest(
                ORDER, PATIENT, "enc-1", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referring-clinic", "radiology", "clinician-a", "rad-work-queue", "referrer-a");
        intakeBody = new ObjectMapper().writeValueAsString(request);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void intakesAndReadsAnOrderThroughHttp() throws Exception {
        mvc.perform(post("/api/v1/imaging/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(intakeBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(ORDER))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/imaging/orders/{id}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(PATIENT));
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
}
