package com.aurevia.staffmsg.directory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/** Only an active member with at least one active role may use the messaging directory. */
class ActiveStaffGuardTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "nurse-a";

    private StaffRepository staff;
    private ActiveStaffGuard gate;
    private TenantContext context;

    @BeforeEach
    void setUp() {
        staff = mock(StaffRepository.class);
        gate = new ActiveStaffGuard(staff);
        context = new TenantContext(ORG, SUBJECT, "MESSAGING", "corr-1");
    }

    @Test
    void activeMemberWithRolePasses() {
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("staff"));
        assertDoesNotThrow(() -> gate.require(context));
    }

    @Test
    void subjectWithNoMembershipIsRefused() {
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(false);
        ApiException denial = assertThrows(ApiException.class, () -> gate.require(context));
        assertEquals(403, denial.status());
        assertEquals("ACTIVE_STAFF_REQUIRED", denial.code());
    }

    @Test
    void actorWithoutActiveRoleIsRefused() {
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of());
        ApiException denial = assertThrows(ApiException.class, () -> gate.require(context));
        assertEquals(403, denial.status());
        assertEquals("ACTIVE_STAFF_REQUIRED", denial.code());
    }
}
