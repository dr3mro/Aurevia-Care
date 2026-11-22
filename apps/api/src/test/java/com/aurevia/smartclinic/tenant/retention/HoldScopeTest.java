package com.aurevia.smartclinic.tenant.retention;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;

/**
 * Unit tests for the OPS-07 legal-hold scope rules.
 *
 * <p>Hold scopes are non-sensitive identifiers. An effective hold must cover an
 * exact record, a descendant, an ancestor (bidirectionally) or a whole tenant;
 * a lift is only authoritative when it names an actor and a reason.</p>
 */
class HoldScopeTest {

    private static final String ORGANIZATION = "22222222-2222-2222-2222-222222222222";
    private static final Instant PLACED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LIFTED_AT = Instant.parse("2026-02-01T00:00:00Z");

    private static LegalHold activeHold(String scope) {
        return new LegalHold("hold-active", ORGANIZATION, scope, "dispute", "privacy-owner",
                PLACED_AT, null, null, null);
    }

    private static LegalHold releasedHold(String scope, String liftedBy, String liftReason) {
        return new LegalHold("hold-released", ORGANIZATION, scope, "dispute", "privacy-owner",
                PLACED_AT, liftedBy, LIFTED_AT, liftReason);
    }

    @Test
    void activeHoldIsEffectiveAndFullyLiftedHoldIsNot() {
        assertTrue(HoldScope.effective(activeHold("chart-1")));
        assertFalse(HoldScope.effective(releasedHold("chart-1", "privacy-owner", "resolved")));
    }

    @Test
    void liftWithoutNamedActorStillCovers() {
        assertTrue(HoldScope.effective(releasedHold("chart-1", null, "resolved")));
    }

    @Test
    void liftWithoutReasonStillCovers() {
        assertTrue(HoldScope.effective(releasedHold("chart-1", "privacy-owner", null)));
    }

    @Test
    void liftWithBlankActorStillCovers() {
        assertTrue(HoldScope.effective(releasedHold("chart-1", "   ", "resolved")));
    }

    @Test
    void liftWithBlankReasonStillCovers() {
        assertTrue(HoldScope.effective(releasedHold("chart-1", "privacy-owner", "  ")));
    }

    @Test
    void scopeMatchesExactIdentifierDescendantAndTrailingSeparator() {
        assertTrue(HoldScope.covers("chart-1", "chart-1"));
        assertTrue(HoldScope.covers("chart", "chart/note-1"));
        assertTrue(HoldScope.covers("chart/", "chart/note-1"));
    }

    @Test
    void paddedScopeStillCoversAfterNormalisation() {
        assertTrue(HoldScope.covers("  chart-1  ", "chart-1"));
    }

    @Test
    void wildcardScopeCoversEveryTarget() {
        assertTrue(HoldScope.covers("*", "any-record"));
    }

    @Test
    void blankAndNullScopesMatchNothing() {
        assertFalse(HoldScope.covers(null, "chart-1"));
        assertFalse(HoldScope.covers("chart-1", null));
        assertFalse(HoldScope.covers("  ", "chart-1"));
        assertFalse(HoldScope.covers("chart-1", "  "));
    }

    @Test
    void unrelatedScopeDoesNotCover() {
        assertFalse(HoldScope.covers("chart-a", "chart-b"));
    }

    @Test
    void holdBlocksExactAndDescendantTargets() {
        List<LegalHold> holds = List.of(activeHold("chart-1"));
        assertTrue(HoldScope.blocks(holds, "chart-1"));
        assertTrue(HoldScope.blocks(holds, "chart-1/note-2"));
    }

    @Test
    void holdOnDescendantAlsoBlocksAnAncestorTarget() {
        assertTrue(HoldScope.blocks(List.of(activeHold("chart-1/note-2")), "chart-1"));
    }

    @Test
    void releasedHoldAndEmptyHoldListDoNotBlock() {
        assertFalse(HoldScope.blocks(List.of(releasedHold("chart-1", "privacy-owner", "resolved")), "chart-1"));
        assertFalse(HoldScope.blocks(List.of(), "chart-1"));
    }

    @Test
    void unrelatedHoldDoesNotBlockButLaterMatchingHoldDoes() {
        List<LegalHold> holds = List.of(activeHold("other-chart"), activeHold("chart-1"));

        assertTrue(HoldScope.blocks(holds, "chart-1"));
        assertFalse(HoldScope.blocks(List.of(activeHold("other-chart")), "chart-1"));
    }

    @Test
    void backupExpirationIsBlockedByBackupSetOrByScope() {
        assertTrue(HoldScope.blocksBackup(List.of(activeHold("backup-set-1")), "backup-set-1", "chart-1"));
        assertTrue(HoldScope.blocksBackup(List.of(activeHold("chart-1")), "backup-set-1", "chart-1"));
        assertFalse(HoldScope.blocksBackup(List.of(), "backup-set-1", "chart-1"));
    }
}
