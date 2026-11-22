package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * CLN-03 clause 3 (API evidence): the clinically responsible author is recorded
 * separately from the subject who entered the row.
 *
 * <p>A data-entry actor may intentionally leave authorship open. A clinician who
 * later verifies that entry becomes the accountable clinical author, while the
 * original data-entry actor is preserved. An author claimed at intake is kept
 * verbatim across verification by a different clinician. The negative tests show
 * that a recorder cannot claim authorship for someone else, that malformed author
 * values are refused, and that a user without a chart role or outside the tenant
 * cannot acquire authorship.</p>
 *
 * <p>All requests go through the real {@code /api/v1/chart/records/entries}
 * entry point against a fresh database created from the production Flyway
 * migrations. Fixtures are synthetic: no real identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_clinical_author_it")
@ActiveProfiles("synthetic-auth")
class RecordClinicalAuthorSeparatelyDataEntryActorTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_clinical_author_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships and one synthetic patient.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_clinical_author_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_clinical_author_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "clinician-b", "clinician");
            seedUser(statement, ORG_A, "assistant-a", "clinical_assistant");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "clinician-other", "clinician");
            seedPatient(statement);
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_clinical_author_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedUser(Statement statement, String org, String subject, String role)
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
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
    }

    private static void seedPatient(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'assistant-a'");
        // V7 requires a civil date or an explicit unknown reason; use a synthetic
        // date so the append-only patient invariant holds for the fixture.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_A + "', '" + PATIENT_A + "', 1, 'MRN-AUTHOR', 'CLINIC', "
                + "'Synthetic Author Patient', 'synthetic author patient', DATE '1990-05-20')");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> record(String text, String recorder, String correlation) {
        return recordWithAuthor(text, recorder, null, correlation);
    }

    private ResponseEntity<String> recordWithAuthor(String text, String recorder,
                                                     String author, String correlation) {
        String body = "{\"patientId\":\"" + PATIENT_A + "\",\"type\":\"PROBLEM\""
                + ",\"originalText\":\"" + text + "\""
                + ",\"source\":\"CLINICIAN_OBSERVED\",\"presence\":\"PRESENT\""
                + (author == null ? "" : ",\"clinicalAuthor\":\"" + author + "\"") + "}";
        return request(HttpMethod.POST, "/api/v1/chart/records/entries", body,
                recorder, ORG_A, correlation);
    }

    private ResponseEntity<String> verify(String id, String version, String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("If-Match", "\"" + version + "\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri("/api/v1/chart/records/entries/" + id + "/verify")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
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

    private static int countRows(String text) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            // The migration owner is subject to FORCE RLS too, so bind the tenant
            // before reading the rows written inside the request transaction.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.chart_entry WHERE original_text = '" + text + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void verifyingClinicianBecomesClinicalAuthorWhileDataEntryActorRemainsTheRecorder()
            throws Exception {
        ResponseEntity<String> created = record("open authorship", "assistant-a", "author-open-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("\"clinicalAuthor\":null"), created.getBody());
        assertEquals("assistant-a", field(created.getBody(), "dataEntryActor"));
        String id = field(created.getBody(), "id");

        ResponseEntity<String> verified = verify(id, "1", "clinician-b", ORG_A);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("clinician-b", field(verified.getBody(), "clinicalAuthor"));
        assertEquals("assistant-a", field(verified.getBody(), "dataEntryActor"));
        assertEquals("clinician-b", field(verified.getBody(), "verifiedBy"));
        assertNotEquals(field(verified.getBody(), "clinicalAuthor"),
                field(verified.getBody(), "dataEntryActor"));

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_author, data_entry_actor, verified_by "
                            + "FROM public.chart_entry WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("clinician-b", result.getString("clinical_author"));
                assertEquals("assistant-a", result.getString("data_entry_actor"));
                assertEquals("clinician-b", result.getString("verified_by"));
            }
        }
    }

    @Test
    void explicitClinicalAuthorSurvivesVerificationByAnotherClinician() throws Exception {
        ResponseEntity<String> created = recordWithAuthor("authored headache", "clinician-a",
                "clinician-a", "author-explicit-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("clinician-a", field(created.getBody(), "clinicalAuthor"));
        assertEquals("clinician-a", field(created.getBody(), "dataEntryActor"));
        String id = field(created.getBody(), "id");

        ResponseEntity<String> verified = verify(id, "1", "clinician-b", ORG_A);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("clinician-a", field(verified.getBody(), "clinicalAuthor"));
        assertEquals("clinician-a", field(verified.getBody(), "dataEntryActor"));
        assertEquals("clinician-b", field(verified.getBody(), "verifiedBy"));

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_author, verified_by FROM public.chart_entry "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("clinician-a", result.getString("clinical_author"));
                assertEquals("clinician-b", result.getString("verified_by"));
            }
        }
    }

    @Test
    void recorderCannotAttributeClinicalAuthorshipToAnotherSubject() throws Exception {
        ResponseEntity<String> denied = recordWithAuthor("false attribution", "clinician-a",
                "clinician-b", "author-false-claim");
        assertEquals(422, denied.getStatusCode().value(), denied.getBody());
        assertTrue(denied.getBody().contains("INVALID_CLINICAL_AUTHOR"), denied.getBody());
        assertEquals(0, countRows("false attribution"));
    }

    @Test
    void blankAndOverlongClinicalAuthorAreRefused() throws Exception {
        ResponseEntity<String> blank = recordWithAuthor("blank author", "clinician-a",
                "   ", "author-blank");
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertTrue(blank.getBody().contains("INVALID_CLINICAL_AUTHOR"), blank.getBody());

        ResponseEntity<String> overlong = recordWithAuthor("overlong author", "clinician-a",
                "x".repeat(121), "author-overlong");
        assertEquals(422, overlong.getStatusCode().value(), overlong.getBody());
        assertTrue(overlong.getBody().contains("INVALID_CLINICAL_AUTHOR"), overlong.getBody());

        assertEquals(0, countRows("blank author"));
        assertEquals(0, countRows("overlong author"));
    }

    @Test
    void actorWithoutAChartRoleCannotRecordAnAttributedEntry() throws Exception {
        ResponseEntity<String> denied = recordWithAuthor("reception claim", "reception-a",
                "reception-a", "author-wrong-role");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals(0, countRows("reception claim"));
    }

    @Test
    void crossTenantClinicianCannotVerifyAnEntryAndAcquireAuthorship() throws Exception {
        ResponseEntity<String> created = record("other tenant author", "assistant-a",
                "author-cross-tenant-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("\"clinicalAuthor\":null"), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> denied = verify(id, "1", "clinician-other", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_author, verification FROM public.chart_entry "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertNull(result.getString("clinical_author"));
                assertEquals("UNVERIFIED", result.getString("verification"));
            }
        }
    }
}
