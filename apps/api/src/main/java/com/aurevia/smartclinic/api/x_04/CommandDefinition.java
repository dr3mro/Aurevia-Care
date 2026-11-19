package com.aurevia.smartclinic.api.x_04;

import java.util.regex.Pattern;

import com.aurevia.smartclinic.api.ApiException;

/**
 * One protected command and the server-side action that governs it (PRD-06, IAM-01, ARC-03).
 *
 * <p>The action key is not a new permission matrix: it is the key owned by an
 * existing domain policy. The catalog only asserts that a command exists and
 * names its owning policy, so role decisions stay with the policy that already
 * owns the workflow. Every field is validated here because the same values are
 * emitted verbatim into the generated OpenAPI contract, TypeScript client and
 * checksum manifest.</p>
 *
 * @param id     stable wire command identifier used by clients and the catalog
 * @param action existing action key whose owning policy decides the roles
 * @param method HTTP method of the command; explicit commands are always POST
 * @param path   documented route template in the active organization
 * @param policy simple name of the policy that owns the action key
 */
public record CommandDefinition(String id, String action, String method, String path, String policy) {

    /** Tokens are emitted into YAML, TypeScript and JSON without further escaping. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z/][A-Za-z0-9._:/{}-]*");

    /**
     * Validate and normalize every generated artifact field.
     */
    public CommandDefinition {
        id = token(id, "id");
        action = token(action, "action");
        method = token(method, "method");
        path = token(path, "path");
        policy = token(policy, "policy");
        if (!"POST".equals(method)) {
            throw new ApiException(422, "INVALID_COMMAND_DEFINITION", "command methods must be POST");
        }
    }

    /**
     * Require a bounded, artifact-safe token.
     *
     * @param value value to validate
     * @param field field name used in the error message
     * @return the trimmed token
     * @throws ApiException when the value is absent, blank or contains an unsafe character
     */
    static String token(String value, String field) {
        if (value == null || value.isBlank() || !SAFE.matcher(value.trim()).matches()) {
            throw new ApiException(422, "INVALID_COMMAND_DEFINITION", field + " must be a bounded safe token");
        }
        return value.trim();
    }
}
