package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.staff.StaffMember;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staff.StaffRepository.StaffRow;
import com.aurevia.staffmsg.directory.DirectoryPolicy;
import com.aurevia.staffmsg.directory.StaffDirectoryService;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenant.TenantContext;

/** Production role-holder expansion over the authoritative staff projection. */
class StaffRecipientDirectoryTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACILITY_CODE = "FAC-A";
    private static final String OTHER_FACILITY_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String OTHER_FACILITY_CODE = "FAC-B";

    private StaffRepository staff;
    private StaffDirectoryService directory;
    private AccessRepository access;
    private TransactionTemplate transactions;
    private StaffRecipientDirectory recipients;

    @BeforeEach
    void setUp() {
        staff = mock(StaffRepository.class);
        directory = mock(StaffDirectoryService.class);
        access = mock(AccessRepository.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        recipients = new StaffRecipientDirectory(staff, transactions, directory, access, new DirectoryPolicy());
        TenantContext.set(new TenantContext(ORG, "nurse-a", "MAIL", "corr-1"));
        when(directory.findFacilityById(FACILITY_ID)).thenReturn(Optional.of(facility(ORG, FACILITY_CODE)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void twoFacilitiesSharingARoleReturnDifferentHolders() {
        when(directory.findFacilityById(OTHER_FACILITY_ID))
                .thenReturn(Optional.of(facility(ORG, OTHER_FACILITY_CODE)));
        whenActiveStaff(List.of(row("nurse-b"), row("nurse-c")));
        // The fold is keyed by facility CODE and by each candidate holder, never by the
        // draft's facility id. nurse-b belongs to A, nurse-c to B.
        when(access.outsideAssignedFacilities("nurse-b", FACILITY_CODE)).thenReturn(false);
        when(access.outsideAssignedFacilities("nurse-c", FACILITY_CODE)).thenReturn(true);
        when(access.outsideAssignedFacilities("nurse-b", OTHER_FACILITY_CODE)).thenReturn(true);
        when(access.outsideAssignedFacilities("nurse-c", OTHER_FACILITY_CODE)).thenReturn(false);

        assertEquals(List.of("nurse-b"), recipients.holders(ORG, FACILITY_ID, "nurse"));
        assertEquals(List.of("nurse-c"), recipients.holders(ORG, OTHER_FACILITY_ID, "nurse"));
        verify(access, never()).outsideAssignedFacilities(any(), eq(FACILITY_ID));
        verify(access, never()).outsideAssignedFacilities(any(), eq(OTHER_FACILITY_ID));
    }

    @Test
    void inactiveStaffAndOtherOrganizationsExcluded() {
        whenActiveStaff(List.of(row("nurse-b")));
        assertEquals(List.of("nurse-b"), recipients.holders(ORG, FACILITY_ID, "nurse"));
        verify(staff).query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE));

        when(directory.findFacilityById(FACILITY_ID))
                .thenReturn(Optional.of(facility(OTHER_ORG, FACILITY_CODE)));
        ApiException denial = assertThrows(ApiException.class,
                () -> recipients.holders(ORG, FACILITY_ID, "nurse"));
        assertEquals(403, denial.status());
        assertEquals("CROSS_ORGANIZATION_MESSAGING_DISABLED", denial.code());
        verify(access, times(1)).outsideAssignedFacilities("nurse-b", FACILITY_CODE);
    }

    @Test
    void failsClosedWhenFacilityMissingOrContextAbsent() {
        when(directory.findFacilityById(FACILITY_ID)).thenReturn(Optional.empty());
        ApiException missing = assertThrows(ApiException.class,
                () -> recipients.holders(ORG, FACILITY_ID, "nurse"));
        assertEquals(404, missing.status());
        assertEquals("FACILITY_NOT_FOUND", missing.code());
        verifyNoInteractions(staff, access);

        TenantContext.clear();
        ApiException denial = assertThrows(ApiException.class,
                () -> recipients.holders(ORG, FACILITY_ID, "nurse"));
        assertEquals(403, denial.status());
        assertEquals("MISSING_TENANT_CONTEXT", denial.code());
    }

    @Test
    void returnsEmptyWhenNobodyHoldsRole() {
        whenActiveStaff(List.of());

        assertEquals(List.of(), recipients.holders(ORG, FACILITY_ID, "nurse"));
        verifyNoInteractions(access);
    }

    @Test
    void returnsEmptyWhenEveryHolderIsOutsideTheFacilityScope() {
        whenActiveStaff(List.of(row("nurse-b"), row("nurse-c")));
        when(access.outsideAssignedFacilities("nurse-b", FACILITY_CODE)).thenReturn(true);
        when(access.outsideAssignedFacilities("nurse-c", FACILITY_CODE)).thenReturn(true);

        assertEquals(List.of(), recipients.holders(ORG, FACILITY_ID, "nurse"));
    }

    @Test
    void deduplicatesHolders() {
        whenActiveStaff(List.of(row("nurse-b"), row("nurse-b")));

        assertEquals(List.of("nurse-b"), recipients.holders(ORG, FACILITY_ID, "nurse"));
    }

    @Test
    void unassignedLegacyHoldersRemainInScopeByTheAuthoritativeFold() {
        // V93 keeps members with no recorded assignment organization-wide. The mail
        // directory reuses that authoritative fold rather than inventing a second rule.
        whenActiveStaff(List.of(row("nurse-b")));
        when(access.outsideAssignedFacilities("nurse-b", FACILITY_CODE)).thenReturn(false);

        assertEquals(List.of("nurse-b"), recipients.holders(ORG, FACILITY_ID, "nurse"));
    }

    @Test
    void resolvesTheFacilityInsideTheTenantTransaction() {
        whenActiveStaff(List.of(row("nurse-b")));

        recipients.holders(ORG, FACILITY_ID, "nurse");

        InOrder order = inOrder(transactions, directory, staff, access);
        order.verify(transactions).execute(any());
        order.verify(directory).findFacilityById(FACILITY_ID);
        order.verify(staff).query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE));
        order.verify(access).outsideAssignedFacilities("nurse-b", FACILITY_CODE);
    }

    @Test
    void expandsOnlyActiveHoldersOfTheRole() {
        when(staff.query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(
                        new StaffRow(new StaffMember("nurse-a", "A", "active", List.of("reception")), "id-a"),
                        new StaffRow(new StaffMember("nurse-b", "B", "active", List.of("nurse")), "id-b"),
                        new StaffRow(new StaffMember("doctor-a", "D", "active", List.of("nurse")), "id-d")));

        assertEquals(List.of("nurse-b", "doctor-a"), recipients.holders(ORG, FACILITY_ID, "nurse"));
        verify(access, atLeastOnce()).outsideAssignedFacilities("nurse-b", FACILITY_CODE);
        verify(access, atLeastOnce()).outsideAssignedFacilities("doctor-a", FACILITY_CODE);
        verify(access, never()).outsideAssignedFacilities(eq("nurse-a"), any());
    }

    private void whenActiveStaff(List<StaffRow> rows) {
        when(staff.query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE))).thenReturn(rows);
    }

    private static StaffRow row(String subject) {
        return new StaffRow(new StaffMember(subject, subject, "active", List.of("nurse")), "id-" + subject);
    }

    private static Facility facility(String organizationId, String code) {
        return new Facility(organizationId, code, "North Clinic", "Europe/London");
    }
}
