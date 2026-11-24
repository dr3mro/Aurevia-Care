package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
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
 * CLN-07 HTTP acceptance for the verified original download (synthetic data).
 *
 * <p>Proves the complete path: link through the real controller, download the
 * verified bytes, corrupt storage, then get 409 CHECKSUM_MISMATCH with a
 * durable TAMPERED audit row — without aborting the transaction — and a 404 for
 * another tenant. This is the entry-point proof for the V75 function.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_doc_http_it")
@ActiveProfiles("synthetic-auth")
class DocumentDownloadHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_doc_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final byte[] ORIGINAL = "%PDF-1.4 synthetic original".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED = Base64.getEncoder().encodeToString(ORIGINAL);

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
            statement.execute("DROP DATABASE IF EXISTS orevia_doc_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_doc_http_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
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
            statement.execute("DROP DATABASE IF EXISTS orevia_doc_http_it WITH (FORCE)");
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

    private ResponseEntity<byte[]> download(String id, String subject, String org) {
        return rest.get().uri("/api/v1/documents/associations/" + id + "/content")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(byte[].class);
    }

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

    private static void corrupt(String id) throws Exception {
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            try (PreparedStatement update = root.prepareStatement(
                    "UPDATE public.document_association SET content = ? WHERE id = CAST(? AS uuid)")) {
                update.setBytes(1, "%PDF-1.4 TAMPERED bytes!!".getBytes(StandardCharsets.UTF_8));
                update.setString(2, id);
                update.executeUpdate();
            }
            statement.execute("SET session_replication_role = origin");
        }
    }

    private static long tamperedAudits() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT count(*) FROM public.audit_event "
                     + "WHERE outcome = 'TAMPERED' AND action = 'DOCUMENT_CONTENT'")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    @Test
    void verifiesDownloadThenDetectsTamperingAndConcealsOtherTenants() throws Exception {
        String id = link(registerChart());

        ResponseEntity<byte[]> first = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, first.getHeaders().getContentType());
        assertArrayEquals(ORIGINAL, first.getBody());

        long auditsBefore = tamperedAudits();
        corrupt(id);
        ResponseEntity<byte[]> tampered = download(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, tampered.getStatusCode());
        assertTrue(new String(tampered.getBody() == null ? new byte[0] : tampered.getBody(),
                StandardCharsets.UTF_8).contains("CHECKSUM_MISMATCH"));
        assertEquals(auditsBefore + 1, tamperedAudits(),
                "a tamper must append a durable TAMPERED audit, not abort the transaction");

        ResponseEntity<byte[]> foreign = download(id, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatusCode());
    }
}
