package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.units.InMemoryMessagingStore;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-07 clause 5 HTTP acceptance on real PostgreSQL: a published reply commits
 * its message, its work-item fact, its audit event and the conversation state in
 * one durable transaction, and a refused reply writes none of them.
 *
 * <p>The notification-outbox leg of the clause is not built in this path. Its
 * would-prove test is preserved in the clause document's Gap section while the
 * shipping constraint gate rejects an added skip marker, so this class proves
 * only the three writes that exist today. All fixtures and narratives are
 * synthetic; two organizations share the same local codes so isolation is
 * tested at the tenant boundary rather than by giving the peer different names.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_publication_atomicity_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CommitPublicationAuditQueueUpdateNotificationOutboxTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_publication_atomicity_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PATIENT = "patient-1111";
    private static final String OTHER_PATIENT = "patient-b-1111";
    private static final String LAB_STAFF = "lab-a-1111";
    private static final String CLINIC_STAFF = "clinic-a-1111";
    private static final String PEER_PATIENT = "patient-2222";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Synthetic messaging directory shared with the Spring context under test. */
    @Autowired
    private InMemoryMessagingStore messagingStore;

    /**
     * Create the HTTP client against the random server port and seed the
     * deterministic directory members for both tenants.
     *
     * @param port server port assigned by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        for (String org : new String[] {ORG_A, ORG_B}) {
            String suffix = org.substring(0, 4);
            messagingStore.addMember(org, "patient-" + suffix, "patient", true);
            messagingStore.addMember(org, "lab-a-" + suffix, "lab_verifier", true);
            messagingStore.addMember(org, "clinic-a-" + suffix, "reception", true);
            if (ORG_A.equals(org)) {
                messagingStore.addMember(org, OTHER_PATIENT, "patient", true);
            }
        }
    }

    /** Recreate the database, apply Flyway and seed the two isolated synthetic tenants. */
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
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B}) {
                String suffix = org.substring(0, 4);
                seedMember(statement, org, "patient-" + suffix, "patient");
                seedMember(statement, org, "lab-a-" + suffix, "lab_verifier");
                seedMember(statement, org, "clinic-a-" + suffix, "reception");
                seedLink(statement, org, "patient-" + suffix, true);
            }
            seedMember(statement, ORG_A, OTHER_PATIENT, "patient");
            seedLink(statement, ORG_A, OTHER_PATIENT, true);
        }
    }

    /** Drop the synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedLink(Statement statement, String org, String subject, boolean active)
            throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.patient_link (organization_id, subject, patient_id, active, created_by) "
                + "SELECT '" + org + "', '" + subject + "', id, " + active + ", "
                + "(SELECT id FROM public.user_identity WHERE subject = 'bootstrap-" + suffix + "') "
                + "FROM public.patient_record WHERE mrn = 'MRN-1'");
    }

    /**
     * Seed a verified connection and its tenant care unit in the synthetic
     * directory so a patient can open a thread for that unit. The identifiers
     * are deterministic, so repeated seeding in one Spring context is idempotent.
     *
     * @param org           owning organization
     * @param patientSubject synthetic patient account subject
     * @param unit          care-unit wire key
     * @return the seeded connection identifier
     */
    private String seedConnection(String org, String patientSubject, String unit) {
        UnitKey key = UnitKey.from(unit);
        String unitId = UUID.nameUUIDFromBytes(
                ("conversation-unit:" + key.wire()).getBytes(StandardCharsets.UTF_8)).toString();
        String connectionId = UUID.nameUUIDFromBytes(
                ("conversation-connection:" + org + ":" + unit).getBytes(StandardCharsets.UTF_8)).toString();
        String patientId = patientId(org);
        messagingStore.addMember(org, patientSubject, "patient", true);
        messagingStore.linkPatient(org, patientSubject, patientId);
        messagingStore.addUnit(new CareUnit(unitId, org, "facility-" + unit, "Synthetic " + unit,
                List.of("messages"), true, unit));
        messagingStore.insertConnection(org, new PatientUnitConnection(connectionId, org, patientId, unitId,
                ConnectionContext.ORDER, "order-ref-" + unit, "rel-" + unit, ConnectionState.ACTIVE,
                patientSubject, Instant.now(), Instant.now(), null));
        return connectionId;
    }

    /**
     * Open a fresh synthetic conversation through the normal HTTP entry point.
     *
     * @param org           owning organization
     * @param patientSubject verified patient subject
     * @param unit          recipient care-unit key
     * @return the new conversation identifier
     */
    private String open(String org, String patientSubject, String unit) {
        String connectionId = seedConnection(org, patientSubject, unit);
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"patientSubject\":\"" + patientSubject
                + "\",\"unitKey\":\"" + unit + "\",\"connectionId\":\"" + connectionId
                + "\",\"topic\":\"follow up\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/conversations", body,
                patientSubject, org, Map.of());
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return text(response, "id");
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            var result = statement.executeQuery("SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'");
            assertTrue(result.next());
            return result.getString(1);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> {
                    httpHeaders.set("X-Synthetic-Subject", subject);
                    httpHeaders.set("X-Active-Organization", organization);
                    httpHeaders.set("X-Correlation-Id", UUID.randomUUID().toString());
                    httpHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    extra.forEach(httpHeaders::set);
                });
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) throws Exception {
        assertTrue(response.getBody() != null && !response.getBody().isBlank());
        return MAPPER.readTree(response.getBody());
    }

    private static String text(ResponseEntity<String> response, String field) {
        try {
            return tree(response).get(field).asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Open a read-only JDBC session bound to the caller's tenant and actor so
     * forced row-level security is exercised on every direct assertion.
     */
    private static Connection scoped(String org, String subject) throws Exception {
        Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
        connection.setAutoCommit(false);
        try (PreparedStatement bind = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            bind.setString(1, org);
            bind.setString(2, subject);
            bind.execute();
        }
        return connection;
    }

    /** Count all rows matching a one-argument predicate in the caller's tenant. */
    private static long scalarCount(String sql, String org, String subject, String argument) throws Exception {
        Connection connection = scoped(org, subject);
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, argument);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count committed messages of a conversation. */
    private static long messageCount(String org, String subject, String conversationId) throws Exception {
        return scalarCount("SELECT count(*) FROM public.conversation_message "
                + "WHERE conversation_id = CAST(? AS uuid)", org, subject, conversationId);
    }

    /** Count committed messages with the expected sender and body. */
    private static long matchingMessageCount(String org, String subject, String conversationId,
                                             String senderKind, String senderSubject, String body)
            throws Exception {
        Connection connection = scoped(org, subject);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.conversation_message WHERE conversation_id = CAST(? AS uuid) "
                        + "AND sender_kind = ? AND sender_subject = ? AND body = ?")) {
            query.setString(1, conversationId);
            query.setString(2, senderKind);
            query.setString(3, senderSubject);
            query.setString(4, body);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count committed work-item facts of a given type. */
    private static long eventCount(String org, String subject, String conversationId, String eventType)
            throws Exception {
        Connection connection = scoped(org, subject);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.work_item_event WHERE conversation_id = CAST(? AS uuid) "
                        + "AND event_type = ?")) {
            query.setString(1, conversationId);
            query.setString(2, eventType);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count committed work-item facts of a type bound to a specific conversation version. */
    private static long eventCountAtVersion(String org, String subject, String conversationId,
                                            String eventType, int version) throws Exception {
        Connection connection = scoped(org, subject);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.work_item_event WHERE conversation_id = CAST(? AS uuid) "
                        + "AND event_type = ? AND conversation_version = ?")) {
            query.setString(1, conversationId);
            query.setString(2, eventType);
            query.setInt(3, version);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count committed audit facts for this conversation and command outcome. */
    private static long auditCount(String org, String subject, String conversationId,
                                   String action, String outcome) throws Exception {
        Connection connection = scoped(org, subject);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.audit_event WHERE resource_id = ? "
                        + "AND action = ? AND outcome = ?")) {
            query.setString(1, conversationId);
            query.setString(2, action);
            query.setString(3, outcome);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Read the durable conversation projection; the work queue is derived from it. */
    private static Projection projection(String org, String subject, String conversationId) throws Exception {
        Connection connection = scoped(org, subject);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT state, version FROM public.conversation WHERE id = CAST(? AS uuid)")) {
            query.setString(1, conversationId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "the conversation must remain visible to its own tenant");
                return new Projection(rows.getString("state"), rows.getInt("version"));
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count durable outbox rows bound to a conversation aggregate. */
    private static long outboxCount(String org, String subject, String conversationId) throws Exception {
        return scalarCount("SELECT count(*) FROM public.delivery_outbox "
                + "WHERE aggregate_id = CAST(? AS uuid)", org, subject, conversationId);
    }

    /**
     * Install a temporary trigger that rejects the reopen fact of one synthetic
     * conversation. The failure lands after the message and state writes, so a
     * rollback proves those earlier writes share the same commit. The predicate
     * is the server-generated UUID of this test's own conversation only.
     *
     * @param conversationId conversation whose reopen fact must fail
     */
    private static void failReopenFactFor(String conversationId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS clause_test_fail_work_item_event "
                    + "ON public.work_item_event");
            statement.execute("CREATE OR REPLACE FUNCTION public.clause_test_fail_work_item_event() "
                    + "RETURNS trigger LANGUAGE plpgsql AS $fn$ BEGIN "
                    + "IF NEW.conversation_id = CAST('" + conversationId + "' AS uuid) THEN "
                    + "RAISE EXCEPTION 'injected work-item fact failure'; END IF; "
                    + "RETURN NEW; END $fn$");
            statement.execute("CREATE TRIGGER clause_test_fail_work_item_event BEFORE INSERT "
                    + "ON public.work_item_event FOR EACH ROW "
                    + "EXECUTE FUNCTION public.clause_test_fail_work_item_event()");
        }
    }

    /** Remove the temporary fault-injection trigger when the test is finished. */
    private static void removeReopenFactFailure() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS clause_test_fail_work_item_event "
                    + "ON public.work_item_event");
            statement.execute("DROP FUNCTION IF EXISTS public.clause_test_fail_work_item_event()");
        }
    }

    /** Durable work-item state and version that drive staff queue visibility. */
    private record Projection(String state, int version) {
    }

    /**
     * Positive proof: an ordinary staff reply commits its message, its new
     * state and its audit fact together, with no spurious work-item fact.
     */
    @Test
    void replyCommitsPublicationStateAndAuditInOneTransaction() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");

        ResponseEntity<String> reply = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"please bring your results\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(200, reply.getStatusCode().value(), reply.getBody());
        JsonNode view = tree(reply);
        assertEquals("awaiting_patient", view.get("conversation").get("state").asText());
        assertEquals(2, view.get("conversation").get("version").asInt());

        assertEquals(1L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, matchingMessageCount(ORG_A, LAB_STAFF, id,
                "staff", LAB_STAFF, "please bring your results"));
        assertEquals(1L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        assertEquals(0L, eventCount(ORG_A, LAB_STAFF, id, "reopened"));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "created"));
        Projection committed = projection(ORG_A, LAB_STAFF, id);
        assertEquals("awaiting_patient", committed.state());
        assertEquals(2, committed.version());
        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
    }

    /**
     * Positive proof: a staff reply that reopens a resolved thread commits the
     * message, the reopen fact, the audit fact and the new state together.
     */
    @Test
    void publicationAuditAndQueueUpdateCommitTogether() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");
        ResponseEntity<String> resolved = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/resolve", "{\"reason\":\"answered\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(200, resolved.getStatusCode().value(), resolved.getBody());

        ResponseEntity<String> reply = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"please bring your results\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "2"));
        assertEquals(200, reply.getStatusCode().value(), reply.getBody());
        JsonNode view = tree(reply);
        assertEquals("awaiting_patient", view.get("conversation").get("state").asText());
        assertEquals(3, view.get("conversation").get("version").asInt());
        assertEquals(1, view.get("messages").size());
        assertTrue(view.get("events").toString().contains("reopened"));

        // Direct reads on the committed database: all four existing facts are
        // durable, and none of them leaks to the peer tenant under forced RLS.
        assertEquals(1L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, matchingMessageCount(ORG_A, LAB_STAFF, id,
                "staff", LAB_STAFF, "please bring your results"));
        assertEquals(1L, eventCountAtVersion(ORG_A, LAB_STAFF, id, "reopened", 3));
        assertEquals(1L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        Projection committed = projection(ORG_A, LAB_STAFF, id);
        assertEquals("awaiting_patient", committed.state());
        assertEquals(3, committed.version());
        assertEquals(0L, messageCount(ORG_B, PEER_PATIENT, id));
    }

    /**
     * Negative proof: a stale precondition refuses the reply and rolls back
     * every write the command would have committed.
     */
    @Test
    void staleVersionRefusesReplyAndWritesNothing() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"stale attempt\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "9"));
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", tree(refused).get("code").asText());

        assertEquals(0L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "created"));
        assertEquals(0L, eventCount(ORG_A, LAB_STAFF, id, "reopened"));
        assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        Projection unchanged = projection(ORG_A, LAB_STAFF, id);
        assertEquals("awaiting_staff", unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /**
     * Negative proof: a peer tenant cannot publish into another tenant's
     * conversation, and forced RLS hides the thread from the peer entirely.
     */
    @Test
    void peerTenantCannotPublishIntoAnotherTenantsConversation() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");
        String peerId = open(ORG_B, PEER_PATIENT, "lab");
        assertNotEquals(id, peerId);

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"cross tenant attempt\"}",
                PEER_PATIENT, ORG_B, Map.of("If-Match", "1"));
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());

        assertEquals(0L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "created"));
        assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        Projection unchanged = projection(ORG_A, LAB_STAFF, id);
        assertEquals("awaiting_staff", unchanged.state());
        assertEquals(1, unchanged.version());
        assertEquals(0L, messageCount(ORG_B, PEER_PATIENT, id), "the peer tenant cannot see the row at all");
    }

    /**
     * Negative proof: another verified patient in the same tenant cannot
     * publish into this patient's thread; the ownership check conceals it.
     */
    @Test
    void otherPatientCannotPublishIntoThisPatientsConversation() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"wrong patient attempt\"}",
                OTHER_PATIENT, ORG_A, Map.of("If-Match", "1"));
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());

        assertEquals(0L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "created"));
        assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        Projection unchanged = projection(ORG_A, LAB_STAFF, id);
        assertEquals("awaiting_staff", unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /**
     * Negative proof: a staff member who cannot work the recipient unit may
     * not publish, and the refusal leaves no message, fact or audit behind.
     */
    @Test
    void wrongUnitRoleCannotPublish() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"wrong unit attempt\"}",
                CLINIC_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", tree(refused).get("code").asText());

        assertEquals(0L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "created"));
        assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        // The unit check throws inside the guarded transaction, so even the
        // denial audit rolls back; only authorize-path denials are committed.
        assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "DENIED"));
        Projection unchanged = projection(ORG_A, LAB_STAFF, id);
        assertEquals("awaiting_staff", unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /**
     * Negative proof: a closed thread refuses publication and keeps its
     * append-only history unchanged.
     */
    @Test
    void closedThreadRefusesPublicationAndWritesNothing() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");
        ResponseEntity<String> closed = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/close", "{\"reason\":\"done\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(200, closed.getStatusCode().value(), closed.getBody());

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"after close\"}",
                PATIENT, ORG_A, Map.of("If-Match", "2"));
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("CLOSED_THREAD", tree(refused).get("code").asText());

        assertEquals(0L, messageCount(ORG_A, LAB_STAFF, id));
        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "created"));
        assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "closed"));
        assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
        Projection unchanged = projection(ORG_A, LAB_STAFF, id);
        assertEquals("closed", unchanged.state());
        assertEquals(2, unchanged.version());
    }

    /**
     * Atomicity proof by fault injection: when the reopen fact fails after the
     * message and state writes, every earlier write rolls back with it. Without
     * this test, co-presence on success would not distinguish one commit from
     * several commit-at-the-end transactions.
     */
    @Test
    void failureAfterPublicationRollsBackStateAuditAndWorkItemFact() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");
        ResponseEntity<String> resolved = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/resolve", "{\"reason\":\"answered\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(200, resolved.getStatusCode().value(), resolved.getBody());

        failReopenFactFor(id);
        try {
            ResponseEntity<String> failed = send(HttpMethod.POST,
                    "/api/v1/conversations/" + id + "/reply", "{\"body\":\"roll me back\"}",
                    LAB_STAFF, ORG_A, Map.of("If-Match", "2"));
            assertEquals(500, failed.getStatusCode().value(), failed.getBody());

            assertEquals(0L, messageCount(ORG_A, LAB_STAFF, id));
            assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
            assertEquals(0L, eventCountAtVersion(ORG_A, LAB_STAFF, id, "reopened", 3));
            assertEquals(1L, eventCount(ORG_A, LAB_STAFF, id, "resolved"));
            assertEquals(0L, auditCount(ORG_A, LAB_STAFF, id, "CONVERSATION_REPLY", "SUCCESS"));
            Projection unchanged = projection(ORG_A, LAB_STAFF, id);
            assertEquals("resolved", unchanged.state());
            assertEquals(2, unchanged.version());
        } finally {
            removeReopenFactFailure();
        }
    }

    /**
     * Active gap marker: the clause is only partly proven because this reply
     * path commits no notification outbox row. This characterization asserts
     * today's absence so the claim cannot be overstated; the complete
     * would-prove assertion is in the clause document's Gap section and must be
     * enabled, inverting this test, when the outbox is wired.
     */
    @Test
    void replyCurrentlyWritesNoNotificationOutboxRow() throws Exception {
        String id = open(ORG_A, PATIENT, "lab");
        ResponseEntity<String> reply = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply", "{\"body\":\"notify the patient\"}",
                LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(200, reply.getStatusCode().value(), reply.getBody());

        assertEquals(0L, outboxCount(ORG_A, LAB_STAFF, id));
        assertEquals(0L, outboxCount(ORG_B, PEER_PATIENT, id));
    }

    /**
     * Keep this clause proof on the deterministic directory it was written for.
     * The production bean is the JDBC messaging store; a primary test bean
     * overrides it here so the assertions do not depend on SQL seeding of the
     * directory.
     */
    @TestConfiguration
    static class SyntheticDirectory {
        /**
         * @return the deterministic, test-only messaging directory
         */
        @Bean
        @Primary
        InMemoryMessagingStore inMemoryMessagingStore() {
            return new InMemoryMessagingStore();
        }
    }
}
