package com.aurevia.smartclinic.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.patient.Patient;
import com.aurevia.smartclinic.patient.consolidation.Consolidation;
import com.aurevia.smartclinic.patient.consolidation.ConsolidationService;
import com.aurevia.smartclinic.patient.consolidation.DuplicatePolicy;
import com.aurevia.smartclinic.patient.consolidation.DuplicateRepository;
import com.aurevia.smartclinic.patient.consolidation.DuplicateReview;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * IAM-03 clause 6 proof: at a high-risk chart consolidation the caller must
 * either confirm two issuer-scoped identifiers taken from the two records, or,
 * when identifiers are unavailable, name one documented approved alternate
 * process. The two modes are mutually exclusive, the alternate is a bounded
 * coded reference, and the basis actually relied on is recorded in the audit
 * trail. All fixtures are synthetic; no real patient data or database is used.
 */
class DocumentApprovedAlternateProcessIdentifiersUnavailableTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String SURVIVOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DUPLICATE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVIEW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String OPENER = "reviewer-a";
    private static final String APPROVER = "reviewer-b";

    private DuplicateRepository repository;
    private ConsolidationService service;

    /**
     * Build the synthetic two-record review state that is ready for a second
     * reviewer to approve, with the approver bound as an active tenant admin.
     */
    @BeforeEach
    void setUp() {
        repository = mock(DuplicateRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        service = new ConsolidationService(repository, new DuplicatePolicy(), transactions);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        // A chart resolves to itself until a consolidation moves it.
        when(repository.effectivePatientId(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TenantContext.set(new TenantContext(ORG, APPROVER, "IDENTITY_ADMIN", "corr-clause-six"));
        when(repository.hasActiveMembership(APPROVER)).thenReturn(true);
        when(repository.activeRoles(APPROVER)).thenReturn(List.of("tenant_admin"));

        when(repository.findReviewById(REVIEW))
                .thenReturn(Optional.of(new DuplicateReview(REVIEW, SURVIVOR, DUPLICATE, OPENER, 1, "OPEN")));
        when(repository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        when(repository.findPatient(SURVIVOR))
                .thenReturn(Optional.of(patient(SURVIVOR, 1, "MRN-1", "CLINIC-A")));
        when(repository.findPatient(DUPLICATE))
                .thenReturn(Optional.of(patient(DUPLICATE, 2, "MRN-2", "CLINIC-B")));
        when(repository.lockPair(SURVIVOR, DUPLICATE)).thenReturn(2);
        when(repository.hasUnresolvedBlockers(DUPLICATE)).thenReturn(0);
        when(repository.newId()).thenReturn("assoc-1", "cons-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A synthetic issuer-scoped patient chart. */
    private static Patient patient(String id, int version, String mrn, String issuer) {
        return new Patient(id, version, mrn, issuer, "Amina Khan", "amina khan",
                "1988-03-03", null, "en", "{}", "[]", "[]");
    }

    /** Two distinct issuer-scoped identifiers, one from each record. */
    private static List<String> confirmedIdentifiers() {
        return List.of("MRN-1@CLINIC-A", "MRN-2@CLINIC-B");
    }

    /** No chart association or consolidation event is appended on a refusal. */
    private void assertNothingAppended() {
        verify(repository, never()).insertAssociation(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(repository, never()).insertConsolidation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void documentsAlternateProcessWhenIdentifiersCannotBeConfirmed() {
        Consolidation result = service.consolidate(REVIEW, 2, "reason", null, "site_witness");

        assertEquals("CONSOLIDATED", result.status());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATION_IDENTIFIER_CONFIRM",
                REVIEW, "ALTERNATE:site_witness");
        // The final correctionReason argument is null on a first consolidation.
        verify(repository).insertConsolidation(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void refusesAlternateProcessThatIsNotABoundedCodedReference() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.consolidate(REVIEW, 2, "reason", null, "!!"));

        assertEquals(422, thrown.status());
        assertEquals("INVALID_ALTERNATE_PROCESS", thrown.code());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATION_IDENTIFIER_CONFIRM", REVIEW, "DENIED");
        assertNothingAppended();
    }

    @Test
    void refusesAlternateProcessCombinedWithConfirmedIdentifiers() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.consolidate(REVIEW, 2, "reason", confirmedIdentifiers(), "site_witness"));

        assertEquals(422, thrown.status());
        assertEquals("CONFLICTING_CONFIRMATION", thrown.code());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATION_IDENTIFIER_CONFIRM", REVIEW, "DENIED");
        assertNothingAppended();
    }

    @Test
    void refusesConsolidationWhenIdentifiersAreUnavailableAndNoAlternateProcessIsDocumented() {
        ApiException thrown = assertThrows(ApiException.class,
                () -> service.consolidate(REVIEW, 2, "reason", List.of(), null));

        assertEquals(422, thrown.status());
        assertEquals("IDENTIFIER_CONFIRMATION_REQUIRED", thrown.code());
        verify(repository).appendAudit(TenantContext.current(), "CONSOLIDATION_IDENTIFIER_CONFIRM", REVIEW, "DENIED");
        assertNothingAppended();
    }
}
