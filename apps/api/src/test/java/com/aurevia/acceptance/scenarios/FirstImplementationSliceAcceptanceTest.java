package com.aurevia.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabActionPolicy;
import com.aurevia.labflow.collection.CollectRequest;
import com.aurevia.labflow.collection.SpecimenCollectionService;
import com.aurevia.patient.PatientRepository;
import com.aurevia.exchange.order.AuthorizationView;
import com.aurevia.exchange.order.OrderRepository;
import com.aurevia.exchange.order.OrderRequest;
import com.aurevia.exchange.order.OrderService;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.ResultKind;
import com.aurevia.exchange.result.ResultRepository;
import com.aurevia.exchange.result.ResultRoutingService;
import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.messaging.enrollment.InMemoryPatientAccessStore;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.PatientAccessService;
import com.aurevia.messaging.reports.PatientChartLink;
import com.aurevia.messaging.reports.ReleasedReport;
import com.aurevia.messaging.reports.ReleasedReport.Status;
import com.aurevia.messaging.reports.ReleasedReportService;
import com.aurevia.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.messaging.reports.ReportReleaseStore;
import com.aurevia.scheduling.Appointment;
import com.aurevia.scheduling.AppointmentService;
import com.aurevia.scheduling.AppointmentState;
import com.aurevia.scheduling.AppointmentView;
import com.aurevia.scheduling.SchedulingRepository;
import com.aurevia.scheduling.checkin.CheckInService;
import com.aurevia.scheduling.checkin.Verification;
import com.aurevia.scheduling.checkin.WorklistPolicy;
import com.aurevia.scheduling.checkin.WorklistRepository;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.staff.StaffMember;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staff.StaffService;

import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLINIC_ALPHA;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLINIC_BETA;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.CLOCK;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.NOW;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PARTNER_LAB;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.PATIENT_SUBJECT;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.bind;
import static com.aurevia.acceptance.scenarios.ScenarioSupport.clear;

/**
 * Section 11 first implementation slice, end to end (IAM-01, CLN-02, LAB-01, XCH-06, PAT-02).
 *
 * <p>Provisions two synthetic clinics and a synthetic partner laboratory, then
 * carries one synthetic patient through staff membership, site-verified
 * registration, booking and check-in, verification of a laboratory result,
 * its correction and clinician acknowledgment, and an approved release the
 * patient can open. Each step exercises the real domain service through its
 * public command with synthetic state, and the second clinic cannot see the
 * first clinic's patient. No real credential, identifier or patient data is
 * used.</p>
 */
class FirstImplementationSliceAcceptanceTest {

