package com.aurevia.smartclinic.g0.s_g0_06;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.g0.s_g0_06.DuplicateRepository.DuplicatePair;
import com.aurevia.smartclinic.patient.Patient;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Same-tenant duplicate review and all-or-nothing consolidation invariants (IAM-04).
 *
 * <p>Reviewers see both identities, conflicts, blocking dependencies and a dry run.
 * Consolidation needs two different authorized reviewers, a current version
 * precondition and a pair lock; the association, event and audit are appended in
 * one transaction. An erroneous consolidation is reversed by an audited support
 * workflow that appends new versions instead of changing history.</p>
 */
@Service
public class ConsolidationService {

    private static final Pattern ID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9 _.:'-]{3,200}");

    private final DuplicateRepository repository;
    private final DuplicatePolicy policy;
    private final TransactionTemplate transactions;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded duplicate repository
     * @param policy       identity-administration action policy
     * @param transactions tenant-guarded transaction template
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborators")
    public ConsolidationService(DuplicateRepository repository, DuplicatePolicy policy,
                                TransactionTemplate transactions) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
    }

    /**
     * List same-tenant likely-duplicate pairs for review.
     *
     * @return review worklist ordered by normalized name
     * @throws ApiException when denied
     */
    public List<DuplicatePair> worklist() {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<DuplicatePair> pairs = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(DuplicatePolicy.REVIEW, "DUPLICATE_WORKLIST", "LIST");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<DuplicatePair> found = repository.likelyPairs();
            repository.appendAudit(context, "DUPLICATE_WORKLIST", "LIST", "SUCCESS");
            return found;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return pairs;
    }

    /**
     * Show both identities, conflicts, dependencies, a dry run and current visibility.
     *
     * @param survivorId  surviving patient identifier
     * @param duplicateId duplicate patient identifier
     * @return non-mutating review detail
     * @throws ApiException on invalid input, denial or an unknown patient
     */
    public ReviewDetail review(String survivorId, String duplicateId) {
        validateId(survivorId, "SURVIVOR");
        validateId(duplicateId, "DUPLICATE");
        if (survivorId.equals(duplicateId)) {
            throw new ApiException(422, "INVALID_PAIR", "a patient cannot be its own duplicate");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ReviewDetail detail = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(DuplicatePolicy.REVIEW, "DUPLICATE_REVIEW", duplicateId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Patient survivor = repository.findPatient(survivorId).orElse(null);
            Patient duplicate = repository.findPatient(duplicateId).orElse(null);
            if (survivor == null || duplicate == null) {
                repository.appendAudit(context, "DUPLICATE_REVIEW", duplicateId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            Optional<DuplicateReview> review = repository.findReviewByPair(survivorId, duplicateId);
            List<ReviewDetail.Dependency> dependencies = repository.dependencies(duplicateId);
            List<ReviewDetail.Conflict> conflicts = conflicts(survivor, duplicate);
            String dryRun = dryRun(duplicate, conflicts, dependencies);
            repository.appendAudit(context, "DUPLICATE_REVIEW", duplicateId, "SUCCESS");
            return new ReviewDetail(review.orElse(null), survivor, duplicate, conflicts, dependencies,
                    dryRun, repository.effectivePatientId(duplicateId));
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return detail;
    }

    /**
     * Open a review ticket owned by the authenticated reviewer.
     *
     * <p>Re-opening an existing ticket is idempotent. A pair already consolidated
     * cannot be reopened through this command; the correction workflow owns it.</p>
     *
     * @param survivorId  surviving patient identifier
     * @param duplicateId duplicate patient identifier
     * @return the open review ticket
     * @throws ApiException on invalid input, denial, conflict or an unknown patient
     */
    public DuplicateReview open(String survivorId, String duplicateId) {
        validateId(survivorId, "SURVIVOR");
        validateId(duplicateId, "DUPLICATE");
        if (survivorId.equals(duplicateId)) {
            throw new ApiException(422, "INVALID_PAIR", "a patient cannot be its own duplicate");
        }
        AtomicReference<ApiException> failure = new AtomicReference<>();
        DuplicateReview review = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(DuplicatePolicy.APPROVE, "DUPLICATE_REVIEW_OPEN", duplicateId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.findPatient(survivorId).isEmpty() || repository.findPatient(duplicateId).isEmpty()) {
                repository.appendAudit(context, "DUPLICATE_REVIEW_OPEN", duplicateId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            Optional<DuplicateReview> existing = repository.findReviewByPair(survivorId, duplicateId);
            if (existing.isPresent()) {
                DuplicateReview found = existing.get();
                if (repository.findCurrentConsolidation(found.id()).isPresent()) {
                    repository.appendAudit(context, "DUPLICATE_REVIEW_OPEN", found.id(), "CONFLICT");
                    failure.set(new ApiException(409, "CONFLICT", "pair already consolidated"));
                    return null;
                }
                repository.appendAudit(context, "DUPLICATE_REVIEW_OPEN", found.id(), "SUCCESS");
                return found;
            }
            try {
                repository.insertReview(context.organizationId(), survivorId, duplicateId, context.subject());
            } catch (DuplicateKeyException exception) {
                failure.set(new ApiException(409, "CONFLICT", "review already exists"));
                return null;
            }
            DuplicateReview created = repository.findReviewByPair(survivorId, duplicateId).orElse(null);
            repository.appendAudit(context, "DUPLICATE_REVIEW_OPEN",
                    created == null ? duplicateId : created.id(), "SUCCESS");
            return created;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return review;
    }

    /**
     * Approve a consolidation as a second authorized reviewer.
     *
     * <p>Both records are locked first, then re-read so the version precondition
     * is compared against the current committed revision inside the lock. Unresolved
     * ambiguous dependencies block the command. All appends commit together or not
     * at all.</p>
     *
     * @param reviewId       open review identifier
     * @param expectedVersion current version of the duplicate patient
     * @param reason         recorded business reason
     * @return the appended consolidation event
     * @throws ApiException on invalid input, denial, stale version or a conflict
     */
    public Consolidation consolidate(String reviewId, int expectedVersion, String reason) {
        validateId(reviewId, "REVIEW");
        validateReason(reason);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Consolidation result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(DuplicatePolicy.APPROVE, "CONSOLIDATE", reviewId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            DuplicateReview review = repository.findReviewById(reviewId).orElse(null);
            if (review == null) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "review not found"));
                return null;
            }
            if (context.subject().equals(review.openedBySubject())) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "DENIED");
                failure.set(new ApiException(409, "SECOND_REVIEWER_REQUIRED",
                        "a different authorized reviewer must approve"));
                return null;
            }
            if (repository.findCurrentConsolidation(reviewId).isPresent()) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "review already consolidated"));
                return null;
            }
            // Lock before reading: a concurrent patient edit cannot slip between the
            // version read and the compare-and-set, so the snapshot stays current.
            if (repository.lockPair(review.survivorId(), review.duplicateId()) != 2) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "both patient records must be locked"));
                return null;
            }
            Patient survivor = repository.findPatient(review.survivorId()).orElse(null);
            Patient duplicate = repository.findPatient(review.duplicateId()).orElse(null);
            if (survivor == null || duplicate == null) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            if (duplicate.version() != expectedVersion) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "STALE");
                failure.set(new ApiException(412, "STALE_VERSION", "patient version changed"));
                return null;
            }
            if (repository.hasUnresolvedBlockers(duplicate.id()) > 0) {
                repository.appendAudit(context, "CONSOLIDATE", reviewId, "BLOCKED");
                failure.set(new ApiException(409, "UNRESOLVED_DEPENDENCY",
                        "ambiguous orders, links or grants must be resolved first"));
                return null;
            }
            String associationId = repository.newId();
            String consolidationId = repository.newId();
            try {
                repository.insertAssociation(associationId, context.organizationId(), duplicate.id(),
                        duplicate.version(), survivor.id(), "ACTIVE", duplicate.mrn(), duplicate.mrnIssuer(),
                        duplicate.identifiers(), duplicate.consents(), reason);
                repository.insertConsolidation(consolidationId, context.organizationId(), reviewId, associationId,
                        survivor.id(), duplicate.id(), "CONSOLIDATED", context.subject(),
                        review.openedBySubject(), reason, null);
            } catch (DuplicateKeyException exception) {
                status.setRollbackOnly();
                failure.set(new ApiException(409, "CONFLICT", "consolidation already committed"));
                return null;
            }
            repository.appendAudit(context, "CONSOLIDATE", reviewId, "SUCCESS");
            return new Consolidation(consolidationId, reviewId, survivor.id(), duplicate.id(), 1,
                    "CONSOLIDATED", context.subject(), review.openedBySubject(), reason, null);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Reverse an erroneous consolidation through the audited support workflow.
     *
     * @param reviewId       consolidated review identifier
     * @param expectedVersion current consolidation version
     * @param reason         recorded correction reason
     * @return the appended correction event
     * @throws ApiException on invalid input, denial, stale version or a conflict
     */
    public Consolidation correct(String reviewId, int expectedVersion, String reason) {
        validateId(reviewId, "REVIEW");
        validateReason(reason);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        Consolidation result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(DuplicatePolicy.CORRECT, "CONSOLIDATION_CORRECT", reviewId);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Consolidation current = repository.findCurrentConsolidation(reviewId).orElse(null);
            if (current == null) {
                repository.appendAudit(context, "CONSOLIDATION_CORRECT", reviewId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "consolidation not found"));
                return null;
            }
            if (current.version() != expectedVersion) {
                repository.appendAudit(context, "CONSOLIDATION_CORRECT", reviewId, "STALE");
                failure.set(new ApiException(412, "STALE_VERSION", "consolidation version changed"));
                return null;
            }
            if (!"CONSOLIDATED".equals(current.status())) {
                repository.appendAudit(context, "CONSOLIDATION_CORRECT", reviewId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "consolidation already corrected"));
                return null;
            }
            Patient duplicate = repository.findPatient(current.duplicateId()).orElse(null);
            if (duplicate == null) {
                repository.appendAudit(context, "CONSOLIDATION_CORRECT", reviewId, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "patient not found"));
                return null;
            }
            if (repository.lockPair(current.survivorId(), current.duplicateId()) != 2) {
                repository.appendAudit(context, "CONSOLIDATION_CORRECT", reviewId, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "both patient records must be locked"));
                return null;
            }
            String associationId = repository.newId();
            String consolidationId = repository.newId();
            try {
                repository.insertAssociation(associationId, context.organizationId(), duplicate.id(),
                        duplicate.version(), current.survivorId(), "REVERSED", duplicate.mrn(),
                        duplicate.mrnIssuer(), duplicate.identifiers(), duplicate.consents(), reason);
                repository.insertConsolidation(consolidationId, context.organizationId(), reviewId, associationId,
                        current.survivorId(), duplicate.id(), "CORRECTED", context.subject(),
                        current.approverSubject(), current.reason(), reason);
            } catch (DuplicateKeyException exception) {
                status.setRollbackOnly();
                failure.set(new ApiException(409, "CONFLICT", "correction already committed"));
                return null;
            }
            repository.appendAudit(context, "CONSOLIDATION_CORRECT", reviewId, "SUCCESS");
            return new Consolidation(consolidationId, reviewId, current.survivorId(), duplicate.id(),
                    current.version() + 1, "CORRECTED", context.subject(), current.approverSubject(),
                    current.reason(), reason);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    private ApiException authorize(String action, String auditAction, String resourceId) {
        TenantContext context = TenantContext.current();
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, auditAction, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream()
                .noneMatch(role -> policy.allows(action, role))) {
            repository.appendAudit(context, auditAction, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing identity permission");
        }
        return null;
    }

    private static List<ReviewDetail.Conflict> conflicts(Patient survivor, Patient duplicate) {
        List<ReviewDetail.Conflict> found = new ArrayList<>();
        addConflict(found, "mrn", survivor.mrn(), duplicate.mrn());
        addConflict(found, "mrnIssuer", survivor.mrnIssuer(), duplicate.mrnIssuer());
        addConflict(found, "displayName", survivor.displayName(), duplicate.displayName());
        addConflict(found, "dob", survivor.dob(), duplicate.dob());
        addConflict(found, "language", survivor.language(), duplicate.language());
        addConflict(found, "identifiers", survivor.identifiers(), duplicate.identifiers());
        addConflict(found, "consents", survivor.consents(), duplicate.consents());
        return found;
    }

    private static void addConflict(List<ReviewDetail.Conflict> found, String field,
                                    String survivorValue, String duplicateValue) {
        if (!Objects.equals(survivorValue, duplicateValue)) {
            found.add(new ReviewDetail.Conflict(field, text(survivorValue), text(duplicateValue)));
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String dryRun(Patient duplicate, List<ReviewDetail.Conflict> conflicts,
                                 List<ReviewDetail.Dependency> dependencies) {
        long blockers = dependencies.stream()
                .filter(dependency -> dependency.ambiguous() && !dependency.resolved())
                .count();
        return "move " + duplicate.id() + " -> survivor; preserve " + duplicate.mrn() + "@"
                + duplicate.mrnIssuer() + "; conflicts=" + conflicts.size() + "; blockers=" + blockers;
    }

    private static void validateId(String id, String field) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new ApiException(422, "INVALID_" + field, field + " must be a UUID");
        }
    }

    private static void validateReason(String reason) {
        if (reason == null || !REASON.matcher(reason).matches()) {
            throw new ApiException(422, "INVALID_REASON", "reason must be 3-200 bounded characters");
        }
    }
}
