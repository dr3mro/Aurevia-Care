package com.aurevia.smartclinic.staffmsg.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.events.DurableEventContract.PublishRequest;
import com.aurevia.smartclinic.events.DurableEventService;
import com.aurevia.smartclinic.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.smartclinic.staffmsg.notify.NotificationContract.NotificationDraft;
import com.aurevia.smartclinic.staffmsg.notify.NotificationContract.NotificationSettings;
import com.aurevia.smartclinic.tenant.TenantContext;

/** Covers the production @Bean wiring and its two narrow adapters. */
class NotificationConfigurationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "99999999-9999-9999-9999-999999999999";
    private static final String CONVERSATION = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void bindTenant() {
        TenantContext.set(new TenantContext(ORG, "nurse-a", "TEST", "corr-cfg"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void beanMethodsProduceAUsableService() {
        NotificationConfiguration configuration = new NotificationConfiguration();
        DurableEventService events = mock(DurableEventService.class);
        NotificationSettingsPort settings = configuration.notificationSettingsPort();
        NotificationNotifier notifier = configuration.notificationNotifier(events);
        NotificationService service = configuration.notificationService(
                Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC), settings, notifier);
        assertNotNull(service);
        assertNotNull(settings);
        assertNotNull(notifier);
    }

    @Test
    void processLocalPortKeepsSettingsRepliesAndMutesTenantScoped() {
        NotificationSettingsPort port = new NotificationConfiguration().notificationSettingsPort();
        port.saveSettings(new NotificationSettings(ORG, "nurse-a", Set.of(CONVERSATION), null, true, "delegate-a"));

        NotificationSettings found = port.findSettings(ORG, "nurse-a").orElseThrow();
        assertEquals(ORG, found.organizationId());
        assertTrue(port.findSettings(ORG, "nurse-b").isEmpty());
        assertTrue(port.isConversationMuted(ORG, CONVERSATION, "nurse-a"));
        assertFalse(port.isConversationMuted(ORG, CONVERSATION, "nurse-b"),
                "a subject with no stored preferences has no muted conversations");
        assertFalse(port.isConversationMuted(ORG, "33333333-3333-3333-3333-333333333333", "nurse-a"));
        assertEquals(403, assertThrows(ApiException.class,
                () -> port.isConversationMuted(OTHER_ORG, CONVERSATION, "nurse-a")).status());

        AutoReplyReceipt receipt = AutoReplyReceipt.of(
                ORG, CONVERSATION, "nurse-a", "doctor-a", LocalDate.of(2026, 11, 23), "delegate-a");
        assertTrue(port.tryRecordAutoReply(receipt), "first sender/day wins");
        assertFalse(port.tryRecordAutoReply(receipt), "a repeat is deduplicated");
    }

    @Test
    void processLocalPortFailsClosedForAnotherTenantOrNoContext() {
        NotificationSettingsPort port = new NotificationConfiguration().notificationSettingsPort();
        ApiException wrongTenant = assertThrows(ApiException.class,
                () -> port.findSettings(OTHER_ORG, "nurse-a"));
        assertEquals(403, wrongTenant.status());
        assertEquals("CROSS_TENANT", wrongTenant.code());

        TenantContext.clear();
        assertEquals(403, assertThrows(ApiException.class,
                () -> port.findSettings(ORG, "nurse-a")).status());
    }

    @Test
    void outboxNotifierPublishesOnlyTheFixedDigest() {
        DurableEventService events = mock(DurableEventService.class);
        NotificationNotifier notifier = new NotificationConfiguration().notificationNotifier(events);

        NotificationDraft draft = NotificationDraft.of(ORG, CONVERSATION, "nurse-b", false);
        notifier.dispatch(draft);

        ArgumentCaptor<PublishRequest> capture = ArgumentCaptor.forClass(PublishRequest.class);
        verify(events).publish(capture.capture());
        PublishRequest request = capture.getValue();
        assertEquals(draft.messageId(), request.aggregateId(), "the opaque message id is the aggregate");
        assertEquals("staff-notification", request.namespace());
        assertEquals("staff-new-message", request.eventType());
        assertFalse(request.payloadDigest().isBlank());
        assertTrue(request.idempotencyKey().contains(ORG));
        assertTrue(request.idempotencyKey().contains(draft.messageId()));
    }

    @Test
    void distinctMessagesAndUrgentVariantsPublishDistinctOutboxEvents() {
        DurableEventService events = mock(DurableEventService.class);
        NotificationNotifier notifier = new NotificationConfiguration().notificationNotifier(events);

        NotificationDraft plain = NotificationDraft.of(ORG, CONVERSATION, "nurse-b", false);
        NotificationDraft anotherPlain = NotificationDraft.of(ORG, CONVERSATION, "nurse-b", false);
        NotificationDraft urgent = NotificationDraft.of(ORG, CONVERSATION, "nurse-b", true);
        notifier.dispatch(plain);
        notifier.dispatch(anotherPlain);
        notifier.dispatch(urgent);

        org.mockito.ArgumentCaptor<PublishRequest> capture = org.mockito.ArgumentCaptor.forClass(PublishRequest.class);
        verify(events, org.mockito.Mockito.times(3)).publish(capture.capture());
        java.util.List<PublishRequest> published = capture.getAllValues();
        assertEquals(3, published.size());
        assertEquals(plain.messageId(), published.get(0).aggregateId());
        assertEquals(anotherPlain.messageId(), published.get(1).aggregateId());
        assertEquals(urgent.messageId(), published.get(2).aggregateId());
        assertEquals(published.get(0).payloadDigest(), published.get(1).payloadDigest(),
                "identical fixed text has an identical digest");
        org.junit.jupiter.api.Assertions.assertNotEquals(published.get(0).payloadDigest(),
                published.get(2).payloadDigest(), "the urgent variant has a distinct fixed payload");
        org.junit.jupiter.api.Assertions.assertNotEquals(published.get(0).idempotencyKey(),
                published.get(1).idempotencyKey(), "two messages in one conversation are two events");
    }
}
