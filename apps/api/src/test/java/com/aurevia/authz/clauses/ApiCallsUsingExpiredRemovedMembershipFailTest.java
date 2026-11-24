package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * IAM-02 clause 4 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>Every protected command re-resolves the caller's membership fold inside a
 * tenant-guarded transaction. An expired membership event (its validity window
 * has closed) or a removed membership (a later revoked event) must therefore
 * fail closed on the very next API call: the request is refused with 403 and no
 * tenant data is returned. The positive case shows that a still-active member
 * of the same tenant is allowed through the same entry point, and the
 * cross-tenant case shows that membership in a different organization does not
 * authorize a call against the selected one.</p>
 *
 * <p>All fixtures are synthetic. No production code, migration or contract was
 * changed by this task; it only adds proof of the delivered behaviour.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_iam02_clause4_it")
@ActiveProfiles("synthetic-auth")
class ApiCallsUsingExpiredRemovedMembershipFailTest {

    static final String ORG_A = "aa000000-0000-4000-8000-000000000001";
    static final String ORG_B = "aa000000-0000-4000-8000-000000000002";
    static final String FACILITY_A = "IAM02-C4-LOC";
    static final String FACILITY_B = "IAM02-C4-OTHER";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_iam02_clause4_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    // The expired event opened two days ago and closed one day ago, so the
    // database fold sees a current-but-expired membership at now().
    static final String EXPIRED_FROM = "now() - interval '2 days'";
    static final String EXPIRED_TO = "now() - interval '1 day'";

    RestClient rest;

