package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP acceptance for the reachable document command API (ARC-06).
 *
 * <p>Every command is driven through the real controller and the production JDBC
 * store against a fresh PostgreSQL database. The controller derives tenant and
 * roles from the request context, never from a request body. All identifiers,
 * subjects and payload metadata are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_doc_cmd_it")
@ActiveProfiles("synthetic-auth")
class DocumentArtifactControllerHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_doc_cmd_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTEXT = "ctx-http";
    private static final String PATIENT = "pat-http";
    private static final String CHECKSUM = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    DocumentArtifactController controller;

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_doc_cmd_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_doc_cmd_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "uploader", "reception");
            seedStaff(statement, ORG_A, "scanner", "security_scanner");
            seedStaff(statement, ORG_A, "reviewer", "document_reviewer");
            seedStaff(statement, ORG_A, "renderer", "pdf_renderer");
            seedStaff(statement, ORG_A, "clinician", "clinician");
            seedStaff(statement, ORG_A, "patient-role", "patient");
            seedStaff(statement, ORG_B, "foreign", "clinician");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.document_authorization "
                    + "(organization_id, context_id, grant_organization_id, patient_id, active) VALUES ('"
                    + ORG_A + "', '" + CONTEXT + "', '" + ORG_A + "', '" + PATIENT + "', true)");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.document_authorization "
                    + "(organization_id, context_id, grant_organization_id, patient_id, active) VALUES ('"
                    + ORG_B + "', '" + CONTEXT + "', '" + ORG_B + "', '" + PATIENT + "', true)");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_doc_cmd_it WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Document command tenant') ON CONFLICT DO NOTHING");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "SELECT '" + org + "', '" + subject + "', '" + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> call(String method, String path, String subject, String org,
                                        String ifMatch, String body) {
        if ("POST".equals(method)) {
            RestClient.RequestBodySpec spec = rest.post().uri(path)
                    .header("X-Synthetic-Subject", subject)
                    .header("X-Active-Organization", org);
            if (ifMatch != null) {
                spec.header("If-Match", ifMatch);
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return retrieve(spec);
        }
        RestClient.RequestHeadersSpec<?> spec = rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org);
        if (ifMatch != null) {
            spec.header("If-Match", ifMatch);
        }
        return retrieve(spec);
    }

    private static ResponseEntity<String> retrieve(RestClient.RequestHeadersSpec<?> spec) {
        return spec.retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody() == null ? "{}" : response.getBody());
    }

    private JsonNode uploadNode(String subject, String org) {
        String body = """
                {"contextId":"%s","fileName":"notes.pdf","contentType":"application/pdf",
                 "magicHex":"25504446","sizeBytes":128,"checksum":"%s"}
                """.formatted(CONTEXT, CHECKSUM);
        ResponseEntity<String> response = call("POST", "/api/v1/documents/artifacts", subject, org, null, body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return tree(response);
    }

    private String upload(String subject, String org) {
        return uploadNode(subject, org).get("id").asString();
    }

    private static void seedObject(String objectKey, int objectVersion, long sizeBytes) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO public.document_verified_object "
                     + "(organization_id, object_key, object_version, checksum, size_bytes) "
                     + "VALUES (CAST(? AS uuid), ?, ?, ?, ?)")) {
            connection.createStatement().execute("SET app.current_org = '" + ORG_A + "'");
            insert.setString(1, ORG_A);
            insert.setString(2, objectKey);
            insert.setInt(3, objectVersion);
            insert.setString(4, CHECKSUM);
            insert.setLong(5, sizeBytes);
            insert.executeUpdate();
        }
    }

    @Test
    void quarantineCommandsAreReachableThroughTheController() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();
        assertEquals(HttpStatus.OK, call("GET", "/api/v1/documents/artifacts/" + id,
                "uploader", ORG_A, null, null).getStatusCode());
        seedObject(created.get("objectKey").asString(), created.get("objectVersion").asInt(), 128);

        ResponseEntity<String> scanned = call("POST", "/api/v1/documents/artifacts/" + id + "/scan",
                "scanner", ORG_A, "1", "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.OK, scanned.getStatusCode(), scanned.getBody());
        assertTrue(tree(scanned).get("state").asString().equalsIgnoreCase("clean"));

        ResponseEntity<String> completed = call("POST", "/api/v1/documents/artifacts/" + id + "/complete",
                "reviewer", ORG_A, "2", "{\"note\":\"verified\"}");
        assertEquals(HttpStatus.OK, completed.getStatusCode(), completed.getBody());
        assertTrue(tree(completed).get("state").asString().equalsIgnoreCase("completed"));

        ResponseEntity<String> downloaded = call("GET", "/api/v1/documents/artifacts/" + id + "/download",
                "clinician", ORG_A, "3", null);
        assertEquals(HttpStatus.OK, downloaded.getStatusCode(), downloaded.getBody());
        assertEquals(CHECKSUM, tree(downloaded).get("checksum").asString());

        ResponseEntity<String> viewed = call("GET", "/api/v1/documents/artifacts/" + id,
                "clinician", ORG_A, null, null);
        assertEquals(HttpStatus.OK, viewed.getStatusCode(), viewed.getBody());
        assertTrue(tree(viewed).get("events").size() > 0);

        String removable = upload("uploader", ORG_A);
        ResponseEntity<String> removed = call("POST", "/api/v1/documents/artifacts/" + removable + "/remove",
                "uploader", ORG_A, "1", "{\"reason\":\"wrong upload\"}");
        assertEquals(HttpStatus.OK, removed.getStatusCode(), removed.getBody());
        assertTrue(tree(removed).get("state").asString().equalsIgnoreCase("removed"));
    }

    @Test
    void pdfCommandsAreReachableThroughTheController() {
        String revision = """
                {"organizationId":"%s","revisionId":"rev-http","revisionVersion":1,
                 "contentChecksum":"%s"}
                """.formatted(ORG_A, CHECKSUM);

        ResponseEntity<String> requested = call("POST", "/api/v1/documents/artifacts/renders",
                "clinician", ORG_A, null,
                "{\"revision\":" + revision + ",\"templateId\":\"soap-en\",\"language\":\"en\"}");
        assertEquals(HttpStatus.CREATED, requested.getStatusCode(), requested.getBody());
        String jobId = tree(requested).get("jobId").asString();

        ResponseEntity<String> picked = call("POST", "/api/v1/documents/artifacts/renders/"
                + jobId + "/pickup", "renderer", ORG_A, "1", null);
        assertEquals(HttpStatus.OK, picked.getStatusCode(), picked.getBody());
        assertTrue(tree(picked).get("state").asString().equalsIgnoreCase("rendering"));

        String objectKey = "pdf/" + ORG_A + "/rev-http/v1";
        ResponseEntity<String> finished = call("POST", "/api/v1/documents/artifacts/renders/"
                + jobId + "/finish", "renderer", ORG_A, "2",
                "{\"objectKey\":\"" + objectKey + "\",\"objectVersion\":1,\"checksum\":\""
                        + CHECKSUM + "\",\"sizeBytes\":128}");
        assertEquals(HttpStatus.OK, finished.getStatusCode(), finished.getBody());
        assertTrue(tree(finished).get("state").asString().equalsIgnoreCase("complete"));

        ResponseEntity<String> bound = call("POST", "/api/v1/documents/artifacts/bindings",
                "reviewer", ORG_A, null, "{\"revision\":" + revision + ",\"jobId\":\"" + jobId + "\"}");
        assertEquals(HttpStatus.CREATED, bound.getStatusCode(), bound.getBody());
        assertEquals(jobId, tree(bound).get("jobId").asString());

        ResponseEntity<String> released = call("POST", "/api/v1/documents/artifacts/releases",
                "clinician", ORG_A, null,
                "{\"revision\":" + revision + ",\"policyVersion\":\"policy-http\",\"requirePdf\":true}");
        assertEquals(HttpStatus.CREATED, released.getStatusCode(), released.getBody());
        assertEquals("clinician", tree(released).get("releasedBy").asString());

        ResponseEntity<String> another = call("POST", "/api/v1/documents/artifacts/renders",
                "clinician", ORG_A, null,
                "{\"revision\":{\"organizationId\":\"" + ORG_A
                        + "\",\"revisionId\":\"rev-fail\",\"revisionVersion\":1,\"contentChecksum\":\""
                        + CHECKSUM + "\"},\"templateId\":\"soap-ar\",\"language\":\"ar\"}");
        String failJob = tree(another).get("jobId").asString();
        ResponseEntity<String> failed = call("POST", "/api/v1/documents/artifacts/renders/"
                + failJob + "/fail", "renderer", ORG_A, "1", "{\"detail\":\"renderer offline\"}");
        assertEquals(HttpStatus.OK, failed.getStatusCode(), failed.getBody());
        assertTrue(tree(failed).get("state").asString().equalsIgnoreCase("failed"));
    }

    @Test
    void nonCleanScanVerdictBlocksDownload() throws Exception {
        JsonNode rejected = uploadNode("uploader", ORG_A);
        String rejectedId = rejected.get("id").asString();
        seedObject(rejected.get("objectKey").asString(), rejected.get("objectVersion").asInt(), 128);

        ResponseEntity<String> scanned = call("POST", "/api/v1/documents/artifacts/" + rejectedId + "/scan",
                "scanner", ORG_A, "1", "{\"verdict\":\"failed\",\"detail\":\"malware signature\"}");
        assertEquals(HttpStatus.OK, scanned.getStatusCode(), scanned.getBody());
        assertTrue(tree(scanned).get("state").asString().equalsIgnoreCase("failed"));

        ResponseEntity<String> blocked = call("GET", "/api/v1/documents/artifacts/" + rejectedId + "/download",
                "clinician", ORG_A, "2", null);
        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode(), blocked.getBody());
        assertEquals("NOT_COMPLETED", tree(blocked).get("code").asString());

        JsonNode clean = uploadNode("uploader", ORG_A);
        String cleanId = clean.get("id").asString();
        seedObject(clean.get("objectKey").asString(), clean.get("objectVersion").asInt(), 128);
        ResponseEntity<String> cleanScan = call("POST", "/api/v1/documents/artifacts/" + cleanId + "/scan",
                "scanner", ORG_A, "1", "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.OK, cleanScan.getStatusCode(), cleanScan.getBody());
        assertTrue(tree(cleanScan).get("state").asString().equalsIgnoreCase("clean"));

        ResponseEntity<String> notCompleted = call("GET", "/api/v1/documents/artifacts/" + cleanId + "/download",
                "clinician", ORG_A, "2", null);
        assertEquals(HttpStatus.CONFLICT, notCompleted.getStatusCode(), notCompleted.getBody());
        assertEquals("NOT_COMPLETED", tree(notCompleted).get("code").asString());
    }

    @Test
    void pdfBindingIsImmutableAndReleaseRequiresTheExactRevision() {
        String revision = """
                {"organizationId":"%s","revisionId":"rev-gate","revisionVersion":1,
                 "contentChecksum":"%s"}
                """.formatted(ORG_A, CHECKSUM);

        ResponseEntity<String> premature = call("POST", "/api/v1/documents/artifacts/releases",
                "clinician", ORG_A, null,
                "{\"revision\":" + revision + ",\"policyVersion\":\"policy-gate\",\"requirePdf\":true}");
        assertEquals(HttpStatus.CONFLICT, premature.getStatusCode(), premature.getBody());
        assertEquals("PDF_INCOMPLETE", tree(premature).get("code").asString());

        ResponseEntity<String> requested = call("POST", "/api/v1/documents/artifacts/renders",
                "clinician", ORG_A, null,
                "{\"revision\":" + revision + ",\"templateId\":\"soap-en\",\"language\":\"en\"}");
        assertEquals(HttpStatus.CREATED, requested.getStatusCode(), requested.getBody());
        String jobId = tree(requested).get("jobId").asString();

        ResponseEntity<String> picked = call("POST", "/api/v1/documents/artifacts/renders/" + jobId + "/pickup",
                "renderer", ORG_A, "1", null);
        assertEquals(HttpStatus.OK, picked.getStatusCode(), picked.getBody());

        ResponseEntity<String> finished = call("POST", "/api/v1/documents/artifacts/renders/" + jobId + "/finish",
                "renderer", ORG_A, "2",
                "{\"objectKey\":\"pdf/" + ORG_A + "/rev-gate/v1\",\"objectVersion\":1,\"checksum\":\""
                        + CHECKSUM + "\",\"sizeBytes\":128}");
        assertEquals(HttpStatus.OK, finished.getStatusCode(), finished.getBody());

        ResponseEntity<String> bound = call("POST", "/api/v1/documents/artifacts/bindings",
                "reviewer", ORG_A, null, "{\"revision\":" + revision + ",\"jobId\":\"" + jobId + "\"}");
        assertEquals(HttpStatus.CREATED, bound.getStatusCode(), bound.getBody());
        assertEquals("rev-gate", tree(bound).get("revisionId").asString());
        assertEquals(1, tree(bound).get("revisionVersion").asInt());

        ResponseEntity<String> released = call("POST", "/api/v1/documents/artifacts/releases",
                "clinician", ORG_A, null,
                "{\"revision\":" + revision + ",\"policyVersion\":\"policy-gate\",\"requirePdf\":true}");
        assertEquals(HttpStatus.CREATED, released.getStatusCode(), released.getBody());

        String corrected = """
                {"organizationId":"%s","revisionId":"rev-gate","revisionVersion":2,
                 "contentChecksum":"%s"}
                """.formatted(ORG_A, "b".repeat(64));
        ResponseEntity<String> staleRelease = call("POST", "/api/v1/documents/artifacts/releases",
                "clinician", ORG_A, null,
                "{\"revision\":" + corrected + ",\"policyVersion\":\"policy-gate\",\"requirePdf\":true}");
        assertEquals(HttpStatus.CONFLICT, staleRelease.getStatusCode(), staleRelease.getBody());
        assertEquals("PDF_INCOMPLETE", tree(staleRelease).get("code").asString());

        ResponseEntity<String> second = call("POST", "/api/v1/documents/artifacts/renders",
                "clinician", ORG_A, null,
                "{\"revision\":" + revision + ",\"templateId\":\"soap-ar\",\"language\":\"ar\"}");
        assertEquals(HttpStatus.CREATED, second.getStatusCode(), second.getBody());
        String secondJob = tree(second).get("jobId").asString();
        assertEquals(HttpStatus.OK, call("POST", "/api/v1/documents/artifacts/renders/" + secondJob + "/pickup",
                "renderer", ORG_A, "1", null).getStatusCode());
        assertEquals(HttpStatus.OK, call("POST", "/api/v1/documents/artifacts/renders/" + secondJob + "/finish",
                "renderer", ORG_A, "2",
                "{\"objectKey\":\"pdf/" + ORG_A + "/rev-gate/v2\",\"objectVersion\":1,\"checksum\":\""
                        + CHECKSUM + "\",\"sizeBytes\":128}").getStatusCode());
        ResponseEntity<String> competing = call("POST", "/api/v1/documents/artifacts/bindings",
                "reviewer", ORG_A, null,
                "{\"revision\":" + revision + ",\"jobId\":\"" + secondJob + "\"}");
        assertEquals(HttpStatus.CONFLICT, competing.getStatusCode(), competing.getBody());
        assertEquals("PDF_ALREADY_BOUND", tree(competing).get("code").asString());
    }

    @Test
    void commandsAreTenantScopedAndFailClosed() {
        String id = upload("uploader", ORG_A);

        ResponseEntity<String> foreign = call("GET", "/api/v1/documents/artifacts/" + id,
                "foreign", ORG_B, null, null);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode());

        ResponseEntity<String> forbidden = call("POST", "/api/v1/documents/artifacts/" + id + "/scan",
                "clinician", ORG_A, "1", "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());

        ResponseEntity<String> noPrecondition = call("POST", "/api/v1/documents/artifacts/" + id + "/scan",
                "scanner", ORG_A, null, "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, noPrecondition.getStatusCode());

        ResponseEntity<String> patient = call("GET", "/api/v1/documents/artifacts/" + id,
                "patient-role", ORG_A, null, null);
        assertEquals(HttpStatus.FORBIDDEN, patient.getStatusCode(), patient.getBody());

        TenantContext.clear();
        ApiException failure = assertThrows(ApiException.class, () -> controller.view(id));
        assertEquals(401, failure.status());
        assertEquals("UNAUTHENTICATED", failure.code());
    }
}
