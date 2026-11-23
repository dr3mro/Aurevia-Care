package com.aurevia.smartclinic.staffmsg.directory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;

/** Pure same-organization and cross-facility policy decisions. */
class DirectoryPolicyTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";

    private final DirectoryPolicy policy = new DirectoryPolicy();

    @Test
    void allowsCrossFacilityReturnsOnlyTheApprovedFlag() {
        assertTrue(policy.allowsCrossFacility(true));
        assertFalse(policy.allowsCrossFacility(false));
    }

    @Test
    void sameOrganizationIsAllowed() {
        assertDoesNotThrow(() -> policy.requireSameOrganization(ORG, ORG));
    }

    @Test
    void crossOrganizationIsRefusedWithAClearCode() {
        ApiException denial = assertThrows(ApiException.class,
                () -> policy.requireSameOrganization(ORG, OTHER_ORG));
        assertEquals(403, denial.status());
        assertEquals("CROSS_ORGANIZATION_MESSAGING_DISABLED", denial.code());
    }

    @Test
    void nullActorOrganizationFailsClosed() {
        ApiException denial = assertThrows(ApiException.class,
                () -> policy.requireSameOrganization(null, ORG));
        assertEquals(403, denial.status());
        assertEquals("CROSS_ORGANIZATION_MESSAGING_DISABLED", denial.code());
    }

    @Test
    void nullTargetOrganizationFailsClosed() {
        ApiException denial = assertThrows(ApiException.class,
                () -> policy.requireSameOrganization(ORG, null));
        assertEquals(403, denial.status());
        assertEquals("CROSS_ORGANIZATION_MESSAGING_DISABLED", denial.code());
    }
}
