package com.aurevia.tenant.importing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.tenant.TenantContext;

/** OPS-08 entry points: staged commit, authorized sign-off and reversal over HTTP. */
class ControlledImportControllerTest {

    private final ControlledImportService service = new ControlledImportService();
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ControlledImportController(service))
            .build();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void commitEndpointStagesBatchesWithoutChangingSourceOfTruth() throws Exception {
        ImportManifest manifest = manifest();
        String body = "{\"manifest\": " + manifestJson(manifest) + "}";

        mvc.perform(post("/api/v1/tenant/imports/commit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped").value(false))
                .andExpect(jsonPath("$.results[0].outcome").value("COMMITTED"))
                .andExpect(jsonPath("$.progress.finalized").value(false));
    }

    @Test
    void commitEndpointResumesFromSubmittedProgress() throws Exception {
        ImportManifest manifest = manifest();
        String progress = """
                {"manifestVersion":7,"committedSequences":[1],"committedSourceIds":["r1"],"finalized":false}
                """;
        String body = "{\"manifest\": " + manifestJson(manifest)
                + ", \"progress\": " + progress + "}";

        mvc.perform(post("/api/v1/tenant/imports/commit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("ALREADY_COMMITTED"))
                .andExpect(jsonPath("$.progress.finalized").value(false));
    }

    @Test
    void signOffEndpointAcceptsAuthorizedRole() throws Exception {
        TenantContext.set(new TenantContext("org-1", "admin", "ONBOARDING", "corr-1"));
        ImportManifest manifest = manifest();
        String body = signOffBody(manifest, "tenant_admin", service.contentDigest(manifest));

        mvc.perform(post("/api/v1/tenant/imports/sign-off")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.progress.finalized").value(true));
    }

    @Test
    void signOffEndpointRefusesUnauthorizedRole() throws Exception {
        TenantContext.set(new TenantContext("org-1", "admin", "ONBOARDING", "corr-1"));
        ImportManifest manifest = manifest();
        String body = signOffBody(manifest, "reception", service.contentDigest(manifest));

        mvc.perform(post("/api/v1/tenant/imports/sign-off")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason")
                        .value(org.hamcrest.Matchers.containsString("unauthorized")))
                .andExpect(jsonPath("$.progress.finalized").value(false));
    }

    @Test
    void signOffEndpointRefusesApproverWhoIsNotTheAuthenticatedSubject() throws Exception {
        TenantContext.set(new TenantContext("org-1", "someone-else", "ONBOARDING", "corr-1"));
        ImportManifest manifest = manifest();
        String body = signOffBody(manifest, "tenant_admin", service.contentDigest(manifest));

        mvc.perform(post("/api/v1/tenant/imports/sign-off")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("approver"))
                .andExpect(jsonPath("$.progress.finalized").value(false));
    }

    @Test
    void signOffEndpointRefusesWhenNoSubjectIsAuthenticated() throws Exception {
        TenantContext.clear();
        ImportManifest manifest = manifest();
        String body = signOffBody(manifest, "tenant_admin", service.contentDigest(manifest));

        mvc.perform(post("/api/v1/tenant/imports/sign-off")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("approver"))
                .andExpect(jsonPath("$.progress.finalized").value(false));
    }

    @Test
    void signOffEndpointDelegatesMissingSignOffToTheService() throws Exception {
        TenantContext.set(new TenantContext("org-1", "admin", "ONBOARDING", "corr-1"));
        ImportManifest manifest = manifest();
        String body = "{\"manifest\": " + manifestJson(manifest)
                + ", \"progress\": {\"manifestVersion\":7,\"committedSequences\":[1,2],"
                + "\"committedSourceIds\":[\"r1\",\"r2\",\"r3\"],\"finalized\":false}}";

        mvc.perform(post("/api/v1/tenant/imports/sign-off")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.reason").value("missing sign-off"));
    }

    @Test
    void reverseEndpointClearsStaging() throws Exception {
        String body = """
                {"progress": {"manifestVersion":7,"committedSequences":[1,2],
                              "committedSourceIds":["r1","r2","r3"],"finalized":false}}
                """;

        mvc.perform(post("/api/v1/tenant/imports/reverse")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committedSequences").isEmpty())
                .andExpect(jsonPath("$.committedSourceIds").isEmpty())
                .andExpect(jsonPath("$.finalized").value(false));
    }

    private String signOffBody(ImportManifest manifest, String role, String content) {
        return """
                {"manifest": %s,
                 "progress": {"manifestVersion":7,"committedSequences":[1,2],
                              "committedSourceIds":["r1","r2","r3"],"finalized":false},
                 "signOff": {"approver":"admin","role":"%s","decision":"approved",
                             "content_sha256":"%s","manifest_version":7,"batches":[1,2],
                             "approved_at":"2026-11-21T09:00:00Z"}}
                """.formatted(manifestJson(manifest), role, content);
    }

    private ImportManifest manifest() {
        List<ImportRecord> records = List.of(record("r1", "M1"), record("r2", "M2"),
                record("r3", "M3"));
        ImportManifest base = new ImportManifest("1.0", "OPS-08", 7, "legacy-clinic", true,
                records, mapping(),
                new ImportProvenance("legacy-clinic", "1", true, false), List.of());
        List<ImportBatch> batches = new ArrayList<>();
        batches.add(batch(base, 1, List.of("r1", "r2")));
        batches.add(batch(base, 2, List.of("r3")));
        return new ImportManifest("1.0", "OPS-08", 7, "legacy-clinic", true,
                records, mapping(),
                new ImportProvenance("legacy-clinic", "1", true, false), batches);
    }

    private ImportBatch batch(ImportManifest base, int sequence, List<String> ids) {
        String digest = service.batchDigest(base,
                new ImportBatch(sequence, ids, ids.size(), "f".repeat(64)));
        return new ImportBatch(sequence, ids, ids.size(), digest);
    }

    private static String manifestJson(ImportManifest manifest) {
        String attSha = "b".repeat(64);
        String sigSha = "a".repeat(64);
        String d1 = manifest.batches().get(0).sha256();
        String d2 = manifest.batches().get(1).sha256();
        return """
                {
                  "schema_version": "1.0", "requirement": "OPS-08", "manifest_version": 7,
                  "source": "legacy-clinic", "synthetic_only": true,
                  "records": [
                    {"source_id":"r1","fields":{"mrn_no":"M1","issuer":"clinic-a","first":"Ada","last":"Lovelace","dob":"1815-12-10"},
                     "attachments":[{"key":"doc-r1","sha256":"%s","type":"pdf","present":true}],
                     "historical_signature":{"issuer":"legacy","signed_at":"2019-01-01T00:00:00Z","checksum":"%s"}},
                    {"source_id":"r2","fields":{"mrn_no":"M2","issuer":"clinic-a","first":"Ada","last":"Lovelace","dob":"1815-12-10"},
                     "attachments":[{"key":"doc-r2","sha256":"%s","type":"pdf","present":true}],
                     "historical_signature":{"issuer":"legacy","signed_at":"2019-01-01T00:00:00Z","checksum":"%s"}},
                    {"source_id":"r3","fields":{"mrn_no":"M3","issuer":"clinic-a","first":"Ada","last":"Lovelace","dob":"1815-12-10"},
                     "attachments":[{"key":"doc-r3","sha256":"%s","type":"pdf","present":true}],
                     "historical_signature":{"issuer":"legacy","signed_at":"2019-01-01T00:00:00Z","checksum":"%s"}}
                  ],
                  "mapping": {
                    "deterministic": true, "strategy": "canonical_sha256",
                    "source_to_target": {"mrn_no":"mrn","issuer":"mrnIssuer","first":"givenName","last":"familyName","dob":"birthDate"},
                    "identity_fields": ["mrn","mrnIssuer"], "ignored_fields": []
                  },
                  "provenance": {"source_system":"legacy-clinic","source_version":"1",
                    "preserve_historical_signatures":true,"creates_new_attestation":false},
                  "batches": [
                    {"sequence":1,"record_ids":["r1","r2"],"expected_count":2,"sha256":"%s"},
                    {"sequence":2,"record_ids":["r3"],"expected_count":1,"sha256":"%s"}
                  ]
                }
                """.formatted(attSha, sigSha, attSha, sigSha, attSha, sigSha, d1, d2);
    }

    private static ImportRecord record(String sourceId, String mrn) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("mrn_no", mrn);
        fields.put("issuer", "clinic-a");
        fields.put("first", "Ada");
        fields.put("last", "Lovelace");
        fields.put("dob", "1815-12-10");
        ImportAttachment attachment = new ImportAttachment("doc-" + sourceId, "b".repeat(64),
                "pdf", true);
        return new ImportRecord(sourceId, fields, List.of(attachment),
                new HistoricalSignature("legacy", "2019-01-01T00:00:00Z", "a".repeat(64)));
    }

    private static FieldMapping mapping() {
        Map<String, String> targets = new LinkedHashMap<>();
        targets.put("mrn_no", "mrn");
        targets.put("issuer", "mrnIssuer");
        targets.put("first", "givenName");
        targets.put("last", "familyName");
        targets.put("dob", "birthDate");
        return new FieldMapping(true, "canonical_sha256", targets,
                List.of("mrn", "mrnIssuer"), List.of());
    }
}
