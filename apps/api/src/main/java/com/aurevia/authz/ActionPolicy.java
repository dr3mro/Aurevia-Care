package com.aurevia.authz;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for the delivered slices.
 *
 * <p>The policy is intentionally small: each slice names the exact actions its
 * roles may perform. Scheduling separates clerical transitions (check-in,
 * cancel, no show) from clinical ones (start, complete) so reception cannot
 * close an encounter. Broader role matrices belong to later identity stories.</p>
 */
@Component
public class ActionPolicy {

    /** Read one appointment and its history. */
    public static final String APPOINTMENT_READ = "APPOINTMENT_READ";
    /** Create a confirmed appointment. */
    public static final String APPOINTMENT_BOOK = "APPOINTMENT_BOOK";
    /** Create a temporary hold. */
    public static final String APPOINTMENT_HOLD = "APPOINTMENT_HOLD";
    /** Convert a hold into a confirmed appointment. */
    public static final String APPOINTMENT_CONFIRM = "APPOINTMENT_CONFIRM";
    /** Move an appointment to a new slot. */
    public static final String APPOINTMENT_RESCHEDULE = "APPOINTMENT_RESCHEDULE";
    /** Record that the patient arrived. */
    public static final String APPOINTMENT_CHECK_IN = "APPOINTMENT_CHECK_IN";
    /** Start the encounter. */
    public static final String APPOINTMENT_START = "APPOINTMENT_START";
    /** Complete the encounter. */
    public static final String APPOINTMENT_COMPLETE = "APPOINTMENT_COMPLETE";
    /** Cancel an appointment with a reason. */
    public static final String APPOINTMENT_CANCEL = "APPOINTMENT_CANCEL";
    /** Record a no-show with a reason. */
    public static final String APPOINTMENT_NO_SHOW = "APPOINTMENT_NO_SHOW";
    /** Administer visit types, resources and availability windows. */
    public static final String SCHEDULING_ADMIN = "SCHEDULING_ADMIN";
    /** Read the append-only membership and role-change history (IAM-02). */
    public static final String ROLE_HISTORY_READ = "ROLE_HISTORY_READ";

    private static final Set<String> ADMIN_ACTIONS = Set.of(
            "FACILITY_READ", "FACILITY_WRITE",
            "STAFF_INVITE", "STAFF_DEACTIVATE", "STAFF_READ", "STAFF_HISTORY",
            ROLE_HISTORY_READ,
            "CONFIG_CREATE", "CONFIG_APPROVE", "CONFIG_READ", "CONFIG_HISTORY", "AUDIT_READ",
            "CATALOG_READ",
            APPOINTMENT_READ, APPOINTMENT_BOOK, APPOINTMENT_HOLD, APPOINTMENT_CONFIRM,
            APPOINTMENT_RESCHEDULE, APPOINTMENT_CHECK_IN, APPOINTMENT_START,
            APPOINTMENT_COMPLETE, APPOINTMENT_CANCEL, APPOINTMENT_NO_SHOW, SCHEDULING_ADMIN);

    private static final Set<String> RECEPTION_ACTIONS = Set.of(
            "PATIENT_REGISTER", "PATIENT_READ", "PATIENT_SEARCH",
            APPOINTMENT_READ, APPOINTMENT_BOOK, APPOINTMENT_HOLD, APPOINTMENT_CONFIRM,
            APPOINTMENT_RESCHEDULE, APPOINTMENT_CHECK_IN, APPOINTMENT_CANCEL, APPOINTMENT_NO_SHOW);

    private static final Set<String> CLINICIAN_ACTIONS = Set.of(
            APPOINTMENT_READ, APPOINTMENT_START, APPOINTMENT_COMPLETE);

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key such as {@code APPOINTMENT_BOOK}
     * @param role   role key assigned in the active organization
     * @return {@code true} when the action is allowed
     */
    public boolean allows(String action, String role) {
        if ("tenant_admin".equals(role)) {
            return ADMIN_ACTIONS.contains(action);
        }
        // A privacy auditor may review the append-only audit trail, but cannot
        // administer configuration, staff or scheduling. Clinical access is separate.
        if ("privacy_auditor".equals(role)) {
            return "AUDIT_READ".equals(action);
        }
        // Reception registers patients and runs the clerical scheduling workflow.
        if ("reception".equals(role)) {
            return RECEPTION_ACTIONS.contains(action);
        }
        // A clinician starts and completes the encounter, but does not book,
        // reschedule or cancel it in this slice.
        return "clinician".equals(role) && CLINICIAN_ACTIONS.contains(action);
    }
}
