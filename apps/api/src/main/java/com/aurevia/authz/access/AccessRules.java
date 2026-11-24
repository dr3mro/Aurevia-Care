package com.aurevia.authz.access;

import java.util.List;
import java.util.Set;

import com.aurevia.api.ApiException;

/**
 * Bounded, side-effect-free validation and role rules for the IAM-06 access slice.
 *
 * <p>These rules are deliberately local: the shared {@code ActionPolicy} has no
 * emergency or support actions, and adding them there would widen unrelated roles.
 * Every rule fails closed on absent or malformed values.</p>
 */
public final class AccessRules {

    /** Longest accepted justification or review note. */
    public static final int MAX_REASON_LENGTH = 500;
    /** Longest accepted emergency access lifetime in minutes. */
    public static final int MAX_DURATION_MINUTES = 60;
    /** Longest accepted support incident window in minutes. */
    public static final int MAX_WINDOW_MINUTES = 240;
    /** Outcome recorded when a legacy review command omits an explicit outcome. */
    public static final String DEFAULT_REVIEW_OUTCOME = "recorded";

    private static final Set<String> REVIEW_OUTCOMES =
            Set.of("approved", "concerns_noted", "action_required", DEFAULT_REVIEW_OUTCOME);
    private static final java.util.regex.Pattern CODE =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final java.util.regex.Pattern REFERENCE =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,63}");
    private static final Set<String> REVIEWERS = Set.of("privacy_auditor", "tenant_admin");

    /**
     * Create the rule holder used by the two IAM-06 domain services.
     */
    public AccessRules() {
    }

    /**
     * Validate a tenant-local resource code.
     *
     * @param value code supplied by the caller
     * @throws ApiException when the code is absent, malformed or over-long
     */
    public static void validateFacility(String value) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_FACILITY", "facility code must be 1-64 bounded characters");
        }
    }

    /**
     * Validate an incident reference.
     *
     * @param value reference supplied by the caller
     * @throws ApiException when the reference is absent, malformed or over-long
     */
    public static void validateReference(String value) {
        if (value == null || !REFERENCE.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_REFERENCE", "incident reference must be 3-64 bounded characters");
        }
    }

    /**
     * Validate a human justification or review note.
     *
     * @param value  text supplied by the caller
     * @param errorCode stable error code to return on failure
     * @throws ApiException when the text is absent, blank or over-long
     */
    public static void validateReason(String value, String errorCode) {
        if (value == null || value.isBlank() || value.length() > MAX_REASON_LENGTH) {
            throw new ApiException(422, errorCode,
                    "reason must be 1-" + MAX_REASON_LENGTH + " characters");
        }
    }

    /**
     * Validate an emergency access lifetime.
     *
     * @param minutes requested lifetime in minutes
     * @throws ApiException when the lifetime is outside the bounded range
     */
    public static void validateDuration(int minutes) {
        if (minutes < 1 || minutes > MAX_DURATION_MINUTES) {
            throw new ApiException(422, "INVALID_DURATION",
                    "duration must be 1-" + MAX_DURATION_MINUTES + " minutes");
        }
    }

    /**
     * Validate a support incident window.
     *
     * @param minutes requested window in minutes
     * @throws ApiException when the window is outside the bounded range
     */
    public static void validateWindow(int minutes) {
        if (minutes < 1 || minutes > MAX_WINDOW_MINUTES) {
            throw new ApiException(422, "INVALID_WINDOW",
                    "window must be 1-" + MAX_WINDOW_MINUTES + " minutes");
        }
    }

    /**
     * Strictly parse an approved site configuration flag.
     *
     * @param value configured value, or {@code null} when no approved entry exists
     * @return {@code true} only for the exact value {@code true}, ignoring case
     */
    public static boolean isEnabled(String value) {
        return "true".equalsIgnoreCase(value);
    }

    /**
     * Decide whether the active role set contains a qualified clinician.
     *
     * @param roles active role keys in the current organization
     * @return {@code true} when the clinician role is present
     */
    public static boolean isClinician(List<String> roles) {
        return roles.contains("clinician");
    }

    /**
     * Decide whether the active role set may review emergency grants.
     *
     * @param roles active role keys in the current organization
     * @return {@code true} for a privacy auditor or tenant administrator
     */
    public static boolean isReviewer(List<String> roles) {
        return roles.stream().anyMatch(REVIEWERS::contains);
    }

    /**
     * Decide whether the active role set may open support incidents.
     *
     * @param roles active role keys in the current organization
     * @return {@code true} for a tenant administrator
     */
    public static boolean isAdmin(List<String> roles) {
        return roles.contains("tenant_admin");
    }

    /**
     * Decide whether a value is one of the bounded review-closure outcomes.
     *
     * @param value outcome supplied by a reviewer, or {@code null}
     * @return {@code true} only for a known outcome; {@code null} is refused
     */
    public static boolean isReviewOutcome(String value) {
        return value != null && REVIEW_OUTCOMES.contains(value);
    }

    /**
     * Validate the explicit outcome that closes an emergency access review.
     *
     * @param value outcome supplied by a reviewer
     * @throws ApiException when the outcome is absent or outside the bounded set
     */
    public static void validateReviewOutcome(String value) {
        if (!isReviewOutcome(value)) {
            throw new ApiException(422, "INVALID_OUTCOME", "review outcome must be one of " + REVIEW_OUTCOMES);
        }
    }
}
