package com.aurevia.scheduling.followup;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;

/**
 * Transaction boundary for the CLN-06 follow-up commands.
 *
 * <p>The domain service owns validation, authorization and the rule explanation;
 * this application boundary makes each command atomic with its persistence and
 * audit facts. A domain denial writes its DENIED audit row before throwing, so the
 * callback catches that error, lets the transaction commit, then rethrows it to
 * the caller. Any other failure propagates immediately and rolls the command back.</p>
 */
@Service
public class FollowUpCommandService {

    private final FollowUpService service;
    private final TransactionTemplate transactions;

    /**
     * Create the command boundary.
     *
     * @param service      follow-up domain service
     * @param transactions tenant-guarded transaction template
     */
    public FollowUpCommandService(FollowUpService service, TransactionTemplate transactions) {
        this.service = service;
        this.transactions = transactions;
    }

    /**
     * Suggest a follow-up date and its rule explanation.
     *
     * @param patientId    tenant-local patient identifier
     * @param facilityCode tenant-local facility code
     * @param visitType    visit type code
     * @param encounterAt  UTC instant of the encounter
     * @return the stored suggestion
     */
    public FollowUpSuggestion suggest(String patientId, String facilityCode, String visitType,
                                      String encounterAt) {
        return inTransaction(() -> service.suggest(patientId, facilityCode, visitType, encounterAt));
    }

    /**
     * Override the suggested date with recorded provenance.
     *
     * @param suggestionId suggestion identifier
     * @param requestedDate replacement civil date
     * @param reason        required override reason
     * @return the new suggestion version
     */
    public FollowUpSuggestion override(String suggestionId, String requestedDate, String reason) {
        return inTransaction(() -> service.override(suggestionId, requestedDate, reason));
    }

    /**
     * Explicitly confirm a suggestion, creating one booking.
     *
     * @param suggestionId    suggestion identifier
     * @param expectedVersion version read by the caller
     * @return the confirmed suggestion with its booking identifier
     */
    public FollowUpSuggestion confirm(String suggestionId, int expectedVersion) {
        return inTransaction(() -> service.confirm(suggestionId, expectedVersion));
    }

    /**
     * Open a care-team action task.
     *
     * @param followUpId  suggestion identifier the task supports
     * @param owner       accountable owner subject
     * @param dueDate     facility-local civil due date
     * @param instruction action for the owner
     * @return the open task
     */
    public CareTeamTask createTask(String followUpId, String owner, String dueDate, String instruction) {
        return inTransaction(() -> service.createTask(followUpId, owner, dueDate, instruction));
    }

    /**
     * Record the outcome of an open care-team task.
     *
     * @param taskId  task identifier
     * @param outcome recorded outcome
     * @return the completed task
     */
    public CareTeamTask completeTask(String taskId, String outcome) {
        return inTransaction(() -> service.completeTask(taskId, outcome));
    }

    /**
     * List the active tenant's tasks for a suggestion.
     *
     * @param followUpId suggestion identifier
     * @return the visible tasks
     */
    public List<CareTeamTask> tasks(String followUpId) {
        return inTransaction(() -> service.tasks(followUpId));
    }

    private <T> T inTransaction(Supplier<T> command) {
        AtomicReference<ApiException> denial = new AtomicReference<>();
        T result;
        try {
            result = transactions.execute(status -> {
                try {
                    return command.get();
                } catch (ApiException exception) {
                    denial.set(exception);
                    return null;
                }
            });
        } catch (UnexpectedRollbackException exception) {
            // A nested command (the booking service on confirmation) can mark the
            // shared transaction rollback-only before its own domain error reaches
            // here. Surface that original decision rather than an opaque 500.
            if (denial.get() != null) {
                throw denial.get();
            }
            throw exception;
        }
        if (denial.get() != null) {
            throw denial.get();
        }
        return result;
    }
}