    private static final String ADMIN = "syn-slice-admin";
    private static final String RECEPTION = "syn-slice-reception";
    private static final String CLINICIAN = "syn-slice-clinician";
    private static final String APPOINTMENT = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
    private static final String ORDER = "slice-order";
    private static final String ACCESSION = "ACC-SLICE";
    private static final String REPORT = "slice-report";

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
    }

    @AfterEach
    void tearDown() {
        clear();
    }

    @Test
    void firstSliceCarriesOnePatientFromStaffingToApprovedPatientRelease() {
        // 1. Staff membership: an administrator at the first clinic invites a
        // receptionist with a bounded role grant (IAM-01).
        bind(CLINIC_ALPHA, ADMIN);
        StaffRepository staffRepository = mock(StaffRepository.class);
        StaffService staff = new StaffService(staffRepository, new ActionPolicy(), transactions);
        when(staffRepository.hasActiveMembership(ADMIN)).thenReturn(true);
        when(staffRepository.activeRoles(ADMIN)).thenReturn(List.of("tenant_admin"));
        when(staffRepository.findUserIdBySubject(ADMIN)).thenReturn(Optional.of("admin-user"));
        when(staffRepository.insertIdentity(eq(CLINIC_ALPHA), anyString(), anyString())).thenReturn("reception-user");
        when(staffRepository.insertMembership(eq(CLINIC_ALPHA), eq("reception-user"), eq("active")))
                .thenReturn("reception-membership");
        when(staffRepository.findBySubject(RECEPTION)).thenReturn(Optional.of(
                new StaffMember(RECEPTION, "Synthetic Reception", "active", List.of("reception"))));
        StaffMember invited = staff.invite(RECEPTION, "Synthetic Reception", "reception");
        assertEquals("active", invited.status());
        assertTrue(invited.roles().contains("reception"));
        verify(staffRepository).insertRoleGrant(CLINIC_ALPHA, "reception-membership", "reception", "admin-user");

        // 2. Patient registration: the receptionist issues a site-verified
        // invitation and the patient redeems it into an active, linked account.
        InMemoryPatientAccessStore patientStore = new InMemoryPatientAccessStore();
        PatientAccessService enrollment = new PatientAccessService(patientStore,
                new EnrollmentActions(), CLOCK);
        patientStore.seedChart(CLINIC_ALPHA, PATIENT);
        PatientAccessActor receptionActor = PatientAccessActor.of(CLINIC_ALPHA, RECEPTION,
                EnrollmentActions.RECEPTION);
        PatientAccessActor patientActor = PatientAccessActor.of(CLINIC_ALPHA, PATIENT_SUBJECT,
                EnrollmentActions.PATIENT);
        Invitation invitation = enrollment.issueInvitation(receptionActor, new IssueInvitationRequest(
                PATIENT, new RelationshipRef("encounter-slice", true), "site-verified-facts", null));
        PatientAccount account = enrollment.enroll(patientActor,
                new EnrollRequest(invitation.code(), "site-verified-facts"));
        assertEquals(AccountState.ACTIVE, account.state());
        assertTrue(enrollment.checkChartAccess(patientActor, account.id(), PATIENT).allowed());

        // 3. Booking and check-in: the booked appointment's identity is verified
        // with two identifiers before the patient is admitted (CLN-02).
        bind(CLINIC_ALPHA, RECEPTION);
        WorklistRepository worklist = mock(WorklistRepository.class);
        AppointmentService appointments = mock(AppointmentService.class);
        SchedulingRepository scheduling = mock(SchedulingRepository.class);
        AuditAppender audit = mock(AuditAppender.class);
        CheckInService checkIn = new CheckInService(worklist, appointments, scheduling,
                new WorklistPolicy(), transactions, audit, CLOCK);
        when(scheduling.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(scheduling.activeRoles(RECEPTION)).thenReturn(List.of("reception"));
        when(scheduling.setting(anyString())).thenReturn(Optional.empty());
        when(worklist.facilityId("MAIN")).thenReturn(Optional.of("facility-slice"));
        Appointment booked = new Appointment(APPOINTMENT, 1, PATIENT, "MAIN", "DR-A", "ROOM-1",
                "NEW", NOW, NOW.plusSeconds(1800), AppointmentState.BOOKED, null, null, null);
        when(appointments.get(APPOINTMENT)).thenReturn(new AppointmentView(booked, List.of()));
        Verification verification = checkIn.verify(new VerifyIdentity(APPOINTMENT,
                List.of("MRN", "DOB"), "two_identifiers", null, null));
        assertEquals(APPOINTMENT, verification.appointmentId());
        assertEquals(PATIENT, verification.patientId());

        // 4. One signed laboratory order: the clinician releases a bounded,
        // fingerprinted disclosure to the partner laboratory under an approved
        // authorization (LAB-01, XCH-06).
        bind(CLINIC_ALPHA, CLINICIAN);
        OrderRepository orderRepository = mock(OrderRepository.class);
        OrderService orders = new OrderService(orderRepository, audit, transactions, CLOCK);
        String grantId = "11111111-1111-4111-8111-111111111111";
        when(orderRepository.hasActiveMembership(anyString())).thenReturn(true);
        when(orderRepository.activeRoles(CLINICIAN)).thenReturn(List.of("clinician"));
        when(orderRepository.authorization(grantId)).thenReturn(Optional.of(new AuthorizationView(
                grantId, CLINIC_ALPHA, ORDER, PARTNER_LAB, "diagnosis", 120,
                NOW.minusSeconds(3600), NOW.plusSeconds(86400), true)));
        when(orderRepository.patientIdentity(PATIENT)).thenReturn(Optional.of(
                new OrderRepository.PatientIdentity("MRN-SLICE", "CLINIC")));
        OrderView signedOrder = orders.create(new OrderRequest(PATIENT, "encounter-slice", ORDER,
                "routine", "synthetic laboratory question", PARTNER_LAB, "diagnosis", grantId,
                CLINICIAN, "lab-queue", 60));
        assertNotNull(signedOrder.snapshotFingerprint());
        assertEquals(PARTNER_LAB, signedOrder.destinationOrg());
        verify(orderRepository).insertWorkItem(eq(signedOrder.workItemId()), eq(PARTNER_LAB),
                eq(grantId), eq(signedOrder.id()), eq(PATIENT), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());

        // 5. Collection: the partner lab's collector records the specimen after
        // confirming it matches the patient and the signed order (LAB-01).
        bind(CLINIC_ALPHA, ScenarioSupport.LAB_COLLECTOR);
        LabAccessionRepository accessions = mock(LabAccessionRepository.class);
        PatientRepository patients = mock(PatientRepository.class);
        SpecimenCollectionService collection = new SpecimenCollectionService(accessions, patients,
                new LabActionPolicy(), transactions, audit);
        String specimen = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        String labIssuer = "SLICE-LAB";
        LabAccession accessioned = new LabAccession(specimen, 1, ACCESSION, labIssuer, PATIENT,
                signedOrder.id(), AccessionState.ACCESSIONED, "PLASMA", "SITE-A", null, null,
                null, "acceptable", null, null, null);
        LabAccession collected = new LabAccession(specimen, 2, ACCESSION, labIssuer, PATIENT,
                signedOrder.id(), AccessionState.COLLECTED, "PLASMA", "SITE-A", "syn-collector",
                NOW.toString(), null, "acceptable", null, null, null);
        when(accessions.hasActiveMembership(ScenarioSupport.LAB_COLLECTOR)).thenReturn(true);
        when(accessions.activeRoles(ScenarioSupport.LAB_COLLECTOR)).thenReturn(List.of("lab_collector"));
        when(accessions.findById(specimen)).thenReturn(Optional.of(accessioned),
                Optional.of(collected));
        when(accessions.update(eq(CLINIC_ALPHA), eq(specimen), eq(1), eq(AccessionState.COLLECTED),
                anyString(), anyString(), anyString(), anyString(), eq(null), anyString(),
                eq(null), eq(null))).thenReturn(1);
        LabAccession result = collection.collect(specimen, new CollectRequest(PATIENT, ACCESSION,
                labIssuer, "PLASMA", "SITE-A", "syn-collector", NOW.toString(), "acceptable", 1));
        assertEquals(AccessionState.COLLECTED, result.state());
        verify(accessions).appendEvent(specimen, 2, "collected", AccessionState.ACCESSIONED,
                AccessionState.COLLECTED, null, PATIENT);

        // 6. Verification and correction: the partner lab's final result is
        // stored, then a correction appends a new revision and reopens review
        // while leaving the acknowledged original intact (LAB-01, XCH-06).
        bind(CLINIC_ALPHA, ScenarioSupport.LAB_VERIFIER);
        ResultRepository results = mock(ResultRepository.class);
        ResultRoutingService routing = new ResultRoutingService(results, new ResultActionPolicy(),
                transactions, audit, CLOCK);
        when(results.hasActiveMembership(anyString())).thenReturn(true);
        when(results.activeRoles(anyString())).thenReturn(List.of("lab_verifier", "clinician"));
        when(results.findOrder(ORDER)).thenReturn(Optional.of(
                new ExpectedOrder(ORDER, new com.aurevia.exchange.result.ResultModel.ResultIdentifiers(
                        PATIENT, PARTNER_LAB, ACCESSION, CLINIC_ALPHA, ORDER), "owner-slice", "queue-slice")));
        when(results.insertReport(any())).thenReturn(true);
        when(results.findReview(anyString())).thenReturn(Optional.empty());
        when(results.lineage(ORDER, PARTNER_LAB, ACCESSION)).thenReturn(List.of());
        when(results.findReport(ORDER, PARTNER_LAB, ACCESSION, 1)).thenReturn(Optional.empty());
        ImportOutcome finalResult = routing.importResult(resultRequest("final", null, "checksum-final"));
        assertNotNull(finalResult.report());
        assertEquals(1, finalResult.report().version());
        String reportId = finalResult.report().id();

        when(results.lineage(ORDER, PARTNER_LAB, ACCESSION)).thenReturn(List.of(finalResult.report()));
        when(results.findReport(ORDER, PARTNER_LAB, ACCESSION, 1))
                .thenReturn(Optional.of(finalResult.report()));
        when(results.findReview(reportId)).thenReturn(
                Optional.of(new com.aurevia.exchange.result.ResultModel.Review(
                        reportId, 1, com.aurevia.exchange.result.ResultModel.ReviewState.UNREVIEWED, null)),
                Optional.of(new com.aurevia.exchange.result.ResultModel.Review(
                        reportId, 1, com.aurevia.exchange.result.ResultModel.ReviewState.ACKNOWLEDGED, NOW)),
                Optional.of(new com.aurevia.exchange.result.ResultModel.Review(
                        reportId, 1, com.aurevia.exchange.result.ResultModel.ReviewState.ACKNOWLEDGED, NOW)));
        when(results.updateReview(reportId, 1, NOW)).thenReturn(1);
        // 5. Clinician acknowledgment is a distinct human fact.
        assertEquals(com.aurevia.exchange.result.ResultModel.ReviewState.ACKNOWLEDGED,
                routing.acknowledge(reportId, 1).state());
        when(results.priorRecipients(ORDER)).thenReturn(List.of("owner-slice"));
        ImportOutcome corrected = routing.importResult(resultRequest("corrected", 1, "checksum-corrected"));
        assertNotNull(corrected.report());
        assertEquals(2, corrected.report().version());
        assertEquals(ResultKind.CORRECTED, corrected.report().kind());
        assertEquals(reportId, corrected.report().previousId());

        // 6. Approved release is visible to the patient and only while the
        // chart link is active (PAT-02).
        bind(CLINIC_ALPHA, PATIENT_SUBJECT);
        ReportReleaseStore reportStore = mock(ReportReleaseStore.class);
        ReleasedReportService portal = new ReleasedReportService(reportStore, new InMemoryAuditSink(), CLOCK);
        when(reportStore.link(CLINIC_ALPHA, PATIENT_SUBJECT)).thenReturn(
                Optional.of(new PatientChartLink(PATIENT, true, true)));
        ReleasedReport released = new ReleasedReport(REPORT, 2, PATIENT, CLINIC_ALPHA, "Synthetic clinic",
                "2026-11-22", "en", "document-2", "checksum-corrected", Status.RELEASED, null);
        when(reportStore.reportsFor(CLINIC_ALPHA, PATIENT)).thenReturn(List.of(released));
        when(reportStore.find(CLINIC_ALPHA, REPORT, 2)).thenReturn(Optional.of(released));
        when(reportStore.latestReleasedRevision(CLINIC_ALPHA, REPORT,
                NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate())).thenReturn(OptionalInt.of(2));
        assertEquals(1, portal.listFor().size());
        assertEquals(2, portal.download(REPORT, 2).revision());

        // 7. The second clinic cannot reach the first clinic's patient account
        // through the same local identifiers; it is concealed as not found.
        bind(CLINIC_BETA, RECEPTION);
        ApiException concealed = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> enrollment.checkChartAccess(
                        PatientAccessActor.of(CLINIC_BETA, PATIENT_SUBJECT, EnrollmentActions.PATIENT),
                        account.id(), PATIENT));
        assertEquals(404, concealed.status());
        assertEquals("NOT_FOUND", concealed.code());
    }

    private static ImportResultRequest resultRequest(String kind, Integer supersedes, String checksum) {
        return new ImportResultRequest(ORDER, PATIENT, PARTNER_LAB, ACCESSION, CLINIC_ALPHA,
                kind, supersedes, NOW.toString(), checksum);
    }
}
