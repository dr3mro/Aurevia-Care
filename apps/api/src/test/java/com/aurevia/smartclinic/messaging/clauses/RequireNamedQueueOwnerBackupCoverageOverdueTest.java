package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.acknowledgment.InMemoryInstructionFactStore;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactActions;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactController;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactService;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.smartclinic.messaging.service.InMemoryServiceProfileStore;
import com.aurevia.smartclinic.messaging.service.ServiceProfileActions;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.smartclinic.messaging.service.ServiceProfileController;
import com.aurevia.smartclinic.messaging.service.ServiceProfileService;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * Clause-level proof for MSG-06 clause 3: a unit is enabled for patient
 * messaging only while its approved, effective service profile names a queue
 * owner and at least one backup, and overdue requested work is routed to the
 * owning unit under role and tenant scoping.
 *
 * <p>Every subject, unit, patient, chart and identifier below is synthetic. The
 * tests drive the real controllers, services and domain records through the
 * Spring MVC pipeline (tenant interceptor plus API error handler) with the
 * test-only in-memory stores, so a missing route or a weakened boundary cannot
 * pass. No production code, contract or migration is added by this suite. The
 * part of the clause that is not built — routing overdue work to the *named*
 * owner/backup or through the profile's escalation channel — is documented in
 * {@code docs/spec/clauses/messaging/require-named-queue-owner-backup-coverage-overdue.md}
 * and asserted as a structural gap rather than hidden behind a skipped test.</p>
 */
