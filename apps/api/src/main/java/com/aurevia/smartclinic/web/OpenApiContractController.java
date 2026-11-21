package com.aurevia.smartclinic.web;

import java.io.IOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the versioned OpenAPI contract as a static YAML document (specification section 7).
 *
 * <p>The document is read from the packaged classpath copy and never from the
 * repository, a request parameter or any data store. The route therefore needs
 * no sign-in and cannot expose tenant or patient data. The packaged copy is
 * guarded against drift from {@code contracts/openapi.yaml} by a build-time
 * test, so generated clients can rely on the served description.</p>
 */
@RestController
public class OpenApiContractController {

    /** Media type of an OpenAPI YAML document. */
    private static final MediaType APPLICATION_YAML = MediaType.parseMediaType("application/yaml");

    /** Classpath location of the packaged contract served by this controller. */
    static final String CONTRACT_RESOURCE = "contract/openapi.yaml";

    /**
     * Return the exact packaged contract bytes with the YAML media type.
     *
     * @return HTTP 200 carrying the contract document
     * @throws IOException when the packaged document cannot be read
     */
    @GetMapping(value = "/api/v1/openapi.yaml", produces = "application/yaml")
    public ResponseEntity<byte[]> contract() throws IOException {
        byte[] document = new ClassPathResource(CONTRACT_RESOURCE).getContentAsByteArray();
        return ResponseEntity.ok().contentType(APPLICATION_YAML).body(document);
    }
}
