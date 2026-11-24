package com.aurevia.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

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
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-03 HTTP entry point for staff-message attachments: multipart upload, scan,
 * pending list, explicit send and recipient-checked download.
 */
class StaffAttachmentControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SENDER = "nurse-a";
    private static final String SCANNER = "scanner-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String DRAFT = "conversation-1";
    private static final String MESSAGE = "message-1";
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private InMemoryStaffAttachmentStore store;
    private StaffAttachmentController controller;
    private MockMvc mvc;

    /** Wire the controller over synthetic stores and fresh membership. */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        store = new InMemoryStaffAttachmentStore();
        StaffRepository staff = mock(StaffRepository.class);
        when(staff.activeRoles(SENDER)).thenReturn(List.of("nurse"));
        when(staff.activeRoles(SCANNER)).thenReturn(List.of("security_scanner"));
        when(staff.activeRoles(RECIPIENT)).thenReturn(List.of("clinician"));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        controller = new StaffAttachmentController(
                new StaffAttachmentService(store, clock, new StaffAttachmentLimits()), staff, transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    /** Clear the thread-local tenant after each request. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "note.pdf", "application/pdf", PDF);
    }

    @Test
    void uploadsScansListsSendsAndDownloadsThroughHttp() throws Exception {
        String response = mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(file()).param("draftId", DRAFT)
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scanState").value("pending"))
                .andExpect(jsonPath("$.draftId").value(DRAFT))
                .andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(response).get("id").asString();

        mvc.perform(post("/api/v1/staff/attachments/{id}/scan", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"clean\",\"detail\":\"ok\"}")
                        .header("X-Synthetic-Subject", SCANNER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanState").value("clean"));

        mvc.perform(get("/api/v1/staff/attachments/pending")
                        .param("draftId", DRAFT)
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(post("/api/v1/staff/attachments/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"" + DRAFT + "\",\"messageId\":\"" + MESSAGE
                                + "\",\"attachmentIds\":[\"" + id + "\"],\"recipients\":[\""
                                + RECIPIENT + "\"],\"confirm\":true}")
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sent").value(true));
        store.putRecipients(ORG, MESSAGE, Set.of(RECIPIENT));

        mvc.perform(get("/api/v1/staff/attachments/{id}/download", id)
                        .header("X-Synthetic-Subject", RECIPIENT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Checksum", org.hamcrest.Matchers.notNullValue()))
                .andExpect(result -> assertEquals(new String(PDF, StandardCharsets.UTF_8),
                        result.getResponse().getContentAsString()));
    }

    @Test
    void uploadWithoutLoginReturns401() throws Exception {
        mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(file()).param("draftId", DRAFT)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void missingTenantContextFailsClosed() {
        TenantContext.clear();
        ApiException denied = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> controller.download("missing"));
        assertEquals(401, denied.status());
        assertEquals("UNAUTHENTICATED", denied.code());
    }

    @Test
    void unreadablePayloadReturns422() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("file", "note.pdf", "application/pdf", PDF) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("synthetic read failure");
            }
        };
        mvc.perform(multipart("/api/v1/staff/attachments/upload")
                        .file(broken).param("draftId", DRAFT)
                        .header("X-Synthetic-Subject", SENDER)
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD"));
    }
}
