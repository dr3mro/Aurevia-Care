package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * LAB-01 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>A laboratory collector accessions a requisition with a number that is
 * unique to its issuer inside the active tenant. The rule is exercised through
 * the normal HTTP entry point, and the durable row is read back under forced
 * row-level security, so the result proves tenant-scoped persistence rather
 * than only unit coverage.</p>
 *
 * <p>Negative cases prove the refused sides of the rule: a number already used
 * by the same issuer is refused while another issuer may reuse it, a peer
 * tenant may reuse the pair without seeing the first accession, a role without
 * accession permission is refused, and malformed issuer or number input fails
 * closed at the boundary.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_lab01_clause1_it")
@ActiveProfiles("synthetic-auth")
class AccessionRequisitionIssuerScopedUniqueIdTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_lab01_clause1_it";
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_lab01_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_lab01_clause1_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "collector-a", "lab_collector");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "verifier-a", "lab_verifier");
            seed(statement, ORG_B, "collector-b", "lab_collector");
            seed(statement, ORG_B, "reception-b", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_lab01_clause1_it WITH (FORCE)");
        }
    }

    /** Seed one organization, identity, active membership and bounded role for a synthetic subject. */
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

    /**
     * Issue one HTTP request with the synthetic actor and active organization headers.
     *
     * @param method  HTTP verb
     * @param uri     request path
     * @param body    JSON body, or {@code null} for a bodyless request
     * @param subject synthetic subject header, or {@code null} to omit it
     * @param org     active organization header, or {@code null} to omit it
     * @return the raw response so status and body can both be asserted
     */
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

    /**
     * Register a synthetic patient through the normal entry point.
     *
     * @param org        active organization
     * @param reception  receptionist subject that may register patients
     * @param mrn        locally unique MRN
     * @return the new patient's opaque identifier
     */
    private String patient(String org, String reception, String mrn) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body, reception, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    /**
     * Post one accession command through the HTTP entry point.
     *
     * @param number   issuer-scoped accession number
     * @param issuer   accession issuer
     * @param patient  patient identifier
     * @param order    bounded source order reference
     * @param actor    synthetic subject performing the accession
     * @param org      active organization
     * @return the raw response
     */
    private ResponseEntity<String> accession(String number, String issuer, String patient, String order,
                                             String actor, String org) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"" + issuer
                + "\",\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"" + order + "\"}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions", body, actor, org);
    }

    /**
     * Read one accession through the HTTP entry point.
     *
     * @param id    specimen identifier
     * @param actor synthetic subject
     * @param org   active organization
     * @return the raw response
     */
    private ResponseEntity<String> get(String id, String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/lab/accessions/" + id, null, actor, org);
    }

    /** @return the string value of a JSON key, or an empty string when absent */
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
     * Count durable accession rows for an issuer-scoped number inside one organization.
     *
     * <p>The connection is a non-bypass role and sets the tenant context before
     * the query, so forced RLS conceals peer-tenant rows exactly as the runtime
     * connection would.</p>
     *
     * @param organization active organization whose rows must be visible
     * @param issuer       accession issuer
     * @param number       accession number
     * @return number of visible durable rows
     */
    private int countAccessionWithOrg(String organization, String issuer, String number) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
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

    /**
     * Assert the durable accession row for an id carries the exact issuer-scoped key and
     * belongs to the expected organization, read back under forced RLS.
     *
     * @param organization active organization whose RLS scope must expose the row
     * @param id           specimen identifier
     * @param issuer       expected persisted issuer
     * @param number       expected persisted number
     * @param expectedOrg  expected persisted organization identifier
     */
    private void assertDurableAccession(String organization, String id, String issuer, String number,
                                        String expectedOrg) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT organization_id::text, accession_issuer, accession_no, state, version "
                            + "FROM public.lab_accession WHERE id = '" + id + "'")) {
                assertTrue(result.next(), "the durable row must be visible in " + organization);
                assertEquals(expectedOrg, result.getString("organization_id"));
                assertEquals(issuer, result.getString("accession_issuer"));
                assertEquals(number, result.getString("accession_no"));
                assertEquals("accessioned", result.getString("state"));
                assertEquals(1, result.getInt("version"));
                assertFalse(result.next(), "exactly one durable row is expected");
            }
        }
    }

    /**
     * Count the durable rows visible for a specimen id inside one organization.
     *
     * @param organization active organization whose RLS scope is applied
     * @param id           specimen identifier
     * @return number of visible rows (zero when concealed by forced RLS)
     */
    private int countAccessionByIdWithOrg(String organization, String id) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.lab_accession WHERE id = '" + id + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /**
     * A collector accessions a requisition with an issuer-scoped number: the HTTP response
     * and the durable row carry the number and issuer in the active organization, and the row
     * is invisible from the peer organization.
     */
    @Test
    void labCollectorAccessionsRequisitionWithIssuerScopedNumber() throws Exception {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-CLAUSE-POS-1");
        ResponseEntity<String> created = accession("ACC-CLAUSE-POS-1", "ISS-A", patientA,
                "ORD/CLAUSE-POS-1", "collector-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("ACC-CLAUSE-POS-1", field(created.getBody(), "accessionNo"));
        assertEquals("ISS-A", field(created.getBody(), "accessionIssuer"));
        assertEquals("accessioned", field(created.getBody(), "state"));

        String id = field(created.getBody(), "id");
        assertEquals(1, countAccessionWithOrg(ORG_A, "ISS-A", "ACC-CLAUSE-POS-1"),
                "one durable accession in the active organization");
        assertEquals(0, countAccessionWithOrg(ORG_B, "ISS-A", "ACC-CLAUSE-POS-1"),
                "the accession must not be visible from another organization");
        assertDurableAccession(ORG_A, id, "ISS-A", "ACC-CLAUSE-POS-1", ORG_A);

        ResponseEntity<String> read = get(id, "collector-a", ORG_A);
        assertEquals(HttpStatus.OK, read.getStatusCode(), read.getBody());
        assertEquals("ACC-CLAUSE-POS-1", field(read.getBody(), "accessionNo"));
        assertEquals("ISS-A", field(read.getBody(), "accessionIssuer"));
    }

    /**
     * The number is issuer-scoped: the same issuer is refused (409) while a different
     * issuer may reuse it (201), and both durable rows coexist.
     */
    @Test
    void duplicateNumberForSameIssuerIsRefusedButAnotherIssuerMayReuseIt() throws Exception {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-CLAUSE-ISS-1");
        ResponseEntity<String> first = accession("ACC-CLAUSE-ISS-1", "ISS-A", patientA,
                "ORD/CLAUSE-ISS-1", "collector-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());

        ResponseEntity<String> duplicate = accession("ACC-CLAUSE-ISS-1", "ISS-A", patientA,
                "ORD/CLAUSE-ISS-2", "collector-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode(), duplicate.getBody());
        assertEquals("CONFLICT", field(duplicate.getBody(), "code"));

        ResponseEntity<String> otherIssuer = accession("ACC-CLAUSE-ISS-1", "ISS-B", patientA,
                "ORD/CLAUSE-ISS-3", "collector-a", ORG_A);
        assertEquals(HttpStatus.CREATED, otherIssuer.getStatusCode(),
                "the same number from a different issuer is a distinct accession: " + otherIssuer.getBody());
        assertEquals(1, countAccessionWithOrg(ORG_A, "ISS-A", "ACC-CLAUSE-ISS-1"));
        assertEquals(1, countAccessionWithOrg(ORG_A, "ISS-B", "ACC-CLAUSE-ISS-1"));
    }

    /**
     * The number is tenant-scoped: a peer tenant may reuse the same issuer-scoped pair
     * with its own patient, holds its own durable row, and cannot read the first accession.
     */
    @Test
    void peerTenantMayReuseNumberAndCannotReadFirstAccession() throws Exception {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-CLAUSE-ORG-A");
        String patientB = patient(ORG_B, "reception-b", "MRN-LAB-CLAUSE-ORG-B");
        ResponseEntity<String> first = accession("ACC-CLAUSE-ORG-1", "ISS-A", patientA,
                "ORD/CLAUSE-ORG-1", "collector-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        String firstId = field(first.getBody(), "id");

        ResponseEntity<String> peer = accession("ACC-CLAUSE-ORG-1", "ISS-A", patientB,
                "ORD/CLAUSE-ORG-1", "collector-b", ORG_B);
        assertEquals(HttpStatus.CREATED, peer.getStatusCode(),
                "a peer tenant may reuse the same issuer-scoped number: " + peer.getBody());
        assertNotEquals(firstId, field(peer.getBody(), "id"));

        assertEquals(1, countAccessionWithOrg(ORG_A, "ISS-A", "ACC-CLAUSE-ORG-1"));
        assertEquals(1, countAccessionWithOrg(ORG_B, "ISS-A", "ACC-CLAUSE-ORG-1"));
        assertEquals(1, countAccessionByIdWithOrg(ORG_A, firstId));
        assertEquals(0, countAccessionByIdWithOrg(ORG_B, firstId),
                "the first specimen row is concealed from the peer tenant under forced RLS");
        assertEquals(HttpStatus.NOT_FOUND, get(firstId, "collector-b", ORG_B).getStatusCode(),
                "the first accession is concealed from the peer tenant");
        assertEquals(HttpStatus.OK, get(firstId, "collector-a", ORG_A).getStatusCode());
    }

    /**
     * A role without accession-create permission is refused: both reception and the
     * laboratory verifier are denied even with a valid patient in the active tenant.
     */
    @Test
    void roleWithoutAccessionPermissionIsRefused() {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-CLAUSE-DENY-1");
        ResponseEntity<String> byReception = accession("ACC-CLAUSE-DENY-1", "ISS-A", patientA,
                "ORD/CLAUSE-DENY-1", "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, byReception.getStatusCode(), byReception.getBody());
        assertEquals("FORBIDDEN", field(byReception.getBody(), "code"));

        ResponseEntity<String> byVerifier = accession("ACC-CLAUSE-DENY-2", "ISS-A", patientA,
                "ORD/CLAUSE-DENY-2", "verifier-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, byVerifier.getStatusCode(), byVerifier.getBody());
        assertEquals("FORBIDDEN", field(byVerifier.getBody(), "code"));
    }

    /**
     * Malformed issuer and accession number fail closed at the boundary with a
     * machine-readable error before any durable row can be created.
     */
    @Test
    void malformedIssuerAndNumberAreRefused() throws Exception {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-CLAUSE-MALF-1");
        ResponseEntity<String> badIssuer = accession("ACC-CLAUSE-MALF-1", "!", patientA,
                "ORD/CLAUSE-MALF-1", "collector-a", ORG_A);
        assertEquals(422, badIssuer.getStatusCode().value(), badIssuer.getBody());
        assertEquals("INVALID_ISSUER", field(badIssuer.getBody(), "code"));

        ResponseEntity<String> badNumber = accession("!", "ISS-A", patientA,
                "ORD/CLAUSE-MALF-2", "collector-a", ORG_A);
        assertEquals(422, badNumber.getStatusCode().value(), badNumber.getBody());
        assertEquals("INVALID_ACCESSION", field(badNumber.getBody(), "code"));

        assertEquals(0, countAccessionWithOrg(ORG_A, "!", "ACC-CLAUSE-MALF-1"),
                "a malformed issuer must create no durable row");
        assertEquals(0, countAccessionWithOrg(ORG_A, "ISS-A", "!"),
                "a malformed number must create no durable row");
    }
}
