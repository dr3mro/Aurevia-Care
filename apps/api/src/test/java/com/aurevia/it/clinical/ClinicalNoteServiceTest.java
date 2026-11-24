package com.aurevia.it.clinical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.audit.AuditAppender;
import com.aurevia.api.ApiException;
import com.aurevia.clinical.notes.ClinicalNoteService;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.clinical.notes.NoteInputs;
import com.aurevia.clinical.notes.NoteRepository;
import com.aurevia.clinical.notes.ClinicalNoteRevision;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.clinical.notes.NoteView;
import com.aurevia.clinical.notes.RevisionInput;
import com.aurevia.tenant.TenantContext;

/** S-G1-05 fast unit tier: CLN-05 signing rules, state machine, validation and provenance. */
class ClinicalNoteServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "clinician-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String NOTE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
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
        TenantContext.set(new TenantContext(ORG, ACTOR, ClinicalNoteService.PURPOSE, "corr-1"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles.length == 0
                ? new String[] {"clinician"} : roles));
    }

    private static ClinicalNoteRevision revision(NoteState state, int version) {
        return new ClinicalNoteRevision(NOTE, version, state, PATIENT, null,
                "Visit note", "Clinical content", "en", "SOAP", 1,
                "author-a", ACTOR, NOW.toString(), null, null, null,
                NoteChecksum.sha256("Clinical content"), null, null, null, "[]", NOW.toString());
    }

    private static ClinicalNoteRevision invalidContentDraft() {
        return new ClinicalNoteRevision(NOTE, 1, NoteState.DRAFT, PATIENT, null,
                "Visit note", "  ", "en", "SOAP", 1,
                "author-a", ACTOR, NOW.toString(), null, null, null,
                NoteChecksum.sha256("  "), null, null, null, "[]", NOW.toString());
    }

    private void willInsert() {
        when(repository.insert(eq(ORG), anyString(), any(RevisionInput.class))).thenReturn(1);
    }

    @Test
    void stateWireFormsAndPredicatesAreTotal() {
        assertEquals("draft", NoteState.DRAFT.wire());
        assertEquals("signed", NoteState.SIGNED.wire());
        assertEquals("amendment_draft", NoteState.AMENDMENT_DRAFT.wire());
        assertEquals("addendum", NoteState.ADDENDUM.wire());
        assertEquals("entered_in_error", NoteState.ENTERED_IN_ERROR.wire());
        for (NoteState state : NoteState.values()) {
            assertEquals(state, NoteState.from(state.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> NoteState.from("bogus"));
        assertTrue(NoteState.DRAFT.editable());
        assertTrue(NoteState.AMENDMENT_DRAFT.editable());
        assertFalse(NoteState.SIGNED.editable());
        assertFalse(NoteState.ADDENDUM.editable());
        assertFalse(NoteState.ENTERED_IN_ERROR.editable());
        assertTrue(NoteState.SIGNED.signatureBearing());
        assertTrue(NoteState.ADDENDUM.signatureBearing());
        assertFalse(NoteState.DRAFT.signatureBearing());
        assertFalse(NoteState.AMENDMENT_DRAFT.signatureBearing());
        assertFalse(NoteState.ENTERED_IN_ERROR.signatureBearing());
    }

    @Test
    void checksumIsDeterministicAndContentSensitive() {
        String first = NoteChecksum.sha256("Clinical content");
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertEquals(first, NoteChecksum.sha256("Clinical content"));
        assertNotEquals(first, NoteChecksum.sha256("Different content"));
    }

    @Test
    void inputsRejectMalformedValuesAtTheTrustBoundary() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.uuid(null, "patientId")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.uuid("not-a-uuid", "patientId")).status());
        assertEquals(PATIENT, NoteInputs.uuid(PATIENT, "patientId"));
        assertEquals(null, NoteInputs.optionalUuid(null, "encounterId"));
        assertEquals(null, NoteInputs.optionalUuid("  ", "encounterId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.optionalUuid("bad", "encounterId")).status());
        assertEquals(NOTE, NoteInputs.optionalUuid(NOTE, "encounterId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.text(null, "title", 200)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.text("   ", "title", 200)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.text("x".repeat(201), "title", 200)).status());
        assertEquals("Title", NoteInputs.text("  Title  ", "title", 200));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.code("soap", "templateCode")).status());
        assertEquals("INVALID_TEMPLATECODE", assertThrows(ApiException.class,
                () -> NoteInputs.code("soap", "templateCode")).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.code(null, "templateCode")).status());
        assertEquals("SOAP", NoteInputs.code("SOAP", "templateCode"));
        assertEquals("A".repeat(32), NoteInputs.code("A".repeat(32), "templateCode"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.code("A".repeat(33), "templateCode")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.code("رمز", "templateCode")).status());
        assertEquals("en", NoteInputs.language("en"));
        assertEquals("ar", NoteInputs.language("ar"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.language("fr")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.language(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.version(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.version(0)).status());
        assertEquals(3, NoteInputs.version(3));
        assertEquals(NOW.toString(), NoteInputs.instantOrNow(NOW.toString(), NOW));
        assertEquals("2020-01-01T00:00:00Z", NoteInputs.instantOrNow(null, Clock.fixed(
                Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC).instant()));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.instantOrNow("yesterday", NOW)).status());
        // A future effective instant can never be signed because V15 requires
        // signed_at >= effective_at; reject it at the trust boundary instead of
        // letting the irreversible sign command fail with an unhandled 500 (A14).
        ApiException futureEffective = assertThrows(ApiException.class,
                () -> NoteInputs.instantOrNow(NOW.plusSeconds(3600).toString(), NOW));
        assertEquals(422, futureEffective.status());
        assertEquals("INVALID_EFFECTIVE_AT", futureEffective.code());
        assertEquals("[]", NoteInputs.recipients(null));
        assertEquals("[]", NoteInputs.recipients("   "));
        assertEquals("[\"lab-a\"]", NoteInputs.recipients("[\"lab-a\"]"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.recipients("not-json")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.recipients("{\"a\":1}")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.recipients("[\"a\"")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.recipients("x".repeat(2001))).status());
        assertEquals("because", NoteInputs.reason("because", "reason"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.reason(null, "reason")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.reason("  ", "reason")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> NoteInputs.reason("x".repeat(201), "reason")).status());
    }

    @Test
    void createDraftDeniesInactiveMembershipAndMissingPermission() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a", null)).status());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.CREATE), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), anyString(), eq("DENIED"));
        authorized("reception");
        assertEquals(403, assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a", null)).status());
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void createDraftConcealsUnknownPatient() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(false);
        assertEquals(422, assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a", null)).status());
        verify(audit).append(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("CONFLICT"));
    }

    @Test
    void createDraftRejectsFutureEffectiveAtBeforeAnyWrite() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        ApiException failure = assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a",
                NOW.plusSeconds(3600).toString()));
        assertEquals(422, failure.status());
        assertEquals("INVALID_EFFECTIVE_AT", failure.code());
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void createDraftPersistsDraftAndAuditsSuccess() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        willInsert();
        when(repository.current(anyString())).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        ClinicalNoteRevision created = service.createDraft(PATIENT, null, "Title", "Content",
                "en", "SOAP", 1, "author-a", null);
        assertEquals(NoteState.DRAFT, created.state());
        verify(repository).lockNote(anyString());
        verify(repository).insert(eq(ORG), anyString(), any(RevisionInput.class));
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.CREATE),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void createDraftMapsConcurrentConflict() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insert(eq(ORG), anyString(), any(RevisionInput.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a", null)).status());
    }

    @Test
    void updateDraftRejectsStaleVersionsImmutableStatesAndInvalidContent() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.updateDraft("bad", 1, "Title", "Content")).status());
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 2)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.updateDraft(NOTE, 1, "Title", "Content")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.updateDraft(NOTE, 1, "Title", "Content")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.updateDraft(NOTE, 1, "Title", "  ")).status());
    }

    @Test
    void updateDraftConcealsMissingNoteAndDenies() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.updateDraft(NOTE, 1, "Title", "Content")).status());
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.updateDraft(NOTE, 1, "Title", "Content")).status());
    }

    @Test
    void updateDraftAppendsNewVersionAndAudits() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        willInsert();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)),
                Optional.of(revision(NoteState.DRAFT, 2)));
        assertEquals(2, service.updateDraft(NOTE, 1, "New title", "New content").version());
        verify(repository).insert(eq(ORG), eq(NOTE), any(RevisionInput.class));
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.UPDATE),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void signRequiresAttestationPermissionAndCorrectPatientContext() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.sign("bad", 1, "attest")).status());
        authorized("reception");
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "  ")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, null)).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(invalidContentDraft()));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        when(repository.patientExists(PATIENT)).thenReturn(false);
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
    }

    @Test
    void signRejectsNonDraftStaleAndMissingNotes() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 3)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
    }

    @Test
    void signingAppendsImmutableSignedRevisionWithProvenance() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        willInsert();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)),
                Optional.of(revision(NoteState.SIGNED, 2)));
        ClinicalNoteRevision signed = service.sign(NOTE, 1, "I attest I reviewed this note");
        assertEquals(NoteState.SIGNED, signed.state());
        verify(repository).insert(eq(ORG), eq(NOTE), any(RevisionInput.class));
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.SIGN),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void signAddendumOnlyAppliesToAmendmentDrafts() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.signAddendum(NOTE, 1, "attest")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.AMENDMENT_DRAFT, 2)));
        willInsert();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.AMENDMENT_DRAFT, 2)),
                Optional.of(revision(NoteState.ADDENDUM, 3)));
        ClinicalNoteRevision addendum = service.signAddendum(NOTE, 2, "I attest this addendum");
        assertEquals(NoteState.ADDENDUM, addendum.state());
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.SIGN_ADDENDUM),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void createAmendmentDraftRequiresSignedSourceAndCorrectVersion() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.createAmendmentDraft(NOTE, 1)).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 4)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.createAmendmentDraft(NOTE, 1)).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        willInsert();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)),
                Optional.of(revision(NoteState.AMENDMENT_DRAFT, 2)));
        assertEquals(NoteState.AMENDMENT_DRAFT, service.createAmendmentDraft(NOTE, 1).state());
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.AMEND),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void enteredInErrorRequiresReasonRecipientsAndSignedSource() {
        authorized();
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.markEnteredInError("bad", 1, "reason", "[]")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.markEnteredInError(NOTE, 1, "  ", "[]")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.markEnteredInError(NOTE, 1, "reason", "bad")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.markEnteredInError(NOTE, 1, "wrong note", "[]")).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 3)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.markEnteredInError(NOTE, 1, "reason", "[]")).status());
    }

    @Test
    void enteredInErrorPreservesPriorRevisionAndRecordsReasonAndRecipients() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        willInsert();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)),
                Optional.of(revision(NoteState.ENTERED_IN_ERROR, 2)));
        ClinicalNoteRevision result = service.markEnteredInError(NOTE, 1, "wrong patient", "[\"lab-a\"]");
        assertEquals(NoteState.ENTERED_IN_ERROR, result.state());
        verify(repository).insert(eq(ORG), eq(NOTE), any(RevisionInput.class));
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.ENTERED_IN_ERROR),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void getConcealsUnknownAndReturnsCurrentWithImmutableHistory() {
        assertEquals(422, assertThrows(ApiException.class, () -> service.get("bad")).status());
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(NOTE)).status());
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(NOTE)).status());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 2)));
        List<ClinicalNoteRevision> history = List.of(revision(NoteState.DRAFT, 1),
                revision(NoteState.SIGNED, 2));
        when(repository.history(NOTE)).thenReturn(history);
        NoteView view = service.get(NOTE);
        assertEquals(NoteState.SIGNED, view.current().state());
        assertEquals(2, view.history().size());
        assertThrows(UnsupportedOperationException.class, () -> view.history().clear());
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.READ),
                anyString(), anyString(), anyString(), eq("SUCCESS"));
    }

    @Test
    void readDenialAuditsTheRequestedNoteId() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(NOTE)).status());
        // OPS-02 requires the audited resource id even on a committed denial.
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.READ), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("DENIED"));
    }

    @Test
    void missingNoteFailureAuditsInASecondCommittedTransaction() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        // The command transaction appends a NOT_FOUND audit and then throws, so
        // that row is rolled back with the rest of the transaction. A second,
        // committed transaction must re-append the audit with the requested
        // resource id, action and outcome (OPS-02). Without the second transaction
        // a failed read leaves no durable trace of the attempt.
        verify(transactions, times(2)).execute(any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("NOT_FOUND"));
    }

    @Test
    void staleVersionAttemptAuditsConflictInASecondCommittedTransaction() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 2)));
        assertEquals(412, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        // A stale version that would otherwise advance an immutable chain is a
        // clinically significant rejected write (G1: a stale draft must not
        // overwrite a signed record). Its audit must outlive the rollback (OPS-02).
        verify(transactions, times(2)).execute(any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("CONFLICT"));
    }

    @Test
    void immutableRevisionEditAttemptAuditsConflict() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.updateDraft(NOTE, 1, "Title", "Content")).status());
        verify(transactions, times(2)).execute(any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.UPDATE), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("CONFLICT"));
    }

    @Test
    void illegalTransitionAttemptAuditsConflict() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 1)));
        assertEquals(409, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        verify(transactions, times(2)).execute(any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("CONFLICT"));
    }

    @Test
    void concurrentInsertConflictAuditsInSecondTransaction() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.insert(eq(ORG), anyString(), any(RevisionInput.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a", null)).status());
        verify(transactions, times(2)).execute(any());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.CREATE), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), anyString(), eq("CONFLICT"));
    }

    @Test
    void allMutationCommandsDenyInactiveMembership() {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.signAddendum(NOTE, 1, "attest")).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.createAmendmentDraft(NOTE, 1)).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.markEnteredInError(NOTE, 1, "reason", "[]")).status());
        verify(repository, never()).insert(any(), any(), any());
    }

    @Test
    void clinicalAssistantCanDraftButCannotSign() {
        authorized("clinical_assistant");
        when(repository.patientExists(PATIENT)).thenReturn(true);
        willInsert();
        when(repository.current(anyString())).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(NoteState.DRAFT, service.createDraft(PATIENT, null, "Title", "Content",
                "en", "SOAP", 1, "author-a", null).state());
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.sign(NOTE, 1, "attest")).status());
    }

    @Test
    void checksumReportsUnavailableDigest() {
        ApiException failure = assertThrows(ApiException.class, () -> NoteChecksum.sha256("x",
                algorithm -> {
                    throw new NoSuchAlgorithmException("unavailable");
                }));
        assertEquals(500, failure.status());
        assertEquals("CHECKSUM_UNAVAILABLE", failure.code());
    }

    @Test
    void aLostAppendRaceIsReportedAsStale() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));
        when(repository.insert(eq(ORG), eq(NOTE), any(RevisionInput.class))).thenReturn(0);
        assertEquals(412, assertThrows(ApiException.class, () -> service.createDraft(
                PATIENT, null, "Title", "Content", "en", "SOAP", 1, "author-a", null)).status());
        verify(audit).append(any(), anyString(), eq(ClinicalNoteService.CREATE),
                anyString(), anyString(), anyString(), eq("CONFLICT"));
    }

    @Test
    void signingIntroducesNoReleaseOrRenderingState() {
        assertTrue(Arrays.stream(ClinicalNoteRevision.class.getRecordComponents())
                .noneMatch(component -> component.getName().matches("(?i).*(release|pdf|render|disclos).*")));
        assertTrue(Arrays.stream(NoteView.class.getRecordComponents())
                .noneMatch(component -> component.getName().matches("(?i).*(release|pdf|render|disclos).*")));
        assertTrue(Arrays.stream(ClinicalNoteService.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().matches("(?i).*(release|pdf|render|disclos).*")));
    }

    @Test
    void signPersistsTheExactSignedRevisionInput() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)),
                Optional.of(revision(NoteState.SIGNED, 2)));
        willInsert();
        service.sign(NOTE, 1, "reviewed and attested");
        ArgumentCaptor<RevisionInput> captor = ArgumentCaptor.forClass(RevisionInput.class);
        verify(repository).insert(eq(ORG), eq(NOTE), captor.capture());
        RevisionInput input = captor.getValue();
        assertEquals(NoteState.SIGNED, input.state());
        assertEquals(1, input.supersedesVersion());
        assertEquals("reviewed and attested", input.attestation());
        assertEquals(NOW.toString(), input.signedAt());
        assertEquals("Clinical content", input.content());
        assertEquals("SOAP", input.templateCode());
        assertEquals(1, input.templateVersion());
        assertEquals("author-a", input.clinicalAuthorSubject());
        assertEquals(NOW.toString(), input.effectiveAt());
        assertEquals(NoteChecksum.sha256("Clinical content"), input.contentChecksum());
        assertNull(input.enteredErrorReason());
        assertEquals("[]", input.affectedRecipients());
    }

    @Test
    void enteredInErrorPersistsReasonRecipientsAndPreservedContent() {
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 4)),
                Optional.of(revision(NoteState.ENTERED_IN_ERROR, 5)));
        willInsert();
        service.markEnteredInError(NOTE, 4, "wrong patient", "[\"lab-a\"]");
        ArgumentCaptor<RevisionInput> captor = ArgumentCaptor.forClass(RevisionInput.class);
        verify(repository).insert(eq(ORG), eq(NOTE), captor.capture());
        RevisionInput input = captor.getValue();
        assertEquals(NoteState.ENTERED_IN_ERROR, input.state());
        assertEquals(4, input.supersedesVersion());
        assertEquals("wrong patient", input.enteredErrorReason());
        assertEquals("[\"lab-a\"]", input.affectedRecipients());
        assertEquals("Clinical content", input.content());
        assertEquals(NoteChecksum.sha256("Clinical content"), input.contentChecksum());
        assertNull(input.signedAt());
        assertNull(input.attestation());
        // Actors and signer are not caller-supplied fields of the append command.
        assertTrue(Arrays.stream(RevisionInput.class.getRecordComponents())
                .noneMatch(component -> component.getName().matches("(?i).*(actor|signer|entry).*")));
    }

    @Test
    void amendmentAndAddendumPersistThePriorVersionLink() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.SIGNED, 2)),
                Optional.of(revision(NoteState.AMENDMENT_DRAFT, 3)));
        willInsert();
        service.createAmendmentDraft(NOTE, 2);
        ArgumentCaptor<RevisionInput> amendmentCaptor = ArgumentCaptor.forClass(RevisionInput.class);
        verify(repository).insert(eq(ORG), eq(NOTE), amendmentCaptor.capture());
        RevisionInput amendment = amendmentCaptor.getValue();
        assertEquals(NoteState.AMENDMENT_DRAFT, amendment.state());
        assertEquals(2, amendment.supersedesVersion());
        assertNull(amendment.signedAt());
        assertNull(amendment.attestation());

        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.AMENDMENT_DRAFT, 3)),
                Optional.of(revision(NoteState.ADDENDUM, 4)));
        service.signAddendum(NOTE, 3, "addendum attested");
        ArgumentCaptor<RevisionInput> addendumCaptor = ArgumentCaptor.forClass(RevisionInput.class);
        verify(repository, org.mockito.Mockito.times(2)).insert(eq(ORG), eq(NOTE), addendumCaptor.capture());
        RevisionInput addendum = addendumCaptor.getAllValues().get(1);
        assertEquals(NoteState.ADDENDUM, addendum.state());
        assertEquals(3, addendum.supersedesVersion());
        assertEquals("addendum attested", addendum.attestation());
        assertEquals(NOW.toString(), addendum.signedAt());
    }

    @Test
    void valueRecordsExposeTheirParts() {
        ClinicalNoteRevision revision = revision(NoteState.SIGNED, 2);
        assertEquals(NOTE, revision.id());
        assertEquals(2, revision.version());
        assertEquals(NoteState.SIGNED, revision.state());
        assertEquals(PATIENT, revision.patientId());
        assertEquals(null, revision.encounterId());
        assertEquals("Visit note", revision.title());
        assertEquals("Clinical content", revision.content());
        assertEquals("en", revision.language());
        assertEquals("SOAP", revision.templateCode());
        assertEquals(1, revision.templateVersion());
        assertEquals("author-a", revision.clinicalAuthorSubject());
        assertEquals(ACTOR, revision.dataEntrySubject());
        assertEquals(NOW.toString(), revision.effectiveAt());
        assertEquals(null, revision.signedAt());
        assertEquals(null, revision.signerSubject());
        assertEquals(null, revision.attestation());
        assertEquals(64, revision.contentChecksum().length());
        assertEquals(null, revision.supersedesVersion());
        assertEquals(null, revision.enteredErrorReason());
        assertEquals(null, revision.enteredErrorActor());
        assertEquals("[]", revision.affectedRecipients());
        assertEquals(NOW.toString(), revision.createdAt());
        assertEquals(revision, new ClinicalNoteRevision(NOTE, 2, NoteState.SIGNED, PATIENT, null,
                "Visit note", "Clinical content", "en", "SOAP", 1,
                "author-a", ACTOR, NOW.toString(), null, null, null,
                revision.contentChecksum(), null, null, null, "[]", NOW.toString()));
        assertEquals(revision.hashCode(), new ClinicalNoteRevision(NOTE, 2, NoteState.SIGNED, PATIENT,
                null, "Visit note", "Clinical content", "en", "SOAP", 1,
                "author-a", ACTOR, NOW.toString(), null, null, null,
                revision.contentChecksum(), null, null, null, "[]", NOW.toString()).hashCode());
        assertTrue(revision.toString().contains("SIGNED"));

        RevisionInput input = new RevisionInput(NoteState.DRAFT, PATIENT, null, "Title",
                "Content", "en", "SOAP", 1, "author-a", NOW.toString(), null, null,
                "checksum", null, null, "[]");
        assertEquals(NoteState.DRAFT, input.state());
        assertEquals(PATIENT, input.patientId());
        assertEquals(null, input.encounterId());
        assertEquals("Title", input.title());
        assertEquals("Content", input.content());
        assertEquals("en", input.language());
        assertEquals("SOAP", input.templateCode());
        assertEquals(1, input.templateVersion());
        assertEquals("author-a", input.clinicalAuthorSubject());
        assertEquals(NOW.toString(), input.effectiveAt());
        assertEquals(null, input.signedAt());
        assertEquals(null, input.attestation());
        assertEquals("checksum", input.contentChecksum());
        assertEquals(null, input.supersedesVersion());
        assertEquals(null, input.enteredErrorReason());
        assertEquals("[]", input.affectedRecipients());

        NoteView view = new NoteView(revision, List.of(revision));
        assertEquals(revision, view.current());
        assertEquals(1, view.history().size());
        assertEquals(view, new NoteView(revision, List.of(revision)));
        assertEquals(view.hashCode(), new NoteView(revision, List.of(revision)).hashCode());
        assertTrue(view.toString().contains("NoteView"));
    }
}
