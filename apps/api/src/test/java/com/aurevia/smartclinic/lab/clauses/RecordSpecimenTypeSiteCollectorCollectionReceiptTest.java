package com.aurevia.smartclinic.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LAB-01 clause 2 acceptance against a real PostgreSQL: collection records the
 * specimen type, collection site, collector and collection instant, and receipt
 * records the quality and receipt instant, through the HTTP entry point. The
 * negative tests exercise every guard that refuses an invalid or unauthorized
 * capture: role, tenant, version, input, lifecycle state and patient/accession
 * identity. All organizations, patients, accessions and people are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_specimen_capture_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class RecordSpecimenTypeSiteCollectorCollectionReceiptTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT_B = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String COLLECTOR = "lab-a";
    private static final String VERIFIER = "verifier-a";
    private static final String RECEPTION = "reception-a";
    private static final String PEER = "lab-b";
    private static final String ISSUER = "LAB-A";
    private static final String ORDER = "ORD-SPEC";
    private static final String SPECIMEN_TYPE = "PLASMA";
    private static final String SITE = "SITE-A";
    private static final String COLLECTOR_NAME = "Sam Collector";
    private static final String COLLECTED_AT = "2026-11-19T10:15:30Z";
    private static final String QUALITY = "acceptable";
    private static final String RECEIVED_AT = "2026-11-19T11:15:30Z";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_specimen_capture_it";
    private static final String DATABASE = "smartclinic_specimen_capture_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private RestClient rest;
    private String accessionId;
    private String accessionNo;

    /**
     * Prepare the isolated database and seed the synthetic identities once for the class.
     *
     * @throws Exception when the database or a fixture cannot be created
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, ORG_A, COLLECTOR, "lab_collector");
            seedMember(statement, ORG_A, VERIFIER, "lab_verifier");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_B, PEER, "lab_collector");
            seedPatient(statement, ORG_A, PATIENT_A, "MRN-SPEC-A", RECEPTION);
            seedPatient(statement, ORG_A, PATIENT_B, "MRN-SPEC-B", RECEPTION);
        }
    }

    /**
     * Drop the ephemeral synthetic database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client and create a fresh accessioned specimen for this test.
     *
     * @param port the random local server port
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        accessionNo = "SPEC-" + SEQUENCE.incrementAndGet();
        ResponseEntity<String> created = accession(COLLECTOR, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        accessionId = json(created).get("id").asText();
    }

    // Each test starts from its own accessioned specimen; every organization, patient and
    // person below is a synthetic fixture with no clinical or personal data.

    /**
     * Collection records every captured field and the read-back preserves it.
     */
    @Test
    void collectionRecordsSpecimenTypeSiteCollectorAndCollectionTime() {
        ResponseEntity<String> response = collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1));
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        JsonNode collected = json(response);
        assertEquals("collected", collected.get("state").asText());
        assertEquals(2, collected.get("version").asInt());
        assertField(collected, "specimenType", SPECIMEN_TYPE);
        assertField(collected, "siteCode", SITE);
        assertField(collected, "collector", COLLECTOR_NAME);
        assertInstantField(collected, "collectedAt", COLLECTED_AT);

        JsonNode readBack = json(get(accessionId, COLLECTOR, ORG_A)).get("accession");
        assertEquals("collected", readBack.get("state").asText());
        assertEquals(2, readBack.get("version").asInt());
        assertField(readBack, "specimenType", SPECIMEN_TYPE);
        assertField(readBack, "siteCode", SITE);
        assertField(readBack, "collector", COLLECTOR_NAME);
        assertInstantField(readBack, "collectedAt", COLLECTED_AT);
        assertEquals(collected.get("collectedAt").asText(), readBack.get("collectedAt").asText(),
                "the collected instant must round-trip unchanged");
    }

    /**
     * Receipt records the quality and receipt instant while preserving collection.
     */
    @Test
    void receiptRecordsQualityAndReceiptTime() {
        assertEquals(HttpStatus.OK, collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)).getStatusCode());

        JsonNode received = json(receive(VERIFIER, ORG_A, receiveBody(PATIENT_A, accessionNo,
                ISSUER, QUALITY, RECEIVED_AT, 2)));
        assertEquals("received", received.get("state").asText());
        assertEquals(3, received.get("version").asInt());
        assertField(received, "quality", QUALITY);
        assertInstantField(received, "receivedAt", RECEIVED_AT);
        assertField(received, "specimenType", SPECIMEN_TYPE);
        assertInstantField(received, "collectedAt", COLLECTED_AT);

        JsonNode readBack = json(get(accessionId, VERIFIER, ORG_A)).get("accession");
        assertEquals("received", readBack.get("state").asText());
        assertField(readBack, "quality", QUALITY);
        assertInstantField(readBack, "receivedAt", RECEIVED_AT);
        assertInstantField(readBack, "collectedAt", COLLECTED_AT);
        assertEquals(received.get("receivedAt").asText(), readBack.get("receivedAt").asText(),
                "the received instant must round-trip unchanged");
    }

    /**
     * A verifier cannot collect a specimen; the separation of duties is enforced.
     */
    @Test
    void verifierCannotCollectSpecimen() {
        ResponseEntity<String> denied = collect(VERIFIER, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1));
        assertRejected(denied, HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertEquals("accessioned", json(get(accessionId, COLLECTOR, ORG_A))
                .get("accession").get("state").asText());
    }

    /**
     * A collector cannot receive a specimen; the separation of duties is enforced.
     */
    @Test
    void collectorCannotReceiveSpecimen() {
        assertEquals(HttpStatus.OK, collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)).getStatusCode());
        ResponseEntity<String> denied = receive(COLLECTOR, ORG_A, receiveBody(PATIENT_A, accessionNo,
                ISSUER, QUALITY, RECEIVED_AT, 2));
        assertRejected(denied, HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertEquals("collected", json(get(accessionId, COLLECTOR, ORG_A))
                .get("accession").get("state").asText());
    }

    /**
     * A peer tenant cannot capture a concealed specimen, even with a valid collector role.
     */
    @Test
    void otherTenantCannotCaptureSpecimen() {
        ResponseEntity<String> denied = collect(PEER, ORG_B, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1));
        assertRejected(denied, HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertEquals("accessioned", json(get(accessionId, COLLECTOR, ORG_A))
                .get("accession").get("state").asText());
    }

    /**
     * A stale expected version is refused on both collection and receipt.
     */
    @Test
    void staleVersionIsRejected() {
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, ISSUER,
                SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 9)),
                HttpStatus.PRECONDITION_FAILED, "STALE_VERSION");

        assertEquals(HttpStatus.OK, collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)).getStatusCode());
        assertRejected(receive(VERIFIER, ORG_A, receiveBody(PATIENT_A, accessionNo, ISSUER,
                QUALITY, RECEIVED_AT, 1)), HttpStatus.PRECONDITION_FAILED, "STALE_VERSION");
    }

    /**
     * Malformed or missing specimen fields are rejected with their field-specific code.
     */
    @Test
    void malformedOrMissingSpecimenFieldsAreRejected() {
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, ISSUER,
                "!", SITE, COLLECTOR_NAME, COLLECTED_AT, 1)), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_SPECIMEN_TYPE");
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, ISSUER,
                SPECIMEN_TYPE, "!", COLLECTOR_NAME, COLLECTED_AT, 1)), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_SITE");
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, ISSUER,
                SPECIMEN_TYPE, SITE, "!", COLLECTED_AT, 1)), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_COLLECTOR");
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, ISSUER,
                SPECIMEN_TYPE, SITE, COLLECTOR_NAME, "not-an-instant", 1)),
                HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TIMESTAMP");

        String missingSpecimenType = "{\"patientId\":\"" + PATIENT_A + "\",\"accessionNo\":\"" + accessionNo
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"siteCode\":\"" + SITE
                + "\",\"collector\":\"" + COLLECTOR_NAME + "\",\"collectedAt\":\"" + COLLECTED_AT
                + "\",\"expectedVersion\":1}";
        assertRejected(collect(COLLECTOR, ORG_A, missingSpecimenType), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_SPECIMEN_TYPE");
        String missingSite = "{\"patientId\":\"" + PATIENT_A + "\",\"accessionNo\":\"" + accessionNo
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"specimenType\":\"" + SPECIMEN_TYPE
                + "\",\"collector\":\"" + COLLECTOR_NAME + "\",\"collectedAt\":\"" + COLLECTED_AT
                + "\",\"expectedVersion\":1}";
        assertRejected(collect(COLLECTOR, ORG_A, missingSite), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_SITE");

        String missingCollector = "{\"patientId\":\"" + PATIENT_A + "\",\"accessionNo\":\"" + accessionNo
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"specimenType\":\"" + SPECIMEN_TYPE
                + "\",\"siteCode\":\"" + SITE + "\",\"collectedAt\":\"" + COLLECTED_AT
                + "\",\"expectedVersion\":1}";
        assertRejected(collect(COLLECTOR, ORG_A, missingCollector), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_COLLECTOR");
        String missingCollectedAt = "{\"patientId\":\"" + PATIENT_A + "\",\"accessionNo\":\"" + accessionNo
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"specimenType\":\"" + SPECIMEN_TYPE
                + "\",\"siteCode\":\"" + SITE + "\",\"collector\":\"" + COLLECTOR_NAME
                + "\",\"expectedVersion\":1}";
        assertRejected(collect(COLLECTOR, ORG_A, missingCollectedAt), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_TIMESTAMP");

        assertEquals(HttpStatus.OK, collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)).getStatusCode());
        assertRejected(receive(VERIFIER, ORG_A, receiveBody(PATIENT_A, accessionNo, ISSUER,
                "!", RECEIVED_AT, 2)), HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_QUALITY");
        String missingQuality = "{\"patientId\":\"" + PATIENT_A + "\",\"accessionNo\":\"" + accessionNo
                + "\",\"accessionIssuer\":\"" + ISSUER + "\",\"receivedAt\":\"" + RECEIVED_AT
                + "\",\"expectedVersion\":2}";
        assertRejected(receive(VERIFIER, ORG_A, missingQuality), HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_QUALITY");
    }

    /**
     * A specimen cannot be collected twice; the lifecycle edge is closed.
     */
    @Test
    void secondCollectionIsForbidden() {
        assertEquals(HttpStatus.OK, collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)).getStatusCode());
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, ISSUER,
                SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 2)), HttpStatus.CONFLICT,
                "INVALID_TRANSITION");
        assertEquals("collected", json(get(accessionId, COLLECTOR, ORG_A))
                .get("accession").get("state").asText());
    }

    /**
     * A mismatched patient or accession is refused at collection and at receipt.
     */
    @Test
    void mismatchedPatientOrAccessionIsRejected() {
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_B, accessionNo, ISSUER,
                SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)), HttpStatus.UNPROCESSABLE_ENTITY,
                "PATIENT_ACCESSION_MISMATCH");
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, "SPEC-OTHER", ISSUER,
                SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)), HttpStatus.UNPROCESSABLE_ENTITY,
                "PATIENT_ACCESSION_MISMATCH");
        assertRejected(collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo, "LAB-B",
                SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)), HttpStatus.UNPROCESSABLE_ENTITY,
                "PATIENT_ACCESSION_MISMATCH");

        assertEquals(HttpStatus.OK, collect(COLLECTOR, ORG_A, collectBody(PATIENT_A, accessionNo,
                ISSUER, SPECIMEN_TYPE, SITE, COLLECTOR_NAME, COLLECTED_AT, 1)).getStatusCode());
        assertRejected(receive(VERIFIER, ORG_A, receiveBody(PATIENT_B, accessionNo, ISSUER,
                QUALITY, RECEIVED_AT, 2)), HttpStatus.UNPROCESSABLE_ENTITY,
                "PATIENT_ACCESSION_MISMATCH");
    }

    private static void bind(Statement statement, String org, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        bind(statement, org, subject);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic org " + org + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement, String org, String id, String mrn,
                                    String createdBy) throws Exception {
        bind(statement, org, createdBy);
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + "dob, created_by) "
                + "SELECT '" + org + "', '" + id + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient " + mrn + "', 'synthetic patient " + mrn + "', '1990-05-20', u.id "
                + "FROM public.user_identity u WHERE u.organization_id = '" + org + "' "
                + "AND u.subject = '" + createdBy + "'");
    }

    private ResponseEntity<String> accession(String subject, String org) {
        String body = "{\"accessionNo\":\"" + accessionNo + "\",\"accessionIssuer\":\"" + ISSUER
                + "\",\"patientId\":\"" + PATIENT_A + "\",\"sourceOrderRef\":\"" + ORDER + "\"}";
        return request(HttpMethod.POST, "/api/v1/lab/accessions", body, subject, org);
    }

    private ResponseEntity<String> collect(String subject, String org, String body) {
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + accessionId + "/collect",
                body, subject, org);
    }

    private ResponseEntity<String> receive(String subject, String org, String body) {
        return request(HttpMethod.POST, "/api/v1/lab/accessions/" + accessionId + "/receive",
                body, subject, org);
    }

    private ResponseEntity<String> get(String id, String subject, String org) {
        return request(HttpMethod.GET, "/api/v1/lab/accessions/" + id, null, subject, org);
    }

    private static String collectBody(String patient, String number, String issuer, String type,
                                      String site, String collector, String collectedAt, int version) {
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number
                + "\",\"accessionIssuer\":\"" + issuer + "\",\"specimenType\":\"" + type
                + "\",\"siteCode\":\"" + site + "\",\"collector\":\"" + collector
                + "\",\"collectedAt\":\"" + collectedAt + "\",\"expectedVersion\":" + version + "}";
    }

    private static String receiveBody(String patient, String number, String issuer, String quality,
                                      String receivedAt, int version) {
        return "{\"patientId\":\"" + patient + "\",\"accessionNo\":\"" + number
                + "\",\"accessionIssuer\":\"" + issuer + "\",\"quality\":\"" + quality
                + "\",\"receivedAt\":\"" + receivedAt + "\",\"expectedVersion\":" + version + "}";
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

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response: " + response.getBody(), exception);
        }
    }

    private static void assertField(JsonNode node, String key, String expected) {
        assertEquals(expected, node.get(key).asText(), key + " did not round-trip");
    }

    /**
     * Compare a stored instant to the submitted ISO-8601 value. PostgreSQL renders a
     * {@code timestamptz} in its own text form (for example {@code 2026-11-19 10:15:30+00}),
     * so the wire value is parsed back into an instant instead of compared as a literal.
     */
    private static void assertInstantField(JsonNode node, String key, String expectedIso) {
        String wire = node.get(key).asText();
        String normalized = wire.replace(' ', 'T');
        if (normalized.endsWith("+00")) {
            normalized = normalized.substring(0, normalized.length() - 3) + "Z";
        }
        Instant submitted = Instant.parse(expectedIso);
        Instant stored = Instant.parse(normalized);
        assertEquals(submitted, stored, key + " did not record the submitted instant");
    }

    private static void assertRejected(ResponseEntity<String> response, HttpStatus status, String code) {
        assertEquals(status.value(), response.getStatusCode().value(), response.getBody());
        assertEquals(code, json(response).get("code").asText(), response.getBody());
    }
}
