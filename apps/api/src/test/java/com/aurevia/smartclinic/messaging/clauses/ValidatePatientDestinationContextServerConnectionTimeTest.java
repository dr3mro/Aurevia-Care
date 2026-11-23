package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingActions;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Clause proof for MSG-01: the server validates patient, destination and context
 * at connection time and on every protected action.
 *
 * <p>This is a domain/service-boundary proof. It drives the real
 * {@link com.aurevia.smartclinic.messaging.units.CareUnitDirectoryService} through a
 * synthetic tenant-scoped store and asserts the authoritative status and code of
 * each refusal. Every subject, patient, unit and reference below is synthetic; no
 * HTTP entry point, real identity or patient data is involved. The neighbouring
 * {@code CareUnitDirectoryServiceTest} covers the same guards independently; this
 * class proves only this clause and deliberately avoids invitation, listing and
 * revocation-conflict behaviour.</p>
 */
class ValidatePatientDestinationContextServerConnectionTimeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-user";
    private static final String STAFF_SUBJECT = "staff-user";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String FOREIGN_UNIT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String INACTIVE_UNIT = "12121212-1212-1212-1212-121212121212";
    private static final String UNKNOWN_ID = "99999999-9999-9999-9999-999999999999";
    private static final String REFERENCE = "order-ref-1";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingStore store;
    private CareUnitDirectoryService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingStore();
        service = new CareUnitDirectoryService(store, new MessagingActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        store.addMember(ORG, SUBJECT, "patient", true);
        store.addPatient(ORG, PATIENT);
        store.addPatient(ORG, OTHER_PATIENT);
        store.linkPatient(ORG, SUBJECT, PATIENT);
        store.addUnit(unit(UNIT, ORG, "Synthetic Laboratory", true));
        store.addUnit(unit(FOREIGN_UNIT, OTHER_ORG, "Foreign Unit", true));
        store.addRelationship(relationship("rel-1", PATIENT, UNIT, ConnectionContext.ORDER,
                REFERENCE, true, null, false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void connectAcceptsOnlyVerifiedPatientUnitAndContext() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        assertEquals(ORG, connection.organizationId());
        assertEquals(PATIENT, connection.patientId());
        assertEquals(UNIT, connection.unitId());
        assertEquals(ConnectionContext.ORDER, connection.context());
        assertEquals(ConnectionState.ACTIVE, connection.state());
        assertEquals(SUBJECT, connection.verifiedBy());
        assertEquals(NOW, connection.verifiedAt());
        assertEquals("SUCCESS", store.auditEvents(ORG).get(0).outcome());

        // Every identity used by a later protected action must come from the stored
        // connection, not from the caller's copy of the request.
        VerifiedConnection verified = service.requireActive(PATIENT, "lab", connection.id());
        assertEquals(PATIENT, verified.patientId());
        assertEquals(connection.id(), verified.connectionId());
        assertEquals("lab", verified.unitKey());
    }

    @Test
    void connectRefusesUnverifiedPatient() {
        // An unknown chart is concealed; a known chart that is not linked to this
        // account is a distinct denial. Neither can create a connection.
        ApiException unknown = assertThrows(ApiException.class,
                () -> service.connect(request(UNKNOWN_ID, UNIT, "order", REFERENCE)));
        assertEquals(404, unknown.status());
        assertEquals("NOT_FOUND", unknown.code());

        ApiException unlinked = assertThrows(ApiException.class,
                () -> service.connect(request(OTHER_PATIENT, UNIT, "order", REFERENCE)));
        assertEquals(403, unlinked.status());
        assertEquals("FORBIDDEN", unlinked.code());
        assertTrue(store.connectionsFor(ORG, PATIENT).isEmpty());
        assertTrue(store.connectionsFor(ORG, OTHER_PATIENT).isEmpty());
    }

    @Test
    void connectRefusesInactiveOrForeignUnit() {
        store.addUnit(unit(INACTIVE_UNIT, ORG, "Closed Laboratory", false));
        store.addRelationship(relationship("rel-closed", PATIENT, INACTIVE_UNIT, ConnectionContext.ORDER,
                "closed-ref", true, null, false));
        ApiException inactive = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, INACTIVE_UNIT, "order", "closed-ref")));
        assertEquals(404, inactive.status());
        assertEquals("NOT_FOUND", inactive.code());

        // A unit in another tenant is indistinguishable from no unit at all.
        store.addRelationship(relationship("rel-foreign", PATIENT, FOREIGN_UNIT, ConnectionContext.ORDER,
                "foreign-ref", true, null, false));
        ApiException foreign = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, FOREIGN_UNIT, "order", "foreign-ref")));
        assertEquals(404, foreign.status());
        assertEquals("NOT_FOUND", foreign.code());
        assertTrue(store.connectionsFor(ORG, PATIENT).isEmpty());
    }

    @Test
    void connectRefusesContextOrReferenceMismatch() {
        // Field validation runs first, so a malformed context leaves no
        // authorization fact behind.
        ApiException invalidContext = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "demographics", REFERENCE)));
        assertEquals(422, invalidContext.status());
        assertEquals("INVALID_CONTEXT", invalidContext.code());
        assertTrue(store.auditEvents(ORG).isEmpty());

        ApiException wrongContext = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "referral", REFERENCE)));
        assertEquals(422, wrongContext.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", wrongContext.code());

        ApiException wrongReference = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", "not-the-verified-reference")));
        assertEquals(422, wrongReference.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", wrongReference.code());
        assertTrue(store.connectionsFor(ORG, PATIENT).isEmpty());
    }

    @Test
    void requireActiveRefusesRevokedWrongUnitOrUnknownConnection() {
        String connectionId = service.connect(request(PATIENT, UNIT, "order", REFERENCE)).id();

        ApiException unknown = assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", UNKNOWN_ID));
        assertEquals(404, unknown.status());
        assertEquals("NOT_FOUND", unknown.code());

        ApiException wrongUnit = assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "clinic", connectionId));
        assertEquals(403, wrongUnit.status());
        assertEquals("FORBIDDEN", wrongUnit.code());

        // A connection owned by another chart is concealed rather than reported.
        ApiException wrongChart = assertThrows(ApiException.class,
                () -> service.requireActive(OTHER_PATIENT, "lab", connectionId));
        assertEquals(404, wrongChart.status());
        assertEquals("NOT_FOUND", wrongChart.code());

        service.revoke(connectionId);
        ApiException revoked = assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", connectionId));
        assertEquals(403, revoked.status());
        assertEquals("FORBIDDEN", revoked.code());
    }

    @Test
    void requireUsableConnectionRefusesInactiveStaffMembership() {
        String connectionId = service.connect(request(PATIENT, UNIT, "order", REFERENCE)).id();
        store.addMember(ORG, STAFF_SUBJECT, "lab_verifier", true);
        TenantContext.set(new TenantContext(ORG, STAFF_SUBJECT, "PATIENT_MESSAGING", "corr-2"));
        try {
            assertEquals(connectionId,
                    service.requireUsableConnection(PATIENT, connectionId).connection().id());

            // Staff authority is re-derived on the very next protected action; a
            // membership change takes effect without a session change.
            store.addMember(ORG, STAFF_SUBJECT, "lab_verifier", false);
            ApiException denied = assertThrows(ApiException.class,
                    () -> service.requireUsableConnection(PATIENT, connectionId));
            assertEquals(403, denied.status());
            assertEquals("FORBIDDEN", denied.code());
        } finally {
            TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        }
    }

    private static CareUnit unit(String id, String organizationId, String name, boolean active) {
        return new CareUnit(id, organizationId, "facility-1", name,
                List.of("collection", "results"), active, "lab");
    }

    private static EligibleRelationship relationship(String id, String patient, String unit,
                                                      ConnectionContext context, String reference,
                                                      boolean active, Instant expiresAt, boolean consumed) {
        return new EligibleRelationship(id, ORG, patient, unit, context, reference, active, expiresAt, consumed);
    }

    private static ConnectRequest request(String patient, String unit, String context, String reference) {
        return new ConnectRequest(patient, unit, context, reference);
    }
}
