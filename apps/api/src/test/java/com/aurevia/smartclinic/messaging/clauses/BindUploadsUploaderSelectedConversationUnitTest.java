package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-03 clause 3 proof on real PostgreSQL: an accepted upload carries the
 * uploader, selected conversation and receiving unit from the server-side
 * verified connection grant, and a wrong role, a stale grant, an unknown or
 * foreign-tenant connection, or a client attempt to rebind the file cannot
 * create such a row. Fixtures are synthetic and the peer tenant shares the
 * same local connection, unit and patient identifiers.
 *
 * <p>The service's explicit wrong-unit branch is deliberately not exercised
 * here: {@link com.aurevia.smartclinic.messaging.attachments.AttachmentController}
 * derives the actor's patient identifier from the grant itself, so that branch
 * is unreachable through the public HTTP entry point. It is covered at unit
 * level by {@code AttachmentAcceptanceTest}; this class proves the clause as
 * it is observable and enforceable through HTTP and the database.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_bind_upload_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class BindUploadsUploaderSelectedConversationUnitTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_bind_upload_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String BOOTSTRAP_A = "bootstrap-1111";
    private static final String BOOTSTRAP_B = "bootstrap-2222";
    private static final String UPLOADER = "uploader-1111";
    private static final String AUDITOR = "auditor-1111";
    private static final String CONNECTION = "conv-bound";
    private static final String INACTIVE_CONNECTION = "conv-inactive";
    private static final String FOREIGN_CONNECTION = "conv-b-only";
    private static final String UNIT = "unit-shared";
    private static final String PATIENT = "patient-shared";
    private static final byte[] PDF = "%PDF-1.4 synthetic binding proof".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Start the HTTP client against the randomly assigned application port. */
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
            seedTenant(statement, ORG_A, BOOTSTRAP_A);
            seedTenant(statement, ORG_B, BOOTSTRAP_B);
            seedMember(statement, ORG_A, BOOTSTRAP_A, UPLOADER, "patient");
            seedMember(statement, ORG_A, BOOTSTRAP_A, AUDITOR, "privacy_auditor");
            seedMember(statement, ORG_B, BOOTSTRAP_B, UPLOADER, "patient");
            // The same local identifiers in both tenants prove scoping; the
            // foreign-only connection is visible only to the peer tenant.
            seedGrant(statement, ORG_A, BOOTSTRAP_A, CONNECTION, true);
            seedGrant(statement, ORG_A, BOOTSTRAP_A, INACTIVE_CONNECTION, false);
            seedGrant(statement, ORG_B, BOOTSTRAP_B, CONNECTION, true);
            seedGrant(statement, ORG_B, BOOTSTRAP_B, FOREIGN_CONNECTION, true);
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

    private static void seedTenant(Statement statement, String org, String bootstrap) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
    }

    private static void seedMember(Statement statement, String org, String bootstrap,
                                   String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedGrant(Statement statement, String org, String bootstrap,
                                  String connectionId, boolean active) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.attachment_connection_grant "
                + "(organization_id, connection_id, unit_id, patient_id, active) "
                + "VALUES ('" + org + "', '" + connectionId + "', '" + UNIT + "', '" + PATIENT
                + "', " + active + ")");
    }

    private ResponseEntity<String> upload(String connectionId, String subject, String org) {
        return upload(connectionId, subject, org, null);
    }

    /** Send a byte-valid PDF and the connection selector through the normal multipart entry point. */
    private ResponseEntity<String> upload(String connectionId, String subject, String org, String spoofedUnit) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("connectionId", connectionId);
        parts.part("file", new ByteArrayResource(PDF))
                .filename("consent.pdf")
                .contentType(MediaType.APPLICATION_PDF);
        if (spoofedUnit != null) {
            // Plain form fields, not file parts: the controller has no such
            // parameters, so their only possible effect would be an unsafe rebind.
            parts.part("unitId", spoofedUnit);
            parts.part("patientId", "spoofed-patient");
        }
        return rest.post().uri("/api/v1/patient/files/upload")
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts.build())
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static void assertRefused(ResponseEntity<String> response, int status, String code) throws Exception {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        assertEquals(code, json(response).get("code").asText());
    }

    private static void bindTenant(Connection connection, String org) throws SQLException {
        try (PreparedStatement bind = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
            bind.setString(1, org);
            bind.execute();
        }
    }

    private static long countAttachments(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bindTenant(connection, org);
            try (PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM public.attachment");
                 ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static String[] bindingOf(String org, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bindTenant(connection, org);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT uploader_subject, connection_id, unit_id, patient_id "
                            + "FROM public.attachment WHERE id = CAST(? AS uuid)")) {
                query.setString(1, id);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return new String[] { result.getString(1), result.getString(2),
                            result.getString(3), result.getString(4) };
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void uploadBindsUploaderConversationAndUnitFromServerGrant() throws Exception {
        long before = countAttachments(ORG_A);
        ResponseEntity<String> response = upload(CONNECTION, UPLOADER, ORG_A);

        assertEquals(201, response.getStatusCode().value(), response.getBody());
        JsonNode body = json(response);
        assertEquals("quarantined", body.get("state").asText());
        assertEquals(UPLOADER, body.get("uploaderSubject").asText());
        assertEquals(CONNECTION, body.get("connectionId").asText());
        assertEquals(UNIT, body.get("unitId").asText());
        assertEquals(PATIENT, body.get("patientId").asText());
        String id = body.get("id").asText();
        assertNotNull(id);
        assertArrayEquals(new String[] { UPLOADER, CONNECTION, UNIT, PATIENT }, bindingOf(ORG_A, id));
        assertEquals(before + 1, countAttachments(ORG_A));
    }

    @Test
    void uploadIgnoresClientSuppliedUnitAndPatientBinding() throws Exception {
        long before = countAttachments(ORG_A);
        ResponseEntity<String> response = upload(CONNECTION, UPLOADER, ORG_A, "unit-spoofed");

        assertEquals(201, response.getStatusCode().value(), response.getBody());
        JsonNode body = json(response);
        // The client-supplied unit and patient are ignored: only the verified
        // grant may bind a draft, so the response and the stored row agree.
        assertEquals(UNIT, body.get("unitId").asText());
        assertEquals(PATIENT, body.get("patientId").asText());
        assertEquals(UPLOADER, body.get("uploaderSubject").asText());
        assertArrayEquals(new String[] { UPLOADER, CONNECTION, UNIT, PATIENT },
                bindingOf(ORG_A, body.get("id").asText()));
        assertEquals(before + 1, countAttachments(ORG_A));
    }

    @Test
    void uploadRefusesRoleWithoutUploadPermission() throws Exception {
        long before = countAttachments(ORG_A);
        ResponseEntity<String> response = upload(CONNECTION, AUDITOR, ORG_A);

        assertRefused(response, 403, "FORBIDDEN");
        assertEquals(before, countAttachments(ORG_A));
    }

    @Test
    void uploadRefusesInactiveConnection() throws Exception {
        long before = countAttachments(ORG_A);
        ResponseEntity<String> response = upload(INACTIVE_CONNECTION, UPLOADER, ORG_A);

        assertRefused(response, 403, "INACTIVE_CONNECTION");
        assertEquals(before, countAttachments(ORG_A));
    }

    @Test
    void uploadRefusesUnknownConnection() throws Exception {
        long before = countAttachments(ORG_A);
        ResponseEntity<String> response = upload("conv-missing", UPLOADER, ORG_A);

        assertRefused(response, 404, "NOT_FOUND");
        assertEquals(before, countAttachments(ORG_A));
    }

    @Test
    void uploadRefusesGrantOwnedByAnotherTenant() throws Exception {
        long beforeA = countAttachments(ORG_A);
        long beforeB = countAttachments(ORG_B);
        ResponseEntity<String> response = upload(FOREIGN_CONNECTION, UPLOADER, ORG_A);

        assertRefused(response, 404, "NOT_FOUND");
        assertEquals(beforeA, countAttachments(ORG_A));
        assertEquals(beforeB, countAttachments(ORG_B));
    }

    @Test
    void boundAttachmentCannotBeReboundByDirectSql() throws Exception {
        ResponseEntity<String> created = upload(CONNECTION, UPLOADER, ORG_A);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = json(created).get("id").asText();

        SQLException failure = assertThrows(SQLException.class, () -> {
            try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
                connection.setAutoCommit(false);
                bindTenant(connection, ORG_A);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE public.attachment SET unit_id = 'unit-rebound' WHERE id = CAST(? AS uuid)")) {
                    update.setString(1, id);
                    update.executeUpdate();
                } finally {
                    connection.rollback();
                }
            }
        });
        assertEquals("44000", failure.getSQLState());
        assertArrayEquals(new String[] { UPLOADER, CONNECTION, UNIT, PATIENT }, bindingOf(ORG_A, id));
    }
}
