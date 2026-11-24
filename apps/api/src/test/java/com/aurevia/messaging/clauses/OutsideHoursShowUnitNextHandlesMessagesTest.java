package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.service.InMemoryServiceProfileStore;
import com.aurevia.messaging.service.ServiceProfileActions;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileService;
import com.aurevia.tenant.TenantContext;

/**
 * Clause-focused proof for MSG-06: outside a care unit's published service
 * hours, an authorized reader is shown the exact instant the unit next handles
 * messages, and that read refuses the wrong role, tenant, connection and
 * missing profile.
 *
 * <p>Every organization, subject, unit and profile below is synthetic. This
 * class adds proof only: the availability calculation, its authorization and
 * its audit trail are already implemented in
 * {@link com.aurevia.messaging.service.ServiceProfileService} and
 * reached over HTTP through
 * {@code GET /api/v1/care-units/{unitId}/availability}.</p>
 */
class OutsideHoursShowUnitNextHandlesMessagesTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_SUBJECT = "patient-user";
    private static final String ADMIN = "tenant-admin";
    private static final String RECEPTION = "reception-user";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final String PROFILE_ID = "11111111-2222-3333-4444-555555555555";

    private InMemoryServiceProfileStore store;
    private ServiceProfileService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryServiceProfileStore();
        service = new ServiceProfileService(store, new ServiceProfileActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        store.addMember(ORG, PATIENT_SUBJECT, "patient", true);
        store.addMember(OTHER_ORG, PATIENT_SUBJECT, "patient", true);
        store.addUnit(ORG, UNIT);
        store.addUnit(OTHER_ORG, UNIT);
        store.linkPatient(ORG, PATIENT_SUBJECT, PATIENT);
        store.addConnection(ORG, PATIENT, UNIT, true);
        store.addProfile(approvedProfile());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void outsideHoursReturnsTheNextHandlingInstantForAConnectedPatient() {
        ServiceAvailability availability = service.availability(UNIT);
        assertFalse(availability.open());
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), availability.nextHandlingAt());
        assertEquals("UTC", availability.timeZone());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "AVAILABILITY_VIEW".equals(audit.action())
                && "SUCCESS".equals(audit.outcome())));
    }

    @Test
    void availabilityRefusesARoleWithoutTheAvailabilityViewAction() {
        store.addMember(ORG, RECEPTION, "reception", true);
        TenantContext.set(new TenantContext(ORG, RECEPTION, "PATIENT_MESSAGING", "corr-role"));
        ApiException denial = assertThrows(ApiException.class, () -> service.availability(UNIT));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "AVAILABILITY_VIEW".equals(audit.action())
                && "DENIED".equals(audit.outcome())));
    }

    @Test
    void availabilityConcealsAUnitForAnotherTenant() {
        // Give the other tenant an active member, a connected chart and the same
        // local unit id, so authorization and unit access pass. The 404 can then
        // only come from the org-scoped profile lookup finding no approved profile
        // owned by OTHER_ORG: a cross-tenant reader cannot borrow ORG's profile.
        store.linkPatient(OTHER_ORG, PATIENT_SUBJECT, PATIENT);
        store.addConnection(OTHER_ORG, PATIENT, UNIT, true);
        TenantContext.set(new TenantContext(OTHER_ORG, PATIENT_SUBJECT, "PATIENT_MESSAGING", "corr-tenant"));
        ApiException denial = assertThrows(ApiException.class, () -> service.availability(UNIT));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertTrue(store.auditEvents(OTHER_ORG).stream().anyMatch(audit -> "AVAILABILITY_VIEW".equals(audit.action())
                && "NOT_FOUND".equals(audit.outcome())));
    }

    @Test
    void availabilityRefusesAPatientWithoutAnActiveUnitConnection() {
        store.addConnection(ORG, PATIENT, UNIT, false);
        ApiException denial = assertThrows(ApiException.class, () -> service.availability(UNIT));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "AVAILABILITY_VIEW".equals(audit.action())
                && "NOT_FOUND".equals(audit.outcome())));
    }

    @Test
    void availabilityIsConcealedWhenNoApprovedEffectiveProfileExists() {
        String unconfiguredUnit = "99999999-9999-9999-9999-999999999999";
        store.addUnit(ORG, unconfiguredUnit);
        store.addConnection(ORG, PATIENT, unconfiguredUnit, true);
        ApiException denial = assertThrows(ApiException.class, () -> service.availability(unconfiguredUnit));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertTrue(store.auditEvents(ORG).stream().anyMatch(audit -> "AVAILABILITY_VIEW".equals(audit.action())
                && "NOT_FOUND".equals(audit.outcome())));
    }

    private static ServiceProfile approvedProfile() {
        Instant effectiveFrom = NOW.minusSeconds(3600);
        return new ServiceProfile(PROFILE_ID, ORG, UNIT, 1, ProfileStatus.APPROVED,
                new ServiceHours(ZoneId.of("UTC"),
                        List.of(new ServiceWindow(DayOfWeek.FRIDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)))),
                30, List.of("collection"), instructions(), "nurse", List.of("backup"), "escalation",
                "urgent", "downtime", ADMIN, ADMIN, effectiveFrom, "site-policy", effectiveFrom,
                effectiveFrom.minusSeconds(60));
    }

    private static List<LocalizedText> instructions() {
        return List.of(new LocalizedText("en", "Call the unit"), new LocalizedText("ar", "اتصل بالوحدة"));
    }
}
