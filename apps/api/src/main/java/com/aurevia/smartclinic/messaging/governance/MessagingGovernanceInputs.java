package com.aurevia.smartclinic.messaging.governance;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;

/**
 * Pure, total validation of every MSG-07 governance command field.
 *
 * <p>Each method either returns a normalized value or raises a {@code 422}
 * before any domain or store call, so a malformed identifier cannot reach a
 * durable record. The audit trail itself carries no user-supplied clinical
 * text: reason, purpose and scope are bounded non-sensitive strings.</p>
 */
public final class MessagingGovernanceInputs {

    private static final int MAX_SCOPE_LENGTH = 128;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Allowlisted, non-sensitive export resource families. */
    private static final Set<String> EXPORT_SCOPES = Set.of(
            "conversation", "message", "instruction", "attachment", "report", "audit");

    /** Allowlisted, non-sensitive reasons for producing a copy. */
    private static final Set<String> EXPORT_PURPOSES = Set.of(
            "record_access", "legal_review", "dispute", "care_continuity", "regulatory");

    /** A revision is a bounded identifier token, never free clinical text. */
    private static final Pattern REVISION_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * Actions the generic audit recorder may legitimately record. Governance
     * commands (holds, exports, chart links, copy requests, retention, audit
     * review) are only performed by their dedicated endpoints; accepting them
     * through the recorder would fabricate a SUCCESS fact for work that never
     * happened and weaken the OPS-02 audit guarantee.
     */
    private static final Set<AuditAction> WORKFLOW_ACTIONS = Set.of(
            AuditAction.OPEN, AuditAction.VIEW, AuditAction.SEND, AuditAction.ASSIGN,
            AuditAction.DOWNLOAD, AuditAction.ACKNOWLEDGE, AuditAction.FILE,
            AuditAction.CORRECT, AuditAction.RESTRICT);

    private MessagingGovernanceInputs() {
    }

    /**
     * Validate an opaque tenant-owned identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated value
     * @throws ApiException when the value is not a UUID
     */
    public static String uuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a UUID");
        }
        return value;
    }

    /**
     * Validate a required non-sensitive text field.
     *
     * @param value supplied text
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed text
     * @throws ApiException when the value is absent, blank or too long
     */
    public static String text(String value, String field, int max) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > max) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Parse an audited action wire key.
     *
     * @param value supplied action key
     * @return the validated action
     * @throws ApiException when the key is unknown
     */
    public static AuditAction action(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_ACTION", "action is required");
        }
        try {
            return AuditAction.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_ACTION", "unknown audit action");
        }
    }

    /**
     * Parse an action wire key that the generic audit recorder may record.
     *
     * <p>Governance-command actions are deliberately excluded: each is
     * performed and audited only by its dedicated command, so a caller of the
     * recorder cannot self-report that a hold, export, chart link, copy
     * request, retention run or audit review occurred (OPS-02).</p>
     *
     * @param value supplied action key
     * @return the validated workflow action
     * @throws ApiException when the key is unknown or is a governance command
     */
    public static AuditAction workflowAction(String value) {
        AuditAction action = action(value);
        if (!WORKFLOW_ACTIONS.contains(action)) {
            throw new ApiException(422, "INVALID_ACTION", "action is not a recordable workflow action");
        }
        return action;
    }

    /**
     * Parse an outcome wire key.
     *
     * @param value supplied outcome key
     * @return the validated outcome
     * @throws ApiException when the key is unknown
     */
    public static Outcome outcome(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_OUTCOME", "outcome is required");
        }
        try {
            return Outcome.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_OUTCOME", "unknown audit outcome");
        }
    }

    /**
     * Validate an export scope against the allowlisted resource families.
     *
     * @param value supplied scope
     * @return the validated scope
     * @throws ApiException when the scope is not an allowed non-sensitive family
     */
    public static String exportScope(String value) {
        String scope = text(value, "scope", MAX_SCOPE_LENGTH);
        if (!EXPORT_SCOPES.contains(scope)) {
            throw new ApiException(422, "INVALID_SCOPE", "scope must be an allowed resource family");
        }
        return scope;
    }

    /**
     * Validate an export purpose against the allowlisted non-sensitive codes.
     *
     * <p>A free-text purpose could carry patient identifiers into an immutable
     * governance record, so only approved codes are accepted.</p>
     *
     * @param value supplied purpose code
     * @return the validated code
     * @throws ApiException when the code is absent, blank or not allowlisted
     */
    public static String exportPurpose(String value) {
        String purpose = text(value, "purpose", MAX_SCOPE_LENGTH);
        if (!EXPORT_PURPOSES.contains(purpose)) {
            throw new ApiException(422, "INVALID_PURPOSE", "purpose must be an allowed code");
        }
        return purpose;
    }

    /**
     * Validate a bounded source revision token.
     *
     * <p>Revisions are opaque identifiers, not descriptions; the character set
     * keeps clinical free text out of the export provenance record.</p>
     *
     * @param value supplied revision token
     * @return the validated token
     * @throws ApiException when the token is blank or has disallowed characters
     */
    public static String exportRevision(String value) {
        String revision = text(value, "sourceRevision", MAX_SCOPE_LENGTH);
        if (!REVISION_PATTERN.matcher(revision).matches()) {
            throw new ApiException(422, "INVALID_SOURCEREVISION", "sourceRevision has invalid characters");
        }
        return revision;
    }

    /**
     * Create a new opaque governance fact identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
