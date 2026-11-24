package com.aurevia.messaging.conversations;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.aurevia.messaging.conversations.ConversationContract.UnitKey;

/**
 * Bounded action matrix for the MSG-02 conversation slice.
 *
 * <p>Patient actions and staff work-item actions are deliberately separated: a
 * verified patient can open, reply, read and list an inbox, but cannot assign,
 * transfer, resolve or close a work item. Staff can run the work queue and all
 * work-item transitions. The matrix is paired with a fresh membership and role
 * check on every protected request in the domain service.</p>
 */
@Component
public class ConversationActions {

    /** Open a conversation through a verified connection. */
    public static final String CONVERSATION_OPEN = "CONVERSATION_OPEN";
    /** Post a reply to an existing conversation. */
    public static final String CONVERSATION_REPLY = "CONVERSATION_REPLY";
    /** Read one conversation and its trails. */
    public static final String CONVERSATION_READ = "CONVERSATION_READ";
    /** Read the caller's patient inbox with unread counts. */
    public static final String INBOX_READ = "INBOX_READ";
    /** Read the role-scoped staff work queue. */
    public static final String WORK_QUEUE_READ = "WORK_QUEUE_READ";
    /** Assign an open work item to a named staff subject. */
    public static final String WORK_ASSIGN = "WORK_ASSIGN";
    /** Transfer a work item inside the same unit. */
    public static final String WORK_TRANSFER = "WORK_TRANSFER";
    /** Resolve a work item with a reason. */
    public static final String WORK_RESOLVE = "WORK_RESOLVE";
    /** Close a thread with a reason, making it read-only. */
    public static final String WORK_CLOSE = "WORK_CLOSE";

    private static final Set<String> PATIENT_ACTIONS = Set.of(
            CONVERSATION_OPEN, CONVERSATION_REPLY, CONVERSATION_READ, INBOX_READ);

    private static final Set<String> STAFF_ACTIONS = Set.of(
            CONVERSATION_OPEN, CONVERSATION_REPLY, CONVERSATION_READ, WORK_QUEUE_READ,
            WORK_ASSIGN, WORK_TRANSFER, WORK_RESOLVE, WORK_CLOSE);

    // IAM-02: tenant administration carries no clinical authority. An admin may
    // see the operational queue (ADM-02) but cannot open, reply to or write a
    // patient work item; clinical access requires a separate clinical role.
    private static final Set<String> ADMIN_ACTIONS = Set.of(WORK_QUEUE_READ);

    /** Staff roles that may run care-unit work items. */
    public static final Set<String> STAFF_ROLES = Set.of(
            "reception", "clinical_assistant", "clinician", "lab_collector", "lab_verifier",
            "radiology_staff", "radiology_interpreter", "pt_clinician", "pharmacy_staff");

    private static final Set<String> CLINIC_ROLES = Set.of("reception", "clinical_assistant", "clinician");
    private static final Set<String> LAB_ROLES = Set.of("lab_collector", "lab_verifier");
    private static final Set<String> RADIOLOGY_ROLES = Set.of("radiology_staff", "radiology_interpreter");

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        if ("tenant_admin".equals(role)) {
            return ADMIN_ACTIONS.contains(action);
        }
        if ("patient".equals(role)) {
            return PATIENT_ACTIONS.contains(action);
        }
        if (STAFF_ROLES.contains(role)) {
            return STAFF_ACTIONS.contains(action);
        }
        return false;
    }

    /**
     * Decide whether a role is eligible to work in a care unit.
     *
     * <p>An administrator may read any unit's queue but holds no work-item
     * write action, so unit capability is meaningful only for staff roles. A
     * cross-unit assignment or transfer is therefore impossible. An auditor
     * holds no messaging action at all; its {@code true} result here is unused
     * and fails closed at the {@code allows} action check.</p>
     *
     * @param unit care-unit key
     * @param role role key active in the current organization
     * @return {@code true} when the role may act in the unit
     */
    public boolean allowsUnit(UnitKey unit, String role) {
        if ("tenant_admin".equals(role) || "privacy_auditor".equals(role)) {
            return true;
        }
        return switch (unit) {
            case CLINIC -> CLINIC_ROLES.contains(role);
            case LAB -> LAB_ROLES.contains(role);
            case RADIOLOGY -> RADIOLOGY_ROLES.contains(role);
            case PT -> "pt_clinician".equals(role);
            case PHARMACY -> "pharmacy_staff".equals(role);
        };
    }
}
