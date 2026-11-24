package com.aurevia.labflow.collection;

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

/** LAB-01 end-to-end specimen collection and label printing with forced row-level security. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_labflow01_it")
@ActiveProfiles("synthetic-auth")
class SpecimenCollectionIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_labflow01_it";
    private static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS orevia_labflow01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_labflow01_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "lab-a", "lab_collector");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "lab-b", "lab_collector");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_labflow01_it WITH (FORCE)");
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

    private String patient(String org, String mrn) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body,
                "reception-a", org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String accession(String number, String patient) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"LAB-A\","
                + "\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"ORD/1\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/lab/accessions", body,
                "lab-a", ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private ResponseEntity<String> collect(String id, String number, String patient, int version,
                                           String subject, String org) {
        String body = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"specimenType\":\"PLASMA\",\"siteCode\":\"SITE-A\","
                + "\"collector\":\"Sam Collector\",\"collectedAt\":\"2026-11-19T10:15:30Z\","
                + "\"quality\":\"acceptable\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/specimens/" + id + "/collect", body, subject, org);
    }

    private ResponseEntity<String> label(String id, String subject, String org) {
        return request(HttpMethod.GET, "/api/v1/lab/specimens/" + id + "/label", null, subject, org);
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
    void collectionAndLabelPersistAndMismatchIsAudited() {
        String patient = patient(ORG_A, "MRN-LABFLOW-1");
        String otherPatient = patient(ORG_A, "MRN-LABFLOW-2");
        String id = accession("ACC-1", patient);

        ResponseEntity<String> mismatch = collect(id, "ACC-1", otherPatient, 1, "lab-a", ORG_A);
        assertEquals(422, mismatch.getStatusCode().value(), mismatch.getBody());
        assertEquals("PATIENT_ACCESSION_MISMATCH", field(mismatch.getBody(), "code"));

        ResponseEntity<String> collected = collect(id, "ACC-1", patient, 1, "lab-a", ORG_A);
        assertEquals(HttpStatus.OK, collected.getStatusCode(), collected.getBody());
        assertEquals("collected", field(collected.getBody(), "state"));
        assertEquals("acceptable", field(collected.getBody(), "quality"));

        ResponseEntity<String> label = label(id, "lab-a", ORG_A);
        assertEquals(HttpStatus.OK, label.getStatusCode(), label.getBody());
        String json = label.getBody();
        assertEquals("ACC-1", field(json, "accessionNo"));
        assertEquals("LAB-A", field(json, "accessionIssuer"));
        assertEquals("Synthetic Patient", field(json, "patientName"));
        assertEquals("MRN-LABFLOW-1", field(json, "patientMrn"));
        assertEquals("CLINIC-A", field(json, "patientMrnIssuer"));
        assertEquals("PLASMA", field(json, "specimenType"));
        assertEquals(field(collected.getBody(), "collectedAt"), field(json, "collectedAt"));

        ResponseEntity<String> audit = request(HttpMethod.GET,
                "/api/v1/audit-events?action=LAB_SPECIMEN_COLLECT&outcome=MISMATCH",
                null, "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, audit.getStatusCode(), audit.getBody());
        assertTrue(audit.getBody().contains("\"action\":\"LAB_SPECIMEN_COLLECT\""), audit.getBody());
        assertTrue(audit.getBody().contains("\"outcome\":\"MISMATCH\""), audit.getBody());
        assertTrue(audit.getBody().contains("\"resourceType\":\"SPECIMEN\""), audit.getBody());
        assertFalse(audit.getBody().contains("MRN-LABFLOW-2"), audit.getBody());
    }

    @Test
    void anotherTenantIsRefusedCollectionAndLabel() {
        String patient = patient(ORG_A, "MRN-LABFLOW-3");
        String id = accession("ACC-9", patient);

        assertEquals(HttpStatus.NOT_FOUND,
                collect(id, "ACC-9", patient, 1, "lab-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                label(id, "lab-b", ORG_B).getStatusCode());
        // A label is only produced once collection is recorded (LAB-01 label completeness).
        assertEquals(409, label(id, "lab-a", ORG_A).getStatusCode().value());
        assertEquals(HttpStatus.OK,
                collect(id, "ACC-9", patient, 1, "lab-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, label(id, "lab-a", ORG_A).getStatusCode());
    }
}
