package com.aurevia.smartclinic.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.chart.record.ChartActionPolicy;
import com.aurevia.smartclinic.scheduling.AppointmentService;
import com.aurevia.smartclinic.scheduling.SchedulingRepository;
import com.aurevia.smartclinic.scheduling.checkin.CheckInCommands.Assign;
import com.aurevia.smartclinic.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.smartclinic.scheduling.checkin.CheckInCommands.Intake;
import com.aurevia.smartclinic.scheduling.checkin.CheckInService;
import com.aurevia.smartclinic.scheduling.checkin.WorklistItem;
import com.aurevia.smartclinic.scheduling.checkin.WorklistPolicy;
import com.aurevia.smartclinic.scheduling.checkin.WorklistRepository;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Characterization evidence for the section-2 Clinical assistant row (IAM-01, CLN-02).
 *
 * <p>The tests reach the real {@link com.aurevia.smartclinic.scheduling.checkin.CheckInService}
 * and the real action policies with mocked stores and an inline transaction, so a
 * denial and its audit row are observable without a database. They prove the verbs
 * that exist today and the generic membership/facility/item guards. They do not
 * claim the unbuilt "reconciliation preparation" or "assigned encounters or care
 * team" constraint; those would-prove scenarios are recorded in the clause doc.</p>
 *
 * <p>Every fixture value is synthetic: fixed UUIDs, a fixed clock and a fixed
 * tenant context, cleared after each test so neighbouring suites are unaffected.</p>
 */
class RoleClinicalAssistantTest {

