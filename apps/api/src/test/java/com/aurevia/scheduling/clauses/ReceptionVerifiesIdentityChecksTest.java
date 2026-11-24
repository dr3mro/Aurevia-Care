package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.AppointmentState;
import com.aurevia.scheduling.AppointmentView;
import com.aurevia.scheduling.SchedulingRepository;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.scheduling.checkin.CheckInService;
import com.aurevia.scheduling.checkin.Verification;
import com.aurevia.scheduling.checkin.WorklistItem;
import com.aurevia.scheduling.checkin.WorklistPolicy;
import com.aurevia.scheduling.checkin.WorklistRepository;
import com.aurevia.tenant.TenantContext;

/**
 * CLN-02 clause 1 proof: reception confirms a patient's identity and then checks
 * the patient in. The positive case walks both commands through the shared
 * service and asserts the evidence, the checked-in worklist entry and their
 * audit rows. Negative cases show that the rule refuses an unverified check-in,
 * a role without the identity permission, a facility outside the receptionist's
 * assigned scope and a stale appointment version. All data is synthetic.
 */
class ReceptionVerifiesIdentityChecksTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "reception-a";
    private static final String APPOINTMENT = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ITEM = "44444444-4444-4444-4444-444444444444";
    private static final String FACILITY_ID = "55555555-5555-5555-5555-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private WorklistRepository repository;
    private AppointmentService appointments;
    private SchedulingRepository scheduling;
    private AuditAppender audit;
    private CheckInService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorklistRepository.class);
        appointments = mock(AppointmentService.class);
        scheduling = mock(SchedulingRepository.class);
        audit = mock(AuditAppender.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new CheckInService(repository, appointments, scheduling, new WorklistPolicy(),
                transactions, audit, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, SUBJECT, "CHECK_IN_WORKLIST", "corr-clause"));
        when(repository.facilityId("MAIN")).thenReturn(Optional.of(FACILITY_ID));
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant one active role to the receptionist subject in the active organization. */
    private void activeRole(String role) {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(scheduling.activeRoles(SUBJECT)).thenReturn(List.of(role));
    }

    /** Build a synthetic appointment in the requested lifecycle state. */
    private static Appointment appointment(AppointmentState state) {
        return new Appointment(APPOINTMENT, 1, PATIENT, "MAIN", "DR-A", "ROOM-1", "NEW",
                NOW, NOW.plusSeconds(1800), state, null, null, null);
    }

    /** Standard two-identifier evidence presented at the reception desk. */
    private static VerifyIdentity twoIdentifiers() {
        return new VerifyIdentity(APPOINTMENT, List.of("MRN", "DOB"), "two_identifiers", null, null);
    }

    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void receptionVerifiesIdentityThenChecksInAndCreatesAnAuditedWorklistItem() {
        activeRole("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(
                appointment(AppointmentState.BOOKED), List.of()));

        Verification verification = service.verify(twoIdentifiers());

        assertEquals(APPOINTMENT, verification.appointmentId());
        assertEquals(PATIENT, verification.patientId());
        assertEquals("MAIN", verification.facilityCode());
        assertEquals(List.of("MRN", "DOB"), verification.identifiers());
        assertEquals(SUBJECT, verification.verifier());
        assertEquals(NOW, verification.verifiedAt());
        verify(repository).insertVerification(eq(verification.id()), eq(APPOINTMENT), eq(PATIENT),
                eq(FACILITY_ID), eq("two_identifiers"), eq(List.of("MRN", "DOB")), eq(null), eq(null));
        verify(audit).append(any(), eq("IDENTITY_VERIFY"), eq("IDENTITY_VERIFY"),
                eq("WORKLIST_ITEM"), eq("CHECK_IN_WORKLIST"), eq(APPOINTMENT), eq("SUCCESS"));

        // verify() persisted this record; model its committed effect on the next command.
        // The real persistence-to-check-in chain is exercised by CheckInWorklistIntegrationTest.
        when(repository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(appointments.checkIn(APPOINTMENT, 1)).thenReturn(
                appointment(AppointmentState.CHECKED_IN));
        when(scheduling.setting("clinic.worklist.priority.NEW")).thenReturn(Optional.of("5"));
        when(repository.insertItem(eq(APPOINTMENT), eq(null), eq(PATIENT), eq(FACILITY_ID),
                anyInt(), eq(null), eq(null))).thenReturn(ITEM);

        WorklistItem arrival = service.checkIn(APPOINTMENT, 1);

        assertEquals(ITEM, arrival.id());
        assertEquals(APPOINTMENT, arrival.appointmentId());
        assertEquals(PATIENT, arrival.patientId());
        assertEquals("MAIN", arrival.facilityCode());
        assertEquals(5, arrival.priority());
        assertEquals(NOW, arrival.startsAt());
        verify(appointments).checkIn(APPOINTMENT, 1);
        verify(audit).append(any(), eq("VERIFIED_CHECK_IN"), eq("VERIFIED_CHECK_IN"),
                eq("WORKLIST_ITEM"), eq("CHECK_IN_WORKLIST"), eq(APPOINTMENT), eq("SUCCESS"));
    }

    @Test
    void checkInRefusesAnUnverifiedPatient() {
        activeRole("reception");

        ApiException denial = reject(() -> service.checkIn(APPOINTMENT, 1));

        assertEquals(409, denial.status());
        assertEquals("VERIFICATION_REQUIRED", denial.code());
        verify(appointments, never()).checkIn(anyString(), anyInt());
        verify(repository, never()).insertItem(anyString(), any(), anyString(), anyString(),
                anyInt(), any(), any());
    }

    @Test
    void verifyRefusesARoleWithoutIdentityPermission() {
        // A clinical assistant has active membership but no identity-verify action.
        activeRole("clinical_assistant");

        ApiException denial = reject(() -> service.verify(twoIdentifiers()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        // The alternate fields are null for two_identifiers, so anyString() would
        // not match them; nullable() closes that gap on the denied path.
        verify(repository, never()).insertVerification(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), nullable(String.class), nullable(String.class));
        verify(audit).append(any(), eq("IDENTITY_VERIFY"), eq("IDENTITY_VERIFY"),
                eq("WORKLIST_ITEM"), eq("CHECK_IN_WORKLIST"), eq(APPOINTMENT), eq("DENIED"));
    }

    @Test
    void verifyRefusesAFacilityOutsideAssignedScope() {
        activeRole("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(
                appointment(AppointmentState.BOOKED), List.of()));
        when(repository.outsideAssignedFacilities(SUBJECT, "MAIN")).thenReturn(true);

        ApiException denial = reject(() -> service.verify(twoIdentifiers()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        // The alternate fields are null for two_identifiers, so anyString() would
        // not match them; nullable() closes that gap on the denied path.
        verify(repository, never()).insertVerification(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), nullable(String.class), nullable(String.class));
        verify(audit).append(any(), eq("IDENTITY_VERIFY"), eq("IDENTITY_VERIFY"),
                eq("WORKLIST_ITEM"), eq("CHECK_IN_WORKLIST"), eq(APPOINTMENT), eq("DENIED"));
    }

    @Test
    void checkInRefusesStaleAppointmentVersion() {
        activeRole("reception");
        when(repository.hasVerification(APPOINTMENT)).thenReturn(true);
        doThrow(new ApiException(412, "STALE_VERSION", "stale"))
                .when(appointments).checkIn(APPOINTMENT, 1);

        ApiException denial = reject(() -> service.checkIn(APPOINTMENT, 1));

        assertEquals(412, denial.status());
        assertEquals("STALE_VERSION", denial.code());
        verify(repository, never()).insertItem(anyString(), any(), anyString(), anyString(),
                anyInt(), any(), any());
    }
}
