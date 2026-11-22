package com.aurevia.smartclinic.audit.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.authz.access.AccessContracts.IncidentRow;
import com.aurevia.smartclinic.authz.access.AccessController;
import com.aurevia.smartclinic.authz.access.AccessRepository;
import com.aurevia.smartclinic.authz.access.EmergencyAccessService;
import com.aurevia.smartclinic.authz.access.SupportAccessService;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

/**
 * IAM-06 clause 4 API proof: platform support access to clinical data is denied by
 * default and is only opened by a separately approved, unexpired and audited support
 * incident bound to the requesting subject in the active organization.
 *
 * <p>Every request goes through the real HTTP entry point
 * {@code AccessController} at {@code /api/v1/access/support/**}, backed by the real
 * {@code SupportAccessService} and {@code AccessRules}. Only the RLS-bounded
 * {@code AccessRepository} is a synthetic Mockito boundary; the real-PostgreSQL
 * proof of the same behaviour lives in the integration suite. Fixtures use
 * synthetic subjects and references, never patient data or credentials.</p>
 *
 * <p>This proves the reported decision and the audited incident procedure. The
 * OIDC URL-level authentication boundary is not installed by this standalone
 * MockMvc harness, and enforcement at clinical-data read call sites is outside
 * this clause; both belong to other suites.</p>
 */
