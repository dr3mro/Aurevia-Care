package com.aurevia.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.tenant.TenantContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Unit acceptance for the synthetic in-memory adapter's empty branches.
 *
 * <p>Every tenant and identifier below is synthetic. These cases prove that an
 * unknown subject or an unseeded unit yields an empty result rather than a
 * leaked value from another tenant.</p>
 */
class InMemoryServiceProfileStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-user";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PROFILE = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void anUnknownSubjectHasNoRoles() {
        assertTrue(new InMemoryServiceProfileStore().activeRoles(ORG, "nobody").isEmpty());
    }

    @Test
    void aCrossTenantActiveConnectionCannotResurrectARevokedLocalConnection() {
        InMemoryServiceProfileStore store = new InMemoryServiceProfileStore();
        store.addMember(ORG, SUBJECT, "patient", true);
        store.linkPatient(ORG, SUBJECT, PATIENT);
        store.addUnit(ORG, UNIT);
        store.addProfile(new ServiceProfile(PROFILE, ORG, UNIT, 1, ProfileStatus.APPROVED,
                new ServiceHours(ZoneId.of("UTC"), List.of()), 30, List.of("collection"),
                List.of(new LocalizedText("en", "call us"), new LocalizedText("ar", "اتصل")),
                "nurse", List.of("backup"), "escalation", "urgent", "downtime",
                "admin", "admin", NOW, "policy", NOW, NOW));
        store.addConnection(ORG, PATIENT, UNIT, false);
        store.addConnection(OTHER_ORG, PATIENT, UNIT, true);
        assertFalse(store.hasConnectedChart(ORG, SUBJECT, UNIT));

        ServiceProfileService service = new ServiceProfileService(store, new ServiceProfileActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGING", "corr-cross"));
        try {
            assertEquals(404, assertThrows(ApiException.class, () -> service.viewProfile(UNIT)).status());
            assertEquals(404, assertThrows(ApiException.class, () -> service.availability(UNIT)).status());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aUnitWithoutWorkHasAnEmptyQueue() {
        assertTrue(new InMemoryServiceProfileStore().workItems(ORG, UNIT).isEmpty());
    }
}
