package com.aurevia.scheduling.checkin;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Action policy for the CLN-02 check-in and worklist slice.
 *
 * <p>The policy is deliberately local to this slice: it does not widen the
 * shared {@code ActionPolicy}, so an unrelated story cannot accidentally gain
 * an identity-verification or handoff permission. Membership and role
 * assignments are re-derived per protected request by the caller; this class
 * only decides whether a named role may perform a named action.</p>
 *
 * <p>Reception verifies identity and runs the clerical worklist but may not
 * open an encounter. A clinical assistant may record vitals and hand off a
 * queue item. Only a clinician may open the encounter. A privacy auditor has
 * no clinical or worklist permission.</p>
 */
@Component
public class WorklistPolicy {

    /** Record the two-identifier or approved-alternate evidence at check-in. */
    public static final String IDENTITY_VERIFY = "IDENTITY_VERIFY";
    /** Complete the booked to checked-in transition once identity is verified. */
    public static final String CHECK_IN = "APPOINTMENT_CHECK_IN";
    /** Read the organization/facility worklist. */
    public static final String WORKLIST_READ = "WORKLIST_READ";
    /** Assign an unassigned worklist item to an active member. */
    public static final String WORKLIST_ASSIGN = "WORKLIST_ASSIGN";
    /** Transfer an assigned item to another active member. */
    public static final String HANDOFF = "WORKLIST_HANDOFF";
    /** Record vitals; the vitals store itself belongs to the CLN-03 slice. */
    public static final String VITALS_RECORD = "VITALS_RECORD";
    /** Open the clinical encounter after a verified check-in. */
    public static final String ENCOUNTER_OPEN = "ENCOUNTER_OPEN";
    /** Link an unscheduled arrival to intake. */
    public static final String INTAKE_LINK = "INTAKE_LINK";

    // Tenant administration is an organizational role: it may run the clerical
    // worklist but holds no clinical permission. Opening an encounter or recording
    // vitals follows separate clinical privileges (spec role table, IAM-01).
    private static final Set<String> ADMIN_ACTIONS = Set.of(
            IDENTITY_VERIFY, CHECK_IN, WORKLIST_READ, WORKLIST_ASSIGN, HANDOFF, INTAKE_LINK);
    private static final Set<String> RECEPTION_ACTIONS = Set.of(
            IDENTITY_VERIFY, CHECK_IN, WORKLIST_READ, WORKLIST_ASSIGN, HANDOFF, INTAKE_LINK);
    private static final Set<String> ASSISTANT_ACTIONS = Set.of(
            WORKLIST_READ, VITALS_RECORD, HANDOFF, INTAKE_LINK);
    private static final Set<String> CLINICIAN_ACTIONS = Set.of(
            WORKLIST_READ, VITALS_RECORD, ENCOUNTER_OPEN);

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key such as
     *               {@link com.aurevia.scheduling.checkin.WorklistPolicy#IDENTITY_VERIFY}
     * @param role   role key assigned in the active organization
     * @return {@code true} when the role is allowed, {@code false} otherwise
     */
    public boolean allows(String action, String role) {
        return switch (role == null ? "" : role) {
            case "tenant_admin" -> ADMIN_ACTIONS.contains(action);
            case "reception" -> RECEPTION_ACTIONS.contains(action);
            case "clinical_assistant" -> ASSISTANT_ACTIONS.contains(action);
            case "clinician" -> CLINICIAN_ACTIONS.contains(action);
            default -> false;
        };
    }
}
