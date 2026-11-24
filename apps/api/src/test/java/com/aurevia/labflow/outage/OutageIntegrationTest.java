package com.aurevia.labflow.outage;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * LAB-02 / XCH-03 / PRD-02 outage fallback end to end: reason, attachment link,
 * unmatched queue with an owner and a visible outage history over HTTP.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_labflow05_it")
@ActiveProfiles("synthetic-auth")
class OutageIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_labflow05_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_labflow05_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_labflow05_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "lab-collector-a", "lab_collector");
            seed(statement, ORG_A, "lab-verifier-a", "lab_verifier");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "lab-collector-b", "lab_collector");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_labflow05_it WITH (FORCE)");
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

    private static String manualBody(String patient, String number, String reason, String owner) {
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number + "\","
                + "\"accessionIssuer\":\"LAB-A\",\"reason\":\"" + reason + "\","
                + "\"attachment\":{\"objectKey\":\"partner/report-1\",\"fileName\":\"original-report.pdf\","
                + "\"checksum\":\"" + CHECKSUM + "\",\"contentType\":\"application/pdf\"},"
                + "\"observations\":[{\"code\":\"GLU\",\"kind\":\"numeric\",\"presence\":\"present\","
                + "\"value\":\"5.5\",\"unit\":\"mmol/L\"}],\"unmatchedOwner\":" + owner + "}";
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
    void manualEntryReasonAttachmentLinkUnmatchedQueueAndHistoryAreReachableOverHttp() {
        String patient = patient(ORG_A, "MRN-LABFLOW-05-A", "reception-a");
        String specimen = collectedSpecimen("ACC-OUT-1", patient);

        ResponseEntity<String> noReason = request(HttpMethod.POST,
                "/api/v1/lab/outage/orders/" + specimen + "/manual-results",
                manualBody(patient, "ACC-OUT-1", " ", "null"), "lab-collector-a", ORG_A);
        assertEquals(422, noReason.getStatusCode().value(), noReason.getBody());
        assertEquals("REASON_REQUIRED", field(noReason.getBody(), "code"));

        ResponseEntity<String> linked = request(HttpMethod.POST,
                "/api/v1/lab/outage/orders/" + specimen + "/manual-results",
                manualBody(patient, "ACC-OUT-1", "partner interface down", "null"),
                "lab-collector-a", ORG_A);
        assertEquals(HttpStatus.OK, linked.getStatusCode(), linked.getBody());
        assertEquals("linked", field(linked.getBody(), "disposition"));
        assertEquals(specimen, field(linked.getBody(), "orderId"));
        assertTrue(linked.getBody().contains("\"objectKey\":\"partner/report-1\""), linked.getBody());

        ResponseEntity<String> attachment = request(HttpMethod.GET,
                "/api/v1/lab/outage/orders/" + specimen + "/attachment", null,
                "lab-collector-a", ORG_A);
        assertEquals(HttpStatus.OK, attachment.getStatusCode(), attachment.getBody());
        assertTrue(attachment.getBody().contains("original-report.pdf"), attachment.getBody());

        ResponseEntity<String> history = request(HttpMethod.GET,
                "/api/v1/lab/outage/orders/" + specimen + "/history", null,
                "lab-verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, history.getStatusCode(), history.getBody());
        assertTrue(history.getBody().contains("\"action\":\"LAB_OUTAGE_MANUAL_ENTRY\""), history.getBody());
        assertTrue(history.getBody().contains("\"disposition\":\"linked\""), history.getBody());

        String unmatchedOrder = "99999999-9999-9999-9999-999999999999";
        ResponseEntity<String> queued = request(HttpMethod.POST,
                "/api/v1/lab/outage/orders/" + unmatchedOrder + "/manual-results",
                manualBody(patient, "ACC-NO-MATCH", "partner interface down",
                        "\"reconciliation-owner\""), "lab-collector-a", ORG_A);
        assertEquals(HttpStatus.OK, queued.getStatusCode(), queued.getBody());
        assertEquals("unmatched", field(queued.getBody(), "disposition"));
        assertTrue(queued.getBody().contains("reconciliation-owner"), queued.getBody());

        ResponseEntity<String> queue = request(HttpMethod.GET, "/api/v1/lab/outage/unmatched",
                null, "lab-verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertTrue(queue.getBody().contains("reconciliation-owner"), queue.getBody());

        ResponseEntity<String> peer = request(HttpMethod.GET, "/api/v1/lab/outage/unmatched",
                null, "lab-collector-b", ORG_B);
        assertEquals(HttpStatus.OK, peer.getStatusCode(), peer.getBody());
        assertEquals("[]", peer.getBody());
    }
}
