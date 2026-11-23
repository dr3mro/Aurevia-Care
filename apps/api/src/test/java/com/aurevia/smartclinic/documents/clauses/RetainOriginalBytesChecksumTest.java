package com.aurevia.smartclinic.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * <p>Drives the real HTTP entry point against a dedicated real PostgreSQL
 * database and proves that the exact uploaded bytes are retained verbatim with
 * a SHA-256 derived from those bytes: the positive test reads the stored row
 * back and compares content byte for byte, byte size and digest. The negative
 * tests show the rule refusing what it must refuse: bytes altered after upload
 * are never served but answered with 409 CHECKSUM_MISMATCH and a durable
 * TAMPERED audit, and the retained original of another tenant is concealed with
 * 404 under forced row-level security.</p>
 *
 * <p>Synthetic fixtures only; no patient data, secrets or credentials are
 * used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_retain_bytes_it")
@ActiveProfiles("synthetic-auth")
class RetainOriginalBytesChecksumTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_doc_retain_bytes_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TAMPERED = "%PDF-1.4 TAMPERED bytes!!".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);

    RestClient rest;

    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** The retained columns that prove original bytes and their checksum. */
    private record RetainedRow(byte[] content, long byteSize, String sha256, String recomputed) { }

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_doc_retain_bytes_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_doc_retain_bytes_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_doc_retain_bytes_it WITH (FORCE)");
        }
    }

    /** Seed an active organization member with one role for the synthetic fixtures. */
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

    /** Register a synthetic chart in ORG_A through the normal HTTP entry point. */
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

    /** Link the synthetic original PDF to a chart through the normal HTTP entry point. */
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

    /** Download retained bytes through the verified-content HTTP entry point. */
    private ResponseEntity<byte[]> download(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
    }

    /** Read a scalar field out of the simple flat JSON responses used by these tests. */
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

    /** Read the retained row and recompute the digest from the bytes actually stored. */
    private static RetainedRow retainedRow(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT content, byte_size, sha256, encode(sha256(content), 'hex') AS recomputed "
                            + "FROM public.document_association WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next(), "the retained association must exist");
                return new RetainedRow(result.getBytes("content"), result.getLong("byte_size"),
                        result.getString("sha256"), result.getString("recomputed"));
            }
        }
    }

    /** Alter the stored bytes as a storage-layer fault, with the guard trigger disabled. */
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

    /** Count the durable tamper audit rows for verified content reads. */
    private static long tamperedAudits() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM public.audit_event "
                     + "WHERE outcome = 'TAMPERED' AND action = 'DOCUMENT_CONTENT'")) {
            result.next();
            return result.getLong(1);
        }
    }

    /** Compute the expected SHA-256 of the original bytes independently of the database. */
    private static String expectedDigest() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ORIGINAL));
    }

    @Test
    void originalBytesAreRetainedWithTheirChecksumAndServedVerified() throws Exception {
        String id = link(registerChart());

        ResponseEntity<byte[]> served = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, served.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, served.getHeaders().getContentType());
        assertArrayEquals(ORIGINAL, served.getBody(), "the verified download serves the exact original bytes");

        RetainedRow row = retainedRow(id);
        assertArrayEquals(ORIGINAL, row.content(), "the retained bytes are the original bytes");
        assertEquals(ORIGINAL.length, row.byteSize(), "byte size is derived from the retained original");
        assertEquals(expectedDigest(), row.sha256(), "the checksum is the SHA-256 of the original bytes");
        assertEquals(row.recomputed(), row.sha256(), "a recomputed digest still matches the retained checksum");
    }

    @Test
    void tamperedRetainedBytesAreRefusedWithChecksumMismatch() throws Exception {
        String id = link(registerChart());
        long auditsBefore = tamperedAudits();
        corrupt(id);

        ResponseEntity<byte[]> response = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        String tamperedBody = new String(response.getBody() == null ? new byte[0] : response.getBody(),
                StandardCharsets.UTF_8);
        assertTrue(tamperedBody.contains("CHECKSUM_MISMATCH"));
        assertFalse(tamperedBody.contains(new String(TAMPERED, StandardCharsets.UTF_8)),
                "no altered bytes may be served to the caller");
        assertEquals(auditsBefore + 1, tamperedAudits(),
                "a tamper must append a durable TAMPERED audit, not abort the transaction");

        RetainedRow row = retainedRow(id);
        assertArrayEquals(TAMPERED, row.content(), "the altered bytes remain in storage for forensics");
        assertNotEquals(row.sha256(), row.recomputed(),
                "the retained checksum no longer matches the altered bytes");
    }

    @Test
    void retainedBytesOfAnotherTenantAreConcealed() {
        String id = link(registerChart());

        ResponseEntity<byte[]> foreign = download(id, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode(),
                "forced RLS must conceal another tenant's retained original");
    }
}
