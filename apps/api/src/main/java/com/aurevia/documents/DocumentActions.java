package com.aurevia.documents;

import java.util.Set;

/**
 * Single source of truth for role permission on document commands (IAM-01).
 *
 * <p>The table is deliberately small and explicit: a caller must hold an active
 * role that names the action. Authorization is re-derived on every command by
 * the integrating layer, and a denial is recorded before the exception leaves
 * the domain service.</p>
 */
public final class DocumentActions {

    private DocumentActions() {
    }

    /**
     * Protected document actions.
     */
    public enum Action {
        /** Accept a validated payload into quarantine. */
        UPLOAD,
        /** Record an independent scanner verdict. */
        SCAN,
        /** Authoritatively complete a clean, verified artifact. */
        COMPLETE,
        /** Remove a draft or failed artifact. */
        REMOVE,
        /** Obtain a mediated download handle. */
        DOWNLOAD,
        /** Read an artifact view and its fact trail. */
        READ,
        /** Request a PDF render for an immutable revision. */
        RENDER,
        /** Pick up a requested render job. */
        PICKUP_RENDER,
        /** Finish a render with a verified object version. */
        FINISH_RENDER,
        /** Record a render failure. */
        FAIL_RENDER,
        /** Bind a completed PDF to an exact revision. */
        BIND,
        /** Record a release decision. */
        RELEASE
    }

    private static final Set<Action> UPLOAD_ROLES = Set.of(
            Action.UPLOAD, Action.REMOVE, Action.DOWNLOAD, Action.READ);

    private static final Set<Action> REVIEW_ROLES = Set.of(
            Action.COMPLETE, Action.DOWNLOAD, Action.READ, Action.RENDER, Action.BIND);

    private static final Set<Action> CLINICIAN_ROLES = Set.of(
            Action.UPLOAD, Action.REMOVE, Action.DOWNLOAD, Action.READ,
            Action.RENDER, Action.BIND, Action.RELEASE);

    private static final Set<Action> RENDERER_ROLES = Set.of(
            Action.PICKUP_RENDER, Action.FINISH_RENDER, Action.FAIL_RENDER);

    /**
     * Decide whether one role permits one action.
     *
     * @param action required action
     * @param role   currently active role key
     * @return {@code true} only for an explicit grant
     */
    public static boolean allows(Action action, String role) {
        if (role == null) {
            return false;
        }
        return switch (role) {
            case "patient" -> UPLOAD_ROLES.contains(action);
            case "reception", "clinical_assistant" -> UPLOAD_ROLES.contains(action);
            case "document_reviewer" -> REVIEW_ROLES.contains(action);
            case "clinician" -> CLINICIAN_ROLES.contains(action);
            case "security_scanner" -> action == Action.SCAN;
            case "pdf_renderer" -> RENDERER_ROLES.contains(action);
            case "privacy_auditor" -> action == Action.READ || action == Action.RELEASE;
            default -> false;
        };
    }
}
