package com.aurevia.api.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generated OpenAPI contract, typed client and manifest are catalog-driven and drift-checked. */
class CommandClientGeneratorTest {

    @TempDir
    Path temp;

    @Test
    void everyCommandAppearsInContractAndClient() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        CommandClientGenerator generator = new CommandClientGenerator(catalog);
        String yaml = generator.openApiYaml();
        String client = generator.typeScriptClient();
        assertEquals(yaml, generator.openApiYaml());
        for (CommandDefinition definition : catalog.commands()) {
            assertTrue(yaml.contains("operationId: " + definition.id()));
            assertTrue(yaml.contains("x-aurevia-action: " + definition.action()));
            assertTrue(yaml.contains("x-aurevia-policy: " + definition.policy()));
            assertTrue(yaml.contains("  " + definition.path() + ":"));
            assertTrue(client.contains("export function " + definition.id() + "("));
            if (CommandClientGenerator.pathParameters(definition.path()).isEmpty()) {
                assertTrue(client.contains("path: '" + definition.path() + "',"));
            } else {
                assertTrue(client.contains("encodeURIComponent("));
            }
            assertTrue(client.contains("command: '" + definition.id() + "',"));
        }
        assertTrue(yaml.contains("'If-Match':") || yaml.contains("IfMatch:"));
        assertTrue(client.contains("'If-Match': ifMatch,"));
        assertTrue(client.contains("'Idempotency-Key': idempotencyKey,"));
        assertTrue(client.contains("'X-Active-Organization': ctx.organizationId,"));
        assertTrue(client.contains("'X-Synthetic-Subject': ctx.subject,"));
        assertTrue(client.contains("'X-Correlation-Id': ctx.correlationId,"));
        for (String forbidden : List.of("role:", "permission:", "scope:", "actor:", "claimedRole", "any")) {
            assertFalse(client.contains(forbidden));
        }
    }

    @Test
    void committedArtifactsMatchAFreshGeneration() throws IOException {
        Path generated = locateGeneratedArtifacts();
        CommandClientGenerator generator = new CommandClientGenerator(CommandCatalog.synthetic());
        assertTrue(generator.matches(generated),
                "contracts/openapi/generated is stale; rerun CommandClientGenerator <directory>");
    }

    /**
     * Resolve the committed artifacts from the working directory or the module basedir published
     * by Surefire, so the drift gate is independent of where Maven was invoked.
     *
     * @return the generated artifact directory
     */
    private static Path locateGeneratedArtifacts() {
        for (String candidate : List.of("../contracts/openapi/generated",
                "contracts/openapi/generated", "../../contracts/openapi/generated")) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            Path path = Path.of(basedir).resolve("../contracts/openapi/generated").normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        throw new IllegalStateException("cannot locate contracts/openapi/generated");
    }

    @Test
    void contractDocumentsTheFailClosedAuditOutcome() {
        String yaml = new CommandClientGenerator(CommandCatalog.synthetic()).openApiYaml();
        assertTrue(yaml.contains("'500': { $ref: '#/components/responses/DecisionNotRecorded' }"));
        assertTrue(yaml.contains("    DecisionNotRecorded:\n"));
        assertTrue(yaml.contains("'428': { $ref: '#/components/responses/PreconditionRequired' }"));
    }

    @Test
    void pathParametersBecomeEncodedFunctionArguments() {
        assertEquals(List.of(), CommandClientGenerator.pathParameters("/api/v1/facilities"));
        assertEquals(List.of("key", "version"),
                CommandClientGenerator.pathParameters(
                        "/api/v1/configuration/{key}/versions/{version}/approve"));
        String client = new CommandClientGenerator(CommandCatalog.synthetic()).typeScriptClient();
        assertTrue(client.contains("  id: string,\n"));
        assertTrue(client.contains("path: '/api/v1/orders/' + encodeURIComponent(id) + '/accept',"));
        assertTrue(client.contains("    path: '/api/v1/facilities',"));
    }

    @Test
    void manifestCarriesDeterministicChecksumsAndEveryCommand() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        CommandClientGenerator generator = new CommandClientGenerator(catalog);
        String manifest = generator.manifestJson();
        assertTrue(manifest.contains("\"generator\": \"" + CommandClientGenerator.GENERATOR));
        assertTrue(manifest.contains("\"contractSha256\": \""));
        assertTrue(manifest.contains("\"clientSha256\": \""));
        for (CommandDefinition definition : catalog.commands()) {
            assertTrue(manifest.contains("\"id\": \"" + definition.id() + "\""));
            assertTrue(manifest.contains("\"action\": \"" + definition.action() + "\""));
        }
        CommandClientGenerator smaller = new CommandClientGenerator(new CommandCatalog(List.of(catalog.commands().get(0))));
        assertNotEquals(manifest, smaller.manifestJson());
    }

    @Test
    void emptyCatalogStillGeneratesValidArtifacts() {
        CommandClientGenerator generator = new CommandClientGenerator(new CommandCatalog(List.of()));
        assertTrue(generator.manifestJson().contains("\"commands\": [\n  ]"));
        assertTrue(generator.openApiYaml().contains("paths:\ncomponents:"));
        assertTrue(generator.typeScriptClient().contains("export interface CommandTransport"));
        assertFalse(generator.typeScriptClient().contains("export function"));
    }

    @Test
    void writeAndCheckDetectEveryKindOfDrift() throws IOException {
        CommandClientGenerator generator = new CommandClientGenerator(CommandCatalog.synthetic());
        Path dir = temp.resolve("artifacts");
        generator.writeTo(dir);
        assertEquals(generator.openApiYaml(), Files.readString(dir.resolve(CommandClientGenerator.CONTRACT)));
        assertEquals(generator.typeScriptClient(), Files.readString(dir.resolve(CommandClientGenerator.CLIENT)));
        assertEquals(generator.manifestJson(), Files.readString(dir.resolve(CommandClientGenerator.MANIFEST)));
        assertTrue(generator.matches(dir));

        Files.writeString(dir.resolve(CommandClientGenerator.CONTRACT), "drifted");
        assertFalse(generator.matches(dir));
        Files.writeString(dir.resolve(CommandClientGenerator.CONTRACT), generator.openApiYaml());

        Files.writeString(dir.resolve(CommandClientGenerator.CLIENT), "drifted");
        assertFalse(generator.matches(dir));
        Files.writeString(dir.resolve(CommandClientGenerator.CLIENT), generator.typeScriptClient());

        Files.delete(dir.resolve(CommandClientGenerator.MANIFEST));
        assertFalse(generator.matches(dir));
    }

    @Test
    void runWritesChecksAndRejectsMalformedArguments() throws IOException {
        CommandClientGenerator generator = new CommandClientGenerator(CommandCatalog.synthetic());
        Path dir = temp.resolve("cli");
        generator.run(new String[] {dir.toString()});
        assertTrue(Files.exists(dir.resolve(CommandClientGenerator.MANIFEST)));
        generator.run(new String[] {"--check", dir.toString()});

        Files.writeString(dir.resolve(CommandClientGenerator.CLIENT), "drifted");
        assertThrows(IllegalStateException.class, () -> generator.run(new String[] {"--check", dir.toString()}));

        assertThrows(IllegalArgumentException.class, () -> generator.run(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> generator.run(new String[] {"--check"}));
        assertThrows(IllegalArgumentException.class, () -> generator.run(new String[] {"one", "two"}));
        assertThrows(IllegalArgumentException.class,
                () -> generator.run(new String[] {"--check", dir.toString(), "extra"}));
    }

    @Test
    void mainUsesTheSyntheticCatalog() throws IOException {
        Path dir = temp.resolve("main");
        CommandClientGenerator.main(new String[] {dir.toString()});
        String client = Files.readString(dir.resolve(CommandClientGenerator.CLIENT));
        assertTrue(client.contains("export function attestClinicalDecision("));
        assertEquals(CommandCatalog.synthetic().commands().size(),
                client.lines().filter(line -> line.startsWith("export function ")).count());
    }
}
