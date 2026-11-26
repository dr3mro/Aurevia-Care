package com.aurevia.api.authorization;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * One protected command and the server-side action that governs it (PRD-06, IAM-01, ARC-03).
 *
 * <p>The action key is not a new permission matrix: it is the key owned by an
 * existing domain policy. The catalog only asserts that a command exists and
 * names its owning policy, so role decisions stay with the policy that already
 * owns the workflow. Every field is validated against the way it is emitted:
 * the command id and path parameters become TypeScript identifiers, so they are
 * checked as identifiers; action, method and policy are emitted into YAML and
 * JSON and are checked as bounded artifact-safe tokens.</p>
 *
 * @param id     stable wire command identifier used by clients and the catalog
 * @param action existing action key whose owning policy decides the roles
 * @param method HTTP method of the command; explicit commands are always POST
 * @param path   documented route template in the active organization
 * @param policy simple name of the policy that owns the action key
 * @param gated  whether the live HTTP gate registers this route. A mapping-only definition
 *               binds a delivered handler without broadening that registration; other
 *               consumers of the full definition set, such as replay authorization,
 *               still see it and must be reviewed when a mapping is added.
 */
public record CommandDefinition(String id, String action, String method, String path, String policy,
                                boolean gated) {

    /**
     * Build a definition that the live HTTP gate registers.
     *
     * <p>This is the published baseline shape; callers that only bind a delivered
     * handler use the full constructor and leave gate registration unchanged.</p>
     *
     * @param id     stable wire command identifier
     * @param action existing action key whose owning policy decides the roles
     * @param method HTTP method; explicit commands are always POST
     * @param path   documented route template in the active organization
     * @param policy simple name of the policy that owns the action key
     */
    public CommandDefinition(String id, String action, String method, String path, String policy) {
        this(id, action, method, path, policy, true);
    }


    /** Tokens emitted into YAML and JSON without further escaping. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z/][A-Za-z0-9._:/{}-]*");
    /** Identifiers emitted as TypeScript function names and function parameters. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    /** Route template parameter such as {@code {id}}. */
    private static final Pattern PATH_TOKEN = Pattern.compile("\\{([^{}]*)\\}");
    /** Arguments the generator already emits; a path parameter with one of these names duplicates it. */
    private static final Set<String> RESERVED_PARAMETERS =
            Set.of("ctx", "idempotencyKey", "ifMatch", "body", "transport");

    /**
     * Validate and normalize every generated artifact field.
     */
    public CommandDefinition {
        id = identifier(id, "id");
        action = token(action, "action");
        method = token(method, "method");
        path = commandPath(path);
        policy = token(policy, "policy");
        if (!"POST".equals(method)) {
            throw invalid("command methods must be POST");
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
            throw invalid(field + " must be a bounded safe token");
        }
        return value.trim();
    }

    /**
     * Require a token that is also a legal TypeScript identifier.
     *
     * @param value value to validate
     * @param field field name used in the error message
     * @return the trimmed identifier
     * @throws ApiException when the value is not a legal identifier
     */
    private static String identifier(String value, String field) {
        String candidate = token(value, field);
        if (!IDENTIFIER.matcher(candidate).matches()) {
            throw invalid(field + " must be a TypeScript identifier");
        }
        return candidate;
    }

    /**
     * Validate a route template and every parameter that becomes a client argument.
     *
     * @param value route template to validate
     * @return the trimmed route template
     * @throws ApiException when the route, a parameter name or a parameter token is unsafe
     */
    private static String commandPath(String value) {
        String path = token(value, "path");
        if (!path.startsWith("/")) {
            throw invalid("path must start with '/'");
        }
        Matcher matcher = PATH_TOKEN.matcher(path);
        Set<String> parameters = new HashSet<>();
        while (matcher.find()) {
            String parameter = matcher.group(1);
            if (!IDENTIFIER.matcher(parameter).matches() || RESERVED_PARAMETERS.contains(parameter)
                    || !parameters.add(parameter)) {
                throw invalid("path parameter is unsafe, reserved or repeated: " + parameter);
            }
        }
        String literals = matcher.replaceAll("");
        if (literals.indexOf('{') >= 0 || literals.indexOf('}') >= 0) {
            throw invalid("path has an unclosed parameter token");
        }
        return path;
    }

    /**
     * Build the uniform machine-readable rejection for an invalid definition.
     *
     * @param detail reason the definition was rejected
     * @return the exception to throw
     */
    private static ApiException invalid(String detail) {
        return new ApiException(422, "INVALID_COMMAND_DEFINITION", detail);
    }
}
