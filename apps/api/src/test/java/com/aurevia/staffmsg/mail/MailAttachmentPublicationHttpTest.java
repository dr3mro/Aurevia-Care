package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.attachments.InMemoryStaffAttachmentStore;
import com.aurevia.staffmsg.attachments.StaffAttachmentController;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.staffmsg.attachments.StaffAttachmentLimits;
import com.aurevia.staffmsg.attachments.StaffAttachmentPublicationService;
import com.aurevia.staffmsg.attachments.StaffAttachmentService;
import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-03 acceptance for the real mail send path: a clean upload bound to a mail
 * draft is published by the send command and is downloadable only by the
 * recipient frozen on the published message. Refusals grant nothing and leave no
 * audit fact in an attacker tenant. Synthetic identifiers and payload only.
 */
class MailAttachmentPublicationHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SENDER = "nurse-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String SCANNER = "scanner-a";
    private static final String STRANGER = "nurse-c";
    private static final String OUTSIDER = "outsider-b";
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryStaffAttachmentStore attachments;
    private InMemoryMailStore mails;
    private StaffAttachmentPublicationService publication;
    private MockMvc mvc;

    /** Mount the real attachment and mail controllers over synthetic durable stores. */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        attachments = new InMemoryStaffAttachmentStore();
        mails = new InMemoryMailStore();
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SENDER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(SCANNER)).thenReturn(List.of("security_scanner"));
        when(staff.activeRoles(RECIPIENT)).thenReturn(List.of("clinician"));
        when(staff.activeRoles(STRANGER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(OUTSIDER)).thenReturn(List.of("nurse"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        StaffAttachmentService attachmentService = new StaffAttachmentService(attachments, clock,
                new StaffAttachmentLimits());
        RecipientDirectory directory = (organizationId, facilityId, role) ->
                "nurse".equals(role) ? List.of(RECIPIENT) : List.of();
        publication = spy(new StaffAttachmentPublicationService(attachmentService, staff, transactions));
        MailService mailService = new MailService(clock, directory, mails, publication);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(
                        new StaffAttachmentController(attachmentService, staff, transactions),
                        new MailController(mailService))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the request-bound tenant after every standalone request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "handover.pdf", "application/pdf", PDF);
    }

    private String compose() throws Exception {
        String body = mvc.perform(withHeaders(post("/api/v1/staff/mail/drafts"), SENDER, ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MailController.ComposeRequest(FACILITY,
                                "Handover", "body", List.of(Recipient.role("nurse")), List.of(), List.of(),
                                List.of()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private String upload(String draft) throws Exception {
        String body = mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(file()).param("draftId", draft)
                        .header("X-Synthetic-Subject", SENDER).header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private void scan(String id, String verdict) throws Exception {
        mvc.perform(withHeaders(post("/api/v1/staff/attachments/{id}/scan", id), SCANNER, ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"" + verdict + "\",\"detail\":\"synthetic detail\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions send(String draft, String key) throws Exception {
        return mvc.perform(withHeaders(post("/api/v1/staff/mail/drafts/{id}/send", draft), SENDER, ORG)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedRecipients\":[\"" + RECIPIENT + "\"]}"));
    }

    private MvcResult download(String id, String subject, String organization) throws Exception {
        return mvc.perform(get("/api/v1/staff/attachments/{id}/download", id)
                        .header("X-Synthetic-Subject", subject)
                        .header("X-Active-Organization", organization))
                .andReturn();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withHeaders(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String subject, String organization) {
        return request.header("X-Synthetic-Subject", subject).header("X-Active-Organization", organization);
    }

    private long audits(AuditType type, String outcome) {
        return attachments.audits(ORG).stream()
                .filter(fact -> fact.type() == type && fact.outcome().equals(outcome))
                .count();
    }

    /** A clean upload bound to a mail draft is published and downloadable by the resolved recipient. */
    @Test
    void cleanConfirmedMailAttachmentIsPublishedAndDownloadableByResolvedRecipient() throws Exception {
        String draft = compose();
        String id = upload(draft);
        String checksum = attachments.attachment(ORG, id).orElseThrow().checksum();
        scan(id, "clean");

        String body = send(draft, "mail-publish-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.message.resolvedRecipients[0]").value(RECIPIENT))
                .andReturn().getResponse().getContentAsString();
        String messageId = mapper.readTree(body).get("message").get("id").asText();
        assertTrue(attachments.attachment(ORG, id).orElseThrow().sent());
        assertEquals(messageId, attachments.attachment(ORG, id).orElseThrow().messageId());
        verify(publication, times(1)).publish(ORG, SENDER, draft, messageId, Set.of(RECIPIENT), List.of(id));
        assertEquals(1L, audits(AuditType.SENT, "success"));

        MvcResult allowed = download(id, RECIPIENT, ORG);
        assertEquals(200, allowed.getResponse().getStatus());
        assertEquals(checksum, allowed.getResponse().getHeader("X-Checksum"));
        assertEquals(new String(PDF, StandardCharsets.UTF_8), allowed.getResponse().getContentAsString());
        assertEquals(1L, audits(AuditType.DOWNLOADED, "success"));

        MvcResult refused = download(id, STRANGER, ORG);
        assertEquals(403, refused.getResponse().getStatus());
        assertEquals("NOT_RECIPIENT", mapper.readTree(refused.getResponse().getContentAsString())
                .get("code").asText());
        assertFalse(refused.getResponse().getHeaderNames().contains("X-Checksum"));
        assertEquals(1L, attachments.audits(ORG).stream()
                .filter(fact -> fact.type() == AuditType.ACCESS_DENIED
                        && "NOT_RECIPIENT".equals(fact.detail()))
                .count());
    }

    /** A quarantined file must block publication entirely: no message row, no grant. */
    @Test
    void quarantinedMailAttachmentIsNotPublishedOrGranted() throws Exception {
        String draft = compose();
        String id = upload(draft);
        scan(id, "failed");

        MvcResult result = send(draft, "mail-quarantine-1").andReturn();
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("QUARANTINED_FILE", mapper.readTree(result.getResponse().getContentAsString())
                .get("code").asText());
        assertEquals(0, mails.messages(ORG).size());
        verify(publication, times(1)).requirePublishable(ORG, SENDER, draft);
        verify(publication, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
        assertFalse(attachments.attachment(ORG, id).orElseThrow().sent());
        assertEquals(0L, audits(AuditType.SENT, "success"));

        MvcResult blocked = download(id, RECIPIENT, ORG);
        assertEquals(409, blocked.getResponse().getStatus());
        assertEquals("NOT_SENT", mapper.readTree(blocked.getResponse().getContentAsString())
                .get("code").asText());
        assertFalse(blocked.getResponse().getHeaderNames().contains("X-Checksum"));
    }

    /** A retried send key returns the first message and issues no second publication or grant. */
    @Test
    void sameKeyRetryPublishesOneMessageAndOneGrant() throws Exception {
        String draft = compose();
        String id = upload(draft);
        scan(id, "clean");
        String first = send(draft, "mail-retry-1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String messageId = mapper.readTree(first).get("message").get("id").asText();
        String second = send(draft, "mail-retry-1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(messageId, mapper.readTree(second).get("message").get("id").asText());
        verify(publication, times(1)).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
        assertEquals(1, mails.messages(ORG).size());
        assertEquals(1L, audits(AuditType.SENT, "success"));
    }

    /** A guessed foreign identifier is concealed and leaves no audit fact in the attacking tenant. */
    @Test
    void crossTenantDownloadNeverGrantsAccessAndLeavesNoForeignAudit() throws Exception {
        String draft = compose();
        String id = upload(draft);
        scan(id, "clean");
        send(draft, "mail-tenant-1").andExpect(status().isOk());

        MvcResult foreign = download(id, OUTSIDER, OTHER_ORG);
        MvcResult absent = download(UUID.randomUUID().toString(), OUTSIDER, OTHER_ORG);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals(absent.getResponse().getContentAsString().replaceAll("\"correlationId\":\"[^\"]*\"",
                        "\"correlationId\":\"normalised\""),
                foreign.getResponse().getContentAsString().replaceAll("\"correlationId\":\"[^\"]*\"",
                        "\"correlationId\":\"normalised\""));
        assertFalse(foreign.getResponse().getHeaderNames().contains("X-Checksum"));
        assertEquals(List.of(), attachments.audits(OTHER_ORG));
    }

    /** An unconfirmed mail send cannot publish attachments or grant downloads. */
    @Test
    void unconfirmedSendCannotPublishAttachments() throws Exception {
        String draft = compose();
        String id = upload(draft);
        scan(id, "clean");
        mvc.perform(withHeaders(post("/api/v1/staff/mail/drafts/{id}/send", draft), SENDER, ORG)
                        .header("Idempotency-Key", "mail-unconfirmed-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmedRecipients\":null}"))
                .andExpect(status().is(428));
        assertFalse(attachments.attachment(ORG, id).orElseThrow().sent());
        assertEquals(0L, audits(AuditType.SENT, "success"));
        assertEquals(0, mails.messages(ORG).size());
    }

    /** A sent attachment audit fact never carries a checksum, payload or object key. */
    @Test
    void sentAuditFactsCarryNoPayloadOrIntegrityValue() throws Exception {
        String draft = compose();
        String id = upload(draft);
        scan(id, "clean");
        send(draft, "mail-audit-1").andExpect(status().isOk());
        for (AttachmentAudit fact : attachments.audits(ORG)) {
            String detail = String.valueOf(fact.detail());
            assertFalse(detail.contains(new String(PDF, StandardCharsets.UTF_8)));
            assertFalse(detail.contains("objectKey"));
            assertFalse(detail.contains(attachments.attachment(ORG, id).orElseThrow().checksum()));
        }
    }
}
