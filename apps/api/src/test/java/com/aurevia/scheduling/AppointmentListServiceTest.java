package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/** ARC-03 cursor paging on the appointment list: validation, authorization and page boundaries. */
class AppointmentListServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";
    private static final String FROM = "2027-01-01T00:00:00Z";
    private static final String TO = "2027-01-31T00:00:00Z";
    private static final String ID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String ID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ID_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant START_A = Instant.parse("2027-01-05T09:00:00Z");
    private static final Instant START_B = Instant.parse("2027-01-06T09:00:00Z");
    private static final Instant START_C = Instant.parse("2027-01-07T09:00:00Z");

    private AppointmentRepository appointments;
    private SchedulingRepository scheduling;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentRepository.class);
        scheduling = mock(SchedulingRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        service = new AppointmentService(appointments, scheduling, new ActionPolicy(), transactions,
                audit, Clock.systemUTC());
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "SCHEDULING", "corr-1"));
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
        when(appointments.history(anyString())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Appointment appointment(String id, Instant start) {
        return new Appointment(id, 1, "patient-1", "MAIN", "DR-A", "ROOM-1", "NEW", start,
                start.plus(Duration.ofMinutes(30)), AppointmentState.BOOKED, null, null, null);
    }

    private static AppointmentRepository.AppointmentRow row(String id, Instant start) {
        return new AppointmentRepository.AppointmentRow(appointment(id, start), id);
    }

    private ApiException failure(String cursor, String limit, String from, String to,
                                 String resource, String state) {
        return assertThrows(ApiException.class,
                () -> service.list(cursor, limit, from, to, resource, state));
    }

    @Test
    void absentAndBlankOptionalParametersUseDefaults() {
        service.list(null, null, FROM, TO, null, null);
        verify(appointments).query(Instant.parse(FROM), Instant.parse(TO), null, null, null, null, 51);

        service.list("  ", "  ", FROM, TO, "  ", "  ");
        verify(appointments, org.mockito.Mockito.times(2))
                .query(Instant.parse(FROM), Instant.parse(TO), null, null, null, null, 51);
    }

    @Test
    void invalidCursorsAreRejectedBeforeAnyQuery() {
        for (String cursor : List.of("plain", "a:b:c", ":id", "subject:",
                "2027-01-01T00:00:00Z:not-a-uuid", "not-a-date:" + ID_A,
                "2027-01-01T00:00:00Z:" + ID_C + "x")) {
            ApiException exception = failure(cursor, null, FROM, TO, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_CURSOR", exception.code());
        }
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void invalidLimitsAreRejectedBeforeAnyQuery() {
        for (String limit : List.of("0", "201", "abc")) {
            ApiException exception = failure(null, limit, FROM, TO, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_LIMIT", exception.code());
        }
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void missingRangeIsRejected() {
        for (String[] range : new String[][] { { null, TO }, { "  ", TO }, { FROM, null }, { FROM, "  " } }) {
            ApiException exception = failure(null, null, range[0], range[1], null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_RANGE", exception.code());
        }
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void malformedRangeIsRejected() {
        assertEquals("INVALID_RANGE", failure(null, null, "not-a-date", TO, null, null).code());
        assertEquals("INVALID_RANGE", failure(null, null, FROM, "also-bad", null, null).code());
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void invalidRangeOrderingIsRejected() {
        String start = "2027-01-01T00:00:00Z";
        for (String end : List.of(start, "2026-12-31T00:00:00Z", "2027-02-02T00:00:00Z")) {
            ApiException exception = failure(null, null, start, end, null, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_RANGE", exception.code());
        }
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void maximumThirtyOneDayRangeIsAccepted() {
        service.list(null, null, FROM, "2027-02-01T00:00:00Z", null, null);
        verify(appointments).query(Instant.parse(FROM), Instant.parse("2027-02-01T00:00:00Z"),
                null, null, null, null, 51);
    }

    @Test
    void invalidResourceIsRejectedBeforeAnyQuery() {
        for (String resource : List.of("bad", "X")) {
            ApiException exception = failure(null, null, FROM, TO, resource, null);
            assertEquals(422, exception.status());
            assertEquals("INVALID_RESOURCE", exception.code());
        }
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void validResourceAndStateArePassedThrough() {
        service.list(null, null, FROM, TO, "DR-A", "booked");
        verify(appointments).query(Instant.parse(FROM), Instant.parse(TO), "DR-A",
                AppointmentState.BOOKED, null, null, 51);
    }

    @Test
    void invalidStateIsRejectedBeforeAnyQuery() {
        ApiException exception = failure(null, null, FROM, TO, null, "unknown");
        assertEquals(422, exception.status());
        assertEquals("INVALID_STATE", exception.code());
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void emptyPageHasNoNextCursor() {
        when(appointments.query(Instant.parse(FROM), Instant.parse(TO), null, null, null, null, 51))
                .thenReturn(List.of());
        AppointmentPage page = service.list(null, null, FROM, TO, null, null);
        assertTrue(page.items().isEmpty());
        assertNull(page.nextCursor());
        verify(audit).append(TenantContext.current(), ActionPolicy.APPOINTMENT_READ,
                ActionPolicy.APPOINTMENT_READ, "APPOINTMENT", "SCHEDULING", "ALL", "SUCCESS");
    }

    @Test
    void exactlyLimitRowsHaveNoNextCursor() {
        when(appointments.query(Instant.parse(FROM), Instant.parse(TO), null, null, null, null, 3))
                .thenReturn(List.of(row(ID_A, START_A), row(ID_B, START_B)));
        AppointmentPage page = service.list(null, "2", FROM, TO, null, null);
        assertEquals(List.of(ID_A, ID_B), page.items().stream().map(item -> item.appointment().id()).toList());
        assertNull(page.nextCursor());
    }

    @Test
    void extraRowProducesCursorFromTheLastReturnedRow() {
        String cursor = START_A + ":" + ID_A;
        when(appointments.query(Instant.parse(FROM), Instant.parse(TO), null, null, START_A, ID_A, 3))
                .thenReturn(List.of(row(ID_A, START_A), row(ID_B, START_B), row(ID_C, START_C)));
        AppointmentPage page = service.list(cursor, "2", FROM, TO, null, null);
        assertEquals(List.of(ID_A, ID_B), page.items().stream().map(item -> item.appointment().id()).toList());
        assertEquals(START_B + ":" + ID_B, page.nextCursor());
        verify(appointments).history(ID_A);
        verify(appointments).history(ID_B);
        verify(appointments, never()).history(ID_C);
    }

    @Test
    void inactiveMembershipIsDeniedAndAudited() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        ApiException exception = failure(null, null, FROM, TO, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), ActionPolicy.APPOINTMENT_READ,
                ActionPolicy.APPOINTMENT_READ, "APPOINTMENT", "SCHEDULING", "ALL", "DENIED");
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void missingPermissionIsDeniedAndAudited() {
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("privacy_auditor"));
        ApiException exception = failure(null, null, FROM, TO, null, null);
        assertEquals(403, exception.status());
        verify(audit).append(TenantContext.current(), ActionPolicy.APPOINTMENT_READ,
                ActionPolicy.APPOINTMENT_READ, "APPOINTMENT", "SCHEDULING", "ALL", "DENIED");
        verify(appointments, never()).query(any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void pageAndRowFreezeTheirPartsAndExposeValueSemantics() {
        Appointment appointment = appointment(ID_A, START_A);
        List<AppointmentView> mutable = new ArrayList<>(
                List.of(new AppointmentView(appointment, List.of())));
        AppointmentPage page = new AppointmentPage(mutable, START_A + ":" + ID_A);
        mutable.clear();
        assertEquals(1, page.items().size());
        assertEquals(START_A + ":" + ID_A, page.nextCursor());
        assertThrows(UnsupportedOperationException.class, () -> page.items().clear());

        AppointmentPage equal = new AppointmentPage(
                List.of(new AppointmentView(appointment, List.of())), START_A + ":" + ID_A);
        assertEquals(page, equal);
        assertEquals(page.hashCode(), equal.hashCode());
        assertEquals(page.toString(), equal.toString());

        AppointmentRepository.AppointmentRow first = row(ID_A, START_A);
        assertEquals(ID_A, first.appointment().id());
        assertEquals(ID_A, first.id());
        assertEquals(first, row(ID_A, START_A));
        assertEquals(first.hashCode(), row(ID_A, START_A).hashCode());
        assertEquals(first.toString(), row(ID_A, START_A).toString());
    }
}
