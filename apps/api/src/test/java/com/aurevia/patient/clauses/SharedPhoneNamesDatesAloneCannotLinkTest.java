package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.ActionPolicy;
import com.aurevia.patient.Patient;
import com.aurevia.patient.PatientRepository;
import com.aurevia.patient.PatientService;
import com.aurevia.patient.PatientView;
import com.aurevia.patient.consolidation.Consolidation;
import com.aurevia.patient.consolidation.ConsolidationService;
import com.aurevia.patient.consolidation.DuplicatePolicy;
import com.aurevia.patient.consolidation.DuplicateRepository;
import com.aurevia.patient.consolidation.DuplicateReview;
import com.aurevia.patient.identity.ChartLink;
import com.aurevia.patient.identity.IdentityActionPolicy;
import com.aurevia.patient.identity.IdentityProofingRepository;
import com.aurevia.patient.identity.IdentityProofingService;
import com.aurevia.patient.identity.InvitationRecord;
import com.aurevia.tenant.TenantContext;

/**
 * IAM-03 clause 7 proof: shared contact details, names and dates of birth never
 * link patient records or patient accounts. Registration keeps them as separate
 * issuer-scoped records and surfaces a name/date match only as a duplicate warning;
 * consolidation needs two issuer-scoped identifiers confirmed from the two records;
 * account-to-chart linking needs a single-use invitation bound to the caller's
 * verified account. Synthetic fixtures only.
 */
