package com.aurevia.exchange.release;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.function.IntFunction;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Patient release decisions: authorize, timed/hold visibility, withdrawal and correction.
 *
 * <p>Every command re-derives membership and roles inside a guarded transaction,
 * uses an optimistic version precondition for irreversible transitions, and commits
 * state, append-only history, outbox notification and audit together. Authorization
 * to release is separate from transmission and from clinical acknowledgment.</p>
 */
@Service
public class ReleaseService {

    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9._:-]{3,128}");
    private static final Pattern PATIENT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]{3,128}");

    private final ReleaseRepository repository;
    private final ReleaseActionPolicy policy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded release repository
     * @param policy       release action policy
     * @param transactions tenant-guarded transaction template
     * @param clock        shared UTC clock for visibility decisions
     */
    public ReleaseService(ReleaseRepository repository, ReleaseActionPolicy policy,
                          TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Authorize a release of one immutable report revision to a patient.
     *
     * @param command validated authorization command
     * @return the committed release, including whether it is visible now
     * @throws ApiException on invalid input, denial or a duplicate live authorization
     */
    public PatientRelease authorize(AuthorizeRelease command) {
        if (command == null || command.revisionId() == null || !REVISION.matcher(command.revisionId()).matches()) {
            throw new ApiException(422, "INVALID_REVISION_ID", "revision id must be 3-128 safe characters");
        }
        if (command.revisionVersion() < 1) {
            throw new ApiException(422, "INVALID_REVISION_VERSION", "revision version must be positive");
        }
        if (command.patientId() == null || !PATIENT.matcher(command.patientId()).matches()) {
            throw new ApiException(422, "INVALID_PATIENT_ID", "patient id must be a UUID");
        }
        if (command.policyVersion() < 1) {
            throw new ApiException(422, "INVALID_POLICY_VERSION", "policy version must be positive");
        }
        ReleaseKind kind = ReleaseKind.parse(command.kind());
        Instant effectiveFrom = resolveEffectiveFrom(kind, command.effectiveFrom());
        Instant holdExpiresAt = null;
        if (kind == ReleaseKind.HOLD) {
            if (command.holdReason() == null || command.holdReason().isBlank()) {
                throw new ApiException(422, "INVALID_HOLD_REASON", "a hold requires a reason");
            }
            holdExpiresAt = parseInstant(command.holdExpiresAt(), "INVALID_HOLD_EXPIRY", "hold expiry is required");
            if (!holdExpiresAt.isAfter(clock.instant())) {
                throw new ApiException(422, "INVALID_HOLD_EXPIRY", "hold expiry must be in the future");
            }
        }
        final Instant expiry = holdExpiresAt;
        final ReleaseKind finalKind = kind;
        final ReleaseState initialState = finalKind == ReleaseKind.HOLD ? ReleaseState.HELD : ReleaseState.AUTHORIZED;
        AtomicReference<ApiException> failure = new AtomicReference<>();
        PatientRelease result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ReleaseActionPolicy.AUTHORIZE, command.revisionId());
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findAnyByPatientAndRevision(command.patientId(), command.revisionId()).isPresent()) {
                repository.appendAudit(context, ReleaseActionPolicy.AUTHORIZE, command.revisionId(), "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "a release already exists for this revision"));
                return null;
            }
            String releaseId;
            try {
                releaseId = repository.insertRelease(context.organizationId(), command.patientId(),
                        command.revisionId(), command.revisionVersion(), context.organizationId(),
                        command.policyVersion(), finalKind.name(), initialState.name(), effectiveFrom,
                        finalKind == ReleaseKind.HOLD ? command.holdReason() : null, expiry, null);
            } catch (DuplicateKeyException exception) {
                // A unique violation aborts the PostgreSQL transaction. Issuing further SQL here
                // (including a conflict audit) would fail with 25P02 and surface as a 500 instead
                // of the documented 409. The pre-insert live-release check already audits any
                // conflict it can detect before the insert is attempted.
                failure.set(new ApiException(409, "CONFLICT", "an active release already exists for this revision"));
                return null;
            }
            repository.insertEvent(context, releaseId, "RELEASE_AUTHORIZE", null, initialState.name(), null);
            repository.insertNotification(context, releaseId, "RELEASE_AUTHORIZED", "linked-patient");
            repository.appendAudit(context, ReleaseActionPolicy.AUTHORIZE, command.revisionId(), "SUCCESS");
            return repository.findRelease(releaseId).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Lift a held release after an authorized review.
     *
     * @param id              tenant-local release identifier
     * @param expectedVersion current version precondition
     * @param note            non-sensitive review note
     * @return the now-authorized release
     * @throws ApiException on invalid input, denial, concealment or a stale version
     */
    public PatientRelease liftHold(String id, int expectedVersion, String note) {
        requireId(id);
        requireReason(note, "INVALID_REVIEW_NOTE", "a review note is required");
        return transition(id, expectedVersion, ReleaseState.AUTHORIZED,
                ReleaseActionPolicy.LIFT_HOLD, id, note, "HOLD_LIFTED", "linked-patient",
                expected -> repository.liftHold(id, expected));
    }

    /**
     * Withdraw a release so no future download is authorized. History is retained.
     *
     * @param id              tenant-local release identifier
     * @param expectedVersion current version precondition
     * @param reason          non-sensitive withdrawal reason
     * @return the withdrawn release
     * @throws ApiException on invalid input, denial, concealment or a stale version
     */
    public PatientRelease withdraw(String id, int expectedVersion, String reason) {
        requireId(id);
        requireReason(reason, "INVALID_WITHDRAW_REASON", "a withdrawal reason is required");
        return transition(id, expectedVersion, ReleaseState.WITHDRAWN,
                ReleaseActionPolicy.WITHDRAW, id, reason, "RELEASE_WITHDRAWN", "prior-recipients",
                expected -> repository.withdraw(id, expected));
    }

    /**
     * Correct a release: revoke the old revision and authorize its replacement atomically.
     *
     * @param id                     tenant-local release identifier being corrected
     * @param expectedVersion        current version precondition
     * @param correctedRevisionId    replacement immutable revision identifier
     * @param correctedRevisionVersion version of the replacement revision
     * @param reason                 non-sensitive correction reason
     * @return the new release linked to the corrected one
     * @throws ApiException on invalid input, denial, concealment or a stale version
     */
    public PatientRelease correct(String id, int expectedVersion, String correctedRevisionId,
                                  int correctedRevisionVersion, String reason) {
        requireId(id);
        if (correctedRevisionId == null || !REVISION.matcher(correctedRevisionId).matches()) {
            throw new ApiException(422, "INVALID_REVISION_ID", "corrected revision id must be 3-128 safe characters");
        }
        if (correctedRevisionVersion < 1) {
            throw new ApiException(422, "INVALID_REVISION_VERSION", "corrected revision version must be positive");
        }
        requireReason(reason, "INVALID_CORRECTION_REASON", "a correction reason is required");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        PatientRelease result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ReleaseActionPolicy.CORRECT, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            PatientRelease current = repository.findRelease(id).orElse(null);
            if (current == null) {
                repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "release not found"));
                return null;
            }
            if (current.version() != expectedVersion) {
                repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "STALE");
                failure.set(new ApiException(412, "STALE_VERSION", "release version is stale"));
                return null;
            }
            if (!current.state().canTransitionTo(ReleaseState.CORRECTED)) {
                repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "CONFLICT");
                failure.set(new ApiException(409, "INVALID_TRANSITION", "release cannot be corrected in its current state"));
                return null;
            }
            // Only an authorized release can be corrected: ReleaseState.canTransitionTo rejects a
            // held one above, so lifting a hold stays a separate decision.
            // A correction must point at a different immutable revision. Re-pointing at the row
            // just marked CORRECTED would re-authorize the retracted result.
            if (correctedRevisionId.equals(current.revisionId())) {
                repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "a correction must use a different revision"));
                return null;
            }
            // The replacement revision must never have been released before, including a
            // terminal row: an immutable revision is one-way. Detect it before the CAS update
            // so the conflict is audited; a concurrent insert still fails on the unique index.
            if (repository.findAnyByPatientAndRevision(current.patientId(), correctedRevisionId).isPresent()) {
                repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "a release already exists for the corrected revision"));
                return null;
            }
            if (repository.correct(id, expectedVersion) == 0) {
                repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "release changed concurrently"));
                return null;
            }
            repository.insertEvent(context, id, "RELEASE_CORRECT", current.state().name(),
                    ReleaseState.CORRECTED.name(), reason);
            repository.insertNotification(context, id, "RELEASE_CORRECTED", "prior-recipients");
            // A corrected revision inherits the original release policy AND its effective
            // instant. A hold that a reviewer has already lifted is converted to IMMEDIATE
            // (the replacement carries no hold reason/expiry, which V24 forbids on a HOLD row,
            // and needs no second review), but a configured future instant is preserved for
            // every kind so a correction can never open access before the release time.
            boolean liftedHold = current.kind() == ReleaseKind.HOLD;
            ReleaseKind replacementKind = liftedHold ? ReleaseKind.IMMEDIATE : current.kind();
            Instant replacementEffectiveFrom = current.effectiveFrom();
            String replacement = repository.insertRelease(context.organizationId(), current.patientId(),
                    correctedRevisionId, correctedRevisionVersion, context.organizationId(),
                    current.policyVersion(), replacementKind.name(), ReleaseState.AUTHORIZED.name(),
                    replacementEffectiveFrom, null, null, id);
            repository.insertEvent(context, replacement, "RELEASE_AUTHORIZE", null,
                    ReleaseState.AUTHORIZED.name(), reason);
            repository.insertNotification(context, replacement, "RELEASE_AUTHORIZED", "linked-patient");
            repository.appendAudit(context, ReleaseActionPolicy.CORRECT, id, "SUCCESS");
            return repository.findRelease(replacement).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Read one release with its current visibility decision.
     *
     * @param id tenant-local release identifier
     * @return the release projection
     * @throws ApiException on denial, concealment or an unknown identifier
     */
    public PatientRelease get(String id) {
        requireId(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        PatientRelease result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ReleaseActionPolicy.READ, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            PatientRelease found = repository.findRelease(id).orElse(null);
            if (found == null) {
                repository.appendAudit(context, ReleaseActionPolicy.READ, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "release not found"));
                return null;
            }
            repository.appendAudit(context, ReleaseActionPolicy.READ, id, "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Read the append-only decision history of one release.
     *
     * @param id tenant-local release identifier
     * @return decision events in commit order
     * @throws ApiException on denial, concealment or an unknown identifier
     */
    public List<ReleaseEvent> history(String id) {
        requireId(id);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<ReleaseEvent> result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, ReleaseActionPolicy.HISTORY, id);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findRelease(id).isEmpty()) {
                repository.appendAudit(context, ReleaseActionPolicy.HISTORY, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "release not found"));
                return null;
            }
            List<ReleaseEvent> events = repository.findEvents(id);
            repository.appendAudit(context, ReleaseActionPolicy.HISTORY, id, "SUCCESS");
            return events;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * List the revision identifiers a caller may currently access for one patient.
     *
     * <p>This is a patient-scoped query, not a staff directory. The caller is either
     * the verified patient whose active chart link points at {@code patientId}, or
     * a care-team clinician/tenant administrator. Both checks are re-derived inside
     * the guarded transaction. A receptionist, privacy auditor or patient linked to
     * another chart is denied and the denial is audited in the same transaction.</p>
     *
     * @param patientId tenant-local patient identifier
     * @return currently visible revision identifiers
     * @throws ApiException on invalid input, missing context or denial
     */
    public List<String> visibleRevisions(String patientId) {
        if (patientId == null || !PATIENT.matcher(patientId).matches()) {
            throw new ApiException(422, "INVALID_PATIENT_ID", "patient id must be a UUID");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<String> result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            if (context == null) {
                failure.set(new ApiException(401, "UNAUTHENTICATED", "authentication required"));
                return null;
            }
            // A verified, active link to this exact chart is the patient's own
            // authorization. It is read from the authoritative ledger on every
            // request, so an unlink or a link to another chart denies at once.
            if (!repository.hasVerifiedChartLink(context.subject(), patientId)) {
                ApiException denial = authorize(context, ReleaseActionPolicy.ACCESS, patientId);
                if (denial != null) {
                    failure.set(denial);
                    return null;
                }
            }
            List<String> visible = repository.findAuthorizedByPatient(patientId).stream()
                    .filter(release -> release.visibleNow(clock))
                    .map(PatientRelease::revisionId)
                    .toList();
            repository.appendAudit(context, ReleaseActionPolicy.ACCESS, patientId, "SUCCESS");
            return visible;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private PatientRelease transition(String id, int expectedVersion, ReleaseState target, String action,
                                      String auditResource, String reason, String notificationType,
                                      String recipientScope, IntFunction<Integer> update) {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        PatientRelease result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, action, auditResource);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            PatientRelease current = repository.findRelease(id).orElse(null);
            if (current == null) {
                repository.appendAudit(context, action, auditResource, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "release not found"));
                return null;
            }
            if (current.version() != expectedVersion) {
                repository.appendAudit(context, action, auditResource, "STALE");
                failure.set(new ApiException(412, "STALE_VERSION", "release version is stale"));
                return null;
            }
            if (!current.state().canTransitionTo(target)) {
                repository.appendAudit(context, action, auditResource, "CONFLICT");
                failure.set(new ApiException(409, "INVALID_TRANSITION", "release cannot make this transition"));
                return null;
            }
            if (update.apply(expectedVersion) == 0) {
                repository.appendAudit(context, action, auditResource, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "release changed concurrently"));
                return null;
            }
            repository.insertEvent(context, id, action, current.state().name(), target.name(), reason);
            repository.insertNotification(context, id, notificationType, recipientScope);
            repository.appendAudit(context, action, auditResource, "SUCCESS");
            return repository.findRelease(id).orElse(null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private Instant resolveEffectiveFrom(ReleaseKind kind, String raw) {
        if (kind == ReleaseKind.TIMED && (raw == null || raw.isBlank())) {
            throw new ApiException(422, "INVALID_EFFECTIVE_FROM", "a timed release requires an effective instant");
        }
        if (raw == null || raw.isBlank()) {
            return clock.instant();
        }
        return parseInstant(raw, "INVALID_EFFECTIVE_FROM", "effectiveFrom must be a UTC instant");
    }

    private Instant parseInstant(String raw, String code, String message) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(422, code, message);
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, code, message);
        }
    }

    private void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new ApiException(422, "INVALID_RELEASE_ID", "release id must be 3-128 safe characters");
        }
    }

    private void requireReason(String reason, String code, String message) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }

    private ApiException authorize(TenantContext context, String action, String resourceId) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            repository.appendAudit(context, action, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing action permission");
        }
        return null;
    }
}