    /** Build the HTTP client against the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate an isolated database, apply the production migrations and seed synthetic members. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_iam02_clause4_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_iam02_clause4_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin", null, null);
            seedStaff(statement, ORG_A, "active-a", "tenant_admin", null, null);
            seedStaff(statement, ORG_A, "expired-a", "tenant_admin", EXPIRED_FROM, EXPIRED_TO);
            seedStaff(statement, ORG_A, "removed-a", "tenant_admin", null, null);
            seedStaff(statement, ORG_B, "tenant-b-only", "tenant_admin", null, null);
            seedFacility(statement, ORG_A, FACILITY_A);
            seedFacility(statement, ORG_B, FACILITY_B);
        }
    }

    /** Drop the isolated database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_iam02_clause4_it WITH (FORCE)");
        }
    }

    /**
     * Append one synthetic identity, membership event and bound role grant for a subject.
     *
     * @param statement  owner statement on the test database
     * @param org        organization the member belongs to
     * @param subject    synthetic authenticated subject
     * @param role       bounded role key granted to the member
     * @param validFrom  SQL expression for an earlier opening time, or null for the default
     * @param validTo    SQL expression for a closing time, or null for an open-ended membership
     * @throws SQLException when a fixture row cannot be appended
     */
    private static void seedStaff(Statement statement, String org, String subject, String role,
                                  String validFrom, String validTo) throws SQLException {
        statement.execute("SET app.current_org = '" + org + "'");
        // The role-grant trigger derives the grantor from the actor, so the
        // subject must exist as an identity before its grant is inserted.
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        String columns = "organization_id, user_id, status";
        String values = "'" + org + "', id, 'active'";
        if (validFrom != null) {
            columns += ", valid_from";
            values += ", " + validFrom;
        }
        if (validTo != null) {
            columns += ", valid_to";
            values += ", " + validTo;
        }
        statement.execute("INSERT INTO public.membership (" + columns + ") "
                + "SELECT " + values + " FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    /**
     * Append one synthetic facility so reads are meaningful under forced row-level security.
     *
     * @param statement owner statement on the test database
     * @param org       owning organization
     * @param code      tenant-local facility code
     * @throws SQLException when the fixture row cannot be inserted
     */
    private static void seedFacility(Statement statement, String org, String code) throws SQLException {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                + "VALUES ('" + org + "', '" + code + "', 'Clause four location')");
    }

    /**
     * Read the authoritative membership fold through the same RLS-scoped function the API uses.
     *
     * @param org     organization to bind as the active tenant
     * @param subject synthetic subject to check
     * @return true only when the current event is active and inside its validity window
     * @throws SQLException when the fold cannot be queried
     */
    private boolean databaseSaysActive(String org, String subject) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT public.has_active_membership('" + subject + "')")) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    /** Build protected request headers from a synthetic subject and selected organization. */
    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.set("X-Correlation-Id", "iam02-clause4-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Issue one API request without letting a non-2xx status abort the test. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Read one JSON scalar field without adding a JSON parser dependency. */
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

    @Test
    void activeMembershipAllowsTheProtectedFacilityRead() throws Exception {
        assertTrue(databaseSaysActive(ORG_A, "active-a"));

        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/facilities/" + FACILITY_A, null, "active-a", ORG_A);

        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains(ORG_A), response.getBody());
        assertTrue(response.getBody().contains(FACILITY_A), response.getBody());
    }

    @Test
    void expiredMembershipFailsClosedOnTheNextProtectedReadAndWrite() throws Exception {
        assertFalse(databaseSaysActive(ORG_A, "expired-a"),
                "the fixture must be expired in the authoritative fold");

        ResponseEntity<String> read = request(HttpMethod.GET,
                "/api/v1/facilities/" + FACILITY_A, null, "expired-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, read.getStatusCode(), read.getBody());
        assertEquals("FORBIDDEN", field(read.getBody(), "code"));
        assertTrue(read.getBody().contains("no active membership"), read.getBody());
        assertFalse(read.getBody().contains(FACILITY_A), read.getBody());

        ResponseEntity<String> write = request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"IAM02-C4-EXPIRED-WRITE\",\"name\":\"Must not be created\"}",
                "expired-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, write.getStatusCode(), write.getBody());
        assertEquals("FORBIDDEN", field(write.getBody(), "code"));
        // The denial happens before the insert, so the tenant must have no new row.
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/facilities/IAM02-C4-EXPIRED-WRITE", null, "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void removedMembershipFailsClosedOnTheNextProtectedCall() throws Exception {
        assertTrue(databaseSaysActive(ORG_A, "removed-a"));
        assertEquals(HttpStatus.OK, request(HttpMethod.GET,
                "/api/v1/facilities/" + FACILITY_A, null, "removed-a", ORG_A).getStatusCode());

        ResponseEntity<String> deactivated = request(HttpMethod.POST,
                "/api/v1/staff/removed-a/deactivate", "{}", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, deactivated.getStatusCode(), deactivated.getBody());
        assertFalse(databaseSaysActive(ORG_A, "removed-a"),
                "the deactivation event must remove the fold's active membership");

        ResponseEntity<String> read = request(HttpMethod.GET,
                "/api/v1/facilities/" + FACILITY_A, null, "removed-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, read.getStatusCode(), read.getBody());
        assertEquals("FORBIDDEN", field(read.getBody(), "code"));
        assertTrue(read.getBody().contains("no active membership"), read.getBody());
        assertFalse(read.getBody().contains(FACILITY_A), read.getBody());

        ResponseEntity<String> write = request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"IAM02-C4-REMOVED-WRITE\",\"name\":\"Must not be created\"}",
                "removed-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, write.getStatusCode(), write.getBody());
        assertEquals("FORBIDDEN", field(write.getBody(), "code"));
        // A removed member cannot create durable tenant data either.
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/facilities/IAM02-C4-REMOVED-WRITE", null, "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void membershipInAnotherTenantIsRefusedForTheSelectedOrganization() throws Exception {
        assertTrue(databaseSaysActive(ORG_B, "tenant-b-only"));
        assertEquals(HttpStatus.OK, request(HttpMethod.GET,
                "/api/v1/facilities/" + FACILITY_B, null, "tenant-b-only", ORG_B).getStatusCode());

        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/facilities/" + FACILITY_A, null, "tenant-b-only", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals("FORBIDDEN", field(response.getBody(), "code"));
        assertTrue(response.getBody().contains("no active membership"), response.getBody());
        assertFalse(response.getBody().contains(FACILITY_A), response.getBody());
    }
}
