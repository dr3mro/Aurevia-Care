package com.aurevia.labflow.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** LAB-02 / PAT-02 end-to-end release, correction, withdrawal and policy history over HTTP. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_labflow04_it")
@ActiveProfiles("synthetic-auth")
class LabReportReleaseIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_labflow04_it";
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
            statement.execute("DROP DATABASE IF EXISTS orevia_labflow04_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_labflow04_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "lab-collector-a", "lab_collector");
            seed(statement, ORG_A, "lab-verifier-a", "lab_verifier");
            seed(statement, ORG_A, "clinician-a", "clinician");
            seed(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_labflow04_it WITH (FORCE)");
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
        return request(method, uri, body, subject, org, null);
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private String patient(String mrn) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body,
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String finalReport(String number, String patient) {
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
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/lab/specimens/" + id + "/collect",
                collectBody, "lab-collector-a", ORG_A).getStatusCode());
        String entry = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":2,\"owner\":\"clinician-a\","
                + "\"queue\":\"result-queue\",\"dueAfter\":\"PT30M\",\"escalation\":\"PHONE\","
                + "\"observations\":[{\"code\":\"GLU\",\"kind\":\"numeric\",\"presence\":\"present\","
                + "\"value\":\"5.5\",\"unit\":\"mmol/L\",\"referenceLow\":\"4.0\",\"referenceHigh\":\"6.0\"}]}";
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/lab/results/" + id,
                entry, "lab-collector-a", ORG_A).getStatusCode());
        String decision = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":1,\"owner\":\"clinician-a\","
                + "\"queue\":\"result-queue\",\"reason\":\"verified\"}";
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/lab/results/" + id + "/verify",
                decision, "lab-verifier-a", ORG_A).getStatusCode());
        return id;
    }

    private static String releaseBody(String specimen, String patient) {
        return "{\"reportId\":\"" + specimen + "\",\"revisionId\":\"" + specimen + ":v2\","
                + "\"revisionVersion\":2,\"patientId\":\"" + patient + "\","
                + "\"policy\":{\"policyVersion\":1,\"patientKind\":\"TIMED\",\"patientDelay\":\"PT1H\"}}";
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

    private static int count(String sql, String param) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             PreparedStatement query = connection.prepareStatement(sql)) {
            connection.createStatement().execute("SET app.current_org = '" + ORG_A + "'");
            query.setString(1, param);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Test
    void releaseCorrectionWithdrawalAndHistoryAreDurableOverHttp() throws Exception {
        String patient = patient("MRN-LABFLOW-04-A");
        String specimen = finalReport("ACC-04", patient);

        ResponseEntity<String> released = request(HttpMethod.POST, "/api/v1/lab/releases",
                releaseBody(specimen, patient), "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, released.getStatusCode(), released.getBody());
        assertEquals("false", field(released.getBody(), "patientVisibleNow"));
        String releaseId = field(released.getBody(), "releaseId");

        // The clinician sees the issued report at once; the timed policy keeps patient access closed.
        ResponseEntity<String> visible = request(HttpMethod.GET,
                "/api/v1/patients/" + patient + "/released-revisions", null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, visible.getStatusCode(), visible.getBody());
        assertFalse(visible.getBody().contains(specimen + ":v2"), visible.getBody());
        assertEquals(1, count("SELECT count(*) FROM public.patient_release WHERE revision_id = ?",
                specimen + ":v2"));
        assertTrue(count("SELECT count(*) FROM public.release_event WHERE release_id = CAST(? AS uuid)",
                releaseId) >= 1);

        // A correction appends a new immutable revision, supersedes the release and notifies viewers.
        String correctReport = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"ACC-04\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":2,\"owner\":\"clinician-a\","
                + "\"queue\":\"result-queue\",\"reason\":\"GLU value corrected\"}";
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/lab/results/" + specimen + "/correct",
                correctReport, "lab-verifier-a", ORG_A).getStatusCode());
        ResponseEntity<String> corrected = request(HttpMethod.POST,
                "/api/v1/lab/releases/" + releaseId + "/correct",
                "{\"correctedRevisionId\":\"" + specimen + ":v3\",\"correctedRevisionVersion\":3,"
                        + "\"reason\":\"GLU value corrected\"}",
                "clinician-a", ORG_A, "1");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertTrue(corrected.getBody().contains("GLU value corrected"), corrected.getBody());
        String correctedReleaseId = field(corrected.getBody(), "releaseId");
        assertEquals(1, count("SELECT count(*) FROM public.patient_release WHERE revision_id = ?",
                specimen + ":v3"));
        assertEquals("CORRECTED", field(request(HttpMethod.GET, "/api/v1/releases/" + releaseId,
                null, "clinician-a", ORG_A).getBody(), "state"));

        // Entered-in-error withdraws the report and the release while preserving the history.
        String errorReport = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"ACC-04\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":3,\"reason\":\"wrong patient\"}";
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/lab/results/" + specimen + "/error",
                errorReport, "lab-verifier-a", ORG_A).getStatusCode());
        ResponseEntity<String> withdrawn = request(HttpMethod.POST,
                "/api/v1/lab/releases/" + correctedReleaseId + "/withdraw",
                "{\"reason\":\"wrong patient\"}", "clinician-a", ORG_A, "1");
        assertEquals(HttpStatus.OK, withdrawn.getStatusCode(), withdrawn.getBody());
        assertTrue(withdrawn.getBody().contains("Report withdrawn"), withdrawn.getBody());
        assertEquals("WITHDRAWN", field(request(HttpMethod.GET,
                "/api/v1/releases/" + correctedReleaseId, null, "clinician-a", ORG_A).getBody(), "state"));

        ResponseEntity<String> history = request(HttpMethod.GET,
                "/api/v1/lab/releases/" + correctedReleaseId + "/history", null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, history.getStatusCode(), history.getBody());
        assertTrue(history.getBody().contains("RELEASE_AUTHORIZE"), history.getBody());
        assertTrue(history.getBody().contains("CORRECTION_NOTICE"), history.getBody());
        assertTrue(history.getBody().contains("WITHDRAWAL_NOTICE"), history.getBody());
    }

    @Test
    void anotherTenantCannotAuthorizeARelease() {
        String patient = patient("MRN-LABFLOW-04-B");
        String specimen = finalReport("ACC-04B", patient);
        ResponseEntity<String> refused = request(HttpMethod.POST, "/api/v1/lab/releases",
                releaseBody(specimen, patient), "clinician-b", ORG_B);
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
    }
}
