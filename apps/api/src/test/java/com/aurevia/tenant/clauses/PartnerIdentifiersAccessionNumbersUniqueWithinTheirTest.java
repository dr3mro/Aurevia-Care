package com.aurevia.tenant.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * ARC-01 clause 4 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>The clause requires partner identifiers and accession numbers to be unique
 * within their defined issuer and type. The laboratory accession half is built:
 * the issuer-scoped uniqueness rule is exercised through the normal HTTP entry
 * point and the durable row is read back under forced row-level security, so
 * this proves tenant-scoped persistence rather than only unit behavior.</p>
 *
 * <p>The partner-identifier half and a type-qualified accession key are not
 * built. The missing surfaces and their intended contract are recorded in the
 * clause evidence note and in follow-up tasks, so a later implementation can
 * add acceptance proofs without rediscovering the contract.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_arc01c04_it")
@ActiveProfiles("synthetic-auth")
class PartnerIdentifiersAccessionNumbersUniqueWithinTheirTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_arc01c04_it";
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
            statement.execute("DROP DATABASE IF EXISTS orevia_arc01c04_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_arc01c04_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "lab-a", "lab_collector");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "reception-b", "reception");
            seed(statement, ORG_B, "lab-b", "lab_collector");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_arc01c04_it WITH (FORCE)");
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

    private String patient(String org, String reception, String mrn) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body, reception, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private ResponseEntity<String> accession(String number, String issuer, String patient, String order,
                                             String actor, String org) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"" + issuer
                + "\",\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"" + order + "\"}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions", body, actor, org);
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

    /**
     * Count durable accessions for one issuer and number under the active
     * organization's forced row-level security.
     */
    private int countAccession(String organization, String issuer, String number) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.lab_accession WHERE accession_issuer = '" + issuer
                            + "' AND accession_no = '" + number + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void accessionNumberIsUniqueWithinItsIssuer() throws Exception {
        String patient = patient(ORG_A, "reception-a", "MRN-ARC01-UNIQ");
        ResponseEntity<String> first = accession("ARC01-UNIQ-1", "LAB-A", patient, "ORD/UNIQ-1", "lab-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        assertEquals("ARC01-UNIQ-1", field(first.getBody(), "accessionNo"));
        assertEquals("LAB-A", field(first.getBody(), "accessionIssuer"));
        assertEquals("accessioned", field(first.getBody(), "state"));
        String id = field(first.getBody(), "id");

        ResponseEntity<String> duplicate = accession("ARC01-UNIQ-1", "LAB-A", patient,
                "ORD/UNIQ-2", "lab-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode(), duplicate.getBody());
        assertEquals("CONFLICT", field(duplicate.getBody(), "code"));

        ResponseEntity<String> otherIssuer = accession("ARC01-UNIQ-1", "LAB-B", patient,
                "ORD/UNIQ-3", "lab-a", ORG_A);
        assertEquals(HttpStatus.CREATED, otherIssuer.getStatusCode(), otherIssuer.getBody());

        assertEquals(1, countAccession(ORG_A, "LAB-A", "ARC01-UNIQ-1"),
                "one durable row for the issuer and number that won the race");
        assertEquals(1, countAccession(ORG_A, "LAB-B", "ARC01-UNIQ-1"),
                "a different issuer may reuse the same number in the same tenant");
        assertEquals(0, countAccession(ORG_B, "LAB-A", "ARC01-UNIQ-1"),
                "the row must be invisible from another organization");
        assertEquals(HttpStatus.OK,
                request(HttpMethod.GET, "/api/v1/lab/accessions/" + id, null, "lab-a", ORG_A).getStatusCode());
    }

    @Test
    void peerOrganizationMayReuseTheSameIssuerAccessionNumberButCannotReadTheFirst() throws Exception {
        String patientA = patient(ORG_A, "reception-a", "MRN-ARC01-PEER");
        ResponseEntity<String> first = accession("ARC01-PEER-1", "LAB-A", patientA,
                "ORD/PEER-1", "lab-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        String firstId = field(first.getBody(), "id");

        String patientB = patient(ORG_B, "reception-b", "MRN-ARC01-PEER");
        ResponseEntity<String> second = accession("ARC01-PEER-1", "LAB-A", patientB,
                "ORD/PEER-1", "lab-b", ORG_B);
        assertEquals(HttpStatus.CREATED, second.getStatusCode(),
                "a peer organization may reuse the same issuer accession number: " + second.getBody());
        assertNotEquals(firstId, field(second.getBody(), "id"),
                "reuse in another tenant creates a distinct specimen, not a shared row");

        assertEquals(1, countAccession(ORG_A, "LAB-A", "ARC01-PEER-1"));
        assertEquals(1, countAccession(ORG_B, "LAB-A", "ARC01-PEER-1"));
        ResponseEntity<String> concealed = request(HttpMethod.GET,
                "/api/v1/lab/accessions/" + firstId, null, "lab-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(),
                "the first organization's accession is concealed from the peer organization");
        assertEquals("NOT_FOUND", field(concealed.getBody(), "code"));
    }

    @Test
    void roleWithoutAccessionPermissionCannotAccession() throws Exception {
        String patient = patient(ORG_A, "reception-a", "MRN-ARC01-ROLE");
        ResponseEntity<String> denied = accession("ARC01-ROLE-1", "LAB-A", patient,
                "ORD/ROLE-1", "auditor-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(),
                "a privacy auditor has no laboratory accession permission");
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
        assertEquals(0, countAccession(ORG_A, "LAB-A", "ARC01-ROLE-1"),
                "a denied command must leave no durable accession behind");
    }

    @Test
    void malformedIssuerOrMissingNumberIsRejected() {
        String patient = patient(ORG_A, "reception-a", "MRN-ARC01-BAD");
        ResponseEntity<String> badIssuer = accession("ARC01-BAD-1", "!", patient,
                "ORD/BAD-1", "lab-a", ORG_A);
        assertEquals(422, badIssuer.getStatusCode().value(),
                "the issuer must satisfy the shared issuer/code boundary");
        assertEquals("INVALID_ISSUER", field(badIssuer.getBody(), "code"));

        String missingNumber = "{\"accessionIssuer\":\"LAB-A\",\"patientId\":\"" + patient
                + "\",\"sourceOrderRef\":\"ORD/BAD-2\"}";
        ResponseEntity<String> badNumber = request(HttpMethod.POST, "/api/v1/lab/accessions",
                missingNumber, "lab-a", ORG_A);
        assertEquals(422, badNumber.getStatusCode().value(), "an accession number is required");
        assertEquals("INVALID_ACCESSION", field(badNumber.getBody(), "code"));
    }
}
