package com.aurevia.smartclinic.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * IAM-02 clause 2 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>Every role grant is appended to an immutable ledger that carries the subject
 * who granted the role and the effective-date window supplied with the grant.
 * This test proves only that clause: the positive case reads a grant back through
 * the normal HTTP entry point and confirms the granter and both effective dates;
 * the negative cases show a cross-tenant subject concealed, a non-admin refused,
 * malformed input refused and the recorded row immune to rewriting.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam02_clause2_it")
@ActiveProfiles("synthetic-auth")
class RecordGrantedRoleEffectiveDatesTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam02_clause2_it";
    private static final String VALID_FROM = "2026-01-01T00:00:00Z";
    private static final String VALID_TO = "2027-01-01T00:00:00Z";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam02_clause2_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam02_clause2_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            // admin-a is the caller; granter-a grants subject-a's role; reception-a
            // is the wrong-role caller; subject-b lives only in the other tenant.
            seedIdentity(statement, ORG_A, "admin-a");
            seedIdentity(statement, ORG_A, "granter-a");
            seedIdentity(statement, ORG_A, "subject-a");
            seedIdentity(statement, ORG_A, "reception-a");
            seedIdentity(statement, ORG_B, "admin-b");
            seedIdentity(statement, ORG_B, "subject-b");

            seedRole(statement, ORG_A, "admin-a", "tenant_admin", "admin-a", null, null);
            seedRole(statement, ORG_A, "granter-a", "tenant_admin", "admin-a", null, null);
            seedRole(statement, ORG_A, "reception-a", "reception", "admin-a", null, null);
            seedRole(statement, ORG_B, "admin-b", "tenant_admin", "admin-b", null, null);
            seedRole(statement, ORG_B, "subject-b", "reception", "admin-b", null, null);
            // The grant under test names granter-a and an explicit effective window.
            seedRole(statement, ORG_A, "subject-a", "reception", "granter-a", VALID_FROM, VALID_TO);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam02_clause2_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic organization member with an active membership but no role yet. */
    private static void seedIdentity(Statement statement, String org, String subject) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
    }

    /**
     * Seed one role grant. The actor GUC is the authenticated caller and the grantor
     * GUC carries the grantor for the invite case, so both name the granter; the V4
     * trigger fills granted_by and the V71 trigger records the same principal as the
     * actor, making the ledger proof reflect a real grant.
     */
    private static void seedRole(Statement statement, String org, String subject, String role,
                                 String granter, String validFrom, String validTo) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + granter + "'");
        statement.execute("SET app.grantor_subject = '" + granter + "'");
        String dates = validFrom == null ? "now(), NULL"
                : "'" + validFrom + "', " + (validTo == null ? "NULL" : "'" + validTo + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by, valid_from, valid_to) "
                + "SELECT '" + org + "', m.id, '" + role + "', g.id, " + dates + " "
                + "FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "JOIN public.user_identity g ON g.organization_id = '" + org + "' AND g.subject = '" + granter + "' "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    private ResponseEntity<String> history(String subject, String actor, String organization) {
        return rest.get()
                .uri("/api/v1/authz/role-history/" + subject)
                .header("X-Synthetic-Subject", actor)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "iam02-clause2-corr")
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static boolean contains(String json, String needle) {
        return json != null && json.contains(needle);
    }

    @Test
    void grantedRoleRecordsWhoGrantedItAndItsEffectiveDates() throws Exception {
        ResponseEntity<String> response = history("subject-a", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        String body = response.getBody();
        assertTrue(contains(body, "\"subject\":\"subject-a\""), body);
        assertTrue(contains(body, "\"changeKind\":\"GRANT\""), body);
        assertTrue(contains(body, "\"roleKey\":\"reception\""), body);
        assertTrue(contains(body, "\"actor\":\"granter-a\""), body);
        assertTrue(contains(body, "\"effectiveFrom\":\""), body);
        assertTrue(contains(body, "\"effectiveTo\":\""), body);

        // Compare the durable RLS-scoped ledger values as instants, not as rendered
        // text, so the assertion is independent of the server time zone.
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT actor, "
                            + "effective_from = '" + VALID_FROM + "'::timestamptz AS from_ok, "
                            + "effective_to = '" + VALID_TO + "'::timestamptz AS to_ok "
                            + "FROM public.role_change_history WHERE subject = 'subject-a'")) {
                assertTrue(result.next());
                assertEquals("granter-a", result.getString("actor"));
                assertTrue(result.getBoolean("from_ok"));
                assertTrue(result.getBoolean("to_ok"));
            }
        }
    }

    @Test
    void crossTenantGrantHistoryIsConcealed() {
        assertEquals(HttpStatus.NOT_FOUND, history("subject-b", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void nonAdminCannotReadGrantHistory() {
        assertEquals(HttpStatus.FORBIDDEN, history("subject-a", "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void malformedSubjectIsRefused() {
        assertEquals(422, history("x", "admin-a", ORG_A).getStatusCode().value());
    }

    @Test
    void recordedGranterAndDatesCannotBeRewritten() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException update = assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.role_change_history SET actor = 'forged'"));
            assertEquals("44000", update.getSQLState(), update.getMessage());
            assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.role_change_history"));
            assertThrows(SQLException.class,
                    () -> statement.execute("TRUNCATE public.role_change_history"));
        }
    }
}
