package com.aurevia.staffmsg.instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.staffmsg.attachments.StaffAttachmentLimits;
import com.aurevia.staffmsg.attachments.StaffAttachmentPublicationService;
import com.aurevia.staffmsg.attachments.StaffAttachmentService;
import com.aurevia.staffmsg.instant.InstantMessagingContract.SendRequest;
import com.aurevia.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-03 acceptance for the real instant send path: a clean upload bound to a
 * conversation is published by the message send command and is downloadable by
 * an active participant only. Removed participants, strangers and other tenants
 * stay denied without leaving an audit fact in the attacking tenant.
 */
class InstantAttachmentPublicationHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SENDER = "nurse-a";
    private static final String RECIPIENT = "nurse-b";
    private static final String FORMER = "nurse-former";
    private static final String STRANGER = "nurse-c";
    private static final String OUTSIDER = "outsider-b";
    private static final String SCANNER = "scanner-a";
    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String MESSAGE = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryStaffAttachmentStore attachments;
    private InstantConversationStore store;
    private StaffAttachmentPublicationService publication;
    private MockMvc mvc;

    /** Mount the real attachment and instant controllers over synthetic stores. */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        attachments = new InMemoryStaffAttachmentStore();
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SENDER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(RECIPIENT)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(FORMER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(STRANGER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(OUTSIDER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(SCANNER)).thenReturn(List.of("security_scanner"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        StaffAttachmentService attachmentService = new StaffAttachmentService(attachments, clock,
                new StaffAttachmentLimits());

        store = mock(InstantConversationStore.class);
        when(store.activeStaff(anyString(), anyString())).thenReturn(true);
        when(store.facilityWithinScope(anyString(), anyString(), anyString())).thenReturn(true);
        when(store.conversation(ORG, CONVERSATION)).thenReturn(Optional.of(new StaffConversation(ORG, FACILITY,
                CONVERSATION, ConversationKind.DIRECT, "topic", SENDER, NOW)));
        when(store.participants(ORG, CONVERSATION)).thenReturn(List.of(
                participant(SENDER, ParticipantRole.OWNER, null),
                participant(RECIPIENT, ParticipantRole.MEMBER, null),
                participant(FORMER, ParticipantRole.MEMBER, NOW.minusSeconds(60))));
        when(store.messageByClientId(ORG, CONVERSATION, "instant-publish-1")).thenReturn(Optional.empty());
        when(store.messageByClientId(ORG, CONVERSATION, "instant-retry-1")).thenReturn(Optional.empty());
        when(store.sendMessage(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new StaffMessage(ORG, MESSAGE, CONVERSATION, 1L, SENDER, "owner", "handover",
                        NOW, null));
        publication = spy(new StaffAttachmentPublicationService(attachmentService, staff, transactions));
        InstantMessagingService service = new InstantMessagingService(store, clock, publication);

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(
                        new StaffAttachmentController(attachmentService, staff, transactions),
                        new InstantMessagingController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    private static StaffParticipant participant(String subject, ParticipantRole role, Instant leftAt) {
        return new StaffParticipant(ORG, CONVERSATION, subject, role, NOW, leftAt, false);
    }

    /** Clear the request-bound tenant after every standalone request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String upload() throws Exception {
        String body = mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(new MockMultipartFile("file", "handover.pdf", "application/pdf", PDF))
                        .param("draftId", CONVERSATION)
                        .header("X-Synthetic-Subject", SENDER).header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asText();
    }

    private void scan(String id, String verdict) throws Exception {
        mvc.perform(withHeaders(post("/api/v1/staff/attachments/{id}/scan", id), SCANNER, ORG)
                        .contentType("application/json")
                        .content("{\"verdict\":\"" + verdict + "\",\"detail\":\"synthetic detail\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions send(String key) throws Exception {
        return mvc.perform(withHeaders(post("/api/v1/staff/instant/{conversationId}/messages", CONVERSATION),
                        SENDER, ORG)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, CONVERSATION, "handover"))));
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

    /** A clean conversation-bound upload is published and downloadable by an active participant. */
    @Test
    void cleanInstantAttachmentIsPublishedAndDownloadableByActiveParticipant() throws Exception {
        String id = upload();
        String checksum = attachments.attachment(ORG, id).orElseThrow().checksum();
        scan(id, "clean");

        MvcResult published = send("instant-publish-1").andReturn();
        assertEquals(200, published.getResponse().getStatus());
        assertEquals(MESSAGE, mapper.readTree(published.getResponse().getContentAsString()).get("id").asText());
        assertEquals(MESSAGE, attachments.attachment(ORG, id).orElseThrow().messageId());
        verify(publication, times(1)).publish(ORG, SENDER, CONVERSATION, MESSAGE, Set.of(SENDER, RECIPIENT),
                List.of(id));
        assertEquals(1L, audits(AuditType.SENT, "success"));

        MvcResult allowed = download(id, RECIPIENT, ORG);
        assertEquals(200, allowed.getResponse().getStatus());
        assertEquals(checksum, allowed.getResponse().getHeader("X-Checksum"));
        assertEquals(new String(PDF, StandardCharsets.UTF_8), allowed.getResponse().getContentAsString());
        assertEquals(1L, audits(AuditType.DOWNLOADED, "success"));
    }

    /** A former participant and a stranger stay denied; the attacking tenant learns nothing. */
    @Test
    void formerParticipantStrangerAndCrossTenantDownloadsAreDenied() throws Exception {
        String id = upload();
        scan(id, "clean");
        send("instant-publish-1").andExpect(status().isOk());

        MvcResult former = download(id, FORMER, ORG);
        MvcResult stranger = download(id, STRANGER, ORG);
        assertEquals(403, former.getResponse().getStatus());
        assertEquals(403, stranger.getResponse().getStatus());
        assertFalse(former.getResponse().getHeaderNames().contains("X-Checksum"));
        assertEquals(2L, attachments.audits(ORG).stream()
                .filter(fact -> fact.type() == AuditType.ACCESS_DENIED
                        && "NOT_RECIPIENT".equals(fact.detail()))
                .count());

        MvcResult foreign = download(id, OUTSIDER, OTHER_ORG);
        MvcResult absent = download(UUID.randomUUID().toString(), OUTSIDER, OTHER_ORG);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals(absent.getResponse().getContentAsString().replaceAll("\"correlationId\":\"[^\"]*\"",
                        "\"correlationId\":\"normalised\""),
                foreign.getResponse().getContentAsString().replaceAll("\"correlationId\":\"[^\"]*\"",
                        "\"correlationId\":\"normalised\""));
        assertEquals(List.of(), attachments.audits(OTHER_ORG));
    }

    /** A retried key returns the first message and issues no second publication or grant. */
    @Test
    void sameKeyRetryPublishesOneMessageAndOneGrant() throws Exception {
        String id = upload();
        scan(id, "clean");
        when(store.messageByClientId(ORG, CONVERSATION, "instant-retry-1")).thenReturn(Optional.empty(),
                Optional.of(new StaffMessage(ORG, MESSAGE, CONVERSATION, 1L, SENDER, "owner", "handover",
                        NOW, null)));
        send("instant-retry-1").andExpect(status().isOk());
        send("instant-retry-1").andExpect(status().isOk());
        verify(store, times(1)).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(publication, times(1)).publish(ORG, SENDER, CONVERSATION, MESSAGE, Set.of(SENDER, RECIPIENT),
                List.of(id));
        assertEquals(1L, audits(AuditType.SENT, "success"));
    }

    /** A quarantined upload cannot be published by an instant send. */
    @Test
    void quarantinedInstantAttachmentIsNotPublishedOrGranted() throws Exception {
        String id = upload();
        scan(id, "failed");
        MvcResult result = send("instant-publish-1").andReturn();
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("QUARANTINED_FILE", mapper.readTree(result.getResponse().getContentAsString())
                .get("code").asText());
        verify(publication, times(1)).requirePublishable(ORG, SENDER, CONVERSATION);
        verify(publication, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertFalse(attachments.attachment(ORG, id).orElseThrow().sent());
        assertEquals(0L, audits(AuditType.SENT, "success"));
        MvcResult blocked = download(id, RECIPIENT, ORG);
        assertEquals(409, blocked.getResponse().getStatus());
        assertFalse(blocked.getResponse().getHeaderNames().contains("X-Checksum"));
    }
}
