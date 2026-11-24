package com.aurevia.it.schedulingcheckin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
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
import com.aurevia.scheduling.checkin.CheckInService;
import com.aurevia.scheduling.checkin.CheckInCommands.Assign;
import com.aurevia.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.scheduling.checkin.CheckInCommands.Intake;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.scheduling.checkin.Verification;
import com.aurevia.scheduling.checkin.WorklistItem;
import com.aurevia.scheduling.checkin.WorklistPolicy;
import com.aurevia.scheduling.checkin.WorklistRepository;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.AppointmentView;
import com.aurevia.scheduling.SchedulingRepository;
import com.aurevia.tenant.TenantContext;

/** S-G1-02 service branches: authorization, verification, worklist, assignment and handoff. */
class CheckInServiceTest {

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
        TenantContext.set(new TenantContext(ORG, SUBJECT, "CHECK_IN_WORKLIST", "corr-1"));
        when(repository.facilityId("MAIN")).thenReturn(Optional.of(FACILITY_ID));
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String role) {
        when(scheduling.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(scheduling.activeRoles(SUBJECT)).thenReturn(List.of(role));
    }

    private static Appointment appointment(com.aurevia.scheduling.AppointmentState state) {
        return new Appointment(APPOINTMENT, 1, PATIENT, "MAIN", "DR-A", "ROOM-1", "NEW",
                NOW, NOW.plusSeconds(1800), state, null, null, null);
    }

    private static VerifyIdentity twoIdentifiers() {
        return new VerifyIdentity(APPOINTMENT, List.of("MRN", "DOB"), "two_identifiers", null, null);
    }

    private static WorklistItem item(String assignee, Instant assignedAt, int version) {
        return new WorklistItem(ITEM, APPOINTMENT, null, PATIENT, "MAIN", NOW, 0,
                assignee, assignedAt, version);
    }

    private static ApiException reject(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    // ---- verify ----

    @Test
    void verifyStoresEvidenceAndAuditsSuccess() {
        authorized("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(appointment(
                com.aurevia.scheduling.AppointmentState.BOOKED), List.of()));
        Verification result = service.verify(twoIdentifiers());
        assertEquals(APPOINTMENT, result.appointmentId());
        assertEquals(PATIENT, result.patientId());
        assertEquals("MAIN", result.facilityCode());
        assertEquals(List.of("MRN", "DOB"), result.identifiers());
        assertEquals(SUBJECT, result.verifier());
        assertEquals(NOW, result.verifiedAt());
        verify(repository).insertVerification(eq(result.id()), eq(APPOINTMENT), eq(PATIENT),
                eq(FACILITY_ID), eq("two_identifiers"), eq(List.of("MRN", "DOB")),
                eq(null), eq(null));
        verify(audit).append(any(), eq("IDENTITY_VERIFY"), eq("IDENTITY_VERIFY"),
                eq("WORKLIST_ITEM"), anyString(), eq(APPOINTMENT), eq("SUCCESS"));
    }

    @Test
    void verifySupportsTheDocumentedAlternatePath() {
        authorized("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(appointment(
                com.aurevia.scheduling.AppointmentState.BOOKED), List.of()));
        VerifyIdentity request = new VerifyIdentity(APPOINTMENT, List.of(), "documented_alternate",
                "SITE_BACKUP", "documented outage");
        Verification result = service.verify(request);
        assertEquals("documented_alternate", result.method());
        assertTrue(result.identifiers().isEmpty());
        assertEquals("SITE_BACKUP", result.alternateCode());
        assertEquals("documented outage", result.alternateReason());
    }

    @Test
    void verifyRejectsBadInputBeforeAnyWrite() {
        assertEquals(422, reject(() -> service.verify(new VerifyIdentity("no", List.of("MRN", "DOB"),
                "two_identifiers", null, null))).status());
        assertEquals(422, reject(() -> service.verify(new VerifyIdentity(APPOINTMENT,
                List.of("DOB", "DOB"), "two_identifiers", null, null))).status());
        assertEquals(422, reject(() -> service.verify(new VerifyIdentity(APPOINTMENT,
                List.of("MRN", "DOB"), "unknown", null, null))).status());
        assertEquals(422, reject(() -> service.verify(new VerifyIdentity(APPOINTMENT,
                List.of(), "documented_alternate", "CODE", null))).status());
        verify(repository, never()).insertVerification(anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void verifyDeniesWithoutMembershipOrRole() {
        assertEquals(403, reject(() -> service.verify(twoIdentifiers())).status());
        authorized("privacy_auditor");
        ApiException denial = reject(() -> service.verify(twoIdentifiers()));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(audit, org.mockito.Mockito.times(2)).append(any(), eq("IDENTITY_VERIFY"),
                anyString(), anyString(), anyString(), anyString(), eq("DENIED"));
    }

    @Test
    void verifyFailsClosedWithoutTenantContext() {
        TenantContext.clear();
        assertEquals(403, reject(() -> service.verify(twoIdentifiers())).status());
        verify(repository, never()).hasVerification(anyString());
    }

    @Test
    void everyGuardedCommandDeniesWithoutActiveMembership() {
        // No authorized(...) call: each command must take its DENIED branch, record
        // the audit and throw the captured denial after the transaction commits.
        assertEquals(403, reject(() -> service.verify(twoIdentifiers())).status());
        assertEquals(403, reject(() -> service.checkIn(APPOINTMENT, 1)).status());
        assertEquals(403, reject(() -> service.worklist("MAIN", null)).status());
        assertEquals(403, reject(() -> service.assign(ITEM, new Assign("nurse-a"), 1)).status());
        assertEquals(403, reject(() -> service.handoff(ITEM, new Handoff("nurse-b", "r"), 1)).status());
        assertEquals(403, reject(() -> service.linkIntake(new Intake("MAIN", PATIENT, "walk-in"))).status());
        assertEquals(403, reject(() -> service.openEncounter(APPOINTMENT, 2)).status());
    }

    @Test
    void denialAuditIsCommittedEvenThoughTheCommandFails() {
        // A real TransactionTemplate rolls back on RuntimeException, so an audit row
        // written inside a callback that then throws is lost. Model that here: only
        // audits from callbacks that return normally are treated as committed.
        java.util.List<String> committed = new java.util.ArrayList<>();
        java.util.List<String> pending = new java.util.ArrayList<>();
        AuditAppender recordingAudit = mock(AuditAppender.class);
        doAnswer(invocation -> {
            pending.add(invocation.getArgument(6));
            return null;
        }).when(recordingAudit).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
        TransactionTemplate simulating = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            pending.clear();
            try {
                Object value = callback.doInTransaction(mock(TransactionStatus.class));
                committed.addAll(pending);
                return value;
            } catch (RuntimeException exception) {
                pending.clear();
                throw exception;
            }
        }).when(simulating).execute(any());
        CheckInService local = new CheckInService(repository, appointments, scheduling,
                new WorklistPolicy(), simulating, recordingAudit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ApiException denial = reject(() -> local.verify(twoIdentifiers()));
        assertEquals(403, denial.status());
        assertEquals(java.util.List.of("DENIED"), committed,
                "the DENIED audit row must commit even though the command fails");
    }

    @Test
    void verifyConcealsMissingAppointmentAndRejectsDuplicates() {
        authorized("reception");
        doThrow(new ApiException(404, "NOT_FOUND", "missing"))
                .when(appointments).get(APPOINTMENT);
        assertEquals(404, reject(() -> service.verify(twoIdentifiers())).status());

        doReturn(new AppointmentView(appointment(
                com.aurevia.scheduling.AppointmentState.BOOKED), List.of()))
                .when(appointments).get(APPOINTMENT);
        when(repository.hasVerification(APPOINTMENT)).thenReturn(true);
        assertEquals("DUPLICATE_VERIFICATION", reject(() -> service.verify(twoIdentifiers())).code());
    }

    @Test
    void verifyMapsAConcurrentUniqueViolationToDuplicate() {
        authorized("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(appointment(
                com.aurevia.scheduling.AppointmentState.BOOKED), List.of()));
        doThrow(new org.springframework.dao.DataIntegrityViolationException("unique"))
                .when(repository).insertVerification(anyString(), eq(APPOINTMENT), eq(PATIENT),
                        eq(FACILITY_ID), eq("two_identifiers"), eq(List.of("MRN", "DOB")),
                        eq(null), eq(null));
        assertEquals("DUPLICATE_VERIFICATION", reject(() -> service.verify(twoIdentifiers())).code());
        verify(audit, never()).append(any(), eq("IDENTITY_VERIFY"), eq("IDENTITY_VERIFY"),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void verifyConcealsMissingFacility() {
        authorized("reception");
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(appointment(
                com.aurevia.scheduling.AppointmentState.BOOKED), List.of()));
        when(repository.facilityId("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, reject(() -> service.verify(twoIdentifiers())).status());
    }

    // ---- checkIn ----

    @Test
    void checkInRequiresVerificationAndCreatesAWorklistItem() {
        authorized("reception");
        assertEquals("VERIFICATION_REQUIRED",
                reject(() -> service.checkIn(APPOINTMENT, 1)).code());

        when(repository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(appointments.checkIn(APPOINTMENT, 1)).thenReturn(appointment(
                com.aurevia.scheduling.AppointmentState.CHECKED_IN));
        when(repository.insertItem(eq(APPOINTMENT), eq(null), eq(PATIENT), eq(FACILITY_ID),
                anyInt(), eq(null), eq(null))).thenReturn(ITEM);
        when(scheduling.setting("clinic.worklist.priority.NEW")).thenReturn(Optional.of("5"));
        WorklistItem result = service.checkIn(APPOINTMENT, 1);
        assertEquals(ITEM, result.id());
        assertEquals(APPOINTMENT, result.appointmentId());
        assertEquals(5, result.priority());
        assertEquals(NOW, result.startsAt());
        verify(audit).append(any(), eq("VERIFIED_CHECK_IN"), eq("VERIFIED_CHECK_IN"),
                anyString(), anyString(), eq(APPOINTMENT), eq("SUCCESS"));
    }

    @Test
    void checkInPropagatesStaleAndMissingFacility() {
        authorized("reception");
        when(repository.hasVerification(APPOINTMENT)).thenReturn(true);
        doThrow(new ApiException(412, "STALE_VERSION", "stale"))
                .when(appointments).checkIn(APPOINTMENT, 1);
        assertEquals(412, reject(() -> service.checkIn(APPOINTMENT, 1)).status());

        doReturn(appointment(com.aurevia.scheduling.AppointmentState.CHECKED_IN))
                .when(appointments).checkIn(APPOINTMENT, 1);
        when(repository.facilityId("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, reject(() -> service.checkIn(APPOINTMENT, 1)).status());
    }

    @Test
    void checkInValidatesItsIdentifier() {
        assertEquals(422, reject(() -> service.checkIn("not-a-uuid", 1)).status());
    }

    // ---- worklist ----

    @Test
    void worklistIsBoundedAndOrderedByTheRepository() {
        authorized("reception");
        WorklistItem expected = item(null, null, 1);
        when(repository.listWorklist("MAIN", 100)).thenReturn(List.of(expected));
        assertEquals(List.of(expected), service.worklist("MAIN", null));
        assertEquals(List.of(expected), service.worklist("MAIN", 100));
        verify(audit, org.mockito.Mockito.times(2)).append(any(), eq("WORKLIST_READ"),
                eq("WORKLIST_READ"), anyString(), anyString(), eq("MAIN"), eq("SUCCESS"));
    }

    @Test
    void worklistRejectsBadFacilityAndBounds() {
        assertEquals(422, reject(() -> service.worklist("main", null)).status());
        assertEquals(422, reject(() -> service.worklist("MAIN", 0)).status());
        assertEquals(422, reject(() -> service.worklist("MAIN", 201)).status());
    }

    // ---- assign ----

    @Test
    void assignSetsTheAssigneeTimestampAndAppendsAHandoff() {
        authorized("reception");
        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(null, null, 1)));
        when(scheduling.hasActiveMembership("nurse-a")).thenReturn(true);
        when(repository.updateItem(ITEM, 1, "nurse-a", NOW, 0)).thenReturn(1);
        WorklistItem result = service.assign(ITEM, new Assign("nurse-a"), 1);
        assertEquals("nurse-a", result.assigneeSubject());
        assertEquals(NOW, result.assignedAt());
        assertEquals(2, result.version());
        org.mockito.ArgumentCaptor<com.aurevia.scheduling.checkin.HandoffEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.aurevia.scheduling.checkin.HandoffEvent.class);
        verify(repository).insertHandoff(captor.capture());
        assertEquals(ITEM, captor.getValue().itemId());
        assertEquals(null, captor.getValue().fromSubject());
        assertEquals("nurse-a", captor.getValue().toSubject());
        assertEquals(SUBJECT, captor.getValue().actor());
        assertEquals(NOW, captor.getValue().occurredAt());
    }

    @Test
    void assignRejectsBadInputMissingStaleAssignedAndInactive() {
        assertEquals(422, reject(() -> service.assign("no", new Assign("nurse-a"), 1)).status());
        assertEquals(422, reject(() -> service.assign(ITEM, new Assign(" "), 1)).status());

        authorized("reception");
        assertEquals(404, reject(() -> service.assign(ITEM, new Assign("nurse-a"), 1)).status());

        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(null, null, 2)));
        assertEquals(412, reject(() -> service.assign(ITEM, new Assign("nurse-a"), 1)).status());

        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item("other-a", NOW, 1)));
        assertEquals("ALREADY_ASSIGNED",
                reject(() -> service.assign(ITEM, new Assign("nurse-a"), 1)).code());

        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(null, null, 1)));
        assertEquals("INVALID_ASSIGNEE",
                reject(() -> service.assign(ITEM, new Assign("nurse-a"), 1)).code());

        when(scheduling.hasActiveMembership("nurse-a")).thenReturn(true);
        when(repository.updateItem(ITEM, 1, "nurse-a", NOW, 0)).thenReturn(0);
        assertEquals(412, reject(() -> service.assign(ITEM, new Assign("nurse-a"), 1)).status());
        verify(repository, never()).insertHandoff(any());
    }

    // ---- handoff ----

    @Test
    void handoffRequiresAnActiveAssigneeAndRecordsFromToAndReason() {
        authorized("reception");
        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item(null, null, 1)));
        Handoff request = new Handoff("nurse-b", "coverage");
        assertEquals("NO_ACTIVE_ASSIGNEE",
                reject(() -> service.handoff(ITEM, request, 1)).code());

        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item("nurse-a", NOW, 1)));
        when(scheduling.hasActiveMembership("nurse-a")).thenReturn(true);
        when(scheduling.hasActiveMembership("nurse-b")).thenReturn(true);
        when(repository.updateItem(ITEM, 1, "nurse-b", NOW, 0)).thenReturn(1);
        WorklistItem result = service.handoff(ITEM, request, 1);
        assertEquals("nurse-b", result.assigneeSubject());
        assertEquals(2, result.version());
        org.mockito.ArgumentCaptor<com.aurevia.scheduling.checkin.HandoffEvent> captor =
                org.mockito.ArgumentCaptor.forClass(com.aurevia.scheduling.checkin.HandoffEvent.class);
        verify(repository).insertHandoff(captor.capture());
        assertEquals("nurse-a", captor.getValue().fromSubject());
        assertEquals("nurse-b", captor.getValue().toSubject());
        assertEquals("coverage", captor.getValue().reason());
    }

    @Test
    void handoffRejectsBadInputMissingStaleAndInvalidTransitions() {
        assertEquals(422, reject(() -> service.handoff("no", new Handoff("b", "r"), 1)).status());
        assertEquals(422, reject(() -> service.handoff(ITEM, new Handoff(" ", "r"), 1)).status());
        assertEquals(422, reject(() -> service.handoff(ITEM, new Handoff("b", " "), 1)).status());

        authorized("reception");
        assertEquals(404, reject(() -> service.handoff(ITEM, new Handoff("b", "r"), 1)).status());

        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item("nurse-a", NOW, 2)));
        assertEquals(412, reject(() -> service.handoff(ITEM, new Handoff("b", "r"), 1)).status());

        when(repository.findItemForUpdate(ITEM)).thenReturn(Optional.of(item("nurse-a", NOW, 1)));
        assertEquals("ASSIGNEE_INACTIVE", inactiveSourceCode());

        when(scheduling.hasActiveMembership("nurse-a")).thenReturn(true);
        assertEquals("INVALID_ASSIGNEE",
                reject(() -> service.handoff(ITEM, new Handoff("nurse-b", "r"), 1)).code());

        when(scheduling.hasActiveMembership("nurse-b")).thenReturn(true);
        assertEquals("INVALID_HANDOFF",
                reject(() -> service.handoff(ITEM, new Handoff("nurse-a", "r"), 1)).code());

        when(repository.updateItem(ITEM, 1, "nurse-b", NOW, 0)).thenReturn(0);
        assertEquals(412, reject(() -> service.handoff(ITEM, new Handoff("nurse-b", "r"), 1)).status());
    }

    private String inactiveSourceCode() {
        return reject(() -> service.handoff(ITEM, new Handoff("nurse-b", "r"), 1)).code();
    }

    // ---- intake ----

    @Test
    void intakeLinksAnUnscheduledArrival() {
        authorized("reception");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insertItem(eq(null), eq("walk-in"), eq(PATIENT), eq(FACILITY_ID),
                anyInt(), eq(null), eq(null))).thenReturn(ITEM);
        WorklistItem result = service.linkIntake(new Intake("MAIN", PATIENT, "walk-in"));
        assertEquals(ITEM, result.id());
        assertEquals("walk-in", result.intakeReason());
        assertNull(result.appointmentId());
        assertNull(result.startsAt());
    }

    @Test
    void intakeRejectsBadInputAndMissingReferences() {
        assertEquals(422, reject(() -> service.linkIntake(new Intake("main", PATIENT, "r"))).status());
        assertEquals(422, reject(() -> service.linkIntake(new Intake("MAIN", "no", "r"))).status());
        assertEquals(422, reject(() -> service.linkIntake(new Intake("MAIN", PATIENT, " "))).status());

        authorized("reception");
        assertEquals(404, reject(() -> service.linkIntake(new Intake("MAIN", PATIENT, "r"))).status());

        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.facilityId("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, reject(() -> service.linkIntake(new Intake("MAIN", PATIENT, "r"))).status());
    }

    // ---- open encounter ----

    @Test
    void openEncounterRequiresVerificationAndAnEligibleRole() {
        authorized("reception");
        assertEquals("FORBIDDEN", reject(() -> service.openEncounter(APPOINTMENT, 2)).code());

        authorized("clinician");
        assertEquals("VERIFICATION_REQUIRED",
                reject(() -> service.openEncounter(APPOINTMENT, 2)).code());

        when(repository.hasVerification(APPOINTMENT)).thenReturn(true);
        Appointment opened = appointment(com.aurevia.scheduling.AppointmentState.IN_PROGRESS);
        when(appointments.start(APPOINTMENT, 2)).thenReturn(opened);
        assertEquals(com.aurevia.scheduling.AppointmentState.IN_PROGRESS,
                service.openEncounter(APPOINTMENT, 2).state());
        assertEquals(422, reject(() -> service.openEncounter("no", 2)).status());
    }
}
