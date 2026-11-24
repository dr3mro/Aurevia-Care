package com.aurevia.messaging.units;

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

import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionList;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.messaging.units.MessagingContract.UnitDirectory;

/**
 * Unit acceptance for the thin MSG-01 HTTP adapter.
 *
 * <p>The adapter never decides authorization; the test proves that every route
 * delegates to the domain service with the caller's fields unchanged.</p>
 */
class CareUnitControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    private CareUnitDirectoryService service;
    private CareUnitController controller;

    @BeforeEach
    void setUp() {
        service = mock(CareUnitDirectoryService.class);
        controller = new CareUnitController(service);
    }

    @Test
    void directoryRouteDelegatesToTheService() {
        UnitDirectory directory = new UnitDirectory(List.of(
                new CareUnit(UNIT, ORG, "facility-1", "Synthetic Lab", "lab", List.of("lab"), true)));
        when(service.eligibleUnits()).thenReturn(directory);
        assertEquals(directory, controller.units());
        verify(service).eligibleUnits();
    }

    @Test
    void connectRouteReturnsCreatedWithTheNewConnection() {
        ConnectRequest request = new ConnectRequest(PATIENT, UNIT, "order", "ref-1");
        PatientUnitConnection connection = connection();
        when(service.connect(request)).thenReturn(connection);
        ResponseEntity<PatientUnitConnection> response = controller.connect(request);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(connection, response.getBody());
        assertEquals("/api/v1/patient-unit-connections/" + connection.id(),
                response.getHeaders().getLocation().toString());
        verify(service).connect(request);
    }

    @Test
    void listRoutePassesThePatientIdentifierUnchanged() {
        ConnectionList list = new ConnectionList(List.of(connection()));
        when(service.listConnections(PATIENT)).thenReturn(list);
        assertEquals(list, controller.connections(PATIENT));
        verify(service).listConnections(PATIENT);
    }

    @Test
    void revokeRouteDelegatesTheConnectionIdentifier() {
        PatientUnitConnection revoked = connection();
        when(service.revoke(revoked.id())).thenReturn(revoked);
        assertEquals(revoked, controller.revoke(revoked.id()));
        verify(service).revoke(revoked.id());
    }

    private static PatientUnitConnection connection() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        return new PatientUnitConnection("dddddddd-dddd-dddd-dddd-dddddddddddd", ORG, PATIENT, UNIT,
                ConnectionContext.ORDER, "ref-1", "rel-1", ConnectionState.ACTIVE,
                "patient-user", now, now, null);
    }
}
