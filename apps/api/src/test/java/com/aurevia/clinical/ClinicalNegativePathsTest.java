package com.aurevia.clinical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.clinical.notes.ClinicalNoteController;
import com.aurevia.clinical.notes.ClinicalNoteRevision;
import com.aurevia.clinical.notes.ClinicalNoteService;
import com.aurevia.clinical.notes.NoteChecksum;
import com.aurevia.clinical.notes.NoteRepository;
import com.aurevia.clinical.notes.NoteState;
import com.aurevia.clinical.notes.RevisionInput;
import com.aurevia.tenant.TenantContext;

/**
 * Consolidated negative matrix at the clinical note controller boundary (CLN-05, ARC-02, ARC-03).
 *
 * <p>Every case enters through a {@code ClinicalNoteController} method and runs the real
 * {@code ClinicalNoteService}, so the assertions cover binding, precondition parsing,
 * authorization and failure auditing together — not just an isolated validator. The repository
 * and audit sink are synthetic boundaries: an empty {@code Optional} models the fold forced
 * row-level security returns to a peer tenant. That mock cannot prove RLS itself; authoritative
 * isolation evidence stays in the HTTP integration suite. All identifiers, subjects,
 * organizations and content strings in this class are synthetic.</p>
 *
 * <p>No production code, contract or migration is changed by this class.</p>
 */
class ClinicalNegativePathsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ACTOR = "clinician-a";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String NOTE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String CLINICAL_CONTENT = "peer-tenant-chief-concern";
    private static final Instant NOW = Instant.parse("2026-11-27T00:00:00Z");

    private NoteRepository repository;
    private AuditAppender audit;
    private TransactionTemplate transactions;
    private ClinicalNoteController controller;

    /** Wire a real service and controller over synthetic transaction, repository and audit boundaries. */
    @BeforeEach
    void setUp() {
        repository = mock(NoteRepository.class);
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        ClinicalNoteService service = new ClinicalNoteService(repository, transactions, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new ClinicalNoteController(service);
        bind(ORG_A, "corr-a");
    }

    /** Release the thread-bound tenant context after each case. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** Bind one synthetic organization to the request thread. */
    private static void bind(String organization, String correlation) {
        TenantContext.set(new TenantContext(organization, ACTOR, ClinicalNoteService.PURPOSE, correlation));
    }

    /** Grant an active membership and the supplied role set to the synthetic subject. */
    private void authorized(String... roles) {
        when(repository.hasActiveMembership(ACTOR)).thenReturn(true);
        when(repository.activeRoles(ACTOR)).thenReturn(List.of(roles.length == 0
                ? new String[] {"clinician"} : roles));
    }

    /** Build a deterministic synthetic draft revision for precondition and read cases. */
    private static ClinicalNoteRevision revision(NoteState state, int version) {
        return new ClinicalNoteRevision(NOTE, version, state, PATIENT, null,
                "Visit note", CLINICAL_CONTENT, "en", "SOAP", 1,
                "author-a", ACTOR, NOW.toString(), null, null, null,
                NoteChecksum.sha256(CLINICAL_CONTENT), null, null, null, "[]", NOW.toString());
    }

    /** Make the synthetic append succeed for the draft-create case. */
    private void willInsert() {
        when(repository.insert(eq(ORG_A), anyString(), any(RevisionInput.class))).thenReturn(1);
    }

    /** Build a create command with one field corrupted by the caller. */
    private static ClinicalNoteController.CreateRequest create(String patientId, String title,
                                                               String templateCode, String language,
                                                               String effectiveAt) {
        return new ClinicalNoteController.CreateRequest(patientId, null, title, CLINICAL_CONTENT,
                language, templateCode, 1, "author-a", effectiveAt);
    }

    /** Assert one malformed create command is refused before any transaction or write. */
    private void assertCreateFailure(ClinicalNoteController.CreateRequest request, String code) {
        ApiException failure = assertThrows(ApiException.class, () -> controller.create(request));
        assertEquals(422, failure.status());
        assertEquals(code, failure.code());
    }

    /**
     * Missing, blank, oversized and malformed create fields are refused with machine codes before
     * authorization or persistence, so a bad field cannot reach a SQL cast or the revision ledger.
     */
    @Test
    void createRejectsMissingBlankTooLongAndMalformedInput() {
        assertCreateFailure(create(PATIENT, null, "SOAP", "en", null), "INVALID_TITLE");
        assertCreateFailure(create(PATIENT, "   ", "SOAP", "en", null), "INVALID_TITLE");
        assertCreateFailure(create(PATIENT, "x".repeat(201), "SOAP", "en", null), "INVALID_TITLE");
        assertCreateFailure(create("not-a-uuid", "Title", "SOAP", "en", null), "INVALID_PATIENTID");
        assertCreateFailure(create(PATIENT, "Title", "soap", "en", null), "INVALID_TEMPLATECODE");
        assertCreateFailure(create(PATIENT, "Title", "SOAP", "fr", null), "INVALID_LANGUAGE");
        assertCreateFailure(create(PATIENT, "Title", "SOAP", "en", "yesterday"), "INVALID_EFFECTIVE_AT");
        verify(repository, never()).insert(any(), any(), any());
        verify(transactions, never()).execute(any());
    }

    /**
     * A caller with an active membership but no clinical note permission is denied on sign;
     * the denial is audited in the caller's tenant and no note is read or written.
     */
    @Test
    void signDeniesWrongRole() {
        authorized("reception");
        ApiException denial = assertThrows(ApiException.class,
                () -> controller.sign(NOTE, "\"1\"", new ClinicalNoteController.SignRequest("I attest")));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        verify(audit).append(eq(TenantContext.current()), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("DENIED"));
        verify(repository, never()).lockNote(anyString());
        verify(repository, never()).current(anyString());
        verify(repository, never()).insert(any(), any(), any());
    }

    /**
     * An identifier owned by another tenant is indistinguishable from a missing note: read and
     * edit answer 404 with a fixed message and audit NOT_FOUND, never DENIED or FORBIDDEN.
     *
     * <p>The read audit is appended in its single guarded transaction; a rejected edit throws
     * inside that transaction, so its NOT_FOUND audit is re-appended in a committed second one.</p>
     */
    @Test
    void otherTenantIdLooksLikeNotFound() {
        TenantContext peer = new TenantContext(ORG_B, ACTOR, ClinicalNoteService.PURPOSE, "corr-b");
        TenantContext.set(peer);
        authorized();
        when(repository.current(NOTE)).thenReturn(Optional.empty());

        ApiException read = assertThrows(ApiException.class, () -> controller.get(NOTE));
        assertEquals(404, read.status());
        assertEquals("NOT_FOUND", read.code());
        assertNotEquals("FORBIDDEN", read.code());
        assertFalse(read.getMessage().contains(CLINICAL_CONTENT));
        assertFalse(read.getMessage().contains(PATIENT));

        ApiException edit = assertThrows(ApiException.class,
                () -> controller.edit(NOTE, "\"1\"",
                        new ClinicalNoteController.EditRequest("Title", "Content")));
        assertEquals(404, edit.status());
        assertEquals("NOT_FOUND", edit.code());
        assertNotEquals("FORBIDDEN", edit.code());
        assertFalse(edit.getMessage().contains(CLINICAL_CONTENT));

        verify(audit).append(eq(peer), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.READ), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("NOT_FOUND"));
        verify(audit).append(eq(peer), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.UPDATE), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("NOT_FOUND"));
        verify(audit, never()).append(any(), any(), any(), any(), any(), any(), eq("DENIED"));
        verify(transactions, times(3)).execute(any());
        verify(repository, never()).history(anyString());
        verify(repository, never()).insert(any(), any(), any());
    }

    /**
     * A missing or malformed precondition is rejected by the shared parser before any command
     * runs; a valid but stale version is refused by the compare-and-set with a conflict audit.
     */
    @Test
    void staleAndMalformedIfMatch() {
        authorized();
        ApiException missing = assertThrows(ApiException.class,
                () -> controller.sign(NOTE, null, new ClinicalNoteController.SignRequest("I attest")));
        assertEquals(428, missing.status());
        assertEquals("PRECONDITION_REQUIRED", missing.code());

        ApiException malformed = assertThrows(ApiException.class,
                () -> controller.sign(NOTE, "abc", new ClinicalNoteController.SignRequest("I attest")));
        assertEquals(422, malformed.status());
        assertEquals("INVALID_IF_MATCH", malformed.code());

        ApiException zero = assertThrows(ApiException.class,
                () -> controller.sign(NOTE, "0", new ClinicalNoteController.SignRequest("I attest")));
        assertEquals(422, zero.status());
        assertEquals("INVALID_IF_MATCH", zero.code());

        when(repository.current(NOTE)).thenReturn(Optional.of(revision(NoteState.DRAFT, 2)));
        ApiException staleEdit = assertThrows(ApiException.class,
                () -> controller.edit(NOTE, "\"1\"",
                        new ClinicalNoteController.EditRequest("Title", "Content")));
        assertEquals(412, staleEdit.status());
        assertEquals("STALE_VERSION", staleEdit.code());

        ApiException staleSign = assertThrows(ApiException.class,
                () -> controller.sign(NOTE, "\"1\"", new ClinicalNoteController.SignRequest("I attest")));
        assertEquals(412, staleSign.status());
        assertEquals("STALE_VERSION", staleSign.code());

        verify(audit).append(any(), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.UPDATE), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("CONFLICT"));
        verify(audit).append(any(), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.SIGN), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), eq(NOTE), eq("CONFLICT"));
        verify(repository, never()).insert(any(), any(), any());
    }

    /**
     * Replaying an identical create command with the same synthetic retry key still produces two
     * drafts at this boundary: the controller binds no retry-key header and the service consults
     * no deduplication store. Real HTTP replay is owned by the idempotency interceptor upstream of
     * the controller, so this characterization is not ARC-03 replay-signoff evidence.
     */
    @Test
    void repeatedRetryKeyHasNoClinicalNoteDedupToday() {
        authorized();
        when(repository.patientExists(PATIENT)).thenReturn(true);
        willInsert();
        when(repository.current(anyString())).thenReturn(Optional.of(revision(NoteState.DRAFT, 1)));

        // The synthetic key "clinical-note-retry-key" would travel in Idempotency-Key at the
        // HTTP boundary; a direct controller call cannot observe it.
        ClinicalNoteController.CreateRequest request = create(PATIENT, "Title", "SOAP", "en", null);
        ClinicalNoteRevision first = controller.create(request);
        ClinicalNoteRevision second = controller.create(request);
        assertEquals(NoteState.DRAFT, first.state());
        assertEquals(NoteState.DRAFT, second.state());

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).insert(eq(ORG_A), ids.capture(), any(RevisionInput.class));
        assertNotEquals(ids.getAllValues().get(0), ids.getAllValues().get(1));
        verify(audit, times(2)).append(any(), eq(ClinicalNoteService.PURPOSE),
                eq(ClinicalNoteService.CREATE), eq(ClinicalNoteService.RESOURCE_TYPE),
                eq(ClinicalNoteService.SCOPE), anyString(), eq("SUCCESS"));
    }
}
