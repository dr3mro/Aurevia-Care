package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
 * ARC-07 clause 2 acceptance: a reply stores the sender's identity and active
 * role, the exact immutable body, a server-generated timestamp and a monotonic
 * per-conversation sequence, and every refusal stores no message at all.
 *
 * <p>The write is a real HTTP reply through ConversationController on a real
 * PostgreSQL database with two synthetic tenants. Stored state is asserted
 * directly on public.conversation_message under the correct forced-RLS scope
 * and through the ordinary GET thread view. Each test seeds its own uniquely
 * identified conversation, so message counts and sequences cannot leak between
 * tests. All identifiers and text are synthetic; no patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_store_message_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class StoreMessageSenderIdentitySenderRoleImmutableTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_ID_A = "cccccccc-0000-0000-0000-000000000001";
    private static final String PATIENT_ID_B = "cccccccc-0000-0000-0000-000000000002";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_store_message_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String BOOTSTRAP_A = "bootstrap-1111";
    private static final String BOOTSTRAP_B = "bootstrap-2222";
    private static final String PATIENT_A = "patient-1111";
    private static final String PATIENT_B = "patient-2222";
    private static final String LAB_STAFF_A = "lab-a-1111";
    private static final String LAB_STAFF_B = "lab-b-2222";
    private static final String LAB_ROLE = "lab_verifier";
    private static final String UNIT = "lab";
    private static final String BODY = "please bring your results to the lab";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String conversationId;

    /** Immutable snapshot of one stored message row for assertions. */
    private record StoredMessage(String id, String conversationId, int sequence, String senderKind,
                                 String senderSubject, String senderRole, String body, Instant createdAt) {
    }

    /**
     * Create the HTTP client and seed a fresh conversation for this test.
     *
     * @param port server port assigned by Spring Boot
     */
    @BeforeEach
    void setUp(@LocalServerPort int port) throws Exception {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        conversationId = UUID.randomUUID().toString();
        seedConversation(ORG_A, BOOTSTRAP_A, PATIENT_ID_A, PATIENT_A, conversationId);
    }

    /** Recreate the database, apply Flyway and seed two isolated tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_store_message_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_store_message_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, PATIENT_ID_A);
            seedTenant(statement, ORG_B, PATIENT_ID_B);
        }
    }

    /** Drop the synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_store_message_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, String patientId) throws Exception {
        String suffix = org.substring(0, 4);
        String bootstrap = "bootstrap-" + suffix;
        String patient = "patient-" + suffix;
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-ARC07', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
        seedMember(statement, org, bootstrap, patient, "patient");
        // Exactly one eligible staff role keeps the stored sender_role deterministic.
        seedMember(statement, org, bootstrap, "lab-a-" + suffix, LAB_ROLE);
        if (ORG_B.equals(org)) {
            seedMember(statement, org, bootstrap, LAB_STAFF_B, LAB_ROLE);
        }
        // The active patient link must exist before any conversation is inserted:
        // V33's conversation_verified_link_guard re-checks it on insert.
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.patient_link "
                + "(organization_id, subject, patient_id, active, created_by) "
                + "VALUES ('" + org + "', '" + patient + "', '" + patientId + "', true, "
                + "(SELECT id FROM public.user_identity WHERE subject = '" + bootstrap
                + "' AND organization_id = '" + org + "'))");
    }

    private static void seedMember(Statement statement, String org, String bootstrap,
                                   String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    private static void seedConversation(String org, String bootstrap, String patientId,
                                         String patientSubject, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // Both settings are required: RLS WITH CHECK uses app_org() and the
            // V33 author trigger resolves app.actor to an existing user of the tenant.
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + bootstrap + "'");
            statement.execute("INSERT INTO public.conversation "
                    + "(organization_id, id, version, patient_id, patient_subject, unit_key, "
                    + "connection_id, topic, state) "
                    + "VALUES ('" + org + "', '" + id + "', 1, '" + patientId + "', '"
                    + patientSubject + "', '" + UNIT + "', 'conn-" + id + "', 'synthetic topic', 'awaiting_staff')");
        }
    }

    private HttpHeaders headers(String subject, String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", "arc07" + UUID.randomUUID().toString().replace("-", ""));
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> reply(String subject, String organization, String body, String ifMatch) {
        return reply(conversationId, subject, organization, body, ifMatch);
    }

    private ResponseEntity<String> reply(String targetId, String subject, String organization,
                                         String body, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.POST)
                .uri("/api/v1/conversations/" + targetId + "/reply")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, ifMatch)));
        RestClient.RequestHeadersSpec<?> request = body == null ? spec : spec.body(body);
        return request.retrieve()
                .onStatus(status -> true, (httpRequest, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> view(String subject, String organization) {
        return rest.method(HttpMethod.GET).uri("/api/v1/conversations/" + conversationId)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, null)))
                .retrieve()
                .onStatus(status -> true, (httpRequest, response) -> { })
                .toEntity(String.class);
    }

    /** Read the stored message trail for this conversation under one tenant scope. */
    private List<StoredMessage> messages(String organization) throws Exception {
        return messages(organization, conversationId);
    }

    /** Read a named conversation's stored trail under one tenant scope. */
    private List<StoredMessage> messages(String organization, String targetId) throws Exception {
        List<StoredMessage> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement binding = connection.createStatement();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT id::text, conversation_id::text, sequence, sender_kind, sender_subject, "
                     + "sender_role, body, created_at FROM public.conversation_message "
                     + "WHERE conversation_id = CAST(? AS uuid) ORDER BY sequence")) {
            binding.execute("SET app.current_org = '" + organization + "'");
            query.setString(1, targetId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(new StoredMessage(rows.getString(1), rows.getString(2), rows.getInt(3),
                            rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7),
                            rows.getObject(8, OffsetDateTime.class).toInstant()));
                }
            }
        }
        return result;
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        assertNotNull(response.getBody());
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse conversation response", exception);
        }
    }

    @Test
    void replyStoresSenderIdentityRoleBodyServerTimestampAndSequence() throws Exception {
        assertTrue(messages(ORG_A).isEmpty(), "a new thread has no messages");
        Instant before = Instant.now().minusSeconds(60);
        ResponseEntity<String> response = reply(LAB_STAFF_A, ORG_A,
                "{\"body\":\"" + BODY + "\"}", "1");
        Instant after = Instant.now().plusSeconds(60);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        JsonNode viewMessages = tree(response).get("messages");
        assertEquals(1, viewMessages.size());
        JsonNode viewMessage = viewMessages.get(0);
        assertEquals("staff", viewMessage.get("senderKind").asString());
        assertEquals(LAB_STAFF_A, viewMessage.get("senderSubject").asString());
        assertEquals(LAB_ROLE, viewMessage.get("senderRole").asString());
        assertEquals(BODY, viewMessage.get("body").asString());
        assertEquals(1, viewMessage.get("sequence").asInt());

        List<StoredMessage> stored = messages(ORG_A);
        assertEquals(1, stored.size());
        StoredMessage row = stored.get(0);
        assertEquals(viewMessage.get("id").asString(), row.id());
        assertEquals(conversationId, row.conversationId());
        assertEquals("staff", row.senderKind());
        assertEquals(LAB_STAFF_A, row.senderSubject(),
                "the sender is the authenticated actor, not a client-supplied identity");
        assertEquals(LAB_ROLE, row.senderRole(), "the sender role is the one active at publication");
        assertEquals(BODY, row.body(), "the body is stored verbatim");
        assertEquals(1, row.sequence(), "the first message has sequence one");
        assertFalse(row.createdAt().isBefore(before), "created_at is stamped by the server at reply time");
        assertFalse(row.createdAt().isAfter(after), "created_at is stamped by the server at reply time");
        assertEquals(row.createdAt().truncatedTo(ChronoUnit.MILLIS),
                Instant.parse(viewMessage.get("createdAt").asString()).truncatedTo(ChronoUnit.MILLIS),
                "the thread view must show the stored server timestamp");

        ResponseEntity<String> read = view(LAB_STAFF_A, ORG_A);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertEquals(row.body(), tree(read).get("messages").get(0).get("body").asString());
    }

    @Test
    void staleIfMatchReplyIsRefusedAndStoresNoMessage() throws Exception {
        ResponseEntity<String> stale = reply(LAB_STAFF_A, ORG_A,
                "{\"body\":\"" + BODY + "\"}", "99");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", tree(stale).get("code").asString());
        assertTrue(messages(ORG_A).isEmpty(), "a refused reply stores no message");
    }

    @Test
    void otherTenantCannotReplyInThisConversation() throws Exception {
        // Positive control: the same caller can reply inside its own tenant, so
        // the cross-tenant 404 is FORCE RLS concealment, not a failed login.
        String ownConversation = UUID.randomUUID().toString();
        seedConversation(ORG_B, BOOTSTRAP_B, PATIENT_ID_B, PATIENT_B, ownConversation);
        ResponseEntity<String> own = reply(ownConversation, LAB_STAFF_B, ORG_B,
                "{\"body\":\"own tenant reply\"}", "1");
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertEquals(1, messages(ORG_B, ownConversation).size(),
                "the tenant-B caller is authorized to reply in its own thread");

        ResponseEntity<String> denied = reply(LAB_STAFF_B, ORG_B,
                "{\"body\":\"" + BODY + "\"}", "1");
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertTrue(messages(ORG_A).isEmpty(), "a foreign tenant writes no message into this thread");
    }

    @Test
    void missingBodyIsRejectedBeforeStorage() throws Exception {
        ResponseEntity<String> missing = reply(LAB_STAFF_A, ORG_A, "{}", "1");
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());
        assertEquals("INVALID_BODY", tree(missing).get("code").asString());
        ResponseEntity<String> blank = reply(LAB_STAFF_A, ORG_A, "{\"body\":\"   \"}", "1");
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertEquals("INVALID_BODY", tree(blank).get("code").asString());
        assertTrue(messages(ORG_A).isEmpty(), "an invalid body stores no message");
    }

    @Test
    void storedMessageContentIsImmutable() throws Exception {
        ResponseEntity<String> response = reply(LAB_STAFF_A, ORG_A,
                "{\"body\":\"" + BODY + "\"}", "1");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals(1, messages(ORG_A).size());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException rejected = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.conversation_message SET body = 'tampered' "
                    + "WHERE conversation_id = '" + conversationId + "'"));
            assertTrue(rejected.getMessage().contains("append-only"),
                    "the append-only trigger, not an incidental error, rejects the body change");
        }
        assertEquals(BODY, messages(ORG_A).get(0).body(), "the stored body is unchanged");
        assertEquals(BODY, tree(view(LAB_STAFF_A, ORG_A)).get("messages").get(0).get("body").asString());
    }

    @Test
    void secondReplyAdvancesSequence() throws Exception {
        ResponseEntity<String> first = reply(LAB_STAFF_A, ORG_A,
                "{\"body\":\"first synthetic reply\"}", "1");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        ResponseEntity<String> second = reply(LAB_STAFF_A, ORG_A,
                "{\"body\":\"second synthetic reply\"}", "2");
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody());

        List<StoredMessage> stored = messages(ORG_A);
        assertEquals(2, stored.size());
        assertEquals(1, stored.get(0).sequence());
        assertEquals("first synthetic reply", stored.get(0).body());
        assertEquals(2, stored.get(1).sequence(),
                "the second message advances the per-conversation sequence");
        assertEquals("second synthetic reply", stored.get(1).body());

        JsonNode viewMessages = tree(view(LAB_STAFF_A, ORG_A)).get("messages");
        assertEquals(2, viewMessages.size());
        assertEquals(1, viewMessages.get(0).get("sequence").asInt());
        assertEquals(2, viewMessages.get(1).get("sequence").asInt());
    }
}
