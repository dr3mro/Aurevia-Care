package com.aurevia.printing.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.printing.HandoutRenderer;
import com.aurevia.printing.HandoutService;
import com.aurevia.printing.LocalHandoutArchive;
import com.aurevia.documents.PdfRenderer;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenancy.FacilityRepository;
import com.aurevia.tenant.TenantContext;

/**
 * HTTP-level print and download behaviour through the real handout service.
 *
 * <p>Standalone MockMvc drives the controller, identity and facility lookups
 * are mocked on synthetic values, and a capturing audit trail proves that each
 * print, reprint, download and denial writes one sanitized entry. No database,
 * partner or real patient record is used.</p>
 */
class PrintControllerTest {

    private static final String ORG = "org-alpha";
    private static final String SUBJECT = "clinician-a";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant ISSUED = Instant.parse("2026-11-21T10:15:00Z");

    private StaffRepository staff;
    private FacilityRepository facilities;
    private List<PrintAuditEntry> auditEntries;
    private PrintController controller;
    private MockMvc mockMvc;

    /** Build the controller over a real handout service and synthetic collaborators. */
    @BeforeEach
    void setUp() {
        staff = mock(StaffRepository.class);
        facilities = mock(FacilityRepository.class);
        auditEntries = new ArrayList<>();
        PrintAuditTrail auditTrail = auditEntries::add;
        HandoutService service = new HandoutService(new HandoutRenderer(new PdfRenderer()),
                new LocalHandoutArchive(), Clock.fixed(ISSUED, ZoneOffset.UTC));
        controller = new PrintController(service, staff, facilities,
                new TransactionTemplate(new PassthroughTransactionManager()), auditTrail,
                Clock.fixed(ISSUED, ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler()).build();
    }

    /** Remove any thread-bound tenant context left by a case. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static void bind(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, "PRINT", "corr-1"));
    }

    private void allow(String... roles) {
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of(roles));
        when(facilities.findByCode("FAC-A"))
                .thenReturn(Optional.of(new Facility(ORG, "FAC-A", "Facility A", "UTC")));
    }

    private static String printBody(String reason, String organizationId, String language) {
        return """
                {"reason":"%s","language":"%s","source":{
                  "organizationId":"%s","kind":"PRESCRIPTION","sourceId":"src-1",
                  "revisionVersion":1,"contentChecksum":"%s","state":"attested","attested":true,
                  "printChannel":"PRINTABLE_LOCAL","patientId":"PAT-SYN-1",
                  "patientName":"Synthetic Patient","clinician":"Dr Synthetic",
                  "facility":"FAC-A","issueTime":"2026-11-21T10:15:00Z",
                  "sourceSystem":"SYNTHETIC-EMR","contentLines":["Synthetic line"]}}
                """.formatted(reason, language, organizationId, CHECKSUM);
    }

    private static String downloadBody(String reason, int version, String organizationId) {
        return """
                {"reason":"%s","language":"en","version":%d,"source":{
                  "organizationId":"%s","kind":"PRESCRIPTION","sourceId":"src-1",
                  "revisionVersion":1,"contentChecksum":"%s","state":"attested","attested":true,
                  "printChannel":"PRINTABLE_LOCAL","patientId":"PAT-SYN-1",
                  "patientName":"Synthetic Patient","clinician":"Dr Synthetic",
                  "facility":"FAC-A","issueTime":"2026-11-21T10:15:00Z",
                  "sourceSystem":"SYNTHETIC-EMR","contentLines":["Synthetic line"]}}
                """.formatted(reason, version, organizationId, CHECKSUM);
    }

    /** A treating clinician prints a handout: 201, identifiers only, one sanitized audit row. */
    @Test
    void clinicianPrintsHandoutAndAuditRecordsWhyWhatVersionWhen() throws Exception {
        bind(ORG, SUBJECT);
        allow("clinician");

        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PRESCRIPTION"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sourceId").value("src-1"));

