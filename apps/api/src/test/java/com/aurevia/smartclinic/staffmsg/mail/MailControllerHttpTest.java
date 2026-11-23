package com.aurevia.smartclinic.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.mail.MailController.ComposeRequest;
import com.aurevia.smartclinic.staffmsg.mail.MailController.FlagRequest;
import com.aurevia.smartclinic.staffmsg.mail.MailController.FolderRequest;
import com.aurevia.smartclinic.staffmsg.mail.MailController.SendRequest;
import com.aurevia.smartclinic.staffmsg.mail.MailController.TextRequest;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.Recipient;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** HTTP entry point for staff mail: compose, confirm, send, reply, forward and folders. */
class MailControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String BASE = "/api/v1/staff/mail";

    private MockMvc mvc;
    private ObjectMapper mapper;
    private MailService service;
    private MailController controller;
    private Map<String, List<String>> holders;
    private List<String> queriedFacilities;

    @BeforeEach
    void setUp() {
        holders = new HashMap<>();
        queriedFacilities = new ArrayList<>();
        RecipientDirectory directory = (organizationId, facilityId, role) -> {
            queriedFacilities.add(facilityId);
            return holders.getOrDefault(role, List.of());
        };
        service = new MailService(Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC), directory,
                new InMemoryMailStore());
        controller = new MailController(service);
        mapper = new ObjectMapper();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void composeSaveAndSendThroughHttp() throws Exception {
        String id = idOf(compose("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)));

        mvc.perform(withHeaders(put(BASE + "/drafts/{id}", id), "nurse-a")
                        .header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComposeRequest(FACILITY, "Handover v2",
                                "edited", List.of(Recipient.person("nurse-b")), List.of(), List.of(), List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        send(id, "nurse-a", List.of("nurse-b"), "http-send-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.subject").value("Handover v2"))
                .andExpect(jsonPath("$.viewerFolder").value("sent"))
                .andExpect(jsonPath("$.message.resolvedRecipients[0]").value("nurse-b"));
    }

    @Test
    void staleVersionBlindCopyAndConfirmationErrorsSurfaceThroughHttp() throws Exception {
        String id = idOf(compose("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of()));

        mvc.perform(withHeaders(put(BASE + "/drafts/{id}", id), "nurse-a")
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComposeRequest(FACILITY, "stale", "body",
                                List.of(), List.of(), List.of(), List.of()))))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        compose("nurse-a", "Blind", List.of(Recipient.person("nurse-b")),
                List.of(), List.of("hidden"), List.of())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BLIND_COPY_NOT_ALLOWED"));

        mvc.perform(withHeaders(post(BASE + "/drafts/{id}/send", id), "nurse-a")
                        .header("Idempotency-Key", "http-no-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SendRequest(null))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("CONFIRMATION_REQUIRED"));

        mvc.perform(withHeaders(post(BASE + "/drafts/{id}/send", id), "nurse-a")
                        .header("Idempotency-Key", "http-wrong-confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SendRequest(List.of("wrong")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPIENT_CONFIRMATION_MISMATCH"));
    }

    @Test
    void replyAllAndForwardThroughHttp() throws Exception {
        String originalId = idOf(compose("nurse-a", "Handover",
                List.of(Recipient.person("nurse-b"), Recipient.person("doctor-a")),
                List.of(Recipient.person("nurse-c")), List.of(), List.of("doc-1")));
        String messageId = idOf(send(originalId, "nurse-a",
                List.of("nurse-b", "doctor-a", "nurse-c"), "http-original").andReturn().getResponse(), true);

        mvc.perform(withHeaders(post(BASE + "/messages/{id}/reply-all", messageId), "nurse-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new TextRequest("thanks"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Re: handover"))
                .andExpect(jsonPath("$.to.length()").value(3));

        mvc.perform(withHeaders(post(BASE + "/messages/{id}/reply", messageId), "nurse-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new TextRequest("yes"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to.length()").value(1));

        mvc.perform(withHeaders(post(BASE + "/messages/{id}/forward", messageId), "nurse-b")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComposeRequest(FACILITY, null, "review",
                                List.of(Recipient.person("nurse-d")), List.of(), List.of(), List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentDocumentIds[0]").value("doc-1"))
                .andExpect(jsonPath("$.subject").value("Fwd: handover"));
    }

    @Test
    void foldersFlagsThreadsAndMessageReadThroughHttp() throws Exception {
        String draftId = idOf(compose("nurse-a", "Handover", List.of(Recipient.person("nurse-b")),
                List.of(), List.of(), List.of()));
        String messageId = idOf(send(draftId, "nurse-a", List.of("nurse-b"), "http-folders")
                .andReturn().getResponse(), true);

        mvc.perform(withHeaders(get(BASE + "/folders/{folder}", "inbox"), "nurse-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message.id").value(messageId));
        mvc.perform(withHeaders(get(BASE + "/folders/{folder}", "sent"), "nurse-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message.id").value(messageId));

        mvc.perform(withHeaders(post(BASE + "/messages/{id}/move", messageId), "nurse-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FolderRequest("archive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerFolder").value("archive"));

        mvc.perform(withHeaders(post(BASE + "/messages/{id}/flag", messageId), "nurse-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FlagRequest("flagged", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.flags[0]").value("flagged"));

        mvc.perform(withHeaders(get(BASE + "/messages/{id}", messageId), "nurse-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.id").value(messageId));
        mvc.perform(withHeaders(get(BASE + "/threads"), "nurse-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subject").value("handover"))
                .andExpect(jsonPath("$[0].messages.length()").value(1));
    }

    @Test
    void roleRecipientExpandsAndIsolationAndPreconditionsHoldThroughHttp() throws Exception {
        holders.put("nurse", List.of("nurse-b", "nurse-c"));
        String draftId = idOf(compose("nurse-a", "Handover", List.of(Recipient.role("nurse")),
                List.of(), List.of(), List.of()));
        send(draftId, "nurse-a", List.of("nurse-b", "nurse-c"), "http-role")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.resolvedRecipients.length()").value(2));

        String messageId = service.listFolder(ORG, "nurse-a",
                com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.MailFolder.SENT)
                .get(0).message().id();
        mvc.perform(withHeaders(get(BASE + "/messages/{id}", messageId), "nurse-z"))
                .andExpect(status().isNotFound());

        mvc.perform(withHeaders(post(BASE + "/messages/{id}/move", messageId), "nurse-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new FolderRequest("spam"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FOLDER"));

        mvc.perform(withHeaders(post(BASE + "/drafts/{id}/send", draftId), "nurse-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SendRequest(List.of("nurse-b", "nurse-c")))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mvc.perform(withHeaders(put(BASE + "/drafts/{id}", draftId), "nurse-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ComposeRequest(FACILITY, "s", "b",
                                List.of(), List.of(), List.of(), List.of()))))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void sendRequiresReconfirmationWhenFacilityHoldersChange() throws Exception {
        holders.put("nurse", List.of("nurse-b", "nurse-c"));
        String draftId = idOf(compose("nurse-a", "Handover", List.of(Recipient.role("nurse")),
                List.of(), List.of(), List.of()));
        assertTrue(queriedFacilities.isEmpty(), "role holders resolve at send time, not at compose time");

        holders.put("nurse", List.of("nurse-b", "nurse-d"));
        send(draftId, "nurse-a", List.of("nurse-b", "nurse-c"), "http-reconfirm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPIENT_CONFIRMATION_MISMATCH"));
        assertEquals(List.of(FACILITY), queriedFacilities);
    }

    @Test
    void aRequestWithoutTenantContextIsRejected() {
        assertEquals(401, assertThrows(ApiException.class, controller::threads).status());
    }

    private ResultActions compose(String actor, String subject, List<Recipient> to, List<Recipient> cc,
                                  List<String> bcc, List<String> attachments) throws Exception {
        String body = mapper.writeValueAsString(new ComposeRequest(FACILITY, subject, "body",
                to, cc, bcc, attachments));
        return mvc.perform(withHeaders(post(BASE + "/drafts"), actor)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions send(String draftId, String actor, List<String> confirmed, String key)
            throws Exception {
        return mvc.perform(withHeaders(post(BASE + "/drafts/{id}/send", draftId), actor)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new SendRequest(confirmed))));
    }

    private String idOf(ResultActions result) throws Exception {
        return idOf(result.andReturn().getResponse(), false);
    }

    private String idOf(MockHttpServletResponse response, boolean sentMessage) throws Exception {
        var node = mapper.readTree(response.getContentAsString());
        return (sentMessage ? node.get("message") : node).get("id").asText();
    }

    private MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder request, String actor) {
        return request.header("X-Synthetic-Subject", actor).header("X-Active-Organization", ORG);
    }
}
