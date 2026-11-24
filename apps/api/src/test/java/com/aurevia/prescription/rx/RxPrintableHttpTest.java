package com.aurevia.prescription.rx;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** BUILD-10 (RX-02): the printable prescription is reachable and protected through its HTTP route. */
class RxPrintableHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "clinician-1";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "bbbbbbbb-0000-0000-0000-000000000001";

    private RxService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RxService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new RxController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        when(service.printable(ID)).thenReturn(document());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsThePrintableDocumentThroughHttp() throws Exception {
        mvc.perform(get("/api/v1/prescriptions/{id}/printable", ID)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptionId").value(ID))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.channel").value("PRINTABLE_LOCAL"))
                .andExpect(jsonPath("$.attestationNote").value("synthetic attestation"))
                .andExpect(jsonPath("$.attestedAt").value("2026-11-20T10:15:30Z"));
        verify(service).printable(ID);
    }

    @Test
    void translatesAnEntitlementDenialToAnHttpError() throws Exception {
        when(service.printable(ID)).thenThrow(
                new ApiException(422, "NO_ENTITLEMENT", "the prescriber is not entitled in this market"));

        mvc.perform(get("/api/v1/prescriptions/{id}/printable", ID)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ENTITLEMENT"));
    }

    @Test
    void withoutLoginReturnsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/prescriptions/{id}/printable", ID)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static PrintablePrescription document() {
        return new PrintablePrescription(ID, PATIENT, "amoxicillin", "500 mg", "1 capsule",
                "oral", "twice daily", 30, SUBJECT, "pharmacy-x", "synthetic attestation",
                Instant.parse("2026-11-20T10:15:30Z"), RxChannel.PRINTABLE_LOCAL.wire());
    }
}
