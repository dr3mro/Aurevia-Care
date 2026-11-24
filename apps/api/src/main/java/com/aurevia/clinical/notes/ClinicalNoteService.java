package com.aurevia.clinical.notes;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * Draft-to-sign clinical documentation invariants (CLN-05).
 *
 * <p>Every command re-derives authorization and the note head inside one
 * tenant-guarded transaction. An editable draft is appended as a new immutable
 * signed revision that carries the signer, attestation, effective and signed
 * instants, content checksum and template version. Amendments and entered-in-
 * error append further revisions and never mutate or remove an earlier one.
 * Signing does not release content to a patient and does not render a PDF;
 * those are separate workflows.</p>
 */
@Service
public class ClinicalNoteService {

    /** Business purpose recorded on note audit events (OPS-02). */
    public static final String PURPOSE = "CLINICAL_DOC";
    /** Audit scope for note events (OPS-02). */
    public static final String SCOPE = "CLINICAL_NOTE";
    /** Audited resource type (OPS-02). */
    public static final String RESOURCE_TYPE = "CLINICAL_NOTE";
    /** Create-draft audit action. */
    public static final String CREATE = "NOTE_CREATE";
    /** Draft-edit audit action. */
    public static final String UPDATE = "NOTE_EDIT";
    /** Sign audit action. */
    public static final String SIGN = "NOTE_SIGN";
    /** Addendum signature audit action. */
    public static final String SIGN_ADDENDUM = "NOTE_ADDENDUM";
    /** Amendment audit action. */
    public static final String AMEND = "NOTE_AMEND";
    /** Entered-in-error audit action. */
    public static final String ENTERED_IN_ERROR = "NOTE_ENTERED_IN_ERROR";
    /** Read audit action. */
    public static final String READ = "NOTE_READ";

    private static final Set<String> CLINICIAN_ACTIONS = Set.of(
            CREATE, UPDATE, SIGN, SIGN_ADDENDUM, AMEND, ENTERED_IN_ERROR, READ);
    private static final Set<String> ASSISTANT_ACTIONS = Set.of(CREATE, UPDATE, READ);

