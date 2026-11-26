package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/** OPS-02 membership resolution: every decision uses a fresh, tenant-scoped role fold. */
class JdbcMembershipResolverTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "admin-a";

    private final StaffRepository staff = mock(StaffRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);

    private JdbcMembershipResolver resolver() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        return new JdbcMembershipResolver(staff, transactions);
    }

    private static void bind(String organization, String subject) {
        TenantContext.set(new TenantContext(organization, subject, TenantContext.PURPOSE, "corr-test"));
    }

    private static ServerIdentity identity(String organization, String subject) {
        return new ServerIdentity(organization, subject, "corr-test");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void activeMemberGetsFreshRolesInsideTheGuardedTransaction() {
        bind(ORG, SUBJECT);
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("tenant_admin", "reception"));

        MemberContext context = resolver().resolve(identity(ORG, SUBJECT));

        assertTrue(context.activeMembership());
        assertEquals(List.of("tenant_admin", "reception"), context.activeRoles());
    }

    @Test
    void inactiveMemberIsNotAuthorized() {
        bind(ORG, SUBJECT);
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(false);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("tenant_admin"));

        MemberContext context = resolver().resolve(identity(ORG, SUBJECT));

        assertFalse(context.activeMembership());
    }

    @Test
    void wrongTenantOrSubjectNeverReadsTheFold() {
        JdbcMembershipResolver resolver = resolver();
        bind(ORG, SUBJECT);
        assertNull(resolver.resolve(identity("22222222-2222-2222-2222-222222222222", SUBJECT)));
        assertNull(resolver.resolve(identity(ORG, "other-subject")));
        TenantContext.clear();
        assertNull(resolver.resolve(identity(ORG, SUBJECT)));
    }

    @Test
    void repositoryFailurePropagatesSoTheGateRecordsMembershipUnavailable() {
        bind(ORG, SUBJECT);
        when(staff.hasActiveMembership(SUBJECT)).thenThrow(new IllegalStateException("store unavailable"));

        assertThrows(IllegalStateException.class, () -> resolver().resolve(identity(ORG, SUBJECT)));
    }

    @Test
    void collaboratorNullsAreRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new JdbcMembershipResolver(null, transactions));
        assertThrows(NullPointerException.class, () -> new JdbcMembershipResolver(staff, null));
    }
}
