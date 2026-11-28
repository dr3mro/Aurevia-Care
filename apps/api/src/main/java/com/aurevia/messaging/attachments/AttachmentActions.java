package com.aurevia.messaging.attachments;

import java.util.Map;
import java.util.Set;

/**
 * Bounded action matrix for the attachment workflow.
 *
 * <p>The quarantine scanner is a distinct service principal and can only
 * issue a verdict. Filing patient-supplied material into a chart is a separate
 * clinician action; downloading is never implied by scanning or filing. The
 * matrix is paired with a fresh actor snapshot and a server-side connection
 * grant checked on upload; other commands re-check role, tenant and attachment
 * binding only.</p>
 */
public final class AttachmentActions {

    /** Bind a new upload to a verified connection. */
    public static final String UPLOAD = "ATTACHMENT_UPLOAD";
    /** Issue a quarantine scan verdict. */
    public static final String SCAN = "ATTACHMENT_SCAN";
    /** Explicitly publish named pending files to a message. */
    public static final String PUBLISH = "ATTACHMENT_PUBLISH";
    /** Obtain a mediated download handle. */
    public static final String DOWNLOAD = "ATTACHMENT_DOWNLOAD";
    /** Remove a draft or failed attachment. */
    public static final String REMOVE = "ATTACHMENT_REMOVE";
    /** File published patient-supplied material in the chart. */
    public static final String FILE = "ATTACHMENT_FILE";
    /** Read an attachment view, its fact trail or the unit inbox. */
    public static final String READ = "ATTACHMENT_READ";

    /** Role key for a verified patient. */
    public static final String PATIENT = "patient";
    /** Role key for an authorized quarantine scanner principal. */
    public static final String SCANNER = "security_scanner";
    /** Role key that may file material in the chart. */
    public static final String CLINICIAN = "clinician";
    /** Role key that may review the append-only fact trail. */
    public static final String PRIVACY_AUDITOR = "privacy_auditor";
    /** Role key for tenant administration and audit review. */
    public static final String TENANT_ADMIN = "tenant_admin";

    private static final Set<String> MESSAGE_SENDERS = Set.of(
            PATIENT, "reception", CLINICIAN, "lab_collector", "lab_verifier",
            "radiology_staff", "pt_clinician", "pharmacy_staff");

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            UPLOAD, MESSAGE_SENDERS,
            PUBLISH, MESSAGE_SENDERS,
            DOWNLOAD, MESSAGE_SENDERS,
            REMOVE, MESSAGE_SENDERS,
            SCAN, Set.of(SCANNER),
            FILE, Set.of(CLINICIAN),
            READ, Set.of(PATIENT, CLINICIAN, "reception", "lab_collector", "lab_verifier",
                    "radiology_staff", "pt_clinician", "pharmacy_staff",
                    PRIVACY_AUDITOR, TENANT_ADMIN));

    private AttachmentActions() {
    }

    /**
     * Decide whether one active role permits one action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public static boolean allows(String action, String role) {
        return ALLOWED.getOrDefault(action, Set.of()).contains(role);
    }
}
