package com.aurevia.tenant.importing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** OPS-08 entry-point test: a versioned manifest reaches the dry run over HTTP. */
class ImportControllerTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ImportController(new OnboardingImportService()))
            .build();

    @Test
    void dryRunReturnsFindingsWithoutACommitEndpoint() throws Exception {
        mvc.perform(post("/api/v1/tenant/imports/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manifest("legacy_field")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.total_records").value(1))
                .andExpect(jsonPath("$.counts.batch_count").value(1))
                .andExpect(jsonPath("$.unsupported_fields[0]").value("legacy_field"))
                .andExpect(jsonPath("$.counts.unsupported_fields").value(1))
                .andExpect(jsonPath("$.counts.errors").value(0));
    }

    private static String manifest(String extraField) {
        String sha = "a".repeat(64);
        return """
                {
                  "schema_version": "1.0",
                  "requirement": "OPS-08",
                  "manifest_version": 1,
                  "source": "legacy-clinic",
                  "synthetic_only": true,
                  "records": [{
                    "source_id": "r1",
                    "fields": {
                      "mrn_no": "MRN-1", "issuer": "clinic-a", "first": "Ada",
                      "last": "Lovelace", "dob": "1815-12-10", "%s": "X"
                    },
                    "attachments": [{"key": "doc-1", "sha256": "%s", "type": "pdf", "present": true}],
                    "historical_signature": {"issuer": "legacy", "signed_at": "2019-01-01T00:00:00Z", "checksum": "%s"}
                  }],
                  "mapping": {
                    "deterministic": true,
                    "strategy": "canonical_sha256",
                    "source_to_target": {
                      "mrn_no": "mrn", "issuer": "mrnIssuer", "first": "givenName",
                      "last": "familyName", "dob": "birthDate"
                    },
                    "identity_fields": ["mrn", "mrnIssuer"],
                    "ignored_fields": []
                  },
                  "provenance": {
                    "source_system": "legacy-clinic", "source_version": "1",
                    "preserve_historical_signatures": true, "creates_new_attestation": false
                  },
                  "batches": [{"sequence": 1, "record_ids": ["r1"], "expected_count": 1, "sha256": "%s"}]
                }
                """.formatted(extraField, sha, sha, sha);
    }
}
