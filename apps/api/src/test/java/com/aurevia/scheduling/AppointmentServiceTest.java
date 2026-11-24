package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/** S-G1-01 service branches: authorization, reference resolution, rules and version guards. */
class AppointmentServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "reception-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String START = "2026-12-07T07:00:00Z";
    private static final String APP = "33333333-3333-3333-3333-333333333333";

    private AppointmentRepository appointments;
    private SchedulingRepository scheduling;
    private TransactionTemplate transactions;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentRepository.class);
        scheduling = mock(SchedulingRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new AppointmentService(appointments, scheduling, new ActionPolicy(), transactions,
                mock(AuditAppender.class),
                Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "SCHEDULING", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("reception"));
    }

    private void referenceData() {
        when(scheduling.facility("MAIN")).thenReturn(Optional.of(new FacilityInfo("f1", "MAIN", "UTC")));
        when(scheduling.visitType("NEW")).thenReturn(Optional.of(new VisitTypeView("NEW", 30)));
        when(appointments.patientExists(PATIENT)).thenReturn(true);
        when(scheduling.resource("f1", "clinician", "DR-A"))
                .thenReturn(Optional.of(new ResourceView("r-c", "MAIN", "clinician", "DR-A", "Dr A")));
        when(scheduling.resource("f1", "room", "ROOM-1"))
                .thenReturn(Optional.of(new ResourceView("r-r", "MAIN", "room", "ROOM-1", "Room 1")));
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
        when(scheduling.withinAvailability(anyString(), anyString(), any(), any())).thenReturn(true);
        when(appointments.find(anyString())).thenReturn(Optional.of(booked()));
    }

    private static BookingRequest request(String facility, String visitType, String clinician,
                                          String room, String start, String override) {
        return new BookingRequest(facility, PATIENT, visitType, clinician, room, start, override);
    }

    private static BookingRequest request() {
        return request("MAIN", "NEW", "DR-A", "ROOM-1", START, null);
    }

    private static Appointment booked() {
        return new Appointment(APP, 1, PATIENT, "MAIN", "DR-A", "ROOM-1", "NEW",
                Instant.parse(START), Instant.parse(START).plusSeconds(1800), AppointmentState.BOOKED,
                null, null, null);
    }

    private static Appointment appointment(AppointmentState state, String roomCode, String clinicianCode) {
        return new Appointment(APP, 1, PATIENT, "MAIN", clinicianCode, roomCode, "NEW",
                Instant.parse(START), Instant.parse(START).plusSeconds(1800), state, null, null, null);
    }

    @Test
    void booksInsideAvailabilityAndAudits() {
        authorized();
        referenceData();
        Appointment created = service.book(request());
        assertEquals(AppointmentState.BOOKED, created.state());
        verify(appointments).appendEvent(anyString(), eq(1), eq("booked"), eq(null),
                eq(AppointmentState.BOOKED), eq(null), eq(null), eq(null));
    }

    @Test
    void createsAHoldWithAnExpiry() {
        authorized();
        referenceData();
        Appointment held = service.hold(request());
        assertEquals(AppointmentState.BOOKED, held.state(), "the stub returns the stored projection");
        verify(appointments).appendEvent(anyString(), eq(1), eq("held"), eq(null),
                eq(AppointmentState.HELD), eq(null), eq(null), eq(null));
        verify(appointments).insert(eq(ORG), anyString(), eq(PATIENT), eq("f1"), eq("r-c"), eq("r-r"),
                eq("NEW"), any(), any(), eq(AppointmentState.HELD), eq(null), any(), eq(null));
    }

    @Test
    void rejectsUnknownReferenceData() {
        authorized();
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
        when(appointments.patientExists(PATIENT)).thenReturn(true);
        when(scheduling.facility("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.book(request())).status());

        referenceData();
        when(scheduling.facility("MAIN")).thenReturn(Optional.of(new FacilityInfo("f1", "MAIN", "UTC")));
        when(scheduling.visitType("NEW")).thenReturn(Optional.empty());
        assertEquals(422, assertThrows(ApiException.class, () -> service.book(request())).status());

        referenceData();
        when(appointments.patientExists(PATIENT)).thenReturn(false);
        assertEquals(404, assertThrows(ApiException.class, () -> service.book(request())).status());

        referenceData();
        when(scheduling.resource("f1", "clinician", "DR-A")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.book(request())).status());

        referenceData();
        when(scheduling.resource("f1", "room", "ROOM-1")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.book(request())).status());
    }

    @Test
    void deniesBookingWithoutMembershipOrPermission() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.book(request())).status());

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("privacy_auditor"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.book(request())).status());
        verify(appointments, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), anyString(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void enforcesAvailabilityAndRecordedOverride() {
        authorized();
        referenceData();
        when(scheduling.withinAvailability(anyString(), anyString(), any(), any())).thenReturn(false);
        assertEquals("OUTSIDE_AVAILABILITY", assertThrows(ApiException.class,
                () -> service.book(request())).code());

        Appointment overridden = service.book(request("MAIN", "NEW", "DR-A", "ROOM-1", START, "urgent"));
        assertEquals(AppointmentState.BOOKED, overridden.state());

        // A clinician-only appointment never asks the room window.
        referenceData();
        when(scheduling.withinAvailability(eq("r-c"), anyString(), any(), any())).thenReturn(false);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.book(request("MAIN", "NEW", "DR-A", null, START, null))).status());
    }

    @Test
    void enforcesConfiguredBookingRules() {
        authorized();
        referenceData();
        when(scheduling.setting(BookingRules.MIN_NOTICE_KEY)).thenReturn(Optional.of("120"));
        assertEquals("MIN_NOTICE_NOT_MET", assertThrows(ApiException.class,
                () -> service.book(request("MAIN", "NEW", "DR-A", "ROOM-1",
                        "2026-11-19T01:00:00Z", null))).code());

        referenceData();
        when(scheduling.setting(BookingRules.MAX_ADVANCE_KEY)).thenReturn(Optional.of("5"));
        assertEquals("MAX_ADVANCE_EXCEEDED", assertThrows(ApiException.class,
                () -> service.book(request())).code());
    }

    @Test
    void mapsASlotConflictTo409() {
        authorized();
        referenceData();
        when(appointments.insert(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("slot",
                        new SQLException("exclusion", "23P01")));
        ApiException conflict = assertThrows(ApiException.class, () -> service.book(request()));
        assertEquals(409, conflict.status());
        assertEquals("SLOT_CONFLICT", conflict.code());
    }

    @Test
    void createsASeriesAtomically() {
        authorized();
        referenceData();
        List<Appointment> series = service.bookSeries(new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A",
                "ROOM-1", START, null, 1, 2));
        assertEquals(2, series.size());
        verify(appointments, org.mockito.Mockito.times(2)).insert(anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void rejectsAnUnauthorizedOrMalformedSeries() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.bookSeries(
                new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", "ROOM-1", START, null, 1, 2)))
                .status());

        assertEquals(422, assertThrows(ApiException.class, () -> service.bookSeries(
                new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", "ROOM-1", START, null, 0, 2)))
                .status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.bookSeries(
                new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", "ROOM-1", START, null, 1, 53)))
                .status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.bookSeries(
                new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", "ROOM-1", START, null, null, 2)))
                .status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.bookSeries(
                new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", "ROOM-1", START, null, 1, null)))
                .status());
    }

    @Test
    void readsAnAppointmentAndDeniesWithoutPermission() {
        authorized();
        referenceData();
        when(appointments.history(APP)).thenReturn(List.of());
        assertEquals(0, service.get(APP).history().size());
        assertEquals(AppointmentState.BOOKED, service.get(APP).appointment().state());

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(APP)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.get("not-a-uuid")).status());
    }

    @Test
    void movesThroughTheStateMachine() {
        authorized();
        referenceData();
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(booked()));
        when(appointments.updateState(APP, 1, AppointmentState.CHECKED_IN)).thenReturn(1);

        assertEquals(AppointmentState.BOOKED, service.checkIn(APP, 1).state());
        verify(appointments).appendEvent(eq(APP), eq(2), eq("checked_in"),
                eq(AppointmentState.BOOKED), eq(AppointmentState.CHECKED_IN), eq(null), eq(null), eq(null));
    }

    @Test
    void rejectsStaleIllegalAndMissingTransitions() {
        authorized();
        referenceData();
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(booked()));

        assertEquals(412, assertThrows(ApiException.class, () -> service.checkIn(APP, 7)).status());

        assertEquals(422, assertThrows(ApiException.class, () -> service.cancel(APP, 1, null)).status());

        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(
                appointment(AppointmentState.IN_PROGRESS, "ROOM-1", "DR-A")));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.cancel(APP, 1, "too late")).status());

        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(booked()));
        when(appointments.updateState(APP, 1, AppointmentState.CHECKED_IN)).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.checkIn(APP, 1)).status());

        when(appointments.loadForUpdate(APP)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.checkIn(APP, 1)).status());

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.checkIn(APP, 1)).status());
    }

    @Test
    void reschedulesKeepingOrReplacingResources() {
        authorized();
        referenceData();
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(appointment(
                AppointmentState.BOOKED, null, "DR-A")));
        when(appointments.updateSlot(anyString(), anyInt(), anyString(), any(), any(), any(), any()))
                .thenReturn(1);
        assertEquals(AppointmentState.BOOKED, service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1).state());

        authorized();
        referenceData();
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(booked()));
        assertEquals(AppointmentState.BOOKED, service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", "DR-A", "ROOM-1", "clinic moved"), 1)
                .state());
    }

    @Test
    void rejectsRescheduleFailures() {
        authorized();
        referenceData();
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(appointment(
                AppointmentState.CANCELLED, "ROOM-1", "DR-A")));
        assertEquals(409, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());

        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(booked()));
        assertEquals(412, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 7)).status());

        when(scheduling.facility("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());

        referenceData();
        when(scheduling.visitType("NEW")).thenReturn(Optional.empty());
        assertEquals(422, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());

        referenceData();
        when(appointments.updateSlot(anyString(), anyInt(), anyString(), any(), any(), any(), any()))
                .thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());

        referenceData();
        when(appointments.updateSlot(anyString(), anyInt(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("slot",
                        new SQLException("exclusion", "23P01")));
        assertEquals(409, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());
    }

    @Test
    void concealsAnAppointmentMissingFromTheActiveTenant() {
        authorized();
        referenceData();
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.reschedule(APP,
                new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).status());
    }

    @Test
    void requiresTheRoomWindowAsWellAsTheClinicianWindow() {
        authorized();
        referenceData();
        when(scheduling.withinAvailability(eq("r-c"), anyString(), any(), any())).thenReturn(true);
        when(scheduling.withinAvailability(eq("r-r"), anyString(), any(), any())).thenReturn(false);
        assertEquals("OUTSIDE_AVAILABILITY", assertThrows(ApiException.class,
                () -> service.book(request())).code());
        // The same room constraint applies when the slot moves.
        when(appointments.loadForUpdate(APP)).thenReturn(Optional.of(booked()));
        assertEquals("OUTSIDE_AVAILABILITY", assertThrows(ApiException.class,
                () -> service.reschedule(APP,
                        new RescheduleRequest("2026-12-07T08:00:00Z", null, null, null), 1)).code());
    }
}
