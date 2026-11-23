package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-06 clause 1 proof over real PostgreSQL with synthetic fixtures only.
 *
 * <p>Every command travels through the production HTTP entry point
 * {@code /api/v1/documents/artifacts} into the injected quarantine service, the
 * JDBC store and the V81 tenant schema. The positive path shows that a payload
 * stays private in quarantine until an independent clean verdict and an
 * authorized completion, and that the resulting handle carries metadata only.
 * The negative paths show that invalid size, type, checksum and leading bytes
 * are refused before a row is written, that forbidden states and stale versions
 * are refused, and that another tenant or role cannot read or scan the file.
 * No production class is changed by this proof.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_clause1_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ValidateSizeSupportedTypeChecksumScanStateTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_doc_clause1_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTEXT = "ctx-doc-clause1";
    private static final String PATIENT = "pat-doc-clause1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final long SIZE = 128L;
    private static final String ROUTE = "/api/v1/documents/artifacts";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fields that would expose protected clinical payload bytes or a public locator. */
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "bytes", "payload", "content", "data", "base64", "url", "uri");

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, apply production migrations and seed two synthetic tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "uploader", "reception");
            seedStaff(statement, ORG_A, "scanner", "security_scanner");
            seedStaff(statement, ORG_A, "reviewer", "document_reviewer");
            seedStaff(statement, ORG_A, "clinician", "clinician");
            seedStaff(statement, ORG_A, "patient-role", "patient");
            seedStaff(statement, ORG_A, "renderer", "pdf_renderer");
            seedStaff(statement, ORG_B, "foreign", "clinician");
            seedAuthorization(statement, ORG_A);
            seedAuthorization(statement, ORG_B);
        }
    }

    /** Drop the synthetic database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Document clause tenant') ON CONFLICT DO NOTHING");
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

    private static void seedAuthorization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.document_authorization "
                + "(organization_id, context_id, grant_organization_id, patient_id, active) VALUES ('"
                + org + "', '" + CONTEXT + "', '" + org + "', '" + PATIENT + "', true)");
    }

    private static void seedObject(String objectKey, int objectVersion, long sizeBytes) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO public.document_verified_object "
                     + "(organization_id, object_key, object_version, checksum, size_bytes) "
                     + "VALUES (CAST(? AS uuid), ?, ?, ?, ?)")) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            insert.setString(1, ORG_A);
            insert.setString(2, objectKey);
            insert.setInt(3, objectVersion);
            insert.setString(4, CHECKSUM);
            insert.setLong(5, sizeBytes);
            insert.executeUpdate();
        }
    }

    /**
     * Count committed artifacts for one tenant. The GUC must be set on this
     * connection because FORCE RLS otherwise hides every row and the count
     * would be vacuously zero.
     */
    private static long artifactCount(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.document_artifact WHERE organization_id = CAST('"
                            + org + "' AS uuid)")) {
                assertTrue(rows.next(), "the count query must return one row");
                return rows.getLong(1);
            }
        }
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

    private static String item(String id) {
        return ROUTE + "/" + id;
    }

    private static String uploadBody(String contextId, String fileName, String contentType,
                                     String magicHex, long sizeBytes, String checksum) {
        return """
                {"contextId":"%s","fileName":"%s","contentType":"%s",
                 "magicHex":"%s","sizeBytes":%d,"checksum":"%s"}
                """.formatted(contextId, fileName, contentType, magicHex, sizeBytes, checksum);
    }

    private static String validUploadBody() {
        return uploadBody(CONTEXT, "notes.pdf", "application/pdf", "25504446", SIZE, CHECKSUM);
    }

    private JsonNode uploadNode(String subject, String org) {
        return uploadNode(subject, org, validUploadBody());
    }

    private JsonNode uploadNode(String subject, String org, String body) {
        ResponseEntity<String> response = call("POST", ROUTE, subject, org, null, body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return tree(response);
    }

    private String upload(String subject, String org) {
        return uploadNode(subject, org).get("id").asString();
    }

    /** Protected payloads and public locators must never appear on a response. */
    private static void assertNoPayloadFields(JsonNode response) {
        for (String field : FORBIDDEN_FIELDS) {
            assertFalse(response.has(field), "protected upload metadata must not expose " + field);
        }
    }

    private void assertRefusedUpload(long baseline, String body, String code) throws Exception {
        ResponseEntity<String> response = call("POST", ROUTE, "uploader", ORG_A, null, body);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, response.getStatusCode(), response.getBody());
        assertEquals(code, tree(response).get("code").asString(), response.getBody());
        assertFalse(tree(response).has("id"), "a refused upload must not return a created artifact");
        assertNull(response.getHeaders().getLocation(), "a refused upload must not return a location");
        assertEquals(baseline, artifactCount(ORG_A), "a refused upload must not be stored");
    }

    @Test
    void validUploadStaysPrivateUntilCleanScanAndAuthorizedCompletionMakesItDownloadable() throws Exception {
        long before = artifactCount(ORG_A);
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();

        assertTrue("quarantined".equalsIgnoreCase(created.get("state").asString()));
        assertTrue("pending".equalsIgnoreCase(created.get("verdict").asString()));
        assertTrue(created.get("objectKey").asString().startsWith("quarantine/" + ORG_A + "/"));
        assertNoPayloadFields(created);
        // Positive control: the RLS-scoped count sees the committed upload in its
        // own tenant and nothing in the peer tenant, so the negative assertions
        // below cannot pass vacuously.
        assertEquals(before + 1L, artifactCount(ORG_A));
        assertEquals(0L, artifactCount(ORG_B));

        // Quarantine is private: a non-uploader cannot even view the projection.
        ResponseEntity<String> concealed = call("GET", item(id), "clinician", ORG_A, null, null);
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());

        ResponseEntity<String> earlyDownload = call("GET", item(id) + "/download",
                "clinician", ORG_A, "1", null);
        assertEquals(HttpStatus.CONFLICT, earlyDownload.getStatusCode(), earlyDownload.getBody());
        assertEquals("NOT_COMPLETED", tree(earlyDownload).get("code").asString());

        ResponseEntity<String> scanned = call("POST", item(id) + "/scan", "scanner", ORG_A, "1",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.OK, scanned.getStatusCode(), scanned.getBody());
        assertTrue("clean".equalsIgnoreCase(tree(scanned).get("state").asString()));

        // Completion is refused until the private object version verifies, even
        // after an independent clean verdict.
        ResponseEntity<String> unverified = call("POST", item(id) + "/complete", "reviewer", ORG_A, "2",
                "{\"note\":\"verified\"}");
        assertEquals(HttpStatus.CONFLICT, unverified.getStatusCode(), unverified.getBody());
        assertEquals("OBJECT_UNVERIFIED", tree(unverified).get("code").asString());

        seedObject(created.get("objectKey").asString(), created.get("objectVersion").asInt(), SIZE);

        ResponseEntity<String> completed = call("POST", item(id) + "/complete", "reviewer", ORG_A, "2",
                "{\"note\":\"verified\"}");
        assertEquals(HttpStatus.OK, completed.getStatusCode(), completed.getBody());
        assertTrue("completed".equalsIgnoreCase(tree(completed).get("state").asString()));

        ResponseEntity<String> downloaded = call("GET", item(id) + "/download", "clinician", ORG_A, "3", null);
        assertEquals(HttpStatus.OK, downloaded.getStatusCode(), downloaded.getBody());
        JsonNode handle = tree(downloaded);
        assertEquals(5, handle.size(), "the mediated handle carries metadata only");
        assertEquals(created.get("objectKey").asString(), handle.get("objectKey").asString());
        assertEquals(created.get("objectVersion").asInt(), handle.get("objectVersion").asInt());
        assertEquals(CHECKSUM, handle.get("checksum").asString());
        assertEquals("application/pdf", handle.get("contentType").asString());
        assertEquals(SIZE, handle.get("sizeBytes").asLong());
        assertTrue(handle.get("objectKey").asString().endsWith("/v1"), "the key is a private versioned object");
        assertNoPayloadFields(handle);
        assertFalse(handle.has("fileName"), "the handle must not disclose the original name");

        ResponseEntity<String> viewed = call("GET", item(id), "clinician", ORG_A, null, null);
        assertEquals(HttpStatus.OK, viewed.getStatusCode(), viewed.getBody());
        assertTrue(tree(viewed).get("events").size() > 0);
    }

    @Test
    void oversizeUnsupportedTypeBadChecksumAndMismatchedMagicAreRefusedBeforeStoringAnything() throws Exception {
        long baseline = artifactCount(ORG_A);
        // 10 MiB + 1 byte; every other field stays valid so the size guard fires.
        assertRefusedUpload(baseline, uploadBody(CONTEXT, "notes.pdf", "application/pdf",
                "25504446", 10L * 1024L * 1024L + 1L, CHECKSUM), "INVALID_SIZE_BYTES");
        assertRefusedUpload(baseline, uploadBody(CONTEXT, "notes.pdf", "application/octet-stream",
                "25504446", SIZE, CHECKSUM), "UNSUPPORTED_CONTENT_TYPE");
        assertRefusedUpload(baseline, uploadBody(CONTEXT, "notes.pdf", "application/pdf",
                "25504446", SIZE, "z".repeat(64)), "INVALID_CHECKSUM");
        // Well-formed PNG magic under a declared PDF reaches the byte-signature guard.
        assertRefusedUpload(baseline, uploadBody(CONTEXT, "notes.pdf", "application/pdf",
                "89504e470d0a1a0a", SIZE, CHECKSUM), "CONTENT_MISMATCH");
        assertRefusedUpload(baseline, uploadBody(CONTEXT, "notes.png", "application/pdf",
                "25504446", SIZE, CHECKSUM), "EXTENSION_MISMATCH");
    }

    @Test
    void downloadBeforeCompletionAndScanAfterCompletionAreRefused() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();

        ResponseEntity<String> quarantined = call("GET", item(id) + "/download",
                "clinician", ORG_A, "1", null);
        assertEquals(HttpStatus.CONFLICT, quarantined.getStatusCode(), quarantined.getBody());
        assertEquals("NOT_COMPLETED", tree(quarantined).get("code").asString());

        JsonNode draft = uploadNode("uploader", ORG_A);
        ResponseEntity<String> notClean = call("POST", item(draft.get("id").asString()) + "/complete",
                "reviewer", ORG_A, "1", "{\"note\":\"verified\"}");
        assertEquals(HttpStatus.CONFLICT, notClean.getStatusCode(), notClean.getBody());
        assertEquals("NOT_CLEAN", tree(notClean).get("code").asString());

        seedObject(created.get("objectKey").asString(), created.get("objectVersion").asInt(), SIZE);
        ResponseEntity<String> scanned = call("POST", item(id) + "/scan", "scanner", ORG_A, "1",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.OK, scanned.getStatusCode(), scanned.getBody());
        assertTrue("clean".equalsIgnoreCase(tree(scanned).get("state").asString()));

        ResponseEntity<String> stillClean = call("GET", item(id) + "/download",
                "clinician", ORG_A, "2", null);
        assertEquals(HttpStatus.CONFLICT, stillClean.getStatusCode(), stillClean.getBody());
        assertEquals("NOT_COMPLETED", tree(stillClean).get("code").asString());

        ResponseEntity<String> completed = call("POST", item(id) + "/complete", "reviewer", ORG_A, "2",
                "{\"note\":\"verified\"}");
        assertEquals(HttpStatus.OK, completed.getStatusCode(), completed.getBody());

        ResponseEntity<String> rescan = call("POST", item(id) + "/scan", "scanner", ORG_A, "3",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.CONFLICT, rescan.getStatusCode(), rescan.getBody());
        assertEquals("INVALID_SCAN_STATE", tree(rescan).get("code").asString());
    }

    @Test
    void foreignTenantAndWrongRoleCannotReadOrScanQuarantineArtifact() {
        String id = upload("uploader", ORG_A);

        ResponseEntity<String> foreign = call("GET", item(id), "foreign", ORG_B, null, null);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode(), foreign.getBody());
        assertEquals("NOT_FOUND", tree(foreign).get("code").asString());

        ResponseEntity<String> concealed = call("GET", item(id), "clinician", ORG_A, null, null);
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());

        ResponseEntity<String> wrongRole = call("POST", item(id) + "/scan", "clinician", ORG_A, "1",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());

        ResponseEntity<String> patient = call("GET", item(id), "patient-role", ORG_A, null, null);
        assertEquals(HttpStatus.FORBIDDEN, patient.getStatusCode(), patient.getBody());

        ResponseEntity<String> renderer = call("POST", ROUTE, "renderer", ORG_A, null, validUploadBody());
        assertEquals(HttpStatus.FORBIDDEN, renderer.getStatusCode(), renderer.getBody());
    }

    @Test
    void staleVersionIsRejectedOnScan() {
        String id = upload("uploader", ORG_A);

        ResponseEntity<String> scanned = call("POST", item(id) + "/scan", "scanner", ORG_A, "1",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.OK, scanned.getStatusCode(), scanned.getBody());

        // The version guard runs before the state guard, so a stale header wins
        // deterministically even though the artifact is now clean.
        ResponseEntity<String> stale = call("POST", item(id) + "/scan", "scanner", ORG_A, "9",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", tree(stale).get("code").asString());

        ResponseEntity<String> missing = call("POST", item(id) + "/scan", "scanner", ORG_A, null,
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, missing.getStatusCode(), missing.getBody());
    }
}
