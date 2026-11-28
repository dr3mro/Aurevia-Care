package com.aurevia.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.messaging.acknowledgment.InstructionFactController;
import com.aurevia.messaging.acknowledgment.InstructionFactService;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.governance.InMemoryMessagingGovernanceStore;
import com.aurevia.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.messaging.governance.MessagingGovernanceActions;
import com.aurevia.messaging.governance.MessagingGovernanceController;
import com.aurevia.messaging.governance.MessagingGovernanceService;
import com.aurevia.messaging.service.InMemoryServiceProfileStore;
import com.aurevia.messaging.service.ServiceProfileActions;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileController;
import com.aurevia.messaging.service.ServiceProfileService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * Cross-cutting hostile-input and tenant-isolation evidence for the messaging domain (MSG-01, ARC-02).
 *
 * <p>Each test drives a real controller, its real domain service and a synthetic in-memory store through the
 * production MVC pipeline: the uniform error advice and the tenant-context interceptor under the
 * {@code synthetic-auth} profile. A failure must answer with a stable machine code, echo no protected
 * identifier and leave no writable fact behind. These cases deliberately complement, rather than repeat,
 * {@link com.aurevia.messaging.acknowledgment.InstructionFactControllerEdgeCasesTest}: they cover the
 * governance and service-profile HTTP surfaces and the retry key's HTTP boundary. No patient data, real
 * identifier or credential appears in any fixture or assertion.</p>
 */
class MessagingNegativePathsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_USER = "patient-user";
    private static final String PEER_PATIENT = "peer-patient";
    private static final String PEER_ADMIN = "peer-admin";
    private static final String STAFF_USER = "unit-staff";
    private static final String ADMIN = "tenant-admin";
    private static final String APPROVER = "second-admin";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String DRAFT_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String INSTRUCTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String RESOURCE = "99999999-9999-9999-9999-999999999999";
    private static final String PROFILE = "77777777-7777-7777-7777-777777777777";
    private static final String DRAFT = "66666666-6666-6666-6666-666666666666";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String URGENT_KEY = "msg06.approved_urgent_channels";
    private static final String DOWNTIME_KEY = "msg06.approved_downtime_channels";

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryMessagingGovernanceStore governanceStore;
    private InMemoryServiceProfileStore profileStore;
    private InMemoryInstructionFactStore factStore;
    private MockMvc governanceMvc;
    private MockMvc profileMvc;
    private MockMvc factMvc;

    /** Seed one owning tenant, one peer tenant and the three real MVC pipelines. */
    @BeforeEach
    void setUp() throws Exception {
        governanceStore = new InMemoryMessagingGovernanceStore();
        governanceStore.addMember(ORG, ADMIN, "tenant_admin", true);
        governanceStore.addMember(ORG, PATIENT_USER, "patient", true);
        governanceStore.addMember(OTHER_ORG, PEER_ADMIN, "tenant_admin", true);
        governanceStore.addMember(OTHER_ORG, PEER_PATIENT, "patient", true);
        governanceStore.addResource(ORG, RESOURCE, "conversation", NOW.minusSeconds(3600));
        governanceMvc = governanceMvc(governanceStore);

        profileStore = new InMemoryServiceProfileStore();
        seedProfileBaseline(profileStore);
        profileStore.addMember(OTHER_ORG, PEER_PATIENT, "patient", true);
        profileMvc = profileMvc(profileStore);

        factStore = new InMemoryInstructionFactStore();
        factStore.addMember(ORG, PATIENT_USER, "patient", true);
        factStore.addMember(ORG, STAFF_USER, "lab_collector", true);
        factStore.linkPatient(ORG, PATIENT_USER, PATIENT);
        factStore.addConnection(ORG, PATIENT, UNIT, true);
        factStore.assignStaffUnit(ORG, STAFF_USER, UNIT);
        factMvc = factMvc(factStore);
        publishFactRevision(1);
        publishFactRevision(2);
    }

    /** Clear the thread-bound tenant context so no identity leaks to the next request or test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Malformed, absent and over-long fields are rejected before any write or audit fact. */
    @Test
    void missingBlankMalformedAndOverLengthInputIsRejected() throws Exception {
        String truncated = "{\"scope\":\"conversation\",\"provenance\":{\"sourceReference\":\"" + RESOURCE + "\",";
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/actions")
                        .contentType(MediaType.APPLICATION_JSON).content(truncated), ADMIN, ORG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.resourceId").doesNotExist());

        String shortTtl = mapper.writeValueAsString(new CreateExportRequest("conversation", 30L,
                new ExportProvenance(RESOURCE, "rev-1", "record_access")));
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(shortTtl), ADMIN, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TTL"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.sourceReference").doesNotExist());

        String blankReason = mapper.writeValueAsString(new PlaceHoldRequest(RESOURCE, "   "));
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/legal-holds")
                        .contentType(MediaType.APPLICATION_JSON).content(blankReason), ADMIN, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.resourceId").doesNotExist());

        String overLongReason = mapper.writeValueAsString(new PlaceHoldRequest(RESOURCE, "r".repeat(257)));
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/legal-holds")
                        .contentType(MediaType.APPLICATION_JSON).content(overLongReason), ADMIN, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.resourceId").doesNotExist());

        profileMvc.perform(headers(get("/api/v1/care-units/not-a-uuid/service-profile"), PATIENT_USER, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UNITID"))
                .andExpect(jsonPath("$.unitId").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());

        assertTrue(governanceStore.auditEvents(ORG).isEmpty());
        assertTrue(profileStore.auditEvents(ORG).isEmpty());
    }

    /** A patient's valid credentials cannot use governance or profile-publish commands. */
    @Test
    void wrongRoleIsForbidden() throws Exception {
        String export = mapper.writeValueAsString(new CreateExportRequest("conversation", 600L,
                new ExportProvenance(RESOURCE, "rev-1", "record_access")));
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/exports")
                        .contentType(MediaType.APPLICATION_JSON).content(export), PATIENT_USER, ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.provenance").doesNotExist());
        assertEquals(1, governanceStore.auditEvents(ORG).stream()
                .filter(event -> "EXPORT".equals(event.action().name())
                        && "DENIED".equals(event.outcome().name()))
                .count());

        String publish = mapper.writeValueAsString(publishRequest(UNIT, 1));
        profileMvc.perform(headers(post("/api/v1/care-units/{unitId}/service-profiles", UNIT)
                        .contentType(MediaType.APPLICATION_JSON).content(publish), PATIENT_USER, ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.unitId").doesNotExist());
        assertEquals(1, profileStore.versions(ORG, UNIT).size());
        assertTrue(profileStore.auditEvents(ORG).stream().anyMatch(event ->
                "PROFILE_PUBLISH".equals(event.action()) && "DENIED".equals(event.outcome())));
    }

    /** An identifier that exists only in the peer tenant is indistinguishable from an absent one. */
    @Test
    void otherTenantsIdentifierLooksNotFound() throws Exception {
        int ownerGovernanceAudits = governanceStore.auditEvents(ORG).size();
        int ownerProfileAudits = profileStore.auditEvents(ORG).size();

        String governanceBody = governanceMvc.perform(headers(
                        get("/api/v1/messaging-governance/retention/{resourceId}", RESOURCE), PEER_ADMIN, OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.resourceId").doesNotExist())
                .andExpect(jsonPath("$.organizationId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String profileBody = profileMvc.perform(headers(
                        get("/api/v1/care-units/{unitId}/service-profile", UNIT), PEER_PATIENT, OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.unitId").doesNotExist())
                .andExpect(jsonPath("$.patientId").doesNotExist())
                .andExpect(jsonPath("$.organizationId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertEquals(ownerGovernanceAudits, governanceStore.auditEvents(ORG).size());
        assertEquals(ownerProfileAudits, profileStore.auditEvents(ORG).size());
        assertEquals(1, governanceStore.auditEvents(OTHER_ORG).size());
        assertEquals(1, profileStore.auditEvents(OTHER_ORG).size());
        assertTrue(governanceStore.auditEvents(OTHER_ORG).stream().allMatch(event ->
                "NOT_FOUND".equals(event.outcome().name()) && OTHER_ORG.equals(event.organizationId())));
        assertTrue(profileStore.auditEvents(OTHER_ORG).stream().allMatch(event ->
                "NOT_FOUND".equals(event.outcome())));
        assertFalse(governanceBody.contains(ORG));
        assertFalse(governanceBody.contains(RESOURCE));
        assertFalse(profileBody.contains(ORG));
        assertFalse(profileBody.contains(UNIT));
        assertFalse(profileBody.contains(PATIENT));
    }

    /** Both publish and approval version comparisons fail closed with the fixed 412 contract. */
    @Test
    void staleVersionReturnsConflict() throws Exception {
        InMemoryServiceProfileStore publishStore = new InMemoryServiceProfileStore();
        MockMvc publishMvc = profileMvc(publishStore);
        seedProfileBaseline(publishStore);
        String stalePublish = mapper.writeValueAsString(publishRequest(UNIT, 0));
        publishMvc.perform(headers(post("/api/v1/care-units/{unitId}/service-profiles", UNIT)
                        .contentType(MediaType.APPLICATION_JSON).content(stalePublish), ADMIN, ORG))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.unitId").doesNotExist());
        assertEquals(1, publishStore.latestVersion(ORG, UNIT));
        assertEquals(1, publishStore.versions(ORG, UNIT).size());
        assertTrue(publishStore.auditEvents(ORG).stream().anyMatch(event ->
                "PROFILE_PUBLISH".equals(event.action()) && "DENIED".equals(event.outcome())));

        InMemoryServiceProfileStore approveStore = new InMemoryServiceProfileStore();
        MockMvc approveMvc = profileMvc(approveStore);
        approveStore.addMember(ORG, APPROVER, "tenant_admin", true);
        approveStore.addUnit(ORG, DRAFT_UNIT);
        approveStore.addProfile(draftProfile());
        approveMvc.perform(headers(post(
                        "/api/v1/care-units/{unitId}/service-profiles/{profileId}/approve?note=synthetic-site-policy",
                        DRAFT_UNIT, DRAFT).header("If-Match", "99"), APPROVER, ORG))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.unitId").doesNotExist());
        assertEquals(3, approveStore.latestVersion(ORG, DRAFT_UNIT));
        assertEquals(ProfileStatus.DRAFT, approveStore.findProfile(ORG, DRAFT).orElseThrow().status());
        assertTrue(approveStore.auditEvents(ORG).stream().anyMatch(event ->
                "PROFILE_APPROVE".equals(event.action()) && "DENIED".equals(event.outcome())));
    }

    /** Replaying the same client key returns the committed fact and appends no second dispatch. */
    @Test
    void replayedKeyReturnsOriginalFact() throws Exception {
        // The correlation id is minted per request, so compare the committed fact's
        // stable fields instead of the whole body. A replay must not append a fact.
        retry(1, "retry-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.instructionId").value(INSTRUCTION))
                .andExpect(jsonPath("$.genericKey").value("NEW_INSTRUCTION"))
                .andExpect(jsonPath("$.occurredAt").value(NOW.toString()));
        retry(1, "retry-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.instructionId").value(INSTRUCTION))
                .andExpect(jsonPath("$.genericKey").value("NEW_INSTRUCTION"))
                .andExpect(jsonPath("$.occurredAt").value(NOW.toString()));
        assertEquals(1, factStore.clientDigestCount(ORG, STAFF_USER, "DISPATCH"));
        assertEquals(2, factStore.dispatches(ORG, INSTRUCTION, 1).size());
    }

    /** Reusing a client key for another revision conflicts instead of recording a second dispatch. */
    @Test
    void sameKeyChangedPayloadConflicts() throws Exception {
        retry(1, "conflict-key").andExpect(status().isOk());
        retry(2, "conflict-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.instructionId").doesNotExist())
                .andExpect(jsonPath("$.revision").doesNotExist());
        assertEquals(1, factStore.clientDigestCount(ORG, STAFF_USER, "DISPATCH"));
        assertEquals(1, factStore.dispatches(ORG, INSTRUCTION, 2).size());
    }

    /** Absent, malformed and invalid tenant bindings fail closed before the controller is reached. */
    @Test
    void missingTenantHeadersFailClosed() throws Exception {
        governanceMvc.perform(post("/api/v1/messaging-governance/actions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/actions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), ADMIN, "not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
        governanceMvc.perform(headers(post("/api/v1/messaging-governance/actions")
                        .header("X-Correlation-Id", "bad\ncorrelation")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"), ADMIN, ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CORRELATION_ID"));
        assertTrue(governanceStore.auditEvents(ORG).isEmpty());
    }

    /** Build the governance HTTP pipeline over a tenant-scoped store. */
    private MockMvc governanceMvc(InMemoryMessagingGovernanceStore store) {
        MessagingGovernanceService service = new MessagingGovernanceService(store,
                new MessagingGovernanceActions(), CLOCK);
        return MockMvcBuilders.standaloneSetup(new MessagingGovernanceController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment()))
                .build();
    }

    /** Build the service-profile HTTP pipeline over a tenant-scoped store. */
    private MockMvc profileMvc(InMemoryServiceProfileStore store) {
        ServiceProfileService service = new ServiceProfileService(store, new ServiceProfileActions(), CLOCK);
        return MockMvcBuilders.standaloneSetup(new ServiceProfileController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment()))
                .build();
    }

    /** Build the instruction-fact HTTP pipeline over a tenant-scoped store. */
    private MockMvc factMvc(InMemoryInstructionFactStore store) {
        InstructionFactService service = new InstructionFactService(store, new InstructionFactActions(), CLOCK);
        return MockMvcBuilders.standaloneSetup(new InstructionFactController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment()))
                .build();
    }

    /** Enable the synthetic subject header without making it a production authentication route. */
    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return environment;
    }

    /** Bind the synthetic subject and active organization on one request. */
    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                         String subject, String organization) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization);
    }

    /** Seed an approved, effective profile and the site-approved time-sensitive channels. */
    private static void seedProfileBaseline(InMemoryServiceProfileStore store) {
        store.addMember(ORG, ADMIN, "tenant_admin", true);
        store.addMember(ORG, PATIENT_USER, "patient", true);
        store.addUnit(ORG, UNIT);
        store.seedApprovedChannels(ORG, URGENT_KEY, "urgent");
        store.seedApprovedChannels(ORG, DOWNTIME_KEY, "downtime");
        store.addProfile(approvedProfile());
    }

    /** Build a synthetic publish command that uses only tenant-approved channel codes. */
    private static PublishProfileRequest publishRequest(String unit, int expectedVersion) {
        return new PublishProfileRequest(unit, "UTC", List.of("FRIDAY|09:00|17:00"), 30L,
                "nurse", List.of("backup"), "escalation", "urgent", "downtime",
                List.of("collection"), List.of(new LocalizedText("en", "Call the unit")),
                "2026-11-19T12:00:00Z", expectedVersion);
    }

    /** Build the approved, effective baseline profile of the owning unit. */
    private static ServiceProfile approvedProfile() {
        return new ServiceProfile(PROFILE, ORG, UNIT, 1, ProfileStatus.APPROVED, hours(), 30,
                List.of("collection"), List.of(new LocalizedText("en", "Call the unit")), "nurse",
                List.of("backup"), "escalation", "urgent", "downtime", ADMIN, ADMIN,
                NOW.minusSeconds(3600), "site-policy", NOW.minusSeconds(3600), NOW.minusSeconds(7200));
    }

    /** Build a synthetic draft whose creator is deliberately different from the named approver. */
    private static ServiceProfile draftProfile() {
        return new ServiceProfile(DRAFT, ORG, DRAFT_UNIT, 3, ProfileStatus.DRAFT, hours(), 30,
                List.of("collection"), List.of(new LocalizedText("en", "Call the unit")), "nurse",
                List.of("backup"), "escalation", "urgent", "downtime", ADMIN, null, null, null, NOW, NOW);
    }

    /** Build a bounded service window in the unit's IANA time zone. */
    private static ServiceHours hours() {
        return new ServiceHours(ZoneId.of("UTC"),
                List.of(new ServiceWindow(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
    }

    /** Publish one synthetic instruction revision that carries a generic notification. */
    private void publishFactRevision(int revision) throws Exception {
        PublishCommand command = new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision,
                "artifact-" + revision, false, null, true, "publish-key-" + revision);
        factMvc.perform(headers(post("/api/v1/instructions/publish")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command)),
                STAFF_USER, ORG))
                .andExpect(status().isOk());
    }

    /** Retry a generic notification dispatch through the real keyed HTTP route. */
    private org.springframework.test.web.servlet.ResultActions retry(int revision, String clientId)
            throws Exception {
        return factMvc.perform(headers(post("/api/v1/instructions/dispatch/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RetryCommand(INSTRUCTION, revision, clientId))),
                STAFF_USER, ORG));
    }
}
