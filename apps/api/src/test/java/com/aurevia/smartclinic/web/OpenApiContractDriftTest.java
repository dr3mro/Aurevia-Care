package com.aurevia.smartclinic.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Build-time guard against the packaged OpenAPI document drifting from the
 * authoritative repository contract (specification section 7).
 *
 * <p>The API serves the copy packaged on its classpath so it never reads a
 * repository file at runtime. This test fails when that copy differs by even a
 * byte from {@code contracts/openapi.yaml}, so a contract change cannot be
 * published without refreshing the served document.</p>
 */
class OpenApiContractDriftTest {

    /**
     * Fail when the served classpath copy no longer matches the authoritative
     * contract. A difference in content, line endings or encoding fails the
     * build, which is the drift gate for the two files.
     *
     * @throws IOException when either document cannot be read
     */
    @Test
    void packagedContractMatchesTheAuthoritativeSource() throws IOException {
        byte[] authoritative = Files.readAllBytes(locateContract());
        byte[] packaged = new ClassPathResource(OpenApiContractController.CONTRACT_RESOURCE)
                .getContentAsByteArray();
        assertArrayEquals(authoritative, packaged,
                "packaged contract/openapi.yaml drifted from contracts/openapi.yaml; refresh the copy");
    }

    /**
     * Locate the authoritative contract whatever directory Maven was invoked
     * from: Surefire uses the module basedir, but some tooling starts at the
     * repository root.
     *
     * @return path of {@code contracts/openapi.yaml}
     */
    static Path locateContract() {
        for (String candidate : List.of("../../contracts/openapi.yaml", "contracts/openapi.yaml",
                "../contracts/openapi.yaml")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            Path path = Path.of(basedir).resolve("../contracts/openapi.yaml").normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("cannot locate contracts/openapi.yaml");
    }
}
