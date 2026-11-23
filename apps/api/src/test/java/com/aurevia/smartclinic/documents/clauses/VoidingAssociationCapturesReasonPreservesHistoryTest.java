package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * CLN-07 clause 4 acceptance on real PostgreSQL and the real HTTP API: a
 * clinician's reasoned void records the reason, advances the version, appends a
 * {@code void} event to the append-only history and leaves the retained original
 * byte-for-byte intact, while a wrong role, a peer tenant, a stale version, a
 * second void, an absent or blank reason and an absent precondition are each
 * refused with no change to the association. The actor is derived by the V17
 * trigger from the authenticated subject, never from a request field. All
 * fixtures are synthetic and the peer tenant shares the same local codes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_void_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class VoidingAssociationCapturesReasonPreservesHistoryTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_doc_void_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

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
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /**
     * Seed one tenant-local synthetic subject with a role assignment. The
     * organization row is shared across subjects of the same tenant.
     */
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

    /**
     * Resolve a synthetic subject's tenant-local identity id under forced RLS;
     * this is the value the V17 trigger writes into a history event's actor.
     */
    private static String identityId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity "
                            + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
                query.setString(1, org);
                query.setString(2, subject);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Issue a synthetic POST, deliberately omitting the If-Match header when it
     * is null so the precondition-missing path is reachable through HTTP.
     */
    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                        String ifMatch) {
        RestClient.RequestBodySpec spec = rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        if (ifMatch != null) {
            spec.header("If-Match", ifMatch);
        }
        return spec.retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Read an association and its history as an authorized clinician. */
    private ResponseEntity<String> view(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Download the retained bytes of an association through the HTTP entry point. */
    private ResponseEntity<byte[]> content(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(byte[].class);
    }

    private String registerChart() throws Exception {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-"
                + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = post("/api/v1/patients", body, "reception-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return json(response).get("id").asText();
    }

    /** Link a synthetic PDF, which creates the association and its link event. */
    private String link(String chartId) throws Exception {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chartId
                + "\",\"fileName\":\"scan.pdf\",\"mediaType\":\"application/pdf\","
                + "\"contentBase64\":\"" + ENCODED + "\",\"rotationDegrees\":90}";
        ResponseEntity<String> response = post("/api/v1/documents/associations", body,
                "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return json(response).get("id").asText();
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody() == null ? "{}" : response.getBody());
    }

    private static String voidBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static void assertDenied(ResponseEntity<String> response, int status, String code)
            throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        assertEquals(code, json(response).get("code").asText());
    }

    /**
     * Assert the exact state of an association that no void was allowed to
     * change: still active at version 1 with only its original link event and
     * intact original bytes.
     */
    private void assertUntouched(String id) throws Exception {
        JsonNode view = json(view(id, "clinician-a", ORG_A));
        JsonNode association = view.get("association");
        assertEquals("active", association.get("status").asText());
        assertEquals(1, association.get("version").asInt());
        assertTrue(association.get("voidReason") == null || association.get("voidReason").isNull());
        JsonNode history = view.get("history");
        assertEquals(1, history.size());
        assertEquals("link", history.get(0).get("action").asText());
        assertArrayEquals(ORIGINAL, content(id, "clinician-a", ORG_A).getBody());
    }

    /** A clinician's reasoned void keeps history and original bytes. */
    @Test
    void voidCapturesReasonAndPreservesHistory() throws Exception {
        String id = link(registerChart());
        String reason = "filed under the wrong chart; corrected scan attached";

        ResponseEntity<String> voided = post("/api/v1/documents/associations/" + id + "/void",
                voidBody(reason), "clinician-a", ORG_A, "\"1\"");

        assertEquals(HttpStatus.OK, voided.getStatusCode(), voided.getBody());
        JsonNode result = json(voided);
        assertEquals(id, result.get("id").asText());
        assertEquals("voided", result.get("status").asText());
        assertEquals(reason, result.get("voidReason").asText());
        assertEquals(2, result.get("version").asInt());

        JsonNode view = json(view(id, "clinician-a", ORG_A));
        JsonNode association = view.get("association");
        assertEquals("voided", association.get("status").asText());
        assertEquals(reason, association.get("voidReason").asText());
        assertEquals(2, association.get("version").asInt());
        JsonNode history = view.get("history");
        assertEquals(2, history.size());
        assertEquals("link", history.get(0).get("action").asText());
        assertEquals("void", history.get(1).get("action").asText());
        assertEquals(reason, history.get(1).get("reason").asText());
        assertEquals(identityId(ORG_A, "clinician-a"), history.get(1).get("actor").asText());

        ResponseEntity<byte[]> original = content(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, original.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, original.getHeaders().getContentType());
        assertArrayEquals(ORIGINAL, original.getBody());
    }

    /** Reception may link and read but may not void. */
    @Test
    void receptionCannotVoidAnAssociation() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> denied = post("/api/v1/documents/associations/" + id + "/void",
                voidBody("reception attempted a void"), "reception-a", ORG_A, "\"1\"");

        assertDenied(denied, 403, "FORBIDDEN");
        assertUntouched(id);
    }

    /** A peer tenant with the clinician role is concealed as not found. */
    @Test
    void peerTenantCannotVoidAnotherTenantsAssociation() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> denied = post("/api/v1/documents/associations/" + id + "/void",
                voidBody("peer tenant attempted a void"), "clinician-b", ORG_B, "\"1\"");

        assertDenied(denied, 404, "NOT_FOUND");
        assertUntouched(id);
        assertEquals(HttpStatus.NOT_FOUND, view(id, "clinician-b", ORG_B).getStatusCode());
    }

    /** A version other than the one read is refused without appending history. */
    @Test
    void staleVersionCannotVoidAnAssociation() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> refused = post("/api/v1/documents/associations/" + id + "/void",
                voidBody("stale version attempt"), "clinician-a", ORG_A, "\"2\"");

        assertDenied(refused, 412, "STALE_VERSION");
        assertUntouched(id);
    }

    /** A second void cannot overwrite the first reason or append another event. */
    @Test
    void alreadyVoidedCannotBeVoidedAgain() throws Exception {
        String id = link(registerChart());
        String firstReason = "the reason that must be retained";
        ResponseEntity<String> first = post("/api/v1/documents/associations/" + id + "/void",
                voidBody(firstReason), "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());

        ResponseEntity<String> second = post("/api/v1/documents/associations/" + id + "/void",
                voidBody("a later reason that must not be stored"), "clinician-a", ORG_A, "\"2\"");

        assertDenied(second, 409, "INVALID_TRANSITION");
        JsonNode view = json(view(id, "clinician-a", ORG_A));
        assertEquals("voided", view.get("association").get("status").asText());
        assertEquals(2, view.get("association").get("version").asInt());
        assertEquals(firstReason, view.get("association").get("voidReason").asText());
        JsonNode history = view.get("history");
        assertEquals(2, history.size());
        assertEquals("void", history.get(1).get("action").asText());
        assertEquals(firstReason, history.get(1).get("reason").asText());
        assertArrayEquals(ORIGINAL, content(id, "clinician-a", ORG_A).getBody());
    }

    /** An absent or blank reason is refused before any state change. */
    @Test
    void missingAndBlankReasonAreRefused() throws Exception {
        String missingId = link(registerChart());
        ResponseEntity<String> missing = post("/api/v1/documents/associations/" + missingId + "/void",
                "{}", "clinician-a", ORG_A, "\"1\"");
        assertDenied(missing, 422, "REASON_REQUIRED");
        assertUntouched(missingId);

        String blankId = link(registerChart());
        ResponseEntity<String> blank = post("/api/v1/documents/associations/" + blankId + "/void",
                "{\"reason\":\"   \"}", "clinician-a", ORG_A, "\"1\"");
        assertDenied(blank, 422, "REASON_REQUIRED");
        assertUntouched(blankId);
    }

    /** An absent If-Match precondition is refused before any state change. */
    @Test
    void missingIfMatchIsRefused() throws Exception {
        String id = link(registerChart());

        ResponseEntity<String> refused = post("/api/v1/documents/associations/" + id + "/void",
                voidBody("valid reason but no precondition"), "clinician-a", ORG_A, null);

        assertDenied(refused, 428, "PRECONDITION_REQUIRED");
        assertUntouched(id);
    }
}
