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
 * IAM-06 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>Every sensitive action records its business purpose, the role that performed it,
 * the facility it was scoped to and the acting user. This test proves only that
 * recording clause through the normal HTTP entry point: the positive case creates a
 * reasoned emergency grant and reads purpose, role, facility and subject back from both
 * the durable grant row and the append-only audit event. The negative cases show a
 * caller without the qualifying role refused with a denial whose audit still names the
 * actor, and the recorded context concealed from another tenant under forced RLS.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam06_c1_audit_it")
@ActiveProfiles("synthetic-auth")
class RecordBusinessPurposeRoleFacilityUserSensitiveTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam06_c1_audit_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String PURPOSE = "EMERGENCY_ACCESS";
    private static final String ROLE = "clinician";
    private static final String FACILITY = "MAIN";
    private static final String SCOPE = ROLE + ":" + FACILITY;

    RestClient rest;

    /** Build an HTTP client bound to the randomly assigned server port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate an isolated database, apply the production migrations and seed synthetic identities. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam06_c1_audit_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam06_c1_audit_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", ROLE);
            seedUser(statement, ORG_A, "clinician-scope", ROLE);
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");

            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', '" + FACILITY + "', 'Alpha Main')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', '" + FACILITY + "', 'Beta Main')");

            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'admin-a'");
            statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                    + "VALUES ('" + ORG_A + "', 'access.emergency.enabled', 1, 'true')");
            statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                    + "SELECT '" + ORG_A + "', id, 'synthetic approval' FROM public.config_entry "
                    + "WHERE key = 'access.emergency.enabled' AND version = 1");
        }
    }

    /** Drop the isolated database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam06_c1_audit_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic member with an active membership and one bounded role. */
    private static void seedUser(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    /** Build the synthetic-auth headers, including an explicit correlation id for the audit trail. */
    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Issue one HTTP request, capturing the body even for error statuses. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Open a reasoned emergency grant through the normal POST entry point. */
    private ResponseEntity<String> grant(String facility, String reason, int minutes,
                                         String actor, String organization, String correlation) {
        String body = "{\"facilityCode\":\"" + facility + "\",\"reason\":\"" + reason
                + "\",\"durationMinutes\":" + minutes + "}";
        return request(HttpMethod.POST, "/api/v1/access/emergency", body, actor, organization, correlation);
    }

    /** Read one scalar string field from a small JSON object. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /** Resolve the durable identity of a synthetic subject inside its organization. */
    private String userId(String organization, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    /** A qualified sensitive action records purpose, role, facility and user in both durable stores. */
    @Test
    void sensitiveGrantRecordsPurposeRoleFacilityAndUser() throws Exception {
        ResponseEntity<String> created = grant(FACILITY, "patient in distress", 10,
                "clinician-a", ORG_A, "iam06-c1-grant");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(id.isBlank());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT purpose, role_key, facility_code, subject FROM public.emergency_access_grant "
                            + "WHERE id::text = '" + id + "'")) {
                assertTrue(result.next());
                assertEquals(PURPOSE, result.getString("purpose"));
                assertEquals(ROLE, result.getString("role_key"));
                assertEquals(FACILITY, result.getString("facility_code"));
                assertEquals("clinician-a", result.getString("subject"));
                assertFalse(result.next());
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT purpose, scope, actor_id, outcome FROM public.audit_event "
                            + "WHERE correlation_id = 'iam06-c1-grant' AND action = 'EMERGENCY_GRANT'")) {
                assertTrue(result.next());
                assertEquals(PURPOSE, result.getString("purpose"));
                assertEquals(SCOPE, result.getString("scope"));
                assertEquals(userId(ORG_A, "clinician-a"), result.getString("actor_id"));
                assertEquals("SUCCESS", result.getString("outcome"));
                assertFalse(result.next());
            }
        }
    }

    /** A caller without the qualifying role is refused, and the denial still records the actor. */
    @Test
    void nonClinicianGrantIsRefusedAndDenialRecordsPurposeRoleFacilityAndActor() throws Exception {
        ResponseEntity<String> denied = grant(FACILITY, "not my role", 10,
                "admin-a", ORG_A, "iam06-c1-wrongrole");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'admin-a'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT purpose, scope, actor_id, outcome FROM public.audit_event "
                            + "WHERE correlation_id = 'iam06-c1-wrongrole' AND action = 'EMERGENCY_GRANT'")) {
                assertTrue(result.next());
                assertEquals(PURPOSE, result.getString("purpose"));
                assertEquals(SCOPE, result.getString("scope"));
                assertEquals(userId(ORG_A, "admin-a"), result.getString("actor_id"));
                assertEquals("DENIED", result.getString("outcome"));
                assertFalse(result.next());
            }
        }
    }

    /** The recorded purpose, role, facility and user cannot be read from another tenant. */
    @Test
    void recordedSensitiveContextIsConfinedToItsOwnTenant() throws Exception {
        ResponseEntity<String> created = grant(FACILITY, "patient in distress", 10,
                "clinician-scope", ORG_A, "iam06-c1-tenant");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event "
                            + "WHERE correlation_id = 'iam06-c1-tenant' AND action = 'EMERGENCY_GRANT'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event "
                            + "WHERE correlation_id = 'iam06-c1-tenant' AND action = 'EMERGENCY_GRANT'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'clinician-scope'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
