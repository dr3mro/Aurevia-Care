package com.aurevia.it.exchangetrend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Database-less guard for the XCH-07 acceptance fixture (S-G2-07).
 *
 * <p>{@code TrendingTest.tenantsStayIsolatedAndRolesAreEnforced} defines
 * comparability in the second tenant as subject {@code admin-b}. Defining a
 * comparability policy is an administrative act, so that actor must be seeded
 * as a {@code tenant_admin} of the second organization. Without the seed the
 * scenario would receive 403 instead of 201 only when the real PostgreSQL
 * integration suite runs, so the requirement is made checkable in the fast
 * tier here.</p>
 */
class TrendingFixtureTest {

    @Test
    void secondTenantSeedsAnAdministratorForTheCrossTenantScenario() {
        assertTrue(TrendingTest.seededMembers().stream()
                        .anyMatch(member -> TrendingTest.ORG_B.equals(member[0])
                                && "admin-b".equals(member[1])
                                && "tenant_admin".equals(member[2])),
                "the cross-tenant acceptance scenario defines comparability as admin-b "
                        + "in ORG_B; that tenant_admin must be seeded");
    }

    @Test
    void observationScenariosUseDistinctPatients() {
        List<String> mrns = TrendingTest.scenarioMrns();
        assertEquals(Set.copyOf(mrns).size(), mrns.size(),
                "scenario MRNs must be distinct or one scenario's observations leak into another");
        assertFalse(mrns.contains("MRN-SYN-1"),
                "scenarios must not reuse the patient shared by the seeded/read-only scenarios");
    }
}
