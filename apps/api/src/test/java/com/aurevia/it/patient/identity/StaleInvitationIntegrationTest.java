package com.aurevia.it.patient.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * IAM-05 end-to-end regression (synthetic data only): an invitation left
 * unspent when a chart is unlinked or re-verified can never restore access.
 *
 * <p>The database gate runs this suite; the fast unit tier excludes it with
 * the {@code integration} tag.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_identity_stale_it")
@ActiveProfiles("synthetic-auth")
class StaleInvitationIntegrationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_identity_stale_it";
    private static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS orevia_identity_stale_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_identity_stale_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            statement.execute("SET app.actor = 'admin-a'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG + "', 'Org A')");
            seedStaff(statement, "admin-a", "tenant_admin");
            seedStaff(statement, "registrar-a", "reception");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                    + "VALUES ('" + ORG + "', '" + CHART + "', 1, 'IDENT-STALE', 'ISSUER', 'Org A chart', "
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
            statement.execute("DROP DATABASE IF EXISTS orevia_identity_stale_it WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String subject, String role) throws Exception {
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
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
                "{\"subject\":\"" + subject + "\",\"displayName\":\"Synthetic Patient\"}", "registrar-a");
    }

    private String issue(String subject) {
        ResponseEntity<String> issued = request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"purpose\":\"LINK\",\"ttlSeconds\":600}", "registrar-a");
        assertEquals(HttpStatus.CREATED, issued.getStatusCode(), issued.getBody());
        return field(issued.getBody(), "token");
    }

    private void claim(String subject, String token) {
        request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + token + "\"}", subject);
    }

    private String access(String subject) {
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART,
                null, subject);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response.getBody();
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        assertTrue(start >= 0, "response must contain " + key + ": " + json);
        start += needle.length();
        int end = json.indexOf('"', start + 1);
        return json.substring(start + 1, end);
    }

    private static String uniqueSubject(String base) {
        return base + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void aClaimedLinkAndAListingExposeTheAppendOnlyEventId() {
        String subject = uniqueSubject("projection");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());

        ResponseEntity<String> claim = request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + issue(subject) + "\"}", subject);
        assertEquals(HttpStatus.OK, claim.getStatusCode(), claim.getBody());
        String id = field(claim.getBody(), "id");
        assertTrue(id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
                "the link event id must be the account_chart_link UUID, not the fold's account id: " + claim.getBody());

        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject);
        assertEquals(HttpStatus.OK, links.getStatusCode(), links.getBody());
        assertTrue(links.getBody().contains("\"id\":\"" + id + "\""),
                "the listing must project the same event id: " + links.getBody());
    }

    @Test
    void aStaleInvitationCannotUndoAnUnlink() {
        String subject = uniqueSubject("stale");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());
        claim(subject, issue(subject));
        assertEquals("true", access(subject));

        String stale = issue(subject);
        ResponseEntity<String> unlinked = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"reason\":\"disputed link\"}", subject);
        assertEquals(HttpStatus.OK, unlinked.getStatusCode(), unlinked.getBody());
        assertEquals("false", access(subject));

        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + stale + "\"}", subject).getStatusCode());
        assertEquals("false", access(subject), "a stale token must not restore a disputed link");
    }

    @Test
    void anUnspentInvitationCannotBeRedeemedAfterARejectedUnlink() {
        String subject = uniqueSubject("norevoke");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());
        String stale = issue(subject);
        assertEquals("false", access(subject), "an unspent invitation is not yet access");

        ResponseEntity<String> unlinked = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"reason\":\"disputed link\"}", subject);
        assertEquals(HttpStatus.NOT_FOUND, unlinked.getStatusCode(), unlinked.getBody());

        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + stale + "\"}", subject).getStatusCode());
        assertEquals("false", access(subject), "a rejected unlink must still retire the unspent invitation");
    }

    @Test
    void reverificationInvalidatesPriorInvitationsButHonoursTheFreshOne() {
        String subject = uniqueSubject("rev");
        assertEquals(HttpStatus.CREATED, enroll(subject).getStatusCode());
        claim(subject, issue(subject));

        String stale = issue(subject);
        ResponseEntity<String> reverify = request(HttpMethod.POST, "/api/v1/patient-identity/links/reverify",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART
                        + "\",\"reason\":\"lost access\"}", "admin-a");
        assertEquals(HttpStatus.OK, reverify.getStatusCode(), reverify.getBody());
        String fresh = field(reverify.getBody(), "token");

        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + stale + "\"}", subject).getStatusCode());
        assertEquals("false", access(subject));

        claim(subject, fresh);
        assertEquals("true", access(subject));
    }
}
