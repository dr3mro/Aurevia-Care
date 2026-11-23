package com.aurevia.smartclinic.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ProfileHistory;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.SupervisorSnapshot;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.WorkItemFact;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.WorkState;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Unit acceptance for the MSG-06 protected service.
 *
 * <p>Every tenant, subject, unit and profile below is synthetic. The tests
 * enumerate the safety controls from the clinical-safety review: no effective
 * profile without named approval, concealed cross-tenant reads, patient access
 * only through an active connection to that exact unit, staff unit assignment,
 * two-person approval, stale versions, and audited denials.</p>
 */
class ServiceProfileServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_SUBJECT = "patient-user";
    private static final String ADMIN = "tenant-admin";
    private static final String SUPERVISOR = "unit-supervisor";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String PROFILE_ID = "11111111-2222-3333-4444-555555555555";
    private static final String DRAFT_ID = "11111111-2222-3333-4444-666666666666";
    private static final String FUTURE_ID = "11111111-2222-3333-4444-777777777777";
    private static final String UNKNOWN_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String URGENT_CHANNELS_KEY = "msg06.approved_urgent_channels";
    private static final String DOWNTIME_CHANNELS_KEY = "msg06.approved_downtime_channels";

    private InMemoryServiceProfileStore store;
    private ServiceProfileService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryServiceProfileStore();
        service = new ServiceProfileService(store, new ServiceProfileActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        store.addMember(ORG, PATIENT_SUBJECT, "patient", true);
        store.addMember(ORG, ADMIN, "tenant_admin", true);
        store.addMember(ORG, SUPERVISOR, "unit_supervisor", true);
        store.assignUnit(ORG, SUPERVISOR, UNIT);
        store.addMember(OTHER_ORG, PATIENT_SUBJECT, "patient", true);
        store.addUnit(ORG, UNIT);
        store.addUnit(ORG, OTHER_UNIT);
        store.addUnit(OTHER_ORG, UNIT);
        // Publish now fails closed on unapproved channels; these legacy fixture
        // codes are the tenant's approved site policy for this test class.
        store.seedApprovedChannels(ORG, URGENT_CHANNELS_KEY, "urgent");
        store.seedApprovedChannels(ORG, DOWNTIME_CHANNELS_KEY, "downtime");
        store.linkPatient(ORG, PATIENT_SUBJECT, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        store.addProfile(approvedProfile(PROFILE_ID, 1, ORG, UNIT, ADMIN, NOW.minusSeconds(3600)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aConnectedPatientViewsOnlyTheEffectiveApprovedProfileOfThatUnit() {
        ServiceProfile profile = service.viewProfile(UNIT);
        assertEquals(PROFILE_ID, profile.id());
        assertEquals("UTC", profile.hours().zone().getId());
        assertEquals(List.of("collection"), profile.topics());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "PROFILE_VIEW".equals(audit.action())
                && "SUCCESS".equals(audit.outcome())));
    }

    @Test
    void availabilityIsServedFromThePublishedHoursAndReturnsTheNextHandlingInstant() {
        ServiceAvailability availability = service.availability(UNIT);
        assertFalse(availability.open());
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), availability.nextHandlingAt());
    }

    @Test
    void aDraftOrFutureDatedProfileIsNeverServed() {
        ServiceProfile draft = new ServiceProfile(DRAFT_ID, ORG, UNIT, 2, ProfileStatus.DRAFT,
                hours(), 30, List.of("collection"), instructions(), "nurse", List.of("backup"),
                "escalation", "urgent", "downtime", ADMIN, null, null, null, NOW, NOW);
        store.addProfile(draft);
        ServiceProfile future = new ServiceProfile(FUTURE_ID, ORG, UNIT, 3, ProfileStatus.APPROVED,
                hours(), 30, List.of("collection"), instructions(), "nurse", List.of("backup"),
                "escalation", "urgent", "downtime", ADMIN, ADMIN, NOW, "policy", NOW.plusSeconds(3600), NOW);
        store.addProfile(future);
        assertEquals(PROFILE_ID, service.viewProfile(UNIT).id());
    }

    @Test
    void aPatientWithoutAnActiveConnectionToThatUnitIsConcealed() {
        assertThrows(ApiException.class, () -> service.viewProfile(OTHER_UNIT));
        ApiException denial = assertThrows(ApiException.class, () -> service.viewProfile(OTHER_UNIT));
        assertEquals(404, denial.status());
        store.addConnection(ORG, PATIENT, UNIT, false);
        assertEquals(404, assertThrows(ApiException.class, () -> service.viewProfile(UNIT)).status());
    }

    @Test
    void anInactiveMembershipFailsClosedOnTheNextRequest() {
        store.addMember(ORG, PATIENT_SUBJECT, "patient", false);
        ApiException denial = assertThrows(ApiException.class, () -> service.viewProfile(UNIT));
        assertEquals(403, denial.status());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "DENIED".equals(audit.outcome())));
    }

    @Test
    void aCrossTenantUnitIsConcealedEvenWithTheSameLocalIdentifier() {
        TenantContext.set(new TenantContext(OTHER_ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "corr-2"));
        ApiException denial = assertThrows(ApiException.class, () -> service.viewProfile(UNIT));
        assertEquals(404, denial.status());
    }

    @Test
    void aMissingContextReturnsUnauthenticated() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, () -> service.viewProfile(UNIT)).status());
    }

    @Test
    void aRoleWithoutProfilePermissionIsDenied() {
        TenantContext.set(new TenantContext(ORG, "reception-user", "PATIENT_MESSAGING", "corr-3"));
        store.addMember(ORG, "reception-user", "reception", true);
        assertEquals(403, assertThrows(ApiException.class, () -> service.viewProfile(UNIT)).status());
    }

    @Test
    void onlyATenantAdminCanPublishAProfile() {
        assertEquals(403, assertThrows(ApiException.class, () -> service.publish(publishRequest(1))).status());
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-4"));
        ServiceProfile draft = service.publish(publishRequest(1));
        assertEquals(ProfileStatus.DRAFT, draft.status());
        assertEquals(2, draft.version());
        assertEquals(ADMIN, draft.createdBy());
        assertNotNull(draft.createdAt());
    }

    @Test
    void aStalePublishVersionIsRejected() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-5"));
        assertEquals(412, assertThrows(ApiException.class, () -> service.publish(publishRequest(9))).status());
    }

    @Test
    void anUnapprovedUrgentChannelIsRefusedAndAudited() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-unapproved-channel"));
        PublishProfileRequest request = publishRequestWithChannels(UNIT, "FREE_TEXT_URGENT", "downtime", 1);
        ApiException denial = assertThrows(ApiException.class, () -> service.publish(request));
        assertEquals(422, denial.status());
        assertEquals("UNAPPROVED_CHANNEL", denial.code());
        assertEquals(0, store.versions(ORG, UNIT).stream().filter(p -> p.version() == 2).count());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "PROFILE_PUBLISH".equals(audit.action())
                && "DENIED".equals(audit.outcome()) && UNIT.equals(audit.resourceId())));
    }

    @Test
    void aBlankDowntimeChannelIsRefusedAndAudited() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-blank-channel"));
        PublishProfileRequest request = publishRequestWithChannels(UNIT, "urgent", "  ", 1);
        assertEquals(422, assertThrows(ApiException.class, () -> service.publish(request)).status());
        assertEquals(0, store.versions(ORG, UNIT).stream().filter(p -> p.version() == 2).count());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "PROFILE_PUBLISH".equals(audit.action())
                && "DENIED".equals(audit.outcome()) && UNIT.equals(audit.resourceId())));
    }

    @Test
    void aBrandNewUnitCanPublishItsFirstProfileWithVersionZero() {
        String newUnit = "77777777-7777-7777-7777-777777777777";
        store.addUnit(ORG, newUnit);
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-first-publish"));
        PublishProfileRequest request = new PublishProfileRequest(newUnit, "UTC",
                List.of("FRIDAY|09:00|17:00"), 30L, "nurse", List.of("backup"),
                "escalation", "urgent", "downtime", List.of("collection"), instructions(),
                "2026-11-19T12:00:00Z", 0);
        ServiceProfile draft = service.publish(request);
        assertEquals(1, draft.version());
        assertEquals(ProfileStatus.DRAFT, draft.status());
        assertEquals(newUnit, draft.unitId());
    }

    @Test
    void approvalRequiresADifferentApproverAndAnUnapprovedDraft() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-6"));
        ServiceProfile draft = service.publish(publishRequest(1));
        assertEquals(409, assertThrows(ApiException.class, () -> service.approve(draft.id(), 2, "ok")).status());
        TenantContext.set(new TenantContext(ORG, "second-admin", "ADMIN", "corr-7"));
        store.addMember(ORG, "second-admin", "tenant_admin", true);
        TenantContext.set(new TenantContext(ORG, "reception-user", "ADMIN", "corr-7a"));
        store.addMember(ORG, "reception-user", "reception", true);
        assertEquals(403, assertThrows(ApiException.class, () -> service.approve(draft.id(), 2, "ok")).status());
        TenantContext.set(new TenantContext(ORG, "second-admin", "ADMIN", "corr-7"));
        ServiceProfile approved = service.approve(draft.id(), 2, "site-policy");
        assertEquals(ProfileStatus.APPROVED, approved.status());
        assertEquals("second-admin", approved.approvedBy());
        assertEquals("site-policy", approved.approvalNote());
    }

    @Test
    void approvalOfAnAlreadyApprovedVersionIsConflicted() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-8"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.approve(PROFILE_ID, 1, "again")).status());
    }

    @Test
    void approvalOfAnUnknownProfileIsConcealed() {
        TenantContext.set(new TenantContext(ORG, "second-admin", "ADMIN", "corr-9"));
        store.addMember(ORG, "second-admin", "tenant_admin", true);
        String unknown = UNKNOWN_ID;
        assertEquals(404, assertThrows(ApiException.class, () -> service.approve(unknown, 1, "ok")).status());
    }

    @Test
    void aStaleApprovalVersionIsRejected() {
        TenantContext.set(new TenantContext(ORG, "second-admin", "ADMIN", "corr-10"));
        store.addMember(ORG, "second-admin", "tenant_admin", true);
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.approve(PROFILE_ID, 99, "ok")).status());
    }

    @Test
    void historyIsAuditedAndVisibleOnlyToGovernanceRoles() {
        assertEquals(403, assertThrows(ApiException.class, () -> service.history(UNIT)).status());
        TenantContext.set(new TenantContext(ORG, "auditor", "AUDIT", "corr-11"));
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        ProfileHistory history = service.history(UNIT);
        assertEquals(1, history.versions().size());
        assertThrows(UnsupportedOperationException.class, () -> history.versions().clear());
    }

    @Test
    void aSupervisorSeesDerivedCountersOnlyForAnAssignedUnit() {
        store.addWorkItem(ORG, work("w-1", WorkState.AWAITING_STAFF, null, NOW.minusSeconds(3600), null, true));
        store.addWorkItem(ORG, work("w-2", WorkState.AWAITING_STAFF, "nurse", NOW.minusSeconds(3600), null, false));
        store.addWorkItem(ORG, work("w-3", WorkState.RESOLVED, "nurse", NOW.minusSeconds(3600), null, false));
        store.addWorkItem(ORG, new WorkItemFact(ORG, "w-4", OTHER_UNIT, WorkState.AWAITING_STAFF,
                "nurse", NOW.minusSeconds(3600), null, false));
        TenantContext.set(new TenantContext(ORG, SUPERVISOR, "SUPERVISION", "corr-12"));
        SupervisorSnapshot snapshot = service.supervisorSnapshot(UNIT);
        assertEquals(2, snapshot.backlog());
        assertEquals(1, snapshot.unassigned());
        assertEquals(1, snapshot.failedNotifications());
        assertEquals(2, snapshot.overdueResponses());
        assertEquals(NOW, snapshot.generatedAt());
        assertEquals(404, assertThrows(ApiException.class, () -> service.supervisorSnapshot(OTHER_UNIT)).status());
    }

    @Test
    void aPatientCannotReadTheSupervisorSnapshot() {
        assertEquals(403, assertThrows(ApiException.class, () -> service.supervisorSnapshot(UNIT)).status());
    }

    @Test
    void aUnitWithoutANamedOwnerCannotBeEnabled() {
        // A persisted profile that has lost its named queue owner and backup must not
        // enable the unit for patients, even though it is approved and effective.
        ServiceProfile unstaffed = new ServiceProfile(PROFILE_ID, ORG, UNIT, 1, ProfileStatus.APPROVED,
                hours(), 30, List.of("collection"), instructions(), "  ", List.of("  "),
                "escalation", "urgent", "downtime", ADMIN, ADMIN, NOW, "site-policy", NOW, NOW);
        store.addProfile(unstaffed);
        assertEquals(404, assertThrows(ApiException.class, () -> service.viewProfile(UNIT)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.availability(UNIT)).status());
    }

    @Test
    void aConnectedUnitWithoutAnEffectiveProfileIsConcealedButStillSupervised() {
        String noProfileUnit = "99999999-9999-9999-9999-999999999999";
        store.addUnit(ORG, noProfileUnit);
        store.addConnection(ORG, PATIENT, noProfileUnit, true);
        assertEquals(404, assertThrows(ApiException.class, () -> service.viewProfile(noProfileUnit)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.availability(noProfileUnit)).status());
        store.addWorkItem(ORG, new WorkItemFact(ORG, "w-gap", noProfileUnit, WorkState.AWAITING_STAFF,
                null, NOW.minusSeconds(3600), null, false));
        store.assignUnit(ORG, SUPERVISOR, noProfileUnit);
        TenantContext.set(new TenantContext(ORG, SUPERVISOR, "SUPERVISION", "corr-no-profile"));
        SupervisorSnapshot snapshot = service.supervisorSnapshot(noProfileUnit);
        assertEquals(1, snapshot.backlog());
        assertEquals(1, snapshot.unassigned());
        assertEquals(1, snapshot.overdueResponses());
    }

    @Test
    void anUnknownUnitIsConcealedOnHistoryPublishAndRead() {
        String unknown = "88888888-8888-8888-8888-888888888888";
        TenantContext.set(new TenantContext(ORG, "auditor", "AUDIT", "corr-unknown"));
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        assertEquals(404, assertThrows(ApiException.class, () -> service.history(unknown)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.viewProfile(unknown)).status());
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-unknown-admin"));
        PublishProfileRequest request = new PublishProfileRequest(unknown, "UTC", List.of("FRIDAY|09:00|17:00"),
                30L, "nurse", List.of("backup"), "escalation", "urgent", "downtime",
                List.of("collection"), instructions(), "2026-11-19T12:00:00Z", 1);
        assertEquals(404, assertThrows(ApiException.class, () -> service.publish(request)).status());
    }

    @Test
    void aPublishWithoutServiceWindowsIsRejected() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-no-windows"));
        PublishProfileRequest request = new PublishProfileRequest(UNIT, "UTC", List.of(),
                30L, "nurse", List.of("backup"), "escalation", "urgent", "downtime",
                List.of("collection"), instructions(), "2026-11-19T12:00:00Z", 1);
        assertEquals(422, assertThrows(ApiException.class, () -> service.publish(request)).status());
    }

    @Test
    void aPublishWithoutAnExpectedVersionIsRejected() {
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-null-version"));
        PublishProfileRequest request = new PublishProfileRequest(UNIT, "UTC", List.of("FRIDAY|09:00|17:00"),
                30L, "nurse", List.of("backup"), "escalation", "urgent", "downtime",
                List.of("collection"), instructions(), "2026-11-19T12:00:00Z", null);
        assertEquals(422, assertThrows(ApiException.class, () -> service.publish(request)).status());
    }

    @Test
    void governanceAndSupervisionRolesCanReadAnEffectiveProfile() {
        TenantContext.set(new TenantContext(ORG, "auditor", "AUDIT", "corr-audit-view"));
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        assertEquals(PROFILE_ID, service.viewProfile(UNIT).id());
        TenantContext.set(new TenantContext(ORG, ADMIN, "ADMIN", "corr-admin-view"));
        assertEquals(PROFILE_ID, service.viewProfile(UNIT).id());
        TenantContext.set(new TenantContext(ORG, SUPERVISOR, "SUPERVISION", "corr-supervisor-view"));
        assertEquals(PROFILE_ID, service.viewProfile(UNIT).id());
    }

    @Test
    void anUnassignedSupervisorCannotReadAProfile() {
        TenantContext.set(new TenantContext(ORG, "other-supervisor", "SUPERVISION", "corr-unassigned"));
        store.addMember(ORG, "other-supervisor", "unit_supervisor", true);
        assertEquals(404, assertThrows(ApiException.class, () -> service.viewProfile(UNIT)).status());
    }

    private static ServiceProfile approvedProfile(String id, int version, String org, String unit,
                                                  String creator, Instant effectiveFrom) {
        return new ServiceProfile(id, org, unit, version, ProfileStatus.APPROVED, hours(), 30,
                List.of("collection"), instructions(), "nurse", List.of("backup"), "escalation",
                "urgent", "downtime", creator, creator, effectiveFrom, "site-policy", effectiveFrom,
                effectiveFrom.minusSeconds(60));
    }

    private static ServiceHours hours() {
        return new ServiceHours(ZoneId.of("UTC"),
                List.of(new ServiceWindow(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(17, 0))));
    }

    private static List<LocalizedText> instructions() {
        return List.of(new LocalizedText("en", "Call the unit"), new LocalizedText("ar", "اتصل بالوحدة"));
    }

    private static PublishProfileRequest publishRequest(int expectedVersion) {
        return publishRequestWithChannels(UNIT, "urgent", "downtime", expectedVersion);
    }

    private static PublishProfileRequest publishRequestWithChannels(String unit, String urgent,
                                                                   String downtime, int expectedVersion) {
        return new PublishProfileRequest(unit, "UTC", List.of("FRIDAY|09:00|17:00"), 30L,
                "nurse", List.of("backup"), "escalation", urgent, downtime,
                List.of("collection"), instructions(), "2026-11-19T12:00:00Z", expectedVersion);
    }

    private static WorkItemFact work(String id, WorkState state, String assignee, Instant createdAt,
                                     Instant lastStaffResponseAt, boolean notificationFailed) {
        return new WorkItemFact(ORG, id, UNIT, state, assignee, createdAt, lastStaffResponseAt, notificationFailed);
    }
}
