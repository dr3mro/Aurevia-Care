package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.patient.Patient;
import com.aurevia.patient.consolidation.Consolidation;
import com.aurevia.patient.consolidation.ConsolidationService;
import com.aurevia.patient.consolidation.DuplicatePolicy;
import com.aurevia.patient.consolidation.DuplicateRepository;
import com.aurevia.patient.consolidation.DuplicateReview;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-04 clause 3 proof: a consolidation is approved by a second authorized
 * reviewer, distinct from the reviewer who opened the ticket. The approver's
 * active membership and reviewer role are re-derived on the request, and the
 * append-only event keeps both the approver and the original opener. Synthetic
 * fixtures and a mocked repository boundary only; no patient data is used.
 */
class SecondAuthorizedReviewerApprovesConsolidationTest {

    private static final String ORG = "33333333-3333-3333-3333-333333333333";
    private static final String SURVIVOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DUPLICATE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVIEW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OPENER = "reviewer-opener";
    private static final String APPROVER = "reviewer-approver";
    private static final String CLERK = "reception-clerk";

    private DuplicateRepository repository;
    private ConsolidationService service;

    /** Run the transaction callback synchronously so the assertions see the same context. */
    @BeforeEach
    void setUp() {
        repository = mock(DuplicateRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ConsolidationService(repository, new DuplicatePolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        // A patient's chart resolves to itself until a consolidation moves it.
        when(repository.effectivePatientId(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind the active tenant context to a synthetic subject. */
    private void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "IDENTITY_ADMIN", "corr-second-reviewer"));
    }

    /** Grant a synthetic subject active membership and the given role keys. */
    private void authorized(String subject, String... roles) {
        when(repository.hasActiveMembership(subject)).thenReturn(true);
        when(repository.activeRoles(subject)).thenReturn(List.of(roles));
    }

    /** The first reviewer's surviving identity, with a confirmed issuer-scoped MRN. */
    private Patient survivor() {
        return new Patient(SURVIVOR, 1, "MRN-S", "CLINIC-A", "Synthetic One", "synthetic one",
                "1990-01-01", null, "en", "[]", "[]", "[]");
    }

    /** The suspected duplicate identity, with a different issuer-scoped MRN. */
    private Patient duplicate() {
        return new Patient(DUPLICATE, 2, "MRN-D", "CLINIC-B", "Synthetic  One", "synthetic one",
                null, "not_disclosed", "ar", "[]", "[]", "[]");
    }

    /** A ticket opened by the first reviewer, still awaiting a second approval. */
    private DuplicateReview reviewOpenedByFirstReviewer() {
        return new DuplicateReview(REVIEW, SURVIVOR, DUPLICATE, OPENER, 1, "OPEN");
    }

    /** Two distinct issuer-scoped identifiers, one taken from each identity. */
    private List<String> confirmedIdentifiers() {
        return List.of("MRN-S@CLINIC-A", "MRN-D@CLINIC-B");
    }

    /** Stub every precondition so authorization is the only thing under test. */
    private void readyForApproval() {
        authorized(APPROVER, "privacy_auditor");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(reviewOpenedByFirstReviewer()));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        when(repository.findPatient(SURVIVOR)).thenReturn(Optional.of(survivor()));
        when(repository.findPatient(DUPLICATE)).thenReturn(Optional.of(duplicate()));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.hasUnresolvedBlockers(DUPLICATE)).thenReturn(0);
        when(repository.newId()).thenReturn("assoc-second", "cons-second");
    }

    /** Assert that no association or consolidation event was appended. */
    private void verifyNothingAppended() {
        verify(repository, never()).insertAssociation(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).insertConsolidation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void secondAuthorizedReviewerApprovesAndBothReviewersAreRecorded() {
        bind(APPROVER);
        readyForApproval();

        Consolidation result = service.consolidate(REVIEW, 2, "merge duplicate",
                confirmedIdentifiers(), null);

        assertEquals("cons-second", result.id());
        assertEquals("CONSOLIDATED", result.status());
        assertEquals(APPROVER, result.approverSubject());
        assertEquals(OPENER, result.openedBySubject());
        verify(repository).lockPair(SURVIVOR, DUPLICATE);
        verify(repository).insertConsolidation("cons-second", ORG, REVIEW, "assoc-second", SURVIVOR,
                DUPLICATE, "CONSOLIDATED", APPROVER, OPENER, "merge duplicate", null);
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "SUCCESS");
        verify(repository, never()).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "DENIED");
    }

    @Test
    void reviewerWhoOpenedTheTicketCannotApproveIt() {
        bind(OPENER);
        authorized(OPENER, "tenant_admin");
        when(repository.findReviewById(REVIEW)).thenReturn(Optional.of(reviewOpenedByFirstReviewer()));

        ApiException denial = assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2,
                "merge duplicate", confirmedIdentifiers(), null));

        assertEquals(409, denial.status());
        assertEquals("SECOND_REVIEWER_REQUIRED", denial.code());
        assertTrue(denial.getMessage().contains("different authorized reviewer"));
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "DENIED");
        verify(repository, never()).lockPair(anyString(), anyString());
        verifyNothingAppended();
    }

    @Test
    void unauthorizedRoleCannotApproveAnotherReviewersTicket() {
        bind(CLERK);
        authorized(CLERK, "reception");

        ApiException denial = assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2,
                "merge duplicate", confirmedIdentifiers(), null));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "DENIED");
        verify(repository, never()).findReviewById(anyString());
        verifyNothingAppended();
    }

    @Test
    void subjectWithoutActiveMembershipCannotApprove() {
        bind(APPROVER);
        when(repository.hasActiveMembership(APPROVER)).thenReturn(false);

        ApiException denial = assertThrows(ApiException.class, () -> service.consolidate(REVIEW, 2,
                "merge duplicate", confirmedIdentifiers(), null));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATE", REVIEW, "DENIED");
        verifyNothingAppended();
    }
}
