package com.aurevia.messaging.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileHistory;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.SupervisorSnapshot;

/**
 * Unit acceptance for the thin MSG-06 HTTP adapter.
 *
 * <p>The controller binds fields and maps version preconditions; it never makes
 * an authorization or time decision. Every route delegates unchanged.</p>
 */
class ServiceProfileControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private ServiceProfileService service;
    private ServiceProfileController controller;

    @BeforeEach
    void setUp() {
        service = mock(ServiceProfileService.class);
        controller = new ServiceProfileController(service);
    }

    @Test
    void viewRouteDelegatesTheUnitIdentifier() {
        ServiceProfile profile = profile();
        when(service.viewProfile(UNIT)).thenReturn(profile);
        assertEquals(profile, controller.view(UNIT));
        verify(service).viewProfile(UNIT);
    }

    @Test
    void availabilityRouteDelegatesTheUnitIdentifier() {
        ServiceAvailability availability = new ServiceAvailability(false, NOW.plusSeconds(3600), "UTC");
        when(service.availability(UNIT)).thenReturn(availability);
        assertEquals(availability, controller.availability(UNIT));
        verify(service).availability(UNIT);
    }

    @Test
    void historyRouteDelegatesTheUnitIdentifier() {
        ProfileHistory history = new ProfileHistory(List.of(profile()));
        when(service.history(UNIT)).thenReturn(history);
        assertEquals(history, controller.history(UNIT));
        verify(service).history(UNIT);
    }

    @Test
    void supervisorRouteDelegatesTheUnitIdentifier() {
        SupervisorSnapshot snapshot = new SupervisorSnapshot(ORG, UNIT, 2, 1, 1, 1, NOW);
        when(service.supervisorSnapshot(UNIT)).thenReturn(snapshot);
        assertEquals(snapshot, controller.supervisor(UNIT));
        verify(service).supervisorSnapshot(UNIT);
    }

    @Test
    void publishRouteReturnsCreatedWithTheNewProfile() {
        PublishProfileRequest request = new PublishProfileRequest(UNIT, "UTC", List.of("FRIDAY|09:00|17:00"),
                30L, "nurse", List.of("backup"), "escalation", "urgent", "downtime",
                List.of("collection"), List.of(new LocalizedText("en", "call us")), "2026-11-19T12:00:00Z", 1);
        ServiceProfile draft = profile();
        when(service.publish(request)).thenReturn(draft);
        ResponseEntity<ServiceProfile> response = controller.publish(UNIT, request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(draft, response.getBody());
        assertEquals("/api/v1/service-profiles/" + draft.id(), response.getHeaders().getLocation().toString());
        verify(service).publish(request);
    }

    @Test
    void approveRouteParsesTheVersionPreconditionAndPassesItToTheService() {
        ServiceProfile approved = profile();
        when(service.approve("profile-1", 1, "site-policy")).thenReturn(approved);
        assertEquals(approved, controller.approve("profile-1", "W/\"1\"", "site-policy"));
        verify(service).approve("profile-1", 1, "site-policy");
    }

    private static ServiceProfile profile() {
        return new ServiceProfile("profile-1", ORG, UNIT, 1, ProfileStatus.APPROVED,
                new ServiceHours(java.time.ZoneId.of("UTC"), List.of()), 30, List.of("collection"),
                List.of(new LocalizedText("en", "call us")), "nurse", List.of("backup"), "escalation",
                "urgent", "downtime", "admin", "admin", NOW, "site-policy", NOW, NOW);
    }
}
