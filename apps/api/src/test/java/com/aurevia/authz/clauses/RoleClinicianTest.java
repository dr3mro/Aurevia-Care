package com.aurevia.authz.clauses;

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
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.audit.AuditAppender;
import com.aurevia.clinical.notes.ClinicalNoteRevision;
import com.aurevia.clinical.notes.ClinicalNoteService;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.clinical.notes.NoteRepository;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.clinical.notes.RevisionInput;
import com.aurevia.exchange.release.AuthorizeRelease;
import com.aurevia.exchange.release.PatientRelease;
import com.aurevia.exchange.release.ReleaseActionPolicy;
import com.aurevia.exchange.release.ReleaseKind;
import com.aurevia.exchange.release.ReleaseRepository;
import com.aurevia.exchange.release.ReleaseService;
import com.aurevia.exchange.release.ReleaseState;
import com.aurevia.exchange.result.ResultActionPolicy;
import com.aurevia.orderentry.place.DeliveryMode;
import com.aurevia.orderentry.place.OrderDraft;
import com.aurevia.orderentry.place.OrderPlacementService;
import com.aurevia.orderentry.place.OrderSelection;
import com.aurevia.orderentry.place.PlacedOrder;
import com.aurevia.orderentry.place.PlacedOrderRequest;
import com.aurevia.prescription.PrescriptionActions;
import com.aurevia.prescription.rx.RxActions;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;
import com.aurevia.prescription.rx.RxController;
import com.aurevia.prescription.rx.RxRepository;
import com.aurevia.prescription.rx.RxService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * IAM-01 section-2 role-matrix proof for the Clinician row: document and sign,
 * order, prescribe where authorized, reconcile and release, bounded by care
 * relationship, privileges and facility.
 *
 * <p>Each assertion drives the real authorization decision of the owning
 * service or action policy. Active-membership facts and row visibility are the
 * only mocked boundaries, mirroring {@code ClinicalNoteServiceTest} and
 * {@code RequirePrescriberAttestationMarketEntitlementTest}. The prescription
 * cases enter through {@code RxController} under MockMvc with synthetic headers
 * so tenant and subject come from the request context, never from the body.
 * Two ACTIVE absence-pinning tests record behaviour this clause still lacks
 * (clinician reconciliation and facility narrowing of clinical verbs); they
 * are named and commented so the enabling task flips them to positive proofs.
 * No production file, existing test, contract or migration is changed here.</p>
 */
class RoleClinicianTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "clinician-a";
    private static final String OTHER_CLINICIAN = "clinician-b";
    private static final String RECEPTION = "reception-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String NOTE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String RX_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String REVISION = "LAB-REV-0001";
    private static final String ENCOUNTER = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Set<String> CLINICIAN = Set.of("clinician");

    private static final String CONTENT = "Clinical content";

    private NoteRepository notes;
    private AuditAppender noteAudit;
    private ClinicalNoteService clinicalNotes;

    private OrderPlacementService orders;

    private RxRepository prescriptions;
    private AuditAppender prescriptionAudit;
    private MockMvc prescriptionMvc;

    private ReleaseRepository releases;
    private ReleaseService releaseService;

    /** Build the four real domain components over mocked persistence boundaries. */
    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "CLINICAL_ROLE_PROOF", "corr-clinician"));

        notes = mock(NoteRepository.class);
        noteAudit = mock(AuditAppender.class);
        clinicalNotes = new ClinicalNoteService(notes, inlineTransactions(), noteAudit,
                Clock.fixed(NOW, ZoneOffset.UTC));

        orders = new OrderPlacementService(Clock.fixed(NOW, ZoneOffset.UTC));

        prescriptions = mock(RxRepository.class);
        prescriptionAudit = mock(AuditAppender.class);
        RxService prescriptionService = new RxService(prescriptions, new RxActions(),
                inlineTransactions(), prescriptionAudit);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        prescriptionMvc = MockMvcBuilders.standaloneSetup(new RxController(prescriptionService))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        // An entitled prescriber who owns a draft is the default; each negative
        // overrides exactly the one guard it puts under test.
        when(prescriptions.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(prescriptions.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        when(prescriptions.loadForUpdate(RX_ID)).thenReturn(Optional.of(prescription(State.DRAFT, 1)));
        when(prescriptions.find(RX_ID)).thenReturn(Optional.of(prescription(State.ATTESTED, 2)));
        when(prescriptions.approvedSetting("rx02.market")).thenReturn(Optional.of("US"));
        when(prescriptions.approvedSetting("rx02.entitlement." + SUBJECT)).thenReturn(Optional.of("enabled"));

        releases = mock(ReleaseRepository.class);
        releaseService = new ReleaseService(releases, new ReleaseActionPolicy(),
                inlineTransactions(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Clear the request-scoped identity so a denial cannot leak into the next test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A transaction template that executes its callback inline, without a database. */
    private static TransactionTemplate inlineTransactions() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(template).execute(any());
        return template;
    }

    /** Give the note service an active membership with the supplied fresh roles. */
    private void noteRoles(String... roles) {
        when(notes.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(notes.activeRoles(SUBJECT)).thenReturn(List.of(roles));
    }

    /** Build a draft revision with valid content and checksum for the sign entry point. */
    private static ClinicalNoteRevision revision(NoteState state, int version) {
        return new ClinicalNoteRevision(NOTE, version, state, PATIENT, null,
                "Visit note", CONTENT, "en", "SOAP", 1,
                "author-a", SUBJECT, NOW.toString(), null, null, null,
                NoteChecksum.sha256(CONTENT), null, null, null, "[]", NOW.toString());
    }

    /** Build a synthetic order request for one facility. */
    private static PlacedOrderRequest orderRequest(String facilityCode) {
        return new PlacedOrderRequest(PATIENT, ENCOUNTER, facilityCode,
                List.of(new OrderSelection("item-cbc", 2)), "rule out anaemia",
                "routine", "no preparation", "partner-lab", DeliveryMode.BOTH);
    }

    /** Build a synthetically owned prescription draft in the requested state and version. */
    private static Prescription prescription(State state, int version) {
        return new Prescription(RX_ID, version, PATIENT, "amoxicillin", "500 mg", "1 capsule", "oral",
                "twice daily", 30, SUBJECT, "pharmacy-x", state, null, null, false, NOW);
    }

    /** Send the real attest command through the controller with synthetic identity headers. */
    private ResultActions attest(String subject, String organization, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/prescriptions/{id}/attest", RX_ID)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"synthetic attestation\"}");
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return prescriptionMvc.perform(request);
    }

    /** Build a valid immediate-release command for the release entry point. */
    private static AuthorizeRelease immediateRelease() {
        return new AuthorizeRelease(REVISION, 1, PATIENT, 2, "IMMEDIATE", null, null, null);
    }

    /** Build the committed release row the repository returns after authorization. */
    private static PatientRelease authorizedRelease() {
        return new PatientRelease("rel-1", ORG, PATIENT, REVISION, 1, ORG, 2,
                ReleaseKind.IMMEDIATE, ReleaseState.AUTHORIZED, NOW, null, null, null, null, null,
                1, "author-a", NOW);
    }

    @Test
    void clinicianMayDocumentAndSign() {
        noteRoles("clinician");
        when(notes.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)),
                Optional.of(revision(NoteState.SIGNED, 2)));
        when(notes.patientExists(PATIENT)).thenReturn(true);
        when(notes.insert(eq(ORG), eq(NOTE), any(RevisionInput.class))).thenReturn(1);

        ClinicalNoteRevision signed = clinicalNotes.sign(NOTE, 1, "I reviewed this note");

        assertEquals(NoteState.SIGNED, signed.state());
        assertEquals(2, signed.version());
        ArgumentCaptor<RevisionInput> input = ArgumentCaptor.forClass(RevisionInput.class);
        verify(notes).insert(eq(ORG), eq(NOTE), input.capture());
        assertEquals("I reviewed this note", input.getValue().attestation());
        verify(noteAudit).append(any(), anyString(), eq(ClinicalNoteService.SIGN),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void receptionCannotSign() {
        noteRoles("reception");

        ApiException denial = assertThrows(ApiException.class, () -> clinicalNotes.sign(NOTE, 1, "attest"));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("missing clinical note permission"));
        verify(notes, never()).lockNote(NOTE);
        verify(notes, never()).insert(anyString(), anyString(), any());
        verify(noteAudit).append(any(), anyString(), eq(ClinicalNoteService.SIGN),
                anyString(), anyString(), anyString(), eq("DENIED"));
    }

    @Test
    void staleDraftVersionOnSignIsRefused() {
        noteRoles("clinician");
        when(notes.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 3)));

        ApiException denial = assertThrows(ApiException.class, () -> clinicalNotes.sign(NOTE, 1, "attest"));

        assertEquals(412, denial.status());
        assertEquals("STALE_VERSION", denial.code());
    }

    @Test
    void signRefusesAWrongPatientContext() {
        noteRoles("clinician");
        when(notes.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        when(notes.patientExists(PATIENT)).thenReturn(false);

        ApiException denial = assertThrows(ApiException.class, () -> clinicalNotes.sign(NOTE, 1, "attest"));

        assertEquals(422, denial.status());
        assertEquals("INVALID_PATIENT_CONTEXT", denial.code());
    }

    @Test
    void noteWriteDeniesInactiveMembership() {
        when(notes.hasActiveMembership(SUBJECT)).thenReturn(false);
        when(notes.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));

        ApiException denial = assertThrows(ApiException.class, () -> clinicalNotes.sign(NOTE, 1, "attest"));

        assertEquals(403, denial.status());
        assertTrue(denial.getMessage().contains("no active membership"));
        verify(noteAudit).append(any(), anyString(), eq(ClinicalNoteService.SIGN),
                anyString(), anyString(), anyString(), eq("DENIED"));
    }

    @Test
    void clinicianMayPlaceAnOrder() {
        OrderDraft draft = orders.saveDraft(ORG, SUBJECT, CLINICIAN, orderRequest("MAIN"));
        assertEquals(ORG, draft.organizationId());
        assertEquals(SUBJECT, draft.createdBy());
        assertEquals(1, draft.version());

        PlacedOrder placed = orders.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                new IdempotencyKey("key-clinician"));
        assertEquals(ORG, placed.organizationId());
        assertEquals(SUBJECT, placed.placedBy());
        assertEquals(1, placed.version());
    }

    @Test
    void tenantAdminCannotPlaceOrder() {
        OrderDraft draft = orders.saveDraft(ORG, SUBJECT, CLINICIAN, orderRequest("MAIN"));

        ApiException denial = assertThrows(ApiException.class,
                () -> orders.place(ORG, SUBJECT, Set.of("tenant_admin"), draft.id(), 1,
                        new IdempotencyKey("key-tenant-admin")));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("clinician ordering privilege"));
    }

    @Test
    void clinicianOfAnotherTenantIsDenied() {
        OrderDraft draft = orders.saveDraft(ORG, SUBJECT, CLINICIAN, orderRequest("MAIN"));

        // This is tenant-key isolation in the placement kernel, not a forced-RLS
        // read; real RLS concealment is proven by RxTransmissionFlowTest.
        ApiException denial = assertThrows(ApiException.class,
                () -> orders.get(OTHER_ORG, SUBJECT, CLINICIAN, draft.id()));

        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
    }

    @Test
    void clinicianMayPrescribeWithPrivilege() {
        RxActions rx = new RxActions();
        PrescriptionActions prescribing = new PrescriptionActions();
        for (String action : List.of(RxActions.CREATE, RxActions.ATTEST, RxActions.TRANSMIT,
                RxActions.VERIFY, RxActions.RETRY, RxActions.RENEW, RxActions.CANCEL, RxActions.READ)) {
            assertTrue(rx.allows(action, "clinician"), action + " must be clinician-permitted");
        }
        assertTrue(rx.allows(RxActions.CONFIRM, "clinician"));
        assertTrue(prescribing.allows(PrescriptionActions.CREATE_DRAFT, "clinician"));
        assertFalse(rx.allows(RxActions.DISPENSE, "clinician"),
                "document release evidence belongs to pharmacy staff, not the prescriber");
    }

    @Test
    void clinicianMayAttestAPrescriptionWithAnEnabledEntitlement() throws Exception {
        attest(SUBJECT, ORG, "1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("attested"))
                .andExpect(jsonPath("$.version").value(2));

        verify(prescriptions).attest(RX_ID, 1);
        verify(prescriptionAudit).append(any(), eq(RxActions.ATTEST), eq(RxActions.ATTEST),
                any(), any(), eq(RX_ID), eq("SUCCESS"));
    }

    @Test
    void receptionCannotPrescribe() throws Exception {
        when(prescriptions.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(prescriptions.activeRoles(RECEPTION)).thenReturn(List.of("reception"));

        attest(RECEPTION, ORG, "1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // The role gate denies before a row is read, unlike the ownership denial.
        verify(prescriptions, never()).loadForUpdate(anyString());
        verify(prescriptions, never()).attest(anyString(), anyInt());
        verify(prescriptionAudit).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
    }

    @Test
    void careRelationshipLimitsPrescribingToThePrescriber() throws Exception {
        when(prescriptions.hasActiveMembership(OTHER_CLINICIAN)).thenReturn(true);
        when(prescriptions.activeRoles(OTHER_CLINICIAN)).thenReturn(List.of("clinician"));

        attest(OTHER_CLINICIAN, ORG, "1")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // The clinician role passed the gate; ownership is what refused this actor.
        verify(prescriptions).loadForUpdate(RX_ID);
        verify(prescriptions, never()).attest(anyString(), anyInt());
        verify(prescriptions, never()).appendEvent(anyString(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void prescribingRequiresAnEnabledMarketEntitlement() throws Exception {
        when(prescriptions.approvedSetting("rx02.entitlement." + SUBJECT)).thenReturn(Optional.of("disabled"));

        attest(SUBJECT, ORG, "1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ENTITLEMENT"));

        verify(prescriptions, never()).attest(anyString(), anyInt());
    }

    @Test
    void clinicianMayAuthorizeARelease() {
        when(releases.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(releases.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        when(releases.insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any())).thenReturn("rel-1");
        when(releases.findRelease("rel-1")).thenReturn(Optional.of(authorizedRelease()));

        PatientRelease result = releaseService.authorize(immediateRelease());

        assertEquals(ReleaseState.AUTHORIZED, result.state());
        verify(releases).insertEvent(TenantContext.current(), "rel-1", "RELEASE_AUTHORIZE",
                null, "AUTHORIZED", null);
        verify(releases).insertNotification(TenantContext.current(), "rel-1",
                "RELEASE_AUTHORIZED", "linked-patient");
        verify(releases).appendAudit(TenantContext.current(), "RELEASE_AUTHORIZE", REVISION, "SUCCESS");
        // Verify-release of a lab report is a separate lab_verifier specialty duty.
        assertFalse(new ReleaseActionPolicy().allows(ReleaseActionPolicy.AUTHORIZE, "lab_verifier"));
    }

    @Test
    void receptionCannotAuthorizeARelease() {
        TenantContext.set(new TenantContext(ORG, RECEPTION, "CLINICAL_ROLE_PROOF", "corr-clinician"));
        when(releases.hasActiveMembership(RECEPTION)).thenReturn(true);
        when(releases.activeRoles(RECEPTION)).thenReturn(List.of("reception"));

        ApiException denial = assertThrows(ApiException.class,
                () -> releaseService.authorize(immediateRelease()));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(releases).appendAudit(TenantContext.current(), "RELEASE_AUTHORIZE", REVISION, "DENIED");
        verify(releases, never()).insertRelease(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyInt(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void reconcileIsCurrentlyDeniedToClinicianGap() {
        ResultActionPolicy policy = new ResultActionPolicy();
        // ACTIVE GAP tripwire. The clause grants reconciliation to the clinician,
        // but ResultActionPolicy currently excludes that role from both queue
        // actions. The enabling task must flip these two assertions to true and
        // rename this method as the positive proof; until then they pin the gap.
        assertFalse(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "clinician"));
        assertFalse(policy.allows(ResultActionPolicy.RECONCILIATION_RESOLVE, "clinician"));
        // Result review and acknowledgement are built and distinct from resolution.
        assertTrue(policy.allows(ResultActionPolicy.RESULT_READ, "clinician"));
        assertTrue(policy.allows(ResultActionPolicy.REVIEW_ACK, "clinician"));
    }

    @Test
    void facilityScopeIsNotConsultedByClinicalOrderingGap() {
        // ACTIVE GAP tripwire. "MAIN" is the assigned facility; "NORTH" and the
        // unassigned encounter are out of the care relationship. The placement
        // kernel currently stores the caller's facilityId verbatim and never
        // consults an assignment, so this succeeds. When facility/care scope is
        // wired into clinical verbs, flip this to a 403 denial with no draft.
        OrderDraft outOfScope = orders.saveDraft(ORG, SUBJECT, CLINICIAN, orderRequest("NORTH"));

        assertEquals("NORTH", outOfScope.request().facilityId());
        assertEquals(1, outOfScope.version());
    }
}
