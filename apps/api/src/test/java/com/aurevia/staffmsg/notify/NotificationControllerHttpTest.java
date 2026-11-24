package com.aurevia.staffmsg.notify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationDraft;
import com.aurevia.staffmsg.notify.NotificationContract.SettingsCommand;
import com.aurevia.tenant.TenantContext;

/**
 * HTTP-level evidence for the notification kernel through a test-only controller.
 *
 * <p>The controller is a nested static class exercised with standalone MockMvc, so it
 * never enters the Spring Boot component scan that OpenApiRouteParityTest inspects.</p>
 */
class NotificationControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CONVERSATION = "22222222-2222-2222-2222-222222222222";
    private static final String BASE = "/api/v1/notifications";

    private NotificationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC),
                new InMemoryNotificationSettingsPort(), draft -> { });
        mvc = MockMvcBuilders.standaloneSetup(new TestNotificationController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
        TenantContext.set(new TenantContext(ORG, "nurse-a", "TEST", "corr-http"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void draftEndpointReturnsOnlyTheFixedContentFreeText() throws Exception {
        mvc.perform(get(BASE + "/draft")
                        .param("conversationId", CONVERSATION)
                        .param("urgent", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(true))
                .andExpect(jsonPath("$.text").value("You have a new message"))
                .andExpect(jsonPath("$.conversationId").value(CONVERSATION))
                .andExpect(jsonPath("$.notice").value(""));
    }

    @Test
    void mutedConversationHasNoDraftThroughHttp() throws Exception {
        service.saveSettings(new SettingsCommand(Set.of(CONVERSATION), null, false, null));

        mvc.perform(get(BASE + "/draft").param("conversationId", CONVERSATION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispatched").value(false))
                .andExpect(jsonPath("$.reason").value("MUTED"))
                .andExpect(jsonPath("$.text").doesNotExist());
    }

    @Test
    void urgentDraftCarriesTheFixedNoticeThroughHttp() throws Exception {
        mvc.perform(get(BASE + "/draft")
                        .param("conversationId", CONVERSATION)
                        .param("urgent", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("You have a new message"))
                .andExpect(jsonPath("$.notice").value(
                        org.hamcrest.Matchers.containsString("not for emergencies")));
    }

    @Test
    void autoReplyEndpointDedupesPerSenderPerDay() throws Exception {
        service.saveSettings(new SettingsCommand(Set.of(), null, true, "delegate-a"));

        mvc.perform(post(BASE + "/auto-reply")
                        .param("conversationId", CONVERSATION).param("sender", "doctor-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.delegate").value("delegate-a"));

        mvc.perform(post(BASE + "/auto-reply")
                        .param("conversationId", CONVERSATION).param("sender", "doctor-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(false));
    }

    @Test
    void aRequestWithoutTenantContextIsRejected() throws Exception {
        TenantContext.clear();
        mvc.perform(get(BASE + "/draft").param("conversationId", CONVERSATION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** Test-only adapter that exposes the kernel over HTTP without a production route. */
    @RestController
    static final class TestNotificationController {

        private final NotificationService service;

        TestNotificationController(NotificationService service) {
            this.service = service;
        }

        /** Return a content-free draft view, or the suppression reason. */
        @GetMapping(BASE + "/draft")
        Map<String, Object> draft(@RequestParam String conversationId,
                                  @RequestParam(defaultValue = "false") boolean urgent) {
            var outcome = service.notifyRecipient(conversationId, urgent);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dispatched", outcome.dispatched());
            body.put("reason", outcome.reason().name());
            NotificationDraft draft = outcome.draft();
            if (draft != null) {
                body.put("text", draft.text());
                body.put("conversationId", draft.conversationId());
                body.put("notice", draft.notice() == null ? "" : draft.notice().text());
            }
            return body;
        }

        /** Record an out-of-office reply and report whether it was the first for this sender and day. */
        @PostMapping(BASE + "/auto-reply")
        Map<String, Object> autoReply(@RequestParam String conversationId, @RequestParam String sender) {
            Map<String, Object> body = new LinkedHashMap<>();
            java.util.Optional<AutoReplyReceipt> receipt = service.sendOutOfOfficeReply(conversationId, sender);
            body.put("sent", receipt.isPresent());
            receipt.ifPresent(value -> body.put("delegate", value.delegateSubject()));
            return body;
        }
    }
}
