package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.messaging.appointments.AppointmentStatus;
import com.aurevia.messaging.appointments.AuditSink;
import com.aurevia.messaging.appointments.DeviceState;
import com.aurevia.messaging.appointments.DeviceToken;
import com.aurevia.messaging.appointments.DeviceTokenStore;
import com.aurevia.messaging.appointments.GenericPushNotice;
import com.aurevia.messaging.appointments.PatientAppointment;
import com.aurevia.messaging.appointments.PatientAppointmentController;
import com.aurevia.messaging.appointments.PatientAppointmentService;
import com.aurevia.messaging.appointments.PatientAppointmentStore;
import com.aurevia.messaging.appointments.PushGateway;
import com.aurevia.messaging.appointments.VerifiedPatientLink;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * MSG-05 clause 6 evidence: the only generic push payload in the repository is
 * dispatched unchanged and clinical detail is reachable only after an
 * authenticated, verified subject is bound.
 *
 * <p>The generic notice and the authentication gates live in the patient
 * appointment slice (PAT-01, OPS-02), not in the message/instruction flow of
 * MSG-05. These tests document that slice honestly; the clause document records
 * what MSG-05 itself still lacks. Synthetic ports and headers only, no patient
 * data, no production changes.</p>
 */
class UseGenericPushNotificationsRetrieveFullContentTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-a";
    private static final String PATIENT = "patient-1";
    private static final String APPOINTMENT = "appt-1";
    private static final String TOKEN = "synthetic-token";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private VerifiedPatientLink links;
    private PatientAppointmentStore store;
    private DeviceTokenStore devices;
    private PushGateway gateway;
    private AuditSink audit;
    private PatientAppointmentService service;
    private MockMvc mvc;

    /**
     * Build a service over mocked ports to capture the dispatched notice, and an
     * independent HTTP route over the public controller constructor with empty
     * synthetic ports to exercise the authentication guards.
     */
    @BeforeEach
    void setUp() {
        links = mock(VerifiedPatientLink.class);
        store = mock(PatientAppointmentStore.class);
        devices = mock(DeviceTokenStore.class);
        gateway = mock(PushGateway.class);
        audit = mock(AuditSink.class);
        service = new PatientAppointmentService(links, store, devices, gateway, audit);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new PatientAppointmentController(clock))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the thread-local tenant so one request cannot authenticate another. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Seed a synthetic booked appointment carrying representative clinical detail. */
    private static PatientAppointment appointment() {
        return new PatientAppointment(APPOINTMENT, 1, PATIENT, "MAIN", "Asia/Riyadh",
                Instant.parse("2026-12-01T07:00:00Z"), AppointmentStatus.BOOKED, true, null);
    }

    /** Seed a synthetic active device so dispatch actually reaches the gateway. */
    private static DeviceToken activeToken() {
        return new DeviceToken("device-1", TOKEN, SUBJECT, PATIENT, DeviceState.ACTIVE);
    }

    /**
     * Rule met: confirmation and status notification both hand the gateway the
     * single fixed template, which reveals none of the seeded appointment detail.
     */
    @Test
    void genericAppointmentUpdateCarriesNoClinicalDetail() {
        when(links.allows(SUBJECT, PATIENT)).thenReturn(true);
        when(store.find(APPOINTMENT, PATIENT)).thenReturn(Optional.of(appointment()));
        when(store.confirm(APPOINTMENT, PATIENT, 1, NOW)).thenReturn(1);
        when(devices.active(PATIENT)).thenReturn(List.of(activeToken()));
        when(gateway.send(anyString(), any(GenericPushNotice.class)))
                .thenReturn(PushGateway.Outcome.DELIVERED);

        service.confirm(SUBJECT, PATIENT, APPOINTMENT, 1, NOW);
        service.notifyStatusChange(SUBJECT, PATIENT, APPOINTMENT, AppointmentStatus.CHECKED_IN, NOW);

        ArgumentCaptor<GenericPushNotice> notices = ArgumentCaptor.forClass(GenericPushNotice.class);
        verify(gateway, times(2)).send(eq(TOKEN), notices.capture());
        for (GenericPushNotice notice : notices.getAllValues()) {
            assertEquals(GenericPushNotice.appointmentUpdate(), notice);
            assertEquals(GenericPushNotice.GENERIC_TITLE, notice.title());
            assertEquals(GenericPushNotice.GENERIC_BODY, notice.body());
            assertEquals(GenericPushNotice.GENERIC_LINK, notice.deepLink());
            assertFalse(notice.reveals(PATIENT));
            assertFalse(notice.reveals("MAIN"));
            assertFalse(notice.reveals("Dr A"));
            assertFalse(notice.reveals("2026-12-01T07:00:00Z"));
            assertFalse(notice.reveals(AppointmentStatus.CHECKED_IN.name()));
            assertFalse(notice.reveals(APPOINTMENT));
            assertFalse(notice.body().matches(".*\\d.*"));
            assertFalse(notice.deepLink().contains(APPOINTMENT));
        }
    }

    /**
     * Negative: without a synthetic subject the interceptor rejects the request
     * with 401 UNAUTHENTICATED in preHandle, before the controller or any store
     * runs. Both a content read and a content-changing command are refused.
     */
    @Test
    void unauthenticatedContentRetrievalIsRejectedBeforeAnyRead() throws Exception {
        mvc.perform(get("/api/v1/patient/appointments")
                        .param("patientId", PATIENT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNull(TenantContext.current());

        mvc.perform(post("/api/v1/patient/appointments/{id}/notify", APPOINTMENT)
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"" + PATIENT + "\",\"status\":\"CHECKED_IN\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        assertNull(TenantContext.current());
    }

    /**
     * Negative: an authenticated subject with no verified patient link is refused
     * full content with 403 FORBIDDEN, an audit DENIED fact is written and no
     * appointment or push boundary is read or invoked.
     */
    @Test
    void unlinkedSubjectIsDeniedFullContent() throws Exception {
        mvc.perform(get("/api/v1/patient/appointments")
                        .param("patientId", PATIENT)
                        .header("X-Synthetic-Subject", "unlinked")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        when(links.allows("unlinked", PATIENT)).thenReturn(false);
        ApiException error = assertThrows(ApiException.class,
                () -> service.listAppointments("unlinked", PATIENT, NOW));
        assertEquals(403, error.status());
        assertEquals("FORBIDDEN", error.code());
        verify(audit).append("unlinked", PATIENT, "PATIENT_APPOINTMENT_LIST", "DENIED", NOW);
        verify(store, never()).forPatient(any());
        verify(gateway, never()).send(any(), any());
    }
}
