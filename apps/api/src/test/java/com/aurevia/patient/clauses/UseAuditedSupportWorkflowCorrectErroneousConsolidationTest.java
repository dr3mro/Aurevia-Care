package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.patient.Patient;
import com.aurevia.patient.consolidation.Consolidation;
import com.aurevia.patient.consolidation.ConsolidationController;
import com.aurevia.patient.consolidation.ConsolidationService;
import com.aurevia.patient.consolidation.DuplicatePolicy;
import com.aurevia.patient.consolidation.DuplicateRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * IAM-04 clause 8 proof: an erroneous consolidation is corrected only through an
 * audited support workflow that appends a new version, reverses chart visibility and
 * leaves the original approval event untouched. Every request goes through the real
 * {@link com.aurevia.patient.consolidation.ConsolidationController#correct}
 * HTTP entry point with the real domain service and policy; only the RLS-bounded
 * repository is a synthetic Mockito boundary. Fixtures use synthetic UUIDs, no PHI.
 */
class UseAuditedSupportWorkflowCorrectErroneousConsolidationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SURVIVOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DUPLICATE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVIEW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String APPROVER = "approver-b";
    private static final String SUPPORT = "support-admin";
    private static final String BASE = "/api/v1/duplicate-reviews";

    private MockMvc mvc;
    private DuplicateRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(DuplicateRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        ConsolidationService service =
                new ConsolidationService(repository, new DuplicatePolicy(), transactions);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new ConsolidationController(service))
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

    /** Original approval: version 1, CONSOLIDATED, preserved as append-only history. */
    private Consolidation consolidated() {
        return new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", APPROVER, "reviewer-a", "duplicate", null);
    }

    /** Duplicate patient as read after the pair lock; its version is snapshotted into the correction. */
    private Patient duplicate() {
        return new Patient(DUPLICATE, 2, "MRN-2", "CLINIC-B", "Amina Khan", "amina khan",
                null, "not_disclosed", "ar", "[]", "[]", "[]");
    }

    private ResultActions correct(String subject, String ifMatch, String reason) throws Exception {
        MockHttpServletRequestBuilder request = post(BASE + "/{reviewId}/correct", REVIEW)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG)
                .header("X-Correlation-Id", "corr-clause-eight")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    @Test
    void correctionAppendsAuditedVersionReversesVisibilityAndPreservesOriginal() throws Exception {
        when(repository.activeRoles(SUPPORT)).thenReturn(List.of("tenant_admin"));
        Consolidation original = consolidated();
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(original));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.newId()).thenReturn("assoc-2", "cons-2");

        correct(SUPPORT, "\"1\"", "wrong patient")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cons-2"))
                .andExpect(jsonPath("$.status").value("CORRECTED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.correctionReason").value("wrong patient"))
                .andExpect(jsonPath("$.approverSubject").value(SUPPORT))
                .andExpect(jsonPath("$.openedBySubject").value(APPROVER))
                .andExpect(jsonPath("$.reason").value("duplicate"));

        assertEquals("CONSOLIDATED", original.status(),
                "an append-only correction must not mutate the original approval event");
        assertNull(original.correctionReason());
        verify(repository).lockPair(SURVIVOR, DUPLICATE);
        verify(repository).insertAssociation(eq("assoc-2"), eq(ORG), eq(DUPLICATE), eq(2), eq(SURVIVOR),
                eq("REVERSED"), eq("MRN-2"), eq("CLINIC-B"), anyString(), anyString(), eq("wrong patient"));
        verify(repository).insertConsolidation(eq("cons-2"), eq(ORG), eq(REVIEW), eq("assoc-2"),
                eq(SURVIVOR), eq(DUPLICATE), eq("CORRECTED"), eq(SUPPORT), eq(APPROVER),
                eq("duplicate"), eq("wrong patient"));
        ArgumentCaptor<TenantContext> context = ArgumentCaptor.forClass(TenantContext.class);
        verify(repository).appendAudit(context.capture(), eq("CONSOLIDATION_CORRECT"), eq(REVIEW), eq("SUCCESS"));
        assertEquals(ORG, context.getValue().organizationId());
        assertEquals(SUPPORT, context.getValue().subject());
    }

    @Test
    void correctionViaApiDeniesNonAdminRolesAndAuditsTheRefusal() throws Exception {
        when(repository.activeRoles("auditor-a")).thenReturn(List.of("privacy_auditor"));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(consolidated()));

        // privacy_auditor may review and approve but may not correct; reception may do neither.
        correct("auditor-a", "\"1\"", "wrong patient").andExpect(status().isForbidden());
        correct("reception-a", "\"1\"", "wrong patient").andExpect(status().isForbidden());

        verify(repository, never()).lockPair(anyString(), anyString());
        verifyNoWrites();
        verify(repository, times(2))
                .appendAudit(any(TenantContext.class), eq("CONSOLIDATION_CORRECT"), eq(REVIEW), eq("DENIED"));
    }

    @Test
    void correctionViaApiRefusesStaleVersionWithoutAppending() throws Exception {
        when(repository.activeRoles(SUPPORT)).thenReturn(List.of("tenant_admin"));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(consolidated()));

        correct(SUPPORT, "\"2\"", "wrong patient")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        verify(repository, never()).lockPair(anyString(), anyString());
        verifyNoWrites();
        verify(repository).appendAudit(any(TenantContext.class), eq("CONSOLIDATION_CORRECT"), eq(REVIEW), eq("STALE"));
    }

    @Test
    void correctionViaApiRefusesAnAlreadyCorrectedConsolidation() throws Exception {
        when(repository.activeRoles(SUPPORT)).thenReturn(List.of("tenant_admin"));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(
                new Consolidation("cons-2", REVIEW, SURVIVOR, DUPLICATE, 2,
                        "CORRECTED", SUPPORT, APPROVER, "duplicate", "first correction")));

        correct(SUPPORT, "\"2\"", "second correction")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        verify(repository, never()).lockPair(anyString(), anyString());
        verifyNoWrites();
        verify(repository).appendAudit(any(TenantContext.class), eq("CONSOLIDATION_CORRECT"), eq(REVIEW), eq("CONFLICT"));
    }

    @Test
    void correctionViaApiRefusesUnknownConsolidationAndIncompleteInput() throws Exception {
        when(repository.activeRoles(SUPPORT)).thenReturn(List.of("tenant_admin"));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());

        correct(SUPPORT, "\"1\"", "wrong patient")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        correct(SUPPORT, "", "wrong patient")
                .andExpect(status().isPreconditionRequired());
        correct(SUPPORT, "\"1\"", " ")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_REASON"));

        verifyNoWrites();
        verify(repository).appendAudit(any(TenantContext.class), eq("CONSOLIDATION_CORRECT"), eq(REVIEW), eq("NOT_FOUND"));
    }

    /** A refused correction must never append a chart association or a consolidation event. */
    private void verifyNoWrites() {
        verify(repository, never()).insertAssociation(anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).insertConsolidation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
