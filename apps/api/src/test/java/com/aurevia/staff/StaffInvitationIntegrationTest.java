package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * BUILD-01 / IAM-02 real-PostgreSQL acceptance through the HTTP entry point:
 * create, list, revoke and accept, plus expired, reused, revoked and wrong-tenant
 * token rejection and atomic acceptance. All fixtures are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_staff_it")
@ActiveProfiles("synthetic-auth")
class StaffInvitationIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_staff_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_staff_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_staff_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedAdmin(statement, ORG_A, "admin-a");
            seedAdmin(statement, ORG_B, "admin-b");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'admin-a'");
            statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'MAIN', 'Alpha Main')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = 'admin-b'");
            statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'MAIN', 'Beta Main')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_staff_it WITH (FORCE)");
        }
    }

    private static void seedAdmin(Statement statement, String org, String subject) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'tenant_admin', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> create(String subject, String organization, String actor) {
        String body = "{\"subject\":\"" + subject + "\",\"displayName\":\"New " + subject + "\","
                + "\"facilityId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                + "\"roles\":[\"reception\",\"tenant_admin\"],\"ttlSeconds\":3600}";
        return request(HttpMethod.POST, "/api/v1/staff-invitations", body, actor, organization);
    }

    private String token(ResponseEntity<String> response) {
        assertNotNull(response.getBody());
        int start = response.getBody().indexOf("\"token\":\"") + 9;
        int end = response.getBody().indexOf('"', start);
        return response.getBody().substring(start, end);
    }

    private ResponseEntity<String> accept(String token, String subject, String organization) {
        return request(HttpMethod.POST, "/api/v1/staff-invitations/accept",
                "{\"token\":\"" + token + "\"}", subject, organization);
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

    private static String adminId(String organization, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static void seedInvitation(String token, String subject, String organization, String actor,
                                       String expiresAt, String createdAt) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            statement.execute("SET app.actor = '" + actor + "'");
            statement.execute("INSERT INTO public.staff_invitation "
                    + "(organization_id, subject, display_name, facility_id, roles, token_hash, "
                    + " expires_at, created_at) VALUES ('" + organization + "', '"
                    + subject + "', 'Seeded', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', "
                    + "ARRAY['reception']::text[], encode(sha256(convert_to('" + token + "','UTF8')),'hex'), "
                    + expiresAt + ", " + createdAt + ")");
        }
    }

    private static boolean hasInvitationFlag(String token, String column, String organization) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery("SELECT (" + column + " IS NOT NULL) FROM public.staff_invitation "
                    + "WHERE token_hash = encode(sha256(convert_to('" + token + "','UTF8')),'hex')")) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static int membershipCount(String subject, String organization) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.membership m JOIN public.user_identity u "
                    + "ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = '" + subject + "'")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    @Test
    void createListAcceptRecordsGrantorAndEffectiveDate() throws Exception {
        ResponseEntity<String> issued = create("nurse-a", ORG_A, "admin-a");
        assertEquals(HttpStatus.CREATED, issued.getStatusCode(), issued.getBody());
        String token = token(issued);
        assertTrue(token.length() >= 32);

        ResponseEntity<String> pending = request(HttpMethod.GET, "/api/v1/staff-invitations/pending",
                null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, pending.getStatusCode(), pending.getBody());
        assertTrue(pending.getBody().contains("nurse-a"));

        ResponseEntity<String> accepted = accept(token, "nurse-a", ORG_A);
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("nurse-a", field(accepted.getBody(), "subject"));
        assertEquals("active", field(accepted.getBody(), "status"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT ra.granted_by::text, ra.valid_from, count(*) OVER () "
                    + "FROM public.role_assignment ra JOIN public.membership m "
                    + "ON m.organization_id = ra.organization_id AND m.id = ra.membership_id "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = 'nurse-a' ORDER BY ra.role_key")) {
                assertTrue(result.next());
                assertEquals(adminId(ORG_A, "admin-a"), result.getString(1));
                assertNotNull(result.getTimestamp(2));
                assertEquals(2, result.getInt(3));
            }
        }
    }

    @Test
    void expiredRevokedReusedAndWrongTenantTokensAreRejected() throws Exception {
        String expiredToken = "expired-token-abcdefghijklmno";
        seedInvitation(expiredToken, "expired-a", ORG_A, "admin-a",
                "now() - interval '1 day'", "now() - interval '2 days'");
        assertEquals(409, accept(expiredToken, "expired-a", ORG_A).getStatusCode().value());

        ResponseEntity<String> issued = create("revoked-a", ORG_A, "admin-a");
        String revokedToken = token(issued);
        String id = field(issued.getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/staff-invitations/" + id + "/revoke",
                null, "admin-a", ORG_A).getStatusCode());
        assertEquals(409, accept(revokedToken, "revoked-a", ORG_A).getStatusCode().value());

        ResponseEntity<String> reused = create("reuse-a", ORG_A, "admin-a");
        String reuseToken = token(reused);
        assertEquals(HttpStatus.OK, accept(reuseToken, "reuse-a", ORG_A).getStatusCode());
        assertEquals(409, accept(reuseToken, "reuse-a", ORG_A).getStatusCode().value());

        ResponseEntity<String> crossTenant = create("cross-a", ORG_A, "admin-a");
        String crossToken = token(crossTenant);
        assertEquals(409, accept(crossToken, "cross-a", ORG_B).getStatusCode().value());
    }

    @Test
    void acceptanceIsAtomicWhenTheInviteeAlreadyHasAnIdentity() throws Exception {
        String token = "collision-token-abcdefghijklmnop";
        seedInvitation(token, "admin-a", ORG_A, "admin-a",
                "now() + interval '1 hour'", "now()");
        int before = membershipCount("admin-a", ORG_A);
        assertEquals(409, accept(token, "admin-a", ORG_A).getStatusCode().value());
        assertEquals(before, membershipCount("admin-a", ORG_A));
        assertEquals(false, hasInvitationFlag(token, "used_at", ORG_A));
    }
}
