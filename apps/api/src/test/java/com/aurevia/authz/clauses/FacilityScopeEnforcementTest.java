package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.EmergencyAccessService;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.AppointmentState;
import com.aurevia.scheduling.AppointmentView;
import com.aurevia.scheduling.SchedulingRepository;
import com.aurevia.scheduling.checkin.CheckInCommands.Assign;
import com.aurevia.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.scheduling.checkin.CheckInCommands.Intake;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.scheduling.checkin.CheckInService;
import com.aurevia.scheduling.checkin.WorklistItem;
import com.aurevia.scheduling.checkin.WorklistPolicy;
import com.aurevia.scheduling.checkin.WorklistRepository;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-01 clause 4 (unit evidence): every facility-aware denial branch taken from a
 * normal service entry point with a synthetic out-of-scope answer from the database
 * fold. The acceptance test over HTTP is
 * {@link com.aurevia.authz.clauses.FacilityScopeFurtherNarrowAccessTest}.
 */
class FacilityScopeEnforcementTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "scope-reception";
    private static final String APPOINTMENT = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String ITEM = "44444444-4444-4444-4444-444444444444";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private WorklistRepository worklistRepository;
    private AppointmentService appointments;
    private SchedulingRepository scheduling;
    private AuditAppender audit;
    private CheckInService checkIn;

    private AccessRepository accessRepository;
    private EmergencyAccessService emergency;

    @BeforeEach
    void setUp() {
        worklistRepository = mock(WorklistRepository.class);
        appointments = mock(AppointmentService.class);
        scheduling = mock(SchedulingRepository.class);
        audit = mock(AuditAppender.class);
        checkIn = new CheckInService(worklistRepository, appointments, scheduling, new WorklistPolicy(),
                transactions(), audit, Clock.fixed(NOW, ZoneOffset.UTC));

        accessRepository = mock(AccessRepository.class);
        emergency = new EmergencyAccessService(accessRepository, transactions(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TenantContext.set(new TenantContext(ORG, SUBJECT, "FACILITY_SCOPE", "corr-scope"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    private void authorized(String role) {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(scheduling.activeRoles(SUBJECT)).thenReturn(List.of(role));
    }

    private Appointment appointment() {
        return new Appointment(APPOINTMENT, 1, PATIENT, "MAIN", "DR-A", "ROOM-1", "NEW",
                NOW, NOW.plusSeconds(1800), AppointmentState.BOOKED, null, null, null);
    }

    private WorklistItem item() {
        return new WorklistItem(ITEM, APPOINTMENT, null, PATIENT, "MAIN", NOW, 0, null, null, 1);
    }

    private static ApiException denial(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    @Test
    void verificationOfAnotherFacilityIsDeniedAndAudited() {
        authorized("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(appointment(), List.of()));
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "MAIN")).thenReturn(true);

        ApiException failure = denial(() -> checkIn.verify(new VerifyIdentity(
                APPOINTMENT, List.of("MRN", "DOB"), "two_identifiers", null, null)));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        org.mockito.Mockito.verify(audit).append(any(), anyString(), anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.eq(APPOINTMENT),
                org.mockito.ArgumentMatchers.eq("DENIED"));
    }

    @Test
    void checkInOfAnotherFacilityIsDenied() {
        authorized("reception");
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(appointments.checkIn(APPOINTMENT, 1)).thenReturn(appointment());
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "MAIN")).thenReturn(true);

        assertEquals(403, denial(() -> checkIn.checkIn(APPOINTMENT, 1)).status());
    }

    @Test
    void worklistOfAnotherFacilityIsDeniedBeforeAnyRead() {
        authorized("reception");
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "NORTH")).thenReturn(true);

        assertEquals(403, denial(() -> checkIn.worklist("NORTH", null)).status());
        org.mockito.Mockito.verify(worklistRepository, org.mockito.Mockito.never())
                .listWorklist(anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void assignmentOfAnotherFacilityIsDeniedBeforeAnyWrite() {
        authorized("reception");
        when(worklistRepository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item()));
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "MAIN")).thenReturn(true);

        assertEquals(403, denial(() -> checkIn.assign(ITEM, new Assign("nurse-a"), 1)).status());
        org.mockito.Mockito.verify(worklistRepository, org.mockito.Mockito.never())
                .updateItem(anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), any(),
                        org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void handoffOfAnotherFacilityIsDeniedBeforeAnyWrite() {
        authorized("reception");
        when(worklistRepository.findItemForUpdate(ITEM))
                .thenReturn(Optional.of(new WorklistItem(ITEM, APPOINTMENT, null, PATIENT, "MAIN", NOW,
                        0, "nurse-a", NOW, 1)));
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "MAIN")).thenReturn(true);

        assertEquals(403, denial(() -> checkIn.handoff(ITEM, new Handoff("nurse-b", "coverage"), 1))
                .status());
        org.mockito.Mockito.verify(worklistRepository, org.mockito.Mockito.never())
                .insertHandoff(any());
    }

    @Test
    void intakeOfAnotherFacilityIsDeniedBeforeAnyWrite() {
        authorized("reception");
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "NORTH")).thenReturn(true);

        assertEquals(403, denial(() -> checkIn.linkIntake(new Intake("NORTH", PATIENT, "walk-in")))
                .status());
        org.mockito.Mockito.verify(worklistRepository, org.mockito.Mockito.never())
                .patientExists(anyString());
    }

    @Test
    void emergencyGrantOfAnotherFacilityIsDenied() {
        when(accessRepository.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessRepository.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        when(accessRepository.outsideAssignedFacilities(SUBJECT, "NORTH")).thenReturn(true);

        ApiException failure = denial(() -> emergency.grant(
                new com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest(
                        "NORTH", "patient in distress", 10)));

        assertEquals(403, failure.status());
        org.mockito.Mockito.verify(accessRepository, org.mockito.Mockito.never())
                .latestGrant(anyString());
    }

    @Test
    void activeEmergencyGrantOfAnotherFacilityIsDenied() {
        when(accessRepository.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(accessRepository.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        when(accessRepository.latestGrant(SUBJECT)).thenReturn(Optional.of(grant("NORTH")));
        when(accessRepository.outsideAssignedFacilities(SUBJECT, "NORTH")).thenReturn(true);

        ApiException failure = denial(emergency::active);

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
    }

    private GrantRow grant(String facilityCode) {
        return new GrantRow("grant-1", SUBJECT, facilityCode, "EMERGENCY_ACCESS",
                "patient in distress", "clinician", NOW, NOW.plusSeconds(600), null, null, null, null);
    }

    @Test
    void worklistRepositoryMapsTheDatabaseAnswerWithoutInventingAScope() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        WorklistRepository repository = new WorklistRepository(jdbc);
        when(jdbc.queryForObject(anyString(), any(java.util.Map.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(Boolean.TRUE);

        assertTrue(repository.outsideAssignedFacilities(SUBJECT, "NORTH"));

        when(jdbc.queryForObject(anyString(), any(java.util.Map.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(Boolean.FALSE);
        assertFalse(repository.outsideAssignedFacilities(SUBJECT, "MAIN"));

        when(jdbc.queryForObject(anyString(), any(java.util.Map.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(null);
        assertFalse(repository.outsideAssignedFacilities(SUBJECT, "MAIN"));
    }

    @Test
    void accessRepositoryMapsTheDatabaseAnswerWithoutInventingAScope() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        AccessRepository repository = new AccessRepository(jdbc);
        when(jdbc.queryForObject(anyString(), any(java.util.Map.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(Boolean.TRUE);

        assertTrue(repository.outsideAssignedFacilities(SUBJECT, "NORTH"));

        when(jdbc.queryForObject(anyString(), any(java.util.Map.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(Boolean.FALSE);
        assertFalse(repository.outsideAssignedFacilities(SUBJECT, "MAIN"));

        when(jdbc.queryForObject(anyString(), any(java.util.Map.class),
                org.mockito.ArgumentMatchers.eq(Boolean.class))).thenReturn(null);
        assertFalse(repository.outsideAssignedFacilities(SUBJECT, "MAIN"));
    }
}
