package com.aurevia.staffmsg.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.staffmsg.notify.NotificationContract.DndWindow;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationDraft;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationOutcome;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationSettings;
import com.aurevia.staffmsg.notify.NotificationContract.SettingsCommand;
import com.aurevia.staffmsg.notify.NotificationContract.SuppressionReason;
import com.aurevia.tenant.TenantContext;

/** Decision tests for mute, do-not-disturb, urgent and out-of-office behavior. */
class NotificationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "99999999-9999-9999-9999-999999999999";
    private static final String CONVERSATION = "22222222-2222-2222-2222-222222222222";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    private InMemoryNotificationSettingsPort port;
    private RecordingNotifier notifier;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        port = new InMemoryNotificationSettingsPort();
        notifier = new RecordingNotifier();
        service = new NotificationService(Clock.fixed(NOW, ZoneOffset.UTC), port, notifier);
        bind(ORG, "nurse-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void mutedConversationSuppressesDraftWithoutNotifyingOrRecordingAnything() {
        service.saveSettings(new SettingsCommand(Set.of(CONVERSATION), null, false, null));

        NotificationOutcome outcome = service.notifyRecipient(CONVERSATION, false);

        assertFalse(outcome.dispatched());
        assertEquals(SuppressionReason.MUTED, outcome.reason());
        assertTrue(notifier.drafts.isEmpty(), "suppressed messages never reach the notifier");
        assertTrue(port.recordedReplies().isEmpty(), "suppression never writes a reply ledger row");
    }

    @Test
    void dndInsideWindowSuppressesAndOutsideWindowDispatches() {
        service.saveSettings(new SettingsCommand(Set.of(),
                DndWindow.between(LocalTime.of(9, 0), LocalTime.of(17, 0)), false, null));
        assertEquals(SuppressionReason.DO_NOT_DISTURB, service.notifyRecipient(CONVERSATION, false).reason());
        assertTrue(notifier.drafts.isEmpty());

        service.saveSettings(new SettingsCommand(Set.of(),
                DndWindow.between(LocalTime.of(12, 0), LocalTime.of(13, 0)), false, null));
        NotificationOutcome outside = service.notifyRecipient(CONVERSATION, false);
        assertTrue(outside.dispatched());
        assertEquals(SuppressionReason.NONE, outside.reason());
        assertEquals(1, notifier.drafts.size());
    }

    @Test
    void dndWindowCrossingMidnightSuppressesBothEveningAndEarlyMorning() {
        service.saveSettings(new SettingsCommand(Set.of(),
                DndWindow.between(LocalTime.of(22, 0), LocalTime.of(6, 0)), false, null));

        NotificationService evening = new NotificationService(
                Clock.fixed(Instant.parse("2026-11-23T23:00:00Z"), ZoneOffset.UTC), port, notifier);
        assertEquals(SuppressionReason.DO_NOT_DISTURB, evening.notifyRecipient(CONVERSATION, false).reason());

        NotificationService morning = new NotificationService(
                Clock.fixed(Instant.parse("2026-11-24T02:00:00Z"), ZoneOffset.UTC), port, notifier);
        assertEquals(SuppressionReason.DO_NOT_DISTURB, morning.notifyRecipient(CONVERSATION, false).reason());
        assertTrue(notifier.drafts.isEmpty());
    }

    @Test
    void noSettingsUsesQuietDefaultsAndDispatchesGenericDraft() {
        NotificationSettings defaults = service.settings();
        assertEquals(ORG, defaults.organizationId());
        assertTrue(defaults.mutedConversationIds().isEmpty());

        NotificationOutcome outcome = service.notifyRecipient(CONVERSATION, false);
        assertTrue(outcome.dispatched());
        assertEquals(NotificationContract.GENERIC_TEXT, notifier.drafts.get(0).text());
    }

    @Test
    void settingsReturnsStoredPreferencesForTheBoundTenant() {
        DndWindow window = DndWindow.between(LocalTime.of(9, 0), LocalTime.of(17, 0));
        service.saveSettings(new SettingsCommand(Set.of(CONVERSATION), window, true, "delegate-a"));

        NotificationSettings stored = service.settings();
        assertEquals(ORG, stored.organizationId());
        assertEquals("nurse-a", stored.subject());
        assertEquals(Set.of(CONVERSATION), stored.mutedConversationIds());
        assertEquals(window, stored.dndWindow());
        assertTrue(stored.outOfOffice());
        assertEquals("delegate-a", stored.delegateSubject());
    }

    @Test
    void urgentKeepsGenericTextAddsFixedNoticeAndNeverEscalates() {
        NotificationOutcome urgent = service.notifyRecipient(CONVERSATION, true);
        assertTrue(urgent.dispatched());
        NotificationDraft draft = notifier.drafts.get(0);
        assertEquals(NotificationContract.GENERIC_TEXT, draft.text());
        assertNotNull(draft.notice());
        assertEquals(NotificationContract.URGENT_NOTICE, draft.notice().text());

        service.notifyRecipient(CONVERSATION, false);
        assertEquals(2, notifier.drafts.size(), "one dispatch per request, no escalation channel");
        assertNull(notifier.drafts.get(1).notice());
        assertTrue(port.recordedReplies().isEmpty());
    }

    @Test
    void outOfOfficeRepliesOncePerSenderPerUtcDay() {
        service.saveSettings(new SettingsCommand(Set.of(), null, true, "delegate-a"));

        AutoReplyReceipt first = service.sendOutOfOfficeReply(CONVERSATION, "doctor-a").orElseThrow();
        assertEquals("delegate-a", first.delegateSubject());
        assertEquals("nurse-a", first.recipientSubject());
        assertEquals("doctor-a", first.senderSubject());

        assertTrue(service.sendOutOfOfficeReply(CONVERSATION, "doctor-a").isEmpty(),
                "the same sender is answered only once in a UTC day");
        assertTrue(service.sendOutOfOfficeReply(CONVERSATION, "doctor-b").isPresent(),
                "a different sender gets a fresh reply");

        NotificationService nextDay = new NotificationService(
                Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC), port, notifier);
        assertTrue(nextDay.sendOutOfOfficeReply(CONVERSATION, "doctor-a").isPresent(),
                "the next UTC day resets the dedupe window");
        assertEquals(3, port.recordedReplies().size());
    }

    @Test
    void outOfOfficeDisabledProducesNoReply() {
        assertTrue(service.sendOutOfOfficeReply(CONVERSATION, "doctor-a").isEmpty());
        assertTrue(port.recordedReplies().isEmpty());
    }

    @Test
    void outOfOfficeWithoutDelegateIsRejected() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.saveSettings(new SettingsCommand(Set.of(), null, true, "  ")));
        assertEquals(422, error.status());
        assertEquals("DELEGATE_REQUIRED", error.code());
    }

    @Test
    void anotherTenantsSettingsAreConcealed() {
        service.saveSettings(new SettingsCommand(Set.of(CONVERSATION), null, true, "delegate-a"));
        bind(OTHER_ORG, "nurse-a");

        NotificationSettings other = service.settings();
        assertEquals(OTHER_ORG, other.organizationId());
        assertTrue(other.mutedConversationIds().isEmpty());
        assertFalse(other.outOfOffice());
        assertTrue(service.notifyRecipient(CONVERSATION, false).dispatched());
        assertTrue(service.sendOutOfOfficeReply(CONVERSATION, "doctor-a").isEmpty());
    }

    @Test
    void blankAndMissingIdentityAreRejectedFailClosed() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class, service::settings).status());
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.notifyRecipient(CONVERSATION, false)).status());
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.sendOutOfOfficeReply(CONVERSATION, "doctor-a")).status());

        bind(null, "nurse-a");
        assertEquals(401, assertThrows(ApiException.class, service::settings).status());
        bind("  ", "nurse-a");
        assertEquals(401, assertThrows(ApiException.class, service::settings).status());
        bind(ORG, "  ");
        assertEquals(422, assertThrows(ApiException.class, service::settings).status());
    }

    @Test
    void invalidCommandsAndSubjectsAreRejected() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.saveSettings(null)).status());
        NotificationSettings saved = service.saveSettings(new SettingsCommand(null, null, false, null));
        assertTrue(saved.mutedConversationIds().isEmpty());
        assertNull(saved.dndWindow());

        assertEquals(422, assertThrows(ApiException.class,
                () -> service.notifyRecipient("  ", false)).status());
        service.saveSettings(new SettingsCommand(Set.of(), null, true, "delegate-a"));
        ApiException senderError = assertThrows(ApiException.class,
                () -> service.sendOutOfOfficeReply(CONVERSATION, " "));
        assertEquals(422, senderError.status());
        assertEquals("INVALID_SENDER", senderError.code());
    }

    private static void bind(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, "TEST", "corr-1"));
    }

    /** Captures the identifier-free drafts the service asks the notifier to publish. */
    private static final class RecordingNotifier implements NotificationNotifier {
        private final List<NotificationDraft> drafts = new ArrayList<>();

        @Override
        public void dispatch(NotificationDraft draft) {
            drafts.add(draft);
        }
    }
}
