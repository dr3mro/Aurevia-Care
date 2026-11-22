package com.aurevia.smartclinic.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.authz.ActionPolicy;
import com.aurevia.smartclinic.scheduling.Appointment;
import com.aurevia.smartclinic.scheduling.AppointmentRepository;
import com.aurevia.smartclinic.scheduling.AppointmentService;
import com.aurevia.smartclinic.scheduling.AppointmentState;
import com.aurevia.smartclinic.scheduling.BookingRequest;
import com.aurevia.smartclinic.scheduling.BookingRules;
import com.aurevia.smartclinic.scheduling.FacilityInfo;
import com.aurevia.smartclinic.scheduling.ResourceView;
import com.aurevia.smartclinic.scheduling.SchedulingRepository;
import com.aurevia.smartclinic.scheduling.SeriesRequest;
import com.aurevia.smartclinic.scheduling.VisitTypeView;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * CLN-01 clause 1 (unit acceptance): the scheduling model carries the facility's
 * civil time zone, clinician and room resources with local availability windows,
 * the duration owned by the visit type, a facility-local recurrence template,
 * temporary holds and tenant-approved booking rules. Commands that break those
 * modelled rules must be refused without writing an appointment.
 *
 * <p>The fixtures are synthetic and the repositories are mocked at the same
 * boundary as {@code AppointmentServiceTest}; the HTTP acceptance is
 * {@code com.aurevia.smartclinic.it.SchedulingBookingTest}.</p>
 */