    private final NoteRepository repository;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded revision repository
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        injected clock for effective and signed instants
     */
    public ClinicalNoteService(NoteRepository repository, TransactionTemplate transactions,
                               AuditAppender audit, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Run one guarded command, committing its failure audit after a rollback.
     *
     * <p>A command that fails inside the guarded transaction signals the failure
     * with an {@code AuditedFailure} carrying the audit facts but no appended
     * row: throwing rolls the transaction back, so an inline row would be lost.
     * This method re-appends the audit in a second committed transaction before
     * rethrowing the public {@code ApiException}. Successes and authorization
     * denials keep their existing single-transaction path.</p>
     *
     * @param callback the guarded command
     * @param <T>      command result type
     * @return the command result
     * @throws ApiException on any command failure
     */
    private <T> T audited(TransactionCallback<T> callback) {
        try {
            return transactions.execute(callback);
        } catch (AuditedFailure failure) {
            transactions.execute(status -> {
                audit.append(failure.context, PURPOSE, failure.action, RESOURCE_TYPE, SCOPE,
                        failure.id, failure.outcome);
                return null;
            });
            throw failure.failure;
        }
    }

    /**
     * Create the first editable draft of a new note (A1).
     *
     * @param patientId             patient whose chart owns the note
     * @param encounterId           optional encounter context
     * @param title                 note title
     * @param content               clinical content
     * @param language              content language
     * @param templateCode          template identifier
     * @param templateVersion       template version
     * @param clinicalAuthorSubject clinically responsible author
     * @param effectiveAt           optional clinical effective instant
     * @return the persisted draft
     * @throws ApiException on invalid input, denial, an unknown patient or a lost race
     */
    public ClinicalNoteRevision createDraft(String patientId, String encounterId, String title, String content,
                                    String language, String templateCode, Integer templateVersion,
                                    String clinicalAuthorSubject, String effectiveAt) {
        String patient = NoteInputs.uuid(patientId, "patientId");
        String encounter = NoteInputs.optionalUuid(encounterId, "encounterId");
        String noteTitle = NoteInputs.text(title, "title", 200);
        String noteContent = NoteInputs.text(content, "content", 20000);
        String noteLanguage = NoteInputs.language(language);
        String template = NoteInputs.code(templateCode, "templateCode");
        int version = NoteInputs.version(templateVersion);
        String author = NoteInputs.text(clinicalAuthorSubject, "clinicalAuthorSubject", 128);
        String effective = NoteInputs.instantOrNow(effectiveAt, clock.instant());
        String checksum = NoteChecksum.sha256(noteContent);
        String id = UUID.randomUUID().toString();
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ClinicalNoteRevision created = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, CREATE, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.patientExists(patient)) {
                conflict(context, CREATE, id, "INVALID_PATIENT_CONTEXT",
                        "patient is not in the active organization");
                failure.set(new ApiException(422, "INVALID_PATIENT_CONTEXT",
                        "patient is not in the active organization"));
                return null;
            }
            repository.lockNote(id);
            append(context, CREATE, id, new RevisionInput(NoteState.DRAFT, patient, encounter,
                    noteTitle, noteContent, noteLanguage, template, version, author, effective,
                    null, null, checksum, null, null, "[]"));
            return repository.current(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return created;
    }

    /**
     * Append a new editable version of a draft (A2).
     *
     * @param id              note identifier
     * @param expectedVersion version read by the caller
     * @param title           replacement title
     * @param content         replacement content
     * @return the new draft revision
     * @throws ApiException on invalid input, denial, a missing note, a stale
     *                      version or an immutable revision
     */
    public ClinicalNoteRevision updateDraft(String id, int expectedVersion, String title, String content) {
        NoteInputs.uuid(id, "id");
        String noteTitle = NoteInputs.text(title, "title", 200);
        String noteContent = NoteInputs.text(content, "content", 20000);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ClinicalNoteRevision updated = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, UPDATE, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            repository.lockNote(id);
            ClinicalNoteRevision current = load(id, context, UPDATE);
            requireVersion(current, expectedVersion, context, UPDATE, id);
            requireEditable(current, context, UPDATE, id);
            append(context, UPDATE, id, copy(current, current.state(), noteTitle, noteContent,
                    NoteChecksum.sha256(noteContent), current.version(), null, null, "[]"));
            return repository.current(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return updated;
    }

    /**
     * Sign an original draft (A4, A5).
     *
     * @param id              note identifier
     * @param expectedVersion version read by the caller
     * @param attestation     explicit clinical attestation
     * @return the immutable signed revision
     * @throws ApiException on invalid input, denial, a wrong patient context,
     *                      a stale version or an immutable revision
     */
    public ClinicalNoteRevision sign(String id, int expectedVersion, String attestation) {
        NoteInputs.uuid(id, "id");
        String attested = NoteInputs.text(attestation, "attestation", 500);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ClinicalNoteRevision signed = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, SIGN, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            repository.lockNote(id);
            ClinicalNoteRevision current = load(id, context, SIGN);
            requireVersion(current, expectedVersion, context, SIGN, id);
            if (current.state() != NoteState.DRAFT) {
                throw auditFailure(context, SIGN, id, "CONFLICT", 409,
                        "INVALID_TRANSITION", "only an original draft can be signed");
            }
            requireFields(current);
            requirePatient(current, context, SIGN);
            append(context, SIGN, id, copy(current, NoteState.SIGNED, current.title(), current.content(),
                    current.contentChecksum(), current.version(), clock.instant().toString(),
                    attested, current.affectedRecipients()));
            return repository.current(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return signed;
    }

    /**
     * Sign an amendment draft as an addendum to the prior signed revision (A7).
     *
     * @param id              note identifier
     * @param expectedVersion version read by the caller
     * @param attestation     explicit clinical attestation
     * @return the immutable addendum revision
     * @throws ApiException on invalid input, denial, a wrong patient context,
     *                      a stale version or a non-amendment source
     */
    public ClinicalNoteRevision signAddendum(String id, int expectedVersion, String attestation) {
        NoteInputs.uuid(id, "id");
        String attested = NoteInputs.text(attestation, "attestation", 500);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ClinicalNoteRevision addendum = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, SIGN_ADDENDUM, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            repository.lockNote(id);
            ClinicalNoteRevision current = load(id, context, SIGN_ADDENDUM);
            requireVersion(current, expectedVersion, context, SIGN_ADDENDUM, id);
            if (current.state() != NoteState.AMENDMENT_DRAFT) {
                throw auditFailure(context, SIGN_ADDENDUM, id, "CONFLICT", 409,
                        "INVALID_TRANSITION", "only an amendment draft can become an addendum");
            }
            requireFields(current);
            requirePatient(current, context, SIGN_ADDENDUM);
            append(context, SIGN_ADDENDUM, id, copy(current, NoteState.ADDENDUM, current.title(),
                    current.content(), current.contentChecksum(), current.version(),
                    clock.instant().toString(), attested, current.affectedRecipients()));
            return repository.current(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return addendum;
    }

    /**
     * Open a separate amendment draft from a signed revision (A6).
     *
     * @param id              note identifier
     * @param expectedVersion version read by the caller
     * @return the new amendment draft
     * @throws ApiException on denial, a missing note, a stale version or a non-signed source
     */
    public ClinicalNoteRevision createAmendmentDraft(String id, int expectedVersion) {
        NoteInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ClinicalNoteRevision amendment = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, AMEND, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            repository.lockNote(id);
            ClinicalNoteRevision current = load(id, context, AMEND);
            requireVersion(current, expectedVersion, context, AMEND, id);
            if (!current.state().signatureBearing()) {
                throw auditFailure(context, AMEND, id, "CONFLICT", 409,
                        "INVALID_TRANSITION", "only a signed revision can be amended");
            }
            append(context, AMEND, id, copy(current, NoteState.AMENDMENT_DRAFT, current.title(),
                    current.content(), current.contentChecksum(), current.version(), null, null,
                    current.affectedRecipients()));
            return repository.current(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return amendment;
    }

    /**
     * Record entered-in-error, preserving the prior revision (A8).
     *
     * @param id              note identifier
     * @param expectedVersion version read by the caller
     * @param reason          recorded reason
     * @param recipients      JSON array of affected recipient identifiers
     * @return the entered-in-error revision
     * @throws ApiException on invalid input, denial, a missing note, a stale
     *                      version or a non-signed source
     */
    public ClinicalNoteRevision markEnteredInError(String id, int expectedVersion, String reason, String recipients) {
        NoteInputs.uuid(id, "id");
        String recordedReason = NoteInputs.reason(reason, "reason");
        String recordedRecipients = NoteInputs.recipients(recipients);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ClinicalNoteRevision correction = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ENTERED_IN_ERROR, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            repository.lockNote(id);
            ClinicalNoteRevision current = load(id, context, ENTERED_IN_ERROR);
            requireVersion(current, expectedVersion, context, ENTERED_IN_ERROR, id);
            if (!current.state().signatureBearing()) {
                throw auditFailure(context, ENTERED_IN_ERROR, id, "CONFLICT", 409,
                        "INVALID_TRANSITION", "only a signed revision can be marked entered-in-error");
            }
            append(context, ENTERED_IN_ERROR, id, copy(current, NoteState.ENTERED_IN_ERROR,
                    current.title(), current.content(), current.contentChecksum(), current.version(),
                    null, null, recordedRecipients, recordedReason));
            return repository.current(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return correction;
    }

    /**
     * Read the current revision and the full append-only history.
     *
     * @param id note identifier
     * @return the current revision and ordered history
     * @throws ApiException on invalid input, denial or a missing note
     */
    public NoteView get(String id) {
        NoteInputs.uuid(id, "id");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        NoteView view = audited(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, READ, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ClinicalNoteRevision current = repository.current(id).orElse(null);
            if (current == null) {
                conflict(context, READ, id, "NOT_FOUND", "clinical note not found");
                failure.set(new ApiException(404, "NOT_FOUND", "clinical note not found"));
                return null;
            }
            List<ClinicalNoteRevision> history = repository.history(id);
            audit.append(context, PURPOSE, READ, RESOURCE_TYPE, SCOPE, id, "SUCCESS");
            return new NoteView(current, history);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    private ClinicalNoteRevision load(String id, TenantContext context, String action) {
        ClinicalNoteRevision current = repository.current(id).orElse(null);
        if (current == null) {
            throw auditFailure(context, action, id, "NOT_FOUND", 404,
                    "NOT_FOUND", "clinical note not found");
        }
        return current;
    }

    private void append(TenantContext context, String action, String id, RevisionInput input) {
        int written;
        try {
            written = repository.insert(context.organizationId(), id, input);
        } catch (DuplicateKeyException exception) {
            // A concurrent writer can win the per-note version uniqueness even
            // after the advisory lock. The transaction is aborted, so no success
            // audit can survive; re-append a CONFLICT audit in the committed
            // second transaction owned by audited(...) (OPS-02).
            throw auditFailure(context, action, id, "CONFLICT", 409,
                    "CONFLICT", "the note conflicts with the stored record");
        }
        if (written == 0) {
            throw auditFailure(context, action, id, "CONFLICT", 412,
                    "STALE_VERSION", "the note changed since it was read");
        }
        audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, id, "SUCCESS");
    }

    private void conflict(TenantContext context, String action, String id, String code, String message) {
        String outcome = "NOT_FOUND".equals(code) ? "NOT_FOUND" : "CONFLICT";
        audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, id, outcome);
    }

    /**
     * Build a failure whose audit must outlive the rolling-back command transaction.
     *
     * @param context tenant context of the command
     * @param action  audited action
     * @param id      requested resource id
     * @param outcome audited outcome
     * @param status  public HTTP status
     * @param code    public error code
     * @param message public error message
     * @return the signal to throw inside the guarded command
     */
    private static AuditedFailure auditFailure(TenantContext context, String action, String id,
                                               String outcome, int status, String code, String message) {
        return new AuditedFailure(new ApiException(status, code, message), context, action, id, outcome);
    }

    /**
     * Internal signal for a command failure that already carries its audit facts.
     *
     * <p>Throwing this instead of writing the audit inline lets the audited
     * command wrapper re-append the row in a committed transaction after the
     * command rolls back.</p>
     */
    private static final class AuditedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ApiException failure;
        private final TenantContext context;
        private final String action;
        private final String id;
        private final String outcome;

        private AuditedFailure(ApiException failure, TenantContext context, String action,
                               String id, String outcome) {
            super(failure);
            this.failure = failure;
            this.context = context;
            this.action = action;
            this.id = id;
            this.outcome = outcome;
        }
    }

    private void requireVersion(ClinicalNoteRevision current, int expectedVersion,
                                TenantContext context, String action, String id) {
        if (current.version() != expectedVersion) {
            throw auditFailure(context, action, id, "CONFLICT", 412,
                    "STALE_VERSION", "the note changed since it was read");
        }
    }

    private void requireEditable(ClinicalNoteRevision current, TenantContext context,
                                 String action, String id) {
        if (!current.state().editable()) {
            throw auditFailure(context, action, id, "CONFLICT", 409,
                    "IMMUTABLE_REVISION", "a signed revision cannot be edited");
        }
    }

    private static void requireFields(ClinicalNoteRevision current) {
        NoteInputs.text(current.content(), "content", 20000);
        NoteInputs.text(current.title(), "title", 200);
        NoteInputs.text(current.templateCode(), "templateCode", 32);
        NoteInputs.text(current.language(), "language", 2);
        NoteInputs.version(current.templateVersion());
    }

    private void requirePatient(ClinicalNoteRevision current, TenantContext context, String action) {
        if (!repository.patientExists(current.patientId())) {
            throw auditFailure(context, action, current.id(), "CONFLICT", 422,
                    "INVALID_PATIENT_CONTEXT", "patient is not in the active organization");
        }
    }

    private static RevisionInput copy(ClinicalNoteRevision current, NoteState state, String title, String content,
                                      String checksum, Integer supersedes, String signedAt,
                                      String attestation, String recipients) {
        return new RevisionInput(state, current.patientId(), current.encounterId(), title, content,
                current.language(), current.templateCode(), current.templateVersion(),
                current.clinicalAuthorSubject(), current.effectiveAt(), signedAt, attestation,
                checksum, supersedes, null, recipients);
    }

    private static RevisionInput copy(ClinicalNoteRevision current, NoteState state, String title, String content,
                                      String checksum, Integer supersedes, String signedAt,
                                      String attestation, String recipients, String errorReason) {
        RevisionInput base = copy(current, state, title, content, checksum, supersedes, signedAt,
                attestation, recipients);
        return new RevisionInput(base.state(), base.patientId(), base.encounterId(), base.title(),
                base.content(), base.language(), base.templateCode(), base.templateVersion(),
                base.clinicalAuthorSubject(), base.effectiveAt(), base.signedAt(), base.attestation(),
                base.contentChecksum(), base.supersedesVersion(), errorReason, base.affectedRecipients());
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> allows(action, role))) {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing clinical note permission");
        }
        return null;
    }

    private static boolean allows(String action, String role) {
        if ("clinician".equals(role)) {
            return CLINICIAN_ACTIONS.contains(action);
        }
        if ("clinical_assistant".equals(role)) {
            return ASSISTANT_ACTIONS.contains(action);
        }
        return false;
    }
}
