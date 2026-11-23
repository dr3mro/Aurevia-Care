package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.lab.result.LabResultService;
import com.aurevia.smartclinic.lab.result.LabTypes.TriggerType;
import com.aurevia.smartclinic.lab.result.TriggerEvent;

/**
 * LAB-02 clause 6 (API evidence): trigger XCH-04/05 on relevant issued revisions.
 *
 * <p>Every request goes through the real laboratory result routes against a fresh
 * PostgreSQL database built from the production Flyway migrations. XCH-04 is
 * proven by the immutable
 * {@link com.aurevia.smartclinic.lab.result.TriggerEvent} facts exposed by the
 * injected report kernel (routing a final or corrected revision to an owner and queue).
 * XCH-05 is proven by the durable critical-result row that the result-entry
 * service registers on first verification of a critical finding. Preliminary,
 * unauthorised, cross-tenant and stale attempts must create neither fact. All
 * subjects, accessions and patients are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_lab_xch_clause_it")
@ActiveProfiles("synthetic-auth")
class TriggerXchRelevantIssuedRevisionsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_lab_xch_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    RestClient rest;

    /** The real report kernel; its trigger facts are read directly, never over a new HTTP route. */
    @Autowired
    LabResultService reports;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and seed
     * synthetic memberships for both tenants.
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_lab_xch_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_lab_xch_clause_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_lab_xch_clause_it WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned server port. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seed(Statement statement, String org, String subject, String role)
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

    private String patient(String mrn) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body,
                "reception-a", ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return field(response.getBody(), "id");
    }

    private String collectedSpecimen(String number, String patient) {
        String accessionBody = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"LAB-A\","
                + "\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"ORD/1\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/lab/accessions",
                accessionBody, "lab-collector-a", ORG_A);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = field(created.getBody(), "id");
        String collectBody = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"specimenType\":\"PLASMA\",\"siteCode\":\"SITE-A\","
                + "\"collector\":\"Sam Collector\",\"collectedAt\":\"2026-11-21T09:00:00Z\","
                + "\"quality\":\"acceptable\",\"expectedVersion\":1}";
        assertEquals(200, request(HttpMethod.POST, "/api/v1/lab/specimens/" + id + "/collect",
                collectBody, "lab-collector-a", ORG_A).getStatusCode().value());
        return id;
    }

    private ResponseEntity<String> enterCritical(String report, String patient, String accession,
                                                 String enterer) {
        // Potassium 6.5 mmol/L crosses the supplied critical bound of 6.4, so the
        // finding is eligible for the XCH-05 alert once a different verifier issues it.
        String observation = "{\"code\":\"K\",\"kind\":\"numeric\",\"presence\":\"present\",\"value\":\"6.5\","
                + "\"qualifier\":\"none\",\"unit\":\"mmol/L\",\"method\":\"photometry\","
                + "\"instrument\":\"analyser-1\",\"referenceLow\":\"3.5\",\"referenceHigh\":\"5.0\","
                + "\"criticalLow\":\"6.0\",\"criticalHigh\":\"6.4\"}";
        String body = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + accession + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"expectedVersion\":2,\"owner\":\"clinician-a\","
                + "\"queue\":\"critical-queue\",\"dueAfter\":\"PT30M\",\"escalation\":\"PHONE\","
                + "\"observations\":[" + observation + "]}";
        return request(HttpMethod.POST, "/api/v1/lab/results/" + report, body, enterer, ORG_A);
    }

    private static String decisionBody(String patient, String accession, int version) {
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + accession + "\","
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

    /**
     * Count durable XCH-05 alerts for one report. The migration owner is also subject
     * to FORCE RLS, so bind the tenant and actor before reading the request's rows.
     */
    private static int criticalCount(String reportId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
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

    /** Trigger facts for one report; the kernel's event list is cumulative across tests. */
    private List<TriggerEvent> eventsFor(String reportId) {
        return reports.events(ORG_A).stream()
                .filter(event -> reportId.equals(event.reportId()))
                .toList();
    }

    private long eventCount(String reportId, TriggerType type) {
        return eventsFor(reportId).stream().filter(event -> event.type() == type).count();
    }

    @Test
    void verifyingACriticalRevisionEmitsIssueAndCriticalTriggers() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-01");
        String report = collectedSpecimen("ACC-XCH-01", patient);

        ResponseEntity<String> entered = enterCritical(report, patient, "ACC-XCH-01", "lab-collector-a");
        assertEquals(200, entered.getStatusCode().value(), entered.getBody());
        assertEquals("preliminary", field(entered.getBody(), "state"));
        assertTrue(eventsFor(report).isEmpty(), "a preliminary revision routes nothing");
        assertEquals(0, criticalCount(report), "no XCH-05 alert before verification");

        ResponseEntity<String> verified = request(HttpMethod.POST,
                "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-01", 1), "lab-verifier-a", ORG_A);
        assertEquals(200, verified.getStatusCode().value(), verified.getBody());
        assertEquals("final", field(verified.getBody(), "state"));

        assertEquals(1, eventCount(report, TriggerType.RESULT_ISSUED));
        TriggerEvent issued = eventsFor(report).stream()
                .filter(event -> event.type() == TriggerType.RESULT_ISSUED).findFirst().orElseThrow();
        assertEquals(ORG_A, issued.organizationId());
        assertEquals("clinician-a", issued.owner());
        assertEquals("critical-queue", issued.queue());
        assertEquals(2, issued.reportVersion(), "the final revision supersedes version 1");
        assertEquals(patient, issued.patientId());
        assertNull(issued.findingCode());
        assertEquals(1, criticalCount(report), "verification starts the durable XCH-05 workflow");
    }

    @Test
    void correctingAnIssuedRevisionEmitsCorrectedTriggerWithoutDuplicateAlert() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-02");
        String report = collectedSpecimen("ACC-XCH-02", patient);
        assertEquals(200, enterCritical(report, patient, "ACC-XCH-02", "lab-collector-a")
                .getStatusCode().value());
        assertEquals(200, request(HttpMethod.POST, "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-02", 1), "lab-verifier-a", ORG_A).getStatusCode().value());

        String issuedId = eventsFor(report).stream()
                .filter(event -> event.type() == TriggerType.RESULT_ISSUED)
                .findFirst().orElseThrow().eventId();

        ResponseEntity<String> corrected = request(HttpMethod.POST,
                "/api/v1/lab/results/" + report + "/correct",
                decisionBody(patient, "ACC-XCH-02", 2), "lab-verifier-a", ORG_A);
        assertEquals(200, corrected.getStatusCode().value(), corrected.getBody());
        assertEquals("corrected", field(corrected.getBody(), "state"));

        assertEquals(1, eventCount(report, TriggerType.RESULT_CORRECTED));
        TriggerEvent correction = eventsFor(report).stream()
                .filter(event -> event.type() == TriggerType.RESULT_CORRECTED).findFirst().orElseThrow();
        assertEquals(3, correction.reportVersion());
        assertEquals("clinician-a", correction.owner());
        assertEquals("critical-queue", correction.queue());
        assertNotEquals(issuedId, correction.eventId(), "a new revision gets a new event identity");
        assertEquals(1, criticalCount(report), "a correction must not duplicate the XCH-05 alert");
    }

    @Test
    void preliminaryRevisionEmitsNoTrigger() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-03");
        String report = collectedSpecimen("ACC-XCH-03", patient);

        ResponseEntity<String> entered = enterCritical(report, patient, "ACC-XCH-03", "lab-collector-a");
        assertEquals(200, entered.getStatusCode().value(), entered.getBody());
        assertEquals("preliminary", field(entered.getBody(), "state"));
        assertTrue(eventsFor(report).isEmpty(), "only issued revisions route results");
        assertEquals(0, criticalCount(report));
    }

    @Test
    void receptionCannotIssue() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-04");
        String report = collectedSpecimen("ACC-XCH-04", patient);
        assertEquals(200, enterCritical(report, patient, "ACC-XCH-04", "lab-collector-a")
                .getStatusCode().value());

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-04", 1), "reception-a", ORG_A);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
        assertTrue(eventsFor(report).isEmpty(), "a role without laboratory permission routes nothing");
        assertEquals(0, criticalCount(report));
    }

    @Test
    void otherTenantCannotTriggerOnThisReport() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-05");
        String report = collectedSpecimen("ACC-XCH-05", patient);
        assertEquals(200, enterCritical(report, patient, "ACC-XCH-05", "lab-collector-a")
                .getStatusCode().value());
        int before = eventsFor(report).size();

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-05", 1), "lab-verifier-b", ORG_B);
        assertEquals(404, denied.getStatusCode().value(), denied.getBody());
        assertTrue(reports.events(ORG_B).stream().noneMatch(event -> report.equals(event.reportId())),
                "the other tenant gets no event for this report");
        assertEquals(before, eventsFor(report).size(), "ORG_A facts are unchanged");
        assertEquals(0, criticalCount(report));
    }

    @Test
    void staleVersionCannotReissue() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-06");
        String report = collectedSpecimen("ACC-XCH-06", patient);
        assertEquals(200, enterCritical(report, patient, "ACC-XCH-06", "lab-collector-a")
                .getStatusCode().value());
        assertEquals(200, request(HttpMethod.POST, "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-06", 1), "lab-verifier-a", ORG_A).getStatusCode().value());
        int before = eventsFor(report).size();

        ResponseEntity<String> stale = request(HttpMethod.POST,
                "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-06", 1), "lab-verifier-a", ORG_A);
        assertEquals(412, stale.getStatusCode().value(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(before, eventsFor(report).size(), "a stale retry creates no new event");
        assertEquals(1, criticalCount(report), "a stale retry creates no new alert");
    }

    @Test
    void entererCannotSelfVerify() throws Exception {
        String patient = patient("MRN-XCH-CLAUSE-07");
        String report = collectedSpecimen("ACC-XCH-07", patient);
        assertEquals(200, enterCritical(report, patient, "ACC-XCH-07", "lab-both-a")
                .getStatusCode().value());

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/lab/results/" + report + "/verify",
                decisionBody(patient, "ACC-XCH-07", 1), "lab-both-a", ORG_A);
        assertEquals(422, denied.getStatusCode().value(), denied.getBody());
        assertEquals("SELF_VERIFICATION", field(denied.getBody(), "code"));
        assertTrue(eventsFor(report).isEmpty(), "self-verification cannot issue the report");
        assertEquals(0, criticalCount(report));
    }
}