class RequireNamedQueueOwnerBackupCoverageOverdueTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String INSTRUCTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String PROFILE_ID = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final int NEXT_VERSION = 1;

    private static final String PUBLISHER = "publisher-clause3";
    private static final String APPROVER = "approver-clause3";
    private static final String PATIENT_USER = "patient-clause3";
    private static final String STAFF = "staff-clause3";
    private static final String UNASSIGNED_STAFF = "staff-unassigned-clause3";
    private static final String RECEPTION = "reception-clause3";

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryServiceProfileStore profileStore;
    private InMemoryInstructionFactStore factStore;
    private ServiceProfileService profileService;
    private InstructionFactService factService;
    private MockMvc mvc;

    /** Wire the real controllers, services and tenant-scoped test stores behind one MVC pipeline. */
    @BeforeEach
    void setUp() {
        profileStore = new InMemoryServiceProfileStore();
        profileService = new ServiceProfileService(profileStore, new ServiceProfileActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        factStore = new InMemoryInstructionFactStore();
        factService = new InstructionFactService(factStore, new InstructionFactActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(
                        new ServiceProfileController(profileService),
                        new InstructionFactController(factService))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();

        profileStore.addMember(ORG_A, PUBLISHER, "tenant_admin", true);
        profileStore.addMember(ORG_A, APPROVER, "tenant_admin", true);
        profileStore.addMember(ORG_A, PATIENT_USER, "patient", true);
        profileStore.addMember(ORG_A, RECEPTION, "reception", true);
        profileStore.addMember(ORG_B, PATIENT_USER, "patient", true);
        profileStore.addUnit(ORG_A, UNIT);
        profileStore.addUnit(ORG_B, UNIT);
        profileStore.linkPatient(ORG_A, PATIENT_USER, PATIENT);
        profileStore.addConnection(ORG_A, PATIENT, UNIT, true);
        profileStore.addProfile(namedProfile(1, "nurse-lead", List.of("nurse-backup")));

        factStore.addMember(ORG_A, STAFF, "lab_collector", true);
        factStore.addMember(ORG_A, UNASSIGNED_STAFF, "lab_collector", true);
        factStore.addMember(ORG_A, PATIENT_USER, "patient", true);
        factStore.addMember(ORG_B, STAFF, "lab_collector", true);
        factStore.assignStaffUnit(ORG_A, STAFF, UNIT);
        factStore.addConnection(ORG_A, PATIENT, UNIT, true);
    }

    /** Clear the thread-bound tenant context after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void enabledUnitRequiresNamedOwnerAndBackup() {
        ServiceProfile base = namedProfile(1, "nurse-lead", List.of("nurse-backup"));
        assertTrue(base.enablesUnitAt(NOW));

        TenantContext.set(new TenantContext(ORG_A, PATIENT_USER, "PATIENT_MESSAGING", "c3-enabled"));
        ServiceProfile served = profileService.viewProfile(UNIT);
        assertEquals(base.id(), served.id());
        assertEquals("nurse-lead", served.queueOwner());
        assertEquals(List.of("nurse-backup"), served.backupCoverage());
    }

    @Test
    void blankOrMissingQueueOwnerDisablesUnit() {
        ServiceProfile base = namedProfile(1, "nurse-lead", List.of("nurse-backup"));
        assertFalse(withStaffing(base, "   ", List.of("nurse-backup")).enablesUnitAt(NOW));
        assertFalse(withStaffing(base, null, List.of("nurse-backup")).enablesUnitAt(NOW));

        profileStore.addProfile(withStaffing(namedProfile(2, "nurse-lead", List.of("nurse-backup")),
                "   ", List.of("nurse-backup")));
        TenantContext.set(new TenantContext(ORG_A, PATIENT_USER, "PATIENT_MESSAGING", "c3-owner"));
        assertEquals(404, assertThrows(ApiException.class, () -> profileService.viewProfile(UNIT)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> profileService.availability(UNIT)).status());
    }

    @Test
    void emptyOrBlankBackupCoverageDisablesUnit() {
        ServiceProfile base = namedProfile(1, "nurse-lead", List.of("nurse-backup"));
        assertFalse(withStaffing(base, "nurse-lead", List.of()).enablesUnitAt(NOW));
        assertFalse(withStaffing(base, "nurse-lead", List.of("  ")).enablesUnitAt(NOW));

        profileStore.addProfile(withStaffing(namedProfile(2, "nurse-lead", List.of("nurse-backup")),
                "nurse-lead", List.of("  ")));
        TenantContext.set(new TenantContext(ORG_A, PATIENT_USER, "PATIENT_MESSAGING", "c3-backup"));
        assertEquals(404, assertThrows(ApiException.class, () -> profileService.viewProfile(UNIT)).status());
    }

    @Test
    void draftOrNotYetEffectiveProfileDisablesUnit() {
        ServiceProfile base = namedProfile(1, "nurse-lead", List.of("nurse-backup"));
        ServiceProfile draft = new ServiceProfile("22222222-3333-4444-5555-666666666666",
                ORG_A, UNIT, 2, ProfileStatus.DRAFT,
                base.hours(), base.responseTargetMinutes(), base.topics(), base.urgentInstructions(),
                base.queueOwner(), base.backupCoverage(), base.escalationChannel(), base.urgentChannel(),
                base.downtimeChannel(), base.createdBy(), null, null, null, base.effectiveFrom(), base.createdAt());
        assertFalse(draft.enablesUnitAt(NOW));
        ServiceProfile future = withEffectiveFrom(
                withId(namedProfile(3, "nurse-lead", List.of("nurse-backup")),
                        "33333333-4444-5555-6666-777777777777"),
                NOW.plusSeconds(3600));
        assertFalse(future.enablesUnitAt(NOW));

        profileStore.addProfile(draft);
        profileStore.addProfile(future);
        TenantContext.set(new TenantContext(ORG_A, PATIENT_USER, "PATIENT_MESSAGING", "c3-effective"));
        // Neither the draft nor the future version can displace the effective named profile.
        assertEquals(PROFILE_ID, profileService.viewProfile(UNIT).id());
    }

    @Test
    void httpPublishRefusesBlankOwnerAndMissingBackup() throws Exception {
        mvc.perform(headers(post("/api/v1/care-units/" + UNIT + "/service-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("   ", List.of("nurse-backup"))), PUBLISHER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_QUEUEOWNER"));
        mvc.perform(headers(post("/api/v1/care-units/" + UNIT + "/service-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody(null, List.of("nurse-backup"))), PUBLISHER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_QUEUEOWNER"));
        mvc.perform(headers(post("/api/v1/care-units/" + UNIT + "/service-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("nurse-lead", List.of())), PUBLISHER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_BACKUPCOVERAGE"));
        mvc.perform(headers(post("/api/v1/care-units/" + UNIT + "/service-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("nurse-lead", List.of("  "))), PUBLISHER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_BACKUPCOVERAGE"));
        mvc.perform(headers(post("/api/v1/care-units/" + UNIT + "/service-profiles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publishBody("nurse-lead", List.of("nurse-backup"))), PUBLISHER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.queueOwner").value("nurse-lead"));
    }

    @Test
    void httpServesUnitOnlyWhenOwnerAndBackupAreNamed() throws Exception {
        mvc.perform(headers(get("/api/v1/care-units/" + UNIT + "/service-profile"), PATIENT_USER, ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueOwner").value("nurse-lead"))
                .andExpect(jsonPath("$.backupCoverage[0]").value("nurse-backup"));

        // A persisted effective version that lost its staffing never enables the unit.
        profileStore.addProfile(withStaffing(namedProfile(2, "nurse-lead", List.of("nurse-backup")),
                "  ", List.of()));
        mvc.perform(headers(get("/api/v1/care-units/" + UNIT + "/service-profile"), PATIENT_USER, ORG_A))
                .andExpect(status().isNotFound());
    }

    @Test
    void overdueFollowUpsRouteToTheOwningUnitOnly() {
        publishOverdue(1);
        TenantContext.set(new TenantContext(ORG_A, STAFF, "PATIENT_MESSAGE", "c3-overdue"));
        List<FollowUp> followUps = factService.overdueFollowUps(UNIT);
        assertEquals(1, followUps.size());
        assertEquals(UNIT, followUps.get(0).unitId());
        assertEquals(INSTRUCTION, followUps.get(0).instructionId());
        assertEquals(1, followUps.get(0).revision());
        assertEquals(followUps.get(0), factService.overdueFollowUps(UNIT).get(0));
    }

    @Test
    void supersededInstructionFollowUpIsNotOpenWork() {
        publishOverdue(1);
        TenantContext.set(new TenantContext(ORG_A, STAFF, "PATIENT_MESSAGE", "c3-supersede"));
        factService.publish(new PublishCommand(INSTRUCTION, PATIENT, UNIT, 2,
                "artifact-2", false, null, false, "client-2"));
        assertTrue(factService.overdueFollowUps(UNIT).isEmpty());
    }

    @Test
    void unassignedUnitCannotReadOverdueFollowUps() throws Exception {
        publishOverdue(1);
        String path = "/api/v1/instructions/overdue-follow-ups";

        // Wrong role: a patient cannot read a unit's overdue work.
        mvc.perform(headers(get(path).param("unitId", UNIT), PATIENT_USER, ORG_A))
                .andExpect(status().isForbidden());
        // Wrong tenant: the same local unit id in another tenant has no assignment here.
        mvc.perform(headers(get(path).param("unitId", UNIT), STAFF, ORG_B))
                .andExpect(status().isForbidden());
        // An owning-tenant staff member without that unit's assignment is refused.
        mvc.perform(headers(get(path).param("unitId", UNIT), UNASSIGNED_STAFF, ORG_A))
                .andExpect(status().isForbidden());
        // The assigned owner-unit actor sees the routed follow-up.
        mvc.perform(headers(get(path).param("unitId", UNIT), STAFF, ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anotherTenantCannotReuseTheOwnedUnit() {
        TenantContext.set(new TenantContext(ORG_B, PATIENT_USER, "PATIENT_MESSAGING", "c3-tenant"));
        assertEquals(404, assertThrows(ApiException.class, () -> profileService.viewProfile(UNIT)).status());
    }

    @Test
    void aRoleWithoutProfilePermissionCannotUseTheOwnerBackupRule() {
        TenantContext.set(new TenantContext(ORG_A, RECEPTION, "ADMIN", "c3-role"));
        assertEquals(403, assertThrows(ApiException.class, () -> profileService.viewProfile(UNIT)).status());
    }

    /**
     * Documents the unbuilt half of the clause with a passing assertion instead of
     * a skipped test: the routed follow-up carries no named owner, backup or
     * escalation channel, so overdue work is not tied to the profile's staffing.
     */
    @Test
    void overdueFollowUpsCannotNameTheProfileOwnerBackupOrEscalationChannel() {
        boolean namesStaffing = Arrays.stream(FollowUp.class.getRecordComponents())
                .anyMatch(component -> {
                    String name = component.getName().toLowerCase(Locale.ROOT);
                    return name.contains("owner") || name.contains("backup") || name.contains("channel");
                });
        assertFalse(namesStaffing,
                "GAP: a FollowUp is routed by unit id only and cannot name the queue owner, "
                        + "backup or escalation channel from the service profile");
    }

    /** Seed one overdue, acknowledgment-required instruction revision. */
    private void publishOverdue(int revision) {
        TenantContext.set(new TenantContext(ORG_A, STAFF, "PATIENT_MESSAGE", "c3-publish"));
        factService.publish(new PublishCommand(INSTRUCTION, PATIENT, UNIT, revision,
                "artifact-" + revision, true, NOW.minusSeconds(60).toString(), true, "client-" + revision));
    }

    /** Build a valid publish body for the named owner and backup list. */
    private String publishBody(String queueOwner, List<String> backupCoverage) throws Exception {
        PublishProfileRequest request = new PublishProfileRequest(UNIT, "UTC",
                List.of("MONDAY|09:00|17:00"), 30L, queueOwner, backupCoverage,
                "escalation-queue", "urgent-phone", "downtime-phone", List.of("collection"),
                instructions(), NOW.toString(), NEXT_VERSION);
        return mapper.writeValueAsString(request);
    }

    /** Build an approved, effective profile with the given staffing. */
    private static ServiceProfile namedProfile(int version, String owner, List<String> backups) {
        return new ServiceProfile(PROFILE_ID, ORG_A, UNIT, version, ProfileStatus.APPROVED,
                hours(), 30, List.of("collection"), instructions(), owner, backups,
                "escalation-queue", "urgent-phone", "downtime-phone",
                PUBLISHER, APPROVER, NOW, "site-policy", NOW, NOW);
    }

    /** Replace only the staffing of an existing profile, as a stale persisted row might. */
    private static ServiceProfile withStaffing(ServiceProfile base, String owner, List<String> backups) {
        return new ServiceProfile(base.id(), base.organizationId(), base.unitId(), base.version(),
                base.status(), base.hours(), base.responseTargetMinutes(), base.topics(),
                base.urgentInstructions(), owner, backups, base.escalationChannel(), base.urgentChannel(),
                base.downtimeChannel(), base.createdBy(), base.approvedBy(), base.approvedAt(),
                base.approvalNote(), base.effectiveFrom(), base.createdAt());
    }

    /** Replace only the identifier of an existing profile. */
    private static ServiceProfile withId(ServiceProfile base, String id) {
        return new ServiceProfile(id, base.organizationId(), base.unitId(), base.version(),
                base.status(), base.hours(), base.responseTargetMinutes(), base.topics(),
                base.urgentInstructions(), base.queueOwner(), base.backupCoverage(), base.escalationChannel(),
                base.urgentChannel(), base.downtimeChannel(), base.createdBy(), base.approvedBy(),
                base.approvedAt(), base.approvalNote(), base.effectiveFrom(), base.createdAt());
    }

    /** Replace only the effective date of an existing profile. */
    private static ServiceProfile withEffectiveFrom(ServiceProfile base, Instant effectiveFrom) {
        return new ServiceProfile(base.id(), base.organizationId(), base.unitId(), base.version(),
                base.status(), base.hours(), base.responseTargetMinutes(), base.topics(),
                base.urgentInstructions(), base.queueOwner(), base.backupCoverage(), base.escalationChannel(),
                base.urgentChannel(), base.downtimeChannel(), base.createdBy(), base.approvedBy(),
                base.approvedAt(), base.approvalNote(), effectiveFrom, base.createdAt());
    }

    /** Synthetic service hours in a fixed UTC zone. */
    private static ServiceHours hours() {
        return new ServiceHours(ZoneId.of("UTC"),
                List.of(new ServiceWindow(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
    }

    /** Synthetic bilingual urgent guidance required by publish validation. */
    private static List<LocalizedText> instructions() {
        return List.of(new LocalizedText("en", "Call the unit"),
                new LocalizedText("ar", "اتصل بالوحدة"));
    }

    /** Apply the synthetic subject and organization headers to an MVC request. */
    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                          String subject, String organization) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization);
    }

    /** Apply the owning tenant's synthetic headers to an MVC request. */
    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                          String subject) {
        return headers(builder, subject, ORG_A);
    }
}
