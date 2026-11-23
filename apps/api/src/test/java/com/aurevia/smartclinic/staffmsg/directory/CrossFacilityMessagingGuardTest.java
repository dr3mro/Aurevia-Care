package com.aurevia.smartclinic.staffmsg.directory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.authz.access.AccessRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.tenancy.Facility;

/** Cross-facility refusal is fail-closed: same tenant, approved flag and facility scope. */
class CrossFacilityMessagingGuardTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String CODE = "FAC-A";
    private static final String SUBJECT = "nurse-a";

    private DirectoryPolicy policy;
    private AccessRepository access;
    private StaffDirectoryService directory;
    private ActiveStaffGuard gate;
    private TransactionTemplate transactions;
    private CrossFacilityMessagingGuard guard;
    private TenantContext context;

    @BeforeEach
    void setUp() {
        policy = new DirectoryPolicy();
        access = mock(AccessRepository.class);
        directory = mock(StaffDirectoryService.class);
        gate = mock(ActiveStaffGuard.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        guard = new CrossFacilityMessagingGuard(policy, access, directory, gate, transactions);
        context = new TenantContext(ORG, SUBJECT, "MESSAGING", "corr-1");
        TenantContext.set(context);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void sameTenantCrossFacilityAllowedWhenPolicyEnabled() {
        when(directory.findFacility(CODE)).thenReturn(Optional.of(facility(ORG)));
        when(access.outsideAssignedFacilities(SUBJECT, CODE)).thenReturn(true);
        when(access.effectiveConfig(CrossFacilityMessagingGuard.POLICY_KEY)).thenReturn("true");

        assertDoesNotThrow(() -> guard.requireCrossFacilityAllowed(CODE));

        // A real invitation gives every member one home facility, so the fold is true
        // for a destination in another facility; the approved flag must permit it.
        InOrder order = inOrder(transactions, gate, directory, access);
        order.verify(transactions).execute(any());
        order.verify(gate).require(context);
        order.verify(directory).findFacility(CODE);
        order.verify(access).outsideAssignedFacilities(SUBJECT, CODE);
        order.verify(access).effectiveConfig(CrossFacilityMessagingGuard.POLICY_KEY);
    }

    @Test
    void inScopeFacilityNeedsNoPolicyFlag() {
        when(directory.findFacility(CODE)).thenReturn(Optional.of(facility(ORG)));
        when(access.outsideAssignedFacilities(SUBJECT, CODE)).thenReturn(false);

        assertDoesNotThrow(() -> guard.requireCrossFacilityAllowed(CODE));
        verify(access, never()).effectiveConfig(any());
    }

    @Test
    void crossFacilityRefusedWhenPolicyDisabled() {
        when(directory.findFacility(CODE)).thenReturn(Optional.of(facility(ORG)));
        when(access.outsideAssignedFacilities(SUBJECT, CODE)).thenReturn(true);
        when(access.effectiveConfig(CrossFacilityMessagingGuard.POLICY_KEY)).thenReturn("false");

        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(CODE));
        assertEquals(403, denial.status());
        assertEquals("CROSS_FACILITY_MESSAGING_DISABLED", denial.code());
    }

    @Test
    void missingConfigFailsClosed() {
        when(directory.findFacility(CODE)).thenReturn(Optional.of(facility(ORG)));
        when(access.outsideAssignedFacilities(SUBJECT, CODE)).thenReturn(true);
        when(access.effectiveConfig(CrossFacilityMessagingGuard.POLICY_KEY)).thenReturn(null);

        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(CODE));
        assertEquals(403, denial.status());
        assertEquals("CROSS_FACILITY_MESSAGING_DISABLED", denial.code());
    }

    @Test
    void crossOrganizationRefusedEvenWhenPolicyEnabled() {
        when(directory.findFacility(CODE)).thenReturn(Optional.of(facility(OTHER_ORG)));
        when(access.effectiveConfig(CrossFacilityMessagingGuard.POLICY_KEY)).thenReturn("true");

        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(CODE));
        assertEquals(403, denial.status());
        assertEquals("CROSS_ORGANIZATION_MESSAGING_DISABLED", denial.code());
        verify(access, never()).effectiveConfig(any());
    }

    @Test
    void unknownFacilityFailsClosed() {
        when(directory.findFacility(CODE)).thenReturn(Optional.empty());

        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(CODE));
        assertEquals(403, denial.status());
        assertEquals("CROSS_ORGANIZATION_MESSAGING_DISABLED", denial.code());
    }

    @Test
    void actorWithoutActiveRoleIsRefusedBeforeAnyDirectoryRead() {
        org.mockito.Mockito.doThrow(new ApiException(403, "ACTIVE_STAFF_REQUIRED", "not active staff"))
                .when(gate).require(context);

        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(CODE));
        assertEquals(403, denial.status());
        assertEquals("ACTIVE_STAFF_REQUIRED", denial.code());
        verifyNoInteractions(directory, access);
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();

        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(CODE));
        assertEquals(403, denial.status());
        assertEquals("MISSING_TENANT_CONTEXT", denial.code());
        verifyNoInteractions(transactions, gate, directory, access);
    }

    @Test
    void invalidFacilityCodeIsRejectedBeforeAnyRead() {
        ApiException denial = assertThrows(ApiException.class, () -> guard.requireCrossFacilityAllowed(null));
        assertEquals(422, denial.status());
        assertEquals("INVALID_FACILITY", denial.code());
        verifyNoInteractions(transactions, gate, directory, access);
    }

    private static Facility facility(String organizationId) {
        return new Facility(organizationId, CODE, "North Clinic", "Europe/London");
    }
}
