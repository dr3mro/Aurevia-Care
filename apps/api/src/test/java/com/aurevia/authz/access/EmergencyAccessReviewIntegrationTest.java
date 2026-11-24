package com.aurevia.authz.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Real-PostgreSQL acceptance for the IAM-06 review slice (V88). Exercises the
 * HTTP entry point so the grant, its privacy-auditor alert row and the open
 * review are created together, the review is closed with an explicit outcome,
 * and forced row-level security keeps the review inside its own organization.
 * Only synthetic identifiers and reasons are used.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_access_review_it")
@ActiveProfiles("synthetic-auth")
class EmergencyAccessReviewIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_access_review_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private RestClient rest;

    /** Start the HTTP client for the random test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Apply every migration, including V88, to a fresh synthetic database and seed two tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_access_review_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_access_review_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_A, "clinician-legacy", "clinician");
            seed(statement, ORG_A, "clinician-unknown", "clinician");
            seed(statement, ORG_A, "clinician-tenant", "clinician");
            seed(statement, ORG_A, "clinician-expired", "clinician");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "auditor-b", "privacy_auditor");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Alpha Main')");
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_access_review_it WITH (FORCE)");
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

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", "review-it-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> grant(String subject, String reason) {
        return request(HttpMethod.POST, "/api/v1/access/emergency",
                "{\"facilityCode\":\"MAIN\",\"reason\":\"" + reason + "\",\"durationMinutes\":10}",
                subject, ORG_A);
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

    private String createGrant(String subject) {
        ResponseEntity<String> created = grant(subject, "patient in distress");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    private void assertReview(String organization, String grantId, String expectedStatus,
                              String expectedOutcome, String expectedReviewer) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT status, outcome, reviewer, note, closed_at FROM public.emergency_access_review "
                    + "WHERE grant_id::text = '" + grantId + "'")) {
                assertTrue(result.next(), "one review row must exist for the grant in the active organization");
                assertEquals(expectedStatus, result.getString("status"));
                assertEquals(expectedOutcome, result.getString("outcome"));
                assertEquals(expectedReviewer, result.getString("reviewer"));
                if ("closed".equals(expectedStatus)) {
                    assertNotNull(result.getTimestamp("closed_at"));
                    assertEquals("checked", result.getString("note"));
                } else {
                    assertNull(result.getTimestamp("closed_at"));
                }
            }
        }
    }

    @Test
    void grantOpensAPrivacyAuditorReviewAndAnIndependentReviewClosesIt() throws Exception {
        String id = createGrant("clinician-a");
        assertReview(ORG_A, id, "open", null, null);

        ResponseEntity<String> reviewed = review(id, "{\"note\":\"checked\",\"outcome\":\"concerns_noted\"}",
                "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertEquals("reviewed", field(reviewed.getBody(), "status"));
        assertReview(ORG_A, id, "closed", "concerns_noted", "auditor-a");
    }

    @Test
    void legacyReviewBodyWithoutAnOutcomeIsClosedAsRecorded() throws Exception {
        String id = createGrant("clinician-legacy");

        ResponseEntity<String> reviewed = review(id, "{\"note\":\"checked\"}", "auditor-a", ORG_A);

        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertReview(ORG_A, id, "closed", "recorded", "auditor-a");
    }

    @Test
    void anUnknownOutcomeIsRefusedAndLeavesTheReviewOpen() throws Exception {
        String id = createGrant("clinician-unknown");

        ResponseEntity<String> refused = review(id,
                "{\"note\":\"checked\",\"outcome\":\"unknown\"}", "auditor-a", ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertReview(ORG_A, id, "open", null, null);
    }

    @Test
    void anotherTenantCannotSeeOrCloseTheReview() throws Exception {
        String id = createGrant("clinician-tenant");

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.emergency_access_review WHERE grant_id::text = '" + id + "'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
        assertEquals(HttpStatus.NOT_FOUND,
                review(id, "{\"note\":\"checked\"}", "auditor-b", ORG_B).getStatusCode());
        // The denied cross-tenant command must not have closed or altered the
        // owner organization's review row; only an in-org reviewer may close it.
        assertReview(ORG_A, id, "open", null, null);
    }

    @Test
    void expiredGrantAndMissingReasonAreDeniedOnTheNextRequest() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.emergency_access_grant "
                    + "(organization_id, subject, facility_code, purpose, reason, role_key, "
                    + "granted_at, expires_at) VALUES ('" + ORG_A + "', 'clinician-expired', 'MAIN', "
                    + "'EMERGENCY_ACCESS', 'stale grant', 'clinician', now() - interval '2 hours', "
                    + "now() - interval '1 hour')");
        }
        ResponseEntity<String> active = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-expired", ORG_A);
        assertEquals(HttpStatus.OK, active.getStatusCode());
        assertTrue(active.getBody().contains("EXPIRED"));
        assertFalse(active.getBody().contains("\"allowed\":true"));

        assertEquals(422, grant("clinician-expired", "  ").getStatusCode().value());
    }
}
