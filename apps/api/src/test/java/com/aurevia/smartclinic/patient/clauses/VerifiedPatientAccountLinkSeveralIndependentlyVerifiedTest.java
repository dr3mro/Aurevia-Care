package com.aurevia.smartclinic.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
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
 * IAM-05 clause 2 acceptance: a verified patient account may link to several
 * independently verified charts (real PostgreSQL, synthetic fixtures only).
 *
 * <p>The rule is exercised through the normal HTTP entry points under
 * {@code /api/v1/patient-identity}. Each chart requires its own single-use,
 * expiring invitation, so several links prove several independent verifications
 * rather than one credential reused. The durable rows are read back through the
 * links and access endpoints under forced row-level security. The negative tests
 * show that a chart absent from the active tenant and another account are
 * refused, so the clause grants a capability to the verified account alone and
 * only for charts verified in that tenant.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam05_clause2_it")
@ActiveProfiles("synthetic-auth")
class VerifiedPatientAccountLinkSeveralIndependentlyVerifiedTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String CHART_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String CHART_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String CHART_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam05_clause2_it";
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam05_clause2_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam05_clause2_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "registrar-a", "reception");
            seedChart(statement, ORG_A, CHART_A, "CLAUSE2-A", "First chart");
            seedChart(statement, ORG_A, CHART_B, "CLAUSE2-B", "Second chart");
            seedChart(statement, ORG_A, CHART_C, "CLAUSE2-C", "Third chart");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam05_clause2_it WITH (FORCE)");
        }
    }

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

    private static void seedChart(Statement statement, String org, String id, String mrn, String name)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'registrar-a'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                + "VALUES ('" + org + "', '" + id + "', 1, '" + mrn + "', 'CLAUSE2-ISSUER', '" + name + "', "
                + "'" + name.toLowerCase() + "', '1990-01-01', "
                + "(SELECT id FROM public.user_identity WHERE organization_id = '" + org
                + "' AND subject = 'registrar-a'))");
    }

    private HttpHeaders headers(String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", ORG_A);
        headers.set("X-Correlation-Id", "iam05-clause2-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> enroll(String subject, String name) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/accounts",
                "{\"subject\":\"" + subject + "\",\"displayName\":\"" + name + "\"}", "registrar-a");
    }

    private ResponseEntity<String> issue(String subject, String chartId) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + chartId
                        + "\",\"purpose\":\"LINK\",\"ttlSeconds\":600}", "registrar-a");
    }

    private ResponseEntity<String> claim(String token, String subject) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + token + "\"}", subject);
    }

    private String issueAndClaim(String subject, String chartId) {
        ResponseEntity<String> issued = issue(subject, chartId);
        assertEquals(HttpStatus.CREATED, issued.getStatusCode(), issued.getBody());
        String token = field(issued.getBody(), "token");
        assertFalse(token.isEmpty(), "a one-time token must be returned at issue time");
        ResponseEntity<String> claimed = claim(token, subject);
        assertEquals(HttpStatus.OK, claimed.getStatusCode(), claimed.getBody());
        return token;
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private String uniqueSubject(String base) {
        return base + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void verifiedAccountLinksToSeveralIndependentlyVerifiedCharts() {
        String subject = uniqueSubject("multichart");
        assertEquals(HttpStatus.CREATED, enroll(subject, "Multi Chart").getStatusCode());

        // Each chart is independently verified by its own single-use invitation.
        String tokenA = issueAndClaim(subject, CHART_A);
        String tokenB = issueAndClaim(subject, CHART_B);
        String tokenC = issueAndClaim(subject, CHART_C);
        assertNotEquals(tokenA, tokenB);
        assertNotEquals(tokenB, tokenC);

        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject);
        assertEquals(HttpStatus.OK, links.getStatusCode(), links.getBody());
        String body = links.getBody();
        assertTrue(body.contains("\"accountSubject\":\"" + subject + "\""), body);
        for (String chartId : List.of(CHART_A, CHART_B, CHART_C)) {
            assertTrue(body.contains("\"chartId\":\"" + chartId + "\""), body);
            ResponseEntity<String> access = request(HttpMethod.GET,
                    "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + chartId,
                    null, subject);
            assertEquals(HttpStatus.OK, access.getStatusCode(), access.getBody());
            assertEquals("true", access.getBody(), "each verified chart must grant access");
        }
        assertEquals(3, countOccurrences(body, "\"event\":\"linked\""), body);
        assertEquals(3, countOccurrences(body, "\"method\":\"INVITATION\""), body);
        assertFalse(body.contains("\"token\""), "link material must never expose an invitation token");
    }

    @Test
    void chartAbsentFromTheActiveTenantCannotBeLinked() {
        String subject = uniqueSubject("unverifiedchart");
        assertEquals(HttpStatus.CREATED, enroll(subject, "Unverified Chart").getStatusCode());

        ResponseEntity<String> missing = issue(subject, UUID.randomUUID().toString());
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode(), missing.getBody());

        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject);
        assertEquals(HttpStatus.OK, links.getStatusCode(), links.getBody());
        assertFalse(links.getBody().contains("\"chartId\""), "an unverified chart must not create a link");
    }

    @Test
    void anotherAccountCannotClaimOrReadTheLinks() {
        String owner = uniqueSubject("linkowner");
        String other = uniqueSubject("linkother");
        assertEquals(HttpStatus.CREATED, enroll(owner, "Link Owner").getStatusCode());
        assertEquals(HttpStatus.CREATED, enroll(other, "Link Other").getStatusCode());

        ResponseEntity<String> issued = issue(owner, CHART_A);
        assertEquals(HttpStatus.CREATED, issued.getStatusCode(), issued.getBody());
        String token = field(issued.getBody(), "token");

        assertEquals(HttpStatus.FORBIDDEN, claim(token, other).getStatusCode(),
                "an invitation belongs only to the account it was issued for");
        assertEquals(HttpStatus.OK, claim(token, owner).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, claim(token, owner).getStatusCode(),
                "a single-use invitation must not create a second link");

        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + owner + "/links", null, other).getStatusCode(),
                "another account must not read the owner's links");
    }
}
