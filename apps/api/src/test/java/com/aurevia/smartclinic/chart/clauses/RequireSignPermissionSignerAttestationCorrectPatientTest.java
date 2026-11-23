package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision;
import com.aurevia.smartclinic.clinical.notes.ClinicalNoteService;
import com.aurevia.smartclinic.clinical.notes.NoteChecksum;
import com.aurevia.smartclinic.clinical.notes.NoteRepository;
import com.aurevia.smartclinic.clinical.notes.NoteState;
import com.aurevia.smartclinic.clinical.notes.RevisionInput;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * CLN-05 clause 2 proof: signing a draft requires sign permission, an explicit
 * signer attestation, a patient in the active organization, the version the
 * caller read and all required content fields. The service re-derives every
 * one of those inside one tenant-guarded transaction, so a client cannot relax
 * a rule by supplying a different actor, patient, version or checksum.
 *
 * <p>This is the fast, synthetic unit tier: the repository, audit appender and
 * transaction template are mocked exactly as in {@code ClinicalNoteServiceTest},
 * with no real patient data and no external dependency. The HTTP-level and
 * database-backed proof of the same commands lives, unchanged, in
 * {@code ClinicalNoteRevisionIntegrationTest}.</p>
 */
class RequireSignPermissionSignerAttestationCorrectPatientTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CLINICIAN = "clinician-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String NOTE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CONTENT = "Chief concern: chest pain";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private NoteRepository repository;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private ClinicalNoteService service;

    @BeforeEach
    void setUp() {
        repository = mock(NoteRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        service = new ClinicalNoteService(repository, transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        TenantContext.set(new TenantContext(ORG, CLINICIAN, ClinicalNoteService.PURPOSE, "corr-cln05-c2"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A valid, editable original draft read at the version the caller will send. */
    private static ClinicalNoteRevision draft() {
        return new ClinicalNoteRevision(NOTE, 1, NoteState.DRAFT, PATIENT, null,
                "Visit note", CONTENT, "en", "SOAP", 1,
                "author-a", CLINICIAN, NOW.toString(), null, null, null,
                NoteChecksum.sha256(CONTENT), null, null, null, "[]", NOW.toString());
    }

    /** A draft whose required content is blank: it must never be signable. */
    private static ClinicalNoteRevision draftMissingRequiredField() {
        return new ClinicalNoteRevision(NOTE, 1, NoteState.DRAFT, PATIENT, null,
                "Visit note", "  ", "en", "SOAP", 1,
                "author-a", CLINICIAN, NOW.toString(), null, null, null,
                NoteChecksum.sha256("  "), null, null, null, "[]", NOW.toString());
    }

    /** Grant an active membership with the given roles, the only source of authority. */
    private void authorized(String... roles) {
        when(repository.hasActiveMembership(CLINICIAN)).thenReturn(true);
        when(repository.activeRoles(CLINICIAN)).thenReturn(List.of(roles));
    }

    /** The append succeeds with a single row, so the command can read its result. */
    private void willInsert() {
        when(repository.insert(eq(ORG), anyString(), any(RevisionInput.class))).thenReturn(1);
    }

    @Test
    void clinicianSignsDraftWhenPermissionAttestationPatientVersionAndFieldsAllHold() {
        authorized("clinician");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.current(NOTE)).thenReturn(Optional.of(draft()),
                Optional.of(new ClinicalNoteRevision(NOTE, 2, NoteState.SIGNED, PATIENT, null,
                        "Visit note", CONTENT, "en", "SOAP", 1, "author-a", CLINICIAN,
                        NOW.toString(), NOW.toString(), CLINICIAN, "I attest I reviewed this note",
                        NoteChecksum.sha256(CONTENT), 1, null, null, "[]", NOW.toString())));
        willInsert();

        ClinicalNoteRevision signed = service.sign(NOTE, 1, "I attest I reviewed this note");

        assertEquals(NoteState.SIGNED, signed.state());
        assertEquals(2, signed.version());
        ArgumentCaptor<RevisionInput> captor = ArgumentCaptor.forClass(RevisionInput.class);
        verify(repository).insert(eq(ORG), eq(NOTE), captor.capture());
        RevisionInput input = captor.getValue();
        assertEquals(NoteState.SIGNED, input.state());
        assertEquals(1, input.supersedesVersion(), "the signed revision supersedes the draft it signs");
        assertEquals("I attest I reviewed this note", input.attestation());
        assertEquals(NOW.toString(), input.signedAt());
        assertEquals(PATIENT, input.patientId());
        assertEquals(CONTENT, input.content());
        assertEquals(NoteChecksum.sha256(CONTENT), input.contentChecksum());
        assertEquals(1, input.templateVersion());
        assertEquals("author-a", input.clinicalAuthorSubject());
        assertEquals(NOW.toString(), input.effectiveAt());
        assertNull(input.enteredErrorReason());
        assertEquals("[]", input.affectedRecipients());
        verifySignatureAudit();
    }

    @Test
    void signDeniesANonSigningRoleEvenWithAnActiveMembership() {
        authorized("clinical_assistant", "reception");
        when(repository.current(NOTE)).thenReturn(Optional.of(draft()));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attested"));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("permission"));
        verify(repository, never()).insert(any(), any(), any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("DENIED"));
    }

    @Test
    void signDeniesAMemberWhoseMembershipIsInactive() {
        when(repository.hasActiveMembership(CLINICIAN)).thenReturn(false);
        when(repository.activeRoles(CLINICIAN)).thenReturn(List.of("clinician"));

        ApiException denial = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attested"));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("no active membership"));
        verify(repository, never()).insert(any(), any(), any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("DENIED"));
    }

    @Test
    void signRefusesABlankOrMissingSignerAttestation() {
        authorized("clinician");
        when(repository.current(NOTE)).thenReturn(Optional.of(draft()));
        // Patient context is satisfied so a relaxed attestation rule cannot
        // produce a different 422 downstream and keep this test green.
        when(repository.patientExists(PATIENT)).thenReturn(true);

        ApiException blank = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "  "));
        assertEquals(422, blank.status());
        assertEquals("INVALID_ATTESTATION", blank.code());
        ApiException missing = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, null));
        assertEquals(422, missing.status());
        assertEquals("INVALID_ATTESTATION", missing.code());
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void signRefusesAPatientOutsideTheActiveOrganization() {
        authorized("clinician");
        when(repository.current(NOTE)).thenReturn(Optional.of(draft()));
        when(repository.patientExists(PATIENT)).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attested"));

        assertEquals(422, failure.status());
        assertEquals("INVALID_PATIENT_CONTEXT", failure.code());
        verify(repository, never()).insert(any(), any(), any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("CONFLICT"));
    }

    @Test
    void signRefusesADraftVersionTheCallerDidNotRead() {
        authorized("clinician");
        when(repository.current(NOTE)).thenReturn(Optional.of(
                new ClinicalNoteRevision(NOTE, 3, NoteState.DRAFT, PATIENT, null,
                        "Visit note", CONTENT, "en", "SOAP", 1, "author-a", CLINICIAN,
                        NOW.toString(), null, null, null, NoteChecksum.sha256(CONTENT),
                        null, null, null, "[]", NOW.toString())));

        ApiException failure = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attested"));

        assertEquals(412, failure.status());
        assertEquals("STALE_VERSION", failure.code());
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void signRefusesADraftWithMissingRequiredFields() {
        authorized("clinician");
        when(repository.current(NOTE)).thenReturn(Optional.of(draftMissingRequiredField()));
        // Keep patient context valid: the failure must come from the required
        // content check, not from an unrelated downstream guard.
        when(repository.patientExists(PATIENT)).thenReturn(true);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attested"));

        assertEquals(422, failure.status());
        assertEquals("INVALID_CONTENT", failure.code());
        verify(repository, never()).insert(any(), any(), any());
    }

    /** Confirm the successful signature is audited under the sign action. */
    private void verifySignatureAudit() {
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("SUCCESS"));
    }
}
