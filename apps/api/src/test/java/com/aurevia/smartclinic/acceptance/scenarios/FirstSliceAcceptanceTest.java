package com.aurevia.smartclinic.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.api.contract.IdempotencyKey;
import com.aurevia.smartclinic.authz.ActionPolicy;
import com.aurevia.smartclinic.exchange.result.ResultActionPolicy;
import com.aurevia.smartclinic.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.smartclinic.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.smartclinic.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultKind;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultReport;
import com.aurevia.smartclinic.exchange.result.ResultRepository;
import com.aurevia.smartclinic.exchange.result.ResultRoutingService;
import com.aurevia.smartclinic.lab.accession.AccessionCommands.AccessionRequest;
import com.aurevia.smartclinic.lab.accession.AccessionCommands.CollectRequest;
import com.aurevia.smartclinic.lab.accession.AccessionState;
import com.aurevia.smartclinic.lab.accession.LabAccession;
import com.aurevia.smartclinic.lab.accession.LabAccessionRepository;
import com.aurevia.smartclinic.lab.accession.LabAccessionService;
import com.aurevia.smartclinic.lab.accession.LabActionPolicy;
import com.aurevia.smartclinic.messaging.reports.PatientChartLink;
import com.aurevia.smartclinic.messaging.reports.ReleasedReport;
import com.aurevia.smartclinic.messaging.reports.ReleasedReport.CorrectionContext;
import com.aurevia.smartclinic.messaging.reports.ReleasedReport.Status;
import com.aurevia.smartclinic.messaging.reports.ReleasedReportService;
import com.aurevia.smartclinic.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.smartclinic.messaging.reports.ReportReleaseStore;
import com.aurevia.smartclinic.orderentry.place.DeliveryMode;
import com.aurevia.smartclinic.orderentry.place.OrderDraft;
import com.aurevia.smartclinic.orderentry.place.OrderPlacementService;
import com.aurevia.smartclinic.orderentry.place.OrderSelection;
import com.aurevia.smartclinic.orderentry.place.PlacedOrder;
import com.aurevia.smartclinic.orderentry.place.PlacedOrderRequest;
import com.aurevia.smartclinic.patient.Patient;
import com.aurevia.smartclinic.patient.PatientRepository;
import com.aurevia.smartclinic.patient.PatientService;
import com.aurevia.smartclinic.scheduling.Appointment;
import com.aurevia.smartclinic.scheduling.AppointmentRepository;
import com.aurevia.smartclinic.scheduling.AppointmentService;
import com.aurevia.smartclinic.scheduling.AppointmentState;
import com.aurevia.smartclinic.scheduling.AppointmentView;
import com.aurevia.smartclinic.scheduling.BookingRequest;
import com.aurevia.smartclinic.scheduling.FacilityInfo;
import com.aurevia.smartclinic.scheduling.ResourceView;
import com.aurevia.smartclinic.scheduling.SchedulingRepository;
import com.aurevia.smartclinic.scheduling.VisitTypeView;
import com.aurevia.smartclinic.scheduling.checkin.CheckInService;
import com.aurevia.smartclinic.scheduling.checkin.WorklistItem;
import com.aurevia.smartclinic.scheduling.checkin.WorklistPolicy;
import com.aurevia.smartclinic.scheduling.checkin.WorklistRepository;
import com.aurevia.smartclinic.staff.StaffMember;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staff.StaffService;

/**
 * First implementation slice: one signed laboratory order, end to end.
 *
 * <p>Requirements IAM-01, CLN-02, LAB-01, XCH-06 and PAT-02. Two synthetic
 * clinics with identical local facility and MRN identifiers and a partner lab
 * run staff membership, patient registration, booking, check-in, an immutable
 * placed order, accession and collection, verified result, correction, clinician
 * acknowledgment and an approved release visible to the linked patient. Each
 * stage composes the existing production service with a synthetic double; the
 * released projection is the patient-facing entry point. All identifiers and
 * notes are synthetic; there is no laboratory-order {@code sign} method, so the
 * immutable placement is the signing substitute and result verification is the
 * reported attestation.</p>
 */
