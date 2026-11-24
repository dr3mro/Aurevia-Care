package com.aurevia.messaging.attachments;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/** MSG-03 HTTP entry point: protected multipart upload, scan, publish and mediated retrieval. */
class AttachmentControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "patient-a";
    private static final String SCANNER = "scanner-a";
    private static final String CONNECTION = "connection-a";
    private static final String UNIT = "unit-a";
    private static final String PATIENT = "patient-1";
    private static final String FILE_NAME = "consent.pdf";
    private static final byte[] PDF = "%PDF-1.4 synthetic attachment".getBytes(StandardCharsets.UTF_8);

    private AttachmentController controller;
    private InMemoryAttachmentStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        store = new InMemoryAttachmentStore();
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("patient"));
        when(staff.activeRoles(SCANNER)).thenReturn(List.of("security_scanner"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new AttachmentController(new AttachmentService(store, clock), store, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        store.putGrant(new ConnectionGrant(ORG, CONNECTION, UNIT, PATIENT, true));
        store.putAttachment(new com.aurevia.messaging.attachments.AttachmentContract.Attachment(
                ORG, "published-1", 1, SUBJECT, CONNECTION, UNIT, PATIENT, FILE_NAME,
                com.aurevia.messaging.attachments.AttachmentContract.FileType.PDF,
                PDF.length, checksumValue(), "object-published-1", 1,
                com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict.CLEAN,
                com.aurevia.messaging.attachments.AttachmentContract.AttachmentState.PUBLISHED,
                "message-1", UNIT, Instant.parse("2026-11-20T09:00:00Z")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", FILE_NAME, "application/pdf", PDF);
    }

    private static String checksum() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(PDF);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String checksumValue() {
        return checksum();
    }

    @Test
    void uploadsThroughMultipartScansAndDownloads() throws Exception {
        String response = mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(file())
                        .param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("quarantined"))
                .andExpect(jsonPath("$.unitId").value(UNIT))
                .andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(response).get("id").asString();

        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("clean"));

        mvc.perform(get("/api/v1/patient/files/{id}/download", "published-1")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").value(checksum()))
                .andExpect(jsonPath("$.contentType").value("application/pdf"));
    }

    @Test
    void multipartUploadWithoutLoginReturns401() throws Exception {
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(file()).param("connectionId", CONNECTION)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownConnectionReturns404() throws Exception {
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(file()).param("connectionId", "unknown")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        assertThrows(ApiException.class, () -> controller.download("file-1", "1"));
    }

    @Test
    void emptyPayloadReturns422() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", FILE_NAME, "application/pdf", new byte[0]);
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(empty).param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));
    }

    @Test
    void unknownAttachmentScanReturns404() throws Exception {
        mvc.perform(post("/api/v1/patient/files/{id}/scan", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unknownAttachmentDownloadReturns404() throws Exception {
        mvc.perform(get("/api/v1/patient/files/{id}/download", "missing")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unreadablePayloadReturns422() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("file", FILE_NAME, "application/pdf", PDF) {
            @Override
            public byte[] getBytes() throws java.io.IOException {
                throw new java.io.IOException("synthetic read failure");
            }
        };
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(broken).param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));
    }
}
