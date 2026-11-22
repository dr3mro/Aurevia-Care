package com.aurevia.smartclinic.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * IAM-05 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>A site-verified enrollment and a single-use, expiring invitation create a
 * verified chart link for the patient account; re-verification after lost
 * access or a dispute issues a fresh invitation while retiring the old one, and
 * unlinking ends access on the next protected request. Negative tests show the
 * rule refusing a wrong-role or unauthenticated caller, an expired or replayed
 * token and an invitation owned by another tenant. The durable, append-only
 * records are read back under forced row-level security after each flow.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam05_clause1_it")
@ActiveProfiles("synthetic-auth")
class PatientIdentityProofingUsesSiteVerifiedEnrollmentTest {

    static final String ORG = "11111111-1111-1111-1111-111111111111";
    static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam05_clause1_it";
    static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam05_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam05_clause1_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG, "admin-a", "tenant_admin");
            seedStaff(statement, ORG, "registrar-a", "reception");
            seedStaff(statement, OTHER_ORG, "admin-b", "tenant_admin");
            statement.execute("SET app.current_org = '" + ORG + "'");
            statement.execute("SET app.actor = 'admin-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                    + "VALUES ('" + ORG + "', '" + CHART + "', 1, 'IDENT-IAM05', 'ISSUER', 'Org A chart', "
                    + "'org a chart', '1990-01-01', "
                    + "(SELECT id FROM public.user_identity WHERE organization_id = '" + ORG
                    + "' AND subject = 'admin-a'))");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam05_clause1_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic member with an active membership and a bounded role in an organization. */
    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
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

    /** Build the synthetic authentication and tenant headers for one request. */
    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.set("X-Correlation-Id", "iam05-clause1-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Send one HTTP command through the normal REST entry point. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Read one scalar field from a small JSON response body. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        assertTrue(start >= 0, "response must contain " + key + ": " + json);
        start += needle.length();
        int end = json.indexOf('"', start + 1);
        return json.substring(start + 1, end);
    }

    /** Enroll a site-verified patient account as the given staff subject. */
    private ResponseEntity<String> enroll(String subject, String organization, String staff) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/accounts",
                "{\"subject\":\"" + subject + "\",\"displayName\":\"Synthetic Patient\"}", staff, organization);
    }

    /** Issue a single-use invitation for the synthetic chart and return its raw token. */
    private String issue(String subject, String organization, String staff) {
        ResponseEntity<String> issued = request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"purpose\":\"LINK\",\"ttlSeconds\":600}", staff, organization);
        assertEquals(HttpStatus.CREATED, issued.getStatusCode(), issued.getBody());
        return field(issued.getBody(), "token");
    }

    /** Redeem an invitation as the patient account it was issued for. */
    private ResponseEntity<String> claim(String token, String subject, String organization) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + token + "\"}", subject, organization);
    }

    /** Read the current verified-access decision for a patient account. */
    private String access(String subject, String organization) {
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART,
                null, subject, organization);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response.getBody();
    }

    /** Unlink a patient account from the synthetic chart. */
    private ResponseEntity<String> unlink(String subject, String organization, String actor) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"reason\":\"disputed identity\"}", actor, organization);
    }

    /** Ask a staff subject to re-verify a relationship after lost access. */
    private ResponseEntity<String> reverify(String subject, String organization, String staff) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/links/reverify",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"reason\":\"lost access\"}", staff, organization);
    }

    /** Count durable rows under the forced row-level security of one tenant. */
    private int countWithOrg(String organization, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /** Move an unspent invitation into the past using the database clock. */
    private void expireInvitation(String token) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement();
             PreparedStatement expire = connection.prepareStatement(
                     "UPDATE public.account_invitation "
                     + "SET created_at = now() - interval '10 minutes', "
                     + "expires_at = now() - interval '1 minute' "
                     + "WHERE token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')")) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            expire.setString(1, token);
            assertEquals(1, expire.executeUpdate());
        }
    }

    /** Keep subjects unique so repeated test runs in the same database cannot collide. */
    private static String uniqueSubject(String base) {
        return base + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void siteVerifiedEnrollmentSingleUseInvitationAndReverificationProveTheRule() throws Exception {
        String subject = uniqueSubject("iam05-proof");
        assertEquals(HttpStatus.CREATED, enroll(subject, ORG, "registrar-a").getStatusCode());

        String token = issue(subject, ORG, "registrar-a");
        ResponseEntity<String> linked = claim(token, subject, ORG);
        assertEquals(HttpStatus.OK, linked.getStatusCode(), linked.getBody());
        assertEquals("linked", field(linked.getBody(), "event"));
        assertEquals("true", access(subject, ORG));

        assertEquals(HttpStatus.CONFLICT, claim(token, subject, ORG).getStatusCode(),
                "a redeemed token must be single-use");
        assertEquals("true", access(subject, ORG));

        ResponseEntity<String> unlinked = unlink(subject, ORG, subject);
        assertEquals(HttpStatus.OK, unlinked.getStatusCode(), unlinked.getBody());
        assertEquals("unlinked", field(unlinked.getBody(), "event"));
        assertEquals("false", access(subject, ORG), "unlinking ends future chart access immediately");

        ResponseEntity<String> reverified = reverify(subject, ORG, "admin-a");
        assertEquals(HttpStatus.OK, reverified.getStatusCode(), reverified.getBody());
        String fresh = field(reverified.getBody(), "token");
        assertFalse(fresh.isBlank(), "re-verification must issue a fresh invitation");
        assertEquals(HttpStatus.CONFLICT, claim(token, subject, ORG).getStatusCode(),
                "the stale invitation must be retired by re-verification");
        assertEquals(HttpStatus.OK, claim(fresh, subject, ORG).getStatusCode());
        assertEquals("true", access(subject, ORG), "only the fresh invitation restores access");

        assertEquals(1, countWithOrg(ORG,
                "SELECT count(*) FROM public.patient_account WHERE subject = '" + subject + "'"));
        assertEquals(2, countWithOrg(ORG,
                "SELECT count(*) FROM public.account_chart_link l "
                + "JOIN public.patient_account a ON a.organization_id = l.organization_id AND a.id = l.account_id "
                + "WHERE a.subject = '" + subject + "' AND l.event = 'linked'"));
        assertEquals(2, countWithOrg(ORG,
                "SELECT count(*) FROM public.account_chart_link l "
                + "JOIN public.patient_account a ON a.organization_id = l.organization_id AND a.id = l.account_id "
                + "WHERE a.subject = '" + subject + "' AND l.event = 'unlinked'"),
                "the patient unlink and the re-verification both append an unlinked event");
        assertEquals(0, countWithOrg(OTHER_ORG,
                "SELECT count(*) FROM public.patient_account WHERE subject = '" + subject + "'"));
    }

    @Test
    void receptionAndUnverifiedCallersAreRefusedProtectedIdentityCommands() {
        String subject = uniqueSubject("iam05-role");
        assertEquals(HttpStatus.CREATED, enroll(subject, ORG, "registrar-a").getStatusCode());
        assertEquals(HttpStatus.OK, claim(issue(subject, ORG, "registrar-a"), subject, ORG).getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN, reverify(subject, ORG, "registrar-a").getStatusCode(),
                "reception may enrol and invite but must not re-verify");
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.GET,
                        "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART,
                        null, "registrar-a", ORG).getStatusCode(),
                "reception must not read another account's verified access");
        assertEquals(HttpStatus.FORBIDDEN,
                enroll(uniqueSubject("iam05-nostaff"), ORG, "unknown-staff").getStatusCode(),
                "a caller without active membership must not enrol an account");
    }

    @Test
    void anExpiredInvitationCannotRestoreAccess() throws Exception {
        String subject = uniqueSubject("iam05-expire");
        assertEquals(HttpStatus.CREATED, enroll(subject, ORG, "registrar-a").getStatusCode());
        String token = issue(subject, ORG, "registrar-a");
        assertEquals("false", access(subject, ORG), "an unspent invitation is not access");

        expireInvitation(token);
        assertEquals(HttpStatus.CONFLICT, claim(token, subject, ORG).getStatusCode());
        assertEquals("false", access(subject, ORG), "an expired invitation must never grant access");
    }

    @Test
    void anInvitationIssuedInAnotherTenantCannotBeClaimed() {
        String subject = uniqueSubject("iam05-tenant");
        assertEquals(HttpStatus.CREATED, enroll(subject, ORG, "registrar-a").getStatusCode());
        assertEquals(HttpStatus.CREATED, enroll(subject, OTHER_ORG, "admin-b").getStatusCode());
        String token = issue(subject, ORG, "registrar-a");

        assertEquals(HttpStatus.NOT_FOUND, claim(token, subject, OTHER_ORG).getStatusCode(),
                "forced RLS conceals another tenant's invitation");
        assertEquals("false", access(subject, OTHER_ORG));
    }
}
