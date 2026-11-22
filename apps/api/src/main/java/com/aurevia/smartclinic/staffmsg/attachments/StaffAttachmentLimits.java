package com.aurevia.smartclinic.staffmsg.attachments;

/**
 * Configured bounds for staff-message attachments (MSG-03).
 *
 * <p>Injected into the domain service so a deployment can tighten the size and
 * count ceilings without changing domain code. Defaults reuse the shared upload
 * size bound so both upload entry points stay consistent.</p>
 *
 * @param maxSizeBytes        largest accepted payload in bytes
 * @param maxFilesPerDraft    largest number of pending files bound to one draft
 */
public record StaffAttachmentLimits(long maxSizeBytes, int maxFilesPerDraft) {

    /** Default size bound, shared with the document upload boundary. */
    public static final long DEFAULT_MAX_SIZE_BYTES = StaffAttachmentInputs.MAX_SIZE_BYTES;
    /** Default number of attachments a single draft may hold. */
    public static final int DEFAULT_MAX_FILES_PER_DRAFT = 10;

    /**
     * Create the default bounds.
     */
    public StaffAttachmentLimits() {
        this(DEFAULT_MAX_SIZE_BYTES, DEFAULT_MAX_FILES_PER_DRAFT);
    }

    /**
     * Validate that the configured bounds are positive.
     */
    public StaffAttachmentLimits {
        if (maxSizeBytes < 1 || maxFilesPerDraft < 1) {
            throw new IllegalArgumentException("attachment limits must be positive");
        }
    }
}
