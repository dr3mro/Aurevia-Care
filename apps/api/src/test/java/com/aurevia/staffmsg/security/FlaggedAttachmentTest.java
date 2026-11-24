package com.aurevia.staffmsg.security;

import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.ORG_A;
import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.ORG_B;
import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.OUTSIDER;
import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.RECIPIENT;
import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.SCAN_FINDING;
import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.SCANNER;
import static com.aurevia.staffmsg.security.StaffMessagingSecurityFixture.SENDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.attachments.InMemoryStaffAttachmentStore;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.staffmsg.attachments.StaffAttachmentController;
import com.aurevia.staffmsg.attachments.StaffAttachmentLimits;
import com.aurevia.staffmsg.attachments.StaffAttachmentService;
import com.aurevia.tenant.TenantContext;

import tools.jackson.databind.ObjectMapper;

/**
 * Malicious-attachment and object-level attachment security through the real
 * staff-attachment controller (MSG-03, MSG-07).
 *
 * <p>A scanner-flagged PDF is quarantined, cannot be sent or downloaded and every
 * protected refusal writes an ACCESS_DENIED fact with no payload, checksum or
 * private object key. A guessed foreign identifier is concealed with the same 404
 * body as a random identifier and creates no audit fact in the attacker's tenant.</p>
 */
class FlaggedAttachmentTest {

    private static final String DRAFT = "conversation-1";
    private static final String OTHER_DRAFT = "conversation-2";
    private static final String MESSAGE = "message-1";

