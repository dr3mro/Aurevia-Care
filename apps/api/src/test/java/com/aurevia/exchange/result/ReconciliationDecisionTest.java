package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ReconciliationReason;
import com.aurevia.exchange.result.ResultModel.ReconciliationStatus;
import com.aurevia.exchange.result.ResultModel.ResultIdentifiers;

/**
 * Every guard of the pure reconciliation-resolution rule.
 */
class ReconciliationDecisionTest {

    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private static ReconciliationEntry entry(ReconciliationStatus status) {
        return new ReconciliationEntry("cccccccc-cccc-cccc-cccc-ccccccccccc1",
                new ResultIdentifiers("33333333-3333-3333-3333-333333333333", "src", "acc", "dest", "order-1"),
                ReconciliationReason.CONFLICT, List.of("patientId"), "identifier mismatch", status, AT);
    }

    @Test
    void matchRecordsTheReasonWithoutAnOwner() {
        Resolution decision = ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                ReconciliationAction.MATCH, " identifiers verified ", null, AT);
        assertEquals(ReconciliationAction.MATCH, decision.action());
        assertEquals("identifiers verified", decision.reason());
        assertNull(decision.assignedOwner());
        assertEquals(AT, decision.resolvedAt());
    }

    @Test
    void rejectRecordsTheReasonAndAllowsABlankOwnerField() {
        Resolution decision = ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                ReconciliationAction.REJECT, "duplicate finding", "   ", AT);
        assertEquals(ReconciliationAction.REJECT, decision.action());
        assertEquals("duplicate finding", decision.reason());
        assertNull(decision.assignedOwner());
    }

    @Test
    void reassignRecordsBothTheReasonAndTheNewOwner() {
        Resolution decision = ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                ReconciliationAction.REASSIGN, "owner on leave", " owner-b ", AT);
        assertEquals("owner-b", decision.assignedOwner());
        assertEquals("owner on leave", decision.reason());
    }

    @Test
    void aResolvedEntryCannotBeResolvedAgain() {
        ApiException error = assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.RESOLVED),
                        ReconciliationAction.MATCH, "verified", null, AT));
        assertEquals(409, error.status());
        assertEquals("ENTRY_RESOLVED", error.code());
    }

    @Test
    void aReasonIsRequired() {
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.MATCH, null, null, AT)).code());
        assertEquals("REASON_REQUIRED", assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.MATCH, "  ", null, AT)).code());
    }

    @Test
    void anOversizedReasonIsRejected() {
        String tooLong = "r".repeat(129);
        assertEquals(422, assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.MATCH, tooLong, null, AT)).status());
    }

    @Test
    void aReassignmentRequiresAndBoundsTheNewOwner() {
        assertEquals("OWNER_REQUIRED", assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.REASSIGN, "hand over", null, AT)).code());
        assertEquals("OWNER_REQUIRED", assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.REASSIGN, "hand over", " ", AT)).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.REASSIGN, "hand over", "o".repeat(129), AT)).status());
    }

    @Test
    void aNonReassignmentCannotNameANewOwner() {
        ApiException error = assertThrows(ApiException.class,
                () -> ReconciliationDecision.decide(entry(ReconciliationStatus.OPEN),
                        ReconciliationAction.REJECT, "duplicate", "owner-b", AT));
        assertEquals(422, error.status());
        assertEquals("OWNER_NOT_ALLOWED", error.code());
    }
}
