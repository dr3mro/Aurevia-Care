package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Locale;

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

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.attachments.AttachmentActions;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentController;
import com.aurevia.smartclinic.messaging.attachments.AttachmentInputs;
import com.aurevia.smartclinic.messaging.attachments.AttachmentService;
import com.aurevia.smartclinic.messaging.attachments.InMemoryAttachmentStore;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-03 clause 4 proof: an upload is integrity-, size- and type-validated into
 * private quarantine and stays unreachable until an independent clean scan and
 * explicit publication. A wrong role, a stale version, a failed verdict, bad
 * metadata or a foreign tenant must be refused. Synthetic fixtures only; no
 * patient data, payload bytes or credentials are used.
 */
class ValidateIntegritySizeTypeScanQuarantineTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-a";
    private static final String SCANNER = "scanner-a";
    private static final String CONNECTION = "connection-a";
    private static final String UNIT = "unit-a";
    private static final String PATIENT = "patient-1";
    private static final String FILE_NAME = "consent.pdf";
    private static final byte[] PDF = "%PDF-1.4 synthetic attachment".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private InMemoryAttachmentStore store;
    private AttachmentService service;
    private MockMvc mvc;

    /** Build the same MockMvc + in-memory fixture the neighbouring attachment HTTP tests use. */
    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore();
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of(AttachmentActions.PATIENT));
        when(staff.activeRoles(SCANNER)).thenReturn(List.of(AttachmentActions.SCANNER));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        AttachmentController controller = new AttachmentController(service, store, staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        store.putGrant(new ConnectionGrant(ORG, CONNECTION, UNIT, PATIENT, true));
    }

    /** Clear the thread-bound tenant after each request-driven test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Synthetic PDF payload with a valid leading signature for its declared type. */
    private static MockMultipartFile file() {
        return new MockMultipartFile("file", FILE_NAME, "application/pdf", PDF);
    }

    /** SHA-256 of the synthetic payload, lower-case hex as the adapter produces. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Craft a service-level upload command; the HTTP adapter derives these fields from real bytes. */
    private static UploadRequest request(String magic, String checksum, long size) {
        return new UploadRequest(CONNECTION, FILE_NAME, "application/pdf", magic, size, checksum);
    }

    /** Upload a valid PDF through the HTTP entry point and return its identifier. */
    private String uploadPdf() throws Exception {
        String body = mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(file())
                        .param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("id").asString();
    }

    /** Assert a command is refused with a specific HTTP status and stable code. */
    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    @Test
    void validPdfIsIntegritySizeTypeCheckedQuarantinedAndUnreachableUntilCleanScan() throws Exception {
        String body = mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(file())
                        .param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("quarantined"))
                .andExpect(jsonPath("$.verdict").value("pending"))
                .andExpect(jsonPath("$.fileType").value("application/pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF.length))
                .andExpect(jsonPath("$.checksum").value(sha256(PDF)))
                .andReturn().getResponse().getContentAsString();
        JsonNode uploaded = mapper.readTree(body);
        String id = uploaded.get("id").asString();
        assertEquals(UNIT, uploaded.get("unitId").asString());
        assertEquals(PATIENT, uploaded.get("patientId").asString());
        assertTrue(uploaded.get("objectKey").asString().startsWith("quarantine/" + ORG + "/"));

        // While still quarantined, the payload is already unreachable (before any scan).
        mvc.perform(get("/api/v1/patient/files/{id}/download", id)
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));

        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("clean"))
                .andExpect(jsonPath("$.verdict").value("clean"));

        // A clean verdict alone does not make the payload reachable: publication is also required.
        mvc.perform(get("/api/v1/patient/files/{id}/download", id)
                        .header("If-Match", "2")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));

        // Quarantine facts carry identifiers and outcomes only: no bytes, name, URL or secret.
        Actor patient = Actor.patient(ORG, SUBJECT, PATIENT);
        List<String> facts = service.events(patient, id).stream().map(Object::toString).toList();
        assertFalse(facts.isEmpty());
        for (String fact : facts) {
            assertFalse(fact.contains(FILE_NAME), "fact must not disclose the file name");
            assertFalse(fact.contains(sha256(PDF)), "fact must not disclose the payload checksum");
            assertFalse(fact.contains("%PDF"), "fact must not disclose payload bytes");
            assertFalse(fact.contains("http"), "fact must not disclose a URL");
        }
    }

    @Test
    void rejectsUploadWithWrongTypeMismatchedBytesOversizeOrBadChecksum() throws Exception {
        // An unsupported declared type is refused before quarantine state is created.
        MockMultipartFile text = new MockMultipartFile("file", "notes.txt", "text/plain", PDF);
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(text).param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CONTENT_TYPE"));

        // Leading bytes that disagree with the declared image type are refused.
        MockMultipartFile mismatched = new MockMultipartFile("file", "notes.jpg", "image/jpeg", PDF);
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(mismatched).param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONTENT_MISMATCH"));

        // A file extension that disagrees with the declared type is a distinct type guard.
        MockMultipartFile wrongExtension = new MockMultipartFile("file", "consent.png", "application/pdf", PDF);
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(wrongExtension).param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXTENSION_MISMATCH"));

        // A payload above the shared 10 MiB bound is refused by size.
        byte[] oversize = new byte[(int) AttachmentInputs.MAX_SIZE_BYTES + 1];
        System.arraycopy(PDF, 0, oversize, 0, PDF.length);
        MockMultipartFile big = new MockMultipartFile("file", FILE_NAME, "application/pdf", oversize);
        mvc.perform(multipart("/api/v1/patient/files/upload")
                        .file(big).param("connectionId", CONNECTION)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SIZE_BYTES"));

        // The HTTP adapter derives a valid magic prefix and checksum from the bytes, so
        // malformed metadata is proven at the service boundary with crafted commands.
        Actor patient = Actor.patient(ORG, SUBJECT, PATIENT);
        assertDenied(422, "INVALID_MAGIC_HEX",
                () -> service.upload(patient, request("2", sha256(PDF), PDF.length)));
        assertDenied(422, "INVALID_CHECKSUM",
                () -> service.upload(patient, request("25504446", "abc", PDF.length)));
    }

    @Test
    void refusesScanByWrongRoleOrStaleVersionAndFailedVerdictKeepsFileQuarantined() throws Exception {
        String id = uploadPdf();

        // A patient may not issue a quarantine verdict.
        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // A stale version cannot change the verdict.
        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("If-Match", "99")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        // A failed verdict is recorded and the payload stays unreachable.
        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"failed\",\"detail\":\"scan failed\"}")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.verdict").value("failed"));

        mvc.perform(get("/api/v1/patient/files/{id}/download", id)
                        .header("If-Match", "2")
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PUBLISHED"));

        // A foreign tenant cannot even locate the attachment.
        mvc.perform(post("/api/v1/patient/files/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("If-Match", "1")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
