package com.aurevia.smartclinic.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * IAM-05 clause 3 proof through the real HTTP entry point: unlinking ends
 * future account access for the affected chart on the next protected request.
 *
 * <p>The assertion is an HTTP GET of the access endpoint after the unlink
 * command, so it exercises the append-only link event, the database current-link
 * fold and the injected domain service together. A negative case proves that an
 * unauthorised actor cannot end a verified relationship. All fixtures are
 * synthetic; the dedicated database is created and dropped by this class.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_unlink_clause_it")
@ActiveProfiles("synthetic-auth")
class UnlinkingImmediatelyEndsFutureAccountAccessAffectedTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHART_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_unlink_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private RestClient rest;

    /** Create an isolated database and apply the production migrations once. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_unlink_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_unlink_clause_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            seedStaff(statement, "admin-a", "tenant_admin");
            seedStaff(statement, "registrar-a", "reception");
            seedChart(statement, CHART_A, "CLAUSE-A");
            seedChart(statement, CHART_B, "CLAUSE-B");
        }
    }

    /** Remove the dedicated database so the next run starts from a clean schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_unlink_clause_it WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedStaff(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + ORG + "', 'Clause org' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + ORG + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedChart(Statement statement, String id, String mrn) throws Exception {
        statement.execute("SET app.actor = 'registrar-a'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                + "VALUES ('" + ORG + "', '" + id + "', 1, '" + mrn + "', 'ISSUER', "
                + "'Clause chart', 'clause chart', '1990-01-01', "
                + "(SELECT id FROM public.user_identity WHERE organization_id = '" + ORG
                + "' AND subject = 'registrar-a'))");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                            String subject) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        headers.set("X-Active-Organization", ORG);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> enroll(String subject) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/accounts",
                "{\"subject\":\"" + subject + "\",\"displayName\":\"Clause Patient\"}", "registrar-a");
    }

    private void issueAndClaim(String subject, String chartId) {
        ResponseEntity<String> issue = request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + chartId
                        + "\",\"purpose\":\"LINK\",\"ttlSeconds\":600}", "registrar-a");
        assertEquals(HttpStatus.CREATED, issue.getStatusCode(), issue.getBody());
        ResponseEntity<String> claim = request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + field(issue.getBody(), "token") + "\"}", subject);
        assertEquals(HttpStatus.OK, claim.getStatusCode(), claim.getBody());
    }

    private String access(String subject, String chartId) {
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + chartId,
                null, subject);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response.getBody();
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

    private static String uniqueSubject(String base) {
        return base + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void unlinkEndsFutureAccessOnTheNextRequestForTheAffectedChart() {
        String subject = uniqueSubject("unlinkclause");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());
        issueAndClaim(subject, CHART_A);

        assertEquals("true", access(subject, CHART_A), "verified link grants access before unlink");

        ResponseEntity<String> unlink = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_A
                        + "\",\"reason\":\"patient request\"}", subject);
        assertEquals(HttpStatus.OK, unlink.getStatusCode(), unlink.getBody());
        assertEquals("unlinked", field(unlink.getBody(), "event"));

        assertEquals("false", access(subject, CHART_A),
                "the next protected request after unlink must be denied");

        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject);
        assertTrue(links.getBody().contains("\"event\":\"unlinked\""));
        assertTrue(links.getBody().contains("\"method\":\"PATIENT_REQUEST\""));
    }

    @Test
    void unlinkLeavesOtherVerifiedChartsAccessible() {
        String subject = uniqueSubject("siblingclause");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());
        issueAndClaim(subject, CHART_A);
        issueAndClaim(subject, CHART_B);

        ResponseEntity<String> unlink = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_A
                        + "\",\"reason\":\"patient request\"}", subject);
        assertEquals(HttpStatus.OK, unlink.getStatusCode(), unlink.getBody());

        assertEquals("false", access(subject, CHART_A), "the unlinked chart loses access");
        assertEquals("true", access(subject, CHART_B), "another independently verified chart keeps access");
    }

    @Test
    void aReceptionStaffCannotUnlinkAndAccessContinues() {
        String subject = uniqueSubject("badrole");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());
        issueAndClaim(subject, CHART_A);

        // LINK_UNLINK is privileged; reception may issue invitations but may not revoke a link.
        ResponseEntity<String> unlink = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_A
                        + "\",\"reason\":\"unauthorised\"}", "registrar-a");
        assertEquals(HttpStatus.FORBIDDEN, unlink.getStatusCode(), unlink.getBody());

        assertEquals("true", access(subject, CHART_A),
                "a rejected unlink must not end the verified relationship");
        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject);
        assertTrue(links.getBody().contains("\"event\":\"linked\""));
        assertFalse(links.getBody().contains("\"event\":\"unlinked\""));
    }

    @Test
    void unlinkOfANeverLinkedChartIsRefusedWithoutCreatingAccess() {
        String subject = uniqueSubject("forbiddenstate");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());

        // CHART_B was never linked to this account: the forbidden state is
        // refused, and the refusal must not manufacture access to that chart.
        ResponseEntity<String> unlink = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_B
                        + "\",\"reason\":\"no relationship\"}", subject);
        assertEquals(HttpStatus.NOT_FOUND, unlink.getStatusCode(), unlink.getBody());
        assertEquals("LINK_NOT_FOUND", field(unlink.getBody(), "code"));
        assertEquals("false", access(subject, CHART_B));
    }
}
