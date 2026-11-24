package com.aurevia.scheduling.followup;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A care-team action task with an owner, a due date and an outcome (CLN-06).
 *
 * <p>Only an open task can be completed; the outcome is recorded on the same
 * immutable record so the closed task keeps its original owner and due date.</p>
 *
 * @param id             opaque task identifier
 * @param organizationId owning tenant
 * @param followUpId     follow-up suggestion this task supports
 * @param owner          accountable owner subject
 * @param dueDate        facility-local civil due date
 * @param instruction    action for the owner
 * @param status         {@code OPEN} or {@code COMPLETED}
 * @param outcome        recorded outcome, or {@code null} while open
 * @param createdBy      subject that opened the task
 * @param createdAt      instant the task was opened
 * @return this record
 */
public record CareTeamTask(String id, String organizationId, String followUpId, String owner,
                           LocalDate dueDate, String instruction, String status, String outcome,
                           String createdBy, Instant createdAt) {

    /** Open task awaiting an outcome. */
    public static final String OPEN = "OPEN";
    /** Completed task with a recorded outcome. */
    public static final String COMPLETED = "COMPLETED";

    /**
     * Close the task with an outcome.
     *
     * @param value recorded outcome
     * @return a completed copy of this task
     */
    public CareTeamTask complete(String value) {
        return new CareTeamTask(id, organizationId, followUpId, owner, dueDate, instruction,
                COMPLETED, value, createdBy, createdAt);
    }
}
