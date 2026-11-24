package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.messaging.attachments.AttachmentController;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.InMemoryAttachmentStore;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP-level proof of MSG-03 clause 5: a patient file is accessible only after
 * an independent clean verdict and explicit publication against an authorized
 * message.
 *
 * <p>The positive case drives upload and scan through the normal HTTP entry
 * points, publishes with the domain command (the patient controller exposes no
 * publish route) and asserts every field of the mediated handle returned by
 * {@code GET /api/v1/patient/files/{id}/download}. Each negative case asserts
 * both the status and the machine-readable code, so a weakened gate cannot pass
 * by returning a generic error. A successful download must also leave its
 * append-only fact in the ledger.</p>
 *
 * <p>Every request uses the synthetic-auth profile with tenant-local, synthetic
 * identifiers; no patient data, credential or token is present. The
 * service-level participant denial cannot be reached through this controller
 * because it derives the actor's patient binding from the requested attachment;
 * that limitation is recorded in the clause document rather than asserted here.</p>
 */
class FileAccessibleAfterCleanVerdictAuthorizedMessageTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PEER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-a";
    private static final String SCANNER = "scanner-a";
    private static final String AUDITOR = "auditor-a";
    private static final String CONNECTION = "connection-a";
    private static final String UNIT = "unit-a";
    private static final String PATIENT = "patient-1";
    private static final String MESSAGE = "message-1";
    private static final String FILE_NAME = "consent.pdf";
    private static final byte[] PDF = "%PDF-1.4 synthetic attachment".getBytes(StandardCharsets.UTF_8);
    private static final Instant CREATED = Instant.parse("2026-11-20T09:00:00Z");

    private InMemoryAttachmentStore store;
    private AttachmentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore();
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("patient"));
        when(staff.activeRoles(SCANNER)).thenReturn(List.of("security_scanner"));
        when(staff.activeRoles(AUDITOR)).thenReturn(List.of("privacy_auditor"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        service = new AttachmentService(store, Clock.fixed(CREATED.plusSeconds(3_600), ZoneOffset.UTC));
        AttachmentController controller = new AttachmentController(service, store, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        store.putGrant(new ConnectionGrant(ORG, CONNECTION, UNIT, PATIENT, true));
        // Version 2 makes a published fixture available for the stale-version refusal.
        store.putAttachment(published("published-1", 2));
        store.putAttachment(failed("failed-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void downloadAfterCleanVerdictAndExplicitPublishReturnsMediatedHandle() throws Exception {
        JsonNode uploaded = new ObjectMapper().readTree(upload());
        String id = uploaded.get("id").asString();
        scanClean(id, 1);
        // Upload is version 1, the clean verdict version 2 and explicit publication version 3.
        assertEquals(1, service.publish(Actor.patient(ORG, SUBJECT, PATIENT),
                new PublishRequest(List.of(id), MESSAGE, UNIT)).size());

        mvc.perform(download(id, SUBJECT, ORG, "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectKey").value(uploaded.get("objectKey").asString()))
                .andExpect(jsonPath("$.objectVersion").value(1))
                .andExpect(jsonPath("$.checksum").value(checksum()))
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF.length));

        assertTrue(store.events(ORG).stream()
                .anyMatch(fact -> fact.eventType() == EventType.DOWNLOADED && id.equals(fact.attachmentId())),
                "a mediated download must be recorded in the append-only fact ledger");
    }

    @Test
    void downloadIsRefusedForQuarantinedFileBeforeCleanVerdict() throws Exception {
        String id = new ObjectMapper().readTree(upload()).get("id").asString();

        mvc.perform(download(id, SUBJECT, ORG, "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));
    }

    @Test
    void downloadIsRefusedForCleanButUnpublishedFile() throws Exception {
        String id = new ObjectMapper().readTree(upload()).get("id").asString();
        scanClean(id, 1);

        mvc.perform(download(id, SUBJECT, ORG, "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));
    }

    @Test
    void downloadIsRefusedForAnotherTenant() throws Exception {
        mvc.perform(download("published-1", SUBJECT, PEER_ORG, "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void downloadIsRefusedWithStaleVersion() throws Exception {
        mvc.perform(download("published-1", SUBJECT, ORG, "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    void downloadIsRefusedForRoleWithoutDownloadPermission() throws Exception {
        mvc.perform(download("published-1", AUDITOR, ORG, "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void downloadIsRefusedForScannerThatOnlyRecordsVerdicts() throws Exception {
        mvc.perform(download("published-1", SCANNER, ORG, "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void downloadIsRefusedForFileWithFailedVerdict() throws Exception {
        mvc.perform(download("failed-1", SUBJECT, ORG, "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));
    }

    private MockHttpServletRequestBuilder download(String id, String subject, String organization, String ifMatch) {
        return get("/api/v1/patient/files/{id}/download", id)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("If-Match", ifMatch);
    }

    private String upload() throws Exception {
        return mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(new MockMultipartFile("file", FILE_NAME, "application/pdf", PDF))
                        .param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("quarantined"))
                .andReturn().getResponse().getContentAsString();
    }

    private void scanClean(String id, int expectedVersion) throws Exception {
        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"synthetic clean verdict\"}")
                        .header("If-Match", Integer.toString(expectedVersion))
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("clean"));
    }

    private static Attachment published(String id, int version) {
        return new Attachment(ORG, id, version, SUBJECT, CONNECTION, UNIT, PATIENT, FILE_NAME,
                FileType.PDF, PDF.length, checksum(), "object-" + id, 1,
                ScanVerdict.CLEAN, AttachmentState.PUBLISHED, MESSAGE, UNIT, CREATED);
    }

    private static Attachment failed(String id) {
        return new Attachment(ORG, id, 2, SUBJECT, CONNECTION, UNIT, PATIENT, FILE_NAME,
                FileType.PDF, PDF.length, checksum(), "object-" + id, 1,
                ScanVerdict.FAILED, AttachmentState.FAILED, null, null, CREATED);
    }

    private static String checksum() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
