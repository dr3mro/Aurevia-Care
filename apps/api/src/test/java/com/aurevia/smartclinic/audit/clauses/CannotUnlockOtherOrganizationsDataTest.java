package com.aurevia.smartclinic.audit.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

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
 * IAM-06 clause 3: emergency access is confined to the organization of the active
 * request. A clinician can open a reasoned grant only inside their own organization,
 * and a grant in one organization stays invisible and immutable to another one even
 * when the same subject is entitled to both tenants.
 *
 * <p>Synthetic fixtures only; real PostgreSQL with forced row-level security. The
 * HTTP entry point is the access controller; the authoritative organization id is
 * read back from the grant and review tables because the grant projection never
 * carries one.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cannot_unlock_org_it")
@ActiveProfiles("synthetic-auth")
class CannotUnlockOtherOrganizationsDataTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cannot_unlock_org_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private RestClient rest;

    /** Start the HTTP client for the random test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Apply every migration to a fresh synthetic database and seed two entitled tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cannot_unlock_org_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cannot_unlock_org_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-own", "clinician");
            // The same subject is a qualified clinician in both organizations. Any
            // difference between the two tenants must then come from the active
            // organization, not from subject filtering in latestGrant.
            seed(statement, ORG_A, "clinician-shared", "clinician");
            seed(statement, ORG_B, "clinician-shared", "clinician");
            seed(statement, ORG_A, "receptionist-role", "reception");
            seed(statement, ORG_A, "clinician-input", "clinician");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "auditor-b", "privacy_auditor");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Alpha Main')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'MAIN', 'Beta Main')");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'auditor-a'");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cannot_unlock_org_it WITH (FORCE)");
        }
    }

    /** Create an organization and an active tenant-local membership with one role. */
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
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Issue one synthetic HTTP request and return the response without throwing on 4xx. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Post one grant command with a fresh correlation id. */
    private ResponseEntity<String> grant(String subject, String organization, String facility,
                                          String reason, int duration) {
        return grant(subject, organization, facility, reason, duration, UUID.randomUUID().toString());
    }

    /** Post one grant command with a known correlation id for audit assertions. */
    private ResponseEntity<String> grant(String subject, String organization, String facility,
                                          String reason, int duration, String correlation) {
        String body = "{\"facilityCode\":\"" + facility + "\",\"reason\":\"" + reason
                + "\",\"durationMinutes\":" + duration + "}";
        return request(HttpMethod.POST, "/api/v1/access/emergency", body, subject, organization, correlation);
    }

    /** Create a successful grant and return its opaque identifier. */
    private String createGrant(String subject, String organization) {
        ResponseEntity<String> created = grant(subject, organization, "MAIN", "patient in distress", 10);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    /** Read one scalar string field from a small JSON response body. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /** Count rows visible under one active organization using the RLS-bounded migration role. */
    private static int count(String organization, String query) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(query)) {
                assertTrue(result.next(), "count query must return one row");
                return result.getInt(1);
            }
        }
    }

    /** Assert the grant's owning organization and that no cross-tenant command changed it. */
    private static void assertGrantInOrganization(String organization, String id, String subject,
                                                  String reason) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT organization_id::text, subject, facility_code, purpose, reason, role_key, "
                    + "revoked_at, reviewed_at FROM public.emergency_access_grant "
                    + "WHERE id::text = '" + id + "'")) {
                assertTrue(result.next(), "the owner organization must see exactly one grant row");
                assertEquals(organization, result.getString("organization_id"));
                assertEquals(subject, result.getString("subject"));
                assertEquals("MAIN", result.getString("facility_code"));
                assertEquals("EMERGENCY_ACCESS", result.getString("purpose"));
                assertEquals(reason, result.getString("reason"));
                assertEquals("clinician", result.getString("role_key"));
                assertNull(result.getTimestamp("revoked_at"));
                assertNull(result.getTimestamp("reviewed_at"));
            }
        }
    }

    /** Assert the privacy-auditor review row opened with the grant. */
    private static void assertReview(String organization, String id, String expectedStatus,
                                     String expectedOutcome, String expectedReviewer) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT status, outcome, reviewer, note, closed_at FROM public.emergency_access_review "
                    + "WHERE grant_id::text = '" + id + "'")) {
                assertTrue(result.next(), "one review row must exist in the active organization");
                assertEquals(expectedStatus, result.getString("status"));
                assertEquals(expectedOutcome, result.getString("outcome"));
                assertEquals(expectedReviewer, result.getString("reviewer"));
                if ("closed".equals(expectedStatus)) {
                    assertNotNull(result.getTimestamp("closed_at"));
                } else {
                    assertNull(result.getTimestamp("closed_at"));
                }
            }
        }
    }

    @Test
    void emergencyGrantStaysInsideTheCliniciansOwnOrganization() throws Exception {
        String id = createGrant("clinician-own", ORG_A);

        assertGrantInOrganization(ORG_A, id, "clinician-own", "patient in distress");
        assertReview(ORG_A, id, "open", null, null);
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE id::text = '" + id + "'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE grant_id::text = '" + id + "'"));

        ResponseEntity<String> active = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-own", ORG_A, UUID.randomUUID().toString());
        assertEquals(HttpStatus.OK, active.getStatusCode());
        assertTrue(active.getBody().contains("\"allowed\":true"), active.getBody());
        assertTrue(active.getBody().contains("\"reason\":\"ACTIVE\""), active.getBody());
    }

    @Test
    void anotherOrganizationCannotUnlockOrReadTheGrant() throws Exception {
        String id = createGrant("clinician-shared", ORG_A);
        // Positive control: the shared subject really has a grant in ORG_A, so a
        // zero count under ORG_B can only be tenant isolation, not a missing grant.
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE id::text = '" + id + "'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'clinician-shared'"));

        ResponseEntity<String> activeElsewhere = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-shared", ORG_B, UUID.randomUUID().toString());
        assertEquals(HttpStatus.OK, activeElsewhere.getStatusCode(), activeElsewhere.getBody());
        assertTrue(activeElsewhere.getBody().contains("\"allowed\":false"), activeElsewhere.getBody());
        assertTrue(activeElsewhere.getBody().contains("\"reason\":\"NO_GRANT\""), activeElsewhere.getBody());
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE id::text = '" + id + "'"));
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE grant_id::text = '" + id + "'"));
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'clinician-shared'"));

        ResponseEntity<String> revokeElsewhere = request(HttpMethod.POST,
                "/api/v1/access/emergency/" + id + "/revoke", "{\"reason\":\"cross tenant attempt\"}",
                "clinician-shared", ORG_B, UUID.randomUUID().toString());
        assertEquals(HttpStatus.NOT_FOUND, revokeElsewhere.getStatusCode(), revokeElsewhere.getBody());
        ResponseEntity<String> reviewElsewhere = request(HttpMethod.POST,
                "/api/v1/access/emergency/" + id + "/review",
                "{\"note\":\"checked\",\"outcome\":\"concerns_noted\"}",
                "auditor-b", ORG_B, UUID.randomUUID().toString());
        assertEquals(HttpStatus.NOT_FOUND, reviewElsewhere.getStatusCode(), reviewElsewhere.getBody());

        // A concealed row must also stay unchanged: read the owner tenant, where
        // the row is visible, instead of inferring preservation from ORG_B's zeros.
        assertGrantInOrganization(ORG_A, id, "clinician-shared", "patient in distress");
        assertReview(ORG_A, id, "open", null, null);
    }

    @Test
    void wrongRoleCannotOpenAnEmergencyGrant() throws Exception {
        String correlation = UUID.randomUUID().toString();
        ResponseEntity<String> refused = grant("receptionist-role", ORG_A, "MAIN",
                "patient in distress", 10, correlation);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertTrue(refused.getBody().contains("FORBIDDEN"), refused.getBody());
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'receptionist-role'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE subject = 'receptionist-role'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE organization_id = '" + ORG_A + "' AND correlation_id = '" + correlation + "' "
                + "AND action = 'EMERGENCY_GRANT' AND purpose = 'EMERGENCY_ACCESS' "
                + "AND scope = 'clinician:MAIN' AND resource_id = 'MAIN' AND outcome = 'DENIED'"));
    }

    @Test
    void missingOrOutOfOrganizationFacilityIsRefused() throws Exception {
        ResponseEntity<String> blankReason = grant("clinician-input", ORG_A, "MAIN", "  ", 10);
        assertEquals(422, blankReason.getStatusCode().value(), blankReason.getBody());
        assertTrue(blankReason.getBody().contains("INVALID_REASON"), blankReason.getBody());

        ResponseEntity<String> outOfRangeDuration = grant("clinician-input", ORG_A, "MAIN",
                "patient in distress", 61);
        assertEquals(422, outOfRangeDuration.getStatusCode().value(), outOfRangeDuration.getBody());
        assertTrue(outOfRangeDuration.getBody().contains("INVALID_DURATION"), outOfRangeDuration.getBody());

        ResponseEntity<String> foreignFacility = grant("clinician-input", ORG_A, "NOT_IN_ORG_A",
                "patient in distress", 10);
        assertEquals(HttpStatus.NOT_FOUND, foreignFacility.getStatusCode(), foreignFacility.getBody());
        assertTrue(foreignFacility.getBody().contains("NOT_FOUND"), foreignFacility.getBody());

        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'clinician-input'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE subject = 'clinician-input'"));
        assertFalse(foreignFacility.getBody().contains("Alpha Main"), foreignFacility.getBody());
    }
}
