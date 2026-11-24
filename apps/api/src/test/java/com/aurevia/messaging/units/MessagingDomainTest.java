package com.aurevia.messaging.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;

/**
 * Unit acceptance for the pure MSG-01 / ARC-07 domain vocabulary and validation.
 *
 * <p>All fixtures are synthetic identifiers; no patient data is used.</p>
 */
class MessagingDomainTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String UNIT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    void connectionContextRoundTripsEveryWireValue() {
        for (ConnectionContext context : ConnectionContext.values()) {
            assertEquals(context, ConnectionContext.from(context.wire()));
        }
        assertEquals(5, ConnectionContext.values().length);
        assertEquals("care_relationship", ConnectionContext.CARE_RELATIONSHIP.wire());
        assertEquals("invitation", ConnectionContext.INVITATION.wire());
    }

    @Test
    void unknownConnectionContextIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConnectionContext.from("demographics"));
        assertTrue(error.getMessage().contains("connection context"));
    }

    @Test
    void connectionStateRoundTripsAndRejectsUnknown() {
        assertEquals(ConnectionState.ACTIVE, ConnectionState.from(ConnectionState.ACTIVE.wire()));
        assertEquals(ConnectionState.REVOKED, ConnectionState.from(ConnectionState.REVOKED.wire()));
        assertEquals("active", ConnectionState.ACTIVE.wire());
        assertThrows(IllegalArgumentException.class, () -> ConnectionState.from("paused"));
    }

    @Test
    void eligibleRelationshipCarriesSingleUseInvitationControls() {
        var relationship = new MessagingContract.EligibleRelationship("rel-1", ORG, "patient-1", UNIT,
                ConnectionContext.INVITATION, "inv-reference", true,
                java.time.Instant.parse("2026-12-01T00:00:00Z"), false);
        assertTrue(relationship.active());
        assertFalse(relationship.consumed());
        assertTrue(relationship.expiresAt().isAfter(java.time.Instant.parse("2026-11-19T00:00:00Z")));
    }

    @Test
    void careUnitDefensivelyCopiesSupportedTopics() {
        List<String> topics = new java.util.ArrayList<>(List.of("collection", "results"));
        CareUnit unit = new CareUnit(UNIT, ORG, "facility-1", "Synthetic Laboratory", "lab", topics, true);
        topics.add("injected");
        assertEquals(List.of("collection", "results"), unit.topics());
        assertThrows(UnsupportedOperationException.class, () -> unit.topics().add("x"));
    }

    @Test
    void careUnitKindIsBoundedToTheApprovedUnitKeys() {
        assertEquals("lab", new CareUnit(UNIT, ORG, "facility-1", "Synthetic Laboratory", "lab",
                List.of("results"), true).kind());
        assertThrows(IllegalArgumentException.class, () -> new CareUnit(UNIT, ORG, "facility-1", "Bad",
                "demographics", List.of("results"), true));
        assertThrows(IllegalArgumentException.class, () -> new CareUnit(UNIT, ORG, "facility-1", "Bad",
                null, List.of("results"), true));
    }

    @Test
    void distinctSyntheticUnitsAreNotEqual() {
        CareUnit first = new CareUnit(UNIT, ORG, "facility-1", "Synthetic Laboratory", "lab", List.of("lab"), true);
        CareUnit second = new CareUnit("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", ORG, "facility-1",
                "Synthetic Radiology", "radiology", List.of("imaging"), true);
        assertNotEquals(first, second);
        assertEquals(first, new CareUnit(UNIT, ORG, "facility-1", "Synthetic Laboratory", "lab", List.of("lab"), true));
    }

    @Test
    void memberRolesAreBoundedPerAction() {
        MessagingActions actions = new MessagingActions();
        assertTrue(actions.allows(MessagingActions.UNIT_LIST, "patient"));
        assertTrue(actions.allows(MessagingActions.CONNECTION_CREATE, "patient"));
        assertFalse(actions.allows(MessagingActions.CONNECTION_CREATE, "tenant_admin"));
        assertTrue(actions.allows(MessagingActions.CONNECTION_REVOKE, "tenant_admin"));
        assertTrue(actions.allows(MessagingActions.CONNECTION_LIST, "privacy_auditor"));
        assertFalse(actions.allows(MessagingActions.UNIT_LIST, "lab_verifier"));
        assertTrue(actions.allows(MessagingActions.UNIT_LIST, "tenant_admin"));
        assertTrue(actions.allows(MessagingActions.CONNECTION_REVOKE, "patient"));
        assertFalse(actions.allows(MessagingActions.CONNECTION_REVOKE, "lab_verifier"));
        assertFalse(actions.allows("UNKNOWN", "patient"));
    }

    @Test
    void protectedConnectionUseActionIsBoundedToSelfService() {
        MessagingActions actions = new MessagingActions();
        assertTrue(actions.allows("CONNECTION_USE", "patient"));
        assertFalse(actions.allows("CONNECTION_USE", "tenant_admin"));
        assertFalse(actions.allows("CONNECTION_USE", "lab_verifier"));
    }

    @Test
    void uuidValidationRejectsMalformedIdentifiers() {
        assertEquals(UNIT, MessagingInputs.uuid(UNIT, "unitId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingInputs.uuid("not-a-uuid", "unitId")).status());
        assertEquals("INVALID_UNITID", assertThrows(ApiException.class,
                () -> MessagingInputs.uuid(null, "unitId")).code());
    }

    @Test
    void textValidationNormalizesAndBoundsLength() {
        assertEquals("Synthetic Laboratory", MessagingInputs.text("  Synthetic Laboratory  ", "name", 64));
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingInputs.text("   ", "name", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingInputs.text("x".repeat(65), "name", 64)).status());
    }

    @Test
    void contextValidationMapsUnknownValuesToUnprocessable() {
        assertEquals(ConnectionContext.ORDER, MessagingInputs.context("order"));
        ApiException error = assertThrows(ApiException.class, () -> MessagingInputs.context("guess"));
        assertEquals(422, error.status());
        assertEquals("INVALID_CONTEXT", error.code());
    }

    @Test
    void nullFieldsAreRejectedWithUnprocessableStatus() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingInputs.text(null, "name", 64)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> MessagingInputs.context(null)).status());
    }

    @Test
    void newIdProducesParseableUuid() {
        String generated = MessagingInputs.newId();
        assertEquals(generated, MessagingInputs.uuid(generated, "id"));
        assertNotEquals(UNIT, generated);
    }
}
