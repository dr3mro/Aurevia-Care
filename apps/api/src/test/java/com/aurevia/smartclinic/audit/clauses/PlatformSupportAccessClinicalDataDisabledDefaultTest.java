package com.aurevia.smartclinic.audit.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
 * IAM-06 clause 4 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>Platform support access to clinical data stays denied unless a separate
 * privacy auditor approves an unexpired incident for the exact requesting
 * subject in the active organization. The tests reach the rule only through its
 * HTTP commands, assert the stable decision reason, and read the append-only
 * audit trail to prove that creation, approval and checking are separately
 * recorded. No clinical data, real identifier or credential is used: the only
 * privileged rows are synthetic incidents bound to synthetic staff subjects.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_support_access_it")
@ActiveProfiles("synthetic-auth")
class PlatformSupportAccessClinicalDataDisabledDefaultTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_support_access_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Start an HTTP client bound to the random port of the application under test. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Apply every migration to a fresh synthetic database and seed two isolated tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_support_access_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_support_access_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_B, "admin-b", "tenant_admin");
            seedStaff(statement, ORG_B, "auditor-b", "privacy_auditor");
            // The same subject is an active member of both tenants: the cross-tenant
            // test then proves RLS rather than a difference in subject binding.
            seedStaff(statement, ORG_B, "admin-a", "tenant_admin");
        }
    }

    /** Drop the synthetic database after the suite so no incident or audit row survives. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_support_access_it WITH (FORCE)");
        }
    }

    /**
     * Seed one active synthetic member with a single bounded role in one organization.
     *
     * <p>Both predicates matter: a subject with the same name may exist in the
     * other tenant, and the membership foreign key carries the organization.</p>
     *
     * @param statement open statement on the synthetic database
     * @param org       organization UUID as text
     * @param subject   synthetic staff subject
     * @param role      bounded role key granted to the member
     * @throws Exception when a seed statement fails
     */
    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Support Org " + subject + "' WHERE NOT EXISTS "
                + "(SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.organization_id = '" + org + "' AND u.subject = '" + subject + "'");
    }

    /**
     * Build the three synthetic identity headers accepted in the test profile.
     *
     * @param subject    authenticated synthetic subject
     * @param org        explicitly selected active organization
     * @param correlation unique correlation id for this request and its audit rows
     * @return request headers with a JSON content type
     */
    private HttpHeaders headers(String subject, String org, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Send one HTTP command without letting an error status abort the client.
     *
     * @param method      HTTP method of the command
     * @param uri         versioned API path
     * @param body        JSON body, or {@code null} for a bodyless request
     * @param subject     authenticated synthetic subject
     * @param org         active organization
     * @param correlation correlation id stamped on the resulting audit rows
     * @return the raw HTTP response, including error statuses
     */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, org, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Open a pending incident as the tenants' administrator. */
    private ResponseEntity<String> createIncident(String reference, String actor, String org,
                                                  String correlation) {
        return request(HttpMethod.POST, "/api/v1/access/support/incidents",
                "{\"reference\":\"" + reference + "\",\"reason\":\"restore chart\",\"windowMinutes\":60}",
                actor, org, correlation);
    }

    /** Approve a pending incident as the separate privacy auditor. */
    private ResponseEntity<String> approve(String reference, String actor, String org,
                                           String correlation) {
        return request(HttpMethod.POST, "/api/v1/access/support/incidents/" + reference + "/approve",
                "{}", actor, org, correlation);
    }

    /** Ask the support access decision endpoint whether the incident currently allows access. */
    private ResponseEntity<String> check(String reference, String actor, String org,
                                         String correlation) {
        return request(HttpMethod.POST, "/api/v1/access/support/check",
                "{\"reference\":\"" + reference + "\"}", actor, org, correlation);
    }

    /** Read one JSON scalar without importing a JSON library into the test. */
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

    /**
     * Read the audit actions recorded for one correlation id inside one tenant.
     *
     * @param org         organization whose forced RLS scope is read
     * @param correlation correlation id shared by the request and its audit rows
     * @return {@code action|purpose|outcome} rows in append order
     * @throws SQLException when the query fails
     */
    private List<String> auditRows(String org, String correlation) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'admin-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT action || '|' || purpose || '|' || outcome FROM public.audit_event "
                    + "WHERE correlation_id = '" + correlation + "' ORDER BY seq")) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
        }
        return rows;
    }

    /** Read the lifecycle status of one incident in the given tenant. */
    private String incidentStatus(String org, String reference) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT status FROM public.support_access_incident WHERE reference = '" + reference + "'")) {
                assertTrue(result.next(), "the synthetic incident must exist in its own organization");
                return result.getString(1);
            }
        }
    }

    /** Count incidents with a reference visible under the caller's forced RLS scope. */
    private int visibleIncidents(String org, String reference) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.support_access_incident WHERE reference = '" + reference + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void supportAccessIsDeniedWhenNoApprovedIncidentExists() throws Exception {
        ResponseEntity<String> unknown = check("INC-ABSENT", "admin-a", ORG_A, "support-absent-check");
        assertEquals(HttpStatus.OK, unknown.getStatusCode(), unknown.getBody());
        assertEquals("false", field(unknown.getBody(), "allowed"));
        assertEquals("NO_APPROVED_INCIDENT", field(unknown.getBody(), "reason"));

        ResponseEntity<String> created = createIncident("INC-PENDING", "admin-a", ORG_A,
                "support-pending-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("pending", field(created.getBody(), "status"));

        ResponseEntity<String> pending = check("INC-PENDING", "admin-a", ORG_A, "support-pending-check");
        assertEquals(HttpStatus.OK, pending.getStatusCode(), pending.getBody());
        assertEquals("false", field(pending.getBody(), "allowed"));
        assertEquals("NO_APPROVED_INCIDENT", field(pending.getBody(), "reason"));

        // An unapproved incident is not a support grant: both checks are denied and audited.
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_A, "support-absent-check"));
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_A, "support-pending-check"));
    }

    @Test
    void approvedIncidentBySeparatePrivacyAuditorAllowsOnlyBoundSubject() throws Exception {
        ResponseEntity<String> created = createIncident("INC-FLOW", "admin-a", ORG_A,
                "support-flow-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("pending", field(created.getBody(), "status"));
        assertEquals("admin-a", field(created.getBody(), "subject"));

        ResponseEntity<String> approved = approve("INC-FLOW", "auditor-a", ORG_A,
                "support-flow-approve");
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
        assertEquals("approved", field(approved.getBody(), "status"));
        assertEquals("auditor-a", field(approved.getBody(), "approvedBy"));

        ResponseEntity<String> allowed = check("INC-FLOW", "admin-a", ORG_A, "support-flow-check");
        assertEquals(HttpStatus.OK, allowed.getStatusCode(), allowed.getBody());
        assertEquals("true", field(allowed.getBody(), "allowed"));
        assertEquals("APPROVED_INCIDENT", field(allowed.getBody(), "reason"));

        // The approval is bound to one subject; another active member cannot borrow it.
        ResponseEntity<String> other = check("INC-FLOW", "reception-a", ORG_A, "support-flow-other");
        assertEquals(HttpStatus.OK, other.getStatusCode(), other.getBody());
        assertEquals("false", field(other.getBody(), "allowed"));
        assertEquals("NO_APPROVED_INCIDENT", field(other.getBody(), "reason"));

        assertEquals(List.of("SUPPORT_INCIDENT_CREATE|SUPPORT_ACCESS|SUCCESS"),
                auditRows(ORG_A, "support-flow-create"));
        assertEquals(List.of("SUPPORT_INCIDENT_APPROVE|SUPPORT_ACCESS|SUCCESS"),
                auditRows(ORG_A, "support-flow-approve"));
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|SUCCESS"),
                auditRows(ORG_A, "support-flow-check"));
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_A, "support-flow-other"));
    }

    @Test
    void wrongRoleCannotApproveSupportIncident() throws Exception {
        assertEquals(HttpStatus.CREATED, createIncident("INC-ROLE", "admin-a", ORG_A,
                "support-role-create").getStatusCode());

        ResponseEntity<String> refused = approve("INC-ROLE", "reception-a", ORG_A,
                "support-role-approve");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", field(refused.getBody(), "code"));
        assertEquals("pending", incidentStatus(ORG_A, "INC-ROLE"));
        assertEquals(List.of("SUPPORT_INCIDENT_APPROVE|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_A, "support-role-approve"));
    }

    @Test
    void otherTenantCannotUseApprovedIncident() throws Exception {
        assertEquals(HttpStatus.CREATED, createIncident("INC-CROSS", "admin-a", ORG_A,
                "support-cross-create").getStatusCode());
        assertEquals(HttpStatus.OK, approve("INC-CROSS", "auditor-a", ORG_A,
                "support-cross-approve").getStatusCode());
        assertEquals("approved", incidentStatus(ORG_A, "INC-CROSS"));

        // The requesting subject is an active member of both tenants and the
        // incident is bound to it, so only forced RLS can hide the approval.
        assertEquals(0, visibleIncidents(ORG_B, "INC-CROSS"));
        ResponseEntity<String> denied = check("INC-CROSS", "admin-a", ORG_B, "support-cross-check");
        assertEquals(HttpStatus.OK, denied.getStatusCode(), denied.getBody());
        assertEquals("false", field(denied.getBody(), "allowed"));
        assertEquals("NO_APPROVED_INCIDENT", field(denied.getBody(), "reason"));
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_B, "support-cross-check"));
        assertEquals("approved", incidentStatus(ORG_A, "INC-CROSS"));
    }

    @Test
    void invalidReferenceIsRejected() {
        ResponseEntity<String> malformed = request(HttpMethod.POST, "/api/v1/access/support/check",
                "{\"reference\":\"!!\"}", "admin-a", ORG_A, "support-invalid-check");
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_REFERENCE", field(malformed.getBody(), "code"));

        ResponseEntity<String> missing = request(HttpMethod.POST, "/api/v1/access/support/check",
                "{}", "admin-a", ORG_A, "support-missing-check");
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());
        assertEquals("INVALID_REFERENCE", field(missing.getBody(), "code"));
    }

    @Test
    void expiredApprovedIncidentStillDeniesAccess() throws Exception {
        // Insert a deterministically expired approval: every timestamp is in the past,
        // so the check needs no sleep and cannot race the clock.
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.support_access_incident "
                    + "(organization_id, reference, subject, reason, status, approved_by, approved_at, "
                    + "created_at, expires_at) VALUES ('" + ORG_A + "', 'INC-EXPIRED', 'admin-a', "
                    + "'expired window', 'approved', 'auditor-a', now() - interval '90 minutes', "
                    + "now() - interval '120 minutes', now() - interval '60 minutes')");
        }
        assertEquals("approved", incidentStatus(ORG_A, "INC-EXPIRED"));

        ResponseEntity<String> denied = check("INC-EXPIRED", "admin-a", ORG_A,
                "support-expired-check");
        assertEquals(HttpStatus.OK, denied.getStatusCode(), denied.getBody());
        assertEquals("false", field(denied.getBody(), "allowed"));
        assertEquals("NO_APPROVED_INCIDENT", field(denied.getBody(), "reason"));
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_A, "support-expired-check"));
    }

    @Test
    void supportAccessIsDeniedWithoutActiveMembership() throws Exception {
        // The test profile accepts any non-blank subject, so this reaches the domain
        // rule and proves the default deny does not depend on a valid session alone.
        ResponseEntity<String> denied = check("INC-ABSENT", "no-membership-a", ORG_A,
                "support-nomember-check");
        assertEquals(HttpStatus.OK, denied.getStatusCode(), denied.getBody());
        assertEquals("false", field(denied.getBody(), "allowed"));
        assertEquals("NO_ACTIVE_MEMBERSHIP", field(denied.getBody(), "reason"));
        assertEquals(List.of("SUPPORT_ACCESS_CHECK|SUPPORT_ACCESS|DENIED"),
                auditRows(ORG_A, "support-nomember-check"));
        assertFalse(field(denied.getBody(), "allowed").contains("true"));
    }
}
