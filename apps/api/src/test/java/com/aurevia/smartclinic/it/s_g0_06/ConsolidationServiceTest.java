package com.aurevia.smartclinic.it.s_g0_06;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g0.s_g0_06.Consolidation;
import com.aurevia.smartclinic.g0.s_g0_06.ConsolidationController;
import com.aurevia.smartclinic.g0.s_g0_06.ConsolidationService;
import com.aurevia.smartclinic.g0.s_g0_06.DuplicatePolicy;
import com.aurevia.smartclinic.g0.s_g0_06.DuplicateRepository;
import com.aurevia.smartclinic.g0.s_g0_06.DuplicateRepository.DuplicatePair;
import com.aurevia.smartclinic.g0.s_g0_06.DuplicateReview;
import com.aurevia.smartclinic.g0.s_g0_06.ReviewDetail;
import com.aurevia.smartclinic.patient.Patient;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * S-G0-06 unit acceptance for IAM-04: review worklist, dry run, second-reviewer
 * approval, locked all-or-nothing consolidation, blockers and audited correction.
 */
class ConsolidationServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SURVIVOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DUPLICATE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVIEW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String REVIEWER_A = "reviewer-a";
    private static final String REVIEWER_B = "reviewer-b";

    private DuplicateRepository repository;
    private ConsolidationService service;

    @BeforeEach
    void setUp() {
        repository = mock(DuplicateRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ConsolidationService(repository, new DuplicatePolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        bind(REVIEWER_A);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "IDENTITY_ADMIN", "corr-1"));
    }

    private void authorized(String subject, String... roles) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roles));
    }

    private Patient survivor() {
        return new Patient(SURVIVOR, 1, "MRN-1", "CLINIC-A", "Amina Khan", "amina khan",
                "1988-03-03", null, "en", "{}", "[]", "[]");
    }

    private Patient duplicate() {
        return new Patient(DUPLICATE, 2, "MRN-2", "CLINIC-B", "Amina  Khan", "amina khan",
                null, "not_disclosed", "ar", "{\"phone\":\"x\"}", "[{\"type\":\"MRN\"}]", "[]");
    }

    private DuplicateReview openReview() {
        return new DuplicateReview(REVIEW, SURVIVOR, DUPLICATE, REVIEWER_A, 1, "OPEN");
    }

    @Test
    void policyAllowsOnlyReviewerRoles() {
        DuplicatePolicy policy = new DuplicatePolicy();
        assertTrue(policy.allows(DuplicatePolicy.REVIEW, "tenant_admin"));
        assertTrue(policy.allows(DuplicatePolicy.REVIEW, "privacy_auditor"));
        assertFalse(policy.allows(DuplicatePolicy.REVIEW, "reception"));
        assertFalse(policy.allows(DuplicatePolicy.REVIEW, "clinician"));
        assertTrue(policy.allows(DuplicatePolicy.APPROVE, "tenant_admin"));
        assertTrue(policy.allows(DuplicatePolicy.APPROVE, "privacy_auditor"));
        assertFalse(policy.allows(DuplicatePolicy.APPROVE, "reception"));
        assertTrue(policy.allows(DuplicatePolicy.CORRECT, "tenant_admin"));
        assertFalse(policy.allows(DuplicatePolicy.CORRECT, "privacy_auditor"));
        assertFalse(policy.allows(DuplicatePolicy.CORRECT, "reception"));
        assertFalse(policy.allows("UNKNOWN", "tenant_admin"));
    }

    @Test
    void worklistDeniesInactiveMembershipAndMissingRole() {
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, service::worklist).status());
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_WORKLIST", "LIST", "DENIED");

        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(true);
        when(repository.activeRoles(REVIEWER_A)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, service::worklist).status());
    }

    @Test
    void worklistReturnsSameTenantPairs() {
        authorized(REVIEWER_A, "privacy_auditor");
        DuplicatePair pair = new DuplicatePair(SURVIVOR, "Amina Khan", DUPLICATE, "Amina Khan");
        when(repository.likelyPairs()).thenReturn(List.of(pair));
        List<DuplicatePair> pairs = service.worklist();
        assertEquals(1, pairs.size());
        assertEquals(DUPLICATE, pairs.get(0).duplicateId());
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_WORKLIST", "LIST", "SUCCESS");
    }

    @Test
    void reviewRejectsInvalidIdsAndDenials() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.review("bad", DUPLICATE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.review(SURVIVOR, "bad id")).status());
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.review(SURVIVOR, DUPLICATE)).status());
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(true);
        when(repository.activeRoles(REVIEWER_A)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.review(SURVIVOR, DUPLICATE)).status());
    }

    @Test
    void reviewConcealsMissingIdentities() {
        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.review(SURVIVOR, DUPLICATE)).status());
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.review(SURVIVOR, DUPLICATE)).status());
        verify(repository, atLeastOnce()).appendAudit(TenantContext.current(), "DUPLICATE_REVIEW", DUPLICATE, "NOT_FOUND");
    }

    @Test
    void reviewShowsConflictsDependenciesDryRunAndVisibility() {
        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.findReviewByPair(SURVIVOR, DUPLICATE)).thenReturn(Optional.of(openReview()));
        ReviewDetail.Dependency dependency =
                new ReviewDetail.Dependency("dep-1", "ORDER", "order-9", true, false);
        when(repository.dependencies(DUPLICATE)).thenReturn(List.of(dependency,
                new ReviewDetail.Dependency("dep-2", "GRANT", "grant-2", false, false),
                new ReviewDetail.Dependency("dep-3", "PATIENT_LINK", "link-3", true, true)));
        when(repository.effectivePatientId(DUPLICATE)).thenReturn(DUPLICATE);

        ReviewDetail detail = service.review(SURVIVOR, DUPLICATE);
        assertEquals(SURVIVOR, detail.survivor().id());
        assertEquals(DUPLICATE, detail.duplicate().id());
        assertEquals(REVIEW, detail.review().id());
        assertTrue(detail.conflicts().stream().anyMatch(c -> "dob".equals(c.field())));
        assertTrue(detail.conflicts().stream().anyMatch(c -> "mrn".equals(c.field())));
        assertFalse(detail.conflicts().stream().anyMatch(c -> "normalizedName".equals(c.field())));
        assertEquals(3, detail.dependencies().size());
        assertTrue(detail.dryRun().contains("blockers=1"));
        assertEquals(DUPLICATE, detail.chartVisibilityId());
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_REVIEW", DUPLICATE, "SUCCESS");
    }

    @Test
    void openValidatesAndConcealsMissingIdentities() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.open("bad", DUPLICATE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.open(SURVIVOR, "bad")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.open(SURVIVOR, SURVIVOR)).status());
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(true);
        when(repository.activeRoles(REVIEWER_A)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.open(SURVIVOR, DUPLICATE)).status());
        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.open(SURVIVOR, DUPLICATE)).status());
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.open(SURVIVOR, DUPLICATE)).status());
    }

    @Test
    void openRejectsConsolidatedPairAndReturnsExistingReview() {
        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        Consolidation done = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_A, REVIEWER_A, "duplicate", null);
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(done));
        when(repository.findReviewByPair(SURVIVOR, DUPLICATE)).thenReturn(Optional.of(openReview()));
        assertEquals(409, assertThrows(ApiException.class, () -> service.open(SURVIVOR, DUPLICATE)).status());

        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        DuplicateReview result = service.open(SURVIVOR, DUPLICATE);
        assertEquals(REVIEW, result.id());
        verify(repository, never()).insertReview(anyString(), anyString(), anyString(), anyString());
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_REVIEW_OPEN", REVIEW, "SUCCESS");
    }

    @Test
    void openInsertsReviewOwnedByAuthenticatedActor() {
        authorized(REVIEWER_A, "privacy_auditor");
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.findReviewByPair(SURVIVOR, DUPLICATE)).thenReturn(Optional.empty());
        when(repository.insertReview(ORG, SURVIVOR, DUPLICATE, REVIEWER_A)).thenReturn(1);
        when(repository.findReviewByPair(SURVIVOR, DUPLICATE))
                .thenReturn(Optional.empty(), Optional.of(openReview()));

        DuplicateReview result = service.open(SURVIVOR, DUPLICATE);
        assertEquals(REVIEW, result.id());
        assertEquals(REVIEWER_A, result.openedBySubject());
        verify(repository).insertReview(ORG, SURVIVOR, DUPLICATE, REVIEWER_A);
    }

    @Test
    void consolidateValidatesInputsAndAuthorization() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.consolidate("bad", 1, "reason")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 1, "!")).status());
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 1, "reason")).status());
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(true);
        when(repository.activeRoles(REVIEWER_A)).thenReturn(List.of("reception"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 1, "reason")).status());
    }

    @Test
    void consolidateEnforcesSecondReviewerAndStaleVersion() {
        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());

        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        assertEquals(409, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "DENIED");

        bind(REVIEWER_B);
        authorized(REVIEWER_B,"tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        assertEquals(412, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 1, "reason")).status());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "STALE");
    }

    @Test
    void consolidateBlocksAmbiguousDependenciesWithoutPartialWrite() {
        bind(REVIEWER_B);
        authorized(REVIEWER_B,"tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.hasUnresolvedBlockers(DUPLICATE)).thenReturn(1);
        assertEquals(409, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());
        verify(repository, never()).insertAssociation(anyString(), anyString(), anyString(), anyInt(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).insertConsolidation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "BLOCKED");
    }

    @Test
    void consolidateRejectsAlreadyConsolidatedAndMissingIdentities() {
        bind(REVIEWER_B);
        authorized(REVIEWER_B,"tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        Consolidation done = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_A, REVIEWER_A, "duplicate", null);
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(done));
        assertEquals(409, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());

        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());
    }

    @Test
    void consolidateLocksBothRecordsPreservesEvidenceAndAudits() {
        bind(REVIEWER_B);
        authorized(REVIEWER_B,"privacy_auditor");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.hasUnresolvedBlockers(DUPLICATE)).thenReturn(0);
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.insertAssociation("assoc-1", ORG, DUPLICATE, 2, SURVIVOR, "ACTIVE", "MRN-2", "CLINIC-B",
                "[{\"type\":\"MRN\"}]", "[]", "reason")).thenReturn(1);
        when(repository.insertConsolidation("cons-1", ORG, REVIEW, "assoc-1", SURVIVOR, DUPLICATE, "CONSOLIDATED",
                REVIEWER_B, REVIEWER_A, "reason", null)).thenReturn(1);
        when(repository.newId()).thenReturn("assoc-1", "cons-1");

        Consolidation result = service.consolidate(REVIEW, 2, "reason");
        assertEquals("cons-1", result.id());
        assertEquals("CONSOLIDATED", result.status());
        assertEquals(REVIEWER_B, result.approverSubject());
        assertEquals(REVIEWER_A, result.openedBySubject());
        verify(repository).lockPair(SURVIVOR, DUPLICATE);
        verify(repository).insertAssociation("assoc-1", ORG, DUPLICATE, 2, SURVIVOR, "ACTIVE", "MRN-2", "CLINIC-B",
                "[{\"type\":\"MRN\"}]", "[]", "reason");
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "SUCCESS");
    }

    @Test
    void consolidateFailsClosedWhenLockIsIncomplete() {
        bind(REVIEWER_B);
        authorized(REVIEWER_B,"tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(1);
        assertEquals(409, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());
        verify(repository, never()).insertConsolidation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void consolidateMapsConcurrentConflict() {
        bind(REVIEWER_B);
        authorized(REVIEWER_B,"tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.newId()).thenReturn("assoc-1", "cons-1");
        when(repository.insertConsolidation(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("concurrent"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2, "reason")).status());
    }

    @Test
    void correctRequiresAdminAndValidInputs() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct("bad", 1, "reason")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, " ")).status());
        when(repository.hasActiveMembership(REVIEWER_A)).thenReturn(true);
        when(repository.activeRoles(REVIEWER_A)).thenReturn(List.of("privacy_auditor"));
        assertEquals(403, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, "reason")).status());
        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, "reason")).status());
    }

    @Test
    void correctRejectsStaleAndNonConsolidatedStates() {
        authorized(REVIEWER_A, "tenant_admin");
        Consolidation done = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_A, REVIEWER_B, "duplicate", null);
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(done));
        assertEquals(412, assertThrows(ApiException.class, () -> service.correct(REVIEW, 2, "reason")).status());
        Consolidation corrected = new Consolidation("cons-2", REVIEW, SURVIVOR, DUPLICATE, 2,
                "CORRECTED", REVIEWER_A, REVIEWER_B, "duplicate", "reason");
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(corrected));
        assertEquals(409, assertThrows(ApiException.class, () -> service.correct(REVIEW, 2, "reason")).status());
    }

    @Test
    void correctReversesAssociationWithAuditedReason() {
        authorized(REVIEWER_A, "tenant_admin");
        Consolidation done = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "duplicate", null);
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(done));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.newId()).thenReturn("assoc-2", "cons-2");
        when(repository.insertAssociation(eq("assoc-2"), eq(ORG), eq(DUPLICATE), eq(2), eq(SURVIVOR), eq("REVERSED"),
                anyString(), anyString(), anyString(), anyString(), eq("wrong patient"))).thenReturn(1);
        when(repository.insertConsolidation(eq("cons-2"), eq(ORG), eq(REVIEW), eq("assoc-2"), eq(SURVIVOR),
                eq(DUPLICATE), eq("CORRECTED"), eq(REVIEWER_A), eq(REVIEWER_B), eq("duplicate"),
                eq("wrong patient"))).thenReturn(1);

        Consolidation result = service.correct(REVIEW, 1, "wrong patient");
        assertEquals("CONSOLIDATED", done.status());
        assertEquals("CORRECTED", result.status());
        assertEquals("cons-2", result.id());
        assertEquals(2, result.version());
        assertEquals("wrong patient", result.correctionReason());
        verify(repository).insertAssociation(eq("assoc-2"), eq(ORG), eq(DUPLICATE), eq(2), eq(SURVIVOR),
                eq("REVERSED"), anyString(), anyString(), anyString(), anyString(), eq("wrong patient"));
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATION_CORRECT", REVIEW, "SUCCESS");
    }

    @Test
    void coversRareFailureAndNullInputBranches() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.review(null, DUPLICATE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.review(SURVIVOR, SURVIVOR)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.consolidate(null, 1, "reason")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 1, null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, null)).status());

        authorized(REVIEWER_A, "tenant_admin");
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.findReviewByPair(SURVIVOR, DUPLICATE)).thenReturn(Optional.empty());
        when(repository.insertReview(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.open(SURVIVOR, DUPLICATE)).status());

        doReturn(1).when(repository).insertReview(anyString(), anyString(), anyString(), anyString());
        assertNull(service.open(SURVIVOR, DUPLICATE));
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_REVIEW_OPEN", DUPLICATE, "SUCCESS");

        Consolidation done = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "duplicate", null);
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(done));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, "reason")).status());

        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(1);
        assertEquals(409, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, "reason")).status());

        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.newId()).thenReturn("assoc-x", "cons-x");
        when(repository.insertConsolidation(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.correct(REVIEW, 1, "reason")).status());
    }

    @Test
    void repositoryFailsClosedOnNullScalarResults() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        DuplicateRepository repositoryUnderTest = new DuplicateRepository(jdbc);
        when(jdbc.queryForObject(anyString(), any(Map.class), eq(Integer.class))).thenReturn(null);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(null);
        assertEquals(0, repositoryUnderTest.hasUnresolvedBlockers(DUPLICATE));
        assertEquals(0, repositoryUnderTest.lockPair(SURVIVOR, DUPLICATE));
    }

    @Test
    void valueRecordsExposeAllParts() {
        DuplicatePair pair = new DuplicatePair(SURVIVOR, "A", DUPLICATE, "B");
        assertEquals(SURVIVOR, pair.survivorId());
        assertEquals("A", pair.survivorName());
        assertEquals(DUPLICATE, pair.duplicateId());
        assertEquals("B", pair.duplicateName());
        assertEquals(pair, new DuplicatePair(SURVIVOR, "A", DUPLICATE, "B"));
        assertEquals(pair.hashCode(), new DuplicatePair(SURVIVOR, "A", DUPLICATE, "B").hashCode());
        assertEquals(pair.toString(), new DuplicatePair(SURVIVOR, "A", DUPLICATE, "B").toString());

        DuplicateReview review = openReview();
        assertEquals(REVIEW, review.id());
        assertEquals(SURVIVOR, review.survivorId());
        assertEquals(DUPLICATE, review.duplicateId());
        assertEquals(REVIEWER_A, review.openedBySubject());
        assertEquals(1, review.version());
        assertEquals("OPEN", review.status());
        assertEquals(review, new DuplicateReview(REVIEW, SURVIVOR, DUPLICATE, REVIEWER_A, 1, "OPEN"));
        assertEquals(review.hashCode(), new DuplicateReview(REVIEW, SURVIVOR, DUPLICATE, REVIEWER_A, 1, "OPEN").hashCode());
        assertEquals(review.toString(), new DuplicateReview(REVIEW, SURVIVOR, DUPLICATE, REVIEWER_A, 1, "OPEN").toString());

        ReviewDetail.Conflict conflict = new ReviewDetail.Conflict("dob", "1988", "1989");
        assertEquals("dob", conflict.field());
        assertEquals("1988", conflict.survivorValue());
        assertEquals("1989", conflict.duplicateValue());
        assertEquals(conflict, new ReviewDetail.Conflict("dob", "1988", "1989"));
        assertEquals(conflict.hashCode(), new ReviewDetail.Conflict("dob", "1988", "1989").hashCode());
        assertEquals(conflict.toString(), new ReviewDetail.Conflict("dob", "1988", "1989").toString());

        ReviewDetail.Dependency dependency = new ReviewDetail.Dependency("dep", "ORDER", "ref", true, false);
        assertEquals("dep", dependency.id());
        assertEquals("ORDER", dependency.kind());
        assertEquals("ref", dependency.reference());
        assertTrue(dependency.ambiguous());
        assertFalse(dependency.resolved());
        assertEquals(dependency, new ReviewDetail.Dependency("dep", "ORDER", "ref", true, false));
        assertEquals(dependency.hashCode(), new ReviewDetail.Dependency("dep", "ORDER", "ref", true, false).hashCode());
        assertEquals(dependency.toString(), new ReviewDetail.Dependency("dep", "ORDER", "ref", true, false).toString());

        ReviewDetail detail = new ReviewDetail(review, survivor(), duplicate(), List.of(conflict),
                List.of(dependency), "dry", DUPLICATE);
        assertEquals(review, detail.review());
        assertEquals(SURVIVOR, detail.survivor().id());
        assertEquals(DUPLICATE, detail.duplicate().id());
        assertEquals(1, detail.conflicts().size());
        assertEquals(1, detail.dependencies().size());
        assertEquals("dry", detail.dryRun());
        assertEquals(DUPLICATE, detail.chartVisibilityId());
        assertThrows(UnsupportedOperationException.class, () -> detail.conflicts().clear());
        assertThrows(UnsupportedOperationException.class, () -> detail.dependencies().clear());
        assertEquals(detail, new ReviewDetail(review, survivor(), duplicate(), List.of(conflict),
                List.of(dependency), "dry", DUPLICATE));
        assertEquals(detail.hashCode(), new ReviewDetail(review, survivor(), duplicate(), List.of(conflict),
                List.of(dependency), "dry", DUPLICATE).hashCode());
        assertEquals(detail.toString(), new ReviewDetail(review, survivor(), duplicate(), List.of(conflict),
                List.of(dependency), "dry", DUPLICATE).toString());

        Consolidation consolidation = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "reason", null);
        assertEquals("cons-1", consolidation.id());
        assertEquals(REVIEW, consolidation.reviewId());
        assertEquals(SURVIVOR, consolidation.survivorId());
        assertEquals(DUPLICATE, consolidation.duplicateId());
        assertEquals(1, consolidation.version());
        assertEquals("CONSOLIDATED", consolidation.status());
        assertEquals(REVIEWER_B, consolidation.approverSubject());
        assertEquals(REVIEWER_A, consolidation.openedBySubject());
        assertEquals("reason", consolidation.reason());
        assertEquals(null, consolidation.correctionReason());
        assertNotEquals(consolidation, new Consolidation("cons-2", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "reason", null));
        assertEquals(consolidation, new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "reason", null));
        assertEquals(consolidation.hashCode(), new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "reason", null).hashCode());
        assertEquals(consolidation.toString(), new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_B, REVIEWER_A, "reason", null).toString());
    }

    @Test
    void controllerBindsRequestsToDomainCommands() {
        ConsolidationController controller = new ConsolidationController(service);
        bind(REVIEWER_B);
        authorized(REVIEWER_B,"tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(openReview()));
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.newId()).thenReturn("assoc-1", "cons-1");

        Consolidation result = controller.consolidate(REVIEW, "\"2\"",
                new ConsolidationController.ReasonRequest("reason"));
        assertEquals("cons-1", result.id());
        assertTrue(controller.worklist().isEmpty());

        when(repository.findReviewByPair(SURVIVOR, DUPLICATE)).thenReturn(Optional.of(openReview()));
        assertEquals(REVIEW, controller.open(
                new ConsolidationController.OpenRequest(SURVIVOR, DUPLICATE)).getBody().id());
        when(repository.dependencies(DUPLICATE)).thenReturn(List.of());
        when(repository.effectivePatientId(DUPLICATE)).thenReturn(DUPLICATE);
        assertEquals(SURVIVOR, controller.review(SURVIVOR, DUPLICATE).survivor().id());

        Consolidation done = new Consolidation("cons-1", REVIEW, SURVIVOR, DUPLICATE, 1,
                "CONSOLIDATED", REVIEWER_A, REVIEWER_B, "duplicate", null);
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.of(done));
        when(repository.newId()).thenReturn("assoc-2", "cons-2");
        Consolidation corrected = controller.correct(REVIEW, "\"1\"",
                new ConsolidationController.ReasonRequest("correct reason"));
        assertEquals("CORRECTED", corrected.status());
        assertEquals("cons-2", corrected.id());

        ConsolidationController.OpenRequest open = new ConsolidationController.OpenRequest(SURVIVOR, DUPLICATE);
        assertEquals(SURVIVOR, open.survivorId());
        assertEquals(DUPLICATE, open.duplicateId());
        assertEquals(open, new ConsolidationController.OpenRequest(SURVIVOR, DUPLICATE));
        assertEquals(open.hashCode(), new ConsolidationController.OpenRequest(SURVIVOR, DUPLICATE).hashCode());
        assertEquals(open.toString(), new ConsolidationController.OpenRequest(SURVIVOR, DUPLICATE).toString());

        ConsolidationController.ReasonRequest reason = new ConsolidationController.ReasonRequest("reason");
        assertEquals("reason", reason.reason());
        assertEquals(reason, new ConsolidationController.ReasonRequest("reason"));
        assertEquals(reason.hashCode(), new ConsolidationController.ReasonRequest("reason").hashCode());
        assertEquals(reason.toString(), new ConsolidationController.ReasonRequest("reason").toString());
    }
}
