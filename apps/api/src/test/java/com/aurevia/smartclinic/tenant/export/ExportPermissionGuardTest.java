package com.aurevia.smartclinic.tenant.export;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.audit.AuditRepository;
import com.aurevia.smartclinic.tenant.TenantContext;

/** OPS-07: only an active tenant admin holds the export permission. */
class ExportPermissionGuardTest {

    private static final TenantContext CONTEXT =
            new TenantContext("org-a", "admin", "EXPORT", "corr-1");

    private AuditRepository repository;
    private ExportPermissionGuard authorization;

    @BeforeEach
    void setUp() {
        repository = mock(AuditRepository.class);
        authorization = new ExportPermissionGuard(repository);
    }

    @Test
    void activeTenantAdminIsAllowed() {
        when(repository.hasActiveMembership("admin")).thenReturn(true);
        when(repository.activeRoles("admin")).thenReturn(List.of("tenant_admin"));
        assertTrue(authorization.mayExport(CONTEXT));
    }

    @Test
    void inactiveMembershipIsDeniedWithoutReadingRoles() {
        when(repository.hasActiveMembership("admin")).thenReturn(false);
        assertFalse(authorization.mayExport(CONTEXT));
        verify(repository, never()).activeRoles("admin");
    }

    @Test
    void activeNonAdminIsDenied() {
        when(repository.hasActiveMembership("admin")).thenReturn(true);
        when(repository.activeRoles("admin")).thenReturn(List.of("reception", "clinician"));
        assertFalse(authorization.mayExport(CONTEXT));
    }
}
