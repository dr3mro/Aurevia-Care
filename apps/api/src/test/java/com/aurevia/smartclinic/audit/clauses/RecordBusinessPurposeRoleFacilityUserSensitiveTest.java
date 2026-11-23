package com.aurevia.smartclinic.audit.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * IAM-06 clause 1 acceptance: a sensitive command must persist the business purpose,
 * the acting role, the facility scope and the authenticated user, both in the command's
 * own row and in the append-only audit chain. The command reaches the real HTTP entry
 * point ({@code POST /api/v1/access/emergency}), and the rows are read back from a
 * separate real-PostgreSQL connection. A wrong-role actor must be refused and that
 * refusal must still be audited against the same four facts.
 *
 * <p>Only synthetic subjects, facilities and reasons are used. The audit actor is a
 * database-derived {@code user_identity.id}, never a value supplied in the request
 * body; the test computes the expected identifier from the seeded identity table.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_sensitive_action_audit_it")
@ActiveProfiles("synthetic-auth")
class RecordBusinessPurposeRoleFacilityUserSensitiveTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_sensitive_action_audit_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String GRANT_CORRELATION = "iam06-purpose-grant";
    private static final String DENIAL_CORRELATION = "iam06-purpose-denied";

    private RestClient rest;

    /** Start the HTTP client for the random test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Apply every migration to a fresh synthetic database and seed the two actors and the approved site. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_sensitive_action_audit_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_sensitive_action_audit_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_A, "reception-a", "reception");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Alpha Main')");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                    + "VALUES ('" + ORG_A + "', 'access.emergency.enabled', 1, 'true')");
            statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                    + "SELECT '" + ORG_A + "', id, 'synthetic approval' FROM public.config_entry "
                    + "WHERE key = 'access.emergency.enabled' AND version = 1");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_sensitive_action_audit_it WITH (FORCE)");
        }
    }

    /**
     * Seed one synthetic identity, its active membership and its single role grant.
     *
     * @param statement  database statement for the migrated synthetic database
     * @param organization owning organization identifier
     * @param subject    authenticated subject presented through the synthetic header
     * @param role       single role key under test
     * @throws Exception when a seed statement fails
     */
    private static void seed(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + organization + "', 'Org " + subject + "' WHERE NOT EXISTS "
                + "(SELECT 1 FROM public.organization WHERE organization_id = '" + organization + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + organization + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + organization + "'");
    }

    /**
     * Send a grant command through the real HTTP entry point with a deterministic correlation id.
     *
     * @param subject      synthetic authenticated subject
     * @param correlation  explicit correlation id so the audit rows are selectable
     * @return the HTTP response, including non-2xx statuses
     */
    private ResponseEntity<String> grant(String subject, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", ORG_A);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(HttpMethod.POST).uri("/api/v1/access/emergency")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body("{\"facilityCode\":\"MAIN\",\"reason\":\"patient in distress\","
                        + "\"durationMinutes\":10}")
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Read a scalar JSON field from the grant projection.
     *
     * @param json body returned by the grant endpoint
     * @param key  field name to extract
     * @return the field value, or an empty string when absent
     */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        int end = json.indexOf('"', start + 1);
        return json.substring(start + 1, end);
    }

    /**
     * Resolve the database-issued identity of a synthetic subject in the active organization.
     *
     * @param subject synthetic subject seeded in ORG_A
     * @return the {@code user_identity.id} that the audit insert trigger derives from {@code app.actor}
     * @throws Exception when the identity lookup fails
     */
    private static String userId(String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next(), "seeded identity required for " + subject);
                return result.getString(1);
            }
        }
    }

    /**
     * A qualified clinician's grant records purpose, role, facility and the acting user
     * in both the grant row and the SUCCESS audit row of the append-only chain.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void grantRecordsPurposeRoleFacilityAndUser() throws Exception {
        ResponseEntity<String> created = grant("clinician-a", GRANT_CORRELATION);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String grantId = field(created.getBody(), "id");
        String expectedActor = userId("clinician-a");

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT subject, facility_code, purpose, role_key FROM public.emergency_access_grant "
                    + "WHERE id::text = '" + grantId + "'")) {
                assertTrue(result.next(), "one grant row must survive the HTTP command");
                assertEquals("clinician-a", result.getString("subject"));
                assertEquals("MAIN", result.getString("facility_code"));
                assertEquals("EMERGENCY_ACCESS", result.getString("purpose"));
                assertEquals("clinician", result.getString("role_key"));
                assertFalse(result.next(), "the grant command must create exactly one grant");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT purpose, scope, organization_id::text AS organization_id, "
                    + "correlation_id, outcome, actor_id::text AS actor_id "
                    + "FROM public.audit_event WHERE correlation_id = '" + GRANT_CORRELATION + "' "
                    + "AND action = 'EMERGENCY_GRANT' AND resource_id = '" + grantId + "'")) {
                assertTrue(result.next(), "the grant decision must be audited");
                assertEquals("EMERGENCY_ACCESS", result.getString("purpose"));
                assertEquals("clinician:MAIN", result.getString("scope"));
                assertEquals(ORG_A, result.getString("organization_id"));
                assertEquals(GRANT_CORRELATION, result.getString("correlation_id"));
                assertEquals("SUCCESS", result.getString("outcome"));
                assertEquals(expectedActor, result.getString("actor_id"),
                        "the audit actor must be the database-derived user id, not the subject string");
                assertFalse(result.next(), "one decision audit row per grant command");
            }
        }
    }

    /**
     * An active member without the clinician role is refused, leaves no grant row and
     * still produces a DENIED audit row carrying purpose, scope and the denied actor.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void wrongRoleGrantIsDeniedAndAudited() throws Exception {
        ResponseEntity<String> denied = grant("reception-a", DENIAL_CORRELATION);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        String expectedActor = userId("reception-a");

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'reception-a'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "a refused actor must not receive a grant");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT action, purpose, scope, resource_type, resource_id, "
                    + "organization_id::text AS organization_id, outcome, actor_id::text AS actor_id "
                    + "FROM public.audit_event WHERE correlation_id = '" + DENIAL_CORRELATION + "'")) {
                assertTrue(result.next(), "a refusal must be audited before the error is reported");
                assertEquals("EMERGENCY_GRANT", result.getString("action"));
                assertEquals("EMERGENCY_ACCESS", result.getString("purpose"));
                assertEquals("clinician:MAIN", result.getString("scope"));
                assertEquals("EMERGENCY_ACCESS_GRANT", result.getString("resource_type"));
                assertEquals("MAIN", result.getString("resource_id"));
                assertEquals(ORG_A, result.getString("organization_id"));
                assertEquals("DENIED", result.getString("outcome"));
                assertEquals(expectedActor, result.getString("actor_id"));
                assertFalse(result.next(), "exactly one audited denial per refused command");
            }
        }
    }
}