    private InMemoryStaffAttachmentStore store;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wire the attachment controller over the public in-memory store and fresh roles. */
    @BeforeEach
    void setUp() {
        store = new InMemoryStaffAttachmentStore();
        StaffRepository staff = mock(StaffRepository.class);
        Map<String, List<String>> roles = Map.of(
                SENDER, List.of("nurse"),
                StaffMessagingSecurityFixture.OTHER_SENDER, List.of("nurse"),
                OUTSIDER, List.of("nurse"),
                SCANNER, List.of("security_scanner"),
                RECIPIENT, List.of("clinician"),
                StaffMessagingSecurityFixture.GHOST, List.of());
        when(staff.activeRoles(any())).thenAnswer(call -> roles.getOrDefault(call.getArgument(0), List.of()));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        StaffAttachmentService service = new StaffAttachmentService(store,
                Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC),
                new StaffAttachmentLimits());
        mvc = StaffMessagingSecurityFixture.mvc(
                new StaffAttachmentController(service, staff, transactions));
    }

    /** Clear the thread-local bound by each standalone request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String upload(String org, String subject, String draft) throws Exception {
        String body = StaffMessagingSecurityFixture.body(mvc.perform(
                        multipart("/api/v1/staff/attachments/upload")
                                .file(StaffMessagingSecurityFixture.pdfFile()).param("draftId", draft)
                                .header("X-Synthetic-Subject", subject)
                                .header("X-Active-Organization", org))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scanState").value("pending"))
                .andReturn());
        return mapper.readTree(body).get("id").asText();
    }

    private MvcResult scan(String id, String verdict, String detail, String org, String subject) throws Exception {
        String content = "{\"verdict\":\"" + verdict + "\",\"detail\":\"" + detail + "\"}";
        return mvc.perform(post("/api/v1/staff/attachments/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON).content(content)
                        .header("X-Synthetic-Subject", subject)
                        .header("X-Active-Organization", org))
                .andReturn();
    }

    private MvcResult send(String org, String subject, String draft, String message,
                           List<String> ids, List<String> recipients, boolean confirm) throws Exception {
        String command = "{\"draftId\":\"" + draft + "\",\"messageId\":\"" + message
                + "\",\"attachmentIds\":" + quoted(ids) + ",\"recipients\":" + quoted(recipients)
                + ",\"confirm\":" + confirm + "}";
        return mvc.perform(post("/api/v1/staff/attachments/send")
                        .contentType(MediaType.APPLICATION_JSON).content(command)
                        .header("X-Synthetic-Subject", subject)
                        .header("X-Active-Organization", org))
                .andReturn();
    }

    private static String quoted(List<String> values) {
        return "[" + String.join(",", values.stream().map(value -> "\"" + value + "\"").toList()) + "]";
    }

    private MvcResult download(String id, String org, String subject) throws Exception {
        return mvc.perform(get("/api/v1/staff/attachments/{id}/download", id)
                        .header("X-Synthetic-Subject", subject)
                        .header("X-Active-Organization", org))
                .andReturn();
    }

    private void assertFact(String org, String id, AuditType type, String outcome, String detail) {
        assertTrue(store.audits(org).stream().anyMatch(fact -> fact.attachmentId().equals(id)
                        && fact.type() == type && fact.outcome().equals(outcome)
                        && Objects.equals(fact.detail(), detail)),
                "missing audit fact " + type + "/" + outcome + "/" + detail + " for " + id);
    }

    private void assertNoLeak(MvcResult result) throws Exception {
        String body = StaffMessagingSecurityFixture.body(result);
        StaffMessagingSecurityFixture.assertNoLeak(body, new String(StaffMessagingSecurityFixture.PDF,
                StandardCharsets.UTF_8));
        assertFalse(result.getResponse().getHeaderNames().contains("X-Object-Key"));
        assertFalse(result.getResponse().getHeaderNames().contains("X-Checksum"),
                "a refusal must not carry the original file's integrity value");
    }

    @Test
    void flaggedPdfIsQuarantinedAuditedAndCannotBeSentOrDownloaded() throws Exception {
        String id = upload(ORG_A, SENDER, DRAFT);
        assertFact(ORG_A, id, AuditType.UPLOADED, "success", null);

        MvcResult scanned = scan(id, "failed", SCAN_FINDING, ORG_A, SCANNER);
        assertEquals(200, scanned.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(scanned).contains("quarantined"));
        assertFact(ORG_A, id, AuditType.SCANNED_QUARANTINED, "success", SCAN_FINDING);

        MvcResult sent = send(ORG_A, SENDER, DRAFT, MESSAGE, List.of(id), List.of(RECIPIENT), true);
        assertEquals(409, sent.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(sent).contains("QUARANTINED_FILE"));
        assertTrue(StaffMessagingSecurityFixture.body(sent).contains(SCAN_FINDING));
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "conflict", "QUARANTINED_FILE");
        assertNoLeak(sent);

        MvcResult downloaded = download(id, ORG_A, SENDER);
        assertEquals(409, downloaded.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(downloaded).contains("NOT_SENT"));
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "conflict", "NOT_SENT");
        assertNoLeak(downloaded);
        assertFalse(new String(downloaded.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)
                .contains(new String(StaffMessagingSecurityFixture.PDF, StandardCharsets.UTF_8)));
    }

    @Test
    void scannerCannotSendAndSenderCannotScan() throws Exception {
        String id = upload(ORG_A, SENDER, DRAFT);
        MvcResult wrongScanner = scan(id, "clean", "ok", ORG_A, SENDER);
        assertEquals(403, wrongScanner.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(wrongScanner).contains("FORBIDDEN"));
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "denied", "STAFF_ATTACHMENT_SCAN");
        assertNoLeak(wrongScanner);

        MvcResult wrongSender = send(ORG_A, SCANNER, DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), true);
        assertEquals(403, wrongSender.getResponse().getStatus());
        assertFact(ORG_A, MESSAGE, AuditType.ACCESS_DENIED, "denied", "STAFF_ATTACHMENT_SEND");
        assertNoLeak(wrongSender);
    }

    @Test
    void cleanPdfIsSentAndDownloadedOnlyByARecipient() throws Exception {
        String id = upload(ORG_A, SENDER, DRAFT);
        assertEquals(200, scan(id, "clean", "ok", ORG_A, SCANNER).getResponse().getStatus());
        assertFact(ORG_A, id, AuditType.SCANNED_CLEAN, "success", "ok");

        MvcResult sent = send(ORG_A, SENDER, DRAFT, MESSAGE, List.of(id), List.of(RECIPIENT), true);
        assertEquals(200, sent.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(sent).contains("\"sent\":true"));
        assertFact(ORG_A, id, AuditType.SENT, "success", null);

        store.putRecipients(ORG_A, MESSAGE, Set.of(RECIPIENT));
        MvcResult allowed = download(id, ORG_A, RECIPIENT);
        assertEquals(200, allowed.getResponse().getStatus());
        assertEquals(new String(StaffMessagingSecurityFixture.PDF, StandardCharsets.UTF_8),
                allowed.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFact(ORG_A, id, AuditType.DOWNLOADED, "success", null);

        MvcResult refused = download(id, ORG_A, StaffMessagingSecurityFixture.OTHER_SENDER);
        assertEquals(403, refused.getResponse().getStatus());
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "denied", "NOT_RECIPIENT");
        assertNoLeak(refused);
        assertFalse(new String(refused.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)
                .contains(new String(StaffMessagingSecurityFixture.PDF, StandardCharsets.UTF_8)));
    }

    @Test
    void guessedCrossTenantAttachmentIdIsConcealedWithoutAnAuditFact() throws Exception {
        String id = upload(ORG_A, SENDER, DRAFT);
        String random = StaffMessagingSecurityFixture.randomId();

        MvcResult foreign = download(id, ORG_B, OUTSIDER);
        MvcResult absent = download(random, ORG_B, OUTSIDER);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals(StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(absent)),
                StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(foreign)));
        assertTrue(StaffMessagingSecurityFixture.body(foreign).contains("NOT_FOUND"));
        assertNoLeak(foreign);

        MvcResult foreignSend = send(ORG_B, OUTSIDER, DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), true);
        MvcResult absentSend = send(ORG_B, OUTSIDER, DRAFT, MESSAGE,
                List.of(random), List.of(RECIPIENT), true);
        assertEquals(404, foreignSend.getResponse().getStatus());
        assertEquals(StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(absentSend)),
                StaffMessagingSecurityFixture.normalise(StaffMessagingSecurityFixture.body(foreignSend)));
        assertNoLeak(foreignSend);
        assertEquals(List.of(), store.audits(ORG_B), "the attacked tenant must learn nothing from the probe");
    }

    @Test
    void invalidCommandsAreRefusedAndAudited() throws Exception {
        String id = upload(ORG_A, SENDER, DRAFT);
        assertEquals(200, scan(id, "clean", "ok", ORG_A, SCANNER).getResponse().getStatus());

        MvcResult unconfirmed = send(ORG_A, SENDER, DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), false);
        assertEquals(422, unconfirmed.getResponse().getStatus());
        assertFact(ORG_A, MESSAGE, AuditType.ACCESS_DENIED, "invalid", "CONFIRM_REQUIRED");
        assertNoLeak(unconfirmed);

        MvcResult empty = send(ORG_A, SENDER, DRAFT, MESSAGE, List.of(), List.of(RECIPIENT), true);
        assertEquals(422, empty.getResponse().getStatus());
        assertFact(ORG_A, MESSAGE, AuditType.ACCESS_DENIED, "invalid", "EMPTY_ATTACHMENTS");

        MvcResult duplicates = send(ORG_A, SENDER, DRAFT, MESSAGE,
                List.of(id, id), List.of(RECIPIENT), true);
        assertEquals(422, duplicates.getResponse().getStatus());
        assertFact(ORG_A, MESSAGE, AuditType.ACCESS_DENIED, "invalid", "DUPLICATE_ATTACHMENTS");
        assertNoLeak(duplicates);

        MvcResult noRecipients = send(ORG_A, SENDER, DRAFT, MESSAGE,
                List.of(id), List.of(), true);
        assertEquals(422, noRecipients.getResponse().getStatus());
        assertFact(ORG_A, MESSAGE, AuditType.ACCESS_DENIED, "invalid", "EMPTY_RECIPIENTS");
        assertNoLeak(noRecipients);

        MvcResult wrongDraft = send(ORG_A, SENDER, OTHER_DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), true);
        assertEquals(403, wrongDraft.getResponse().getStatus());
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "denied", "WRONG_DRAFT");
        assertNoLeak(wrongDraft);

        MvcResult wrongUploader = send(ORG_A, StaffMessagingSecurityFixture.OTHER_SENDER, DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), true);
        assertEquals(403, wrongUploader.getResponse().getStatus());
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "denied", "NOT_UPLOADER");
        assertNoLeak(wrongUploader);
    }

    @Test
    void alreadySentFileCannotBeSentTwice() throws Exception {
        String id = upload(ORG_A, SENDER, DRAFT);
        assertEquals(200, scan(id, "clean", "ok", ORG_A, SCANNER).getResponse().getStatus());
        assertEquals(200, send(ORG_A, SENDER, DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), true).getResponse().getStatus());

        MvcResult replay = send(ORG_A, SENDER, DRAFT, MESSAGE,
                List.of(id), List.of(RECIPIENT), true);
        assertEquals(409, replay.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(replay).contains("ALREADY_SENT"));
        assertFact(ORG_A, id, AuditType.ACCESS_DENIED, "conflict", "ALREADY_SENT");
        assertNoLeak(replay);
    }

    @Test
    void malformedSelectorAndMissingIdentityAreRefused() throws Exception {
        MvcResult malformed = download("x", ORG_A, SENDER);
        assertEquals(422, malformed.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(malformed).contains("INVALID_ID"));
        assertNoLeak(malformed);

        mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(StaffMessagingSecurityFixture.pdfFile()).param("draftId", DRAFT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(StaffMessagingSecurityFixture.pdfFile()).param("draftId", DRAFT)
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", "not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TENANT_SELECTION"));
        MvcResult ghost = mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(StaffMessagingSecurityFixture.pdfFile()).param("draftId", DRAFT)
                        .header("X-Synthetic-Subject", StaffMessagingSecurityFixture.GHOST)
                        .header("X-Active-Organization", ORG_A))
                .andReturn();
        assertEquals(403, ghost.getResponse().getStatus());
        assertTrue(StaffMessagingSecurityFixture.body(ghost).contains("FORBIDDEN"));
    }
}
