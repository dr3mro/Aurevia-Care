package com.aurevia.smartclinic.authz.clauses;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.api.authorization.CommandAuthorization;
import com.aurevia.smartclinic.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.smartclinic.api.authorization.CommandAuthorization.Decision;
import com.aurevia.smartclinic.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.smartclinic.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.smartclinic.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.smartclinic.api.authorization.CommandCatalog;
import com.aurevia.smartclinic.api.authorization.InMemoryDecisionSink;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.authz.ActionPolicy;
import com.aurevia.smartclinic.messaging.enrollment.EnrollmentActions;
import com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract.EventType;
import com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.smartclinic.messaging.enrollment.InMemoryPatientAccessStore;
import com.aurevia.smartclinic.messaging.enrollment.PatientAccessActor;
import com.aurevia.smartclinic.messaging.enrollment.PatientAccessController;
import com.aurevia.smartclinic.messaging.enrollment.PatientAccessService;
import com.aurevia.smartclinic.patient.identity.IdentityActionPolicy;
import com.aurevia.smartclinic.scheduling.Appointment;
import com.aurevia.smartclinic.scheduling.AppointmentService;
import com.aurevia.smartclinic.scheduling.AppointmentState;
import com.aurevia.smartclinic.scheduling.SchedulingRepository;
import com.aurevia.smartclinic.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.smartclinic.scheduling.checkin.CheckInService;
import com.aurevia.smartclinic.scheduling.checkin.WorklistItem;
import com.aurevia.smartclinic.scheduling.checkin.WorklistPolicy;
import com.aurevia.smartclinic.scheduling.checkin.WorklistRepository;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * IAM-01 section 2 role matrix (API evidence): the Reception row.
 *
 * <p>Reception may register, schedule, check in and manage patient contact data inside
 * its assigned facilities and demographic/scheduling fields. Each allowed verb maps to
 * an authoritative production policy; where the rule is enforced at a service or HTTP
 * entry point the test drives that entry point, and the remaining policies are asserted
 * directly. Each refused verb is denied before the domain action runs. Facility and organization
 * scope are narrowed by the check-in commands and the command gate respectively.
 * Field-level narrowing of the allowed demographic/scheduling fields is not implemented
 * and is recorded in the clause document; no test here is skipped. All fixtures are
 * synthetic and carry no patient data.</p>
 */
class RoleReceptionTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String HOME_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "reception-actor";
    private static final String PATIENT_SUBJECT = "patient-actor";
    private static final String APPOINTMENT = "33333333-3333-3333-3333-333333333333";
    private static final String PATIENT = "44444444-4444-4444-4444-444444444444";
    private static final String ITEM = "55555555-5555-5555-5555-555555555555";
    private static final String FACILITY = "66666666-6666-6666-6666-666666666666";
    private static final String CHART = "77777777-7777-7777-7777-777777777777";
    private static final String BASE = "/api/v1/patient/access";
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private WorklistRepository worklistRepository;
    private AppointmentService appointments;
    private SchedulingRepository scheduling;
    private AuditAppender audit;
    private CheckInService checkIn;
    private ActionPolicy actions;
    private EnrollmentActions enrollmentActions;
    private InMemoryPatientAccessStore patientAccessStore;
    private MockMvc mvc;
    private String accountId;

    /** Build the real policies and services over mocked repository boundaries. */
    @BeforeEach
    void setUp() {
        worklistRepository = mock(WorklistRepository.class);
        appointments = mock(AppointmentService.class);
        scheduling = mock(SchedulingRepository.class);
        audit = mock(AuditAppender.class);
        actions = new ActionPolicy();
        enrollmentActions = new EnrollmentActions();
        checkIn = new CheckInService(worklistRepository, appointments, scheduling, new WorklistPolicy(),
                transactions(), audit, CLOCK);
        TenantContext.set(new TenantContext(ORG, SUBJECT, "CHECK_IN_WORKLIST", "corr-reception"));

        patientAccessStore = new InMemoryPatientAccessStore();
        patientAccessStore.seedChart(ORG, CHART);
        PatientAccessService access = new PatientAccessService(patientAccessStore, enrollmentActions, CLOCK);
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(anyString())).thenAnswer(invocation ->
                SUBJECT.equals(invocation.getArgument(0))
                        ? List.of(EnrollmentActions.RECEPTION)
                        : List.of(EnrollmentActions.PATIENT));
        PatientAccessController controller = new PatientAccessController(access, staff, transactions());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        String invitation = access.issueInvitation(PatientAccessActor.of(ORG, SUBJECT, "reception"),
                new IssueInvitationRequest(CHART, new RelationshipRef("enc", true), "site-facts", null)).code();
        accountId = access.enroll(PatientAccessActor.of(ORG, PATIENT_SUBJECT, "patient"),
                new com.aurevia.smartclinic.messaging.enrollment.EnrollmentContract.EnrollRequest(
                        invitation, "site-facts")).id();
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

    /** A synthetic appointment in the booked state. */
    private static Appointment appointment() {
        return new Appointment(APPOINTMENT, 1, PATIENT, "MAIN", "DR-A", "ROOM-1",
                "NEW", NOW, NOW.plusSeconds(1800), AppointmentState.BOOKED, null, null, null);
    }

    private static ApiException denial(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authed(
            String subject, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Synthetic-Subject", subject).header("X-Active-Organization", ORG);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder contactChange(
            String subject, String body) {
        return authed(subject, post(BASE + "/accounts/contact"))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ---- allowed workflow ----

    @Test
    void registerAsReceptionIsAllowed() {
        assertTrue(actions.allows("PATIENT_REGISTER", "reception"));
        assertTrue(enrollmentActions.allows(EnrollmentActions.INVITE_ISSUE, Set.of("reception")));
        assertTrue(new IdentityActionPolicy().allows(IdentityActionPolicy.ACCOUNT_ENROLL, "reception"));
        assertFalse(actions.allows("PATIENT_REGISTER", "patient"));
    }

    @Test
    void scheduleAsReceptionIsAllowed() {
        for (String action : List.of(ActionPolicy.APPOINTMENT_BOOK, ActionPolicy.APPOINTMENT_HOLD,
                ActionPolicy.APPOINTMENT_CONFIRM, ActionPolicy.APPOINTMENT_RESCHEDULE,
                ActionPolicy.APPOINTMENT_CANCEL, ActionPolicy.APPOINTMENT_NO_SHOW)) {
            assertTrue(actions.allows(action, "reception"), action + " must be allowed for reception");
        }
    }

    @Test
    void checkInAsReceptionIsAllowed() {
        authorized("reception");
        WorklistPolicy worklistPolicy = new WorklistPolicy();
        assertTrue(worklistPolicy.allows(WorklistPolicy.IDENTITY_VERIFY, "reception"));
        assertTrue(worklistPolicy.allows(WorklistPolicy.WORKLIST_READ, "reception"));
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(appointments.checkIn(APPOINTMENT, 1)).thenReturn(appointment());
        when(worklistRepository.facilityId("MAIN")).thenReturn(Optional.of(FACILITY));
        when(worklistRepository.insertItem(eq(APPOINTMENT), any(), eq(PATIENT), eq(FACILITY),
                anyInt(), any(), any())).thenReturn(ITEM);

        WorklistItem item = checkIn.checkIn(APPOINTMENT, 1);

        assertEquals(ITEM, item.id());
        verify(audit).append(any(), eq("VERIFIED_CHECK_IN"), eq("VERIFIED_CHECK_IN"),
                eq("WORKLIST_ITEM"), anyString(), eq(APPOINTMENT), eq("SUCCESS"));
    }

    @Test
    void manageContactDataAsReceptionIsAllowed() throws Exception {
        String body = "{\"accountId\":\"" + accountId + "\",\"contactDigest\":\"new-contact\"}";

        mvc.perform(contactChange(SUBJECT, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));

        assertTrue(patientAccessStore.audit(ORG).stream().anyMatch(event ->
                event.eventType() == EventType.CONTACT_CHANGED && "ownership_unchanged".equals(event.detail())));
    }

    // ---- refused verbs of other roles ----

    @Test
    void receptionCannotStartOrCompleteEncounter() {
        assertFalse(actions.allows(ActionPolicy.APPOINTMENT_START, "reception"));
        assertFalse(actions.allows(ActionPolicy.APPOINTMENT_COMPLETE, "reception"));
        assertTrue(actions.allows(ActionPolicy.APPOINTMENT_START, "clinician"));
        assertTrue(actions.allows(ActionPolicy.APPOINTMENT_COMPLETE, "clinician"));
    }

    @Test
    void receptionCannotRecordVitalsOrOpenEncounter() {
        authorized("reception");
        WorklistPolicy worklistPolicy = new WorklistPolicy();
        assertFalse(worklistPolicy.allows(WorklistPolicy.VITALS_RECORD, "reception"));
        assertFalse(worklistPolicy.allows(WorklistPolicy.ENCOUNTER_OPEN, "reception"));
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);

        ApiException failure = denial(() -> checkIn.openEncounter(APPOINTMENT, 1));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("missing worklist permission"));
        verify(appointments, never()).start(anyString(), anyInt());
        verify(audit).append(any(), eq(WorklistPolicy.ENCOUNTER_OPEN), eq(WorklistPolicy.ENCOUNTER_OPEN),
                eq("WORKLIST_ITEM"), anyString(), eq(APPOINTMENT), eq("DENIED"));
    }

    @Test
    void receptionCannotInviteStaffOrApproveConfig() {
        assertFalse(actions.allows("STAFF_INVITE", "reception"));
        assertFalse(actions.allows("CONFIG_APPROVE", "reception"));
        assertTrue(actions.allows("STAFF_INVITE", "tenant_admin"));
        assertTrue(actions.allows("CONFIG_APPROVE", "tenant_admin"));
        IdentityActionPolicy identity = new IdentityActionPolicy();
        assertFalse(identity.allows(IdentityActionPolicy.LINK_UNLINK, "reception"));
        assertFalse(identity.allows(IdentityActionPolicy.LINK_REVERIFY, "reception"));
        assertTrue(identity.allows(IdentityActionPolicy.LINK_UNLINK, "privacy_auditor"));
    }

    @Test
    void patientSubjectCannotUseReceptionRoute() throws Exception {
        assertFalse(enrollmentActions.allows(EnrollmentActions.CONTACT_EDIT_STAFF, Set.of("patient")));
        assertFalse(enrollmentActions.allows(EnrollmentActions.INVITE_ISSUE, Set.of("patient")));
        assertTrue(enrollmentActions.allows(EnrollmentActions.INVITE_REDEEM, Set.of("patient")));
        assertTrue(enrollmentActions.allows(EnrollmentActions.SENSITIVE_CHANGE, Set.of("patient")));
        String body = "{\"accountId\":\"" + accountId + "\",\"contactDigest\":\"new-contact\"}";

        mvc.perform(contactChange(PATIENT_SUBJECT, body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---- scope narrowing ----

    @Test
    void anotherFacilityIsRefused() {
        authorized("reception");
        when(worklistRepository.outsideAssignedFacilities(SUBJECT, "NORTH")).thenReturn(true);

        ApiException failure = denial(() -> checkIn.worklist("NORTH", null));

        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        assertTrue(failure.getMessage().contains("outside the assigned scope"));
        verify(worklistRepository, never()).listWorklist(anyString(), anyInt());
        verify(audit).append(any(), eq(WorklistPolicy.WORKLIST_READ), eq(WorklistPolicy.WORKLIST_READ),
                eq("WORKLIST_ITEM"), anyString(), eq("NORTH"), eq("DENIED"));
    }

    @Test
    void anotherOrganizationIsRefused() {
        MembershipResolver homeOnly = identity ->
                new MemberContext(List.of("reception"), HOME_ORG.equals(identity.organizationId()));
        CommandAuthorization authorization = CommandAuthorization.synthetic(
                CommandCatalog.synthetic(), homeOnly, new InMemoryDecisionSink(), CLOCK);
        CommandRequest request = new CommandRequest("registerPatient", "tenant_admin", List.of());

        Decision home = authorization.authorize(request,
                new ServerIdentity(HOME_ORG, SUBJECT, "corr-home"));
        assertTrue(home.allowed());

        ApiException denial = assertThrows(ApiException.class, () -> authorization.authorize(request,
                new ServerIdentity(ORG, SUBJECT, "corr-other")));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
    }

    @Test
    void handoffOfUnassignedItemIsRefused() {
        authorized("reception");
        when(worklistRepository.findItemForUpdate(ITEM)).thenReturn(
                Optional.of(new WorklistItem(ITEM, APPOINTMENT, null, PATIENT, "MAIN", NOW, 0,
                        null, null, 1)));

        ApiException failure = denial(() -> checkIn.handoff(ITEM, new Handoff("nurse-b", "coverage"), 1));

        assertEquals(409, failure.status());
        assertEquals("NO_ACTIVE_ASSIGNEE", failure.code());
        verify(worklistRepository, never()).updateItem(anyString(), anyInt(), anyString(), any(), anyInt());
        verify(worklistRepository, never()).insertHandoff(any());
    }
}
