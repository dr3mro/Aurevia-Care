package com.aurevia.staffmsg.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.aurevia.staffmsg.policy.MessagingPolicyContract.MessageRule;
import static com.aurevia.staffmsg.policy.MessagingPolicyContract.MessagingPolicySettings;
import static com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import static com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyScope;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.tenant.TenantContext;

/** Authorization and read-path acceptance for the messaging policy kernel (MSG-07). */
class MessagingPolicyActionsTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";
    private static final String ACTOR = "admin-a";
    private static final String FACILITY_A = "fac-a";
    private static final String FACILITY_B = "fac-b";
    private static final Instant NOW = Instant.parse("2027-06-01T00:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    private TestPolicyHistory history;
    private TestPolicyActorSource actors;
    private MessagingPolicyActions actions;

    @BeforeEach
    void setUp() {
        history = new TestPolicyHistory();
        actors = new TestPolicyActorSource(List.of("tenant_admin"), Set.of(FACILITY_A));
        actions = new MessagingPolicyActions(new MessagingPolicyService(history), actors);
        TenantContext.set(new TenantContext(ORG_A, ACTOR, "MESSAGING_POLICY", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MessagingPolicySettings settings(long version, Instant effectiveFrom, boolean crossFacility) {
        return new MessagingPolicySettings(MessageRule.ALL_STAFF, EnumSet.allOf(MediaType.class),
                MessagingPolicySettings.DEFAULT_RETENTION_DAYS, crossFacility, effectiveFrom, version);
    }

    private static ApiException assertForbidden(Runnable command) {
        ApiException error = assertThrows(ApiException.class, command::run);
        assertEquals(403, error.status());
        return error;
    }

    @Test
    void tenantAdminMayEditTenantPolicy() {
        PolicyRevision revision = actions.edit(PolicyScope.TENANT, null, settings(1L, NOW, true), NOW);
        assertNotNull(revision.id());
        assertEquals(ORG_A, revision.organizationId());
        assertEquals(ACTOR, revision.changedBy());
        assertEquals(NOW, revision.recordedAt());
        assertTrue(revision.settings().crossFacilityEnabled());
    }

    @Test
    void tenantAdminMayEditFacilityPolicyOutsideOwnScope() {
        PolicyRevision revision = actions.edit(PolicyScope.FACILITY, FACILITY_B, settings(1L, NOW, true), NOW);
        assertEquals(FACILITY_B, revision.facilityCode());
        assertEquals(1, history.size());
    }

    @Test
    void facilityAdministratorMayEditOwnFacilityOnly() {
        TestPolicyActorSource supervisor = new TestPolicyActorSource(
                List.of("unit_supervisor"), Set.of(FACILITY_A));
        MessagingPolicyActions supervisorActions =
                new MessagingPolicyActions(new MessagingPolicyService(history), supervisor);
        PolicyRevision revision = supervisorActions.edit(
                PolicyScope.FACILITY, FACILITY_A, settings(1L, NOW, true), NOW);
        assertEquals(FACILITY_A, revision.facilityCode());
        assertEquals(1, supervisor.facilityChecks());
        assertEquals(FACILITY_A, supervisor.lastFacilityChecked());

        ApiException error = assertThrows(ApiException.class, () -> supervisorActions.edit(
                PolicyScope.FACILITY, FACILITY_B, settings(2L, LATER, true), LATER));
        assertEquals("POLICY_ADMIN_REQUIRED", error.code());
        assertEquals(FACILITY_B, supervisor.lastFacilityChecked());
    }

    @Test
    void facilityAdministratorCannotEditTenantPolicy() {
        TestPolicyActorSource supervisor = new TestPolicyActorSource(
                List.of("unit_supervisor"), Set.of(FACILITY_A));
        MessagingPolicyActions supervisorActions =
                new MessagingPolicyActions(new MessagingPolicyService(history), supervisor);
        assertEquals("POLICY_ADMIN_REQUIRED", assertForbidden(() -> supervisorActions.edit(
                PolicyScope.TENANT, null, settings(1L, NOW, true), NOW)).code());
        assertEquals(0, supervisor.facilityChecks());
    }

    @Test
    void facilityAdministratorWithNullFacilityIsDenied() {
        TestPolicyActorSource supervisor = new TestPolicyActorSource(
                List.of("unit_supervisor"), Set.of(FACILITY_A));
        MessagingPolicyActions supervisorActions =
                new MessagingPolicyActions(new MessagingPolicyService(history), supervisor);
        assertEquals(403, assertForbidden(() -> supervisorActions.edit(
                PolicyScope.FACILITY, null, settings(1L, NOW, true), NOW)).status());
        assertEquals(0, supervisor.facilityChecks());
    }

    @Test
    void nonAdminRolesAreDenied() {
        for (String role : List.of("clinician", "unit_staff", "reception", "privacy_auditor", "")) {
            TestPolicyActorSource source = new TestPolicyActorSource(List.of(role), Set.of(FACILITY_A));
            MessagingPolicyActions gated = new MessagingPolicyActions(new MessagingPolicyService(history), source);
            assertEquals("POLICY_ADMIN_REQUIRED", assertForbidden(() -> gated.edit(
                    PolicyScope.TENANT, null, settings(1L, NOW, true), NOW)).code());
        }
    }

    @Test
    void nullRoleListIsDenied() {
        TestPolicyActorSource source = new TestPolicyActorSource(null, Set.of(FACILITY_A));
        MessagingPolicyActions gated = new MessagingPolicyActions(new MessagingPolicyService(history), source);
        assertEquals("POLICY_ADMIN_REQUIRED", assertForbidden(() -> gated.edit(
                PolicyScope.FACILITY, FACILITY_A, settings(1L, NOW, true), NOW)).code());
    }

    @Test
    void adminRoleIsFoundBeyondTheFirstActiveRole() {
        TestPolicyActorSource multiRole = new TestPolicyActorSource(
                List.of("clinician", "reception", "tenant_admin"), Set.of());
        MessagingPolicyActions gated = new MessagingPolicyActions(new MessagingPolicyService(history), multiRole);
        PolicyRevision revision = gated.edit(PolicyScope.TENANT, null, settings(1L, NOW, true), NOW);
        assertEquals(ACTOR, revision.changedBy());
    }

    @Test
    void missingTenantContextFailsClosedOnReadAndWrite() {
        TenantContext.clear();
        assertEquals("MISSING_TENANT_CONTEXT", assertForbidden(() ->
                actions.policyAt(PolicyScope.TENANT, null, NOW)).code());
        assertEquals("MISSING_TENANT_CONTEXT", assertForbidden(() ->
                actions.history(PolicyScope.TENANT, null)).code());
        assertEquals("MISSING_TENANT_CONTEXT", assertForbidden(() ->
                actions.edit(PolicyScope.TENANT, null, settings(1L, NOW, true), NOW)).code());
    }

    @Test
    void nonAdminReadSucceedsWithoutAnAdminGate() {
        TestPolicyActorSource clinician = new TestPolicyActorSource(List.of("clinician"), Set.of());
        MessagingPolicyActions gated = new MessagingPolicyActions(new MessagingPolicyService(history), clinician);
        assertFalse(gated.policyAt(PolicyScope.TENANT, null, NOW).crossFacilityEnabled());
        actions.edit(PolicyScope.TENANT, null, settings(1L, NOW, true), NOW);
        MessagingPolicySettings read = gated.policyAt(PolicyScope.TENANT, null, LATER);
        assertTrue(read.crossFacilityEnabled());
        assertEquals(1L, read.version());
        assertEquals(1, gated.history(PolicyScope.TENANT, null).size());
    }

    @Test
    void crossFacilitySettingRoundTripsPerEffectiveDate() {
        actions.edit(PolicyScope.TENANT, null, settings(1L, NOW, true), NOW);
        actions.edit(PolicyScope.TENANT, null, settings(2L, LATER, false), LATER);
        assertTrue(actions.policyAt(PolicyScope.TENANT, null, NOW).crossFacilityEnabled());
        assertFalse(actions.policyAt(PolicyScope.TENANT, null, LATER.plusSeconds(1)).crossFacilityEnabled());
    }

    @Test
    void allowedTypesAndRetentionRoundTrip() {
        MessagingPolicySettings requested = new MessagingPolicySettings(MessageRule.CARE_TEAM,
                EnumSet.of(MediaType.PDF, MediaType.JPEG, MediaType.PNG),
                MessagingPolicySettings.MAX_RETENTION_DAYS, true, NOW, 1L);
        actions.edit(PolicyScope.TENANT, null, requested, NOW);
        MessagingPolicySettings read = actions.policyAt(PolicyScope.TENANT, null, LATER);
        assertEquals(EnumSet.allOf(MediaType.class), read.allowedTypes());
        assertEquals(MessageRule.CARE_TEAM, read.messageRule());
        assertEquals(MessagingPolicySettings.MAX_RETENTION_DAYS, read.retentionDays());
    }

    @Test
    void anotherTenantCannotSeeThePolicyEvenWhenItsActorIsAdmin() {
        actions.edit(PolicyScope.TENANT, null, settings(1L, NOW, true), NOW);

        TenantContext.set(new TenantContext(ORG_B, "admin-b", "MESSAGING_POLICY", "corr-2"));
        TestPolicyActorSource otherOrg = new TestPolicyActorSource(List.of("tenant_admin"), Set.of());
        MessagingPolicyActions otherActions =
                new MessagingPolicyActions(new MessagingPolicyService(history), otherOrg);
        assertEquals(0L, otherActions.policyAt(PolicyScope.TENANT, null, LATER).version());
        assertEquals(ORG_B, history.lookups().get(history.lookups().size() - 1).organizationId());
    }

    @Test
    void anotherFacilityCannotSeeThePolicy() {
        actions.edit(PolicyScope.FACILITY, FACILITY_A, settings(1L, NOW, true), NOW);
        assertEquals(0L, actions.policyAt(PolicyScope.FACILITY, FACILITY_B, LATER).version());
        assertEquals(FACILITY_B, history.lookups().get(history.lookups().size() - 1).facilityCode());
    }
}
