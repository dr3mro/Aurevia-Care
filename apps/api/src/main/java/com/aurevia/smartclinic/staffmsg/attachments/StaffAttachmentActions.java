package com.aurevia.smartclinic.staffmsg.attachments;

import java.util.Map;
import java.util.Set;

/**
 * Bounded action matrix for staff-message attachments (IAM-01, MSG-03).
 *
 * <p>The quarantine scanner is a distinct service principal that can only issue
 * a verdict. Sending staff may upload, send, remove and download. Privacy audit
 * roles may read a view and its fact trail. Download is never implied by upload
 * or by scanning.</p>
 */
public final class StaffAttachmentActions {

    /** Bind a new upload to a draft. */
    public static final String UPLOAD = "STAFF_ATTACHMENT_UPLOAD";
    /** Issue a quarantine scan verdict. */
    public static final String SCAN = "STAFF_ATTACHMENT_SCAN";
    /** Explicitly send named clean files. */
    public static final String SEND = "STAFF_ATTACHMENT_SEND";
    /** Obtain verified original bytes. */
    public static final String DOWNLOAD = "STAFF_ATTACHMENT_DOWNLOAD";
    /** Read a view or audit trail. */
    public static final String READ = "STAFF_ATTACHMENT_READ";

    /** Role key for an authorized quarantine scanner. */
    public static final String SCANNER = "security_scanner";

    private static final Set<String> SENDERS = Set.of(
            "clinician", "nurse", "reception", "lab_collector", "lab_verifier",
            "radiology_staff", "pt_clinician", "pharmacy_staff", "tenant_admin");
    private static final Set<String> AUDITORS = Set.of("privacy_auditor", "tenant_admin");

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            UPLOAD, SENDERS,
            SEND, SENDERS,
            DOWNLOAD, SENDERS,
            READ, SENDERS);

    private StaffAttachmentActions() {
    }

    /**
     * Decide whether one active role permits one action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public static boolean allows(String action, String role) {
        if (SCAN.equals(action)) {
            return SCANNER.equals(role);
        }
        if (READ.equals(action)) {
            return SENDERS.contains(role) || AUDITORS.contains(role);
        }
        return ALLOWED.getOrDefault(action, Set.of()).contains(role);
    }
}
