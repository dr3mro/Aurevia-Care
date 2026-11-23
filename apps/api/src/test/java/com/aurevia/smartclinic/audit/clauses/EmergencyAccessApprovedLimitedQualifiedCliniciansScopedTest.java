package com.aurevia.smartclinic.audit.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * IAM-06 clause 2 acceptance against real PostgreSQL with synthetic identities.
 *
 * <p>The clause requires approved emergency access to be confined to qualified
 * clinicians inside their own organization, time-limited, reasoned, alerted and
 * reviewed. The positive test drives the HTTP entry point and asserts the grant,
 * its alert, the durable privacy-auditor review row and its independent closure.
 * The negative tests prove the rule refuses a wrong role, a foreign tenant, an
 * unapproved site, invalid input and an already-active grant. Each test uses its
 * own synthetic subjects and correlation id so shared-database audit assertions
 * cannot be polluted by another test. All fixtures are synthetic; no production
 * code, migration, contract or existing test is changed by this suite.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam06_clause2_it")
@ActiveProfiles("synthetic-auth")
class EmergencyAccessApprovedLimitedQualifiedCliniciansScopedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam06_clause2_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String FACILITY = "MAIN";
    private static final String PURPOSE = "EMERGENCY_ACCESS";
    private static final String CORR_POS = "iam06-c2-positive";
    private static final String CORR_ROLE = "iam06-c2-role";
    private static final String CORR_CROSS = "iam06-c2-cross";
    private static final String CORR_SITE = "iam06-c2-site";
    private static final String CORR_INPUT = "iam06-c2-input";
    private static final String CORR_STATE = "iam06-c2-state";
    private static final String REASON = "patient in distress";

    private RestClient rest;

    /** Start the HTTP client against the randomly assigned application port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Migrate a fresh database and seed approved, unapproved and foreign tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam06_clause2_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam06_clause2_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            // clinician-tenant belongs to both Org A and Org B so the cross-tenant
            // NO_GRANT assertion proves RLS concealment, not a missing membership.
            seedStaff(statement, ORG_A, "clinician-a", "clinician");
            seedStaff(statement, ORG_A, "clinician-tenant", "clinician");
            seedStaff(statement, ORG_A, "clinician-stacked", "clinician");
            seedStaff(statement, ORG_A, "clinician-expired", "clinician");
            seedStaff(statement, ORG_A, "clinician-input", "clinician");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedStaff(statement, ORG_B, "clinician-tenant", "clinician");
            seedStaff(statement, ORG_B, "clinician-b", "clinician");
            seedStaff(statement, ORG_B, "auditor-b", "privacy_auditor");
            // Org C has a qualified clinician but no approved site configuration.
            seedStaff(statement, ORG_C, "clinician-c", "clinician");

            for (String organization : new String[] { ORG_A, ORG_B, ORG_C }) {
                statement.execute("SET app.current_org = '" + organization + "'");
                statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                        + "VALUES ('" + organization + "', '" + FACILITY + "', 'Main')");
            }
            seedApprovedSite(statement, ORG_A, "auditor-a");
            seedApprovedSite(statement, ORG_B, "auditor-b");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam06_clause2_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic member with an active membership and role assignment. */
    private static void seedStaff(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + organization + "', 'Org " + organization + "' WHERE NOT EXISTS "
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

    /** Approve the emergency-access configuration flag for one synthetic tenant. */
    private static void seedApprovedSite(Statement statement, String organization, String approver)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + approver + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                + "VALUES ('" + organization + "', 'access.emergency.enabled', 1, 'true')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + organization + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = 'access.emergency.enabled' AND version = 1");
    }

    /** Build the synthetic authentication headers understood by the test profile. */
    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Send one HTTP request, capturing the body even on an error status. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Post an emergency grant command through the HTTP entry point. */
    private ResponseEntity<String> grant(String subject, String reason, int durationMinutes,
                                         String organization, String correlation) {
        String body = "{\"facilityCode\":\"" + FACILITY + "\",\"reason\":\"" + reason
                + "\",\"durationMinutes\":" + durationMinutes + "}";
        return request(HttpMethod.POST, "/api/v1/access/emergency", body, subject, organization, correlation);
    }

    /** Read one JSON scalar from a response body without a test-only dependency. */
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

    /** Count rows visible under the given tenant from a synthetic table. */
    private static int count(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /** Insert a grant directly to set up an expired or an already-active state. */
    private static String insertGrantRow(String organization, String subject,
                                         String grantedOffset, String expiresOffset) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            statement.execute("SET app.actor = '" + subject + "'");
            try (ResultSet result = statement.executeQuery(
                    "INSERT INTO public.emergency_access_grant "
                    + "(organization_id, subject, facility_code, purpose, reason, role_key, "
                    + "granted_at, expires_at) VALUES ('" + organization + "', '" + subject + "', '"
                    + FACILITY + "', '" + PURPOSE + "', 'seeded state', 'clinician', now() "
                    + grantedOffset + ", now() " + expiresOffset + ") RETURNING id")) {
                assertTrue(result.next());
                return result.getString("id");
            }
        }
    }

    /** Assert the durable privacy-auditor review that must accompany every grant. */
    private static void assertReview(String organization, String grantId, String expectedSubject,
                                     String expectedStatus, String expectedOutcome) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT recipient_role, status, outcome, reviewer, note, closed_at, subject "
                    + "FROM public.emergency_access_review WHERE grant_id::text = '" + grantId + "'")) {
                assertTrue(result.next(), "one review row must exist for the grant in the active organization");
                assertEquals("privacy_auditor", result.getString("recipient_role"));
                assertEquals(expectedSubject, result.getString("subject"));
                assertEquals(expectedStatus, result.getString("status"));
                assertEquals(expectedOutcome, result.getString("outcome"));
                if ("open".equals(expectedStatus)) {
                    assertNull(result.getString("reviewer"));
                    assertNull(result.getString("note"));
                    assertNull(result.getTimestamp("closed_at"));
                } else {
                    assertEquals("auditor-a", result.getString("reviewer"));
                    assertEquals("checked", result.getString("note"));
                    assertNotNull(result.getTimestamp("closed_at"));
                }
            }
        }
    }

    @Test
    void approvedQualifiedClinicianOpensOwnOrganizationReasonedTimeLimitedAlertedReviewedGrant()
            throws Exception {
        ResponseEntity<String> created = grant("clinician-a", REASON, 10, ORG_A, CORR_POS);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(id.isEmpty(), created.getBody());
        assertEquals("/api/v1/access/emergency/" + id, created.getHeaders().getLocation().toString());
        assertEquals("clinician-a", field(created.getBody(), "subject"));
        assertEquals(FACILITY, field(created.getBody(), "facilityCode"));
        assertEquals("clinician", field(created.getBody(), "roleKey"));
        assertEquals(REASON, field(created.getBody(), "reason"));
        assertEquals("active", field(created.getBody(), "status"));
        long lifetimeSeconds = Duration.between(
                Instant.parse(field(created.getBody(), "grantedAt")),
                Instant.parse(field(created.getBody(), "expiresAt"))).getSeconds();
        assertEquals(600L, lifetimeSeconds, "the grant must honour the bounded ten-minute lifetime");

        ResponseEntity<String> active = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-a", ORG_A, CORR_POS);
        assertEquals(HttpStatus.OK, active.getStatusCode(), active.getBody());
        assertTrue(active.getBody().contains("\"allowed\":true"), active.getBody());
        assertEquals("ACTIVE", field(active.getBody(), "reason"));

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet row = statement.executeQuery(
                    "SELECT organization_id::text, purpose, reason, role_key, facility_code, "
                    + "revoked_at, reviewed_at FROM public.emergency_access_grant "
                    + "WHERE id::text = '" + id + "'")) {
                assertTrue(row.next());
                assertEquals(ORG_A, row.getString("organization_id"));
                assertEquals(PURPOSE, row.getString("purpose"));
                assertEquals(REASON, row.getString("reason"));
                assertEquals("clinician", row.getString("role_key"));
                assertEquals(FACILITY, row.getString("facility_code"));
                assertNull(row.getTimestamp("revoked_at"));
                assertNull(row.getTimestamp("reviewed_at"));
            }
            assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'EMERGENCY_GRANT' AND resource_type = 'EMERGENCY_ACCESS_GRANT' "
                    + "AND resource_id = '" + id + "' AND scope = 'clinician:MAIN' "
                    + "AND correlation_id = '" + CORR_POS + "' AND outcome = 'SUCCESS'"));
            assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'EMERGENCY_ALERT' AND resource_type = 'EMERGENCY_ACCESS_GRANT' "
                    + "AND resource_id = '" + id + "' AND scope = 'clinician:MAIN' "
                    + "AND purpose = '" + PURPOSE + "' AND correlation_id = '" + CORR_POS + "' "
                    + "AND outcome = 'SUCCESS'"));
            assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'EMERGENCY_REVIEW_OPEN' AND resource_type = 'EMERGENCY_ACCESS_REVIEW' "
                    + "AND resource_id = '" + id + "' AND scope = 'privacy_auditor:MAIN' "
                    + "AND correlation_id = '" + CORR_POS + "' AND outcome = 'SUCCESS'"));
        }
        assertReview(ORG_A, id, "clinician-a", "open", null);

        // The alert is also independently reviewed: the grantee may not review
        // their own grant, and a privacy auditor closes it with an explicit outcome.
        ResponseEntity<String> selfReview = request(HttpMethod.POST,
                "/api/v1/access/emergency/" + id + "/review",
                "{\"note\":\"checked\",\"outcome\":\"approved\"}",
                "clinician-a", ORG_A, CORR_POS);
        assertEquals(HttpStatus.FORBIDDEN, selfReview.getStatusCode(), selfReview.getBody());
        assertEquals("FORBIDDEN", field(selfReview.getBody(), "code"), selfReview.getBody());
        assertReview(ORG_A, id, "clinician-a", "open", null);

        ResponseEntity<String> reviewed = request(HttpMethod.POST,
                "/api/v1/access/emergency/" + id + "/review",
                "{\"note\":\"checked\",\"outcome\":\"concerns_noted\"}",
                "auditor-a", ORG_A, CORR_POS);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertEquals("reviewed", field(reviewed.getBody(), "status"));
        assertReview(ORG_A, id, "clinician-a", "closed", "concerns_noted");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_REVIEW' AND resource_id = '" + id + "' "
                + "AND scope = 'privacy_auditor:MAIN' AND correlation_id = '" + CORR_POS + "' "
                + "AND outcome = 'SUCCESS'"));
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet row = statement.executeQuery(
                    "SELECT reviewed_by FROM public.emergency_access_grant WHERE id::text = '" + id + "'")) {
                assertTrue(row.next());
                assertEquals("auditor-a", row.getString("reviewed_by"));
            }
        }
    }

    @Test
    void wrongRoleIsRefused() throws Exception {
        for (String subject : new String[] { "reception-a", "admin-a" }) {
            ResponseEntity<String> denied = grant(subject, REASON, 10, ORG_A, CORR_ROLE);
            assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
            assertEquals("FORBIDDEN", field(denied.getBody(), "code"), denied.getBody());
            assertTrue(denied.getBody().contains("missing action permission"), denied.getBody());
            assertEquals(0, count(ORG_A,
                    "SELECT count(*) FROM public.emergency_access_grant WHERE subject = '" + subject + "'"));
            assertEquals(0, count(ORG_A,
                    "SELECT count(*) FROM public.emergency_access_review WHERE subject = '" + subject + "'"));
        }
        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_GRANT' AND scope = 'clinician:MAIN' "
                + "AND resource_id = 'MAIN' AND correlation_id = '" + CORR_ROLE + "' "
                + "AND outcome = 'DENIED'"));
    }

    @Test
    void anotherTenantCannotGrantOrSeeTheGrant() throws Exception {
        // A clinician who belongs only to Org B cannot grant access inside Org A.
        ResponseEntity<String> foreignGrant = grant("clinician-b", REASON, 10, ORG_A, CORR_CROSS);
        assertEquals(HttpStatus.FORBIDDEN, foreignGrant.getStatusCode(), foreignGrant.getBody());
        assertEquals("FORBIDDEN", field(foreignGrant.getBody(), "code"));
        assertEquals(0, count(ORG_A,
                "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'clinician-b'"));

        ResponseEntity<String> created = grant("clinician-tenant", REASON, 10, ORG_A, CORR_CROSS);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(id.isEmpty(), created.getBody());

        // clinician-tenant is an active clinician in Org B too, so a NO_GRANT
        // decision there proves the Org A grant is concealed by forced RLS.
        assertEquals(1, count(ORG_B, "SELECT count(*) FROM public.membership m "
                + "JOIN public.user_identity u ON u.id = m.user_id "
                + "WHERE u.subject = 'clinician-tenant' AND m.status = 'active'"));
        ResponseEntity<String> fromOtherTenant = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-tenant", ORG_B, CORR_CROSS);
        assertEquals(HttpStatus.OK, fromOtherTenant.getStatusCode(), fromOtherTenant.getBody());
        assertEquals("NO_GRANT", field(fromOtherTenant.getBody(), "reason"));
        assertFalse(fromOtherTenant.getBody().contains("\"allowed\":true"), fromOtherTenant.getBody());

        ResponseEntity<String> ownerQueue = request(HttpMethod.GET, "/api/v1/access/emergency/review-queue",
                null, "auditor-a", ORG_A, CORR_CROSS);
        assertEquals(HttpStatus.OK, ownerQueue.getStatusCode(), ownerQueue.getBody());
        assertTrue(ownerQueue.getBody().contains(id), ownerQueue.getBody());
        ResponseEntity<String> foreignQueue = request(HttpMethod.GET, "/api/v1/access/emergency/review-queue",
                null, "auditor-b", ORG_B, CORR_CROSS);
        assertEquals(HttpStatus.OK, foreignQueue.getStatusCode(), foreignQueue.getBody());
        assertFalse(foreignQueue.getBody().contains(id), foreignQueue.getBody());

        // A foreign privacy auditor cannot close the owner's review under forced RLS.
        ResponseEntity<String> foreignReview = request(HttpMethod.POST,
                "/api/v1/access/emergency/" + id + "/review",
                "{\"note\":\"checked\",\"outcome\":\"approved\"}",
                "auditor-b", ORG_B, CORR_CROSS);
        assertEquals(HttpStatus.NOT_FOUND, foreignReview.getStatusCode(), foreignReview.getBody());
        assertEquals("NOT_FOUND", field(foreignReview.getBody(), "code"));

        assertEquals(1, count(ORG_A,
                "SELECT count(*) FROM public.emergency_access_grant WHERE id::text = '" + id + "'"));
        assertEquals(0, count(ORG_B,
                "SELECT count(*) FROM public.emergency_access_grant WHERE id::text = '" + id + "'"));
        assertEquals(0, count(ORG_B,
                "SELECT count(*) FROM public.emergency_access_review WHERE grant_id::text = '" + id + "'"));
        assertEquals(1, count(ORG_B, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_REVIEW' AND resource_id = '" + id + "' "
                + "AND correlation_id = '" + CORR_CROSS + "' AND outcome = 'NOT_FOUND'"));
        assertReview(ORG_A, id, "clinician-tenant", "open", null);
    }

    @Test
    void unapprovedSiteIsRefused() throws Exception {
        assertEquals(0, count(ORG_C,
                "SELECT count(*) FROM public.effective_config('access.emergency.enabled')"));
        ResponseEntity<String> denied = grant("clinician-c", REASON, 10, ORG_C, CORR_SITE);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"), denied.getBody());
        assertTrue(denied.getBody().contains("not approved for this site"), denied.getBody());
        assertEquals(0, count(ORG_C,
                "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'clinician-c'"));
        assertEquals(1, count(ORG_C, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_GRANT' AND scope = 'clinician:MAIN' "
                + "AND resource_id = 'MAIN' AND correlation_id = '" + CORR_SITE + "' "
                + "AND outcome = 'DENIED'"));
    }

    @Test
    void missingReasonOrOutOfRangeDurationIsRefused() throws Exception {
        ResponseEntity<String> noReason = request(HttpMethod.POST, "/api/v1/access/emergency",
                "{\"facilityCode\":\"" + FACILITY + "\",\"durationMinutes\":10}",
                "clinician-input", ORG_A, CORR_INPUT);
        assertEquals(422, noReason.getStatusCode().value(), noReason.getBody());
        assertEquals("INVALID_REASON", field(noReason.getBody(), "code"), noReason.getBody());

        ResponseEntity<String> blankReason = grant("clinician-input", "   ", 10, ORG_A, CORR_INPUT);
        assertEquals(422, blankReason.getStatusCode().value(), blankReason.getBody());
        assertEquals("INVALID_REASON", field(blankReason.getBody(), "code"), blankReason.getBody());

        ResponseEntity<String> tooShort = grant("clinician-input", REASON, 0, ORG_A, CORR_INPUT);
        assertEquals(422, tooShort.getStatusCode().value(), tooShort.getBody());
        assertEquals("INVALID_DURATION", field(tooShort.getBody(), "code"), tooShort.getBody());

        ResponseEntity<String> tooLong = grant("clinician-input", REASON, 61, ORG_A, CORR_INPUT);
        assertEquals(422, tooLong.getStatusCode().value(), tooLong.getBody());
        assertEquals("INVALID_DURATION", field(tooLong.getBody(), "code"), tooLong.getBody());

        assertEquals(0, count(ORG_A,
                "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'clinician-input'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE correlation_id = '" + CORR_INPUT + "'"));

        // An otherwise identical command with valid input is accepted, so the four
        // refusals above are caused by the missing reason and out-of-range lifetime.
        assertEquals(HttpStatus.CREATED,
                grant("clinician-input", REASON, 10, ORG_A, CORR_INPUT).getStatusCode());
    }

    @Test
    void expiredAndStackedGrantsAreRefused() throws Exception {
        String expiredId = insertGrantRow(ORG_A, "clinician-expired",
                " - interval '2 hours'", " - interval '1 hour'");
        ResponseEntity<String> active = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-expired", ORG_A, CORR_STATE);
        assertEquals(HttpStatus.OK, active.getStatusCode(), active.getBody());
        assertFalse(field(active.getBody(), "allowed").equals("true"), active.getBody());
        assertEquals("EXPIRED", field(active.getBody(), "reason"), active.getBody());
        assertEquals(1, count(ORG_A,
                "SELECT count(*) FROM public.emergency_access_grant WHERE id::text = '" + expiredId + "'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_ACTIVE_CHECK' AND resource_id = '" + expiredId + "' "
                + "AND correlation_id = '" + CORR_STATE + "' AND outcome = 'DENIED'"));

        // An expired grant is not usable, but it does not block a fresh, bounded grant.
        ResponseEntity<String> regranted = grant("clinician-expired", REASON, 10, ORG_A, CORR_STATE);
        assertEquals(HttpStatus.CREATED, regranted.getStatusCode(), regranted.getBody());
        assertNotEquals(expiredId, field(regranted.getBody(), "id"));

        String stackedId = insertGrantRow(ORG_A, "clinician-stacked", "", " + interval '10 minutes'");
        ResponseEntity<String> conflict = grant("clinician-stacked", REASON, 10, ORG_A, CORR_STATE);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("CONFLICT", field(conflict.getBody(), "code"), conflict.getBody());
        assertEquals(1, count(ORG_A,
                "SELECT count(*) FROM public.emergency_access_grant WHERE subject = 'clinician-stacked'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'EMERGENCY_GRANT' AND resource_id = '" + stackedId + "' "
                + "AND correlation_id = '" + CORR_STATE + "' AND outcome = 'CONFLICT'"));
    }
}
