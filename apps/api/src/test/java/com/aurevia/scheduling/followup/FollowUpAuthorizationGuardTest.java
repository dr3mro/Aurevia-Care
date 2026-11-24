package com.aurevia.scheduling.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.scheduling.SchedulingRepository;

/** CLN-06 authorization adapter: membership and roles re-derived on every command. */
class FollowUpAuthorizationGuardTest {

    private SchedulingRepository scheduling;
    private FollowUpAuthorizationGuard auth;

    @BeforeEach
    void setUp() {
        scheduling = mock(SchedulingRepository.class);
        auth = new FollowUpAuthorizationGuard(scheduling);
    }

    @Test
    void membershipAndRolesComeFromTheAuthoritativeFold() {
        when(scheduling.hasActiveMembership("reception-1")).thenReturn(true);
        when(scheduling.activeRoles("reception-1")).thenReturn(List.of("reception"));
        assertTrue(auth.hasActiveMembership("reception-1"));
        assertEquals(List.of("reception"), auth.activeRoles("reception-1"));
        verify(scheduling).hasActiveMembership("reception-1");
        verify(scheduling).activeRoles("reception-1");
    }

    @Test
    void deactivatedMemberHasNoActiveMembership() {
        when(scheduling.hasActiveMembership("reception-1")).thenReturn(false);
        assertFalse(auth.hasActiveMembership("reception-1"));
    }
}
