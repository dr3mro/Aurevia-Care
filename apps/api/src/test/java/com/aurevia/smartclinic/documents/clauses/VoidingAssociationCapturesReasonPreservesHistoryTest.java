package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;

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
 * CLN-07 clause 4 acceptance: voiding an association records a reason and keeps
 * its original bytes and append-only history (synthetic data on real PostgreSQL).
 *
 * <p>The entry point is {@code POST /api/v1/documents/associations/{id}/void}
 * through the real controller; the assertions read back through
 * {@code GET /api/v1/documents/associations/{id}} and {@code .../content}. The
 * successful void asserts the reason on both the association and the appended
 * {@code void} event, one version advance, an actor derived from the
 * authenticated header rather than the body, unchanged original bytes and
 * checksum, and the retained {@code link} event. The refusal cases assert that
 * a malformed id, the wrong role, a peer tenant, a stale version, a blank or
 * absent reason and a second void all fail without writing a reason or a
 * history event.</p>
 *
 * <p>An active association always has version 1 and only a void advances the
 * version, so the stale-version case advances a fixture row directly under a
 * superuser connection with triggers disabled. That is a deterministic fixture
 * setup for the service's version guard, not a claim that production code can
 * advance an active association's version. The V17 forced-RLS schema and the
 * immutable-original update trigger stay active for every HTTP call.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_void_assoc_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class VoidingAssociationCapturesReasonPreservesHistoryTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_void_assoc_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN_A = "clinician-a";
    private static final String RECEPTION_A = "reception-a";
    private static final String CLINICIAN_B = "clinician-b";
    private static final String REASON = "wrong chart";
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Bind the RestClient to the server started for this test class. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a dedicated database, apply the real migrations and seed two synthetic tenants. */
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
            seed(statement, ORG_A, CLINICIAN_A, "clinician");
            seed(statement, ORG_A, RECEPTION_A, "reception");
            seed(statement, ORG_B, CLINICIAN_B, "clinician");
        }
    }

    /** Drop the dedicated database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                        String ifMatch) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .header("If-Match", ifMatch == null ? "W/\"1\"" : ifMatch)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<byte[]> content(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(byte[].class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private String registerChart() throws Exception {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-"
                + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = post("/api/v1/patients", body, RECEPTION_A, ORG_A, null);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return json(response).get("id").asText();
    }

    private String link(String chartId) throws Exception {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chartId
                + "\",\"fileName\":\"scan.pdf\",\"mediaType\":\"application/pdf\","
                + "\"contentBase64\":\"" + ENCODED + "\",\"rotationDegrees\":90}";
        ResponseEntity<String> response = post("/api/v1/documents/associations", body,
                CLINICIAN_A, ORG_A, null);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return json(response).get("id").asText();
    }

    private ResponseEntity<String> voidAssociation(String id, String body, String subject,
                                                   String org, String ifMatch) {
        return post("/api/v1/documents/associations/" + id + "/void", body, subject, org, ifMatch);
    }

    private static String reasonBody(String reason) {
        return reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
    }

    /**
     * Resolve the tenant-local identity id for a synthetic subject. The V17
     * history trigger derives the actor from this row, not from the request body.
     */
    private static String identityId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT id::text FROM public.user_identity "
                             + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
            query.setString(1, org);
            query.setString(2, subject);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * Advance a fixture row from version 1 to 2 without voiding it. Triggers are
     * disabled only for this admin-side fixture write so the active/version-2
     * state can reach the service's stale-version guard; every HTTP assertion
     * runs against the unchanged RLS-protected schema.
     */
    private static void bumpVersion(String id) throws Exception {
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try {
                try (PreparedStatement update = root.prepareStatement(
                        "UPDATE public.document_association SET version = 2 "
                                + "WHERE id = CAST(? AS uuid)")) {
                    update.setString(1, id);
                    assertEquals(1, update.executeUpdate());
                }
            } finally {
                statement.execute("SET session_replication_role = origin");
            }
            try (PreparedStatement readBack = root.prepareStatement(
                    "SELECT version, status FROM public.document_association "
                            + "WHERE id = CAST(? AS uuid)")) {
                readBack.setString(1, id);
                try (ResultSet rows = readBack.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt("version"));
                    assertEquals("active", rows.getString("status"));
                }
            }
        }
    }

    /** Assert a refusal left the association, its single link event and its bytes untouched. */
    private void assertUnchanged(String id, int expectedVersion) throws Exception {
        JsonNode detail = json(get("/api/v1/documents/associations/" + id, CLINICIAN_A, ORG_A));
        JsonNode association = detail.get("association");
        assertEquals("active", association.get("status").asText());
        assertEquals(expectedVersion, association.get("version").asInt());
        assertTrue(association.get("voidReason").isNull());
        assertEquals(1, detail.get("history").size());
        assertEquals("link", detail.get("history").get(0).get("action").asText());
        assertArrayEquals(ORIGINAL, content(id, CLINICIAN_A, ORG_A).getBody());
    }

    @Test
    void voidingCapturesReasonAndPreservesOriginalAndHistory() throws Exception {
        String id = link(registerChart());
        JsonNode original = json(get("/api/v1/documents/associations/" + id,
                CLINICIAN_A, ORG_A)).get("association");
        String checksum = original.get("sha256").asText();
        long byteSize = original.get("byteSize").asLong();
        String createdBy = original.get("createdBy").asText();
        String spoofedActor = identityId(ORG_B, CLINICIAN_B);
        assertTrue(spoofedActor != null && !spoofedActor.isBlank());
        String body = "{\"reason\":\"" + REASON + "\",\"actorId\":\"" + spoofedActor
                + "\",\"organizationId\":\"" + ORG_B + "\"}";

        ResponseEntity<String> response = voidAssociation(id, body, CLINICIAN_A, ORG_A, "W/\"1\"");

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode voided = json(response);
        assertEquals("voided", voided.get("status").asText());
        assertEquals(REASON, voided.get("voidReason").asText());
        assertEquals(2, voided.get("version").asInt());
        assertEquals(checksum, voided.get("sha256").asText());
        assertEquals(byteSize, voided.get("byteSize").asLong());
        assertEquals(createdBy, voided.get("createdBy").asText());

        JsonNode history = json(get("/api/v1/documents/associations/" + id,
                CLINICIAN_A, ORG_A)).get("history");
        assertEquals(2, history.size());
        assertEquals("link", history.get(0).get("action").asText());
        assertEquals("void", history.get(1).get("action").asText());
        assertEquals(REASON, history.get(1).get("reason").asText());
        assertEquals(identityId(ORG_A, CLINICIAN_A), history.get(1).get("actor").asText());
        assertNotEquals(spoofedActor, history.get(1).get("actor").asText());
        assertArrayEquals(ORIGINAL, content(id, CLINICIAN_A, ORG_A).getBody());
    }

    @Test
    void invalidAssociationIdVoidIsRefused() throws Exception {
        ResponseEntity<String> refused = voidAssociation("not-a-uuid", reasonBody(REASON),
                CLINICIAN_A, ORG_A, "W/\"1\"");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_ID", json(refused).get("code").asText());
    }

    @Test
    void receptionCannotVoidAnAssociation() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> refused = voidAssociation(id, reasonBody(REASON),
                RECEPTION_A, ORG_A, "W/\"1\"");

        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        assertUnchanged(id, 1);
    }

    @Test
    void peerTenantCannotVoidAnotherTenantsAssociation() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> refused = voidAssociation(id, reasonBody(REASON),
                CLINICIAN_B, ORG_B, "W/\"1\"");

        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        assertUnchanged(id, 1);
    }

    @Test
    void staleVersionVoidIsRefused() throws Exception {
        String id = link(registerChart());
        bumpVersion(id);

        ResponseEntity<String> refused = voidAssociation(id, reasonBody(REASON),
                CLINICIAN_A, ORG_A, "W/\"1\"");

        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        assertUnchanged(id, 2);
    }

    @Test
    void blankOrMissingReasonIsRefusedBeforeAnyWrite() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> blank = voidAssociation(id, reasonBody("   "),
                CLINICIAN_A, ORG_A, "W/\"1\"");
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertEquals("REASON_REQUIRED", json(blank).get("code").asText());
        assertUnchanged(id, 1);

        ResponseEntity<String> missing = voidAssociation(id, reasonBody(null),
                CLINICIAN_A, ORG_A, "W/\"1\"");
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());
        assertEquals("REASON_REQUIRED", json(missing).get("code").asText());
        assertUnchanged(id, 1);
    }

    @Test
    void voidingAnAlreadyVoidedAssociationIsRefused() throws Exception {
        String id = link(registerChart());
        ResponseEntity<String> first = voidAssociation(id, reasonBody(REASON),
                CLINICIAN_A, ORG_A, "W/\"1\"");
        assertEquals(200, first.getStatusCode().value(), first.getBody());

        ResponseEntity<String> refused = voidAssociation(id, reasonBody("second reason"),
                CLINICIAN_A, ORG_A, "W/\"2\"");

        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        JsonNode detail = json(get("/api/v1/documents/associations/" + id,
                CLINICIAN_A, ORG_A));
        assertEquals("voided", detail.get("association").get("status").asText());
        assertEquals(2, detail.get("association").get("version").asInt());
        assertEquals(REASON, detail.get("association").get("voidReason").asText());
        assertEquals(2, detail.get("history").size());
        assertEquals(REASON, detail.get("history").get(1).get("reason").asText());
        assertArrayEquals(ORIGINAL, content(id, CLINICIAN_A, ORG_A).getBody());
    }
}
