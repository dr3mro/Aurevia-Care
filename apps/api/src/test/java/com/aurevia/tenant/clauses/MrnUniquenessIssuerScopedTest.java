package com.aurevia.tenant.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * ARC-01 clause 3 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>The clause states that MRN uniqueness is issuer scoped. The rule is
 * exercised through the normal HTTP entry point of
 * {@link com.aurevia.web.PatientController}: the service accepts a
 * reused MRN when the issuer differs and refuses it when the issuer is the same.
 * The durable rows are read back under forced row-level security, so the result
 * proves tenant-scoped persistence rather than only unit coverage.</p>
 *
 * <p>Negative cases prove the refused sides of the rule: the same issuer is
 * refused with 409, a peer tenant may reuse the issuer-scoped MRN but cannot read
 * the first record, and a role without patient-registration permission is
 * refused. Each durable assertion would fail if the issuer column were dropped
 * from the uniqueness rule, if the service pre-check stopped filtering by
 * issuer, or if forced row-level security were removed.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_arc01_clause3_it")
@ActiveProfiles("synthetic-auth")
class MrnUniquenessIssuerScopedTest {

    static final String ORG_A = "a1000000-0000-0000-0000-000000000001";
    static final String ORG_B = "b2000000-0000-0000-0000-000000000002";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_arc01_clause3_it";
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
            statement.execute("DROP DATABASE IF EXISTS orevia_arc01_clause3_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_arc01_clause3_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "reception-c3-a", "reception");
            seed(statement, ORG_B, "reception-c3-b", "reception");
            seed(statement, ORG_A, "auditor-c3-a", "privacy_auditor");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_arc01_clause3_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
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
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> register(String name, String mrn, String issuer,
                                            String actor, String org) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + ",\"dob\":\"1990-05-20\",\"language\":\"en\",\"contacts\":\"{}\""
                + ",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        return request(HttpMethod.POST, "/api/v1/patients", body, actor, org);
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

    private int countMrnWithOrg(String organization, String issuer, String mrn) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.patient_record WHERE mrn_issuer = '" + issuer
                            + "' AND mrn = '" + mrn + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void mrnUniquenessIsScopedToItsIssuer() throws Exception {
        ResponseEntity<String> first = register("Issuer One", "ARC01-C3-ISS-1", "CLINIC-A",
                "reception-c3-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());

        ResponseEntity<String> sameIssuer = register("Issuer Again", "ARC01-C3-ISS-1", "CLINIC-A",
                "reception-c3-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, sameIssuer.getStatusCode(), sameIssuer.getBody());
        assertEquals("CONFLICT", field(sameIssuer.getBody(), "code"));
        assertEquals("MRN already exists for this issuer", field(sameIssuer.getBody(), "message"));

        ResponseEntity<String> otherIssuer = register("Issuer Two", "ARC01-C3-ISS-1", "CLINIC-B",
                "reception-c3-a", ORG_A);
        assertEquals(HttpStatus.CREATED, otherIssuer.getStatusCode(), otherIssuer.getBody());
        assertEquals(1, countMrnWithOrg(ORG_A, "CLINIC-B", "ARC01-C3-ISS-1"),
                "the different issuer owns a durable row for the same MRN");
        assertEquals(1, countMrnWithOrg(ORG_A, "CLINIC-A", "ARC01-C3-ISS-1"),
                "the first issuer still owns exactly one row");
    }

    @Test
    void peerTenantMayReuseMrnButCannotReadTheFirstRecord() throws Exception {
        ResponseEntity<String> first = register("First Tenant", "ARC01-C3-ORG-1", "CLINIC-A",
                "reception-c3-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        String firstId = field(first.getBody(), "id");

        ResponseEntity<String> ownRead = request(HttpMethod.GET, "/api/v1/patients/" + firstId,
                null, "reception-c3-a", ORG_A);
        assertEquals(HttpStatus.OK, ownRead.getStatusCode(), ownRead.getBody());
        assertEquals("ARC01-C3-ORG-1", field(ownRead.getBody(), "mrn"));
        assertEquals("CLINIC-A", field(ownRead.getBody(), "mrnIssuer"));

        ResponseEntity<String> second = register("Second Tenant", "ARC01-C3-ORG-1", "CLINIC-A",
                "reception-c3-b", ORG_B);
        assertEquals(HttpStatus.CREATED, second.getStatusCode(),
                "a peer organization may reuse the same issuer-scoped MRN: " + second.getBody());

        assertEquals(1, countMrnWithOrg(ORG_A, "CLINIC-A", "ARC01-C3-ORG-1"));
        assertEquals(1, countMrnWithOrg(ORG_B, "CLINIC-A", "ARC01-C3-ORG-1"));
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/v1/patients/" + firstId,
                null, "reception-c3-b", ORG_B).getStatusCode(),
                "the first record is concealed from the peer organization");
    }

    @Test
    void roleWithoutRegistrationPermissionCannotUseIssuerScopedMrn() {
        assertEquals(HttpStatus.FORBIDDEN, register("Denied Name", "ARC01-C3-DENY-1", "CLINIC-A",
                "auditor-c3-a", ORG_A).getStatusCode(),
                "a privacy auditor has no patient-registration permission");
    }
}
