package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

import org.flywaydb.core.Flyway;

/**
 * S-G0-08 acceptance on real PostgreSQL: emergency access is qualified, own-tenant,
 * time-limited, reasoned, alerted and reviewed; platform support access is denied by
 * default and only available through a separately approved incident (IAM-06, ARC-02).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_access_it")
@ActiveProfiles("synthetic-auth")
class AccessIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_access_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

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
            statement.execute("DROP DATABASE IF EXISTS orevia_access_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_access_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "clinician-exp", "clinician");
            seedUser(statement, ORG_A, "clinician-rev", "clinician");
            seedUser(statement, ORG_A, "clinician-grant", "clinician");
            seedUser(statement, ORG_A, "clinician-audit", "clinician");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
            seedUser(statement, ORG_B, "auditor-b", "privacy_auditor");
            seedUser(statement, ORG_B, "clinician-b", "clinician");

            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Alpha Main')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'MAIN', 'Beta Main')");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'BETA', 'Beta Only')");

            // Org A approved the emergency-access policy; Org B deliberately did not.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'admin-a'");
            statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                    + "VALUES ('" + ORG_A + "', 'access.emergency.enabled', 1, 'true')");
            statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                    + "SELECT '" + ORG_A + "', id, 'synthetic approval' FROM public.config_entry "
                    + "WHERE key = 'access.emergency.enabled' AND version = 1");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_access_it WITH (FORCE)");
        }
    }

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
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

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

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> grant(String facility, String reason, int minutes,
                                         String actor, String organization, String correlation) {
        String body = "{\"facilityCode\":\"" + facility + "\",\"reason\":\"" + reason
                + "\",\"durationMinutes\":" + minutes + "}";
        return request(HttpMethod.POST, "/api/v1/access/emergency", body, actor, organization, correlation);
    }

    private ResponseEntity<String> active(String actor, String organization, String correlation) {
        return request(HttpMethod.GET, "/api/v1/access/emergency/active", null, actor, organization, correlation);
    }

    private ResponseEntity<String> revoke(String id, String reason, String actor, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/emergency/" + id + "/revoke",
                "{\"reason\":\"" + reason + "\"}", actor, organization, "revoke-corr");
    }

    private ResponseEntity<String> review(String id, String note, String actor, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/emergency/" + id + "/review",
                "{\"note\":\"" + note + "\"}", actor, organization, "review-corr");
    }

    private ResponseEntity<String> queue(String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/access/emergency/review-queue", null,
                actor, organization, "queue-corr");
    }

    private ResponseEntity<String> createIncident(String reference, String actor, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/support/incidents",
                "{\"reference\":\"" + reference + "\",\"reason\":\"restore chart\",\"windowMinutes\":60}",
                actor, organization, "incident-corr");
    }

    private ResponseEntity<String> approve(String reference, String actor, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/support/incidents/" + reference + "/approve",
                "{}", actor, organization, "approve-corr");
    }

    private ResponseEntity<String> check(String reference, String actor, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/support/check",
                "{\"reference\":\"" + reference + "\"}", actor, organization, "check-corr");
    }

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

    private String userId(String organization, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    @Test
    void qualifiedClinicianGrantsAndChecksActiveAccess() {
        ResponseEntity<String> created = grant("MAIN", "patient in distress", 10,
                "clinician-a", ORG_A, "grant-corr-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(id.isBlank());
        assertEquals("clinician-a", field(created.getBody(), "subject"));
        assertEquals("MAIN", field(created.getBody(), "facilityCode"));
        assertEquals("clinician", field(created.getBody(), "roleKey"));
        assertEquals("active", field(created.getBody(), "status"));

        ResponseEntity<String> activeResponse = active("clinician-a", ORG_A, "active-corr-1");
        assertEquals(HttpStatus.OK, activeResponse.getStatusCode());
        assertTrue(activeResponse.getBody().contains("\"allowed\":true"));
        assertTrue(activeResponse.getBody().contains("ACTIVE"));

        // An authorized non-clinician cannot unlock emergency access.
        assertEquals(HttpStatus.FORBIDDEN,
                grant("MAIN", "not my role", 10, "admin-a", ORG_A, "grant-corr-deny").getStatusCode());
    }

    @Test
    void grantIsOwnTenantOnlyAndRequiresApprovedConfigurationAndValidInputs() {
        // Org B has no approved emergency-access policy.
        assertEquals(HttpStatus.FORBIDDEN,
                grant("MAIN", "why", 10, "clinician-b", ORG_B, "grant-corr-noconfig").getStatusCode());
        // BETA exists only in Org B, so it is concealed from an Org A clinician.
        assertEquals(HttpStatus.NOT_FOUND,
                grant("BETA", "why", 10, "clinician-grant", ORG_A, "grant-corr-nofacility").getStatusCode());
        // An actor with no Org B membership cannot switch tenants to unlock access.
        assertEquals(HttpStatus.FORBIDDEN,
                grant("MAIN", "why", 10, "clinician-a", ORG_B, "grant-corr-wrongorg").getStatusCode());
        assertEquals(422, grant("MAIN", " ", 10, "clinician-a", ORG_A, "grant-corr-badreason")
                .getStatusCode().value());
        assertEquals(422, grant("MAIN", "why", 0, "clinician-a", ORG_A, "grant-corr-badduration")
                .getStatusCode().value());
    }

    @Test
    void expiredGrantCannotUnlockAccess() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.emergency_access_grant "
                    + "(organization_id, subject, facility_code, purpose, reason, role_key, "
                    + "granted_at, expires_at) VALUES ('" + ORG_A + "', 'clinician-exp', 'MAIN', "
                    + "'EMERGENCY_ACCESS', 'stale grant', 'clinician', now() - interval '2 hours', "
                    + "now() - interval '1 hour')");
        }
        ResponseEntity<String> response = active("clinician-exp", ORG_A, "active-corr-expired");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("\"allowed\":false"));
        assertTrue(response.getBody().contains("EXPIRED"));
    }

    @Test
    void revokeAndReviewAreRoleBoundAndPreserveActorReason() {
        ResponseEntity<String> created = grant("MAIN", "patient in distress", 10,
                "clinician-rev", ORG_A, "grant-corr-rev");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.FORBIDDEN, revoke(id, "why", "admin-a", ORG_A).getStatusCode());
        ResponseEntity<String> revoked = revoke(id, "no longer needed", "clinician-rev", ORG_A);
        assertEquals(HttpStatus.OK, revoked.getStatusCode(), revoked.getBody());
        assertEquals("revoked", field(revoked.getBody(), "status"));
        assertTrue(active("clinician-rev", ORG_A, "active-corr-revoked").getBody()
                .contains("REVOKED"));

        assertEquals(HttpStatus.FORBIDDEN, review(id, "checked", "clinician-rev", ORG_A).getStatusCode());
        ResponseEntity<String> reviewed = review(id, "reviewed in huddle", "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertEquals("reviewed", field(reviewed.getBody(), "status"));
    }

    @Test
    void reviewQueueConcealsOtherTenantGrants() {
        ResponseEntity<String> created = grant("MAIN", "patient in distress", 10,
                "clinician-grant", ORG_A, "grant-corr-queue");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> ownQueue = queue("auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, ownQueue.getStatusCode());
        assertTrue(ownQueue.getBody().contains(id));

        ResponseEntity<String> otherQueue = queue("auditor-b", ORG_B);
        assertEquals(HttpStatus.OK, otherQueue.getStatusCode());
        assertFalse(otherQueue.getBody().contains(id));
        assertEquals(HttpStatus.NOT_FOUND, review(id, "checked", "auditor-b", ORG_B).getStatusCode());
    }

    @Test
    void supportAccessIsDeniedByDefaultAndNeedsSeparateApproval() {
        ResponseEntity<String> denied = check("INC-999", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, denied.getStatusCode());
        assertTrue(denied.getBody().contains("\"allowed\":false"));

        assertEquals(HttpStatus.CREATED, createIncident("INC-200", "admin-a", ORG_A).getStatusCode());
        assertTrue(check("INC-200", "admin-a", ORG_A).getBody().contains("\"allowed\":false"));
        assertEquals(HttpStatus.FORBIDDEN, approve("INC-200", "admin-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.OK, approve("INC-200", "auditor-a", ORG_A).getStatusCode());
        ResponseEntity<String> allowed = check("INC-200", "admin-a", ORG_A);
        assertTrue(allowed.getBody().contains("\"allowed\":true"));
        assertTrue(allowed.getBody().contains("APPROVED_INCIDENT"));
        // The same reference is invisible in the other tenant.
        assertTrue(check("INC-200", "admin-b", ORG_B).getBody().contains("\"allowed\":false"));
    }

    @Test
    void supportIncidentCannotBeSelfApprovedAtDatabaseLevelAndGrantsAreTenantIsolated() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO public.support_access_incident "
                    + "(organization_id, reference, subject, reason, status, approved_by, approved_at, expires_at) "
                    + "VALUES ('" + ORG_A + "', 'INC-BAD', 'admin-a', 'why', 'approved', "
                    + "'admin-a', now(), now() + interval '1 hour')"));
        }

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.emergency_access_grant "
                    + "(organization_id, subject, facility_code, purpose, reason, role_key, "
                    + "granted_at, expires_at) VALUES ('" + ORG_A + "', 'clinician-a', 'MAIN', "
                    + "'EMERGENCY_ACCESS', 'stored grant', 'clinician', now(), now() + interval '1 hour')");
        }
        try (Connection connection = DriverManager.getConnection(URL, "orevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.emergency_access_grant")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void sensitiveActionsAreAuditedWithPurposeRoleFacilityAndActor() throws Exception {
        ResponseEntity<String> created = grant("MAIN", "patient in distress", 10,
                "clinician-audit", ORG_A, "grant-corr-audit");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        revoke(field(created.getBody(), "id"), "done", "clinician-audit", ORG_A);
        createIncident("INC-300", "admin-a", ORG_A);
        approve("INC-300", "auditor-a", ORG_A);
        check("INC-300", "admin-a", ORG_A);

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            List<String> actions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT action FROM public.audit_event WHERE correlation_id IN "
                    + "('grant-corr-audit','revoke-corr','incident-corr','approve-corr','check-corr') "
                    + "ORDER BY seq")) {
                while (result.next()) {
                    actions.add(result.getString(1));
                }
            }
            assertTrue(actions.contains("EMERGENCY_GRANT"));
            assertTrue(actions.contains("EMERGENCY_ALERT"));
            assertTrue(actions.contains("EMERGENCY_REVOKE"));
            assertTrue(actions.contains("SUPPORT_INCIDENT_CREATE"));
            assertTrue(actions.contains("SUPPORT_INCIDENT_APPROVE"));
            assertTrue(actions.contains("SUPPORT_ACCESS_CHECK"));

            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE correlation_id = 'grant-corr-audit' "
                    + "AND actor_id = '" + userId(ORG_A, "clinician-audit")
                    + "' AND purpose = 'EMERGENCY_ACCESS' AND scope = 'clinician:MAIN' "
                    + "AND outcome = 'SUCCESS'")) {
                assertTrue(result.next());
                assertTrue(result.getInt(1) >= 2);
            }
        }
    }
}
