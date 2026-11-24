package com.aurevia.it.chart.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

import com.aurevia.chart.record.ChartRepository;

/** S-G1-03 acceptance: CLN-03 provenance, versioned verification and tenant isolation. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_chart_record_it")
@ActiveProfiles("synthetic-auth")
class ChartRecordIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_chart_record_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    ChartRepository repository;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_chart_record_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_chart_record_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                // Only the production migrations are applied: chart_entry must be
                // created by the same schema that production Flyway runs.
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "assistant-a", "clinical_assistant");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedDeactivatedUser(statement, ORG_A, "deactivated-a", "clinician");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedUser(statement, ORG_B, "reception-b", "reception");
            seedPatient(statement, ORG_A, "Synthetic Patient A");
            seedPatient(statement, ORG_B, "Synthetic Patient B");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_chart_record_it WITH (FORCE)");
        }
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
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedDeactivatedUser(Statement statement, String org, String subject,
                                            String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity "
                + "(organization_id, subject, display_name, status) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "', 'deactivated')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement, String org, String name) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'reception-" + (ORG_A.equals(org) ? "a" : "b") + "'");
        // V7 requires exactly one of dob / dob_unknown_reason. Seed a synthetic
        // civil date so the fixture satisfies the append-only patient invariant
        // instead of failing @BeforeAll before any chart behaviour is exercised.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-CHART', 'CLINIC', '"
                + name + "', '" + name.toLowerCase() + "', DATE '1990-05-20')");
    }

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
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

    private ResponseEntity<String> record(String type, String text, String code, String system,
                                          String extra, String actor, String org, String correlation) {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"type\":\"" + type + "\""
                + ",\"originalText\":\"" + text + "\""
                + (code == null ? "" : ",\"originalCode\":\"" + code + "\"")
                + (system == null ? "" : ",\"codeSystem\":\"" + system + "\"")
                + ",\"source\":\"" + ("VITAL".equals(type) ? "DEVICE" : "CLINICIAN_OBSERVED") + "\""
                + ",\"presence\":\"PRESENT\"" + extra
                + ",\"clinicalAuthor\":\"" + actor + "\"}";
        return request(HttpMethod.POST, "/api/v1/chart/records/entries", body, actor, org, correlation);
    }

    private void seedCatalog() {
        ResponseEntity<String> defined = request(HttpMethod.POST,
                "/api/v1/catalogs/diagnosis/entries",
                "{\"code\":\"R51\",\"value\":\"Headache\"}", "admin-a", ORG_A, "cat-define");
        assertEquals(HttpStatus.CREATED, defined.getStatusCode(), defined.getBody());
        ResponseEntity<String> approved = request(HttpMethod.POST,
                "/api/v1/catalogs/diagnosis/entries/R51/versions/1/approve",
                "{\"note\":\"synthetic\"}", "admin-a", ORG_A, "cat-approve");
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
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

    @Test
    void recordsReadsAndAuditsAllFourKindsWithProvenance() throws Exception {
        seedCatalog();
        ResponseEntity<String> problem = record("PROBLEM", "headache", "R51", "diagnosis",
                "", "clinician-a", ORG_A, "chart-record-1");
        assertEquals(HttpStatus.CREATED, problem.getStatusCode(), problem.getBody());
        assertEquals("Headache", field(problem.getBody(), "mappedText"));
        assertEquals("R51", field(problem.getBody(), "originalCode"));
        assertEquals("headache", field(problem.getBody(), "originalText"));
        assertEquals("clinician-a", field(problem.getBody(), "clinicalAuthor"));
        assertEquals("clinician-a", field(problem.getBody(), "dataEntryActor"));
        assertEquals("UNVERIFIED", field(problem.getBody(), "verification"));
        String id = field(problem.getBody(), "id");

        ResponseEntity<String> allergy = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"ALLERGY\""
                        + ",\"originalText\":\"no known allergies\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"ABSENT\",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a", ORG_A, "chart-record-2");
        assertEquals(HttpStatus.CREATED, allergy.getStatusCode(), allergy.getBody());
        assertEquals("ABSENT", field(allergy.getBody(), "presence"));

        ResponseEntity<String> medication = record("MEDICATION", "aspirin", "A01", "local-drug",
                "", "assistant-a", ORG_A, "chart-record-3");
        assertEquals(HttpStatus.CREATED, medication.getStatusCode(), medication.getBody());
        assertEquals("aspirin", field(medication.getBody(), "originalText"));
        assertEquals("assistant-a", field(medication.getBody(), "dataEntryActor"));

        ResponseEntity<String> vital = record("VITAL", "temperature", null, null,
                ",\"value\":\"37.1\",\"unit\":\"Celsius\",\"measuredAt\":\"2026-12-01T10:00:00Z\"",
                "clinician-a", ORG_A, "chart-record-4");
        assertEquals(HttpStatus.CREATED, vital.getStatusCode(), vital.getBody());
        assertEquals("37.1", field(vital.getBody(), "value"));
        assertEquals("Celsius", field(vital.getBody(), "unit"));

        ResponseEntity<String> list = request(HttpMethod.GET,
                "/api/v1/chart/records/patients/" + PATIENT + "/entries", null, "clinician-a", ORG_A, "chart-list");
        assertEquals(HttpStatus.OK, list.getStatusCode(), list.getBody());
        assertTrue(list.getBody().contains("headache"));
        assertTrue(list.getBody().contains("aspirin"));
        ResponseEntity<String> one = request(HttpMethod.GET, "/api/v1/chart/records/entries/" + id,
                null, "clinician-a", ORG_A, "chart-get");
        assertEquals(HttpStatus.OK, one.getStatusCode(), one.getBody());
        assertEquals(id, field(one.getBody(), "id"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // audit_event has FORCE RLS: the migration owner still needs the
            // transaction-local tenant context to see any rows at all. A direct
            // assertion must bind the tenant context or the append-only rows are
            // concealed from the reader.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE action = 'CHART_RECORD' "
                    + "AND outcome = 'SUCCESS' AND correlation_id LIKE 'chart-record%'")) {
                assertTrue(result.next());
                assertEquals(4, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_verify_chain('" + ORG_A + "') WHERE NOT ok")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void verificationIsVersionBoundAndConcurrent() throws Exception {
        ResponseEntity<String> created = record("PROBLEM", "verification target", null, null,
                "", "clinician-a", ORG_A, "verify-target");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        assertEquals(428, request(HttpMethod.POST, "/api/v1/chart/records/entries/" + id + "/verify",
                null, "clinician-a", ORG_A, "v-no-header").getStatusCode().value());
        assertEquals(422, verifyWith(id, "nope").getStatusCode().value());
        assertEquals(412, verifyWith(id, "9").getStatusCode().value());
        ResponseEntity<String> verified = verifyWith(id, "1");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("VERIFIED", field(verified.getBody(), "verification"));
        assertEquals("2", field(verified.getBody(), "version"));
        assertEquals("clinician-a", field(verified.getBody(), "verifiedBy"));
        assertEquals(409, verifyWith(id, "2").getStatusCode().value());

        ResponseEntity<String> race = record("PROBLEM", "race target", null, null,
                "", "clinician-a", ORG_A, "verify-race");
        String raceId = field(race.getBody(), "id");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> verifyWith(raceId, "1").getStatusCode().value();
            Future<Integer> first = pool.submit(task);
            Future<Integer> second = pool.submit(task);
            List<Integer> outcomes = List.of(first.get(), second.get());
            assertEquals(1, outcomes.stream().filter(code -> code == 200).count());
            assertEquals(1, outcomes.stream().filter(code -> code == 412).count());
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<String> verifyWith(String id, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", "clinician-a");
        headers.set("X-Active-Organization", ORG_A);
        headers.set("If-Match", "\"" + version + "\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri("/api/v1/chart/records/entries/" + id + "/verify")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    @Test
    void tenantIsolationConcealsCrossTenantRowsAndForcesChecks() throws Exception {
        ResponseEntity<String> created = record("PROBLEM", "tenant-scoped", null, null,
                "", "clinician-a", ORG_A, "tenant-record");
        String id = field(created.getBody(), "id");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET, "/api/v1/chart/records/entries/" + id,
                null, "clinician-b", ORG_B, "tenant-get-b").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, verifyWithAs("clinician-b", ORG_B, id).getStatusCode());
        ResponseEntity<String> bList = request(HttpMethod.GET,
                "/api/v1/chart/records/patients/" + PATIENT + "/entries", null, "clinician-b", ORG_B, "tenant-list-b");
        assertEquals(HttpStatus.OK, bList.getStatusCode());
        assertFalse(bList.getBody().contains("tenant-scoped"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.chart_entry WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            boolean rejected = false;
            try {
                statement.execute("INSERT INTO public.chart_entry "
                        + "(organization_id, id, patient_id, type, original_text, source, presence, "
                        + "verification, clinical_author, data_entry_actor) VALUES "
                        + "('" + ORG_B + "', gen_random_uuid(), CAST('" + PATIENT + "' AS uuid), "
                        + "'PROBLEM', 'x', 'CLINICIAN_OBSERVED', 'PRESENT', 'UNVERIFIED', 'x', 'x')");
            } catch (Exception expected) {
                rejected = true;
            }
            assertTrue(rejected, "WITH CHECK must reject a mismatched organization");
        }
        assertNotNull(repository);
    }

    private ResponseEntity<String> verifyWithAs(String subject, String org, String id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("If-Match", "\"1\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri("/api/v1/chart/records/entries/" + id + "/verify")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    @Test
    void authorizationAndInputBoundariesFailClosed() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{}", null, ORG_A, "no-subject").getStatusCode());
        for (String role : List.of("reception-a", "admin-a", "auditor-a")) {
            assertEquals(HttpStatus.FORBIDDEN,
                    record("PROBLEM", "denied " + role, null, null, "", role, ORG_A, "denied-" + role)
                            .getStatusCode());
        }
        assertEquals(HttpStatus.FORBIDDEN, record("PROBLEM", "deactivated", null, null, "",
                "deactivated-a", ORG_A, "deactivated").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/chart/records/entries/99999999-9999-9999-9999-999999999999",
                null, "clinician-a", ORG_A, "unknown-entry").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"99999999-9999-9999-9999-999999999999\",\"type\":\"PROBLEM\""
                        + ",\"originalText\":\"orphan\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"PRESENT\",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a", ORG_A, "unknown-patient").getStatusCode());
        assertEquals(422, record("VITAL", "incomplete", null, null, "",
                "clinician-a", ORG_A, "bad-vital").getStatusCode().value());
        assertEquals(422, record("PROBLEM", "bad code", "R51", null, "",
                "clinician-a", ORG_A, "bad-code").getStatusCode().value());
        ResponseEntity<String> unmapped = record("PROBLEM", "unmapped", "ZZZ", "diagnosis", "",
                "clinician-a", ORG_A, "unmapped");
        assertEquals(HttpStatus.CREATED, unmapped.getStatusCode(), unmapped.getBody());
        assertEquals("ZZZ", field(unmapped.getBody(), "originalCode"));
        assertTrue(unmapped.getBody().contains("\"mappedCode\":null"));
    }

    @Test
    void blankUnknownReasonIsNormalizedBeforeTheDatabaseCheck() {
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"ALLERGY\""
                        + ",\"originalText\":\"no known allergies\",\"source\":\"CLINICIAN_OBSERVED\""
                        + ",\"presence\":\"ABSENT\",\"unknownReason\":\" \""
                        + ",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a", ORG_A, "chart-blank-reason");
        // V13 allows a reason only on UNKNOWN; a blank one must be normalized to
        // null before INSERT or the CHECK would surface as a misleading 409.
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("\"unknownReason\":null"), created.getBody());
    }

    @Test
    void legacyChartWriteRouteIsRemoved() {
        // A second, unhardened chart slice used to expose the same chart_entry
        // table on /api/v1/chart. Its write path left clinical_author null on
        // verification and accepted unbounded trust-boundary text. Only the
        // hardened /api/v1/chart/records contract may remain reachable (CLN-03).
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.POST, "/api/v1/chart/entries",
                        "{\"patientId\":\"" + PATIENT + "\",\"type\":\"PROBLEM\""
                                + ",\"originalText\":\"legacy write\",\"source\":\"CLINICIAN_OBSERVED\""
                                + ",\"presence\":\"PRESENT\"}",
                        "clinician-a", ORG_A, "legacy-write").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/chart/patients/" + PATIENT + "/entries",
                        null, "clinician-a", ORG_A, "legacy-list").getStatusCode());
    }

    @Test
    void productionMigrationsCreateForcedRlsChartEntry() throws Exception {
        // A real row in ORG_A keeps the cross-tenant assertion from passing
        // vacuously on an empty table.
        ResponseEntity<String> created = record("PROBLEM", "chart-ddl-probe", null, null,
                "", "clinician-a", ORG_A, "chart-ddl-probe");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                            + "WHERE oid = 'public.chart_entry'::regclass")) {
                assertTrue(result.next(), "chart_entry must be created by the production migrations");
                assertTrue(result.getBoolean("relrowsecurity"), "chart_entry must enable row level security");
                assertTrue(result.getBoolean("relforcerowsecurity"), "chart_entry must force row level security");
            }
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = 'clinician-b'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.chart_entry WHERE original_text = 'chart-ddl-probe'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "forced RLS must conceal a cross-tenant read");
            }
        }
    }

    @Test
    void verificationOfRecorderOpenEntryAssignsAccountableClinicalAuthor() throws Exception {
        // A clinical assistant records a vital without claiming clinical
        // authorship. The surviving public route must let a clinician verify it
        // and attribute the accountable author in both the response and the row.
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"VITAL\""
                        + ",\"originalText\":\"temperature\",\"source\":\"DEVICE\""
                        + ",\"presence\":\"PRESENT\",\"value\":\"37.1\",\"unit\":\"Celsius\""
                        + ",\"measuredAt\":\"2026-12-01T10:00:00Z\"}",
                "assistant-a", ORG_A, "open-author-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("\"clinicalAuthor\":null"), created.getBody());
        assertEquals("assistant-a", field(created.getBody(), "dataEntryActor"));
        String id = field(created.getBody(), "id");

        ResponseEntity<String> verified = verifyWithAs("clinician-a", ORG_A, id);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("VERIFIED", field(verified.getBody(), "verification"));
        assertEquals("clinician-a", field(verified.getBody(), "verifiedBy"));
        assertEquals("clinician-a", field(verified.getBody(), "clinicalAuthor"));
        assertEquals("assistant-a", field(verified.getBody(), "dataEntryActor"));
        assertNotEquals(field(verified.getBody(), "dataEntryActor"),
                field(verified.getBody(), "clinicalAuthor"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_author, verified_by FROM public.chart_entry "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("clinician-a", result.getString("clinical_author"));
                assertEquals("clinician-a", result.getString("verified_by"));
            }
        }
    }

}
