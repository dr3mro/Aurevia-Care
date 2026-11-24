package com.aurevia.api.authorization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aurevia.api.contract.RequestDigest;

/**
 * Deterministic generator for the X-04 command contract, typed client and checksum manifest (PRD-06).
 *
 * <p>The Java command catalog is the single source of truth. This generator
 * emits the OpenAPI document and the TypeScript client from that one model, so
 * they cannot disagree about a command, action or header. The client accepts
 * only identity, an idempotency key, a version precondition and an opaque body;
 * it has no role, permission or scope argument. A {@code --check} invocation
 * fails when the committed artifacts drift from the catalog.</p>
 */
public final class CommandClientGenerator {

    /** Stable generator identity emitted into the manifest and the generated client header. */
    public static final String GENERATOR = "com.aurevia.api.authorization.CommandClientGenerator";
    /** Contract artifact file name. */
    public static final String CONTRACT = "openapi.yaml";
    /** Generated client artifact file name. */
    public static final String CLIENT = "client.ts";
    /** Checksum manifest artifact file name. */
    public static final String MANIFEST = "manifest.json";

    private static final String DIGEST_DOMAIN = "x-04-manifest-v1";
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");

    private final CommandCatalog catalog;

    /**
     * Create a generator for a command catalog.
     *
     * @param catalog authoritative command definitions
     */
    public CommandClientGenerator(CommandCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /**
     * Render the OpenAPI contract for every catalog command.
     *
     * @return deterministic OpenAPI 3.0 YAML
     */
    public String openApiYaml() {
        StringBuilder yaml = new StringBuilder("""
                openapi: 3.0.3
                info:
                  title: Aurevia command contract (X-04)
                  version: 0.1.0
                  description: >-
                    Synthetic-only explicit POST commands. Every operation names the
                    server-side action that governs it. A client never supplies its
                    own role, permission or scope (PRD-06, IAM-01, ARC-03).
                servers:
                  - url: http://localhost:8081
                paths:
                """);
        for (CommandDefinition definition : catalog.commands()) {
            yaml.append("  ").append(definition.path()).append(":\n")
                    .append("    post:\n")
                    .append("      operationId: ").append(definition.id()).append('\n')
                    .append("      summary: ").append(definition.id()).append(" command\n")
                    .append("      description: >-\n")
                    .append("        Authorized by action ").append(definition.action())
                    .append(" in the active organization.\n")
                    .append("        Client-supplied authority is never consulted.\n")
                    .append("      x-aurevia-action: ").append(definition.action()).append('\n')
                    .append("      x-aurevia-policy: ").append(definition.policy()).append('\n')
                    .append("      parameters:\n")
                    .append("        - $ref: '#/components/parameters/ActiveOrganization'\n")
                    .append("        - $ref: '#/components/parameters/SyntheticSubject'\n")
                    .append("        - $ref: '#/components/parameters/CorrelationId'\n")
                    .append("        - $ref: '#/components/parameters/ApiVersion'\n")
                    .append("        - $ref: '#/components/parameters/IdempotencyKey'\n")
                    .append("        - $ref: '#/components/parameters/IfMatch'\n")
                    .append("      responses:\n")
                    .append("        '201': { description: Command committed with its audit event }\n")
                    .append("        '401': { $ref: '#/components/responses/Unauthenticated' }\n")
                    .append("        '403': { $ref: '#/components/responses/Forbidden' }\n")
                    .append("        '422': { $ref: '#/components/responses/InvalidInput' }\n")
                    .append("        '428': { $ref: '#/components/responses/PreconditionRequired' }\n")
                    .append("        '500': { $ref: '#/components/responses/DecisionNotRecorded' }\n");
        }
        yaml.append("""
                components:
                  parameters:
                    ActiveOrganization:
                      name: X-Active-Organization
                      in: header
                      required: true
                      description: Explicitly selected active tenant; never inferred from the body.
                      schema: { type: string, format: uuid }
                    SyntheticSubject:
                      name: X-Synthetic-Subject
                      in: header
                      required: true
                      description: Synthetic authenticated subject; accepted only under the synthetic-auth profile.
                      schema: { type: string, minLength: 1 }
                    CorrelationId:
                      name: X-Correlation-Id
                      in: header
                      required: false
                      description: Generated when absent and echoed on every response.
                      schema: { type: string, pattern: '^[A-Za-z0-9._-]{1,128}$' }
                    ApiVersion:
                      name: X-Api-Version
                      in: header
                      required: false
                      description: Absent or blank selects v1; header selection cannot create a route.
                      schema: { type: string, default: v1 }
                    IdempotencyKey:
                      name: Idempotency-Key
                      in: header
                      required: true
                      description: Opaque retry key scoped to tenant, caller and command.
                      schema: { type: string, minLength: 1 }
                    IfMatch:
                      name: If-Match
                      in: header
                      required: true
                      description: Version precondition; the owning service performs compare-and-set.
                      schema: { type: string, minLength: 1 }
                  responses:
                    Unauthenticated:
                      description: Missing or invalid authentication; no clinical detail is exposed.
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/ErrorBody' }
                    Forbidden:
                      description: Inactive membership or no active role permits the command.
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/ErrorBody' }
                    InvalidInput:
                      description: Unknown command or invalid business input.
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/ErrorBody' }
                    PreconditionRequired:
                      description: Idempotency key or version precondition is missing.
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/ErrorBody' }
                    DecisionNotRecorded:
                      description: Authorization decision could not be recorded; the command is denied.
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/ErrorBody' }
                  schemas:
                    ErrorBody:
                      type: object
                      additionalProperties: false
                      required: [code, message, correlationId]
                      properties:
                        code: { type: string }
                        message: { type: string }
                        correlationId: { type: string }
                """);
        return yaml.toString();
    }

    /**
     * Render the typed TypeScript command client.
     *
     * @return deterministic, prettier-formatted TypeScript source
     */
    public String typeScriptClient() {
        StringBuilder source = new StringBuilder()
                .append("// Generated by ").append(GENERATOR).append(". Do not edit.\n")
                .append("""
                /** Identity of the authenticated caller; authority is always re-derived server-side. */
                export interface CommandClientContext {
                  readonly organizationId: string;
                  readonly subject: string;
                  readonly correlationId: string;
                }

                /** Transport seam; a production transport must bind credentials out of band. */
                export interface CommandTransport {
                  send(request: CommandHttpRequest): Promise<CommandHttpResponse>;
                }

                /** One protected command as sent over the wire. */
                export interface CommandHttpRequest {
                  readonly method: 'POST';
                  readonly path: string;
                  readonly command: string;
                  readonly headers: Readonly<Record<string, string>>;
                  readonly body: unknown;
                }

                /** Machine-readable error body shared with the X-01 API contract. */
                export interface CommandErrorBody {
                  readonly code: string;
                  readonly message: string;
                  readonly correlationId: string;
                }

                /** Response envelope; check the status before reading an untrusted body. */
                export interface CommandHttpResponse {
                  readonly status: number;
                  readonly body: unknown;
                }

                """);
        for (CommandDefinition definition : catalog.commands()) {
            List<String> parameters = pathParameters(definition.path());
            source.append("/** ").append(definition.id()).append(": server action ")
                    .append(definition.action()).append(". */\n")
                    .append("export function ").append(definition.id()).append("(\n")
                    .append("  ctx: CommandClientContext,\n");
            for (String parameter : parameters) {
                source.append("  ").append(parameter).append(": string,\n");
            }
            source.append("  idempotencyKey: string,\n")
                    .append("  ifMatch: string,\n")
                    .append("  body: unknown,\n")
                    .append("  transport: CommandTransport,\n")
                    .append("): Promise<CommandHttpResponse> {\n")
                    .append("  return transport.send({\n")
                    .append("    method: 'POST',\n")
                    .append(pathField(definition.path(), parameters))
                    .append("    command: '").append(definition.id()).append("',\n")
                    .append("    headers: {\n")
                    .append("      'X-Active-Organization': ctx.organizationId,\n")
                    .append("      'X-Synthetic-Subject': ctx.subject,\n")
                    .append("      'X-Correlation-Id': ctx.correlationId,\n")
                    .append("      'X-Api-Version': 'v1',\n")
                    .append("      'Idempotency-Key': idempotencyKey,\n")
                    .append("      'If-Match': ifMatch,\n")
                    .append("    },\n")
                    .append("    body,\n")
                    .append("  });\n")
                    .append("}\n\n");
        }
        // One trailing newline, not a trailing blank line, keeps Prettier check green.
        return source.substring(0, source.length() - 1);
    }

    /**
     * Render a checksum manifest for the two generated artifacts.
     *
     * @return deterministic JSON manifest
     */
    public String manifestJson() {
        String contract = openApiYaml();
        String client = typeScriptClient();
        StringBuilder json = new StringBuilder().append("{\n")
                .append("  \"generator\": \"").append(GENERATOR).append("\",\n")
                .append("  \"contractSha256\": \"").append(checksum(contract)).append("\",\n")
                .append("  \"clientSha256\": \"").append(checksum(client)).append("\",\n")
                .append("  \"commands\": [\n");
        for (CommandDefinition definition : catalog.commands()) {
            json.append("    {\"id\": \"").append(definition.id())
                    .append("\", \"action\": \"").append(definition.action())
                    .append("\", \"method\": \"").append(definition.method())
                    .append("\", \"path\": \"").append(definition.path())
                    .append("\", \"policy\": \"").append(definition.policy()).append("\"},\n");
        }
        if (!catalog.commands().isEmpty()) {
            // Replace the final comma so the JSON stays valid for any catalog size.
            int trailing = json.lastIndexOf("},\n");
            json.replace(trailing, trailing + 3, "}\n");
        }
        return json.append("  ]\n}\n").toString();
    }

    /**
     * Extract path template parameters in declaration order.
     *
     * @param path route template such as {@code /api/v1/orders/{id}/accept}
     * @return parameter names without braces
     */
    static List<String> pathParameters(String path) {
        List<String> parameters = new ArrayList<>();
        Matcher matcher = PATH_PARAMETER.matcher(path);
        while (matcher.find()) {
            parameters.add(matcher.group(1));
        }
        return parameters;
    }

    /**
     * Build a TypeScript expression that encodes path parameters without leaking them into URLs.
     *
     * @param path        route template from the catalog
     * @param parameters  extracted parameter names
     * @return a TypeScript string expression
     */
    private static String pathExpression(String path, List<String> parameters) {
        if (parameters.isEmpty()) {
            return "'" + path + "'";
        }
        StringBuilder expression = new StringBuilder();
        Matcher matcher = PATH_PARAMETER.matcher(path);
        int cursor = 0;
        while (matcher.find()) {
            expression.append("'").append(path, cursor, matcher.start()).append("' + encodeURIComponent(")
                    .append(matcher.group(1)).append(") + ");
            cursor = matcher.end();
        }
        return expression.append("'").append(path.substring(cursor)).append("'").toString();
    }

    /**
     * Emit a path field that already follows the repository Prettier width.
     *
     * @param path       route template from the catalog
     * @param parameters extracted parameter names
     * @return one or more formatted TypeScript source lines
     */
    private static String pathField(String path, List<String> parameters) {
        String expression = pathExpression(path, parameters);
        String single = "    path: " + expression + ",";
        if (single.length() <= 100) {
            return single + "\n";
        }
        StringBuilder lines = new StringBuilder("    path:\n");
        String[] operands = expression.split(" \\+ ");
        for (int index = 0; index < operands.length; index++) {
            lines.append("      ").append(operands[index]);
            lines.append(index == operands.length - 1 ? ",\n" : " +\n");
        }
        return lines.toString();
    }

    /**
     * Fingerprint a generated artifact with the X-01 canonical digest.
     *
     * @param artifact generated text
     * @return lowercase hexadecimal SHA-256 value
     */
    private static String checksum(String artifact) {
        return RequestDigest.of(artifact, "x-04-generator", DIGEST_DOMAIN, "").hex();
    }

    /**
     * Write all three artifacts into a directory.
     *
     * @param directory output directory, created when absent
     * @throws IOException when an artifact cannot be written
     */
    public void writeTo(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(CONTRACT), openApiYaml(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(CLIENT), typeScriptClient(), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(MANIFEST), manifestJson(), StandardCharsets.UTF_8);
    }

    /**
     * Compare committed artifacts with a fresh generation.
     *
     * @param directory directory holding the committed artifacts
     * @return {@code true} when all three artifacts match byte for byte
     * @throws IOException when an artifact cannot be read
     */
    public boolean matches(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        return matchesFile(directory.resolve(CONTRACT), openApiYaml())
                && matchesFile(directory.resolve(CLIENT), typeScriptClient())
                && matchesFile(directory.resolve(MANIFEST), manifestJson());
    }

    /**
     * Read one committed artifact with UTF-8 and compare its text.
     *
     * @param path artifact path
     * @param expected freshly generated text
     * @return {@code true} when the text matches exactly
     * @throws IOException when the file cannot be read
     */
    private static boolean matchesFile(Path path, String expected) throws IOException {
        return Files.exists(path) && expected.equals(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Generate or verify artifacts from the command line.
     *
     * @param args either {@code <directory>} or {@code --check <directory>}
     * @throws IOException when an artifact cannot be read or written
     * @throws IllegalArgumentException when the arguments are malformed
     * @throws IllegalStateException when a {@code --check} detects drift
     */
    public void run(String[] args) throws IOException {
        if (args.length == 1 && !args[0].startsWith("--")) {
            writeTo(Path.of(args[0]));
            return;
        }
        if (args.length == 2 && "--check".equals(args[0])) {
            if (!matches(Path.of(args[1]))) {
                throw new IllegalStateException("generated command artifacts are stale");
            }
            return;
        }
        throw new IllegalArgumentException("usage: CommandClientGenerator <directory> | --check <directory>");
    }

    /**
     * Command line entry point for the drift gate.
     *
     * @param args either {@code <directory>} or {@code --check <directory>}
     * @throws IOException when an artifact cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        new CommandClientGenerator(CommandCatalog.synthetic()).run(args);
    }
}
