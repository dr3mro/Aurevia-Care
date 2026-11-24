package com.aurevia.exchange.clauses;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-01 clause 1 (API evidence): a released order carries the local patient and
 * encounter, order code, priority, clinical question, destination, source actor
 * and responsible clinician/work queue.
 *
 * <p>Every request goes through the real {@code /api/v1/orders} and
 * {@code /api/v1/exchange/work-items} entry points against a fresh PostgreSQL
 * database created from the production Flyway migrations. The ordering tenant A
 * releases to the approved tenant B. The source actor is derived from the
 * authenticated subject, never from the request body, and the destination sees
 * only the bounded work-item projection rather than the sender's chart.</p>
 *
 * <p>The negative tests prove the rule refuses a wrong role, a concealed
 * cross-tenant patient, an unapproved priority or blank question, and malformed
 * identifiers. Each uses a control release with the same grant and headers so a
 * denial cannot be caused by broken setup. Fixtures are synthetic: no real
 * identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_xch01_clause1_it")
@ActiveProfiles("synthetic-auth")
class OrderIncludesLocalPatientEncounterOrderCodeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String ENCOUNTER = "ENC-XCH01-CLAUSE1";
    private static final String QUESTION = "Synthetic CBC question for clause 1";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/aurevia_xch01_clause1_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships, patients and one approved exchange authorization.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch01_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_xch01_clause1_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, "bootstrap-a");
            seedOrganization(statement, ORG_B, "bootstrap-b");
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
            seedPatient(statement, ORG_A, PATIENT_A, "MRN-CLAUSE-A", "admin-a");
            seedPatient(statement, ORG_B, PATIENT_B, "MRN-CLAUSE-B", "admin-b");
            seedGrant(statement);
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch01_clause1_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedOrganization(Statement statement, String org, String subject)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement, String org, String id, String mrn,
                                    String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        // V7 requires a civil date or an explicit unknown reason; use a synthetic
        // date so the append-only patient invariant holds for the fixture.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + id + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Clause Patient', 'synthetic clause patient', DATE '1990-05-20')");
    }

    private static void seedGrant(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'admin-a'");
        // The sender's approved disclosure covers 'CBC' to ORG_B for 'lab review'
        // up to 60 minutes; the 30-minute payload used below stays inside it.
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + ORG_A + "', '" + GRANT + "', "
                + "'CBC', '" + ORG_B + "', 'lab review', 60, "
                + "now() - interval '1 day', now() + interval '1 day', true)");
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static long countOrders(String encounterRef) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "clinician-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT count(*) FROM public.exchange_order "
                                 + "WHERE encounter_ref = '" + encounterRef + "'")) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static long countPatients(String org, String actor, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, actor);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT count(*) FROM public.patient_record WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
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
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String orderBody(String patient, String encounterRef, String priority,
                                    String question, String destination) {
        return "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"" + encounterRef + "\","
                + "\"orderCode\":\"CBC\",\"priority\":\"" + priority + "\","
                + "\"clinicalQuestion\":\"" + question + "\",\"destinationOrg\":\""
                + destination + "\",\"purpose\":\"lab review\",\"authorizationId\":\""
                + GRANT + "\",\"responsibleClinician\":\"dr-a\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":30}";
    }

    private static String clauseOrder(String patient, String encounterRef) {
        return orderBody(patient, encounterRef, "routine", QUESTION, ORG_B);
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer, exception);
        }
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response", exception);
        }
    }

    private JsonNode workItem(String orderId, String subject, String org) {
        ResponseEntity<String> inbox = request(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                subject, org);
        assertEquals(HttpStatus.OK, inbox.getStatusCode(), inbox.getBody());
        for (JsonNode item : json(inbox)) {
            if (orderId.equals(item.at("/orderRef").asText())) {
                return item;
            }
        }
        throw new IllegalStateException("no work item projected for the released order");
    }

    private void assertControlSucceeds(String marker) {
        ResponseEntity<String> control = request(HttpMethod.POST, "/api/v1/orders",
                clauseOrder(PATIENT_A, marker), "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, control.getStatusCode(), control.getBody());
    }

    @Test
    void releasedOrderCarriesEveryClauseField() throws Exception {
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/orders",
                clauseOrder(PATIENT_A, ENCOUNTER), "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        // The response carries every clause field; sourceActor is the authenticated
        // subject and is distinct from the responsible clinician in the command.
        assertEquals(PATIENT_A, text(created, "/patientId"));
        assertEquals(ENCOUNTER, text(created, "/encounterRef"));
        assertEquals("CBC", text(created, "/orderCode"));
        assertEquals("routine", text(created, "/priority"));
        assertEquals(QUESTION, text(created, "/clinicalQuestion"));
        assertEquals(ORG_B, text(created, "/destinationOrg"));
        assertEquals("clinician-a", text(created, "/sourceActor"));
        assertEquals("dr-a", text(created, "/responsibleClinician"));
        assertEquals("lab-queue", text(created, "/workQueue"));
        assertEquals(GRANT, text(created, "/authorizationId"));
        assertEquals("1", text(created, "/version"));
        String id = text(created, "/id");
        String fingerprint = text(created, "/snapshotFingerprint");
        assertEquals(36, fingerprint.length());

        // A separate read proves the values were persisted, not merely echoed.
        ResponseEntity<String> read = request(HttpMethod.GET, "/api/v1/orders/" + id, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, read.getStatusCode(), read.getBody());
        assertEquals(PATIENT_A, text(read, "/patientId"));
        assertEquals(ENCOUNTER, text(read, "/encounterRef"));
        assertEquals("CBC", text(read, "/orderCode"));
        assertEquals("routine", text(read, "/priority"));
        assertEquals(QUESTION, text(read, "/clinicalQuestion"));
        assertEquals(ORG_B, text(read, "/destinationOrg"));
        assertEquals("clinician-a", text(read, "/sourceActor"));
        assertEquals("dr-a", text(read, "/responsibleClinician"));
        assertEquals("lab-queue", text(read, "/workQueue"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "clinician-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT patient_id, encounter_ref, order_code, priority, clinical_question, "
                                 + "destination_org, source_actor, responsible_clinician, work_queue, version "
                                 + "FROM public.exchange_order WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals(PATIENT_A, result.getString("patient_id"));
                assertEquals(ENCOUNTER, result.getString("encounter_ref"));
                assertEquals("CBC", result.getString("order_code"));
                assertEquals("routine", result.getString("priority"));
                assertEquals(QUESTION, result.getString("clinical_question"));
                assertEquals(ORG_B, result.getString("destination_org"));
                assertEquals("clinician-a", result.getString("source_actor"));
                assertEquals("dr-a", result.getString("responsible_clinician"));
                assertEquals("lab-queue", result.getString("work_queue"));
                assertEquals(1, result.getInt("version"));
            } finally {
                connection.rollback();
            }
        }

        // The destination sees only the bounded projection: the clause fields it
        // carries are priority, clinical question, work queue and destination, plus
        // the order/patient references and fingerprint. It has no sender chart access.
        JsonNode item = workItem(id, "clinician-b", ORG_B);
        assertEquals("routine", item.at("/priority").asText());
        assertEquals(QUESTION, item.at("/clinicalQuestion").asText());
        assertEquals("lab-queue", item.at("/workQueue").asText());
        assertEquals(ORG_B, item.at("/destinationOrg").asText());
        assertEquals(ORG_A, item.at("/senderOrg").asText());
        assertEquals(id, item.at("/orderRef").asText());
        assertEquals(PATIENT_A, item.at("/patientRef").asText());
        assertEquals(fingerprint, item.at("/fingerprint").asText());
        assertEquals("awaiting_local_patient", item.at("/state").asText());
        assertFalse(item.at("/patientVerified").isMissingNode());
        assertFalse(item.at("/patientVerified").asBoolean());
        // Sender-only clause fields are deliberately absent from the projection.
        assertTrue(item.at("/orderCode").isMissingNode());
        assertTrue(item.at("/encounterRef").isMissingNode());
        assertTrue(item.at("/sourceActor").isMissingNode());
        assertTrue(item.at("/responsibleClinician").isMissingNode());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_B, "clinician-b");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT clinical_question, priority, work_queue, state, fingerprint "
                                 + "FROM public.exchange_work_item WHERE order_ref = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals(QUESTION, result.getString("clinical_question"));
                assertEquals("routine", result.getString("priority"));
                assertEquals("lab-queue", result.getString("work_queue"));
                assertEquals("awaiting_local_patient", result.getString("state"));
                assertEquals(fingerprint, result.getString("fingerprint"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void sourceActorCannotBeForgedFromTheRequestBody() throws Exception {
        // An extra JSON property cannot become the authoritative source actor:
        // it is absent from OrderRequest and the migration's insert trigger
        // stamps it from the transaction's authenticated subject.
        String body = "{\"patientId\":\"" + PATIENT_A + "\",\"encounterRef\":\"ENC-FORGED-ACTOR\","
                + "\"orderCode\":\"CBC\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"" + QUESTION + "\",\"destinationOrg\":\"" + ORG_B + "\","
                + "\"purpose\":\"lab review\",\"authorizationId\":\"" + GRANT + "\","
                + "\"responsibleClinician\":\"dr-a\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":30,\"sourceActor\":\"forged-actor\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/orders", body,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("clinician-a", text(created, "/sourceActor"));
        String id = text(created, "/id");

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "clinician-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT source_actor FROM public.exchange_order "
                                 + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("clinician-a", result.getString("source_actor"));
            } finally {
                connection.rollback();
            }
        }
        assertEquals(1, countOrders("ENC-FORGED-ACTOR"));
    }

    @Test
    void wrongRoleCannotReleaseAnOrder() throws Exception {
        assertControlSucceeds("ENC-ROLE-CONTROL");
        ResponseEntity<String> denied = request(HttpMethod.POST, "/api/v1/orders",
                clauseOrder(PATIENT_A, "ENC-WRONG-ROLE"), "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));
        assertEquals(0, countOrders("ENC-WRONG-ROLE"));
    }

    @Test
    void crossTenantPatientIsConcealed() throws Exception {
        assertControlSucceeds("ENC-TENANT-CONTROL");
        // The foreign patient really exists in ORG_B, so the 404 below is RLS
        // concealment in the sender's context rather than a missing fixture.
        assertEquals(1, countPatients(ORG_B, "admin-b", PATIENT_B));
        ResponseEntity<String> denied = request(HttpMethod.POST, "/api/v1/orders",
                clauseOrder(PATIENT_B, "ENC-CROSS-TENANT"), "clinician-a", ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertEquals("NOT_FOUND", text(denied, "/code"));
        assertEquals(0, countOrders("ENC-CROSS-TENANT"));
    }

    @Test
    void unapprovedPriorityAndBlankQuestionAreRefused() throws Exception {
        assertControlSucceeds("ENC-PRIORITY-CONTROL");
        ResponseEntity<String> priority = request(HttpMethod.POST, "/api/v1/orders",
                orderBody(PATIENT_A, "ENC-BAD-PRIORITY", "ASAP", QUESTION, ORG_B),
                "clinician-a", ORG_A);
        assertEquals(422, priority.getStatusCode().value(), priority.getBody());
        assertEquals("INVALID_PRIORITY", text(priority, "/code"));

        ResponseEntity<String> question = request(HttpMethod.POST, "/api/v1/orders",
                orderBody(PATIENT_A, "ENC-BLANK-QUESTION", "routine", "   ", ORG_B),
                "clinician-a", ORG_A);
        assertEquals(422, question.getStatusCode().value(), question.getBody());
        assertEquals("INVALID_INPUT", text(question, "/code"));
        assertTrue(text(question, "/message").contains("clinicalQuestion"), question.getBody());

        assertEquals(0, countOrders("ENC-BAD-PRIORITY"));
        assertEquals(0, countOrders("ENC-BLANK-QUESTION"));
    }

    @Test
    void malformedPatientOrDestinationIsRefused() throws Exception {
        assertControlSucceeds("ENC-MALFORMED-CONTROL");
        ResponseEntity<String> patient = request(HttpMethod.POST, "/api/v1/orders",
                orderBody("not-a-uuid", "ENC-BAD-PATIENT", "routine", QUESTION, ORG_B),
                "clinician-a", ORG_A);
        assertEquals(422, patient.getStatusCode().value(), patient.getBody());
        assertEquals("INVALID_INPUT", text(patient, "/code"));
        assertTrue(text(patient, "/message").contains("patientId"), patient.getBody());

        ResponseEntity<String> destination = request(HttpMethod.POST, "/api/v1/orders",
                orderBody(PATIENT_A, "ENC-BAD-DESTINATION", "routine", QUESTION, "not-a-uuid"),
                "clinician-a", ORG_A);
        assertEquals(422, destination.getStatusCode().value(), destination.getBody());
        assertEquals("INVALID_INPUT", text(destination, "/code"));
        assertTrue(text(destination, "/message").contains("destinationOrg"), destination.getBody());

        assertEquals(0, countOrders("ENC-BAD-PATIENT"));
        assertEquals(0, countOrders("ENC-BAD-DESTINATION"));
    }
}