class ModelFacilityTimeZoneClinicianRoomResourceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clause-reception";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY_ID = "f-main";
    private static final String FACILITY = "MAIN";
    private static final String ZONE = "America/New_York";
    private static final String VISIT_TYPE = "NEW";
    private static final int DURATION_MINUTES = 45;
    private static final String CLINICIAN_ID = "r-clinician";
    private static final String ROOM_ID = "r-room";
    private static final String START = "2027-03-07T14:00:00Z";
    private static final Instant START_INSTANT = Instant.parse(START);
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private AppointmentRepository appointments;
    private SchedulingRepository scheduling;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentRepository.class);
        scheduling = mock(SchedulingRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new AppointmentService(appointments, scheduling, new ActionPolicy(), transactions,
                mock(AuditAppender.class), Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "SCHEDULING", "corr-clause"));

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        when(scheduling.facility(FACILITY)).thenReturn(Optional.of(new FacilityInfo(FACILITY_ID, FACILITY, ZONE)));
        when(scheduling.visitType(VISIT_TYPE)).thenReturn(Optional.of(new VisitTypeView(VISIT_TYPE, DURATION_MINUTES)));
        when(appointments.patientExists(PATIENT)).thenReturn(true);
        when(scheduling.resource(FACILITY_ID, "clinician", "DR-A")).thenReturn(
                Optional.of(new ResourceView(CLINICIAN_ID, FACILITY, "clinician", "DR-A", "Dr A")));
        when(scheduling.resource(FACILITY_ID, "room", "ROOM-1")).thenReturn(
                Optional.of(new ResourceView(ROOM_ID, FACILITY, "room", "ROOM-1", "Room 1")));
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
        when(scheduling.withinAvailability(anyString(), anyString(), any(), any())).thenReturn(true);
        when(appointments.find(anyString())).thenReturn(Optional.of(stored()));
        when(appointments.findConflicts(anyString(), any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Appointment stored() {
        return new Appointment("33333333-3333-3333-3333-333333333333", 1, PATIENT, FACILITY, "DR-A",
                "ROOM-1", VISIT_TYPE, START_INSTANT, START_INSTANT.plus(Duration.ofMinutes(DURATION_MINUTES)),
                AppointmentState.BOOKED, null, null, null);
    }

    private static BookingRequest request(String startsAt) {
        return new BookingRequest(FACILITY, PATIENT, VISIT_TYPE, "DR-A", "ROOM-1", startsAt, null);
    }

    private void assertNoAppointmentWasInserted() {
        verify(appointments, never()).insert(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void holdUsesTheFacilityZoneBothResourcesVisitDurationAndTheConfiguredHoldMinutes() {
        when(scheduling.setting(BookingRules.HOLD_MINUTES_KEY)).thenReturn(Optional.of("20"));
        Instant expectedEnd = START_INSTANT.plus(Duration.ofMinutes(DURATION_MINUTES));

        service.hold(request(START));

        // The facility's IANA zone is the civil clock applied to both resource windows.
        verify(scheduling).withinAvailability(CLINICIAN_ID, ZONE, START_INSTANT, expectedEnd);
        verify(scheduling).withinAvailability(ROOM_ID, ZONE, START_INSTANT, expectedEnd);
        // The visit type owns the slot length; the approved rule owns the hold lifetime.
        ArgumentCaptor<Instant> ends = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expires = ArgumentCaptor.forClass(Instant.class);
        verify(appointments).insert(eq(ORG), anyString(), eq(PATIENT), eq(FACILITY_ID),
                eq(CLINICIAN_ID), eq(ROOM_ID), eq(VISIT_TYPE), eq(START_INSTANT), ends.capture(),
                eq(AppointmentState.HELD), isNull(), expires.capture(), isNull());
        assertEquals(expectedEnd, ends.getValue());
        assertEquals(NOW.plus(Duration.ofMinutes(20)), expires.getValue());
    }

    @Test
    void recurrenceKeepsTheFacilityLocalClockTimeAcrossDaylightSaving() {
        List<Appointment> series = service.bookSeries(new SeriesRequest(FACILITY, PATIENT, VISIT_TYPE,
                "DR-A", "ROOM-1", START, null, 1, 3, null));

        assertEquals(3, series.size());
        ArgumentCaptor<Instant> starts = ArgumentCaptor.forClass(Instant.class);
        verify(appointments, times(3)).insert(eq(ORG), anyString(), eq(PATIENT), eq(FACILITY_ID),
                eq(CLINICIAN_ID), eq(ROOM_ID), eq(VISIT_TYPE), starts.capture(), any(),
                eq(AppointmentState.BOOKED), anyString(), isNull(), isNull());
        assertEquals(List.of(Instant.parse("2027-03-07T14:00:00Z"),
                Instant.parse("2027-03-14T13:00:00Z"),
                Instant.parse("2027-03-21T13:00:00Z")), starts.getAllValues());
        verify(appointments).insertRecurrenceTemplate(anyString(), eq(1), eq(3), isNull(),
                eq(LocalTime.of(9, 0).toString()), eq(ZONE));
        assertTrue(starts.getAllValues().stream()
                .allMatch(start -> start.atZone(ZoneId.of(ZONE)).toLocalTime().equals(LocalTime.of(9, 0))));
    }

    @Test
    void refusesASlotOutsideTheClinicianAvailabilityWindow() {
        when(scheduling.withinAvailability(eq(CLINICIAN_ID), eq(ZONE), any(), any())).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.book(request(START)));

        assertEquals(422, failure.status());
        assertEquals("OUTSIDE_AVAILABILITY", failure.code());
        assertNoAppointmentWasInserted();
    }

    @Test
    void refusesASlotOutsideTheRoomAvailabilityWindow() {
        when(scheduling.withinAvailability(eq(ROOM_ID), eq(ZONE), any(), any())).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.book(request(START)));

        assertEquals(422, failure.status());
        assertEquals("OUTSIDE_AVAILABILITY", failure.code());
        assertNoAppointmentWasInserted();
    }

    @Test
    void refusesASlotInsideTheConfiguredMinimumNotice() {
        when(scheduling.setting(BookingRules.MIN_NOTICE_KEY)).thenReturn(Optional.of("120"));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.book(request("2026-11-19T01:00:00Z")));

        assertEquals(422, failure.status());
        assertEquals("MIN_NOTICE_NOT_MET", failure.code());
        assertNoAppointmentWasInserted();
    }

    @Test
    void refusesAFacilityWithAnInvalidTimeZone() {
        when(scheduling.facility(FACILITY)).thenReturn(
                Optional.of(new FacilityInfo(FACILITY_ID, FACILITY, "Not/AZone")));

        ApiException failure = assertThrows(ApiException.class, () -> service.book(request(START)));

        assertEquals(422, failure.status());
        assertEquals("INVALID_TIME_ZONE", failure.code());
        assertNoAppointmentWasInserted();
    }
}
