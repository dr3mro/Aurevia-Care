package com.aurevia.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * ARC-06 clause 5 acceptance: document flows keep protected clinical payloads
 * out of ordinary operational surfaces (logs, URLs, analytics, notifications).
 *
 * <p>The HTTP-observable part of the rule is proven here through the real
 * controller and the tenant-scoped JDBC store on a fresh PostgreSQL database:
 * the mediated download handle carries metadata only, the append-only fact
 * ledger carries only bounded non-sensitive detail, and refusal paths never
 * echo a protected value in an error body or the ledger. The remaining channels
 * have no code path in this slice and are evidenced by static search in the
 * clause document; no log appender, notification or analytics hook is added.</p>
 *
 * <p>All identifiers, subjects and payload metadata are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_doc_arc06_it")
@ActiveProfiles("synthetic-auth")
class KeepProtectedClinicalPayloadsOutOrdinaryOperationalTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DATABASE = "aurevia_doc_arc06_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTEXT = "ctx-arc06";
    private static final String PATIENT = "patient-arc06-7741";
    private static final String MARKER = "SYNTH_MARKER_7741_SCAN";
    private static final String FILE_NAME = "scan-SYNTH_MARKER_7741_SCAN-note.pdf";
    private static final String CHECKSUM = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "uploader", "reception");
            seedStaff(statement, ORG_A, "scanner", "security_scanner");
            seedStaff(statement, ORG_A, "reviewer", "document_reviewer");
            seedStaff(statement, ORG_A, "clinician", "clinician");
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
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

    private JsonNode uploadNode(String fileName) {
        String body = """
                {"contextId":"%s","fileName":"%s","contentType":"application/pdf",
                 "magicHex":"25504446","sizeBytes":128,"checksum":"%s"}
                """.formatted(CONTEXT, fileName, CHECKSUM);
        ResponseEntity<String> response = call("POST", "/api/v1/documents/artifacts",
                "uploader", ORG_A, null, body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return tree(response);
    }

    private static void seedObject(String objectKey, int objectVersion, long sizeBytes) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
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

    /**
     * Drive one artifact through upload, clean scan and completion so a mediated
     * handle and a populated fact trail exist for the assertions.
     *
     * @return the artifact identifier
     * @throws Exception when the private verified object cannot be seeded
     */
    private String completedArtifact() throws Exception {
        JsonNode created = uploadNode(FILE_NAME);
        String id = created.get("id").asString();
        // Prove the protected marker really reached the stored artifact, so a
        // later "response lacks the marker" assertion cannot pass vacuously.
        assertTrue(created.toString().contains(MARKER), created.toString());
        assertEquals(PATIENT, created.get("patientId").asString());
        seedObject(created.get("objectKey").asString(), created.get("objectVersion").asInt(), 128);
        assertEquals(HttpStatus.OK, call("POST", "/api/v1/documents/artifacts/" + id + "/scan",
                "scanner", ORG_A, "1", "{\"verdict\":\"clean\",\"detail\":\"ok\"}").getStatusCode());
        assertEquals(HttpStatus.OK, call("POST", "/api/v1/documents/artifacts/" + id + "/complete",
                "reviewer", ORG_A, "2", "{\"note\":\"verified\"}").getStatusCode());
        return id;
    }

    /**
     * Count ledger rows whose bounded detail contains a caller's value.
     *
     * @param value distinctive synthetic token
     * @return number of matching facts, zero for a non-reflecting surface
     * @throws Exception when the ledger cannot be queried
     */
    private static long ledgerMentions(String value) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM public.document_event WHERE detail LIKE ?")) {
            query.setString(1, "%" + value + "%");
            try (var result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    @Test
    void downloadHandleCarriesNoPayloadNameOrUrl() throws Exception {
        String id = completedArtifact();

        ResponseEntity<String> downloaded = call("GET",
                "/api/v1/documents/artifacts/" + id + "/download", "clinician", ORG_A, "3", null);
        assertEquals(HttpStatus.OK, downloaded.getStatusCode(), downloaded.getBody());
        JsonNode handle = tree(downloaded);
        assertEquals(5, handle.size(), handle.toString());
        assertEquals(Set.of("objectKey", "objectVersion", "checksum", "contentType", "sizeBytes"),
                fieldNames(handle));
        assertNull(downloaded.getHeaders().getLocation(), "a mediated handle is not a redirect");

        String body = downloaded.getBody();
        assertNotNull(body);
        assertFalse(body.contains(MARKER), body);
        assertFalse(body.contains(PATIENT), body);
        assertFalse(body.contains("http"), body);
        assertFalse(body.contains("s3://"), body);
        assertFalse(body.contains("X-Amz"), body);
        // The opaque object key and verified checksum are deliberately present.
        assertEquals(CHECKSUM, handle.get("checksum").asString());
        assertFalse(handle.get("objectKey").asString().isBlank());
    }

    @Test
    void auditFactsNeverCarryPayloadNameOrUrl() throws Exception {
        String id = completedArtifact();
        call("GET", "/api/v1/documents/artifacts/" + id + "/download",
                "clinician", ORG_A, "3", null);

        JsonNode events = tree(call("GET", "/api/v1/documents/artifacts/" + id,
                "clinician", ORG_A, null, null)).get("events");
        assertNotNull(events);
        assertTrue(events.size() >= 4, events.toString());

        Set<String> types = new HashSet<>();
        boolean sawBoundDetail = false;
        for (JsonNode fact : events) {
            types.add(fact.get("eventType").asString());
            JsonNode detail = fact.get("detail");
            if (!detail.isNull()) {
                sawBoundDetail = true;
                String text = detail.asString();
                assertTrue(text.length() <= 200, text);
                assertFalse(text.contains(MARKER), text);
                assertFalse(text.contains(PATIENT), text);
                assertFalse(text.contains("http"), text);
                assertFalse(text.contains("s3://"), text);
                assertFalse(text.contains("X-Amz"), text);
            }
        }
        assertTrue(sawBoundDetail, "non-sensitive operator detail is expected on scan and complete");
        assertTrue(types.containsAll(
                        Set.of("UPLOAD_ACCEPTED", "SCAN_CLEAN", "UPLOAD_COMPLETED", "DOWNLOADED")),
                types.toString());
        assertEquals(0, ledgerMentions(MARKER));
    }

    @Test
    void wrongRoleIsRefusedWithoutEchoingProtectedData() throws Exception {
        String id = completedArtifact();

        ResponseEntity<String> wrongRole = call("POST",
                "/api/v1/documents/artifacts/" + id + "/scan", "clinician", ORG_A, "1",
                "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());
        JsonNode denial = tree(wrongRole);
        assertEquals("FORBIDDEN", denial.get("code").asString());
        assertEquals("missing SCAN permission", denial.get("message").asString());
        assertFalse(wrongRole.getBody().contains(MARKER), wrongRole.getBody());
        assertFalse(wrongRole.getBody().contains(PATIENT), wrongRole.getBody());
        assertFalse(wrongRole.getBody().contains("http"), wrongRole.getBody());

        JsonNode facts = tree(call("GET", "/api/v1/documents/artifacts/" + id,
                "uploader", ORG_A, null, null)).get("events");
        boolean sawDenial = false;
        for (JsonNode fact : facts) {
            if ("ACCESS_DENIED".equals(fact.get("eventType").asString())) {
                sawDenial = true;
                assertEquals("FORBIDDEN", fact.get("detail").asString());
                assertFalse(fact.get("detail").asString().contains(MARKER));
            }
        }
        assertTrue(sawDenial, "the same-tenant wrong-role refusal is recorded as a bounded fact");

        // A foreign tenant is concealed with the generic error body only: the
        // not-found path deliberately appends no ledger fact, so no detail can
        // be inspected here and none is asserted.
        ResponseEntity<String> foreign = call("GET", "/api/v1/documents/artifacts/" + id,
                "foreign", ORG_B, null, null);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode(), foreign.getBody());
        JsonNode concealed = tree(foreign);
        assertEquals("NOT_FOUND", concealed.get("code").asString());
        assertEquals("document artifact not found", concealed.get("message").asString());
        assertFalse(foreign.getBody().contains(MARKER), foreign.getBody());
        assertFalse(foreign.getBody().contains(PATIENT), foreign.getBody());
        assertFalse(foreign.getBody().contains("http"), foreign.getBody());
    }

    @Test
    void smuggledValueIsNotEchoedInErrorDetail() throws Exception {
        String id = completedArtifact();
        long before = ledgerMentions(MARKER);

        String smuggled = "s3://private/patient-" + MARKER + "/note.pdf?X-Amz-Signature=token";
        String body = """
                {"contextId":"%s","fileName":"%s","contentType":"application/pdf",
                 "magicHex":"25504446","sizeBytes":128,"checksum":"%s"}
                """.formatted(CONTEXT, smuggled, CHECKSUM);
        ResponseEntity<String> rejected = call("POST", "/api/v1/documents/artifacts",
                "uploader", ORG_A, null, body);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, rejected.getStatusCode(), rejected.getBody());
        JsonNode error = tree(rejected);
        assertEquals("INVALID_FILE_NAME", error.get("code").asString());
        assertEquals("fileName must be 1-200 characters without a path or control character",
                error.get("message").asString());
        assertFalse(rejected.getBody().contains(MARKER), rejected.getBody());
        assertFalse(rejected.getBody().contains("s3://"), rejected.getBody());
        assertNull(rejected.getHeaders().getLocation());
        assertEquals(before, ledgerMentions(MARKER), "a rejected value must not enter the ledger");

        JsonNode facts = tree(call("GET", "/api/v1/documents/artifacts/" + id,
                "clinician", ORG_A, null, null)).get("events");
        for (JsonNode fact : facts) {
            assertFalse(fact.toString().contains(MARKER), fact.toString());
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }
}