class FirstSliceAcceptanceTest extends FirstSliceFixture {

    private static final String ADMIN = "syn-slice-admin";
    private static final String RECEPTION = "syn-slice-reception";
    private static final String CLINICIAN = "syn-slice-clinician";
    private static final String COLLECTOR = "syn-slice-collector";
    private static final String PATIENT_SUBJECT = "syn-slice-patient";
    private static final String APPOINTMENT = "11111111-1111-4111-8111-111111111111";
    private static final String FACILITY_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ACCESSION_ID = "33333333-3333-4333-8333-333333333333";
    private static final String ORDER = "order-slice";
    private static final String SOURCE = "partner-lab";
    private static final String ACCESSION = "ACC-SLICE";
    private static final String REPORT = "report-slice";
    private static final String DEST = "clinic-a";
    private static final String START = "2026-12-07T07:00:00Z";
    private static final String COLLECTED_AT = "2026-12-07T09:15:00Z";
    private static final String KEY = "slice-order-key";

    private StaffRepository staffRepository;
    private PatientRepository patientRepository;
    private AppointmentRepository appointmentRepository;
    private SchedulingRepository schedulingRepository;
    private WorklistRepository worklistRepository;
    private AppointmentService appointments;
    private AppointmentService checkInAppointments;
    private LabAccessionRepository accessionRepository;
    private ResultRepository resultRepository;
    private ReportReleaseStore releaseStore;
    private InMemoryAuditSink releaseAudit;
    private OrderPlacementService orders;
    private LabAccessionService lab;
    private CheckInService checkIn;
    private ResultRoutingService routing;
    private ReleasedReportService patientReports;

    /** Compose every slice service with synthetic repositories and the shared doubles. */
    @BeforeEach
    void setUpSlice() {
        staffRepository = mock(StaffRepository.class);
        patientRepository = mock(PatientRepository.class);
        appointmentRepository = mock(AppointmentRepository.class);
        schedulingRepository = mock(SchedulingRepository.class);
        worklistRepository = mock(WorklistRepository.class);
        accessionRepository = mock(LabAccessionRepository.class);
        resultRepository = mock(ResultRepository.class);
        releaseStore = mock(ReportReleaseStore.class);
        releaseAudit = new InMemoryAuditSink();

        bind(CLINIC_A, ADMIN);
        when(staffRepository.hasActiveMembership(ADMIN)).thenReturn(true);
        when(staffRepository.activeRoles(ADMIN)).thenReturn(List.of("tenant_admin"));
        when(staffRepository.findUserIdBySubject(ADMIN)).thenReturn(Optional.of("admin-id"));
        when(staffRepository.findUserIdBySubject(RECEPTION)).thenReturn(Optional.empty());
        when(staffRepository.findBySubject(RECEPTION)).thenReturn(Optional.of(
                new StaffMember(RECEPTION, "Synthetic Reception", "active", List.of("reception"))));

        orders = new OrderPlacementService(clock());
        lab = new LabAccessionService(accessionRepository, new LabActionPolicy(), transactions, audit);
        routing = new ResultRoutingService(resultRepository, new ResultActionPolicy(), transactions,
                audit, clock());
        patientReports = new ReleasedReportService(releaseStore, releaseAudit, clock());
    }

