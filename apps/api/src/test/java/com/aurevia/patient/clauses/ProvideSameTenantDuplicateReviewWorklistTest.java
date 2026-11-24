package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.patient.consolidation.ConsolidationService;
import com.aurevia.patient.consolidation.DuplicatePolicy;
import com.aurevia.patient.consolidation.DuplicateRepository;
import com.aurevia.patient.consolidation.DuplicateRepository.DuplicatePair;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-04 clause 1 proof: the duplicate review worklist surfaces only the
 * same-tenant likely-duplicate pairs returned by the tenant-bounded repository,
 * and only to a reviewer with active membership and a review-capable role.
 *
 * <p>The tenant boundary itself is the row-level-security fold behind
 * {@code DuplicateRepository.likelyPairs()}; this test proves the service
 * returns exactly that bounded result without widening it, and refuses callers
 * who lack membership or the review permission. Synthetic fixtures only.</p>
 */
class ProvideSameTenantDuplicateReviewWorklistTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String REVIEWER = "reviewer-clause-one";
    private static final String SURVIVOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DUPLICATE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private DuplicateRepository repository;
    private ConsolidationService service;

    /** Bind a fresh tenant context before every test and clear it afterwards. */
    @BeforeEach
    void setUp() {
        repository = mock(DuplicateRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ConsolidationService(repository, new DuplicatePolicy(), transactions);
        // Run the callback directly so the transaction body can be observed.
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, REVIEWER, "IDENTITY_ADMIN", "corr-clause-one"));
    }

    /** Remove the thread-local tenant context so it cannot leak into other tests. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Grant a subject active membership and the supplied role keys. */
    private void authorized(String subject, String... roles) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roles));
    }

    /** Build a synthetic same-tenant duplicate candidate pair. */
    private DuplicatePair pair() {
        return new DuplicatePair(SURVIVOR, "Amina Khan", DUPLICATE, "Amina Khan");
    }

    /** A review-capable subject sees exactly the tenant-bounded candidate pairs. */
    @Test
    void worklistListsSameTenantPairsForAnAuthorizedReviewer() {
        authorized(REVIEWER, "privacy_auditor");
        DuplicatePair expected = pair();
        when(repository.likelyPairs()).thenReturn(List.of(expected));

        List<DuplicatePair> pairs = service.worklist();

        assertEquals(List.of(expected), pairs,
                "the worklist must surface only the pairs bounded to the active tenant");
        assertEquals(DUPLICATE, pairs.get(0).duplicateId());
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_WORKLIST", "LIST", "SUCCESS");
    }

    /** An empty tenant-bounded result produces an empty, successfully audited worklist. */
    @Test
    void worklistIsEmptyWhenTheTenantHasNoCandidatePairs() {
        authorized(REVIEWER, "tenant_admin");
        when(repository.likelyPairs()).thenReturn(List.of());

        assertTrue(service.worklist().isEmpty());
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_WORKLIST", "LIST", "SUCCESS");
    }

    /** A subject without active membership must not reach the worklist. */
    @Test
    void worklistRefusesASubjectWithoutActiveMembership() {
        when(repository.hasActiveMembership(REVIEWER)).thenReturn(false);

        ApiException denial = assertThrows(ApiException.class, service::worklist);

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).likelyPairs();
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_WORKLIST", "LIST", "DENIED");
    }

    /** An active subject whose role cannot review duplicates must be denied. */
    @Test
    void worklistRefusesAnActiveRoleWithoutReviewPermission() {
        authorized(REVIEWER, "reception");

        ApiException denial = assertThrows(ApiException.class, service::worklist);

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository, never()).likelyPairs();
        verify(repository).appendAudit(TenantContext.current(), "DUPLICATE_WORKLIST", "LIST", "DENIED");
    }
}
