package com.aurevia.scheduling.followup;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-scoped persistence for follow-up suggestions and care-team tasks.
 *
 * <p>Every lookup takes the active organization so an identifier from another
 * tenant is never resolvable even when the local identifier is identical.</p>
 */
public interface FollowUpStore {

    /**
     * Insert or replace one suggestion at its current version.
     *
     * @param suggestion suggestion to store
     */
    void saveSuggestion(FollowUpSuggestion suggestion);

    /**
     * Find a suggestion inside one tenant.
     *
     * @param organizationId active organization
     * @param id             suggestion identifier
     * @return the matching suggestion, or empty
     */
    Optional<FollowUpSuggestion> findSuggestion(String organizationId, String id);

    /**
     * Insert or replace one care-team task.
     *
     * @param task task to store
     */
    void saveTask(CareTeamTask task);

    /**
     * Find a task inside one tenant.
     *
     * @param organizationId active organization
     * @param id             task identifier
     * @return the matching task, or empty
     */
    Optional<CareTeamTask> findTask(String organizationId, String id);

    /**
     * List tasks for one suggestion inside one tenant.
     *
     * @param organizationId active organization
     * @param followUpId     suggestion identifier
     * @return the visible tasks
     */
    List<CareTeamTask> tasksFor(String organizationId, String followUpId);
}
