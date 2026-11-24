package com.aurevia.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;

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
 * ARC-06 clause 2 proof on real PostgreSQL: every upload is bound to a unique,
 * tenant-scoped, patient-opaque object key at immutable version one, and each
 * protected transition releases the object only when that exact version and
 * checksum verify. Commands go through the real controller and the production
 * JDBC store against a fresh database; all fixtures are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_doc_objver_clause_it")
@ActiveProfiles("synthetic-auth")
class UseUniqueObjectKeysVerifiedObjectVersionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_doc_objver_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTEXT = "ctx-objver";
    /** Deliberately patient-shaped so the "no PHI in a key" assertion can fail. */
    private static final String PATIENT = "pat-objver";
    private static final String FILE_NAME = "pat-objver-notes.pdf";
    private static final String CHECKSUM = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY_PATTERN =
            "^quarantine/%s/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}/v1$";

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, apply the production migrations and seed two synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_doc_objver_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_doc_objver_clause_it OWNER aurevia_migrate");
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
            seedStaff(statement, ORG_B, "uploader-b", "reception");
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

    /** Drop the synthetic database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_doc_objver_clause_it WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Object key clause tenant') ON CONFLICT DO NOTHING");
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
            return spec.retrieve().onStatus(status -> true, (request, response) -> { })
                    .toEntity(String.class);
        }
        RestClient.RequestHeadersSpec<?> spec = rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org);
        if (ifMatch != null) {
            spec.header("If-Match", ifMatch);
        }
        return spec.retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        return JSON.readTree(response.getBody() == null ? "{}" : response.getBody());
    }

    private JsonNode uploadNode(String subject, String org) {
        String body = """
                {"contextId":"%s","fileName":"%s","contentType":"application/pdf",
                 "magicHex":"25504446","sizeBytes":128,"checksum":"%s"}
                """.formatted(CONTEXT, FILE_NAME, CHECKSUM);
        ResponseEntity<String> response = call("POST", "/api/v1/documents/artifacts", subject, org, null, body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return tree(response);
    }

    private ResponseEntity<String> scan(String id, String org) {
        return call("POST", "/api/v1/documents/artifacts/" + id + "/scan",
                "scanner", org, "1", "{\"verdict\":\"clean\",\"detail\":\"ok\"}");
    }

    private ResponseEntity<String> complete(String id, String org) {
        return call("POST", "/api/v1/documents/artifacts/" + id + "/complete",
                "reviewer", org, "2", "{\"note\":\"verified\"}");
    }

    /** Seed a verified private object row: the authority the domain verifies on each command. */
    private static void seedObject(String org, String objectKey, int objectVersion,
                                   String checksum, long sizeBytes) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO public.document_verified_object "
                     + "(organization_id, object_key, object_version, checksum, size_bytes) "
                     + "VALUES (CAST(? AS uuid), ?, ?, ?, ?)")) {
            connection.createStatement().execute("SET app.current_org = '" + org + "'");
            insert.setString(1, org);
            insert.setString(2, objectKey);
            insert.setInt(3, objectVersion);
            insert.setString(4, checksum);
            insert.setLong(5, sizeBytes);
            insert.executeUpdate();
        }
    }

    /** Remove a verified object row so a later download must fail its re-check. */
    private static void removeObject(String org, String objectKey) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM public.document_verified_object "
                     + "WHERE organization_id = CAST(? AS uuid) AND object_key = ?")) {
            connection.createStatement().execute("SET app.current_org = '" + org + "'");
            delete.setString(1, org);
            delete.setString(2, objectKey);
            delete.executeUpdate();
        }
    }

    /** Read the lifecycle state from either a flat artifact or a wrapped view response. */
    private static String stateOf(JsonNode response) {
        JsonNode state = response.has("artifact") ? response.get("artifact").get("state") : response.get("state");
        return state.asString().toLowerCase(Locale.ROOT);
    }

    @Test
    void eachUploadGetsAUniqueTenantScopedKeyAtVersionOneAndTheVerifiedObjectCompletes() throws Exception {
        JsonNode first = uploadNode("uploader", ORG_A);
        JsonNode second = uploadNode("uploader", ORG_A);
        String firstKey = first.get("objectKey").asString();
        String secondKey = second.get("objectKey").asString();

        assertEquals(CONTEXT, first.get("contextId").asString());
        assertEquals(PATIENT, first.get("patientId").asString());
        assertEquals(CONTEXT, second.get("contextId").asString());
        assertEquals(PATIENT, second.get("patientId").asString());
        assertNotEquals(first.get("id").asString(), second.get("id").asString());
        assertTrue(firstKey.matches(KEY_PATTERN.formatted(ORG_A)),
                "the key is an opaque tenant-scoped random alias at v1: " + firstKey);
        assertTrue(secondKey.matches(KEY_PATTERN.formatted(ORG_A)),
                "the key is an opaque tenant-scoped random alias at v1: " + secondKey);
        assertFalse(firstKey.contains(PATIENT), "a key must never carry the patient identifier");
        assertFalse(firstKey.contains(FILE_NAME), "a key must never carry the original file name");
        assertFalse(firstKey.contains(CONTEXT), "a key must never carry the patient-linked context");
        assertNotEquals(firstKey, secondKey,
                "two uploads into the same context must never share an object key");
        assertEquals(1, first.get("objectVersion").asInt(),
                "an upload is bound to immutable object version one");
        assertEquals(1, second.get("objectVersion").asInt());

        String id = first.get("id").asString();
        seedObject(ORG_A, firstKey, 1, CHECKSUM, 128);
        assertEquals(HttpStatus.OK, scan(id, ORG_A).getStatusCode());
        ResponseEntity<String> completedResponse = complete(id, ORG_A);
        assertEquals(HttpStatus.OK, completedResponse.getStatusCode(), completedResponse.getBody());
        JsonNode completed = tree(completedResponse);
        assertTrue(stateOf(completed).equalsIgnoreCase("completed"));
        assertEquals(3, completed.get("version").asInt(), "the artifact state version advanced twice");
        assertEquals(firstKey, completed.get("objectKey").asString(),
                "a state change must not replace the bound object key");
        assertEquals(1, completed.get("objectVersion").asInt(),
                "the object version is immutable across state changes");

        ResponseEntity<String> downloadResponse = call("GET",
                "/api/v1/documents/artifacts/" + id + "/download", "clinician", ORG_A, "3", null);
        assertEquals(HttpStatus.OK, downloadResponse.getStatusCode(), downloadResponse.getBody());
        JsonNode handle = tree(downloadResponse);
        assertEquals(firstKey, handle.get("objectKey").asString(),
                "the mediated handle exposes the same unique private object key");
        assertEquals(1, handle.get("objectVersion").asInt(),
                "the mediated handle exposes the same verified object version");
        assertEquals(CHECKSUM, handle.get("checksum").asString());
        assertEquals("application/pdf", handle.get("contentType").asString());
        assertEquals(128, handle.get("sizeBytes").asInt());
    }

    @Test
    void completionRefusesWhenTheStoredObjectVersionDoesNotMatch() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();
        String objectKey = created.get("objectKey").asString();

        // The artifact is bound to immutable object version 1, but only version 2
        // of that private object is on record: the version must be verified, not assumed.
        seedObject(ORG_A, objectKey, 2, CHECKSUM, 128);
        assertEquals(HttpStatus.OK, scan(id, ORG_A).getStatusCode());

        ResponseEntity<String> refused = complete(id, ORG_A);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("OBJECT_UNVERIFIED", tree(refused).get("code").asString());
        JsonNode view = tree(call("GET", "/api/v1/documents/artifacts/" + id,
                "uploader", ORG_A, null, null));
        assertEquals("clean", stateOf(view), "a refused completion must not change state");
        for (JsonNode event : view.get("events")) {
            assertFalse("UPLOAD_COMPLETED".equalsIgnoreCase(event.path("eventType").asString()),
                    "a refused completion must not record a completion fact");
        }

        // With the exact version on record the same command succeeds, proving the
        // refusal was specifically the version mismatch and not a stale precondition.
        seedObject(ORG_A, objectKey, 1, CHECKSUM, 128);
        ResponseEntity<String> recovered = complete(id, ORG_A);
        assertEquals(HttpStatus.OK, recovered.getStatusCode(), recovered.getBody());
        assertTrue(stateOf(tree(recovered)).equalsIgnoreCase("completed"));
    }

    @Test
    void completionRefusesWhenNoVerifiedObjectRowExists() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();
        assertEquals(HttpStatus.OK, scan(id, ORG_A).getStatusCode());

        ResponseEntity<String> refused = complete(id, ORG_A);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("OBJECT_UNVERIFIED", tree(refused).get("code").asString());
        assertEquals("clean", stateOf(tree(call("GET", "/api/v1/documents/artifacts/" + id,
                "uploader", ORG_A, null, null))));
    }

    @Test
    void completionRefusesWhenTheVerifiedChecksumDiffers() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();
        seedObject(ORG_A, created.get("objectKey").asString(), 1, "b".repeat(64), 128);
        assertEquals(HttpStatus.OK, scan(id, ORG_A).getStatusCode());

        ResponseEntity<String> refused = complete(id, ORG_A);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("OBJECT_UNVERIFIED", tree(refused).get("code").asString());
    }

    @Test
    void anotherTenantsVerifiedObjectRowCannotSatisfyThisArtifactsObjectKey() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();
        String objectKey = created.get("objectKey").asString();
        JsonNode foreignUpload = uploadNode("uploader-b", ORG_B);

        assertTrue(objectKey.matches(KEY_PATTERN.formatted(ORG_A)));
        assertTrue(foreignUpload.get("objectKey").asString().matches(KEY_PATTERN.formatted(ORG_B)),
                "an identical local context in another tenant gets its own key namespace");
        assertNotEquals(objectKey, foreignUpload.get("objectKey").asString());

        // A row owned by the other tenant, carrying this artifact's exact key,
        // version and checksum, must still not verify it.
        seedObject(ORG_B, objectKey, 1, CHECKSUM, 128);
        assertEquals(HttpStatus.OK, scan(id, ORG_A).getStatusCode());

        ResponseEntity<String> refused = complete(id, ORG_A);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("OBJECT_UNVERIFIED", tree(refused).get("code").asString());
        assertEquals("clean", stateOf(tree(call("GET", "/api/v1/documents/artifacts/" + id,
                "uploader", ORG_A, null, null))));
    }

    @Test
    void downloadRefusesWhenTheVerifiedObjectIsRemovedAfterCompletion() throws Exception {
        JsonNode created = uploadNode("uploader", ORG_A);
        String id = created.get("id").asString();
        String objectKey = created.get("objectKey").asString();
        seedObject(ORG_A, objectKey, 1, CHECKSUM, 128);
        assertEquals(HttpStatus.OK, scan(id, ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, complete(id, ORG_A).getStatusCode());

        removeObject(ORG_A, objectKey);
        ResponseEntity<String> refused = call("GET",
                "/api/v1/documents/artifacts/" + id + "/download", "clinician", ORG_A, "3", null);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("OBJECT_UNVERIFIED", tree(refused).get("code").asString(),
                "a completed artifact still re-verifies its object version on download");
    }
}
