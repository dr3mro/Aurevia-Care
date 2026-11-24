package com.aurevia.labflow.results;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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

/** LAB-02 end-to-end result entry, verification, flags, segregation and critical routing. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_labflow03_it")
@ActiveProfiles("synthetic-auth")
class ResultIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_labflow03_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_labflow03_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_labflow03_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "lab-collector-a", "lab_collector");
            seed(statement, ORG_A, "lab-verifier-a", "lab_verifier");
            seed(statement, ORG_A, "lab-both-a", "lab_collector");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + ORG_A + "', m.id, 'lab_verifier', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = 'lab-both-a'");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "lab-collector-b", "lab_collector");
            seed(statement, ORG_B, "lab-verifier-b", "lab_verifier");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_labflow03_it WITH (FORCE)");
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

    private String patient(String org, String mrn, String subject) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body, subject, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String collectedSpecimen(String number, String patient) {
        String accessionBody = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"LAB-A\","
                + "\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"ORD/1\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/lab/accessions",
                accessionBody, "lab-collector-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        String collectBody = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"specimenType\":\"PLASMA\",\"siteCode\":\"SITE-A\","
                + "\"collector\":\"Sam Collector\",\"collectedAt\":\"2026-11-21T09:00:00Z\","
                + "\"quality\":\"acceptable\",\"expectedVersion\":1}";
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/lab/specimens/" + id + "/collect", collectBody,
                "lab-collector-a", ORG_A).getStatusCode());
        return id;
    }

    private static String entryBody(String patient, boolean critical) {
        String observation = critical
                ? "{\"code\":\"K\",\"kind\":\"numeric\",\"presence\":\"present\",\"value\":\"6.5\","
                        + "\"qualifier\":\"none\",\"unit\":\"mmol/L\",\"method\":\"photometry\","
                        + "\"instrument\":\"analyser-1\",\"referenceLow\":\"3.5\",\"referenceHigh\":\"5.0\","
                        + "\"criticalLow\":\"6.0\",\"criticalHigh\":\"6.4\"}"
                : "{\"code\":\"GLU\",\"kind\":\"numeric\",\"presence\":\"present\",\"value\":\"5.5\","
                        + "\"qualifier\":\"none\",\"unit\":\"mmol/L\",\"method\":\"photometry\","
                        + "\"instrument\":\"analyser-1\",\"referenceLow\":\"4.0\",\"referenceHigh\":\"6.0\"}";
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"ACC-" + (critical ? "C" : "N")
                + "\",\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":2,\"owner\":\"clinician-a\","
                + "\"queue\":\"critical-queue\",\"dueAfter\":\"PT30M\",\"escalation\":\"PHONE\","
                + "\"observations\":[" + observation + "]}";
    }

    private static String decisionBody(String patient, int version) {
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"ACC-C\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":" + version + ",\"owner\":\"clinician-a\","
                + "\"queue\":\"critical-queue\",\"reason\":\"a documented correction\"}";
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

    private static int criticalCount(String reportId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'lab-verifier-a'");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.critical_result WHERE source_id = ?")) {
                query.setString(1, reportId);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        }
    }

    @Test
    void flagsSegregationStatesAndCriticalWorkflowAreReachableOverHttp() throws Exception {
        String patient = patient(ORG_A, "MRN-LABFLOW-03-A", "reception-a");
        String otherPatient = patient(ORG_A, "MRN-LABFLOW-03-B", "reception-a");
        String id = collectedSpecimen("ACC-C", patient);

        ResponseEntity<String> wrongPatient = request(HttpMethod.POST, "/api/v1/lab/results/" + id,
                entryBody(otherPatient, true), "lab-collector-a", ORG_A);
        assertEquals(422, wrongPatient.getStatusCode().value(), wrongPatient.getBody());
        assertEquals("PATIENT_ACCESSION_MISMATCH", field(wrongPatient.getBody(), "code"));

        ResponseEntity<String> entered = request(HttpMethod.POST, "/api/v1/lab/results/" + id,
                entryBody(patient, true), "lab-both-a", ORG_A);
        assertEquals(HttpStatus.OK, entered.getStatusCode(), entered.getBody());
        assertEquals("preliminary", field(entered.getBody(), "state"));
        assertEquals("critical", field(entered.getBody(), "abnormalFlag"));
        assertEquals(0, criticalCount(id), "no alert before verification");

        ResponseEntity<String> selfVerified = request(HttpMethod.POST,
                "/api/v1/lab/results/" + id + "/verify", decisionBody(patient, 1), "lab-both-a", ORG_A);
        assertEquals(422, selfVerified.getStatusCode().value(), selfVerified.getBody());
        assertEquals("SELF_VERIFICATION", field(selfVerified.getBody(), "code"));

        ResponseEntity<String> verified = request(HttpMethod.POST,
                "/api/v1/lab/results/" + id + "/verify", decisionBody(patient, 1), "lab-verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("final", field(verified.getBody(), "state"));
        assertEquals("critical", field(verified.getBody(), "abnormalFlag"));
        assertEquals(1, criticalCount(id), "critical verification starts the XCH-05 workflow");

        ResponseEntity<String> corrected = request(HttpMethod.POST,
                "/api/v1/lab/results/" + id + "/correct", decisionBody(patient, 2), "lab-verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertEquals("corrected", field(corrected.getBody(), "state"));
        assertEquals(1, criticalCount(id), "a correction must not duplicate the XCH-05 alert");

        ResponseEntity<String> errored = request(HttpMethod.POST,
                "/api/v1/lab/results/" + id + "/error", decisionBody(patient, 3), "lab-verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, errored.getStatusCode(), errored.getBody());
        assertEquals("entered_in_error", field(errored.getBody(), "state"));

        ResponseEntity<String> history = request(HttpMethod.GET,
                "/api/v1/lab/results/" + id, null, "lab-verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, history.getStatusCode(), history.getBody());
        assertEquals("entered_in_error", field(history.getBody(), "state"));
    }

    @Test
    void anotherTenantIsRefusedEntryAndVerification() {
        String patient = patient(ORG_A, "MRN-LABFLOW-03-C", "reception-a");
        String id = collectedSpecimen("ACC-N", patient);

        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST, "/api/v1/lab/results/" + id,
                entryBody(patient, false), "lab-collector-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/lab/results/" + id + "/verify", decisionBody(patient, 1),
                "lab-verifier-b", ORG_B).getStatusCode());

        ResponseEntity<String> entered = request(HttpMethod.POST, "/api/v1/lab/results/" + id,
                entryBody(patient, false), "lab-collector-a", ORG_A);
        assertEquals(HttpStatus.OK, entered.getStatusCode(), entered.getBody());
        assertTrue(entered.getBody().contains("\"state\":\"preliminary\""), entered.getBody());
    }
}
