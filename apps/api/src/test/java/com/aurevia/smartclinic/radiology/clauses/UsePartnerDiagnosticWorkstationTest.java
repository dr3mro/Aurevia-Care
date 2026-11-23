package com.aurevia.smartclinic.radiology.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.radiology.ImagingController;
import com.aurevia.smartclinic.radiology.ImagingOrder;
import com.aurevia.smartclinic.radiology.ImagingService;
import com.aurevia.smartclinic.radiology.RadCommands.AccessionCommand;
import com.aurevia.smartclinic.radiology.RadCommands.IntakeCommand;
import com.aurevia.smartclinic.radiology.RadCommands.ScheduleCommand;
import com.aurevia.smartclinic.radiology.RadPolicyCatalog;
import com.aurevia.smartclinic.radiology.RadTypes.Modality;
import com.aurevia.smartclinic.radiology.RadTypes.OrderState;
import com.aurevia.smartclinic.radiology.RadTypes.RadEventType;
import com.aurevia.smartclinic.radiology.RadTypes.Urgency;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * RAD-01 clause 2: an external study is associated only through the partner diagnostic workstation.
 *
 * <p>The study-link HTTP route requires a non-blank workstation reference and records it on the
 * new order revision; image pixels are never fetched by this service. This clause test drives
 * that rule through {@code POST /api/v1/imaging/orders/{orderId}/studies} with an accessioned
 * synthetic order and asserts that each refusal leaves the order and its event history untouched.
 * Only synthetic identifiers are used; no patient data or credentials are involved.</p>
 */
class UsePartnerDiagnosticWorkstationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "radiology-tech-1";
    private static final String ORDER = "order-workstation-1";
    private static final String PATIENT = "patient-workstation-1";
    private static final String ACC = "ACC-WS-1";
    private static final String ISSUER = "ISSUER-WS";
    private static final String UID = "1.2.840.113619.2.55.3.workstation";
    private static final String WORKSTATION = "pacs-workstation-1";
    private static final String AT = "2026-11-22T10:00:00Z";
    private static final Set<String> STAFF = Set.of("radiology_staff");

    private ImagingService service;
    private StaffRepository staff;
    private MockMvc mvc;
    private ObjectMapper mapper;

    /** Build the real controller, a fresh synthetic service and the guarded HTTP harness. */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse(AT), ZoneOffset.UTC);
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("radiology_staff"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        ImagingController controller = new ImagingController(clock, staff, transactions);
        // The service-accepting constructor is package-private in radiology and this clause test
        // lives in the clauses package; read the controller's own service by its field name so the
        // seeded order and the HTTP route share one state, without changing production visibility.
        service = (ImagingService) ReflectionTestUtils.getField(controller, "service");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
        accessionedOrder();
    }

    /** Clear the request tenant so a failure cannot leak identity to the next test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Intake, schedule and accession a synthetic CT order so version 3 can be linked. */
    private void accessionedOrder() {
        ImagingOrder order = service.intake(new IntakeCommand(ORG, SUBJECT, STAFF, ORDER, PATIENT,
                "enc-ws-1", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism", "clinic",
                "radiology", "clinician-a", "rad-queue", "referrer-a", Modality.CT));
        order = service.schedule(new ScheduleCommand(ORG, SUBJECT, STAFF, ORDER, order.version(),
                Instant.parse(AT).plus(Duration.ofDays(1)), Modality.CT));
        service.accession(new AccessionCommand(ORG, SUBJECT, STAFF, ORDER, order.version(), ACC, ISSUER));
    }

    /** Serialize a study-link request with the supplied version and workstation reference. */
    private String linkBody(int expectedVersion, String studyUid, String workstationRef) throws Exception {
        return mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                expectedVersion, studyUid, PATIENT, ACC, workstationRef));
    }

    /** Post one study-link request under the given tenant. */
    private ResultActions link(String organization, String body) throws Exception {
        return mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Synthetic-Subject", SUBJECT)
                .header("X-Active-Organization", organization));
    }

    /** Assert no revision or STUDY_ASSOCIATED event was appended by a refused link. */
    private void assertNothingWasAssociated() {
        ImagingOrder current = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, current.state());
        assertEquals(3, current.version());
        assertNull(current.studyInstanceUid());
        assertNull(current.workstationRef());
        assertTrue(service.events(ORG).stream()
                .noneMatch(event -> event.type() == RadEventType.STUDY_ASSOCIATED));
    }

    @Test
    void linksAStudyOnlyWithAPartnerWorkstationReference() throws Exception {
        link(ORG, linkBody(3, UID, WORKSTATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STUDY_ASSOCIATED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.studyInstanceUid").value(UID))
                .andExpect(jsonPath("$.workstationRef").value(WORKSTATION));

        ImagingOrder stored = service.read(ORG, ORDER);
        assertEquals(4, stored.version());
        assertEquals(UID, stored.studyInstanceUid());
        assertEquals(WORKSTATION, stored.workstationRef());
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.STUDY_ASSOCIATED).count());
    }

    @Test
    void refusesToLinkWhenWorkstationReferenceIsMissing() throws Exception {
        link(ORG, linkBody(3, UID, " "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSTATION"));
        assertNothingWasAssociated();
    }

    @Test
    void refusesToLinkWithoutAssociationRole() throws Exception {
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("reception"));
        link(ORG, linkBody(3, UID, WORKSTATION))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertNothingWasAssociated();
    }

    @Test
    void refusesToLinkForAnotherTenant() throws Exception {
        link(PEER_ORG, linkBody(3, UID, WORKSTATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertNothingWasAssociated();
    }

    @Test
    void refusesToLinkWithStaleOrderVersion() throws Exception {
        link(ORG, linkBody(2, UID, WORKSTATION))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        assertNothingWasAssociated();
    }
}
