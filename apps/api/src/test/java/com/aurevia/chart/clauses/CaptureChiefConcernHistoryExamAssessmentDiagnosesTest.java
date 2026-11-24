package com.aurevia.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * CLN-03 clause 1 proof at the chart HTTP entry point: capture of the structured
 * longitudinal content the {@code chart.record} domain owns (problems with a
 * versioned diagnosis mapping, allergies including an explicit "none" state,
 * medications, and vitals with units and measurement time), with source,
 * effective dates and a clinical author kept distinct from the data-entry
 * actor. Synthetic fixtures only; no production code is added by this suite.
 *
 * <p>The SOAP narrative sections named by the same sentence (chief concern,
 * history, exam, assessment narrative and plan) are not modelled by this domain;
 * they live in the CLN-04 template slice. A complete proof for them is present
 * but disabled with an explicit gap reason so a later task can enable it.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_cln03_capture_c01_it")
@ActiveProfiles("synthetic-auth")
class CaptureChiefConcernHistoryExamAssessmentDiagnosesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_cln03_capture_c01_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln03_capture_c01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_cln03_capture_c01_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "assistant-a", "clinical_assistant");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedUser(statement, ORG_B, "reception-b", "reception");
            seedPatient(statement, ORG_A, "Synthetic Capture Alpha");
            seedPatient(statement, ORG_B, "Synthetic Capture Beta");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln03_capture_c01_it WITH (FORCE)");
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

    private static void seedPatient(Statement statement, String org, String name) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'reception-" + (ORG_A.equals(org) ? "a" : "b") + "'");
        // V7 requires exactly one of dob / dob_unknown_reason; a synthetic civil
        // date satisfies that append-only invariant before any chart behaviour runs.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-CAPTURE', 'CLINIC', '"
                + name + "', '" + name.toLowerCase() + "', DATE '1990-05-20')");
    }

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private HttpHeaders headers(String subject, String org, String correlation) {
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
        return headers;
    }

    private ResponseEntity<String> post(String uri, String body, String subject, String org, String correlation) {
        return rest.post().uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, org, correlation)))
                .body(body).retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String uri, String subject, String org, String correlation) {
        return rest.get().uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, org, correlation)))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Build a synthetic chart-entry command; extra fields are appended verbatim. */
    private static String entry(String type, String text, String extra, String clinicalAuthor) {
        return "{\"patientId\":\"" + PATIENT + "\",\"type\":\"" + type + "\""
                + ",\"originalText\":\"" + text + "\""
                + ",\"source\":\"" + ("VITAL".equals(type) ? "DEVICE" : "CLINICIAN_OBSERVED") + "\""
                + ",\"presence\":\"PRESENT\""
                + (clinicalAuthor == null ? "" : ",\"clinicalAuthor\":\"" + clinicalAuthor + "\"")
                + extra + "}";
    }

    private void seedCatalog() {
        ResponseEntity<String> defined = post("/api/v1/catalogs/diagnosis/entries",
                "{\"code\":\"R51\",\"value\":\"Headache\"}", "admin-a", ORG_A, "capture-catalog-define");
        assertEquals(HttpStatus.CREATED, defined.getStatusCode(), defined.getBody());
        ResponseEntity<String> approved = post("/api/v1/catalogs/diagnosis/entries/R51/versions/1/approve",
                "{\"note\":\"synthetic\"}", "admin-a", ORG_A, "capture-catalog-approve");
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
    void capturesProblemsAllergiesMedicationsAndVitalsWithUnitsAndProvenance() {
        seedCatalog();

        // A problem/diagnosis keeps the submitted code and text and snapshots a
        // versioned catalog mapping; source and effective dates are captured.
        ResponseEntity<String> problem = post("/api/v1/chart/records/entries",
                entry("PROBLEM", "synthetic headache",
                        ",\"originalCode\":\"R51\",\"codeSystem\":\"diagnosis\","
                                + "\"effectiveFrom\":\"2026-01-10T09:00:00Z\","
                                + "\"effectiveTo\":\"2026-02-10T09:00:00Z\"", "clinician-a"),
                "clinician-a", ORG_A, "capture-problem");
        assertEquals(HttpStatus.CREATED, problem.getStatusCode(), problem.getBody());
        assertEquals("R51", field(problem.getBody(), "originalCode"));
        assertEquals("diagnosis", field(problem.getBody(), "codeSystem"));
        assertEquals("R51", field(problem.getBody(), "mappedCode"));
        assertEquals("Headache", field(problem.getBody(), "mappedText"));
        assertEquals("1", field(problem.getBody(), "catalogVersion"));
        assertEquals("synthetic headache", field(problem.getBody(), "originalText"));
        assertEquals("CLINICIAN_OBSERVED", field(problem.getBody(), "source"));
        assertEquals("UNVERIFIED", field(problem.getBody(), "verification"));
        assertEquals("clinician-a", field(problem.getBody(), "clinicalAuthor"));
        assertEquals("clinician-a", field(problem.getBody(), "dataEntryActor"));
        assertTrue(problem.getBody().contains("\"effectiveFrom\":"), "effective start must be captured");
        assertFalse(problem.getBody().contains("\"effectiveFrom\":null"));

        // An explicit "none" allergy is a distinct clinical assertion, not absence
        // of a record.
        ResponseEntity<String> allergy = post("/api/v1/chart/records/entries",
                "{\"patientId\":\"" + PATIENT + "\",\"type\":\"ALLERGY\""
                        + ",\"originalText\":\"synthetic no known allergies\""
                        + ",\"source\":\"CLINICIAN_OBSERVED\",\"presence\":\"ABSENT\""
                        + ",\"clinicalAuthor\":\"clinician-a\"}",
                "clinician-a", ORG_A, "capture-allergy-none");
        assertEquals(HttpStatus.CREATED, allergy.getStatusCode(), allergy.getBody());
        assertEquals("ABSENT", field(allergy.getBody(), "presence"));

        // A clinical assistant records a medication without claiming clinical
        // authorship: the data-entry actor and the author are separate fields.
        ResponseEntity<String> medication = post("/api/v1/chart/records/entries",
                entry("MEDICATION", "synthetic medication", "", null),
                "assistant-a", ORG_A, "capture-medication");
        assertEquals(HttpStatus.CREATED, medication.getStatusCode(), medication.getBody());
        assertEquals("assistant-a", field(medication.getBody(), "dataEntryActor"));
        assertTrue(medication.getBody().contains("\"clinicalAuthor\":null"),
                "a recorder may leave clinical authorship open");

        // A vital requires a value, a unit and a measurement time.
        ResponseEntity<String> vital = post("/api/v1/chart/records/entries",
                entry("VITAL", "synthetic temperature",
                        ",\"value\":\"37.1\",\"unit\":\"Celsius\","
                                + "\"measuredAt\":\"2026-12-01T10:00:00Z\"", "clinician-a"),
                "clinician-a", ORG_A, "capture-vital");
        assertEquals(HttpStatus.CREATED, vital.getStatusCode(), vital.getBody());
        assertEquals("37.1", field(vital.getBody(), "value"));
        assertEquals("Celsius", field(vital.getBody(), "unit"));
        assertTrue(vital.getBody().contains("\"measuredAt\":"), "measurement time must be captured");
        assertFalse(vital.getBody().contains("\"measuredAt\":null"));

        // All four families are retrieved together as one longitudinal chart.
        ResponseEntity<String> chart = get("/api/v1/chart/records/patients/" + PATIENT + "/entries",
                "clinician-a", ORG_A, "capture-list");
        assertEquals(HttpStatus.OK, chart.getStatusCode(), chart.getBody());
        assertTrue(chart.getBody().contains("synthetic headache"));
        assertTrue(chart.getBody().contains("synthetic no known allergies"));
        assertTrue(chart.getBody().contains("synthetic medication"));
        assertTrue(chart.getBody().contains("synthetic temperature"));
    }

    @Test
    void refusesVitalWithoutUnitsAndMeasurementTime() {
        ResponseEntity<String> noUnit = post("/api/v1/chart/records/entries",
                entry("VITAL", "synthetic temperature",
                        ",\"value\":\"37.1\",\"measuredAt\":\"2026-12-01T10:00:00Z\"", "clinician-a"),
                "clinician-a", ORG_A, "capture-vital-no-unit");
        assertEquals(422, noUnit.getStatusCode().value(),
                "a vital without a unit must be refused");

        ResponseEntity<String> noTime = post("/api/v1/chart/records/entries",
                entry("VITAL", "synthetic temperature",
                        ",\"value\":\"37.1\",\"unit\":\"Celsius\"", "clinician-a"),
                "clinician-a", ORG_A, "capture-vital-no-time");
        assertEquals(422, noTime.getStatusCode().value(),
                "a vital without a measurement time must be refused");
    }

    @Test
    void refusesCaptureByRoleWithoutChartPermission() {
        ResponseEntity<String> denied = post("/api/v1/chart/records/entries",
                entry("PROBLEM", "synthetic denied", "", "reception-a"),
                "reception-a", ORG_A, "capture-denied-role");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
    }

    @Test
    void concealsCapturedEntryFromAnotherTenant() {
        ResponseEntity<String> created = post("/api/v1/chart/records/entries",
                entry("PROBLEM", "synthetic tenant-scoped", "", "clinician-a"),
                "clinician-a", ORG_A, "capture-tenant");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/chart/records/entries/" + id, "clinician-b", ORG_B, "capture-tenant-b")
                        .getStatusCode(), "a captured entry must be concealed across tenants");
    }

    /**
     * Complete proof for the SOAP narrative part of clause 1. Disabled because
     * the CLN-03 chart domain has no such entry family; see the gap note at
     * {@code docs/spec/clauses/chart/capture-chief-concern-history-exam-assessment-diagnoses.md}.
     */
    @Test
    @Disabled("GAP: chief concern, history, exam, assessment narrative and plan are captured only "
            + "as free-text CLN-04 SOAP template fields (clinical.templates.BaselineTemplates, "
            + "TemplateKind.SOAP) and note revisions (clinical.notes), not as chart.record entries. "
            + "No /api/v1/chart/records entry type accepts them. Enabling needs a new chart family "
            + "plus migration, service, controller, OpenAPI and web changes across at least two tasks.")
    void capturesChiefConcernHistoryExamAssessmentAndPlanAsChartEntries() {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"section\":\"CHIEF_CONCERN\""
                + ",\"text\":\"synthetic chief concern\",\"history\":\"synthetic history\""
                + ",\"exam\":\"synthetic exam\",\"assessment\":\"synthetic assessment\""
                + ",\"plan\":\"synthetic plan\"}";
        ResponseEntity<String> created = post("/api/v1/chart/records/entries", body,
                "clinician-a", ORG_A, "capture-soap-narrative");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("synthetic chief concern"));
        assertTrue(created.getBody().contains("synthetic history"));
        assertTrue(created.getBody().contains("synthetic exam"));
        assertTrue(created.getBody().contains("synthetic assessment"));
        assertTrue(created.getBody().contains("synthetic plan"));
    }
}