class PlatformSupportAccessClinicalDataDisabledDefaultTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String ADMIN = "admin-a";
    private static final String AUDITOR = "auditor-a";
    private static final String SUPPORT = "support-b";
    private static final String REF = "INC-100";
    private static final String CORRELATION = "corr-iam06-c4";
    private static final String PURPOSE = "SUPPORT_ACCESS";
    private static final String INCIDENT_TYPE = "SUPPORT_ACCESS_INCIDENT";
    private static final String BASE = "/api/v1/access";
    private static final Instant NOW = Instant.parse("2026-11-20T09:00:00Z");

    private MockMvc mvc;
    private AccessRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(AccessRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EmergencyAccessService emergency = new EmergencyAccessService(repository, transactions, clock);
        SupportAccessService support = new SupportAccessService(repository, transactions, clock);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new AccessController(emergency, support))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        // Fail closed by default; each test grants the role it needs below.
        when(repository.hasActiveMembership(anyString())).thenReturn(true);
        when(repository.activeRoles(anyString())).thenReturn(List.of("reception"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** An opened but not yet independently approved incident. */
    private IncidentRow pendingIncident() {
        return new IncidentRow("id-1", REF, ADMIN, "restore chart", "pending", null, null,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    /** An incident approved by a different subject and still inside its window. */
    private IncidentRow approvedIncident() {
        return new IncidentRow("id-1", REF, ADMIN, "restore chart", "approved", AUDITOR, NOW,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
    }

    private ResultActions check(String subject, String organization, String reference) throws Exception {
        return mvc.perform(post(BASE + "/support/check")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", CORRELATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reference\":\"" + reference + "\"}"));
    }

    private ResultActions createIncident(String subject, String body) throws Exception {
        return mvc.perform(post(BASE + "/support/incidents")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG)
                .header("X-Correlation-Id", CORRELATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions approveIncident(String subject, String reference) throws Exception {
        return mvc.perform(post(BASE + "/support/incidents/{reference}/approve", reference)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG)
                .header("X-Correlation-Id", CORRELATION));
    }

    /**
     * Positive proof of the default-deny rule: with no usable incident the check
     * returns an explicit refusal and records it, rather than failing open.
     */
    @Test
    void supportAccessIsDeniedByDefaultAndAuditsTheRefusal() throws Exception {
        when(repository.activeIncident(REF, ADMIN, NOW)).thenReturn(Optional.empty());

        check(ADMIN, ORG, REF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("NO_APPROVED_INCIDENT"));

        verify(repository).appendAudit(any(), eq(PURPOSE), eq("SUPPORT_ACCESS_CHECK"),
                eq("platform_support:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("DENIED"));
        verify(repository, never()).insertIncident(anyString(), anyString(), anyString(), anyString(),
                any(), any());
        verify(repository, never()).approveIncident(anyString(), anyString(), any());
    }

    /**
     * Positive proof of the separate approved and audited incident procedure: a
     * tenant administrator opens, a privacy auditor independently approves, and
     * only then does the bound support subject get access, with each step audited.
     */
    @Test
    void separatelyApprovedUnexpiredIncidentEnablesAuditedSupportAccess() throws Exception {
        when(repository.activeRoles(ADMIN)).thenReturn(List.of("tenant_admin"));
        when(repository.activeRoles(AUDITOR)).thenReturn(List.of("privacy_auditor"));
        when(repository.insertIncident(ORG, REF, ADMIN, "restore chart", NOW, NOW.plusSeconds(3600)))
                .thenReturn(pendingIncident());
        when(repository.approveIncident(REF, AUDITOR, NOW)).thenReturn(1);
        when(repository.findIncident(REF)).thenReturn(Optional.of(approvedIncident()));
        when(repository.activeIncident(REF, ADMIN, NOW)).thenReturn(Optional.of(approvedIncident()));

        createIncident(ADMIN, "{\"reference\":\"" + REF
                + "\",\"reason\":\"restore chart\",\"windowMinutes\":60}")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE + "/support/incidents/" + REF))
                .andExpect(jsonPath("$.reference").value(REF))
                .andExpect(jsonPath("$.subject").value(ADMIN))
                .andExpect(jsonPath("$.status").value("pending"));

        approveIncident(AUDITOR, REF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.approvedBy").value(AUDITOR));

        check(ADMIN, ORG, REF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("APPROVED_INCIDENT"));

        ArgumentCaptor<TenantContext> created = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(created.capture(), eq(PURPOSE), eq("SUPPORT_INCIDENT_CREATE"),
                eq("tenant_admin:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("SUCCESS"));
        assertEquals(ORG, created.getValue().organizationId());
        assertEquals(ADMIN, created.getValue().subject());

        ArgumentCaptor<TenantContext> approved = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(approved.capture(), eq(PURPOSE), eq("SUPPORT_INCIDENT_APPROVE"),
                eq("privacy_auditor:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("SUCCESS"));
        assertEquals(AUDITOR, approved.getValue().subject());

        ArgumentCaptor<TenantContext> checked = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(checked.capture(), eq(PURPOSE), eq("SUPPORT_ACCESS_CHECK"),
                eq("platform_support:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("SUCCESS"));
        assertEquals(ADMIN, checked.getValue().subject());
    }

    /**
     * Negative proof of separation of duties: the approver role cannot open an
     * incident and the requester role cannot approve one, and neither writes.
     */
    @Test
    void wrongRoleCannotOpenOrApproveAnIncidentAndRefusalIsAudited() throws Exception {
        when(repository.activeRoles(AUDITOR)).thenReturn(List.of("privacy_auditor"));
        when(repository.activeRoles(ADMIN)).thenReturn(List.of("tenant_admin"));

        createIncident(AUDITOR, "{\"reference\":\"" + REF
                + "\",\"reason\":\"restore chart\",\"windowMinutes\":60}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        approveIncident(ADMIN, REF)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(repository, never()).insertIncident(anyString(), anyString(), anyString(), anyString(),
                any(), any());
        verify(repository, never()).approveIncident(anyString(), anyString(), any());
        verify(repository).appendAudit(any(), eq(PURPOSE), eq("SUPPORT_INCIDENT_CREATE"),
                eq("tenant_admin:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("DENIED"));
        verify(repository).appendAudit(any(), eq(PURPOSE), eq("SUPPORT_INCIDENT_APPROVE"),
                eq("privacy_auditor:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("DENIED"));
    }

    /**
     * Negative proof of the forbidden state: a self-approved incident and a subject
     * without active membership both stay denied instead of opening clinical data.
     */
    @Test
    void selfApprovedOrInactiveMembershipIncidentNeverEnablesAccess() throws Exception {
        IncidentRow selfApproved = new IncidentRow("id-1", REF, ADMIN, "restore chart", "approved",
                ADMIN, NOW, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(repository.activeIncident(REF, ADMIN, NOW)).thenReturn(Optional.of(selfApproved));

        check(ADMIN, ORG, REF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("SELF_APPROVAL"));

        when(repository.hasActiveMembership(ADMIN)).thenReturn(false);
        check(ADMIN, ORG, REF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("NO_ACTIVE_MEMBERSHIP"));

        verify(repository, times(2)).appendAudit(any(), eq(PURPOSE), eq("SUPPORT_ACCESS_CHECK"),
                eq("platform_support:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("DENIED"));
        verify(repository, never()).appendAudit(any(), eq(PURPOSE), eq("SUPPORT_ACCESS_CHECK"),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    /**
     * Negative proof that an approval is bound to a subject: the same reference is
     * useless to a different support subject. The repository is mocked here, so the
     * database-level tenant isolation of the same check is proven separately by the
     * real-PostgreSQL integration suite.
     */
    @Test
    void differentSupportSubjectCannotReuseAnApprovedIncidentReference() throws Exception {
        when(repository.activeIncident(REF, SUPPORT, NOW)).thenReturn(Optional.empty());

        check(SUPPORT, OTHER_ORG, REF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("NO_APPROVED_INCIDENT"));

        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq(PURPOSE), eq("SUPPORT_ACCESS_CHECK"),
                eq("platform_support:" + REF), eq(INCIDENT_TYPE), eq(REF), eq("DENIED"));
        assertEquals(OTHER_ORG, context.getValue().organizationId());
        assertEquals(SUPPORT, context.getValue().subject());
    }

    /**
     * Negative proof that malformed or missing input never reaches the incident
     * store: a blank reference, a blank reason and an out-of-range window refuse.
     */
    @Test
    void blankReferenceReasonOrWindowAreRefusedBeforeAnyWrite() throws Exception {
        check(ADMIN, ORG, " ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"));
        createIncident(ADMIN, "{\"reference\":\" \",\"reason\":\"restore chart\",\"windowMinutes\":60}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"));
        createIncident(ADMIN, "{\"reference\":\"" + REF + "\",\"reason\":\" \",\"windowMinutes\":60}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"));
        createIncident(ADMIN, "{\"reference\":\"" + REF + "\",\"reason\":\"restore chart\",\"windowMinutes\":0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

        verify(repository, never()).insertIncident(anyString(), anyString(), anyString(), anyString(),
                any(), any());
        verify(repository, never()).approveIncident(anyString(), anyString(), any());
        verify(repository, never()).activeIncident(anyString(), anyString(), any());
        verify(repository, never()).appendAudit(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }
}
