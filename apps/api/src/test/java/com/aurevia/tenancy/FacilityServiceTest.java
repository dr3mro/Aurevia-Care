package com.aurevia.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.FacilityController;

class FacilityServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private FacilityRepository repository;
    private TransactionTemplate transactions;
    private FacilityService service;

    @BeforeEach
    void setUp() {
        repository = mock(FacilityRepository.class);
        transactions = mock(TransactionTemplate.class);
        service = new FacilityService(repository, new ActionPolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, "admin-a", TenantContext.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorize() {
        when(repository.hasActiveMembership("admin-a")).thenReturn(true);
        when(repository.activeRoles("admin-a")).thenReturn(List.of("tenant_admin"));
    }

    @Test
    void rejectsNullCode() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create(null, "Name")).status());
    }

    @Test
    void rejectsLowercaseCode() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("main", "Name")).status());
        assertEquals("INVALID_CODE", assertThrows(ApiException.class,
                () -> service.create("main", "Name")).code());
    }

    @Test
    void acceptsMaxLengthCodeAndRejectsOverlongAndArabicCodes() {
        authorize();
        when(repository.insert(any())).thenReturn(1);
        assertEquals("A".repeat(32), service.create("A".repeat(32), "Name").code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("A".repeat(33), "Name")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("رمز", "Name")).status());
    }

    @Test
    void rejectsNullName() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("MAIN", null)).status());
    }

    @Test
    void rejectsBlankName() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("MAIN", "  ")).status());
    }

    @Test
    void rejectsLongName() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("MAIN", "x".repeat(201))).status());
    }

    @Test
    void deniesInactiveMembership() {
        when(repository.hasActiveMembership("admin-a")).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.create("MAIN", "Name")).status());
    }

    @Test
    void deniesMissingPermission() {
        when(repository.hasActiveMembership("admin-a")).thenReturn(true);
        when(repository.activeRoles("admin-a")).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.create("MAIN", "Name")).status());
    }

    @Test
    void mapsDuplicateToConflict() {
        authorize();
        when(repository.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.create("MAIN", "Name")).status());
    }

    @Test
    void createsAndAuditsInOneTransaction() {
        authorize();
        when(repository.insert(any())).thenReturn(1);
        Facility created = service.create("WARD", "Ward");
        assertEquals(new Facility(ORG, "WARD", "Ward", "UTC"), created);
        verify(repository).appendAudit(TenantContext.current(), "FACILITY_CREATE",
                "FACILITY", "WARD", "SUCCESS");
    }

    @Test
    void readsAndAudits() {
        authorize();
        when(repository.findByCode("MAIN")).thenReturn(Optional.of(new Facility(ORG, "MAIN", "Main", "UTC")));
        Facility read = service.get("MAIN");
        assertEquals("Main", read.name());
        verify(repository).appendAudit(TenantContext.current(), "FACILITY_READ",
                "FACILITY", "MAIN", "SUCCESS");
    }

    @Test
    void concealsMissingFacility() {
        authorize();
        when(repository.findByCode("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get("MAIN")).status());
    }

    @Test
    void createsAFacilityWithAnExplicitTimeZone() {
        authorize();
        when(repository.insert(any())).thenReturn(1);
        Facility created = service.create("RIYADH", "Riyadh", "Asia/Riyadh");
        assertEquals("Asia/Riyadh", created.timeZone());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.create("BADZONE", "Bad", "Mars/Olympus")).status());
    }

    @Test
    void valueRecordsExposeTheirParts() {
        Facility facility = new Facility(ORG, "MAIN", "Main", "UTC");
        assertEquals(ORG, facility.organizationId());
        assertEquals("MAIN", facility.code());
        assertEquals("Main", facility.name());
        assertEquals(facility, new Facility(ORG, "MAIN", "Main", "UTC"));
        assertEquals(facility.hashCode(), new Facility(ORG, "MAIN", "Main", "UTC").hashCode());
        assertEquals("Facility[organizationId=" + ORG + ", code=MAIN, name=Main, timeZone=UTC]", facility.toString());

        FacilityController.CreateRequest request = new FacilityController.CreateRequest("MAIN", "Main", "UTC");
        assertEquals("MAIN", request.code());
        assertEquals("Main", request.name());
        assertEquals("UTC", request.timeZone());
        assertEquals(request, new FacilityController.CreateRequest("MAIN", "Main", "UTC"));
        assertEquals(request.hashCode(), new FacilityController.CreateRequest("MAIN", "Main", "UTC").hashCode());
    }
}
