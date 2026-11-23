package com.aurevia.smartclinic.staffmsg.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.MessageRule;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.MessagingPolicySettings;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import static com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.PolicyScope;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentContract.MediaType;

/** Domain acceptance for effective dating, history and edit guards (MSG-07). */
class MessagingPolicyServiceTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";
    private static final String FACILITY_A = "fac-a";
    private static final String FACILITY_B = "fac-b";
    private static final Instant NOW = Instant.parse("2027-06-01T00:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(3600);
    private static final Instant FUTURE = NOW.plusSeconds(3600);

    private TestPolicyHistory history;
    private MessagingPolicyService service;

    @BeforeEach
    void setUp() {
        history = new TestPolicyHistory();
        service = new MessagingPolicyService(history);
    }

    private static MessagingPolicySettings settings(long version, Instant effectiveFrom, boolean crossFacility) {
        return new MessagingPolicySettings(MessageRule.ALL_STAFF, EnumSet.allOf(MediaType.class),
                MessagingPolicySettings.DEFAULT_RETENTION_DAYS, crossFacility, effectiveFrom, version);
    }

    private static PolicyRevision revision(String id, String org, PolicyScope scope, String facility,
                                           long version, Instant effectiveFrom, Instant recordedAt) {
        return new PolicyRevision(id, org, scope, facility, settings(version, effectiveFrom, false),
                "admin-" + id, recordedAt);
    }

    @Test
    void futureEffectiveFromIsInactive() {
        history.seed(revision("r-future", ORG_A, PolicyScope.TENANT, null, 1L, FUTURE, NOW));
        MessagingPolicySettings effective = service.policyAt(ORG_A, PolicyScope.TENANT, null, NOW);
        assertFalse(effective.crossFacilityEnabled());
        assertEquals(0L, effective.version());
        assertEquals(EnumSet.allOf(MediaType.class), effective.allowedTypes());
        assertEquals(MessagingPolicySettings.DEFAULT_RETENTION_DAYS, effective.retentionDays());
    }

    @Test
    void effectiveAtBoundaryIsActive() {
        history.seed(revision("r-now", ORG_A, PolicyScope.TENANT, null, 4L, NOW, NOW));
        assertEquals(4L, service.policyAt(ORG_A, PolicyScope.TENANT, null, NOW).version());
    }

    @Test
    void latestEffectiveRevisionWins() {
        history.seed(revision("r-old", ORG_A, PolicyScope.TENANT, null, 1L, PAST, PAST));
        history.seed(revision("r-new", ORG_A, PolicyScope.TENANT, null, 2L, NOW, NOW));
        assertEquals(2L, service.policyAt(ORG_A, PolicyScope.TENANT, null, FUTURE).version());
    }

    @Test
    void versionBreaksEffectiveDateTie() {
        // Lower version is seeded first: without the version tie-break, max would
        // keep that first element and the assertion would fail.
        history.seed(revision("r-low", ORG_A, PolicyScope.TENANT, null, 2L, PAST, PAST));
        history.seed(revision("r-high", ORG_A, PolicyScope.TENANT, null, 9L, PAST, PAST.minusSeconds(1)));
        assertEquals(9L, service.policyAt(ORG_A, PolicyScope.TENANT, null, NOW).version());
    }

    @Test
    void changeAppliesToNewMessagesOnly() {
        history.seed(revision("r-old", ORG_A, PolicyScope.TENANT, null, 1L, PAST, PAST));
        history.seed(revision("r-new", ORG_A, PolicyScope.TENANT, null, 2L, FUTURE, NOW));
        assertEquals(1L, service.policyAt(ORG_A, PolicyScope.TENANT, null, NOW).version());
        assertEquals(2L, service.policyAt(ORG_A, PolicyScope.TENANT, null, FUTURE.plusSeconds(1)).version());
    }

    @Test
    void emptyHistoryFailsClosed() {
        MessagingPolicySettings effective = service.policyAt(ORG_A, PolicyScope.TENANT, null, NOW);
        assertFalse(effective.crossFacilityEnabled());
        assertEquals(0L, effective.version());
        assertEquals(3, effective.allowedTypes().size());
    }

    @Test
    void twoEditsKeepOrderedImmutableRevisions() {
        PolicyRevision first = service.edit(ORG_A, PolicyScope.TENANT, null,
                settings(1L, NOW, false), "admin-a", NOW);
        PolicyRevision second = service.edit(ORG_A, PolicyScope.TENANT, null,
                settings(2L, FUTURE, true), "admin-b", FUTURE);

        List<PolicyRevision> revisions = service.history(ORG_A, PolicyScope.TENANT, null);
        assertEquals(2, revisions.size());
        assertEquals(first.id(), revisions.get(0).id());
        assertEquals(second.id(), revisions.get(1).id());
        assertEquals(settings(1L, NOW, false), revisions.get(0).settings());
        assertNotEquals(first.id(), second.id());
        assertEquals("admin-a", revisions.get(0).changedBy());
    }

    @Test
    void historyBreaksSameInstantTieByVersion() {
        service.edit(ORG_A, PolicyScope.TENANT, null, settings(2L, NOW, true), "admin-b", NOW);
        service.edit(ORG_A, PolicyScope.TENANT, null, settings(1L, NOW, false), "admin-a", NOW);
        List<PolicyRevision> revisions = service.history(ORG_A, PolicyScope.TENANT, null);
        assertEquals(2, revisions.size());
        assertEquals(1L, revisions.get(0).settings().version());
        assertEquals(2L, revisions.get(1).settings().version());
    }

    @Test
    void returnedHistoryCannotBeMutatedByCaller() {
        service.edit(ORG_A, PolicyScope.TENANT, null, settings(1L, NOW, false), "admin-a", NOW);
        assertThrows(UnsupportedOperationException.class,
                () -> service.history(ORG_A, PolicyScope.TENANT, null).clear());
    }

    @Test
    void duplicateVersionIsRejectedAndAppendsNothing() {
        history.seed(revision("r-1", ORG_A, PolicyScope.TENANT, null, 1L, PAST, PAST));
        ApiException error = assertThrows(ApiException.class,
                () -> service.edit(ORG_A, PolicyScope.TENANT, null, settings(1L, FUTURE, true), "admin-a", NOW));
        assertEquals(409, error.status());
        assertEquals("DUPLICATE_POLICY_VERSION", error.code());
        assertEquals(1, history.size());
    }

    @Test
    void sameVersionInAnotherFacilityIsAccepted() {
        history.seed(revision("r-a", ORG_A, PolicyScope.FACILITY, FACILITY_A, 1L, PAST, PAST));
        PolicyRevision other = service.edit(ORG_A, PolicyScope.FACILITY, FACILITY_B,
                settings(1L, NOW, true), "admin-a", NOW);
        assertEquals(FACILITY_B, other.facilityCode());
        assertEquals(1, history.revisions(ORG_A, PolicyScope.FACILITY, FACILITY_B).size());
    }

    @Test
    void sameVersionInAnotherTenantIsAccepted() {
        history.seed(revision("r-a", ORG_A, PolicyScope.TENANT, null, 1L, PAST, PAST));
        PolicyRevision other = service.edit(ORG_B, PolicyScope.TENANT, null,
                settings(1L, NOW, true), "admin-b", NOW);
        assertEquals(ORG_B, other.organizationId());
        assertEquals(1, history.revisions(ORG_B, PolicyScope.TENANT, null).size());
    }

    @Test
    void facilityScopeRequiresAFacilityCode() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.edit(ORG_A, PolicyScope.FACILITY, null, settings(1L, NOW, true), "admin-a", NOW));
        assertEquals(422, error.status());
        assertEquals("INVALID_POLICY_SCOPE", error.code());
        assertEquals(0, history.size());
    }

    @Test
    void tenantScopeRejectsAFacilityCode() {
        ApiException error = assertThrows(ApiException.class, () -> service.edit(ORG_A,
                PolicyScope.TENANT, FACILITY_A, settings(1L, NOW, true), "admin-a", NOW));
        assertEquals(422, error.status());
        assertEquals("INVALID_POLICY_SCOPE", error.code());
        assertEquals(0, history.size());
    }

    @Test
    void backdatedEffectiveFromIsRejectedAndAppendsNothing() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.edit(ORG_A, PolicyScope.TENANT, null, settings(1L, PAST, true), "admin-a", NOW));
        assertEquals(422, error.status());
        assertEquals("BACKDATED_POLICY_EFFECTIVE_FROM", error.code());
        assertEquals(0, history.size());
    }

    @Test
    void effectiveAtNowIsAccepted() {
        PolicyRevision revision = service.edit(ORG_A, PolicyScope.TENANT, null,
                settings(1L, NOW, true), "admin-a", NOW);
        assertEquals(NOW, revision.settings().effectiveFrom());
        assertEquals(1, history.size());
    }

    @Test
    void tenantFacilityAndScopeAreIsolated() {
        history.seed(revision("r-a", ORG_A, PolicyScope.FACILITY, FACILITY_A, 1L, PAST, PAST));

        assertEquals(1L, service.policyAt(ORG_A, PolicyScope.FACILITY, FACILITY_A, NOW).version());
        assertEquals(0L, service.policyAt(ORG_B, PolicyScope.FACILITY, FACILITY_A, NOW).version());
        assertEquals(0L, service.policyAt(ORG_A, PolicyScope.FACILITY, FACILITY_B, NOW).version());
        assertEquals(0L, service.policyAt(ORG_A, PolicyScope.TENANT, null, NOW).version());

        assertTrue(history.lookups().stream().anyMatch(lookup -> ORG_B.equals(lookup.organizationId())));
        assertTrue(history.lookups().stream().anyMatch(lookup -> FACILITY_B.equals(lookup.facilityCode())));
        assertTrue(history.lookups().stream().anyMatch(lookup -> lookup.scope() == PolicyScope.TENANT));
    }
}
