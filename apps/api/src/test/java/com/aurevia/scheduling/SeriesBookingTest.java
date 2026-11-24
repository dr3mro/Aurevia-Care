package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.time.Instant;
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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-01 series booking unit acceptance: facility-local recurrence, clash
 * reporting and the all-or-nothing transaction boundary.
 */
class SeriesBookingTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "reception-a";
    private static final String PATIENT = "22222222-2222-2222-2222-222222222222";
    private static final String APP = "33333333-3333-3333-3333-333333333333";
    private static final String FIRST = "2027-03-07T14:00:00Z";
    private static final Instant SECOND = Instant.parse("2027-03-14T13:00:00Z");
    private static final Instant THIRD = Instant.parse("2027-03-21T13:00:00Z");

    private AppointmentRepository appointments;
    private SchedulingRepository scheduling;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentRepository.class);
        scheduling = mock(SchedulingRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new AppointmentService(appointments, scheduling, new ActionPolicy(), transactions,
                mock(AuditAppender.class),
                Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "SCHEDULING", "corr-1"));
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        when(scheduling.facility("MAIN")).thenReturn(Optional.of(new FacilityInfo("f1", "MAIN",
                "America/New_York")));
        when(scheduling.visitType("NEW")).thenReturn(Optional.of(new VisitTypeView("NEW", 30)));
        when(appointments.patientExists(PATIENT)).thenReturn(true);
        when(scheduling.resource("f1", "clinician", "DR-A"))
                .thenReturn(Optional.of(new ResourceView("r-c", "MAIN", "clinician", "DR-A", "Dr A")));
        when(scheduling.resource("f1", "room", "ROOM-1"))
                .thenReturn(Optional.of(new ResourceView("r-r", "MAIN", "room", "ROOM-1", "Room 1")));
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
        when(scheduling.withinAvailability(anyString(), anyString(), any(), any())).thenReturn(true);
        when(appointments.find(anyString())).thenReturn(Optional.of(booked()));
        when(appointments.findConflicts(anyString(), anyString(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Appointment booked() {
        return new Appointment(APP, 1, PATIENT, "MAIN", "DR-A", "ROOM-1", "NEW",
                Instant.parse(FIRST), Instant.parse(FIRST).plusSeconds(1800), AppointmentState.BOOKED,
                null, null, null);
    }

    private static SeriesRequest request(Integer count, String endDate) {
        return new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", "ROOM-1", FIRST, null, 1, count,
                endDate);
    }

    @Test
    void booksTheSeriesAtTheFacilityLocalClockTimeAcrossDaylightSaving() {
        List<Appointment> series = service.bookSeries(request(3, null));
        assertEquals(3, series.size());
        ArgumentCaptor<Instant> starts = ArgumentCaptor.forClass(Instant.class);
        verify(appointments, times(3)).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), starts.capture(), any(), any(), any(), any(),
                any());
        assertEquals(List.of(Instant.parse(FIRST), SECOND, THIRD), starts.getAllValues());
        verify(appointments).insertRecurrenceTemplate(anyString(), eq(1), eq(3), isNull(),
                anyString(), anyString());
        verify(appointments, times(3)).insertSeriesOccurrence(anyString(), anyString(), anyString(),
                anyInt(), any());
    }

    @Test
    void booksAnEndDatedRecurrence() {
        List<Appointment> series = service.bookSeries(request(null, "2027-03-14"));
        assertEquals(2, series.size());
        verify(appointments).insertRecurrenceTemplate(anyString(), eq(1), isNull(),
                eq("2027-03-14"), anyString(), anyString());
    }

    @Test
    void rejectsAConflictOnTheThirdOccurrenceAndBooksNothing() {
        when(appointments.findConflicts(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Instant start = invocation.getArgument(2);
            return THIRD.equals(start) ? List.of("taken-3") : List.of();
        });
        SeriesConflictException failure = assertThrows(SeriesConflictException.class,
                () -> service.bookSeries(request(3, null)));
        assertEquals(409, failure.status());
        assertEquals("SERIES_CONFLICT", failure.code());
        assertEquals(1, failure.clashes().size());
        assertEquals(3, failure.clashes().get(0).occurrence());
        assertEquals("taken-3", failure.clashes().get(0).appointmentId());
        verify(appointments, never()).insert(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
        verify(appointments, never()).insertAppointmentSeries(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void reportsEveryClashingOccurrence() {
        when(appointments.findConflicts(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Instant start = invocation.getArgument(2);
            if (Instant.parse(FIRST).equals(start)) {
                return List.of("taken-1");
            }
            return THIRD.equals(start) ? List.of("taken-3") : List.of();
        });
        SeriesConflictException failure = assertThrows(SeriesConflictException.class,
                () -> service.bookSeries(request(3, null)));
        assertEquals(2, failure.clashes().size());
        assertEquals(1, failure.clashes().get(0).occurrence());
        assertEquals(3, failure.clashes().get(1).occurrence());
    }

    @Test
    void rejectsBothTerminationsSuppliedTogether() {
        ApiException failure = assertThrows(ApiException.class,
                () -> service.bookSeries(request(3, "2027-03-21")));
        assertEquals(422, failure.status());
        assertEquals("INVALID_OCCURRENCES", failure.code());
    }

    @Test
    void treatsABlankEndDateAsACountBasedSeriesWithoutARoom() {
        SeriesRequest request = new SeriesRequest("MAIN", PATIENT, "NEW", "DR-A", null,
                FIRST, null, 1, 3, "  ");
        assertEquals(3, service.bookSeries(request).size());
        verify(appointments).insertRecurrenceTemplate(anyString(), eq(1), eq(3), isNull(),
                anyString(), anyString());
    }

    @Test
    void rejectsAnUnknownFacilityOrVisitTypeForASeries() {
        SeriesRequest noFacility = new SeriesRequest("MISSING", PATIENT, "NEW", "DR-A",
                "ROOM-1", FIRST, null, 1, 2, null);
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.bookSeries(noFacility)).status());
        SeriesRequest noVisitType = new SeriesRequest("MAIN", PATIENT, "NOPE", "DR-A",
                "ROOM-1", FIRST, null, 1, 2, null);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.bookSeries(noVisitType)).status());
    }
}