        assertEquals(1, auditEntries.size());
        PrintAuditEntry entry = auditEntries.get(0);
        assertEquals(SUBJECT, entry.actorSubject());
        assertEquals("give_to_patient", entry.reason().wire());
        assertEquals("HANDOUT_PRINT", entry.action());
        assertEquals("SUCCESS", entry.outcome());
        assertEquals(1, entry.version());
        assertEquals(ISSUED, entry.at());
        assertEquals("FAC-A", entry.facility());
        assertFalse(asText(entry).contains("Synthetic Patient"));
        assertFalse(asText(entry).contains("Synthetic line"));
    }

    /** Reprinting an unchanged source reuses the version and writes its own audit row. */
    @Test
    void reprintWritesItsOwnAuditEntryAndReusesVersion() throws Exception {
        bind(ORG, SUBJECT);
        allow("clinician");
        String body = printBody("give_to_patient", ORG, "en");

        String first = mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON).content(printBody("patient_request", ORG, "en")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(2, auditEntries.size());
        assertEquals("HANDOUT_PRINT", auditEntries.get(0).action());
        assertEquals("HANDOUT_REPRINT", auditEntries.get(1).action());
        assertEquals(PrintReason.PATIENT_REQUEST, auditEntries.get(1).reason());
        assertEquals(auditEntries.get(0).handoutId(), auditEntries.get(1).handoutId());
    }

    /** A role with no print permission is forbidden and the refusal is audited. */
    @Test
    void forbiddenRoleIsDeniedAndAudited() throws Exception {
        bind(ORG, SUBJECT);
        allow("reception");

        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertEquals(1, auditEntries.size());
        assertEquals("DENIED", auditEntries.get(0).outcome());
        assertEquals("HANDOUT_PRINT", auditEntries.get(0).action());
        assertFalse(asText(auditEntries.get(0)).contains("Synthetic line"));
    }

    /** A record from another tenant is concealed, not revealed as a tenant conflict. */
    @Test
    void wrongTenantIsConcealed() throws Exception {
        bind(ORG, SUBJECT);
        allow("clinician");

        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", "org-beta", "en")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertEquals(1, auditEntries.size());
        assertEquals("DENIED", auditEntries.get(0).outcome());
    }

    /** A record outside the caller's facility scope is forbidden and audited. */
    @Test
    void facilityOutsideScopeIsDenied() throws Exception {
        bind(ORG, SUBJECT);
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(true);
        when(staff.activeRoles(SUBJECT)).thenReturn(List.of("clinician"));
        when(facilities.findByCode("FAC-A")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FACILITY_OUT_OF_SCOPE"));

        assertEquals(1, auditEntries.size());
        assertEquals("DENIED", auditEntries.get(0).outcome());
    }

    /** A caller without active membership is denied and the refusal is audited. */
    @Test
    void inactiveMembershipIsDenied() throws Exception {
        bind(ORG, SUBJECT);
        when(staff.hasActiveMembership(SUBJECT)).thenReturn(false);

        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertEquals(1, auditEntries.size());
        assertEquals("DENIED", auditEntries.get(0).outcome());
    }

    /** No tenant context means no identity and no handout. */
    @Test
    void missingContextIsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isUnauthorized());
        assertTrue(auditEntries.isEmpty());
    }

    /** A reason outside the closed list is rejected before any render. */
    @Test
    void invalidReasonIsRejected() throws Exception {
        bind(ORG, SUBJECT);
        allow("clinician");

        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("marketing", ORG, "en")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"));
        assertTrue(auditEntries.isEmpty());
    }

    /** A download re-renders the same immutable version and writes its own audit row. */
    @Test
    void downloadReturnsTheImmutableVersionBytes() throws Exception {
        bind(ORG, SUBJECT);
        allow("clinician");
        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isCreated());

        byte[] pdf = mockMvc.perform(post("/api/v1/printing/handouts/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(downloadBody("referral", 1, ORG)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();

        assertTrue(new String(pdf, StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
        assertEquals(2, auditEntries.size());
        assertEquals("HANDOUT_DOWNLOAD", auditEntries.get(1).action());
        assertEquals("referral", auditEntries.get(1).reason().wire());
        assertEquals(1, auditEntries.get(1).version());
    }

    /** A forbidden role cannot download either, and the refusal is audited. */
    @Test
    void downloadByForbiddenRoleIsDeniedAndAudited() throws Exception {
        bind(ORG, SUBJECT);
        allow("reception");

        mockMvc.perform(post("/api/v1/printing/handouts/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(downloadBody("give_to_patient", 1, ORG)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertEquals(1, auditEntries.size());
        assertEquals("DENIED", auditEntries.get(0).outcome());
        assertEquals("HANDOUT_DOWNLOAD", auditEntries.get(0).action());
    }

    /** A stale version is refused and the refusal is audited. */
    @Test
    void downloadRefusesStaleVersion() throws Exception {
        bind(ORG, SUBJECT);
        allow("clinician");
        mockMvc.perform(post("/api/v1/printing/handouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(printBody("give_to_patient", ORG, "en")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/printing/handouts/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(downloadBody("patient_request", 2, ORG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        assertEquals(2, auditEntries.size());
        assertEquals("DENIED", auditEntries.get(1).outcome());
        assertEquals("HANDOUT_DOWNLOAD", auditEntries.get(1).action());
    }

    private static String asText(PrintAuditEntry entry) {
        return String.valueOf(entry.reason().wire()) + entry.kind().wire() + entry.handoutId()
                + entry.action() + entry.facility() + entry.outcome();
    }

    /** Runs callbacks directly so DB-less HTTP tests exercise controller logic. */
    private static final class PassthroughTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no durable state in unit tests
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no durable state in unit tests
        }
    }
}
