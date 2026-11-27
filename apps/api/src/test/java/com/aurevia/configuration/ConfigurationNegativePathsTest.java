package com.aurevia.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/**
 * Consolidated negative service paths for configuration administration (ADM-01, ARC-02, OPS-02).
 *
 * <p>Every case runs the real {@code ConfigurationService} with a mocked repository and a
 * mocked transaction template that executes the callback inline, matching
 * {@code ConfigurationServiceTest}. The cases pin the machine-readable status, error code,
 * audit outcome and absence of config-data access for input that is missing or malformed,
 * for an active caller whose role cannot administer configuration, and for a key bound to
 * another tenant. No production behaviour, contract or migration is changed by this class.</p>
 *
 * <p>The peer-tenant case is service-layer control-flow evidence only: a mocked empty result
 * cannot enforce forced row-level security and cannot leak a record that was never loaded.
 * The authoritative cross-tenant HTTP and RLS proof is
 * {@code com.aurevia.it.ConfigurationIsolationTest.crossTenantConfigurationIsConcealed}.</p>
 */
class ConfigurationNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ACTOR_A = "admin-a";
    private static final String ACTOR_B = "admin-b";
    private static final String KEY = "policy.key";
    /** Distinctive stand-in for a record owned by the peer tenant; never appears in an error. */
    private static final String SECRET_VALUE = "hidden-secret-org-a";

    private ConfigurationRepository repository;
    private TransactionTemplate transactions;
    private ConfigurationService service;

    /** Wire fresh synthetic boundaries for one case. */
    @BeforeEach
    void setUp() {
        wire(ORG_A, ACTOR_A, "corr-a");
    }

    /** Release the thread-bound tenant context after each case. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Bind a new repository, transaction template, real service and tenant context.
     *
     * <p>Calling this again inside a case gives each role its own invocation history, so a
     * single `verify` cannot accidentally count calls made for a different role.</p>
     *
     * @param organization active organization identifier
     * @param subject      authenticated synthetic subject
     * @param correlation  request correlation identifier
     */
    private void wire(String organization, String subject, String correlation) {
        repository = mock(ConfigurationRepository.class);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        service = new ConfigurationService(repository, new ActionPolicy(), transactions);
        TenantContext.set(new TenantContext(organization, subject, ConfigurationRepository.PURPOSE, correlation));
    }

    /**
     * Grant an active membership and the given role keys to a synthetic subject.
     *
     * @param subject subject to authorize
     * @param roles   role keys returned by the repository, in force order
     */
    private void authorize(String subject, String... roles) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roles));
    }

    /**
     * Verify that no command reached configuration data or wrote a row.
     *
     * <p>Authorization still reads membership and roles and appends a denial audit; those
     * are asserted separately by the caller, so they are deliberately excluded here.</p>
     */
    private void verifyConfigDataUntouched() {
        verify(repository, never()).findEntry(anyString(), anyInt());
        verify(repository, never()).findEntryId(anyString(), anyInt());
        verify(repository, never()).findUserIdBySubject(anyString());
        verify(repository, never()).effective(anyString());
        verify(repository, never()).findAll(anyString());
        verify(repository, never()).nextVersion(anyString());
        verify(repository, never()).insertEntry(any(), any(), anyInt(), any(), any());
        verify(repository, never()).insertApproval(any(), any(), any());
    }

    /**
     * Missing, short, spaced and overlong keys are refused by approve, get and history
     * before any transaction starts, so neither a read nor an audit event is produced.
     */
    @Test
    void rejectsMalformedKeysOnApproveGetHistory() {
        for (String key : new String[] { null, "Ab", "bad key", "a".repeat(65) }) {
            String label = String.valueOf(key);
            ApiException approve = assertThrows(ApiException.class, () -> service.approve(key, 1, null), label);
            assertEquals(422, approve.status(), label);
            assertEquals("INVALID_KEY", approve.code(), label);

            ApiException get = assertThrows(ApiException.class, () -> service.get(key), label);
            assertEquals(422, get.status(), label);
            assertEquals("INVALID_KEY", get.code(), label);

            ApiException history = assertThrows(ApiException.class, () -> service.history(key), label);
            assertEquals(422, history.status(), label);
            assertEquals("INVALID_KEY", history.code(), label);
        }
        verifyNoInteractions(repository);
    }

    /**
     * An active caller without a configuration action is denied on approve, get and history.
     *
     * <p>Both roles pass the membership check, so the denial is the role test in
     * {@code authorize}; the exact message pins that branch rather than the
     * inactive-membership branch. Each command re-derives authorization, so membership and
     * roles are read three times while configuration data is never touched.</p>
     */
    @Test
    void deniesNonAdminRolesForApproveGetHistory() {
        for (String role : List.of("reception", "privacy_auditor")) {
            wire(ORG_A, ACTOR_A, "corr-deny-" + role);
            authorize(ACTOR_A, role);

            ApiException approve = assertThrows(ApiException.class, () -> service.approve(KEY, 1, null), role);
            assertEquals(403, approve.status(), role);
            assertEquals("FORBIDDEN", approve.code(), role);
            assertEquals("missing action permission", approve.getMessage(), role);

            ApiException get = assertThrows(ApiException.class, () -> service.get(KEY), role);
            assertEquals(403, get.status(), role);
            assertEquals("FORBIDDEN", get.code(), role);
            assertEquals("missing action permission", get.getMessage(), role);

            ApiException history = assertThrows(ApiException.class, () -> service.history(KEY), role);
            assertEquals(403, history.status(), role);
            assertEquals("FORBIDDEN", history.code(), role);
            assertEquals("missing action permission", history.getMessage(), role);

            verify(repository, times(3)).hasActiveMembership(ACTOR_A);
            verify(repository, times(3)).activeRoles(ACTOR_A);
            verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "DENIED");
            verify(repository).appendAudit(TenantContext.current(), "CONFIG_READ", KEY, "DENIED");
            verify(repository).appendAudit(TenantContext.current(), "CONFIG_HISTORY", KEY, "DENIED");
            verifyConfigDataUntouched();
        }
    }

    /**
     * A key owned by another tenant is answered as not found on approve, get and history.
     *
     * <p>Under the peer tenant's forced RLS the repository fold is empty, so the service can
     * only append a NOT_FOUND audit in the caller's tenant. The fixed messages contain no
     * record value; this asserts that control-flow fact, not RLS enforcement itself.</p>
     */
    @Test
    void concealsAnotherTenantsKey() {
        wire(ORG_B, ACTOR_B, "corr-peer");
        authorize(ACTOR_B, "tenant_admin");
        when(repository.findEntry(KEY, 1)).thenReturn(Optional.empty());
        when(repository.effective(KEY)).thenReturn(Optional.empty());
        when(repository.findAll(KEY)).thenReturn(List.of());

        ApiException approve = assertThrows(ApiException.class, () -> service.approve(KEY, 1, null));
        assertEquals(404, approve.status());
        assertEquals("NOT_FOUND", approve.code());
        assertFalse(approve.getMessage().contains(SECRET_VALUE));
        assertFalse(approve.getMessage().contains(ORG_A));
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_APPROVE", KEY, "NOT_FOUND");

        ApiException get = assertThrows(ApiException.class, () -> service.get(KEY));
        assertEquals(404, get.status());
        assertEquals("NOT_FOUND", get.code());
        assertFalse(get.getMessage().contains(SECRET_VALUE));
        assertFalse(get.getMessage().contains(ORG_A));
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_READ", KEY, "NOT_FOUND");

        ApiException history = assertThrows(ApiException.class, () -> service.history(KEY));
        assertEquals(404, history.status());
        assertEquals("NOT_FOUND", history.code());
        assertFalse(history.getMessage().contains(SECRET_VALUE));
        assertFalse(history.getMessage().contains(ORG_A));
        verify(repository).appendAudit(TenantContext.current(), "CONFIG_HISTORY", KEY, "NOT_FOUND");

        verify(repository, never()).insertApproval(any(), any(), any());
        verify(repository, never()).insertEntry(any(), any(), anyInt(), any(), any());
    }
}
