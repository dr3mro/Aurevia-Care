package com.aurevia.audit.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

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
 * IAM-06 clause 2 acceptance: emergency access, where approved, is limited to
 * qualified clinicians, scoped inside their own organization, time-limited,
 * reasoned, alerted and reviewed. The checks go through the real HTTP entry
 * point and the authoritative database fold; only synthetic subjects, facilities
 * and justifications are used. Each negative case proves a distinct refusal: a
 * site that has not approved the feature, a non-clinician role, a grant from a
 * different organization, an expired grant and a missing justification.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_emergency_scope_it")
@ActiveProfiles("synthetic-auth")
class EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_emergency_scope_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String FACILITY = "MAIN";
    private static final String CONFIG_KEY = "access.emergency.enabled";

    private RestClient rest;

    /** Start the HTTP client against the random test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Apply every migration to a fresh synthetic database and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_emergency_scope_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_emergency_scope_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // ORG_A approves emergency access; ORG_B deliberately does not.
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_A, "clinician-cross", "clinician");
            seed(statement, ORG_A, "clinician-expired", "clinician");
            seed(statement, ORG_A, "clinician-reason", "clinician");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "clinician-b", "clinician");
            seed(statement, ORG_B, "auditor-b", "privacy_auditor");
            seedFacility(statement, ORG_A);
            seedFacility(statement, ORG_B);
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'auditor-a'");
            statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                    + "VALUES ('" + ORG_A + "', '" + CONFIG_KEY + "', 1, 'true')");
            statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                    + "SELECT '" + ORG_A + "', id, 'synthetic approval' FROM public.config_entry "
                    + "WHERE key = '" + CONFIG_KEY + "' AND version = 1");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_emergency_scope_it WITH (FORCE)");
        }
    }

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

    private static void seedFacility(Statement statement, String organization) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                + "VALUES ('" + organization + "', '" + FACILITY + "', 'Synthetic Main')");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                            String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", "emergency-scope-it-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> grant(String subject, String organization, String reason, int minutes) {
        return request(HttpMethod.POST, "/api/v1/access/emergency",
                "{\"facilityCode\":\"" + FACILITY + "\",\"reason\":\"" + reason
                        + "\",\"durationMinutes\":" + minutes + "}",
                subject, organization);
    }

    private ResponseEntity<String> active(String subject, String organization) {
        return request(HttpMethod.GET, "/api/v1/access/emergency/active", null, subject, organization);
    }

    private ResponseEntity<String> review(String id, String body, String subject, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/emergency/" + id + "/review",
                body, subject, organization);
    }

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

    private int countGrants(String organization, String grantId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.emergency_access_grant WHERE id::text = '" + grantId + "'")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int countRows(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** A qualified clinician at an approving site gets reasoned, time-limited, alerted, reviewable access. */
    @Test
    void approvedSiteLetsClinicianGrantReasonedTimeLimitedAlertedAndReviewedAccess() throws Exception {
        ResponseEntity<String> created = grant("clinician-a", ORG_A, "patient in distress", 10);

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        String body = created.getBody();
        assertEquals("clinician-a", field(body, "subject"));
        assertEquals("clinician", field(body, "roleKey"));
        assertEquals(FACILITY, field(body, "facilityCode"));
        assertEquals("patient in distress", field(body, "reason"));
        assertEquals("active", field(body, "status"));
        Instant grantedAt = Instant.parse(field(body, "grantedAt"));
        Instant expiresAt = Instant.parse(field(body, "expiresAt"));
        assertTrue(expiresAt.isAfter(grantedAt), "the grant must have a future expiry");
        assertEquals(Duration.ofMinutes(10), Duration.between(grantedAt, expiresAt),
                "the grant lifetime must equal the requested minutes");

        ResponseEntity<String> decision = active("clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, decision.getStatusCode());
        assertEquals("true", field(decision.getBody(), "allowed"));
        assertEquals("ACTIVE", field(decision.getBody(), "reason"));

        // Alerted: an append-only audit event and a durable privacy-auditor review
        // row are created together with the grant in the guarded transaction.
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_ALERT' AND resource_id = '" + id + "'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE grant_id::text = '" + id + "' AND recipient_role = 'privacy_auditor' "
                + "AND status = 'open'"));

        // Reviewed: an independent privacy auditor closes the review with an outcome.
        ResponseEntity<String> reviewed = review(id,
                "{\"note\":\"checked\",\"outcome\":\"approved\"}", "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertEquals("reviewed", field(reviewed.getBody(), "status"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE grant_id::text = '" + id + "' AND status = 'closed' "
                + "AND outcome = 'approved' AND reviewer = 'auditor-a'"));
    }

    /** A site that has not approved emergency access refuses the grant. */
    @Test
    void grantIsRefusedWhenTheSiteHasNotApprovedEmergencyAccess() throws Exception {
        ResponseEntity<String> refused = grant("clinician-b", ORG_B, "patient in distress", 10);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals(0, countRows(ORG_B, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'clinician-b'"));
    }

    /** A non-clinician role cannot grant emergency access. */
    @Test
    void grantByAReceptionRoleIsRefused() throws Exception {
        ResponseEntity<String> refused = grant("reception-a", ORG_A, "patient in distress", 10);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'reception-a'"));
    }

    /** A grant inside one organization cannot be seen or reviewed from another. */
    @Test
    void grantCannotUnlockAnotherOrganizationsData() throws Exception {
        ResponseEntity<String> created = grant("clinician-cross", ORG_A, "patient in distress", 10);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals(1, countGrants(ORG_A, id));

        assertEquals(0, countGrants(ORG_B, id), "forced RLS hides the grant from another organization");
        ResponseEntity<String> crossReview = review(id,
                "{\"note\":\"checked\"}", "auditor-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, crossReview.getStatusCode(), crossReview.getBody());
        // The denied cross-tenant command must not have altered the owner's review.
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.emergency_access_review "
                + "WHERE grant_id::text = '" + id + "' AND status = 'open'"));
    }

    /** An expired grant stops being usable on the next request. */
    @Test
    void expiredGrantIsNotActiveOnTheNextRequest() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.emergency_access_grant "
                    + "(organization_id, subject, facility_code, purpose, reason, role_key, "
                    + "granted_at, expires_at) VALUES ('" + ORG_A + "', 'clinician-expired', '" + FACILITY
                    + "', 'EMERGENCY_ACCESS', 'stale grant', 'clinician', "
                    + "now() - interval '2 hours', now() - interval '1 hour')");
        }

        ResponseEntity<String> decision = active("clinician-expired", ORG_A);
        assertEquals(HttpStatus.OK, decision.getStatusCode());
        assertEquals("false", field(decision.getBody(), "allowed"));
        assertEquals("EXPIRED", field(decision.getBody(), "reason"));
        assertFalse(decision.getBody().contains("\"allowed\":true"));
    }

    /** A missing justification is refused before any grant is created. */
    @Test
    void grantWithoutAReasonIsRefused() throws Exception {
        ResponseEntity<String> refused = grant("clinician-reason", ORG_A, "  ", 10);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.emergency_access_grant "
                + "WHERE subject = 'clinician-reason'"));
    }
}
