package com.aurevia.messaging.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.conversations.PatientConnectionVerifier;
import com.aurevia.messaging.conversations.PatientConnectionVerifier.VerifiedConnection;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionList;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.messaging.units.MessagingContract.UnitDirectory;
import com.aurevia.tenant.TenantContext;

/**
 * Unit acceptance for MSG-01 and ARC-07 at the domain boundary.
 *
 * <p>Every subject, patient, unit and reference below is synthetic. The tests
 * enumerate the safety controls from the clinical-safety review: cross-chart
 * reference reuse, wrong-unit reference reuse, arbitrary directory contact,
 * stale links, forged invitations, duplicate threads, cross-org disclosure and
 * stale membership.</p>
 */
class CareUnitDirectoryServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-user";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_PATIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OTHER_UNIT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String CROSS_ORG_UNIT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
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
        store.addUnit(unit(OTHER_UNIT, ORG, "Synthetic Radiology", true, "radiology"));
        store.addUnit(unit(CROSS_ORG_UNIT, OTHER_ORG, "Foreign Unit", true));
        store.addRelationship(relationship("rel-1", PATIENT, UNIT, ConnectionContext.ORDER, REFERENCE,
                true, null, false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void eligibleUnitsAreLimitedToActiveVerifiedRelationshipsInTheActiveTenant() {
        UnitDirectory directory = service.eligibleUnits();
        assertEquals(1, directory.units().size());
        CareUnit unit = directory.units().get(0);
        assertEquals(UNIT, unit.id());
        assertEquals(ORG, unit.organizationId());
        assertEquals(List.of("collection", "results"), unit.topics());
        assertThrows(UnsupportedOperationException.class, () -> directory.units().add(unit));
    }

    @Test
    void inactiveRelationshipDoesNotExposeItsUnit() {
        store.addRelationship(relationship("rel-inactive", PATIENT, OTHER_UNIT, ConnectionContext.ORDER,
                "inactive-ref", false, null, false));
        assertEquals(1, service.eligibleUnits().units().size());
    }

    @Test
    void inactiveUnitIsNotListedEvenWithAnActiveRelationship() {
        store.addUnit(unit(OTHER_UNIT, ORG, "Synthetic Radiology", false));
        store.addRelationship(relationship("rel-2", PATIENT, OTHER_UNIT, ConnectionContext.REFERRAL,
                "referral-ref", true, null, false));
        assertEquals(1, service.eligibleUnits().units().size());
    }

    @Test
    void connectGrantsOneUnitScopedConnection() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        assertEquals(ORG, connection.organizationId());
        assertEquals(PATIENT, connection.patientId());
        assertEquals(UNIT, connection.unitId());
        assertEquals(ConnectionContext.ORDER, connection.context());
        assertEquals(ConnectionState.ACTIVE, connection.state());
        assertEquals(SUBJECT, connection.verifiedBy());
        assertEquals(NOW, connection.verifiedAt());
        assertEquals(NOW, connection.createdAt());
        assertEquals(null, connection.revokedAt());
        assertEquals("SUCCESS", store.auditEvents(ORG).get(0).outcome());
        assertTrue(store.auditEvents(ORG).get(0).resourceId().contains(connection.id()));
    }

    @Test
    void connectIsIdempotentForTheSameVerifiedReference() {
        PatientUnitConnection first = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        PatientUnitConnection second = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        assertEquals(first.id(), second.id());
        assertEquals(1, store.connectionsFor(ORG, PATIENT).size());
    }

    @Test
    void connectConsumesASingleUseInvitation() {
        store.addRelationship(relationship("rel-invite", PATIENT, OTHER_UNIT, ConnectionContext.INVITATION,
                "invite-ref", true, NOW.plusSeconds(600), false));
        service.connect(request(PATIENT, OTHER_UNIT, "invitation", "invite-ref"));
        assertTrue(store.relationship(ORG, "rel-invite").orElseThrow().consumed());
    }

    @Test
    void connectDeniesAPatientNotLinkedToTheAuthenticatedSubject() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request(OTHER_PATIENT, UNIT, "order", REFERENCE)));
        assertEquals(403, error.status());
        assertEquals(Set.of(), Set.copyOf(store.connectionsFor(ORG, PATIENT)));
    }

    @Test
    void connectConcealsAnUnknownPatient() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request("99999999-9999-9999-9999-999999999999", UNIT, "order", REFERENCE)));
        assertEquals(404, error.status());
    }

    @Test
    void connectConcealsAnUnknownOrCrossTenantUnit() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, "99999999-9999-9999-9999-999999999999",
                        "order", REFERENCE))).status());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, CROSS_ORG_UNIT, "order", REFERENCE))).status());
    }

    @Test
    void connectBlocksArbitraryDirectoryContactWithoutAnEligibleRelationship() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, OTHER_UNIT, "order", REFERENCE)));
        assertEquals(422, error.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", error.code());
    }

    @Test
    void connectRejectsACrossChartReference() {
        store.linkPatient(ORG, SUBJECT, OTHER_PATIENT);
        store.addRelationship(relationship("rel-other", OTHER_PATIENT, UNIT, ConnectionContext.ORDER,
                "other-chart-ref", true, null, false));
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", "other-chart-ref")));
        assertEquals(422, error.status());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", error.code());
    }

    @Test
    void connectRejectsAWrongUnitReference() {
        store.addRelationship(relationship("rel-wrong-unit", PATIENT, OTHER_UNIT, ConnectionContext.ORDER,
                "wrong-unit-ref", true, null, false));
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", "wrong-unit-ref")));
        assertEquals(422, error.status());
    }

    @Test
    void connectRejectsAWrongContextAndAnInactiveRelationship() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "referral", REFERENCE))).status());
        store.addRelationship(relationship("rel-inactive2", PATIENT, UNIT, ConnectionContext.INVITATION,
                "inactive-invite", false, null, false));
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "invitation", "inactive-invite")));
        assertEquals(422, error.status());
    }

    @Test
    void connectRejectsExpiredAndConsumedInvitations() {
        store.addRelationship(relationship("rel-expired", PATIENT, UNIT, ConnectionContext.INVITATION,
                "expired", true, NOW.minusSeconds(1), false));
        store.addRelationship(relationship("rel-consumed", PATIENT, UNIT, ConnectionContext.INVITATION,
                "consumed", true, NOW.plusSeconds(600), true));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "invitation", "expired"))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "invitation", "consumed"))).status());
    }

    @Test
    void listConnectionsIsScopedToTheVerifiedChart() {
        service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        seedOtherSubject();
        store.addRelationship(relationship("rel-other-patient", OTHER_PATIENT, OTHER_UNIT,
                ConnectionContext.ORDER, "other-ref", true, null, false));
        connectAs("other-subject", request(OTHER_PATIENT, OTHER_UNIT, "order", "other-ref"));
        ConnectionList mine = service.listConnections(PATIENT);
        assertEquals(1, mine.connections().size());
        assertEquals(PATIENT, mine.connections().get(0).patientId());
        assertThrows(UnsupportedOperationException.class, () -> mine.connections().clear());
    }

    @Test
    void protectedActionsDenyInactiveMembershipAndMissingRole() {
        store.addMember(ORG, SUBJECT, "patient", false);
        ApiException denied = assertThrows(ApiException.class, () -> service.eligibleUnits());
        assertEquals(403, denied.status());
        assertEquals("DENIED", store.auditEvents(ORG).get(0).outcome());

        store.addMember(ORG, SUBJECT, "lab_verifier", true);
        assertEquals(403, assertThrows(ApiException.class, () -> service.eligibleUnits()).status());
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        ApiException error = assertThrows(ApiException.class, () -> service.eligibleUnits());
        assertEquals(401, error.status());
    }

    @Test
    void revokeStopsAllFutureAccessThroughTheConnection() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        service.requireActiveConnection(PATIENT, UNIT);
        PatientUnitConnection revoked = service.revoke(connection.id());
        assertEquals(ConnectionState.REVOKED, revoked.state());
        assertEquals(NOW, revoked.revokedAt());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnection(PATIENT, UNIT)).status());
    }

    @Test
    void revokeConcealsUnknownAndOtherPatientsConnections() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.revoke("99999999-9999-9999-9999-999999999999")).status());
        seedOtherSubject();
        store.addRelationship(relationship("rel-other-patient", OTHER_PATIENT, UNIT,
                ConnectionContext.ORDER, "other-ref", true, null, false));
        PatientUnitConnection other = connectAs("other-subject",
                request(OTHER_PATIENT, UNIT, "order", "other-ref"));
        assertEquals(404, assertThrows(ApiException.class, () -> service.revoke(other.id())).status());
    }

    @Test
    void connectAfterRevocationOrWithADifferentContextConflicts() {
        PatientUnitConnection first = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        service.revoke(first.id());
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", REFERENCE))).status());

        String otherId = "77777777-7777-7777-7777-777777777777";
        store.insertConnection(ORG, new PatientUnitConnection(otherId, ORG, PATIENT, UNIT,
                ConnectionContext.REFERRAL, "other-reference", "rel-x", ConnectionState.ACTIVE,
                SUBJECT, NOW, NOW, null));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", "other-reference"))).status());
    }

    @Test
    void storeEnforcesOneConnectionPerBusinessKey() {
        Instant now = NOW;
        PatientUnitConnection first = new PatientUnitConnection(
                "88888888-8888-8888-8888-888888888888", ORG, PATIENT, UNIT,
                ConnectionContext.ORDER, REFERENCE, "rel-1", ConnectionState.ACTIVE,
                SUBJECT, now, now, null);
        PatientUnitConnection competing = new PatientUnitConnection(
                "99999999-9999-9999-9999-999999999999", ORG, PATIENT, UNIT,
                ConnectionContext.ORDER, REFERENCE, "rel-1", ConnectionState.ACTIVE,
                SUBJECT, now, now, null);
        assertEquals(first.id(), store.insertConnection(ORG, first).id());
        assertEquals(first.id(), store.insertConnection(ORG, competing).id());
        assertEquals(1, store.connectionsFor(ORG, PATIENT).size());
    }

    @Test
    void tenantAdminMayRevokeAConnectionForSafety() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        store.addMember(ORG, "admin-user", "tenant_admin", true);
        TenantContext.set(new TenantContext(ORG, "admin-user", "PATIENT_MESSAGING", "corr-2"));
        try {
            assertEquals(ConnectionState.REVOKED, service.revoke(connection.id()).state());
        } finally {
            TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        }
    }

    @Test
    void revokeTwiceConflicts() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        service.revoke(connection.id());
        assertEquals(409, assertThrows(ApiException.class, () -> service.revoke(connection.id())).status());
    }

    @Test
    void unlinkingTheChartStopsFutureConnectionUse() {
        service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        store.unlinkPatient(ORG, SUBJECT, PATIENT);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnection(PATIENT, UNIT)).status());
    }

    @Test
    void requireActiveConnectionDeniesAWrongUnit() {
        service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnection(PATIENT, OTHER_UNIT)).status());
    }

    @Test
    void invalidCommandFieldsAreRejectedBeforeAnyStoreAccess() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.connect(request("bad", UNIT, "order", REFERENCE))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "demographics", REFERENCE))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", " "))).status());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void listConnectionsConcealsAnUnverifiedChart() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.listConnections(OTHER_PATIENT)).status());
    }

    @Test
    void requireActiveConnectionFailsClosedForInactiveMembership() {
        service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        store.addMember(ORG, SUBJECT, "patient", false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnection(PATIENT, UNIT)).status());
    }

    @Test
    void requireActiveConnectionDeniesAnUnverifiedChart() {
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnection(OTHER_PATIENT, UNIT)).status());
    }

    @Test
    void storeLookupsNeverEscapeTheirOrganization() {
        assertTrue(store.activeRoles(ORG, "ghost").isEmpty());
        store.addMember(ORG, "inactive", "patient", false);
        assertTrue(store.activeRoles(ORG, "inactive").isEmpty());
        assertTrue(store.verifiedPatientCharts(ORG, "ghost").isEmpty());
        assertTrue(store.relationship(ORG, "ghost").isEmpty());
        store.consumeRelationship(ORG, "ghost");
        store.unlinkPatient(ORG, "ghost", PATIENT);

        store.addRelationship(new MessagingContract.EligibleRelationship("rel-other-org", OTHER_ORG,
                PATIENT, UNIT, ConnectionContext.ORDER, "other-org-ref", true, null, false));
        store.addRelationship(relationship("rel-foreign", PATIENT, CROSS_ORG_UNIT,
                ConnectionContext.ORDER, "foreign-ref", true, null, false));
        store.addRelationship(relationship("rel-missing-unit", PATIENT,
                "99999999-9999-9999-9999-999999999999",
                ConnectionContext.ORDER, "missing-ref", true, null, false));
        assertEquals(1, store.relationshipsFor(ORG, PATIENT).size());
        assertTrue(store.relationship(ORG, "rel-foreign").isEmpty());

        service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        store.addRelationship(relationship("rel-other-unit", PATIENT, OTHER_UNIT,
                ConnectionContext.ORDER, "other-unit-ref", true, null, false));
        service.connect(request(PATIENT, OTHER_UNIT, "order", "other-unit-ref"));
        assertTrue(store.findConnectionByReference(ORG, PATIENT, UNIT, REFERENCE).isPresent());
        assertTrue(store.findConnectionByReference(ORG, PATIENT, UNIT, "no-such-ref").isEmpty());

        PatientUnitConnection foreign = new PatientUnitConnection(
                "ffffffff-ffff-ffff-ffff-ffffffffffff", OTHER_ORG, PATIENT, UNIT,
                ConnectionContext.ORDER, "foreign-connection", "rel-1", ConnectionState.ACTIVE,
                "other-subject", NOW, NOW, null);
        store.insertConnection(OTHER_ORG, foreign);
        store.insertConnection(ORG, foreign);
        assertTrue(store.findConnection(ORG, foreign.id()).isEmpty());
        store.revokeConnection(ORG, "unknown", NOW);
        assertEquals(2, store.connectionsFor(ORG, PATIENT).size());
    }

    @Test
    void connectDenialIsAuditedWithTheAttemptedAction() {
        store.unlinkPatient(ORG, SUBJECT, PATIENT);
        ApiException error = assertThrows(ApiException.class,
                () -> service.connect(request(PATIENT, UNIT, "order", REFERENCE)));
        assertEquals(403, error.status());
        assertEquals(1, store.auditEvents(ORG).size());
        MessagingAudit audit = store.auditEvents(ORG).get(0);
        assertEquals(MessagingActions.CONNECTION_CREATE, audit.action());
        assertEquals("DENIED", audit.outcome());
    }

    @Test
    void listConnectionsDenialIsAuditedWithTheAttemptedAction() {
        store.unlinkPatient(ORG, SUBJECT, PATIENT);
        assertThrows(ApiException.class, () -> service.listConnections(PATIENT));
        MessagingAudit audit = store.auditEvents(ORG).get(0);
        assertEquals(MessagingActions.CONNECTION_LIST, audit.action());
        assertEquals("DENIED", audit.outcome());
    }

    @Test
    void protectedConnectionUseDenialIsAudited() {
        service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireActiveConnection(PATIENT, OTHER_UNIT));
        assertEquals(403, error.status());
        MessagingAudit denial = store.auditEvents(ORG).stream()
                .filter(audit -> "DENIED".equals(audit.outcome()))
                .findFirst().orElseThrow();
        assertEquals("CONNECTION_USE", denial.action());
        assertEquals(OTHER_UNIT, denial.resourceId());
    }

    @Test
    void requireActiveResolvesServerDerivedIdentitiesForTheExactConnection() {
        String connectionId = service.connect(request(PATIENT, UNIT, "order", REFERENCE)).id();
        PatientConnectionVerifier verifier = service;
        VerifiedConnection verified = verifier.requireActive(PATIENT, "lab", connectionId);
        assertEquals(PATIENT, verified.patientId());
        assertEquals(connectionId, verified.connectionId());
        assertEquals("lab", verified.unitKey());
    }

    @Test
    void requireActiveDeniesAnUnknownConnection() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", "99999999-9999-9999-9999-999999999999"));
        assertEquals(404, error.status());
        assertEquals("NOT_FOUND", error.code());
        assertEquals("NOT_FOUND", store.auditEvents(ORG).stream()
                .filter(audit -> "CONNECTION_USE".equals(audit.action())).findFirst().orElseThrow().outcome());
    }

    @Test
    void requireActiveDeniesAConnectionOwnedByAnotherChart() {
        String connectionId = service.connect(request(PATIENT, UNIT, "order", REFERENCE)).id();
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireActive(OTHER_PATIENT, "lab", connectionId)).status());
    }

    @Test
    void requireActiveDeniesAConnectionUsedForTheWrongUnitKind() {
        String connectionId = service.connect(request(PATIENT, UNIT, "order", REFERENCE)).id();
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "clinic", connectionId)).status());
    }

    @Test
    void requireActiveDeniesARevokedConnection() {
        String connectionId = service.connect(request(PATIENT, UNIT, "order", REFERENCE)).id();
        service.revoke(connectionId);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", connectionId)).status());
    }

    @Test
    void requireActiveDeniesConnectionsWhoseUnitIsMissingOrInactive() {
        String missingUnit = "55555555-5555-5555-5555-555555555555";
        String inactiveUnit = "66666666-6666-6666-6666-666666666666";
        store.addUnit(unit(inactiveUnit, ORG, "Closed Lab", false));
        String missingConnection = "c0000000-0000-0000-0000-000000000001";
        String inactiveConnection = "c0000000-0000-0000-0000-000000000002";
        store.insertConnection(ORG, connection(missingConnection, PATIENT, missingUnit));
        store.insertConnection(ORG, connection(inactiveConnection, PATIENT, inactiveUnit));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", missingConnection)).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", inactiveConnection)).status());
    }

    @Test
    void requireActiveRejectsAnUnknownUnitKeyBeforeAnyLookup() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "demographics", "conn-1")).status());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void requireActiveFailsClosedWithoutTenantContext() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.requireActive(PATIENT, "lab", "d0000000-0000-0000-0000-000000000001")).status());
    }

    @Test
    void requireActiveConnectionByIdReturnsTheActiveBoundConnectionAndTenantUnitCategory() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        com.aurevia.messaging.units.MessagingContract.VerifiedConnection resolved =
                service.requireActiveConnectionById(PATIENT, connection.id());
        assertEquals(connection.id(), resolved.connection().id());
        assertEquals(PATIENT, resolved.connection().patientId());
        assertEquals(UNIT, resolved.connection().unitId());
        assertEquals(ConnectionState.ACTIVE, resolved.connection().state());
        assertEquals("lab", resolved.unitKey());
    }

    @Test
    void requireActiveConnectionByIdConcealsAnUnknownConnection() {
        ApiException error = assertThrows(ApiException.class, () -> service.requireActiveConnectionById(
                PATIENT, "99999999-9999-9999-9999-999999999999"));
        assertEquals(404, error.status());
        assertEquals("NOT_FOUND", error.code());
    }

    @Test
    void requireActiveConnectionByIdConcealsAnotherPatientsConnection() {
        seedOtherSubject();
        store.addRelationship(relationship("rel-other-patient", OTHER_PATIENT, UNIT,
                ConnectionContext.ORDER, "other-ref", true, null, false));
        PatientUnitConnection other = connectAs("other-subject",
                request(OTHER_PATIENT, UNIT, "order", "other-ref"));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, other.id())).status());
    }

    @Test
    void requireActiveConnectionByIdDeniesARevokedConnection() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        service.revoke(connection.id());
        ApiException error = assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, connection.id()));
        assertEquals(403, error.status());
        assertEquals("FORBIDDEN", error.code());
    }

    @Test
    void requireActiveConnectionByIdDeniesInactiveMembershipAndUnlinkedChart() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        store.addMember(ORG, SUBJECT, "patient", false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, connection.id())).status());

        store.addMember(ORG, SUBJECT, "patient", true);
        store.unlinkPatient(ORG, SUBJECT, PATIENT);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, connection.id())).status());
    }

    @Test
    void requireActiveConnectionByIdRejectsMalformedIdentifiersBeforeAnyStoreAccess() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById("bad", UNIT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, "bad-connection-id")).status());
        assertTrue(store.auditEvents(ORG).isEmpty());
    }

    @Test
    void requireActiveConnectionByIdDeniesAUnitWithoutAConversationCategoryOrAnInactiveUnit() {
        String noKeyUnit = "66666666-6666-6666-6666-666666666666";
        store.addUnit(new CareUnit(noKeyUnit, ORG, "facility-1", "No Key", List.of("misc"), true));
        String inactiveUnit = "77777777-7777-7777-7777-777777777777";
        store.addUnit(unit(inactiveUnit, ORG, "Inactive Lab", false));
        String noKeyConnection = "a0000000-0000-0000-0000-000000000001";
        String inactiveConnection = "a0000000-0000-0000-0000-000000000002";
        store.insertConnection(ORG, connection(noKeyConnection, PATIENT, noKeyUnit));
        store.insertConnection(ORG, connection(inactiveConnection, PATIENT, inactiveUnit));

        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, noKeyConnection)).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, inactiveConnection)).status());
    }

    @Test
    void requireActiveConnectionByIdConcealsAMissingRecipientCareUnit() {
        String missingUnit = "99999999-9999-9999-9999-999999999999";
        String connectionId = "b0000000-0000-0000-0000-000000000001";
        store.insertConnection(ORG, connection(connectionId, PATIENT, missingUnit));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireActiveConnectionById(PATIENT, connectionId)).status());
    }

    @Test
    void requireUsableConnectionAllowsAStaffActionButDeniesInactiveMembership() {
        PatientUnitConnection connection = service.connect(request(PATIENT, UNIT, "order", REFERENCE));
        store.addMember(ORG, "staff-user", "lab_verifier", true);
        TenantContext.set(new TenantContext(ORG, "staff-user", "PATIENT_MESSAGING", "corr-2"));
        try {
            assertEquals(connection.id(),
                    service.requireUsableConnection(PATIENT, connection.id()).connection().id());
            store.addMember(ORG, "staff-user", "lab_verifier", false);
            assertEquals(403, assertThrows(ApiException.class,
                    () -> service.requireUsableConnection(PATIENT, connection.id())).status());
        } finally {
            TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        }
    }

    @Test
    void requireUsableConnectionConcealsAForeignConnection() {
        seedOtherSubject();
        store.addRelationship(relationship("rel-other-patient", OTHER_PATIENT, UNIT,
                ConnectionContext.ORDER, "other-ref", true, null, false));
        PatientUnitConnection other = connectAs("other-subject",
                request(OTHER_PATIENT, UNIT, "order", "other-ref"));
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireUsableConnection(PATIENT, other.id())).status());
    }

    private static PatientUnitConnection connection(String id, String patient, String unit) {
        return new PatientUnitConnection(id, ORG, patient, unit, ConnectionContext.ORDER,
                "ref-" + id, "rel-" + id, ConnectionState.ACTIVE, SUBJECT, NOW, NOW, null);
    }

    private void seedOtherSubject() {
        store.addMember(ORG, "other-subject", "patient", true);
        store.linkPatient(ORG, "other-subject", OTHER_PATIENT);
    }

    private PatientUnitConnection connectAs(String subject, ConnectRequest request) {
        TenantContext.set(new TenantContext(ORG, subject, "PATIENT_MESSAGING", "corr-1"));
        try {
            return service.connect(request);
        } finally {
            TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_MESSAGING", "corr-1"));
        }
    }

    private static CareUnit unit(String id, String org, String name, boolean active) {
        return unit(id, org, name, active, "lab");
    }

    private static CareUnit unit(String id, String org, String name, boolean active, String unitKey) {
        return new CareUnit(id, org, "facility-1", name, List.of("collection", "results"), active, unitKey);
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