    @Test
    void twoClinicsAndPartnerLabCompleteOneSignedOrderEndToEnd() {
        bind(CLINIC_A, ADMIN);
        StaffService staff = new StaffService(staffRepository, new ActionPolicy(), transactions);
        StaffMember member = staff.invite(RECEPTION, "Synthetic Reception", "reception");
        assertEquals("active", member.status());
        assertTrue(member.roles().contains("reception"));

        bind(CLINIC_A, RECEPTION);
        PatientService registration = new PatientService(patientRepository, new ActionPolicy(), transactions);
        when(patientRepository.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(patientRepository.activeRoles(RECEPTION)).thenReturn(List.of("reception"));
        when(patientRepository.mrnExists(ISSUER, MRN)).thenReturn(false);
        when(patientRepository.findById(anyString())).thenReturn(Optional.of(patient()));
        assertEquals(PATIENT, registration.register("Synthetic Patient", MRN, ISSUER,
                "1990-05-20", null, "en", null, null, null).id());

        appointments = new AppointmentService(appointmentRepository, schedulingRepository,
                new ActionPolicy(), transactions, audit, clock());
        when(schedulingRepository.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(schedulingRepository.activeRoles(RECEPTION)).thenReturn(List.of("reception"));
        when(schedulingRepository.facility(FACILITY)).thenReturn(Optional.of(new FacilityInfo(
                FACILITY_ID, FACILITY, "UTC")));
        when(schedulingRepository.visitType("NEW")).thenReturn(Optional.of(new VisitTypeView("NEW", 30)));
        when(appointmentRepository.patientExists(PATIENT)).thenReturn(true);
        when(schedulingRepository.resource(FACILITY_ID, "clinician", "DR-A")).thenReturn(Optional.of(
                new ResourceView("r-c", FACILITY, "clinician", "DR-A", "Synthetic clinician")));
        when(schedulingRepository.resource(FACILITY_ID, "room", "ROOM-1")).thenReturn(Optional.of(
                new ResourceView("r-r", FACILITY, "room", "ROOM-1", "Synthetic room")));
        when(schedulingRepository.setting(anyString())).thenReturn(Optional.empty());
        when(schedulingRepository.withinAvailability(anyString(), anyString(), any(), any())).thenReturn(true);
        when(appointmentRepository.find(anyString())).thenReturn(Optional.of(booked(AppointmentState.BOOKED)));
        Appointment booked = appointments.book(new BookingRequest(FACILITY, PATIENT, "NEW",
                "DR-A", "ROOM-1", START, null));
        assertEquals(AppointmentState.BOOKED, booked.state());
        assertEquals(PATIENT, booked.patientId());

        checkInAppointments = mock(AppointmentService.class);
        checkIn = new CheckInService(worklistRepository, checkInAppointments, schedulingRepository,
                new WorklistPolicy(), transactions, audit, clock());
        when(worklistRepository.facilityId(FACILITY)).thenReturn(Optional.of(FACILITY_ID));
        when(worklistRepository.hasVerification(APPOINTMENT)).thenReturn(true);
        when(checkInAppointments.checkIn(APPOINTMENT, 1)).thenReturn(booked(AppointmentState.CHECKED_IN));
        when(worklistRepository.insertItem(eq(APPOINTMENT), eq(null), eq(PATIENT), eq(FACILITY_ID),
                anyInt(), eq(null), eq(null))).thenReturn("worklist-item");
        when(schedulingRepository.setting("clinic.worklist.priority.NEW")).thenReturn(Optional.of("5"));
        WorklistItem item = checkIn.checkIn(APPOINTMENT, 1);
        assertEquals("worklist-item", item.id());
        assertEquals(5, item.priority());

        OrderDraft draft = orders.saveDraft(CLINIC_A, CLINICIAN, Set.of("clinician"), orderRequest());
        PlacedOrder placed = orders.place(CLINIC_A, CLINICIAN, Set.of("clinician"), draft.id(), 1,
                IdempotencyKey.parse(KEY));
        assertEquals(1, placed.version());
        assertEquals(PARTNER_LAB, placed.request().destinationPartnerId());
        assertEquals(placed, orders.place(CLINIC_A, CLINICIAN, Set.of("clinician"), draft.id(), 1,
                IdempotencyKey.parse(KEY)), "placement is immutable and idempotent");

        bind(CLINIC_A, COLLECTOR);
        when(accessionRepository.hasActiveMembership(COLLECTOR)).thenReturn(true);
        when(accessionRepository.activeRoles(COLLECTOR)).thenReturn(List.of("lab_collector"));
        when(accessionRepository.patientExists(PATIENT)).thenReturn(true);
        when(accessionRepository.findById(anyString())).thenReturn(
                Optional.of(accession(AccessionState.ACCESSIONED, 1)),
                Optional.of(accession(AccessionState.ACCESSIONED, 1)),
                Optional.of(accession(AccessionState.COLLECTED, 2)));
        when(accessionRepository.update(anyString(), anyString(), anyInt(), any(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), any())).thenReturn(1);
        assertEquals(AccessionState.ACCESSIONED, lab.accession(new AccessionRequest(
                ACCESSION, "LAB-A", PATIENT, ORDER)).state());
        assertEquals(AccessionState.COLLECTED, lab.collect(ACCESSION_ID, new CollectRequest(
                PATIENT, ACCESSION, "LAB-A", "PLASMA", "SITE-A", "Synthetic Collector",
                COLLECTED_AT, 1)).state());

        bind(CLINIC_A, CLINICIAN);
        when(resultRepository.hasActiveMembership(CLINICIAN)).thenReturn(true);
        when(resultRepository.activeRoles(CLINICIAN)).thenReturn(List.of("lab_verifier", "clinician"));
        when(resultRepository.insertReport(any())).thenReturn(true);
        when(resultRepository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(resultRepository.priorRecipients(ORDER)).thenReturn(List.of());
        when(resultRepository.findReview(anyString())).thenReturn(Optional.empty());
        when(resultRepository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of());
        when(resultRepository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());
        ImportOutcome verified = routing.importResult(request("final", null, "checksum-1"));
        ResultReport finalReport = verified.report();
        assertEquals(1, finalReport.version());

        ResultReport prior = finalReport;
        when(resultRepository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(prior));
        when(resultRepository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(prior));
        ImportOutcome correction = routing.importResult(request("corrected", 1, "checksum-2"));
        ResultReport corrected = correction.report();
        assertEquals(2, corrected.version());
        assertEquals(ResultKind.CORRECTED, corrected.kind());
        assertEquals(prior.id(), corrected.previousId());
        when(resultRepository.findReview(corrected.id())).thenReturn(Optional.of(
                new com.aurevia.smartclinic.exchange.result.ResultModel.Review(corrected.id(), 2,
                        com.aurevia.smartclinic.exchange.result.ResultModel.ReviewState.UNREVIEWED, null)));
        when(resultRepository.updateReview(corrected.id(), 2, NOW)).thenReturn(1);
        assertEquals(2, routing.acknowledge(corrected.id(), 2).version());

        bind(CLINIC_A, PATIENT_SUBJECT);
        when(releaseStore.link(CLINIC_A, PATIENT_SUBJECT)).thenReturn(
                Optional.of(new PatientChartLink(PATIENT, true, true)));
        when(releaseStore.reportsFor(CLINIC_A, PATIENT)).thenReturn(List.of(releasedRevision()));
        when(releaseStore.find(CLINIC_A, REPORT, 2)).thenReturn(Optional.of(releasedRevision()));
        when(releaseStore.latestReleasedRevision(CLINIC_A, REPORT,
                NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate())).thenReturn(OptionalInt.of(2));
        ReleasedReportService.ReportDownload download = patientReports.download(REPORT, 2);
        assertEquals(REPORT, download.reportId());
        assertEquals(2, download.revision());
        assertEquals(1, patientReports.listFor().size());
        assertEquals(2, patientReports.listFor().get(0).revision());
        assertEquals("Synthetic clinic", patientReports.listFor().get(0).issuingOrganization());
        assertEquals("2026-11-20", patientReports.listFor().get(0).effectiveDate());
        assertEquals("synthetic corrected value", patientReports.listFor().get(0).correctionReason());
        assertEquals(1, patientReports.listFor().get(0).supersedesRevision());

        bind(CLINIC_B, PATIENT_SUBJECT);
        when(releaseStore.link(CLINIC_B, PATIENT_SUBJECT)).thenReturn(
                Optional.of(new PatientChartLink(PATIENT_B, true, true)));
        assertTrue(patientReports.listFor().isEmpty(), "the peer clinic cannot see clinic A's release");
        assertNotEquals(CLINIC_A, CLINIC_B);
    }

    @Test
    void staleRevisionCannotBeAcknowledgedAtTheReleaseBoundary() {
        // IAM-01 / XCH-06: an acknowledgment binds to the exact revision the
        // clinician read; a stale version is refused and no review is updated.
        bind(CLINIC_A, CLINICIAN);
        when(resultRepository.hasActiveMembership(CLINICIAN)).thenReturn(true);
        when(resultRepository.activeRoles(CLINICIAN)).thenReturn(List.of("lab_verifier", "clinician"));
        ResultReport first = result(1, ResultKind.FINAL, null, null);
        when(resultRepository.findReview(first.id())).thenReturn(
                Optional.of(new com.aurevia.smartclinic.exchange.result.ResultModel.Review(
                        first.id(), 1, com.aurevia.smartclinic.exchange.result.ResultModel.ReviewState.UNREVIEWED, null)));
        when(resultRepository.updateReview(first.id(), 1, NOW)).thenReturn(0);
        ApiException stale = assertThrows(ApiException.class, () -> routing.acknowledge(first.id(), 1));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());
        verify(resultRepository).updateReview(first.id(), 1, NOW);
    }

    private static Patient patient() {
        return new Patient(PATIENT, 1, MRN, ISSUER, "Synthetic Patient", "synthetic patient",
                "1990-05-20", null, "en", "{}", "[]", "[]");
    }

    private static Appointment booked(AppointmentState state) {
        return new Appointment(APPOINTMENT, 1, PATIENT, FACILITY, "DR-A", "ROOM-1", "NEW",
                Instant.parse(START), Instant.parse(START).plusSeconds(1800), state, null, null, null);
    }

    private static PlacedOrderRequest orderRequest() {
        return new PlacedOrderRequest(PATIENT, "encounter-slice", FACILITY_ID,
                List.of(new OrderSelection("CBC", 1)), "synthetic clinical question", "routine",
                null, PARTNER_LAB, DeliveryMode.ELECTRONIC);
    }

    private static LabAccession accession(AccessionState state, int version) {
        return new LabAccession(ACCESSION_ID, version, ACCESSION, "LAB-A", PATIENT, ORDER,
                state, "PLASMA", "SITE-A", "Synthetic Collector", COLLECTED_AT,
                null, null, null, null, null);
    }

    private static ExpectedOrder order() {
        return new ExpectedOrder(ORDER, ids(), "owner-slice", "coverage-queue");
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DEST, ORDER);
    }

    private static ImportResultRequest request(String kind, Integer supersedes, String checksum) {
        return new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, kind, supersedes,
                NOW.toString(), checksum);
    }

    private static ResultReport result(int version, ResultKind kind, Integer supersedes, String previousId) {
        String id = version == 1 ? "99999999-9999-4999-8999-999999999999" : "88888888-8888-4888-8888-888888888888";
        return new ResultReport(id, version, ORDER, ids(), kind, supersedes, previousId,
                "owner-slice", "coverage-queue", NOW, NOW, "checksum-1");
    }

    private static ReleasedReport releasedRevision() {
        return new ReleasedReport(REPORT, 2, PATIENT, CLINIC_A, "Synthetic clinic", "2026-11-20",
                "en", "document-2", "checksum-2", Status.RELEASED, new CorrectionContext(1,
                "synthetic corrected value"));
    }
}
