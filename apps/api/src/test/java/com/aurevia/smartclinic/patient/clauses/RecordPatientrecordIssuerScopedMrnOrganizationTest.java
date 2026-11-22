package com.aurevia.smartclinic.patient.clauses;

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
 * IAM-03 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>A receptionist records a PatientRecord whose MRN is scoped to an issuer
 * inside the active organization. The rule is exercised through the normal HTTP
 * entry point, and the durable row is read back under forced row-level security,
 * so the result proves tenant-scoped persistence rather than only unit coverage.</p>
 *
 * <p>Negative cases prove the refused sides of the rule: an MRN already used by
 * the same issuer is refused, a peer tenant may reuse the same issuer-scoped MRN
 * without seeing the first record, and a role without patient permission cannot
 * record a PatientRecord at all.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam03_clause1_it")
@ActiveProfiles("synthetic-auth")
class RecordPatientrecordIssuerScopedMrnOrganizationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam03_clause1_it";
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam03_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam03_clause1_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "reception-b", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam03_clause1_it WITH (FORCE)");
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
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
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
    void receptionRecordsIssuerScopedPatientRecordInActiveOrganization() throws Exception {
        ResponseEntity<String> created = register("José Álvarez", "IAM03-POS-1", "CLINIC-A",
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("IAM03-POS-1", field(created.getBody(), "mrn"));
        assertEquals("CLINIC-A", field(created.getBody(), "mrnIssuer"));
        assertEquals("José Álvarez", field(created.getBody(), "displayName"),
                "original spelling must be preserved on the recorded PatientRecord");

        String id = field(created.getBody(), "id");
        assertEquals(1, countMrnWithOrg(ORG_A, "CLINIC-A", "IAM03-POS-1"),
                "one durable PatientRecord in the active organization");
        assertEquals(0, countMrnWithOrg(ORG_B, "CLINIC-A", "IAM03-POS-1"),
                "the record must not be visible from another organization");

        ResponseEntity<String> read = request(HttpMethod.GET, "/api/v1/patients/" + id,
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, read.getStatusCode(), read.getBody());
        assertEquals("IAM03-POS-1", field(read.getBody(), "mrn"));
        assertEquals("CLINIC-A", field(read.getBody(), "mrnIssuer"));
    }

    @Test
    void mrnIsScopedToItsIssuerSoAnotherIssuerMayReuseItButTheSameIssuerCannot() {
        assertEquals(HttpStatus.CREATED, register("Issuer One", "IAM03-ISS-1", "CLINIC-A",
                "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CREATED, register("Issuer Two", "IAM03-ISS-1", "CLINIC-B",
                "reception-a", ORG_A).getStatusCode(),
                "the same MRN from a different issuer is a different PatientRecord");
        assertEquals(HttpStatus.CONFLICT, register("Issuer Again", "IAM03-ISS-1", "CLINIC-A",
                "reception-a", ORG_A).getStatusCode(),
                "the same MRN from the same issuer must be refused");
    }

    @Test
    void peerOrganizationReusesTheIssuerScopedMrnAndCannotReadTheFirstRecord() throws Exception {
        ResponseEntity<String> first = register("First Tenant", "IAM03-ORG-1", "CLINIC-A",
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        String firstId = field(first.getBody(), "id");

        ResponseEntity<String> second = register("Second Tenant", "IAM03-ORG-1", "CLINIC-A",
                "reception-b", ORG_B);
        assertEquals(HttpStatus.CREATED, second.getStatusCode(),
                "a peer organization may reuse the same issuer-scoped MRN: " + second.getBody());

        assertEquals(1, countMrnWithOrg(ORG_A, "CLINIC-A", "IAM03-ORG-1"));
        assertEquals(1, countMrnWithOrg(ORG_B, "CLINIC-A", "IAM03-ORG-1"));
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/v1/patients/" + firstId,
                null, "reception-b", ORG_B).getStatusCode(),
                "the first record is concealed from the peer organization");
    }

    @Test
    void roleWithoutPatientPermissionCannotRecordPatientRecord() {
        assertEquals(HttpStatus.FORBIDDEN, register("Denied Name", "IAM03-DENY-1", "CLINIC-A",
                "auditor-a", ORG_A).getStatusCode(),
                "a privacy auditor has no patient-registration permission");
    }
}
