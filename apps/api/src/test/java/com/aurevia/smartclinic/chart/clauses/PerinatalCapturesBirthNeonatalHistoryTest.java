package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * CLN-04 perinatal sentence proof at an HTTP entry point: a saved perinatal note captures
 * both the birth history and the neonatal history as structured, required, versioned
 * template fields, restricted to clinical roles and concealed from other tenants.
 *
 * <p>The sentence is currently implemented only as a domain slice: {@code TemplateKind.PERINATAL}
 * and its {@code birthHistory} / {@code neonatalHistory} field definitions are seeded by V87 and
 * validated by {@code TemplateNoteAcceptanceTest}, but no route accepts or returns those
 * structured values. Every method below is a complete proof disabled with an explicit gap
 * reason so a later task can enable it; this suite adds no production code and stays green.</p>
 *
 * <p>Synthetic fixtures only; the fixed identifiers below are test constants, never patient data.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_perinatal_c04_it")
@ActiveProfiles("synthetic-auth")
class PerinatalCapturesBirthNeonatalHistoryTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln04_perinatal_c04_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_perinatal_c04_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln04_perinatal_c04_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedUser(statement, ORG_B, "reception-b", "reception");
            seedPatient(statement, ORG_A, "Synthetic Perinatal Alpha");
            seedPatient(statement, ORG_B, "Synthetic Perinatal Beta");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln04_perinatal_c04_it WITH (FORCE)");
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
        // date satisfies that append-only invariant before any note behaviour runs.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-PERINATAL', 'CLINIC', '"
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

    /**
     * Build a synthetic perinatal note command carrying the two structured template values.
     * The field keys and valid values match the PERINATAL baseline seeded by V87.
     */
    private static String perinatalNote(String birthHistory, String neonatalHistory) {
        // No encounterId: NoteInputs.optionalUuid rejects a non-UUID with 422 before any
        // structured field is examined, and perinatal history does not require an encounter.
        return "{\"patientId\":\"" + PATIENT + "\""
                + ",\"title\":\"synthetic perinatal note\",\"content\":\"Synthetic perinatal summary\""
                + ",\"language\":\"en\",\"templateCode\":\"PERINATAL\",\"templateVersion\":1"
                + ",\"clinicalAuthorSubject\":\"clinician-a\",\"effectiveAt\":\"2026-10-01T09:00:00Z\""
                + ",\"values\":["
                + "{\"key\":\"birthHistory\",\"value\":\"" + birthHistory + "\",\"language\":\"en\"},"
                + "{\"key\":\"neonatalHistory\",\"value\":\"" + neonatalHistory + "\",\"language\":\"en\"}]}";
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
     * Complete positive proof. Disabled because no HTTP route captures structured template
     * field values; see the gap note at
     * {@code docs/spec/clauses/chart/perinatal-captures-birth-neonatal-history.md}.
     */
    @Test
    @Disabled("GAP: birthHistory and neonatalHistory are defined by BaselineTemplates/TemplateKind.PERINATAL "
            + "and validated by TemplateNoteAcceptanceTest, but POST /api/v1/clinical-notes accepts only a free-text "
            + "content string (ClinicalNoteController.CreateRequest). No route accepts or returns a structured "
            + "values list, so a saved perinatal note cannot echo either field. Enabling needs a structured-value "
            + "route, store, migration, OpenAPI entry and staff-web form across at least two tasks.")
    void capturesBirthAndNeonatalHistoryInPerinatalNote() {
        ResponseEntity<String> created = post("/api/v1/clinical-notes",
                perinatalNote("term vaginal birth", "well newborn"),
                "clinician-a", ORG_A, "perinatal-capture");
        // The clinical-notes create route returns 200 with the saved revision; the 201 in the
        // CLN-03 chart fixture belongs to a different controller.
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("birthHistory"), "birth history must be captured");
        assertTrue(created.getBody().contains("term vaginal birth"), "birth history value must be echoed");
        assertTrue(created.getBody().contains("neonatalHistory"), "neonatal history must be captured");
        assertTrue(created.getBody().contains("well newborn"), "neonatal history value must be echoed");
    }

    /**
     * Complete requiredness proof. Disabled with the same missing-route gap as the positive test.
     */
    @Test
    @Disabled("GAP: the PERINATAL baseline marks birthHistory and neonatalHistory required "
            + "(BaselineTemplates, V87), but no HTTP boundary validates structured values, so a blank required "
            + "history cannot be refused with 422 until a clinical-note value route exists.")
    void refusesPerinatalNoteWithBlankRequiredHistory() {
        ResponseEntity<String> blankBirth = post("/api/v1/clinical-notes",
                perinatalNote("   ", "well newborn"),
                "clinician-a", ORG_A, "perinatal-blank-birth");
        assertEquals(422, blankBirth.getStatusCode().value(),
                "a blank birth history must be refused");

        ResponseEntity<String> blankNeonatal = post("/api/v1/clinical-notes",
                perinatalNote("term vaginal birth", "   "),
                "clinician-a", ORG_A, "perinatal-blank-neonatal");
        assertEquals(422, blankNeonatal.getStatusCode().value(),
                "a blank neonatal history must be refused");
    }

    /**
     * Complete role-authorization proof. Disabled with the same missing-route gap as the positive test.
     */
    @Test
    @Disabled("GAP: structured clinical-note capture has no HTTP boundary to authorize; the existing free-text "
            + "POST /api/v1/clinical-notes cannot exercise the PERINATAL field values this test submits, so the "
            + "clinical-role refusal cannot yet be asserted at the entry point.")
    void refusesPerinatalNoteByRoleWithoutPermission() {
        // Prove the structured values are captured for a clinician first, so the refusal below
        // concerns this field capture and not only the existing free-text note route.
        ResponseEntity<String> allowed = post("/api/v1/clinical-notes",
                perinatalNote("term vaginal birth", "well newborn"),
                "clinician-a", ORG_A, "perinatal-role-allowed");
        assertEquals(HttpStatus.OK, allowed.getStatusCode(), allowed.getBody());
        assertTrue(allowed.getBody().contains("birthHistory"), "clinician must capture structured values");

        ResponseEntity<String> denied = post("/api/v1/clinical-notes",
                perinatalNote("term vaginal birth", "well newborn"),
                "reception-a", ORG_A, "perinatal-denied-role");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
    }

    /**
     * Complete tenant-isolation proof. Disabled with the same missing-route gap as the positive test.
     */
    @Test
    @Disabled("GAP: without an HTTP route that stores and returns structured perinatal values there is no "
            + "persisted note identifier to fetch, so forced-RLS concealment from another tenant cannot be "
            + "asserted through the entry point.")
    void concealsPerinatalNoteFromAnotherTenant() {
        ResponseEntity<String> created = post("/api/v1/clinical-notes",
                perinatalNote("term vaginal birth", "well newborn"),
                "clinician-a", ORG_A, "perinatal-tenant");
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains("neonatalHistory"), "tenant note must carry structured values");
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/clinical-notes/" + id, "clinician-b", ORG_B, "perinatal-tenant-b")
                        .getStatusCode(), "a captured perinatal note must be concealed across tenants");
    }
}
