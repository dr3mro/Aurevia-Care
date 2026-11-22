package com.aurevia.smartclinic.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.scheduling.Appointment;
import com.aurevia.smartclinic.scheduling.AppointmentService;
import com.aurevia.smartclinic.scheduling.AppointmentState;
import com.aurevia.smartclinic.scheduling.SchedulingRepository;
import com.aurevia.smartclinic.scheduling.checkin.CheckInService;
import com.aurevia.smartclinic.scheduling.checkin.WorklistPolicy;
import com.aurevia.smartclinic.scheduling.checkin.WorklistRepository;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * CLN-02 clause 4 proof: only eligible clinical roles may record vitals or open
 * the encounter. The scheduling role matrix is the authoritative rule for both
 * actions; the encounter half is additionally proven through the real
 * {@link com.aurevia.smartclinic.scheduling.checkin.CheckInService} command entry
 * point, including its tenant context and DENIED/SUCCESS audit trail. A denied
 * request must not reach the state machine. All fixtures are synthetic.
 */
class EligibleRolesRecordVitalsOpenEncounterTest {

    private static final String ORG = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUBJECT = "clause-four-actor";
    private static final String APPOINTMENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private WorklistRepository worklistRepository;
    private AppointmentService appointments;
    private SchedulingRepository scheduling;
    private AuditAppender audit;
    private WorklistPolicy policy;
    private CheckInService checkIn;

    /** Build the service with mocked boundaries and a transaction that runs its callback inline. */
    @BeforeEach
    void setUp() {
        worklistRepository = mock(WorklistRepository.class);
        appointments = mock(AppointmentService.class);
        scheduling = mock(SchedulingRepository.class);
        audit = mock(AuditAppender.class);
        policy = new WorklistPolicy();
        checkIn = new CheckInService(worklistRepository, appointments, scheduling, policy,
                transactions(), audit, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, SUBJECT, "CHECK_IN_WORKLIST", "corr-clause-four"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A transaction template that executes the guarded callback immediately. */
    private static TransactionTemplate transactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Model an active member holding exactly one role in the active organization. */
    private void authorized(String role) {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(scheduling.activeRoles(SUBJECT)).thenReturn(List.of(role));
    }

    /** A synthetic appointment in a requested state. */
    private static Appointment appointment(AppointmentState state) {
        return new Appointment(APPOINTMENT, 1, PATIENT, "MAIN", "DR-A", "ROOM-1", "NEW",
                NOW, NOW.plusSeconds(1800), state, null, null, null);
    }

    private static ApiException denial(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    // ---- open the encounter (service entry point) ----

    @Test
    void clinicianOpensEncounterAfterIdentityWasVerified() {
        authorized("clinician");
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(appointments.start(APPOINTMENT, 2)).thenReturn(appointment(AppointmentState.IN_PROGRESS));

        Appointment opened = checkIn.openEncounter(APPOINTMENT, 2);

        assertEquals(AppointmentState.IN_PROGRESS, opened.state());
        verify(appointments).start(APPOINTMENT, 2);
        verify(audit).append(any(), eq(WorklistPolicy.ENCOUNTER_OPEN), eq(WorklistPolicy.ENCOUNTER_OPEN),
                eq("WORKLIST_ITEM"), anyString(), eq(APPOINTMENT), eq("SUCCESS"));
    }

    @Test
    void receptionCannotOpenEncounterEvenAfterIdentityWasVerified() {
        authorized("reception");
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(appointments.start(APPOINTMENT, 2)).thenReturn(appointment(AppointmentState.IN_PROGRESS));

        ApiException failure = denial(() -> checkIn.openEncounter(APPOINTMENT, 2));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("missing worklist permission"));
        // The refusal must happen before any state-machine transition.
        verify(appointments, never()).start(anyString(), anyInt());
        verify(audit).append(any(), eq(WorklistPolicy.ENCOUNTER_OPEN), eq(WorklistPolicy.ENCOUNTER_OPEN),
                eq("WORKLIST_ITEM"), anyString(), eq(APPOINTMENT), eq("DENIED"));
    }

    @Test
    void clinicalAssistantCannotOpenEncounterThoughItMayRecordVitals() {
        authorized("clinical_assistant");
        assertTrue(policy.allows(WorklistPolicy.VITALS_RECORD, "clinical_assistant"));
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);

        ApiException failure = denial(() -> checkIn.openEncounter(APPOINTMENT, 2));

        assertEquals(403, failure.status());
        verify(appointments, never()).start(anyString(), anyInt());
    }

    // ---- record vitals (authoritative scheduling role matrix) ----

    @Test
    void eligibleClinicalRolesMayRecordVitals() {
        assertTrue(policy.allows(WorklistPolicy.VITALS_RECORD, "clinical_assistant"));
        assertTrue(policy.allows(WorklistPolicy.VITALS_RECORD, "clinician"));
    }

    @Test
    void ineligibleRolesCannotRecordVitals() {
        for (String role : List.of("reception", "tenant_admin", "privacy_auditor", "unknown_role")) {
            assertFalse(policy.allows(WorklistPolicy.VITALS_RECORD, role),
                    role + " must not be able to record vitals");
        }
    }

    @Test
    void missingRoleFailsClosedForBothActions() {
        assertFalse(policy.allows(WorklistPolicy.ENCOUNTER_OPEN, null));
        assertFalse(policy.allows(WorklistPolicy.VITALS_RECORD, null));
    }
}
