package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.tenant.TenantContext;

/**
 * Branch proof for the live, command-specific replay authorization check (ARC-03).
 *
 * <p>The authoritative membership and role folds are mocked at the JDBC boundary;
 * each decision side is driven deterministically. A catalog command and a role
 * allowed by its owning policy are used, plus a role that the policy refuses and
 * routes that are not registered. All identifiers are synthetic and no clinical
 * or credential data is used.</p>
 */
class DurableReplayAuthorizationTest {

    private static final String ORG = "11111111-2222-3333-4444-555555555555";
    private static final String SUBJECT = "idem-actor";
    private static final String COMMAND = "POST /api/v1/orders/{id}/accept";
    private static final String MEMBERSHIP_SQL = "SELECT public.has_active_membership(:subject)";
    private static final String ROLES_SQL = "SELECT role_key FROM public.active_roles(:subject)";

    private NamedParameterJdbcTemplate jdbc;
    private DurableReplayAuthorization authorization;

    /** Build the check with a pass-through tenant transaction. */
    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        authorization = new DurableReplayAuthorization(jdbc, transactions);
        TenantContext.set(new TenantContext(ORG, SUBJECT, TenantContext.PURPOSE, "corr-1"));
    }

    /** Clear the thread-local binding after each case. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aCallerAllowedByTheCommandPolicyIsAuthorized() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("clinician"));

        assertTrue(authorization.stillAuthorized(TenantContext.current(), COMMAND));
    }

    @Test
    void aRevokedMembershipIsDeniedWithoutReadingRoles() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(false);

        assertFalse(authorization.stillAuthorized(TenantContext.current(), COMMAND));
        verify(jdbc, never()).queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class));
    }

    @Test
    void aCallerWithNoActiveRoleIsDenied() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of());

        assertFalse(authorization.stillAuthorized(TenantContext.current(), COMMAND));
    }

    @Test
    void anAbsentMembershipResultIsDenied() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(null);

        assertFalse(authorization.stillAuthorized(TenantContext.current(), COMMAND));
        verify(jdbc, never()).queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class));
    }

    @Test
    void aRoleRefusedByTheCommandPolicyIsDenied() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("reception", "privacy_auditor"));

        assertFalse(authorization.stillAuthorized(TenantContext.current(), COMMAND));
    }

    @Test
    void anUnregisteredCommandIsDenied() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("clinician"));

        assertFalse(authorization.stillAuthorized(TenantContext.current(),
                "POST /api/v1/idempotency/probe"));
    }

    @Test
    void aNonPostCommandIsDenied() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("clinician"));

        assertFalse(authorization.stillAuthorized(TenantContext.current(),
                "GET /api/v1/orders/{id}/accept"));
    }

    @Test
    void aPharmacyStaffMayReplayASubstitutionProposal() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("pharmacy_staff"));

        assertTrue(authorization.stillAuthorized(TenantContext.current(),
                "POST /api/v1/pharmacy/substitutions/{prescriptionId}/proposals"));
    }

    @Test
    void aClinicianMayNotReplayASubstitutionProposal() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("clinician"));

        assertFalse(authorization.stillAuthorized(TenantContext.current(),
                "POST /api/v1/pharmacy/substitutions/{prescriptionId}/proposals"));
    }

    @Test
    void aClinicianMayReplayASubstitutionApproval() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("clinician"));

        assertTrue(authorization.stillAuthorized(TenantContext.current(),
                "POST /api/v1/pharmacy/substitutions/{proposalId}/approve"));
    }

    @Test
    void aClinicianMayReplayASubstitutionDecline() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("clinician"));

        assertTrue(authorization.stillAuthorized(TenantContext.current(),
                "POST /api/v1/pharmacy/substitutions/{proposalId}/decline"));
    }

    @Test
    void aPharmacistMayNotReplayASubstitutionApproval() {
        when(jdbc.queryForObject(eq(MEMBERSHIP_SQL), eq(Map.of("subject", SUBJECT)), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForList(eq(ROLES_SQL), eq(Map.of("subject", SUBJECT)), eq(String.class)))
                .thenReturn(List.of("pharmacy_staff"));

        assertFalse(authorization.stillAuthorized(TenantContext.current(),
                "POST /api/v1/pharmacy/substitutions/{proposalId}/approve"));
    }
}
