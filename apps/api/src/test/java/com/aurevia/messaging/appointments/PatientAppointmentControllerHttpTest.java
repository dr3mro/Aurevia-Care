package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/** PAT-01 HTTP entry point: every patient self-service command is reachable through a protected route. */
class PatientAppointmentControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-a";
    private static final String PATIENT = "patient-1";
    private static final String OTHER_PATIENT = "patient-2";
    private static final String APPOINTMENT = "appt-1";
    private static final String DEVICE = "device-1";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private PatientAppointmentController controller;
    private SyntheticPatientAppointmentPorts ports;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ports = new SyntheticPatientAppointmentPorts();
        controller = new PatientAppointmentController(new PatientAppointmentService(ports.links,
                ports.appointments, ports.devices, ports.gateway, ports.audit), clock);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        ports.links.add(SUBJECT, PATIENT);
        ports.links.add(SUBJECT, OTHER_PATIENT);
        ports.appointments.put(new PatientAppointment(APPOINTMENT, 1, PATIENT, "MAIN", "Asia/Riyadh",
                Instant.parse("2026-12-01T07:00:00Z"), AppointmentStatus.BOOKED, true, null));
        ports.devices.save(new DeviceToken("other-device", "other-token", SUBJECT,
                OTHER_PATIENT, DeviceState.ACTIVE));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder headers(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("X-Synthetic-Subject", SUBJECT).header("X-Active-Organization", ORG);
    }

    @Test
    void drivesTheWholePatientAppointmentWorkflowOverHttp() throws Exception {
        mvc.perform(headers(get("/api/v1/patient/appointments").param("patientId", PATIENT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].facilityTimeZone").value("Asia/Riyadh"));

        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/confirm", APPOINTMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"expectedVersion\":1}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.confirmedAt").value("2026-11-20T10:00:00Z"));

        mvc.perform(headers(post("/api/v1/patient/appointments/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"tokenId\":\"" + DEVICE
                                + "\",\"token\":\"synthetic-token\"}")))
                .andExpect(status().isNoContent());

        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/notify", APPOINTMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"status\":\"CHECKED_IN\"}")))
                .andExpect(status().isNoContent());

        mvc.perform(headers(delete("/api/v1/patient/appointments/devices/{tokenId}", DEVICE)
                        .param("patientId", PATIENT)))
                .andExpect(status().isNoContent());

        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/notify", APPOINTMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"status\":\"CHECKED_IN\"}")))
                .andExpect(status().isNoContent());

        mvc.perform(headers(post("/api/v1/patient/appointments/devices/{tokenId}/invalid", DEVICE)
                        .param("patientId", PATIENT).param("reason", "INVALID_TOKEN")))
                .andExpect(status().isNoContent());

        assertEquals(1, ports.audit.entries().stream()
                .filter(entry -> "PATIENT_APPOINTMENT_LIST".equals(entry.action())).count());
        assertEquals(1, ports.devices.invalidLog().size());
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(get("/api/v1/patient/appointments")
                        .param("patientId", PATIENT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        ApiException error = assertThrows(ApiException.class, () -> controller.list(PATIENT));
        assertEquals(401, error.status());
        assertEquals("UNAUTHENTICATED", error.code());
    }

    @Test
    void staleAndConcealedCommandsAreRejected() throws Exception {
        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/confirm", APPOINTMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"expectedVersion\":1}")))
                .andExpect(status().isOk());
        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/confirm", APPOINTMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"expectedVersion\":1}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_CONFIRMABLE"));
        assertEquals(0, ports.appointments.confirm(APPOINTMENT, PATIENT, 1, NOW));
        assertEquals(0, ports.appointments.confirm("missing", PATIENT, 1, NOW));
        assertEquals(0, ports.appointments.confirm(APPOINTMENT, OTHER_PATIENT, 1, NOW));
        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/confirm", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"expectedVersion\":1}")))
                .andExpect(status().isNotFound());
        mvc.perform(headers(post("/api/v1/patient/appointments/{id}/confirm", APPOINTMENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + OTHER_PATIENT + "\",\"expectedVersion\":1}")))
                .andExpect(status().isNotFound());
    }

    @Test
    void unlinkedSubjectIsDenied() throws Exception {
        mvc.perform(get("/api/v1/patient/appointments")
                        .param("patientId", PATIENT)
                        .header("X-Synthetic-Subject", "unlinked")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void deviceConflictsAndConcealmentAreRejected() throws Exception {
        mvc.perform(headers(post("/api/v1/patient/appointments/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"tokenId\":\"" + DEVICE
                                + "\",\"token\":\"synthetic-token\"}")))
                .andExpect(status().isNoContent());
        mvc.perform(headers(post("/api/v1/patient/appointments/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + OTHER_PATIENT + "\",\"tokenId\":\"" + DEVICE
                                + "\",\"token\":\"synthetic-token\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEVICE_CONFLICT"));
        mvc.perform(headers(delete("/api/v1/patient/appointments/devices/{tokenId}", "missing")
                        .param("patientId", PATIENT)))
                .andExpect(status().isNotFound());
        mvc.perform(headers(post("/api/v1/patient/appointments/devices/{tokenId}/invalid", DEVICE)
                        .param("patientId", OTHER_PATIENT).param("reason", "INVALID_TOKEN")))
                .andExpect(status().isNotFound());
        mvc.perform(headers(post("/api/v1/patient/appointments/devices/{tokenId}/invalid", "missing")
                        .param("patientId", PATIENT).param("reason", "INVALID_TOKEN")))
                .andExpect(status().isNotFound());
    }
}
