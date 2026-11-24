package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

/** S-G2-08 acceptance: issuer-scoped accession, verified collection/receipt, rejection and linked recollection (LAB-01). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_lab01_it")
@ActiveProfiles("synthetic-auth")
class SpecimenAccessionIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_lab01_it";
    static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_lab01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_lab01_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "lab-a", "lab_collector");
            seed(statement, ORG_A, "verifier-a", "lab_verifier");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "lab-b", "lab_collector");
            seed(statement, ORG_B, "verifier-b", "lab_verifier");
            seed(statement, ORG_B, "reception-b", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_lab01_it WITH (FORCE)");
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

    private String patient(String org, String reception, String mrn) {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body, reception, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "id");
    }

    private ResponseEntity<String> accession(String number, String issuer, String patient, String order,
                                             String actor, String org) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"" + issuer
                + "\",\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"" + order + "\"}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions", body, actor, org);
    }

    private ResponseEntity<String> collect(String id, String patient, String number, String issuer, int version,
                                           String actor, String org) {
        String body = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number
                + "\",\"accessionIssuer\":\"" + issuer + "\",\"specimenType\":\"PLASMA\","
                + "\"siteCode\":\"SITE-A\",\"collector\":\"Sam Collector\","
                + "\"collectedAt\":\"2026-11-19T10:15:30Z\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/collect", body, actor, org);
    }

    private ResponseEntity<String> receive(String id, String patient, String number, String issuer, int version,
                                           String actor, String org) {
        String body = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number
                + "\",\"accessionIssuer\":\"" + issuer + "\",\"quality\":\"acceptable\","
                + "\"receivedAt\":\"2026-11-19T11:15:30Z\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/receive", body, actor, org);
    }

    private ResponseEntity<String> reject(String id, String reason, String notification, int version,
                                          String actor, String org) {
        String body = "{\"reason\":\"" + reason + "\",\"notification\":\"" + notification
                + "\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/reject", body, actor, org);
    }

    private ResponseEntity<String> recollect(String id, String number, String issuer, int version,
                                             String actor, String org) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"" + issuer
                + "\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/recollect", body, actor, org);
    }

    private ResponseEntity<String> get(String id, String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/lab/accessions/" + id, null, actor, org);
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
    void accessionIsIssuerAndTenantScopedAndPeerReadIsConcealed() {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-1");
        ResponseEntity<String> first = accession("ACC-1", "LAB-A", patientA, "ORD/1", "lab-a", ORG_A);
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        String id = field(first.getBody(), "id");
        assertEquals("accessioned", field(first.getBody(), "state"));

        assertEquals(HttpStatus.CONFLICT,
                accession("ACC-1", "LAB-A", patientA, "ORD/1", "lab-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CREATED,
                accession("ACC-1", "LAB-B", patientA, "ORD/2", "lab-a", ORG_A).getStatusCode());

        String patientB = patient(ORG_B, "reception-b", "MRN-LAB-1");
        ResponseEntity<String> peer = accession("ACC-1", "LAB-A", patientB, "ORD/1", "lab-b", ORG_B);
        assertEquals(HttpStatus.CREATED, peer.getStatusCode(), peer.getBody());
        assertNotEquals(id, field(peer.getBody(), "id"));

        assertEquals(HttpStatus.NOT_FOUND, get(id, "lab-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.OK, get(id, "lab-a", ORG_A).getStatusCode());
        // ARC-02 fail-closed on writes: a peer tenant cannot mutate a concealed accession.
        assertEquals(HttpStatus.NOT_FOUND,
                collect(id, patientA, "ACC-1", "LAB-A", 1, "lab-b", ORG_B).getStatusCode());
    }

    @Test
    void collectionAndReceiptVerifyPatientAndAccession() {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-2");
        String patientB = patient(ORG_A, "reception-a", "MRN-LAB-3");
        String id = field(accession("ACC-2", "LAB-A", patientA, "ORD/2", "lab-a", ORG_A).getBody(), "id");

        assertEquals(422, collect(id, patientB, "ACC-2", "LAB-A", 1, "lab-a", ORG_A)
                .getStatusCode().value());
        assertEquals("PATIENT_ACCESSION_MISMATCH",
                field(collect(id, patientA, "ACC-9", "LAB-A", 1, "lab-a", ORG_A).getBody(), "code"));
        assertEquals(422, collect(id, patientA, "ACC-2", "LAB-B", 1, "lab-a", ORG_A)
                .getStatusCode().value());
        assertEquals("accessioned", field(get(id, "lab-a", ORG_A).getBody(), "state"));

        ResponseEntity<String> collected = collect(id, patientA, "ACC-2", "LAB-A", 1, "lab-a", ORG_A);
        assertEquals(HttpStatus.OK, collected.getStatusCode(), collected.getBody());
        assertEquals("collected", field(collected.getBody(), "state"));
        assertEquals("SITE-A", field(collected.getBody(), "siteCode"));

        assertEquals("PATIENT_ACCESSION_MISMATCH",
                field(receive(id, patientB, "ACC-2", "LAB-A", 2, "verifier-a", ORG_A).getBody(), "code"));
        assertEquals("PATIENT_ACCESSION_MISMATCH",
                field(receive(id, patientA, "ACC-9", "LAB-A", 2, "verifier-a", ORG_A).getBody(), "code"));
        assertEquals(HttpStatus.FORBIDDEN,
                receive(id, patientA, "ACC-2", "LAB-A", 2, "lab-a", ORG_A).getStatusCode());

        ResponseEntity<String> received = receive(id, patientA, "ACC-2", "LAB-A", 2, "verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, received.getStatusCode(), received.getBody());
        assertEquals("received", field(received.getBody(), "state"));
        assertEquals("acceptable", field(received.getBody(), "quality"));

        String view = get(id, "lab-a", ORG_A).getBody();
        assertTrue(view.contains("\"events\""));
        assertTrue(view.contains("collected"));
        assertTrue(view.contains("received"));
    }

    @Test
    void rejectionKeepsEvidenceAndRecollectionLinksANewSpecimen() {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-4");
        String id = field(accession("ACC-3", "LAB-A", patientA, "ORD/3", "lab-a", ORG_A).getBody(), "id");
        collect(id, patientA, "ACC-3", "LAB-A", 1, "lab-a", ORG_A);
        receive(id, patientA, "ACC-3", "LAB-A", 2, "verifier-a", ORG_A);

        ResponseEntity<String> rejected = reject(id, "hemolysed sample", "lab-a@example.test",
                3, "verifier-a", ORG_A);
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", field(rejected.getBody(), "state"));
        assertEquals("hemolysed sample", field(rejected.getBody(), "rejectionReason"));
        assertEquals("lab-a@example.test", field(rejected.getBody(), "rejectionNotification"));

        assertEquals(HttpStatus.FORBIDDEN,
                recollect(id, "ACC-4", "LAB-A", 4, "lab-a", ORG_A).getStatusCode());
        ResponseEntity<String> recollected = recollect(id, "ACC-4", "LAB-A", 4, "verifier-a", ORG_A);
        assertEquals(HttpStatus.CREATED, recollected.getStatusCode(), recollected.getBody());
        String newId = field(recollected.getBody(), "id");
        assertNotEquals(id, newId);
        assertEquals(id, field(recollected.getBody(), "replacesAccessionId"));
        assertEquals("recollected", field(get(id, "lab-a", ORG_A).getBody(), "state"));
        assertEquals("ORD/3", field(get(newId, "lab-a", ORG_A).getBody(), "sourceOrderRef"));

        assertEquals(HttpStatus.CONFLICT,
                recollect(id, "ACC-5", "LAB-A", 5, "verifier-a", ORG_A).getStatusCode());
    }

    @Test
    void authorizationAndValidationFailClosed() {
        String patientA = patient(ORG_A, "reception-a", "MRN-LAB-5");
        assertEquals(HttpStatus.FORBIDDEN,
                accession("ACC-6", "LAB-A", patientA, "ORD/6", "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                accession("ACC-6", "LAB-A", patientA, "ORD/6", null, ORG_A).getStatusCode());
        assertEquals(422, accession("!", "LAB-A", patientA, "ORD/6", "lab-a", ORG_A)
                .getStatusCode().value());

        ResponseEntity<String> audit = request(HttpMethod.GET, "/api/v1/audit-events",
                null, "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        assertTrue(audit.getBody().contains("LAB_ACCESSION_CREATE"));
        assertFalse(audit.getBody().contains("hemolysed"));
    }
}
