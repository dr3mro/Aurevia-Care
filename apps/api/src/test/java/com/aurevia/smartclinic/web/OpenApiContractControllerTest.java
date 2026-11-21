package com.aurevia.smartclinic.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Entry-point evidence that the contract controller streams the packaged YAML
 * document with the required media type (specification section 7).
 */
class OpenApiContractControllerTest {

    private MockMvc mvc;

    /** Drive the real controller through a standalone servlet boundary. */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OpenApiContractController()).build();
    }

    /**
     * The response is the exact packaged document and carries the YAML media
     * type; no patient or tenant data is involved.
     *
     * @throws Exception when the request cannot be performed
     */
    @Test
    void servesThePackagedContractAsYaml() throws Exception {
        byte[] expected = new ClassPathResource(OpenApiContractController.CONTRACT_RESOURCE)
                .getContentAsByteArray();
        mvc.perform(get("/api/v1/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/yaml"))
                .andExpect(content().bytes(expected));
    }
}