    private static final String ORG = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUBJECT = "clinical-assistant-actor";
    private static final String ITEM = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String APPOINTMENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PATIENT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String FACILITY_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String FACILITY = "MAIN";
    private static final String ASSIGNEE = "nurse-a";
    private static final String TARGET = "nurse-b";
    private static final String ROLE = "clinical_assistant";
    private static final String UNKNOWN_ACTION = "UNKNOWN_ACTION";
    private static final String SCOPE = "CHECK_IN_WORKLIST";
    private static final String RESOURCE_TYPE = "WORKLIST_ITEM";
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private static final List<String> ALL_ACTIONS = List.of(
            WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.CHECK_IN, WorklistPolicy.WORKLIST_READ,
            WorklistPolicy.WORKLIST_ASSIGN, WorklistPolicy.HANDOFF, WorklistPolicy.VITALS_RECORD,
            WorklistPolicy.ENCOUNTER_OPEN, WorklistPolicy.INTAKE_LINK, UNKNOWN_ACTION);

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
        TenantContext.set(new TenantContext(ORG, SUBJECT, SCOPE, "corr-clinical-assistant"));
    }

    /** Remove the request-scoped tenant binding so it cannot leak into the next suite. */
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

    /** Model an active member holding the clinical-assistant role in the active organization. */
    private void authorized() {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(scheduling.activeRoles(SUBJECT)).thenReturn(List.of(ROLE));
    }

    /** A synthetic worklist row with a chosen current assignee and optimistic version. */
    private static WorklistItem item(String assignee, int version) {
        return new WorklistItem(ITEM, APPOINTMENT, null, PATIENT, FACILITY, NOW, 0,
                assignee, assignee == null ? null : NOW, version);
    }

    /** Capture the structured denial thrown from a guarded service command. */
    private static ApiException denial(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    /** Assert the DENIED audit row that commits before a guarded refusal is reported. */
    private void assertDeniedAudit(String action, String resourceId) {
        verify(audit).append(any(), eq(action), eq(action), eq(RESOURCE_TYPE), eq(SCOPE),
                eq(resourceId), eq("DENIED"));
    }

    /** Assert the SUCCESS audit row committed by a permitted service command. */
    private void assertSuccessfulAudit(String action, String resourceId) {
        verify(audit).append(any(), eq(action), eq(action), eq(RESOURCE_TYPE), eq(SCOPE),
                eq(resourceId), eq("SUCCESS"));
    }

    /** The assistant is granted exactly the four worklist verbs this slice implements. */
    @Test
    void allowsEachPermittedVerbForClinicalAssistant() {
        for (String verb : List.of(WorklistPolicy.INTAKE_LINK, WorklistPolicy.VITALS_RECORD,
                WorklistPolicy.HANDOFF, WorklistPolicy.WORKLIST_READ)) {
            assertTrue(policy.allows(verb, ROLE), ROLE + " must allow " + verb);
        }
    }

    /**
     * Other roles' verbs and unknown roles are refused; per-role refusal lists are
     * needed because no single defined verb is refused by every real role.
     */
    @Test
    void refusesOtherRolesVerbsAndUnknownRoles() {
        for (String verb : List.of(WorklistPolicy.ENCOUNTER_OPEN, WorklistPolicy.IDENTITY_VERIFY,
                WorklistPolicy.CHECK_IN, WorklistPolicy.WORKLIST_ASSIGN)) {
            assertFalse(policy.allows(verb, ROLE), ROLE + " must refuse " + verb);
        }
        for (String role : List.of("privacy_auditor", "unknown_role")) {
            for (String action : ALL_ACTIONS) {
                assertFalse(policy.allows(action, role), role + " must not allow " + action);
            }
        }
        for (String action : ALL_ACTIONS) {
            assertFalse(policy.allows(action, null), "a missing role must not allow " + action);
        }
        for (String action : List.of(WorklistPolicy.ENCOUNTER_OPEN, WorklistPolicy.VITALS_RECORD,
                UNKNOWN_ACTION)) {
            assertFalse(policy.allows(action, "reception"), "reception must not allow " + action);
            assertFalse(policy.allows(action, "tenant_admin"), "tenant_admin must not allow " + action);
        }
        for (String action : List.of(WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.CHECK_IN,
                WorklistPolicy.WORKLIST_ASSIGN, WorklistPolicy.HANDOFF,
                WorklistPolicy.INTAKE_LINK, UNKNOWN_ACTION)) {
            assertFalse(policy.allows(action, "clinician"), "clinician must not allow " + action);
        }
    }

    /** A handoff cannot start from a row with no current assignee. */
    @Test
    void handoffRefusesAnUnassignedItem() {
        authorized();
        when(worklistRepository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(null, 1)));

        ApiException failure = denial(() -> checkIn.handoff(ITEM, new Handoff(TARGET, "coverage"), 1));

        assertEquals(409, failure.status());
        assertEquals("NO_ACTIVE_ASSIGNEE", failure.code());
        verify(worklistRepository, never()).updateItem(anyString(), anyInt(), anyString(), any(), anyInt());
        verify(worklistRepository, never()).insertHandoff(any());
    }

    /** A stale expected version is refused before any assignment is written (412, not 409). */
    @Test
    void handoffRefusesStaleVersion() {
        authorized();
        when(worklistRepository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(ASSIGNEE, 2)));

        ApiException failure = denial(() -> checkIn.handoff(ITEM, new Handoff(TARGET, "coverage"), 1));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(worklistRepository, never()).updateItem(anyString(), anyInt(), anyString(), any(), anyInt());
        verify(worklistRepository, never()).insertHandoff(any());
    }

    /** The implemented facility narrowing refuses a handoff outside the assigned facility. */
    @Test
    void handoffRefusesAnotherFacility() {
        authorized();
        when(worklistRepository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(ASSIGNEE, 1)));
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, FACILITY)).thenReturn(true);

        ApiException failure = denial(() -> checkIn.handoff(ITEM, new Handoff(TARGET, "coverage"), 1));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("facility is outside the assigned scope"));
        assertDeniedAudit(WorklistPolicy.HANDOFF, ITEM);
        verify(worklistRepository, never()).insertHandoff(any());
    }

    /** Intake of a patient into another facility is refused before the patient is read. */
    @Test
    void intakeRefusesAnotherFacility() {
        authorized();
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "NORTH")).thenReturn(true);

        ApiException failure = denial(() -> checkIn.linkIntake(new Intake("NORTH", PATIENT, "walk-in")));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("facility is outside the assigned scope"));
        assertDeniedAudit(WorklistPolicy.INTAKE_LINK, PATIENT);
        verify(worklistRepository, never()).patientExists(anyString());
        verify(worklistRepository, never()).insertItem(any(), any(), any(), any(), anyInt(), any(), any());
    }

    /** A subject without active membership fails closed on a worklist read. */
    @Test
    void deniesWhenMembershipIsInactive() {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(false);

        ApiException failure = denial(() -> checkIn.worklist(FACILITY, null));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("no active membership"));
        assertDeniedAudit(WorklistPolicy.WORKLIST_READ, FACILITY);
        verify(worklistRepository, never()).listWorklist(anyString(), anyInt());
    }

    /** The assistant can read the worklist through the service entry point. */
    @Test
    void worklistReadReachesCheckInServiceForClinicalAssistant() {
        authorized();
        WorklistItem listed = item(ASSIGNEE, 1);
        when(worklistRepository.listWorklist(FACILITY, 100)).thenReturn(List.of(listed));

        List<WorklistItem> result = checkIn.worklist(FACILITY, null);

        assertEquals(List.of(listed), result);
        assertSuccessfulAudit(WorklistPolicy.WORKLIST_READ, FACILITY);
    }

    /** The assistant can link an unscheduled arrival to intake through the service. */
    @Test
    void intakeLinkReachesCheckInServiceForClinicalAssistant() {
        authorized();
        when(scheduling.setting("clinic.worklist.default_priority")).thenReturn(Optional.empty());
        when(worklistRepository.patientExists(PATIENT)).thenReturn(true);
        when(worklistRepository.facilityId(FACILITY)).thenReturn(Optional.of(FACILITY_ID));
        when(worklistRepository.insertItem(null, "walk-in", PATIENT, FACILITY_ID, 0, null, null))
                .thenReturn(ITEM);

        WorklistItem created = checkIn.linkIntake(new Intake(FACILITY, PATIENT, "walk-in"));

        assertEquals(ITEM, created.id());
        assertEquals("walk-in", created.intakeReason());
        assertEquals(1, created.version());
        assertNull(created.assigneeSubject());
        assertSuccessfulAudit(WorklistPolicy.INTAKE_LINK, ITEM);
    }

    /** The assistant can hand off an item it currently holds, with a recorded event. */
    @Test
    void handoffReachesCheckInServiceForClinicalAssistant() {
        authorized();
        when(worklistRepository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(SUBJECT, 1)));
        when(scheduling.hasActiveMembership(TARGET)).thenReturn(true);
        when(worklistRepository.updateItem(ITEM, 1, TARGET, NOW, 0)).thenReturn(1);

        WorklistItem transferred = checkIn.handoff(ITEM, new Handoff(TARGET, "coverage"), 1);

        assertEquals(TARGET, transferred.assigneeSubject());
        assertEquals(2, transferred.version());
        verify(worklistRepository).insertHandoff(any());
        assertSuccessfulAudit(WorklistPolicy.HANDOFF, ITEM);
    }

    /** Assignment belongs to other roles and is refused before the item is read. */
    @Test
    void assistantCannotAssignWorklistItemThroughService() {
        authorized();

        ApiException failure = denial(() -> checkIn.assign(ITEM, new Assign(TARGET), 1));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("missing worklist permission"));
        assertDeniedAudit(WorklistPolicy.WORKLIST_ASSIGN, ITEM);
        verify(worklistRepository, never()).findItemForUpdate(anyString());
    }

    /** Vitals entry is allowed, but verification of a chart entry is outside the assistant's fields. */
    @Test
    void chartPolicyAllowsAssistantToCreateVitalsEntryButNotVerifyIt() {
        ChartActionPolicy chartPolicy = new ChartActionPolicy();

        assertTrue(chartPolicy.allows(ChartActionPolicy.CHART_ENTRY_CREATE, ROLE));
        assertFalse(chartPolicy.allows(ChartActionPolicy.CHART_ENTRY_VERIFY, ROLE));
    }
}
