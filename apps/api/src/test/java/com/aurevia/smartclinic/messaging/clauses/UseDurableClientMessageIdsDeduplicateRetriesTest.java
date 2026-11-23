package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.api.contract.IdempotencyKey;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.ReplyRequest;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-07 clause 6 gap proof: a retry of a patient-communication reply that
 * carries the same durable client message id must publish one message and return
 * the first outcome; a different payload under the same key must be refused; and
 * the key must be scoped to its tenant.
 *
 * <p>Every test is complete but disabled with an explicit gap reason because the
 * patient conversation path is not built for this rule today:
 * {@code public.conversation_message} has no client message id or unique key,
 * {@code ConversationRepository.insertMessage} has no replay lookup,
 * {@code ConversationController.reply} binds no {@code Idempotency-Key} header and
 * no conversation reply route is published in the OpenAPI contract. Enabling a
 * test here asserts behaviour the current code cannot produce, so it would leave
 * a red suite. A later implementation story builds the rule and enables these
 * tests; no production code is added by this evidence task.</p>
 *
 * <p>The suite drives the real HTTP entry point against real PostgreSQL with a
 * fresh Flyway-migrated database. Two synthetic organizations share the same
 * local patient subject, staff subject, MRN and connection id so a replay lookup
 * that is scoped only by a local identifier cannot pass tenant isolation. The
 * care-unit directory is a test-only in-memory fixture, but the dedup behaviour
 * under proof is asserted on the PostgreSQL message ledger; the eventual
 * implementation must persist replay state in its own RLS-bounded table or
 * column, not in this fixture. All data is synthetic; no patient data or
 * credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_arc07_dedup_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class UseDurableClientMessageIdsDeduplicateRetriesTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_arc07_dedup_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String PATIENT = "patient-1111";
    private static final String LAB_STAFF = "lab-a-1111";
    private static final String KEY = "client-msg-dedup-1";
    private static final String FIRST_BODY = "please bring your results";
    private static final String CHANGED_BODY = "a different clinical question";
    private static final String PEER_BODY = "a separate tenant question";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Deterministic, test-only care-unit directory shared with the Spring context. */
    @Autowired
    private InMemoryMessagingStore messagingStore;

    private RestClient rest;

    /** Recreate the synthetic database, apply the production migrations and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
                seedMember(statement, org, PATIENT, "patient");
                seedMember(statement, org, LAB_STAFF, "lab_verifier");
                seedLink(statement, org, PATIENT, true);
            }
        }
    }

    /** Drop the synthetic database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Create the HTTP client and the per-tenant directory fixtures used by patient open. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        for (String org : new String[] {ORG_A, ORG_B}) {
            messagingStore.addMember(org, PATIENT, "patient", true);
            messagingStore.addMember(org, LAB_STAFF, "lab_verifier", true);
        }
    }

    /**
     * Open a fresh patient thread in one tenant through its verified connection.
     *
     * @param org    active organization
     * @param unit   bounded conversation unit category
     * @return the new conversation identifier
     */
    private String open(String org, String unit) throws Exception {
        String patientId = patientId(org);
        String connectionId = seedConnection(org, patientId, unit);
        String body = "{\"patientId\":\"" + patientId + "\",\"patientSubject\":\"" + PATIENT
                + "\",\"unitKey\":\"" + unit + "\",\"connectionId\":\"" + connectionId
                + "\",\"topic\":\"follow up\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/conversations", body, PATIENT, org,
                Map.of());
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return tree(response).get("id").asString();
    }

    /**
     * Post one reply with an explicit version and an optional durable client message id.
     *
     * @param conversationId owning conversation
     * @param org            active organization
     * @param body           message content
     * @param key            client message id, or {@code null} to omit the header
     * @param ifMatch        version read by the caller
     * @return the raw HTTP response
     */
    private ResponseEntity<String> reply(String conversationId, String org,
                                         String body, String key, String ifMatch) throws Exception {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("If-Match", ifMatch);
        if (key != null) {
            extra.put("Idempotency-Key", IdempotencyKey.parse(key).value());
        }
        return replyRaw(conversationId, org, body, extra);
    }

    /** Post one reply with raw headers so a malformed key can reach the HTTP boundary. */
    private ResponseEntity<String> replyRaw(String conversationId, String org,
                                            String body, Map<String, String> extra) throws Exception {
        return send(HttpMethod.POST, "/api/v1/conversations/" + conversationId + "/reply",
                MAPPER.writeValueAsString(new ReplyRequest(body)), LAB_STAFF, org, extra);
    }

    /**
     * Read the immutable message ledger of a conversation inside the owning tenant.
     *
     * @param org            tenant that owns the conversation
     * @param conversationId conversation identifier
     * @return messages in sequence order
     */
    private List<MessageRow> messages(String org, String conversationId) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT sequence, body FROM public.conversation_message "
                            + "WHERE conversation_id = CAST(? AS uuid) ORDER BY sequence")) {
                query.setString(1, conversationId);
                try (ResultSet rows = query.executeQuery()) {
                    List<MessageRow> found = new ArrayList<>();
                    while (rows.next()) {
                        found.add(new MessageRow(rows.getInt(1), rows.getString(2)));
                    }
                    return found;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * A retried reply with one durable client message id publishes one message and
     * returns the first outcome instead of a stale-version failure.
     */
    @Test
    @Disabled("GAP: patient conversations have no client message id column, replay lookup or "
            + "Idempotency-Key binding; a second reply with the first If-Match returns 412 today")
    void aRetryWithTheSameDurableClientMessageIdPublishesOneMessageAndReturnsTheFirstOutcome() throws Exception {
        String id = open(ORG_A, "lab");

        ResponseEntity<String> first = reply(id, ORG_A, FIRST_BODY, KEY, "1");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        JsonNode firstView = tree(first);
        assertEquals(FIRST_BODY, firstView.get("messages").get(0).get("body").asString());
        int firstVersion = firstView.get("conversation").get("version").asInt();

        // A client retries the same command with the same key and the version it
        // last read. Deduplication must return the first outcome, not insert a
        // second row or fail as a stale update.
        ResponseEntity<String> retry = reply(id, ORG_A, FIRST_BODY, KEY, "1");
        assertEquals(HttpStatus.OK, retry.getStatusCode(), retry.getBody());
        JsonNode retryView = tree(retry);
        assertEquals(firstView.get("messages").toString(), retryView.get("messages").toString());
        assertEquals(firstVersion, retryView.get("conversation").get("version").asInt());
        assertEquals(List.of(new MessageRow(1, FIRST_BODY)), messages(ORG_A, id));
    }

    /**
     * Reusing a durable client message id with different content is a conflict and
     * must not replace or duplicate the first published message. The asserted
     * contract is first-message-wins with a 409 for a changed payload; the version
     * is fresh so the conflict can only come from the key and payload check.
     */
    @Test
    @Disabled("GAP: no client message id or payload digest is stored on public.conversation_message, "
            + "so a second payload under the same key cannot be detected as a replay conflict")
    void aDifferentBodyWithTheSameDurableClientMessageIdIsRefusedWithAConflict() throws Exception {
        String id = open(ORG_A, "lab");
        ResponseEntity<String> first = reply(id, ORG_A, FIRST_BODY, KEY, "1");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        String freshVersion = tree(first).get("conversation").get("version").asString();

        ResponseEntity<String> conflict = reply(id, ORG_A, CHANGED_BODY, KEY, freshVersion);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("IDEMPOTENCY_CONFLICT", tree(conflict).get("code").asString());
        assertEquals(List.of(new MessageRow(1, FIRST_BODY)), messages(ORG_A, id));
    }

    /**
     * The same client message id in another tenant is independent. The retry in
     * tenant B uses B's current version, so merely passing a version check cannot
     * make it green, and both tenants share every local identifier.
     */
    @Test
    @Disabled("GAP: no tenant-scoped unique client message id exists on the patient message "
            + "ledger; a global or local-id-only replay lookup could reuse another tenant's outcome")
    void theSameDurableClientMessageIdInAnotherTenantDoesNotReuseTheFirstTenantsMessage() throws Exception {
        String idA = open(ORG_A, "lab");
        assertEquals(HttpStatus.OK,
                reply(idA, ORG_A, FIRST_BODY, KEY, "1").getStatusCode());

        String idB = open(ORG_B, "lab");
        ResponseEntity<String> publishedB = reply(idB, ORG_B, PEER_BODY, KEY, "1");
        assertEquals(HttpStatus.OK, publishedB.getStatusCode(), publishedB.getBody());
        JsonNode viewB = tree(publishedB);
        String versionB = viewB.get("conversation").get("version").asString();

        ResponseEntity<String> retryB = reply(idB, ORG_B, PEER_BODY, KEY, versionB);
        assertEquals(HttpStatus.OK, retryB.getStatusCode(), retryB.getBody());
        assertEquals(viewB.get("messages").toString(), tree(retryB).get("messages").toString());

        assertEquals(List.of(new MessageRow(1, PEER_BODY)), messages(ORG_B, idB));
        assertEquals(List.of(new MessageRow(1, FIRST_BODY)), messages(ORG_A, idA));
    }

    /**
     * A reply without a durable client message id is refused before any message is
     * written, following the repository's {@code IdempotencyKey.parse} contract.
     */
    @Test
    @Disabled("GAP: ConversationController.reply binds only If-Match, so a missing "
            + "Idempotency-Key is ignored and the reply is published today")
    void aReplyWithoutADurableClientMessageIdIsRejected() throws Exception {
        String id = open(ORG_A, "lab");

        ResponseEntity<String> missing = reply(id, ORG_A, FIRST_BODY, null, "1");
        assertEquals(428, missing.getStatusCode().value(), missing.getBody());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", tree(missing).get("code").asString());
        assertTrue(messages(ORG_A, id).isEmpty());
    }

    /**
     * A malformed client message id is refused before any message is written. The
     * key is sent raw because the helper would reject it in the test process.
     */
    @Test
    @Disabled("GAP: ConversationController.reply does not parse Idempotency-Key, so a malformed "
            + "key is accepted and the reply is published today")
    void aMalformedDurableClientMessageIdIsRejectedBeforeAnyMessageIsWritten() throws Exception {
        String id = open(ORG_A, "lab");

        ResponseEntity<String> malformed = replyRaw(id, ORG_A, FIRST_BODY,
                Map.of("If-Match", "1", "Idempotency-Key", "bad key"));
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_IDEMPOTENCY_KEY", tree(malformed).get("code").asString());
        assertTrue(messages(ORG_A, id).isEmpty());
    }

    /** Seed a tenant with the bootstrap identity and one synthetic patient chart. */
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
                + "DATE '1990-05-20')");
    }

    /** Seed one active member with the given bounded role. */
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

    /** Seed an active verified link from the patient subject to the tenant chart. */
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
     * Register a deterministic active connection and its care unit in the test
     * directory. The connection id ignores the organization so the two tenants
     * genuinely share the local identifier under test.
     */
    private String seedConnection(String org, String patientId, String unit) {
        UnitKey key = UnitKey.from(unit);
        String unitId = UUID.nameUUIDFromBytes(
                ("conversation-unit:" + key.wire()).getBytes(StandardCharsets.UTF_8)).toString();
        String connectionId = UUID.nameUUIDFromBytes(
                ("conversation-connection:" + unit).getBytes(StandardCharsets.UTF_8)).toString();
        messagingStore.addMember(org, PATIENT, "patient", true);
        messagingStore.linkPatient(org, PATIENT, patientId);
        messagingStore.addUnit(new CareUnit(unitId, org, "facility-" + unit, "Synthetic " + unit,
                List.of("messages"), true, unit));
        messagingStore.insertConnection(org, new PatientUnitConnection(connectionId, org, patientId, unitId,
                ConnectionContext.ORDER, "order-ref-" + unit, "rel-" + unit, ConnectionState.ACTIVE,
                PATIENT, Instant.now(), Instant.now(), null));
        return connectionId;
    }

    /** Read the tenant's synthetic patient chart identifier. */
    private String patientId(String org) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    /** Send one raw HTTP request with the synthetic subject and organization headers. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                         String org, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(headers -> {
                    headers.set("X-Synthetic-Subject", subject);
                    headers.set("X-Active-Organization", org);
                    headers.set("X-Correlation-Id", UUID.randomUUID().toString());
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    extra.forEach(headers::set);
                });
        RestClient.RequestHeadersSpec<?> request = body == null ? spec : spec.body(body);
        return request.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a JSON response body. */
    private static JsonNode tree(ResponseEntity<String> response) throws Exception {
        assertTrue(response.getBody() != null);
        return MAPPER.readTree(response.getBody());
    }

    /** A test-only projection of one immutable message row. */
    private record MessageRow(int sequence, String body) {
    }

    /**
     * Keep the deterministic directory fixture primary for this conversation flow.
     * The production bean is
     * {@link com.aurevia.smartclinic.messaging.units.JdbcMessagingStore}; a
     * test-only override keeps the synthetic connections stable.
     */
    @TestConfiguration
    static class SyntheticDirectory {
        /** @return the deterministic, test-only messaging directory */
        @Bean
        @Primary
        InMemoryMessagingStore inMemoryMessagingStore() {
            return new InMemoryMessagingStore();
        }
    }
}
