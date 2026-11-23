package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.InMemoryMessagingGovernanceStore;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController;
import com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * MSG-07 clause 4 acceptance: revoking a patient-unit connection ends new
 * messaging and future attachment access through that same connection.
 *
 * <p>Four cases drive the two governance guards directly over the synthetic
 * tenant-scoped store so the same connection can be observed active and then
 * revoked. One case drives the wired revocation-effects controller through the
 * MVC pipeline, which is the only revocation route the API exposes today. The
 * mediated attachment-download path is not wired to the guard; that gap is
 * recorded in the clause evidence document rather than hidden in a skipped
 * test. All identifiers, actors and timestamps are synthetic.</p>
 */
class RevokingConnectionStopsNewMessagingFutureAttachmentTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RESOURCE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CONNECTION = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String ACTIVE_CONNECTION = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String MISSING = "abcdef01-2345-6789-abcd-ef0123456789";
    private static final String UNIT = "12345678-90ab-cdef-1234-567890abcdef";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryMessagingGovernanceStore store;
    private MessagingGovernanceService service;
    private MockMvc mvc;

    /** Seed a synthetic tenant matrix, a revoked and an active connection, and the MVC pipeline. */
    @BeforeEach
    void setUp() {
        store = new InMemoryMessagingGovernanceStore();
        store.addMember(ORG, "clinician", "clinician", true);
        store.addMember(ORG, "patient-user", "patient", true);
        store.addMember(ORG, "admin", "tenant_admin", true);
        store.addMember(ORG, "auditor", "privacy_auditor", true);
        store.addMember(ORG, "inactive", "unit_staff", false);
        store.addMember(OTHER_ORG, "admin", "tenant_admin", true);
        store.addMember(OTHER_ORG, "other-clinician", "clinician", true);
        store.addRecordAccessPolicy(ORG, true, "policy-v1");
        store.addConnection(ORG, CONNECTION, UNIT, PATIENT, NOW.minus(Duration.ofMinutes(5)));
        store.addConnection(ORG, ACTIVE_CONNECTION, UNIT, PATIENT, null);
        service = new MessagingGovernanceService(store, new MessagingGovernanceActions(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new MessagingGovernanceController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        as("clinician", ORG);
    }

    /** Clear thread-bound identity after every case, including the MVC cases. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * Rule met for new messaging: the same connection is allowed while active
     * and refused once revoked, with a denial fact on the revoked attempt.
     */
    @Test
    void revokingAConnectionBlocksNewMessagingThroughTheGovernanceGuard() {
        int before = auditCount(ORG);
        service.requireMessagingAllowed(ACTIVE_CONNECTION, RESOURCE);
        assertEquals(before + 1, auditCount(ORG));
        assertEquals(AuditAction.SEND, lastAudit(ORG).action());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
        assertEquals(UNIT, lastAudit(ORG).unitId());

        // Revoke the very connection that was just allowed. A client cannot
        // distinguish a stale authorization from a revoked relationship: the
        // guard re-reads the stored revocation instant on every call (MSG-07).
        store.addConnection(ORG, ACTIVE_CONNECTION, UNIT, PATIENT, NOW);
        ApiException denied = assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(ACTIVE_CONNECTION, RESOURCE));
        assertEquals(403, denied.status());
        assertEquals("MESSAGING_REVOKED", denied.code());
        assertEquals(before + 2, auditCount(ORG));
        assertEquals(AuditAction.SEND, lastAudit(ORG).action());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
        assertEquals("SYSTEM", lastAudit(ORG).unitId());
    }

    /**
     * Rule met for future attachment access: a mediated access is allowed while
     * the connection is active and refused once that same connection is revoked.
     */
    @Test
    void revokingAConnectionBlocksFutureAttachmentAccessThroughTheGovernanceGuard() {
        int before = auditCount(ORG);
        service.requireAttachmentAccess(ACTIVE_CONNECTION, RESOURCE);
        assertEquals(before + 1, auditCount(ORG));
        assertEquals(AuditAction.DOWNLOAD, lastAudit(ORG).action());
        assertEquals(Outcome.SUCCESS, lastAudit(ORG).outcome());
        assertEquals(UNIT, lastAudit(ORG).unitId());

        store.addConnection(ORG, ACTIVE_CONNECTION, UNIT, PATIENT, NOW);
        ApiException denied = assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(ACTIVE_CONNECTION, RESOURCE));
        assertEquals(403, denied.status());
        assertEquals("ATTACHMENT_ACCESS_REVOKED", denied.code());
        assertEquals(before + 2, auditCount(ORG));
        assertEquals(AuditAction.DOWNLOAD, lastAudit(ORG).action());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
        assertEquals("SYSTEM", lastAudit(ORG).unitId());
    }

    /**
     * Negative (wrong role): recording revocation effects is a privileged
     * governance command; a patient cannot assert it and no effect is stored.
     */
    @Test
    void aPatientCannotRecordRevocationEffectsForAConnection() {
        int before = auditCount(ORG);
        as("patient-user", ORG);
        ApiException denied = assertThrows(ApiException.class,
                () -> service.recordRevocation(CONNECTION));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertEquals(before + 1, auditCount(ORG));
        assertEquals(AuditAction.RESTRICT, lastAudit(ORG).action());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
        assertTrue(store.revocationEffects(ORG, CONNECTION).isEmpty());
    }

    /**
     * Negative (other tenant): a connection of the active organization is
     * concealed from another tenant for the restrict, send and download guards.
     */
    @Test
    void anotherTenantCannotSeeOrRevokeThisConnection() {
        int orgAudits = auditCount(ORG);
        int otherAudits = auditCount(OTHER_ORG);
        as("admin", OTHER_ORG);
        ApiException denied = assertThrows(ApiException.class,
                () -> service.recordRevocation(CONNECTION));
        assertEquals(404, denied.status());
        assertEquals("NOT_FOUND", denied.code());
        assertEquals(otherAudits + 1, auditCount(OTHER_ORG));
        assertEquals(orgAudits, auditCount(ORG));
        assertEquals(OTHER_ORG, lastAudit(OTHER_ORG).organizationId());
        assertEquals(AuditAction.RESTRICT, lastAudit(OTHER_ORG).action());
        assertEquals(Outcome.NOT_FOUND, lastAudit(OTHER_ORG).outcome());
        assertTrue(store.revocationEffects(OTHER_ORG, CONNECTION).isEmpty());

        // The send and download guards need a conversation role; a tenant admin
        // is refused by authorize() before the connection lookup, so use a
        // clinician in the foreign tenant to reach the concealment branch.
        as("other-clinician", OTHER_ORG);
        ApiException hiddenMessaging = assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(CONNECTION, RESOURCE));
        assertEquals(404, hiddenMessaging.status());
        assertEquals("NOT_FOUND", hiddenMessaging.code());
        assertEquals(AuditAction.SEND, lastAudit(OTHER_ORG).action());
        assertEquals(Outcome.NOT_FOUND, lastAudit(OTHER_ORG).outcome());

        ApiException hiddenAttachment = assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(CONNECTION, RESOURCE));
        assertEquals(404, hiddenAttachment.status());
        assertEquals("NOT_FOUND", hiddenAttachment.code());
        assertEquals(AuditAction.DOWNLOAD, lastAudit(OTHER_ORG).action());
        assertEquals(Outcome.NOT_FOUND, lastAudit(OTHER_ORG).outcome());

        assertEquals(404, assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(MISSING, RESOURCE)).status());
    }

    /**
     * HTTP proof for the wired route: the revocation effect reports both blocking
     * flags and the record-access basis, refuses an active connection and is
     * concealed from another tenant.
     */
    @Test
    void revocationEffectsAreRecordedThroughTheGovernanceHttpRoute() throws Exception {
        mvc.perform(headers(post("/api/v1/messaging-governance/connections/" + CONNECTION
                        + "/revocation-effects"), "admin", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionId").value(CONNECTION))
                .andExpect(jsonPath("$.messagingBlocked").value(true))
                .andExpect(jsonPath("$.attachmentAccessBlocked").value(true))
                .andExpect(jsonPath("$.priorConversationsReadable").value(true))
                .andExpect(jsonPath("$.recordAccessBasis").value("policy-v1"));

        mvc.perform(headers(get("/api/v1/messaging-governance/audit"), "admin", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'restrict' && @.outcome == 'success')]").exists());

        mvc.perform(headers(post("/api/v1/messaging-governance/connections/" + ACTIVE_CONNECTION
                        + "/revocation-effects"), "admin", ORG))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONNECTION_ACTIVE"));

        mvc.perform(headers(post("/api/v1/messaging-governance/connections/" + CONNECTION
                        + "/revocation-effects"), "admin", OTHER_ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * Negative (wrong role on the guards): an audit reviewer cannot use the
     * conversation actions whose revocation state this clause enforces.
     */
    @Test
    void aPrivacyAuditorCannotUseTheMessagingOrAttachmentGuards() {
        int before = auditCount(ORG);
        as("auditor", ORG);
        ApiException messaging = assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(ACTIVE_CONNECTION, RESOURCE));
        assertEquals(403, messaging.status());
        assertEquals("FORBIDDEN", messaging.code());
        assertEquals(AuditAction.SEND, lastAudit(ORG).action());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());

        ApiException attachment = assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(ACTIVE_CONNECTION, RESOURCE));
        assertEquals(403, attachment.status());
        assertEquals("FORBIDDEN", attachment.code());
        assertEquals(before + 2, auditCount(ORG));
        assertEquals(AuditAction.DOWNLOAD, lastAudit(ORG).action());
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
    }

    /**
     * Negative (inactive membership): a deactivated subject fails closed on
     * both guards before any connection or role is considered.
     */
    @Test
    void anInactiveSubjectCannotUseTheRevocationGuards() {
        int before = auditCount(ORG);
        as("inactive", ORG);
        ApiException messaging = assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(ACTIVE_CONNECTION, RESOURCE));
        assertEquals(403, messaging.status());
        assertEquals("FORBIDDEN", messaging.code());
        ApiException attachment = assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(ACTIVE_CONNECTION, RESOURCE));
        assertEquals(403, attachment.status());
        assertEquals("FORBIDDEN", attachment.code());
        assertEquals(before + 2, auditCount(ORG));
        assertEquals(Outcome.DENIED, lastAudit(ORG).outcome());
    }

    /** No ambient identity means neither guard can decide, so both fail closed. */
    @Test
    void missingTenantContextFailsClosedForBothGuards() {
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.requireMessagingAllowed(ACTIVE_CONNECTION, RESOURCE)).status());
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.requireAttachmentAccess(ACTIVE_CONNECTION, RESOURCE)).status());
    }

    private void as(String subject, String organizationId) {
        TenantContext.set(new TenantContext(organizationId, subject, "MSG_GOVERNANCE", "corr-clause"));
    }

    private AuditEvent lastAudit(String organizationId) {
        List<AuditEvent> events = store.auditEvents(organizationId);
        return events.get(events.size() - 1);
    }

    private int auditCount(String organizationId) {
        return store.auditEvents(organizationId).size();
    }

    private MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder builder,
                                                   String subject, String organizationId) {
        return builder.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organizationId);
    }
}
