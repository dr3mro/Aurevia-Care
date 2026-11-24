package com.aurevia.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

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

/**
 * CLN-07 clause 2 (API evidence): retain original bytes and checksum.
 *
 * <p>The proof runs through the real document controller against a fresh
 * PostgreSQL database built from the production Flyway migrations. It shows that
 * the exact uploaded bytes are retained, that the stored SHA-256 is derived by
 * the database from those bytes (the link command supplies no digest), that a
 * storage-layer alteration is refused with a 409 and an audited tamper outcome
 * instead of serving the changed bytes, and that neither another tenant nor a
 * role without document-read permission can obtain the original. All fixtures
 * are synthetic; the test creates and drops its own database.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_doc_retain_bytes_it")
@ActiveProfiles("synthetic-auth")
class RetainOriginalBytesChecksumTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DATABASE = "orevia_doc_retain_bytes_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TAMPERED = "%PDF-1.4 TAMPERED bytes!!".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);

    /**
     * The retained bytes together with the digest the database derived from them.
     *
     * @param content retained original bytes
     * @param sha256  hexadecimal digest stored beside those bytes
     */
    private record Retained(byte[] content, String sha256) {
    }

    RestClient rest;

    /**
     * Bind the HTTP client to the randomly assigned server port.
     *
     * @param port port chosen by Spring Boot for this test context
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships.
     *
     * @throws Exception when the database cannot be created, migrated or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "tenant-admin-a", "tenant_admin");
            seed(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Seed one synthetic organization, user, membership and role assignment.
     *
     * @param statement open statement on the fixture database
     * @param org       organization identifier
     * @param subject   synthetic authenticated subject
     * @param role      role key granted to the subject
     * @throws Exception when a fixture row cannot be inserted
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
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
    }

    /**
     * Register a synthetic chart subject through the patient entry point.
     *
     * @return opaque chart identifier
     */
    private String registerChart() {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"MRN-"
                + UUID.randomUUID() + "\",\"issuer\":\"CLINIC\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = rest.post().uri("/api/v1/patients")
                .header("X-Synthetic-Subject", "reception-a")
                .header("X-Active-Organization", ORG_A)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /**
     * Attach the synthetic original through the document entry point. No digest is
     * sent: the migration trigger must derive it from the retained bytes.
     *
     * @param chartId opaque identifier of the chart subject
     * @return opaque association identifier
     */
    private String link(String chartId) {
        String body = "{\"subjectType\":\"chart\",\"subjectId\":\"" + chartId
                + "\",\"fileName\":\"scan.pdf\",\"mediaType\":\"application/pdf\","
                + "\"contentBase64\":\"" + ENCODED + "\",\"rotationDegrees\":90}";
        ResponseEntity<String> response = rest.post().uri("/api/v1/documents/associations")
                .header("X-Synthetic-Subject", "clinician-a")
                .header("X-Active-Organization", ORG_A)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().onStatus(status -> true, (req, res) -> { })
                .toEntity(String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /**
     * Request a checksum-verified original download as a given synthetic actor.
     *
     * @param id      opaque association identifier
     * @param subject authenticated subject making the request
     * @param org     active organization making the request
     * @return the HTTP response including any error body
     */
    private ResponseEntity<byte[]> download(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
    }

    /**
     * Read one string field from a small JSON response.
     *
     * @param json serialized response body
     * @param key  field name to read
     * @return the field value
     */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle) + needle.length();
        while (start < json.length() && (json.charAt(start) == '"' || json.charAt(start) == ' ')) {
            start++;
        }
        int end = json.indexOf('"', start);
        if (end < 0) {
            end = json.indexOf(',', start);
        }
        return json.substring(start, end);
    }

    /**
     * Read the bytes actually held in storage and the digest stored beside them.
     * The migration owner is subject to FORCE RLS, so the tenant must be bound.
     *
     * @param id opaque association identifier
     * @return retained bytes and the stored digest
     * @throws Exception when the fixture row cannot be read
     */
    private static Retained retainedBytesAndDigest(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT content, sha256 FROM public.document_association "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next(), "the linked original must be retained in storage");
                return new Retained(result.getBytes("content"), result.getString("sha256"));
            }
        }
    }

    /**
     * Simulate storage-layer corruption as a superuser with triggers disabled, so
     * the database-derived digest is deliberately left describing the old bytes.
     *
     * @param id opaque association identifier
     * @throws Exception when the stored bytes cannot be altered
     */
    private static void corrupt(String id) throws Exception {
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try (PreparedStatement update = root.prepareStatement(
                    "UPDATE public.document_association SET content = ? WHERE id = CAST(? AS uuid)")) {
                update.setBytes(1, TAMPERED);
                update.setString(2, id);
                update.executeUpdate();
            }
            statement.execute("SET session_replication_role = origin");
        }
    }

    /**
     * Count durable tamper audits for one association. A superuser reads past RLS.
     *
     * @param id opaque association identifier
     * @return number of TAMPERED content audits for that association
     * @throws Exception when the audit rows cannot be counted
     */
    private static int tamperedAudits(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             PreparedStatement query = connection.prepareStatement(
                     "SELECT count(*) FROM public.audit_event WHERE outcome = 'TAMPERED' "
                             + "AND action = 'DOCUMENT_CONTENT' AND resource_id = ?")) {
            query.setString(1, id);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /**
     * Read an HTTP response body as text for assertion messages and leak checks.
     *
     * @param response response returned by the document download route
     * @return the body as text, or an empty string when there is no body
     */
    private static String bodyOf(ResponseEntity<byte[]> response) {
        return new String(response.getBody() == null ? new byte[0] : response.getBody(),
                StandardCharsets.UTF_8);
    }

    /**
     * Assert that neither the retained original nor the altered bytes appear in a
     * refusal body, so a denial cannot leak document content through its error.
     *
     * @param body serialized HTTP response body
     */
    private static void assertNoDocumentBytes(String body) {
        assertFalse(body.contains("TAMPERED bytes"), "corrupted bytes must never be served as the original");
        assertFalse(body.contains("synthetic original"), "the original must not be served in a refusal");
    }

    /**
     * Confirm a denied actor is an active member carrying the role under test, so a
     * 403 can only come from the document action matrix and not a missing fixture.
     *
     * @param subject synthetic authenticated subject
     * @param role    role key that must be in force
     * @throws Exception when the membership or roles cannot be read
     */
    private static void assertActiveRole(String subject, String role) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT public.has_active_membership('" + subject + "')")) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1), "the denied actor must be an active member");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT role_key FROM public.active_roles('" + subject + "')")) {
                boolean found = false;
                while (result.next()) {
                    found = found || role.equals(result.getString(1));
                }
                assertTrue(found, "the denied actor must carry the role under test");
            }
        }
    }

    /**
     * Compute the hexadecimal SHA-256 of the supplied bytes.
     *
     * @param bytes bytes to digest
     * @return lower-case hexadecimal digest
     * @throws Exception when the digest algorithm is unavailable
     */
    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void linkThenVerifiedDownloadReturnsTheExactRetainedOriginalBytes() throws Exception {
        String id = link(registerChart());

        ResponseEntity<byte[]> response = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, response.getHeaders().getContentType());
        assertArrayEquals(ORIGINAL, response.getBody(), "the verified download must return the exact original");

        Retained retained = retainedBytesAndDigest(id);
        assertArrayEquals(ORIGINAL, retained.content(), "the original bytes must be retained verbatim");
        assertEquals(sha256Hex(ORIGINAL), retained.sha256(),
                "the stored checksum must be derived from the retained bytes, not supplied by the caller");
    }

    @Test
    void tamperedRetainedBytesAreRefusedWithChecksumMismatchAndAudited() throws Exception {
        String id = link(registerChart());
        assertEquals(0, tamperedAudits(id));

        corrupt(id);
        Retained retained = retainedBytesAndDigest(id);
        assertArrayEquals(TAMPERED, retained.content());
        assertNotEquals(sha256Hex(TAMPERED), retained.sha256(),
                "the stored digest must no longer match the altered bytes");

        ResponseEntity<byte[]> response = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        String body = bodyOf(response);
        assertTrue(body.contains("CHECKSUM_MISMATCH"), body);
        assertNoDocumentBytes(body);
        assertEquals(1, tamperedAudits(id),
                "a tamper must append a durable TAMPERED audit, not abort the transaction");
    }

    @Test
    void anotherTenantCannotReadTheRetainedOriginal() throws Exception {
        String id = link(registerChart());

        ResponseEntity<byte[]> foreign = download(id, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode(),
                "forced RLS must conceal another tenant's association");
        assertNoDocumentBytes(bodyOf(foreign));

        ResponseEntity<byte[]> owner = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, owner.getStatusCode());
        assertArrayEquals(ORIGINAL, owner.getBody(), "concealment must not alter the retained original");
    }

    @Test
    void actorWithoutDocumentReadRoleCannotDownload() throws Exception {
        String id = link(registerChart());
        assertActiveRole("tenant-admin-a", "tenant_admin");

        ResponseEntity<byte[]> denied = download(id, "tenant-admin-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(),
                "tenant administration carries no clinical document access");
        assertNoDocumentBytes(bodyOf(denied));
    }
}
