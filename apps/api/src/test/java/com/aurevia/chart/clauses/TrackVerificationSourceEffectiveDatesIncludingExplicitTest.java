package com.aurevia.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

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

import org.flywaydb.core.Flyway;

/**
 * CLN-03 clause 2 (API evidence): track verification, source and effective
 * dates, including explicit unknown and none states.
 *
 * <p>The positive test drives the real HTTP entry point against PostgreSQL and
 * asserts that provenance is captured and persisted: the source of the
 * information, the clinical effective period, the explicit {@code ABSENT}
 * (none) and {@code UNKNOWN} assertions with a coded reason, and the
 * verification actor, instant and version. The negative tests assert the rule
 * refuses what it must refuse: an unknown assertion without a reason, an
 * effective period that ends before it starts, a role that may not verify, and
 * a stale verification precondition.</p>
 *
 * <p>Synthetic fixtures only; forced row-level security confines every row to
 * the active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_chart_provenance_it")
@ActiveProfiles("synthetic-auth")
class TrackVerificationSourceEffectiveDatesIncludingExplicitTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_chart_provenance_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EFFECTIVE_TO = Instant.parse("2026-06-30T23:59:59Z");

    @LocalServerPort
    int port;

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_chart_provenance_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_chart_provenance_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, "clinician-a", "clinician");
            seedUser(statement, "reception-a", "reception");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reception-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-CLN03', 'CLINIC', "
                    + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_chart_provenance_it WITH (FORCE)");
        }
    }

    /** Create an active organization member with one role for the synthetic fixtures. */
    private static void seedUser(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + ORG_A + "', 'Chart Provenance Org' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + ORG_A + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG_A + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void provenanceSourceEffectiveDatesAndExplicitStatesAreTrackedThroughHttp() throws Exception {
        ResponseEntity<String> none = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"ALLERGY\""
                        + ",\"originalText\":\"no known allergies\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"ABSENT\""
                        + ",\"effectiveFrom\":\"2026-01-01T00:00:00Z\""
                        + ",\"effectiveTo\":\"2026-06-30T23:59:59Z\""
                        + ",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a");
        assertEquals(HttpStatus.CREATED, none.getStatusCode(), none.getBody());
        assertEquals("CLINICIAN_OBSERVED", field(none.getBody(), "source"));
        assertEquals("ABSENT", field(none.getBody(), "presence"));
        assertEquals("UNVERIFIED", field(none.getBody(), "verification"));
        String noneId = field(none.getBody(), "id");

        ResponseEntity<String> unknown = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"ALLERGY\""
                        + ",\"originalText\":\"allergy status not assessed\",\"source\":\"PATIENT_REPORTED\""
                        + ",\"presence\":\"UNKNOWN\",\"unknownReason\":\"not-asked\""
                        + ",\"effectiveFrom\":\"2026-02-01T00:00:00Z\""
                        + ",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a");
        assertEquals(HttpStatus.CREATED, unknown.getStatusCode(), unknown.getBody());
        assertEquals("PATIENT_REPORTED", field(unknown.getBody(), "source"));
        assertEquals("UNKNOWN", field(unknown.getBody(), "presence"));
        assertEquals("not-asked", field(unknown.getBody(), "unknownReason"));
        String unknownId = field(unknown.getBody(), "id");

        // Verify the explicit-none entry at the version the client read.
        ResponseEntity<String> verified = verifyAs("clinician-a", noneId, "1");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("VERIFIED", field(verified.getBody(), "verification"));
        assertEquals("clinician-a", field(verified.getBody(), "verifiedBy"));
        assertEquals("2", field(verified.getBody(), "version"));

        // A fresh read must show the tracked verification, and the stored row must
        // keep source, effective dates and both explicit assertion states intact.
        ResponseEntity<String> reread = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + noneId, null, "clinician-a");
        assertEquals(HttpStatus.OK, reread.getStatusCode(), reread.getBody());
        assertEquals("VERIFIED", field(reread.getBody(), "verification"));
        assertEquals("CLINICIAN_OBSERVED", field(reread.getBody(), "source"));
        assertEquals("ABSENT", field(reread.getBody(), "presence"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT source, presence, unknown_reason, verification, verified_by, verified_at, "
                            + "effective_from, effective_to FROM public.chart_entry "
                            + "WHERE id = CAST('" + noneId + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("CLINICIAN_OBSERVED", result.getString("source"));
                assertEquals("ABSENT", result.getString("presence"));
                assertEquals("VERIFIED", result.getString("verification"));
                assertEquals("clinician-a", result.getString("verified_by"));
                assertNotNull(result.getTimestamp("verified_at"), "verification instant must be tracked");
                assertEquals(EFFECTIVE_FROM, toInstant(result, "effective_from"));
                assertEquals(EFFECTIVE_TO, toInstant(result, "effective_to"));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT source, presence, unknown_reason, verification, effective_from "
                            + "FROM public.chart_entry WHERE id = CAST('" + unknownId + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("PATIENT_REPORTED", result.getString("source"));
                assertEquals("UNKNOWN", result.getString("presence"));
                assertEquals("not-asked", result.getString("unknown_reason"));
                assertEquals("UNVERIFIED", result.getString("verification"));
                assertEquals(Instant.parse("2026-02-01T00:00:00Z"), toInstant(result, "effective_from"));
            }
        }
    }

    @Test
    void unknownAssertionWithoutAReasonIsRefused() {
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"ALLERGY\""
                        + ",\"originalText\":\"unknown allergy\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"UNKNOWN\""
                        + ",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a");
        assertEquals(422, response.getStatusCode().value(), response.getBody());
    }

    @Test
    void effectiveEndBeforeEffectiveStartIsRefused() {
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"MEDICATION\""
                        + ",\"originalText\":\"period reversed\",\"source\":\"EXTERNAL_RECORD\""
                        + ",\"presence\":\"PRESENT\""
                        + ",\"effectiveFrom\":\"2026-06-01T00:00:00Z\""
                        + ",\"effectiveTo\":\"2026-05-01T00:00:00Z\""
                        + ",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a");
        assertEquals(422, response.getStatusCode().value(), response.getBody());
    }

    @Test
    void roleWithoutClinicalAuthorityCannotVerifyAnEntry() {
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"PROBLEM\""
                        + ",\"originalText\":\"verify denied\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"PRESENT\",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals(HttpStatus.FORBIDDEN,
                verifyAs("reception-a", field(created.getBody(), "id"), "1").getStatusCode());
    }

    @Test
    void staleVersionCannotVerifyAnEntry() {
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"PROBLEM\""
                        + ",\"originalText\":\"stale verify\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"PRESENT\",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals(412, verifyAs("clinician-a", id, "9").getStatusCode().value());
        assertEquals("UNVERIFIED", field(request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + id, null, "clinician-a").getBody(), "verification"));
    }

    /** Send a chart command as the given synthetic subject in the active organization. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", ORG_A);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Post a version-bound verification as the given synthetic subject. */
    private ResponseEntity<String> verifyAs(String subject, String id, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", ORG_A);
        headers.set("If-Match", "\"" + version + "\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri("/api/v1/chart/records/entries/" + id + "/verify")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Read a scalar field out of the simple flat JSON responses used by these tests. */
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

    /** Read a timestamptz column as an instant without timezone-dependent shifting. */
    private static Instant toInstant(ResultSet result, String column) throws Exception {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
