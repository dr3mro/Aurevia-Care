package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.aurevia.audit.AuditAppender;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.tenant.TenantContext;

/** ADM-01/CLN-01 administration branches: validation, authorization and resolution. */
class SchedulingAdminServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "admin-a";

    private SchedulingRepository scheduling;
    private SchedulingAdminService service;

    @BeforeEach
    void setUp() {
        scheduling = mock(SchedulingRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new SchedulingAdminService(scheduling, new ActionPolicy(), transactions,
                mock(AuditAppender.class));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, ACTOR, "SCHEDULING", "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("tenant_admin"));
    }

    @Test
    void definesAVisitTypeForATenantAdministrator() {
        authorized();
        VisitTypeView created = service.createVisitType("NEW", 45);
        assertEquals(new VisitTypeView("NEW", 45), created);
        verify(scheduling).insertVisitType("NEW", 45);
    }

    @Test
    void rejectsInvalidVisitTypes() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.createVisitType("new", 45)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.createVisitType("NEW", 1)).status());
    }

    @Test
    void deniesVisitTypeCreationWithoutMembershipOrRole() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.createVisitType("NEW", 45)).status());
        verify(scheduling, never()).insertVisitType(anyString(), anyInt());

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(true);
        when(scheduling.activeRoles(ACTOR)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.createVisitType("NEW", 45)).status());
    }

    @Test
    void definesAResourceInAKnownFacility() {
        authorized();
        when(scheduling.facility("MAIN")).thenReturn(Optional.of(new FacilityInfo("f1", "MAIN", "UTC")));
        ResourceView created = service.createResource("MAIN", "clinician", "DR-NEW", "Dr New");
        assertEquals("DR-NEW", created.code());
        assertEquals("MAIN", created.facilityCode());
        assertEquals("clinician", created.kind());
    }

    @Test
    void rejectsInvalidResourcesAndUnknownFacilities() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.createResource("MAIN", "scanner", "DR-NEW", "Dr New")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.createResource("MAIN", "clinician", "DR-NEW", "  ")).status());

        authorized();
        when(scheduling.facility("MAIN")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.createResource("MAIN", "clinician", "DR-NEW", "Dr New")).status());
    }

    @Test
    void addsAWeeklyWindow() {
        authorized();
        when(scheduling.resourceByCode("DR-NEW")).thenReturn(Optional.of(
                new ResourceView("r-1", "MAIN", "clinician", "DR-NEW", "Dr New")));
        AvailabilityView created = service.addAvailability("DR-NEW", 2, "08:00", "12:00");
        assertEquals(new AvailabilityView("MAIN", "DR-NEW", 2, "08:00", "12:00"), created);
        verify(scheduling).insertAvailability("r-1", 2, "08:00", "12:00");
    }

    @Test
    void rejectsInvalidWindowsAndUnknownResources() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.addAvailability("DR-NEW", 9, "08:00", "12:00")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.addAvailability("DR-NEW", 2, "12:00", "08:00")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.addAvailability("DR-NEW", 2, "noon", "12:00")).status());

        authorized();
        when(scheduling.resourceByCode("DR-UNKNOWN")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.addAvailability("DR-UNKNOWN", 2, "08:00", "12:00")).status());
    }

    @Test
    void deniesWindowCreationWithoutPermission() {
        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.addAvailability("DR-NEW", 2, "08:00", "12:00")).status());
        verify(scheduling, never()).insertAvailability(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    void listsResourcesAndDeniesWithoutPermission() {
        authorized();
        when(scheduling.resources("MAIN")).thenReturn(List.of(
                new ResourceView("r-1", "MAIN", "clinician", "DR-NEW", "Dr New")));
        assertEquals(1, service.resources("MAIN").size());

        when(scheduling.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.resources("MAIN")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.resources("main")).status());
    }
}
