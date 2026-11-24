package com.aurevia.staffmsg.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
import com.aurevia.staff.StaffMember;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staff.StaffRepository.StaffRow;
import com.aurevia.staffmsg.directory.DirectoryContract.FacilityDirectory;
import com.aurevia.staffmsg.directory.DirectoryContract.StaffDirectory;
import com.aurevia.staffmsg.directory.DirectoryContract.StaffEntry;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenancy.FacilityRepository;
import com.aurevia.tenancy.FacilityRepository.FacilityRow;
import com.aurevia.tenant.TenantContext;

/** Tenant-scoped directory reads over the existing staff and facility repositories. */
class StaffDirectoryServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CODE = "FAC-A";

    private StaffRepository staff;
    private FacilityRepository facilities;
    private TransactionTemplate transactions;
    private ActiveStaffGuard gate;
    private StaffDirectoryService service;

    @BeforeEach
    void setUp() {
        staff = mock(StaffRepository.class);
        facilities = mock(FacilityRepository.class);
        transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        gate = mock(ActiveStaffGuard.class);
        service = new StaffDirectoryService(staff, facilities, transactions, gate);
        TenantContext.set(new TenantContext(ORG, "nurse-a", "MESSAGING", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void facilitiesReturnsEveryTenantFacilityInsideTheGuardedTransaction() {
        when(facilities.query(isNull(), isNull(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(new FacilityRow(facility(), "id-a")));

        FacilityDirectory directory = service.facilities();

        assertEquals(List.of("FAC-A"), directory.facilities().stream().map(f -> f.code()).toList());
        verify(transactions).execute(any());
        verify(gate).require(any());
    }

    @Test
    void directoryIncludesActiveStaffWithRoles() {
        when(facilities.findByCode(CODE)).thenReturn(Optional.of(facility()));
        whenActiveStaff(List.of(row("nurse-a", "active", List.of("nurse"))));

        StaffDirectory directory = service.staff(CODE);

        assertEquals(List.of("nurse-a"), directory.staff().stream().map(StaffEntry::subject).toList());
        assertEquals(CODE, directory.staff().get(0).facilityCode());
    }

    @Test
    void directoryHidesStaffWithoutAnActiveRole() {
        when(facilities.findByCode(CODE)).thenReturn(Optional.of(facility()));
        whenActiveStaff(List.of(row("no-role", "active", List.of()), row("nurse-a", "active", List.of("nurse"))));

        StaffDirectory directory = service.staff(CODE);

        assertEquals(List.of("nurse-a"), directory.staff().stream().map(StaffEntry::subject).toList());
        assertFalse(directory.staff().stream().anyMatch(entry -> entry.subject().equals("no-role")));
    }

    @Test
    void directoryHidesDeactivatedStaff() {
        when(facilities.findByCode(CODE)).thenReturn(Optional.of(facility()));
        whenActiveStaff(List.of(row("former", "deactivated", List.of("nurse")),
                row("nurse-a", "active", List.of("nurse"))));

        StaffDirectory directory = service.staff(CODE);

        assertEquals(List.of("nurse-a"), directory.staff().stream().map(StaffEntry::subject).toList());
    }

    @Test
    void unknownFacilityFailsClosed() {
        when(facilities.findByCode(CODE)).thenReturn(Optional.empty());

        ApiException denial = assertThrows(ApiException.class, () -> service.staff(CODE));
        assertEquals(404, denial.status());
        assertEquals("FACILITY_NOT_FOUND", denial.code());
    }

    @Test
    void findFacilityReturnsTheTenantFacilityAndEmptyForAnUnknownCode() {
        when(facilities.findByCode(CODE)).thenReturn(Optional.of(facility()));
        when(facilities.findByCode("UNKNOWN")).thenReturn(Optional.empty());

        assertTrue(service.findFacility(CODE).isPresent());
        assertEquals("FAC-A", service.findFacility(CODE).orElseThrow().code());
        assertFalse(service.findFacility("UNKNOWN").isPresent());
    }

    @Test
    void missingTenantContextFailsClosedOnBothReads() {
        TenantContext.clear();

        ApiException facilitiesDenial = assertThrows(ApiException.class, () -> service.facilities());
        ApiException staffDenial = assertThrows(ApiException.class, () -> service.staff(CODE));

        assertEquals(403, facilitiesDenial.status());
        assertEquals("MISSING_TENANT_CONTEXT", facilitiesDenial.code());
        assertEquals(403, staffDenial.status());
        assertEquals("MISSING_TENANT_CONTEXT", staffDenial.code());
    }

    @Test
    void findFacilityByIdResolvesTheTenantFacilityInsideTheGuardedTransaction() {
        when(facilities.query(isNull(), isNull(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(new FacilityRow(facility(), "id-a")));

        Optional<Facility> found = service.findFacilityById("id-a");

        assertTrue(found.isPresent());
        assertEquals("FAC-A", found.orElseThrow().code());
        verify(transactions).execute(any());
    }

    @Test
    void findFacilityByIdFailsClosedForUnknownAndMissingIds() {
        when(facilities.query(isNull(), isNull(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(new FacilityRow(facility(), "id-a")));

        assertTrue(service.findFacilityById("missing").isEmpty());
        assertTrue(service.findFacilityById(null).isEmpty());
    }

    @Test
    void findFacilityByIdFailsClosedWithoutTenantContext() {
        TenantContext.clear();

        ApiException denial = assertThrows(ApiException.class, () -> service.findFacilityById("id-a"));

        assertEquals(403, denial.status());
        assertEquals("MISSING_TENANT_CONTEXT", denial.code());
        verifyNoInteractions(facilities);
    }

    private void whenActiveStaff(List<StaffRow> rows) {
        when(staff.query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE))).thenReturn(rows);
    }

    private static StaffRow row(String subject, String status, List<String> roles) {
        return new StaffRow(new StaffMember(subject, subject, status, roles), "id-" + subject);
    }

    private static Facility facility() {
        return new Facility(ORG, CODE, "North Clinic", "Europe/London");
    }
}