class SharedPhoneNamesDatesAloneCannotLinkTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String REGISTRAR = "registrar-a";
    private static final String REVIEWER = "reviewer-b";
    private static final String PATIENT_ONE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_TWO = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVIEW = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CHART = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    /** The same phone, name and date of birth belong to both synthetic identities. */
    private static final String SHARED_NAME = "Amina Khan";
    private static final String SHARED_NORMALIZED = "amina khan";
    private static final String SHARED_DOB = "1988-03-03";
    private static final String SHARED_CONTACTS = "{\"phone\":\"+15551234567\"}";
    private static final String TOKEN = "raw-token-value";

    private PatientRepository patientRepository;
    private DuplicateRepository duplicateRepository;
    private IdentityProofingRepository identityRepository;
    private PatientService patientService;
    private ConsolidationService consolidationService;
    private IdentityProofingService identityService;

    /** Direct callbacks keep these boundary tests deterministic without a database. */
    private static final class PassthroughTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // no durable state in this unit tier
        }

        @Override
        public void rollback(TransactionStatus status) {
            // no durable state in this unit tier
        }
    }

    @BeforeEach
    void setUp() {
        patientRepository = mock(PatientRepository.class);
        duplicateRepository = mock(DuplicateRepository.class);
        identityRepository = mock(IdentityProofingRepository.class);
        TransactionTemplate transactions = new TransactionTemplate(new PassthroughTransactionManager());
        patientService = new PatientService(patientRepository, new ActionPolicy(), transactions);
        consolidationService = new ConsolidationService(duplicateRepository, new DuplicatePolicy(), transactions);
        identityService = new IdentityProofingService(identityRepository, new IdentityActionPolicy(), transactions);
        bind(REGISTRAR);
        // Production default: a chart resolves to its own record until consolidation.
        when(duplicateRepository.effectivePatientId(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void bind(String subject) {
        TenantContext.set(new TenantContext(ORG, subject, "PATIENT_IDENTITY", "corr-clause-seven"));
    }

    private void canRegister() {
        when(patientRepository.hasActiveMembership(REGISTRAR)).thenReturn(true);
        when(patientRepository.activeRoles(REGISTRAR)).thenReturn(List.of("reception"));
    }

    private void canApprove(String subject) {
        when(duplicateRepository.hasActiveMembership(subject)).thenReturn(true);
        when(duplicateRepository.activeRoles(subject)).thenReturn(List.of("tenant_admin"));
    }

    private static Patient patient(String id, int version, String mrn, String issuer) {
        return new Patient(id, version, mrn, issuer, SHARED_NAME, SHARED_NORMALIZED, SHARED_DOB,
                null, "en", SHARED_CONTACTS, "[]", "[]");
    }

    /** An open review of two records with identical phone, name and date of birth. */
    private void preparedReview(String approver) {
        canApprove(approver);
        when(duplicateRepository.findReviewById(REVIEW)).thenReturn(Optional.of(
                new DuplicateReview(REVIEW, PATIENT_ONE, PATIENT_TWO, "reviewer-a", 1, "OPEN")));
        when(duplicateRepository.findCurrentConsolidation(REVIEW)).thenReturn(Optional.empty());
        when(duplicateRepository.lockPair(PATIENT_ONE, PATIENT_TWO)).thenReturn(2);
        when(duplicateRepository.findPatient(PATIENT_ONE))
                .thenReturn(Optional.of(patient(PATIENT_ONE, 1, "MRN-1", "CLINIC-A")));
        when(duplicateRepository.findPatient(PATIENT_TWO))
                .thenReturn(Optional.of(patient(PATIENT_TWO, 2, "MRN-2", "CLINIC-B")));
        when(duplicateRepository.hasUnresolvedBlockers(PATIENT_TWO)).thenReturn(0);
    }

    private static InvitationRecord invitation(String id, String subject) {
        return new InvitationRecord(id, subject, CHART, "LINK", "2026-11-20T12:00:00Z", false);
    }

    @Test
    void sharedPhoneNameAndDobKeepTwoIssuerScopedRecordsAndOnlyWarn() {
        canRegister();
        patientService.register(SHARED_NAME, "MRN-1", "CLINIC-A", SHARED_DOB, null, "en",
                SHARED_CONTACTS, "[]", "[]");
        patientService.register(SHARED_NAME, "MRN-2", "CLINIC-B", SHARED_DOB, null, "en",
                SHARED_CONTACTS, "[]", "[]");

        ArgumentCaptor<String> insertedIds = ArgumentCaptor.forClass(String.class);
        verify(patientRepository, times(2)).insert(eq(ORG), insertedIds.capture(), anyString(), anyString(),
                eq(SHARED_NAME), eq(SHARED_NORMALIZED), eq(SHARED_DOB), isNull(), eq("en"),
                eq(SHARED_CONTACTS), eq("[]"), eq("[]"));
        assertNotEquals(insertedIds.getAllValues().get(0), insertedIds.getAllValues().get(1),
                "the same phone, name and dob must not collapse two records into one");
        verify(patientRepository, times(2)).mrnExists(anyString(), anyString());

        when(patientRepository.findById(PATIENT_ONE))
                .thenReturn(Optional.of(patient(PATIENT_ONE, 1, "MRN-1", "CLINIC-A")));
        when(patientRepository.likelyDuplicates(SHARED_NORMALIZED, SHARED_DOB, PATIENT_ONE))
                .thenReturn(List.of(patient(PATIENT_TWO, 1, "MRN-2", "CLINIC-B")));
        PatientView view = patientService.get(PATIENT_ONE);

        assertEquals(PATIENT_ONE, view.patient().id());
        assertEquals(1, view.duplicates().size(), "a matching name and dob is a warning, not a link");
        assertEquals(PATIENT_TWO, view.duplicates().get(0).id());
        assertNotEquals(view.patient().id(), view.duplicates().get(0).id());
    }

    @Test
    void twoIssuerScopedIdentifiersOneFromEachRecordAllowConsolidationDespiteSharedDemographics() {
        bind(REVIEWER);
        preparedReview(REVIEWER);
        when(duplicateRepository.newId()).thenReturn("association-1", "consolidation-1");

        Consolidation consolidation = consolidationService.consolidate(REVIEW, 2, "confirmed same person",
                List.of("MRN-1@CLINIC-A", "MRN-2@CLINIC-B"), null);

        assertEquals("CONSOLIDATED", consolidation.status());
        verify(duplicateRepository).insertAssociation(eq("association-1"), eq(ORG), eq(PATIENT_TWO), eq(2),
                eq(PATIENT_ONE), eq("ACTIVE"), eq("MRN-2"), eq("CLINIC-B"), eq("[]"), eq("[]"), anyString());
        verify(duplicateRepository).appendAudit(any(), eq("CONSOLIDATION_IDENTIFIER_CONFIRM"), eq(REVIEW),
                eq("CONFIRMED:MRN-1@CLINIC-A;MRN-2@CLINIC-B"));
    }

    @Test
    void sharedPhoneNameAndDobAreRefusedAsConsolidationIdentifierConfirmation() {
        bind(REVIEWER);
        preparedReview(REVIEWER);
        List<String> sharedAttributes = List.of("15551234567@PHONE", "Amina.Khan@NAME", "1988-03-03@DOB");

        ApiException denial = assertThrows(ApiException.class, () -> consolidationService.consolidate(
                REVIEW, 2, "same phone name and dob", sharedAttributes, null));

        assertEquals(422, denial.status());
        assertEquals("IDENTIFIER_MISMATCH", denial.code());
        verify(duplicateRepository, never()).insertAssociation(any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any());
        verify(duplicateRepository, never()).insertConsolidation(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(duplicateRepository).appendAudit(any(), eq("CONSOLIDATION_IDENTIFIER_CONFIRM"), eq(REVIEW),
                eq("DENIED"));
    }

    @Test
    void invitationBoundToAVerifiedAccountLinksOnlyItsOwnChart() {
        bind("amina.one");
        when(identityRepository.accountExists("amina.one")).thenReturn(true);
        when(identityRepository.findInvitation(TOKEN)).thenReturn(Optional.of(invitation("inv-1", "amina.one")));
        when(identityRepository.consumeInvitation("inv-1")).thenReturn(true);
        when(identityRepository.currentLink("amina.one", CHART)).thenReturn(Optional.of(
                new ChartLink("link-1", "amina.one", CHART, "linked", "INVITATION", null,
                        "2026-11-20T12:00:00Z")));

        ChartLink link = identityService.linkWithInvitation(TOKEN);

        assertEquals("linked", link.event(), "the verified link is the current event");
        verify(identityRepository).currentLink("amina.one", CHART);
        verify(identityRepository).insertLink(eq(ORG), anyString(), eq("amina.one"), eq(CHART),
                eq("linked"), eq("INVITATION"), isNull());
        verify(identityRepository).appendAudit(any(), eq("CHART_LINK"), eq(CHART), eq("SUCCESS"));
    }

    @Test
    void matchingPhoneNameAndDobCannotLetAnotherAccountLinkOrAccessTheChart() {
        // Both accounts describe the same synthetic person by phone, name and dob;
        // only the subject and the invitation binding differ.
        bind("amina.two");
        when(identityRepository.accountExists("amina.two")).thenReturn(true);
        when(identityRepository.findInvitation(TOKEN)).thenReturn(Optional.of(invitation("inv-2", "amina.one")));

        ApiException denial = assertThrows(ApiException.class, () -> identityService.linkWithInvitation(TOKEN));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("another account"));
        verify(identityRepository, never()).consumeInvitation(anyString());
        verify(identityRepository, never()).insertLink(any(), any(), any(), any(), any(), any(), any());
        verify(identityRepository).appendAudit(any(), eq("CHART_LINK"), eq("ME"), eq("DENIED"));
    }

    @Test
    void oneMatchingIdentifierPlusSharedPhoneAndDobCannotLinkRecords() {
        bind(REVIEWER);
        preparedReview(REVIEWER);
        // A caller may pad one real identifier with the demographics the two
        // records share; the second reference must still come from the other record.
        List<String> paddedConfirmation = List.of("MRN-1@CLINIC-A", "15551234567@PHONE", "1988-03-03@DOB");

        ApiException denial = assertThrows(ApiException.class, () -> consolidationService.consolidate(
                REVIEW, 2, "one identifier and shared demographics", paddedConfirmation, null));

        assertEquals(422, denial.status());
        assertEquals("IDENTIFIER_MISMATCH", denial.code());
        verify(duplicateRepository, never()).insertAssociation(any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any());
        verify(duplicateRepository, never()).insertConsolidation(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verify(duplicateRepository).appendAudit(any(), eq("CONSOLIDATION_IDENTIFIER_CONFIRM"), eq(REVIEW),
                eq("DENIED"));
    }
}
