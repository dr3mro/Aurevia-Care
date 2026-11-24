package com.aurevia.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LAB-01 clause 5 acceptance on real PostgreSQL: recollecting a rejected specimen
 * mints a new identifier that records the rejected specimen in
 * {@code replacesAccessionId}, moves the original to {@code recollected} and keeps
 * the source order and patient identity on the new row. The negative tests show
 * that a collector, a stale version, a non-rejected specimen, a duplicate
 * accession number, a peer tenant and a malformed number are all refused through
 * the real HTTP entry point. Fixtures are synthetic and the peer tenant shares
 * the same local issuer and accession-number space.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_recollection_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RecollectionCreatesLinkedSpecimenIdTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_recollection_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String COLLECTOR = "lab-a";
    private static final String VERIFIER = "verifier-a";
    private static final String RECEPTION = "reception-a";
    private static final String PEER_VERIFIER = "verifier-b";
    private static final String ISSUER = "LAB-A";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, COLLECTOR, "lab_collector");
            seed(statement, ORG_A, VERIFIER, "lab_verifier");
            seed(statement, ORG_A, RECEPTION, "reception");
            seed(statement, ORG_B, PEER_VERIFIER, "lab_verifier");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
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
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private String patient(String org, String reception, String mrn) throws Exception {
        String body = "{\"displayName\":\"Synthetic Patient\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/patients", body, reception, org);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return json(response).get("id").asText();
    }

    private ResponseEntity<String> accession(String number, String patient, String order,
                                             String actor, String org) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"" + ISSUER
                + "\",\"patientId\":\"" + patient + "\",\"sourceOrderRef\":\"" + order + "\"}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions", body, actor, org);
    }

    private ResponseEntity<String> collect(String id, String patient, String number, int version,
                                           String actor, String org) {
        String body = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"specimenType\":\"PLASMA\","
                + "\"siteCode\":\"SITE-A\",\"collector\":\"Sam Collector\","
                + "\"collectedAt\":\"2026-11-19T10:15:30Z\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/collect", body, actor, org);
    }

    private ResponseEntity<String> receive(String id, String patient, String number, int version,
                                           String actor, String org) {
        String body = "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"quality\":\"acceptable\","
                + "\"receivedAt\":\"2026-11-19T11:15:30Z\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/receive", body, actor, org);
    }

    private ResponseEntity<String> reject(String id, int version, String actor, String org) {
        String body = "{\"reason\":\"hemolysed sample\",\"notification\":\"lab-a@example.test\","
                + "\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/reject", body, actor, org);
    }

    private ResponseEntity<String> recollect(String id, String number, int version,
                                             String actor, String org) {
        String body = "{\"accessionNo\":\"" + number + "\",\"accessionIssuer\":\"" + ISSUER
                + "\",\"expectedVersion\":" + version + "}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + id + "/recollect", body, actor, org);
    }

    private ResponseEntity<String> get(String id, String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/lab/accessions/" + id, null, actor, org);
    }

    /** Drive one specimen through accession, collection, receipt and rejection to version 4. */
    private String rejectedSpecimen(String mrn, String number, String order) throws Exception {
        String patient = patient(ORG_A, RECEPTION, mrn);
        ResponseEntity<String> created = accession(number, patient, order, COLLECTOR, ORG_A);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = json(created).get("id").asText();
        assertEquals(200, collect(id, patient, number, 1, COLLECTOR, ORG_A).getStatusCode().value());
        assertEquals(200, receive(id, patient, number, 2, VERIFIER, ORG_A).getStatusCode().value());
        ResponseEntity<String> rejected = reject(id, 3, VERIFIER, ORG_A);
        assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("rejected", json(rejected).get("state").asText());
        return id;
    }

    @Test
    void recollectLinksNewSpecimenIdToRejectedOriginal() throws Exception {
        String patient = patient(ORG_A, RECEPTION, "MRN-REC-1");
        String order = "ORD/REC-1";
        ResponseEntity<String> created = accession("REC-1", patient, order, COLLECTOR, ORG_A);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String oldId = json(created).get("id").asText();
        assertEquals(200, collect(oldId, patient, "REC-1", 1, COLLECTOR, ORG_A).getStatusCode().value());
        assertEquals(200, receive(oldId, patient, "REC-1", 2, VERIFIER, ORG_A).getStatusCode().value());
        ResponseEntity<String> rejected = reject(oldId, 3, VERIFIER, ORG_A);
        assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("rejected", json(rejected).get("state").asText());

        ResponseEntity<String> recollected = recollect(oldId, "REC-2", 4, VERIFIER, ORG_A);

        assertEquals(201, recollected.getStatusCode().value(), recollected.getBody());
        JsonNode body = json(recollected);
        String newId = body.get("id").asText();
        assertNotEquals(oldId, newId);
        assertEquals(oldId, body.get("replacesAccessionId").asText());
        assertEquals("accessioned", body.get("state").asText());
        assertEquals(order, body.get("sourceOrderRef").asText());
        assertEquals(patient, body.get("patientId").asText());

        JsonNode oldView = json(get(oldId, VERIFIER, ORG_A));
        assertEquals("recollected", oldView.get("accession").get("state").asText());
        JsonNode oldHistory = oldView.get("events");
        assertEquals(5, oldHistory.size());
        assertEquals("recollected", oldHistory.get(4).get("action").asText());
        assertEquals("rejected", oldHistory.get(4).get("fromState").asText());
        assertEquals("recollected", oldHistory.get(4).get("toState").asText());

        JsonNode newView = json(get(newId, VERIFIER, ORG_A));
        JsonNode newHistory = newView.get("events");
        assertEquals(1, newHistory.size());
        assertEquals("accessioned", newHistory.get(0).get("action").asText());
    }

    @Test
    void collectorCannotRecollect() throws Exception {
        String id = rejectedSpecimen("MRN-REC-2", "REC-3", "ORD/REC-2");

        ResponseEntity<String> refused = recollect(id, "REC-4", 4, COLLECTOR, ORG_A);

        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        JsonNode original = json(get(id, VERIFIER, ORG_A));
        assertEquals("rejected", original.get("accession").get("state").asText());
        assertEquals(4, original.get("accession").get("version").asInt());
    }

    @Test
    void staleVersionRecollectionRefused() throws Exception {
        String id = rejectedSpecimen("MRN-REC-3", "REC-5", "ORD/REC-3");

        ResponseEntity<String> refused = recollect(id, "REC-6", 3, VERIFIER, ORG_A);

        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        JsonNode original = json(get(id, VERIFIER, ORG_A));
        assertEquals("rejected", original.get("accession").get("state").asText());
        assertEquals(4, original.get("accession").get("version").asInt());
        assertEquals(4, original.get("events").size());
    }

    @Test
    void onlyRejectedSpecimenCanBeRecollected() throws Exception {
        String patient = patient(ORG_A, RECEPTION, "MRN-REC-4");
        ResponseEntity<String> created = accession("REC-7", patient, "ORD/REC-4", COLLECTOR, ORG_A);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = json(created).get("id").asText();

        ResponseEntity<String> refused = recollect(id, "REC-8", 1, VERIFIER, ORG_A);

        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        assertEquals("accessioned", json(get(id, VERIFIER, ORG_A))
                .get("accession").get("state").asText());
    }

    @Test
    void duplicateAccessionNumberRecollectionRefused() throws Exception {
        String id = rejectedSpecimen("MRN-REC-5", "REC-9", "ORD/REC-5");
        String otherPatient = patient(ORG_A, RECEPTION, "MRN-REC-5B");
        ResponseEntity<String> other = accession("REC-10", otherPatient, "ORD/REC-5B", COLLECTOR, ORG_A);
        assertEquals(201, other.getStatusCode().value(), other.getBody());

        ResponseEntity<String> refused = recollect(id, "REC-10", 4, VERIFIER, ORG_A);

        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("CONFLICT", json(refused).get("code").asText());
        JsonNode original = json(get(id, VERIFIER, ORG_A));
        assertEquals("rejected", original.get("accession").get("state").asText());
        assertEquals(4, original.get("accession").get("version").asInt());
    }

    @Test
    void peerTenantCannotRecollect() throws Exception {
        String id = rejectedSpecimen("MRN-REC-6", "REC-11", "ORD/REC-6");

        ResponseEntity<String> refused = recollect(id, "REC-12", 4, PEER_VERIFIER, ORG_B);

        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        JsonNode original = json(get(id, VERIFIER, ORG_A));
        assertEquals("rejected", original.get("accession").get("state").asText());
        assertEquals(4, original.get("accession").get("version").asInt());
        assertEquals(4, original.get("events").size());
    }

    @Test
    void malformedAccessionNumberRefused() throws Exception {
        String id = rejectedSpecimen("MRN-REC-7", "REC-13", "ORD/REC-7");

        ResponseEntity<String> refused = recollect(id, "!", 4, VERIFIER, ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_ACCESSION", json(refused).get("code").asText());
        JsonNode original = json(get(id, VERIFIER, ORG_A));
        assertEquals("rejected", original.get("accession").get("state").asText());
        assertEquals(4, original.get("accession").get("version").asInt());
    }
}
