package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.ResolveRequest;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * Wire parsing and value contracts of the reconciliation resolution model.
 */
class ReconciliationModelTest {

    @Test
    void parsesEveryOperatorActionCaseInsensitively() {
        assertEquals(ReconciliationAction.MATCH, ReconciliationAction.from("match"));
        assertEquals(ReconciliationAction.REJECT, ReconciliationAction.from(" ReJeCt "));
        assertEquals(ReconciliationAction.REASSIGN, ReconciliationAction.from("REASSIGN"));
    }

    @Test
    void rejectsAbsentBlankAndUnknownActions() {
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                () -> ReconciliationAction.from(null)).code());
        assertEquals("INVALID_ACTION", assertThrows(ApiException.class,
                () -> ReconciliationAction.from("   ")).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ReconciliationAction.from("delete")).status());
    }

    @Test
    void rendersTheLowerCaseWireForm() {
        assertEquals("match", ReconciliationAction.MATCH.wire());
        assertEquals("reject", ReconciliationAction.REJECT.wire());
        assertEquals("reassign", ReconciliationAction.REASSIGN.wire());
    }

    @Test
    void carriesTheCommandAndResolutionValues() {
        ResolveRequest request = new ResolveRequest("reassign", "wrong owner", "owner-b");
        assertEquals("reassign", request.action());
        assertEquals("wrong owner", request.reason());
        assertEquals("owner-b", request.assignedOwner());

        Instant at = Instant.parse("2026-11-21T10:00:00Z");
        Resolution resolution = new Resolution("entry-1", ReconciliationAction.REASSIGN,
                "wrong owner", "owner-b", at);
        assertEquals("entry-1", resolution.entryId());
        assertEquals(ReconciliationAction.REASSIGN, resolution.action());
        assertEquals("wrong owner", resolution.reason());
        assertEquals("owner-b", resolution.assignedOwner());
        assertEquals(at, resolution.resolvedAt());

        Resolution withoutOwner = new Resolution("entry-2", ReconciliationAction.MATCH, "verified", null, at);
        assertNull(withoutOwner.assignedOwner());
    }
}
