package com.aurevia.staffmsg.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.notify.NotificationContract.DndWindow;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationDraft;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationSettings;
import com.aurevia.staffmsg.notify.NotificationContract.SuppressionReason;
import com.aurevia.staffmsg.notify.NotificationContract.UrgentNotice;

/** Value-level checks for the content-free notification contract (MSG-06, ARC-07). */
class NotificationContractTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CONVERSATION = "22222222-2222-2222-2222-222222222222";

    @Test
    void fullDayWindowCoversEveryHour() {
        DndWindow window = DndWindow.between(LocalTime.of(9, 0), LocalTime.of(9, 0));
        assertTrue(window.contains(LocalTime.of(3, 0)));
        assertTrue(window.contains(LocalTime.of(12, 0)));
    }

    @Test
    void windowBoundsAreRequired() {
        assertThrows(NullPointerException.class,
                () -> DndWindow.between(null, LocalTime.of(17, 0)));
        assertThrows(NullPointerException.class,
                () -> DndWindow.between(LocalTime.of(9, 0), null));
    }

    @Test
    void sameDayWindowIsInsideBetweenItsBounds() {
        DndWindow window = DndWindow.between(LocalTime.of(9, 0), LocalTime.of(17, 0));
        assertTrue(window.contains(LocalTime.of(12, 0)), "mid window is quiet");
        assertTrue(window.contains(LocalTime.of(9, 0)), "start is inclusive");
        assertFalse(window.contains(LocalTime.of(17, 0)), "end is exclusive");
        assertFalse(window.contains(LocalTime.of(8, 0)), "before window is not quiet");
    }

    @Test
    void windowCrossingMidnightCoversBothSides() {
        DndWindow window = DndWindow.between(LocalTime.of(22, 0), LocalTime.of(6, 0));
        assertTrue(window.contains(LocalTime.of(23, 0)), "late evening is quiet");
        assertTrue(window.contains(LocalTime.of(2, 0)), "after midnight is quiet");
        assertFalse(window.contains(LocalTime.of(12, 0)), "midday is not quiet");
        assertFalse(window.contains(LocalTime.of(6, 0)), "end is exclusive across midnight");
    }

    @Test
    void settingsFreezeMutedConversationIdsAndDefaultToEmpty() {
        Set<String> input = new HashSet<>();
        input.add(CONVERSATION);
        NotificationSettings settings = new NotificationSettings(ORG, "nurse-a", input, null, false, null);
        input.add("33333333-3333-3333-3333-333333333333");
        assertEquals(Set.of(CONVERSATION), settings.mutedConversationIds());

        NotificationSettings omitted = new NotificationSettings(ORG, "nurse-a", null, null, false, null);
        assertTrue(omitted.mutedConversationIds().isEmpty(), "an omitted mute list becomes an empty frozen set");

        NotificationSettings defaults = NotificationSettings.defaults(ORG, "nurse-a");
        assertTrue(defaults.mutedConversationIds().isEmpty());
        assertNull(defaults.dndWindow());
        assertFalse(defaults.outOfOffice());
    }

    @Test
    void genericDraftCarriesOnlyFixedTextAndOpaqueIds() {
        NotificationDraft draft = NotificationDraft.of(ORG, CONVERSATION, "nurse-b", false);
        assertEquals(NotificationContract.GENERIC_TEXT, draft.text());
        assertEquals("You have a new message", draft.text());
        assertFalse(draft.text().contains("nurse-b"));
        assertFalse(draft.text().contains("subject"));
        assertFalse(draft.messageId().isBlank());
        assertFalse(draft.messageId().equals(CONVERSATION), "each message gets a fresh opaque id");
        assertEquals(CONVERSATION, draft.conversationId());
        assertEquals(ORG, draft.organizationId());
        assertEquals("nurse-b", draft.recipientSubject());
        assertFalse(draft.urgent());
        assertNull(draft.notice());
    }

    @Test
    void urgentDraftKeepsGenericTextAndAddsFixedNotice() {
        NotificationDraft draft = NotificationDraft.of(ORG, CONVERSATION, "nurse-b", true);
        assertEquals(NotificationContract.GENERIC_TEXT, draft.text());
        assertTrue(draft.urgent());
        assertEquals(NotificationContract.URGENT_NOTICE, draft.notice().text());
        assertTrue(draft.notice().text().contains("not for emergencies"));
        assertTrue(draft.notice().text().contains("facility emergency procedure"));
    }

    @Test
    void blankConversationIdIsRejectedAtTheContractBoundary() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> NotificationDraft.of(ORG, "  ", "nurse-b", false)).status());
    }

    @Test
    void autoReplyReceiptCarriesTheDedupeKeyAndDelegate() {
        var receipt = com.aurevia.staffmsg.notify.NotificationContract.AutoReplyReceipt.of(
                ORG, CONVERSATION, "nurse-a", "doctor-a", LocalDate.of(2026, 11, 23), "delegate-a");
        assertEquals(ORG, receipt.organizationId());
        assertEquals(CONVERSATION, receipt.conversationId());
        assertEquals("nurse-a", receipt.recipientSubject());
        assertEquals("doctor-a", receipt.senderSubject());
        assertEquals(LocalDate.of(2026, 11, 23), receipt.utcDay());
        assertEquals("delegate-a", receipt.delegateSubject());
    }

    @Test
    void suppressionReasonsAndUrgentNoticeAreFixedValues() {
        assertEquals(3, SuppressionReason.values().length);
        assertEquals(SuppressionReason.MUTED, SuppressionReason.valueOf("MUTED"));
        assertEquals(NotificationContract.URGENT_NOTICE, UrgentNotice.fixed().text());
    }
}
