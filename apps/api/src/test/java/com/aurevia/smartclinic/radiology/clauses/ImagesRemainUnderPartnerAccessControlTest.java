package com.aurevia.smartclinic.radiology.clauses;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.radiology.ImagingController;
import com.aurevia.smartclinic.radiology.ImagingOrder;
import com.aurevia.smartclinic.radiology.ImagingService;
import com.aurevia.smartclinic.radiology.RadCommands.AccessionCommand;
import com.aurevia.smartclinic.radiology.RadCommands.IntakeCommand;
import com.aurevia.smartclinic.radiology.RadCommands.ScheduleCommand;
import com.aurevia.smartclinic.radiology.RadPolicyCatalog;
import com.aurevia.smartclinic.radiology.RadRules;
import com.aurevia.smartclinic.radiology.RadTypes.Modality;
import com.aurevia.smartclinic.radiology.RadTypes.OrderState;
import com.aurevia.smartclinic.radiology.RadTypes.Urgency;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAD-01 clause 5: images remain under partner access control (API-level proof).
 *
 * <p>The imaging gateway never ingests, persists or serves pixels. It records
 * only the partner's Study Instance UID and a diagnostic-workstation reference.
 * Writing that reference is tenant- and role-gated; reading it is tenant-gated.
 * The proof uses a
 * standalone MockMvc over the in-memory imaging kernel, the same fixture style
 * as {@code ImagingControllerHttpTest}, with synthetic identifiers only. The
 * absence of an image payload is asserted structurally (record shape and route
 * mappings) rather than by probing an invented image URL, which would prove
 * nothing about the real contract.</p>
 */
class ImagesRemainUnderPartnerAccessControlTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "radiology-tech";
    private static final String RECEPTION = "reception-clerk";
    private static final String ORDER = "order-partner-images";
    private static final String PATIENT = "patient-partner-images";
    private static final String OTHER_PATIENT = "patient-other-partner";
    private static final String ACC = "ACC-PARTNER-1";
    private static final String ISSUER = "ISSUER-PARTNER";
    private static final String UID = "1.2.840.113619.2.55.3.partner";
    private static final String WORKSTATION = "partner-diagnostic-workstation";
    private static final String AT = "2026-11-22T10:00:00Z";

    /**
     * The exact wire shape of an imaging order. Any new field, in particular a
     * pixel or image field, breaks this set and therefore fails the proof.
     */
    private static final Set<String> ORDER_WIRE_FIELDS = Set.of(
            "organizationId", "orderId", "patientId", "encounterId", "orderCode", "urgency",
            "clinicalQuestion", "sourceActor", "destination", "responsibleClinician", "workQueue",
            "referrer", "state", "version", "scheduledAt", "modality", "accession", "accessionIssuer",
            "studyInstanceUid", "workstationRef", "verifiedPatientId", "verifiedAccession");

    private ImagingService service;
    private ImagingController controller;
    private StaffRepository staff;
    private TransactionTemplate transactions;
    private MockMvc mvc;
    private ObjectMapper mapper;
    private String linkBody;
    private String staleLinkBody;
    private String wrongPatientLinkBody;
    private String wrongAccessionLinkBody;
    private String blankWorkstationLinkBody;

    /** Build the in-memory kernel and a standalone HTTP route with synthetic role fixtures. */
    @BeforeEach
    void setUp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse(AT), ZoneOffset.UTC);
        service = new ImagingService(
                new RadPolicyCatalog(List.of(Modality.values()), List.of()), clock);
        staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of(RadRules.RADIOLOGY_STAFF));
        when(staff.activeRoles(RECEPTION)).thenReturn(List.of(RadRules.RECEPTION));
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        // The fixture constructor is package-private; this clause evidence deliberately
        // lives in the clauses package, so bind the same in-memory service by reflection.
        Constructor<ImagingController> fixtureConstructor = ImagingController.class
                .getDeclaredConstructor(ImagingService.class, StaffRepository.class, TransactionTemplate.class);
        fixtureConstructor.setAccessible(true);
        controller = fixtureConstructor.newInstance(service, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
        linkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                3, UID, PATIENT, ACC, WORKSTATION));
        staleLinkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                2, UID, PATIENT, ACC, WORKSTATION));
        wrongPatientLinkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                3, UID, OTHER_PATIENT, ACC, WORKSTATION));
        wrongAccessionLinkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                3, UID, PATIENT, "ACC-OTHER", WORKSTATION));
        blankWorkstationLinkBody = mapper.writeValueAsString(new ImagingController.StudyLinkRequest(
                3, UID, PATIENT, ACC, " "));
    }

    /** Clear the thread-bound tenant so one test cannot leak context into the next. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Drive an order from intake to accession without touching the HTTP layer. */
    private void accessionedOrder() {
        Set<String> roles = Set.of(RadRules.RADIOLOGY_STAFF);
        ImagingOrder order = service.intake(new IntakeCommand(ORG, SUBJECT, roles, ORDER, PATIENT,
                "encounter-partner", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism",
                "referring-clinic", "radiology", "clinician-partner", "rad-work-queue",
                "referrer-partner", Modality.CT));
        order = service.schedule(new ScheduleCommand(ORG, SUBJECT, roles, ORDER, order.version(),
                Instant.parse(AT).plus(Duration.ofDays(1)), Modality.CT));
        service.accession(new AccessionCommand(ORG, SUBJECT, roles, ORDER, order.version(), ACC, ISSUER));
    }

    /** GET the current order through the HTTP entry point and return the response body. */
    private String read(String organization, String subject) throws Exception {
        return mvc.perform(get("/api/v1/imaging/orders/{orderId}", ORDER)
                        .header("X-Synthetic-Subject", subject)
                        .header("X-Active-Organization", organization))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void studyLinkRecordsOnlyTheUidAndPartnerWorkstationReference() throws Exception {
        accessionedOrder();
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STUDY_ASSOCIATED"))
                .andExpect(jsonPath("$.studyInstanceUid").value(UID))
                .andExpect(jsonPath("$.workstationRef").value(WORKSTATION))
                .andExpect(jsonPath("$.verifiedPatientId").value(PATIENT))
                .andExpect(jsonPath("$.verifiedAccession").value(ACC));

        JsonNode view = mapper.readTree(read(ORG, SUBJECT));
        Set<String> fields = new HashSet<>();
        view.properties().forEach(entry -> fields.add(entry.getKey()));
        // The wire payload is exactly the order shape: no pixel/blob/image field can ride along.
        assertEquals(ORDER_WIRE_FIELDS, fields);
        for (String field : fields) {
            String lower = field.toLowerCase(Locale.ROOT);
            assertFalse(lower.contains("image"), field + " must not carry image payload");
            assertFalse(lower.contains("pixel"), field + " must not carry pixel data");
            assertFalse(lower.contains("blob"), field + " must not carry a blob");
            assertFalse(lower.contains("base64"), field + " must not carry encoded bytes");
        }
        assertTrue(view.get("studyInstanceUid").isTextual());
        assertTrue(view.get("workstationRef").isTextual());
    }

    @Test
    void wrongRoleCannotLinkAStudy() throws Exception {
        accessionedOrder();
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Synthetic-Subject", RECEPTION)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mvc.perform(get("/api/v1/imaging/orders/{orderId}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACCESSIONED"))
                .andExpect(jsonPath("$.studyInstanceUid").value(nullValue()))
                .andExpect(jsonPath("$.workstationRef").value(nullValue()));
        ImagingOrder current = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, current.state());
        assertNull(current.studyInstanceUid());
        assertNull(current.workstationRef());
    }

    @Test
    void peerTenantCannotReadALinkedStudy() throws Exception {
        accessionedOrder();
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(linkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyInstanceUid").value(UID));

        String concealed = mvc.perform(get("/api/v1/imaging/orders/{orderId}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(concealed.contains(UID));
        assertFalse(concealed.contains(WORKSTATION));
        assertTrue(service.studyLinkReviews(OTHER_ORG).isEmpty());
    }

    @Test
    void staleVersionCannotRelinkAStudy() throws Exception {
        accessionedOrder();
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(staleLinkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        mvc.perform(get("/api/v1/imaging/orders/{orderId}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACCESSIONED"))
                .andExpect(jsonPath("$.studyInstanceUid").value(nullValue()));
        ImagingOrder current = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, current.state());
        assertNull(current.studyInstanceUid());
        assertNull(current.workstationRef());
    }

    @Test
    void missingWorkstationReferenceIsRefused() throws Exception {
        accessionedOrder();
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(blankWorkstationLinkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WORKSTATION"));

        mvc.perform(get("/api/v1/imaging/orders/{orderId}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studyInstanceUid").value(nullValue()))
                .andExpect(jsonPath("$.workstationRef").value(nullValue()));
        ImagingOrder current = service.read(ORG, ORDER);
        assertNull(current.studyInstanceUid());
        assertNull(current.workstationRef());
    }

    @Test
    void mismatchedLabelsNeverLinkAndOpenAReview() throws Exception {
        accessionedOrder();
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongPatientLinkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_MISMATCH"));
        // The second mismatch exercises the accession half of the label check and the
        // duplicate-review guard: one order and study produce exactly one review item.
        mvc.perform(post("/api/v1/imaging/orders/{orderId}/studies", ORDER)
                        .contentType(MediaType.APPLICATION_JSON).content(wrongAccessionLinkBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LABEL_MISMATCH"));

        mvc.perform(get("/api/v1/imaging/orders/{orderId}", ORDER)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACCESSIONED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.studyInstanceUid").value(nullValue()));
        ImagingOrder current = service.read(ORG, ORDER);
        assertEquals(OrderState.ACCESSIONED, current.state());
        assertNull(current.studyInstanceUid());
        mvc.perform(get("/api/v1/imaging/orders/study-link-reviews")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].studyInstanceUid").value(UID));
    }

    @Test
    void orderAndControllerExposeNoImagePixels() {
        Set<String> components = Arrays.stream(ImagingOrder.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(ORDER_WIRE_FIELDS, components);
        for (RecordComponent component : ImagingOrder.class.getRecordComponents()) {
            String type = component.getType().getName().toLowerCase(Locale.ROOT);
            assertFalse(type.contains("byte"), component.getName() + " must not carry bytes");
            assertFalse(type.contains("image"), component.getName() + " must not hold an image");
            assertFalse(type.contains("blob"), component.getName() + " must not hold a blob");
            assertFalse(InputStream.class.isAssignableFrom(component.getType()),
                    component.getName() + " must not be a stream of image bytes");
        }

        RequestMapping classMapping = ImagingController.class.getAnnotation(RequestMapping.class);
        assertNotNull(classMapping);
        assertEquals(List.of("/api/v1/imaging/orders"), Arrays.asList(
                classMapping.path().length > 0 ? classMapping.path() : classMapping.value()));
        assertEquals(0, classMapping.produces().length);

        int routes = 0;
        Set<String> handlers = new HashSet<>();
        for (Method method : ImagingController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            RequestMapping request = method.getAnnotation(RequestMapping.class);
            String[] produces = get != null ? get.produces()
                    : post != null ? post.produces()
                    : request != null ? request.produces() : null;
            if (produces == null) {
                continue;
            }
            routes++;
            handlers.add(method.getName());
            String[] paths = get != null ? get.path()
                    : post != null ? post.path()
                    : request.path();
            if (paths.length == 0) {
                // Spring stores the shorthand @GetMapping("/x") in value(), not path().
                paths = get != null ? get.value()
                        : post != null ? post.value()
                        : request.value();
            }
            for (String path : paths) {
                String lower = path.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("image"), method.getName() + " must not serve images");
                assertFalse(lower.contains("pixel"), method.getName() + " must not serve pixels");
                assertFalse(lower.contains("dicom"), method.getName() + " must not serve DICOM objects");
            }
            for (String mediaType : produces) {
                assertFalse(mediaType.toLowerCase(Locale.ROOT).contains("image"),
                        method.getName() + " must not produce image media");
            }
            // Use the generic signature: ResponseEntity<byte[]> erases to ResponseEntity.
            String returnType = method.getGenericReturnType().getTypeName().toLowerCase(Locale.ROOT);
            assertFalse(returnType.contains("byte"), method.getName() + " must not return bytes");
            assertFalse(returnType.contains("resource"), method.getName() + " must not return a resource");
        }
        // intake, read, linkStudy, studyLinkReviews and resolveStudyLinkReview — no image route.
        assertEquals(5, routes);
        assertTrue(handlers.contains("linkStudy"), "study-link handler must be under test");
    }
}
