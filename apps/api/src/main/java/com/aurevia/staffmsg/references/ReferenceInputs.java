package com.aurevia.staffmsg.references;

import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.documents.UploadValidation;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.staffmsg.references.ReferenceContract.SubjectWarning;

/**
 * Pure validation for staff-message reference commands.
 *
 * <p>All helpers are deterministic and side-effect free. Opaque keys reuse the
 * shared upload key rule so every boundary enforces the same bound. The subject
 * screener reports a fixed reason code only: the subject itself is never copied
 * into the result, an audit fact or a notification.</p>
 */
public final class ReferenceInputs {

    private static final Pattern LABEL = Pattern.compile("[^\\p{Cntrl}]{1,120}");
    private static final Pattern UUID_LIKE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{7,}");
    private static final Pattern EMAIL_LIKE = Pattern.compile("[^\\s@]+@[^\\s@]+");
    private static final Pattern MRN_LIKE = Pattern.compile(
            "(?i)\\b(MRN|patient|chart|encounter|order)\\b[:\\s#-]*\\d+");

    private ReferenceInputs() {
    }

    /**
     * Validate a bounded opaque selector key.
     *
     * @param value supplied key
     * @param field field name used in the error code
     * @return the trimmed key
     * @throws ApiException when the key is blank, malformed or too long
     */
    public static String key(String value, String field) {
        return UploadValidation.key(value, field);
    }

    /**
     * Resolve a supported reference kind from its wire key.
     *
     * @param value wire key
     * @return the typed kind
     * @throws ApiException when the key is not supported
     */
    public static ReferenceKind kind(String value) {
        try {
            return ReferenceKind.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_REFERENCE_KIND", "unsupported reference kind");
        }
    }

    /**
     * Validate a short, non-identifying display label.
     *
     * @param value supplied label
     * @return the trimmed label
     * @throws ApiException when the label is blank, too long or has control characters
     */
    public static String label(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || !LABEL.matcher(normalized).matches()) {
            throw new ApiException(422, "INVALID_LABEL",
                    "label must be 1-120 printable characters");
        }
        return normalized;
    }

    /**
     * Generate an opaque UUID handle for a reference or audit fact.
     *
     * @return a fresh lowercase UUID string
     */
    public static String newId() {
        return UploadValidation.newId();
    }

    /**
     * Screen a subject line for likely identifiers without ever echoing it.
     *
     * @param subject raw subject line, may be null or blank
     * @return a warning with a fixed reason code, never the subject text
     */
    public static SubjectWarning screenSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return new SubjectWarning(false, "NONE");
        }
        if (UUID_LIKE.matcher(subject).find()) {
            return new SubjectWarning(true, "UUID_LIKE");
        }
        if (DIGIT_RUN.matcher(subject).find()) {
            return new SubjectWarning(true, "DIGIT_RUN");
        }
        if (EMAIL_LIKE.matcher(subject).find()) {
            return new SubjectWarning(true, "EMAIL_LIKE");
        }
        if (MRN_LIKE.matcher(subject).find()) {
            return new SubjectWarning(true, "MRN_LIKE");
        }
        return new SubjectWarning(false, "NONE");
    }
}
