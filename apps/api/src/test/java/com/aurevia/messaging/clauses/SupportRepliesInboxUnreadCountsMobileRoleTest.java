package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * MSG-02 clause 2 (API evidence): replies, an inbox with unread counts and a
 * role-scoped staff work queue.
 *
 * <p>The rule under test is the read and reply side only. A verified patient
 * may reply to a unit conversation and read an inbox whose unread count is
 * derived from staff messages past the patient watermark; an eligible unit
 * staff member may reply and read a work queue restricted to the units that
 * role may work. The positive test drives a full lifecycle through
 * {@code POST /api/v1/conversations/{id}/reply}, {@code GET /api/v1/conversations/inbox}
 * and {@code GET /api/v1/conversations/work-queue} over HTTP against a real
 * PostgreSQL database seeded with the production Flyway migrations. Five
 * negative tests prove the rule refuses what it must refuse: the queue is
 * denied to a patient, a stale version is rejected, a closed thread is
 * read-only, another tenant's thread is invisible under forced RLS, and a
 * role from another unit cannot see that unit's queue row.</p>
 *
 * <p>The production chain is already present and is not changed here: the
 * injected {@link com.aurevia.messaging.conversations.ConversationController}
 * delegates to {@link com.aurevia.messaging.conversations.ConversationService},
 * whose {@link com.aurevia.messaging.conversations.ConversationRepository}
 * reads and writes run under the forced tenant row-level security installed by
 * V33. All fixtures are synthetic; two organizations share the local patient
 * MRN and account subject so tenant isolation is observable. This task adds
 * proof only — no production code, migration, contract or existing test is
 * changed.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_msg02_c02_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SupportRepliesInboxUnreadCountsMobileRoleTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_msg02_c02_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PATIENT = "patient-1111";
    private static final String LAB_STAFF = "lab-a-1111";
    private static final String RECEPTION = "reception-a-1111";
    private static final String FOREIGN_ID = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Synthetic messaging directory shared with the Spring context under test. */
    @Autowired
    private InMemoryMessagingStore messagingStore;

    /**
     * Recreate the scratch database, apply the production migrations and seed
     * two isolated tenants that share local patient identifiers.
     *
     * @throws Exception when database setup or seeding fails
     */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_msg02_c02_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_msg02_c02_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
            }
            seedMember(statement, ORG_A, PATIENT, "patient");
            seedMember(statement, ORG_A, LAB_STAFF, "lab_verifier");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_B, PATIENT, "patient");
            seedLink(statement, ORG_A, PATIENT, true);
            seedLink(statement, ORG_B, PATIENT, true);
            seedForeignConversation(statement);
        }
    }

    /** Drop the scratch database so this class can be run repeatedly. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_msg02_c02_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client and re-seed the in-memory membership fold.
     *
     * @param port injected random port of the test server
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        messagingStore.addMember(ORG_A, PATIENT, "patient", true);
        messagingStore.addMember(ORG_A, LAB_STAFF, "lab_verifier", true);
        messagingStore.addMember(ORG_A, RECEPTION, "reception", true);
        messagingStore.addMember(ORG_B, PATIENT, "patient", true);
    }

    /**
     * A staff reply increments the patient's derived unread count and appears
     * in the replying role's own work queue.
     */
    @Test
    void replyPostsThenInboxShowsUnreadCountAndWorkQueueIsRoleScoped() {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");

        ResponseEntity<String> inboxBefore = send(HttpMethod.GET,
                "/api/v1/conversations/inbox", null, PATIENT, ORG_A);
        assertEquals(HttpStatus.OK, inboxBefore.getStatusCode(), inboxBefore.getBody());
        assertEquals(0, inboxEntry(inboxBefore, id).get("unreadCount").asInt(),
                "a freshly opened thread has no unread staff messages");

        ResponseEntity<String> reply = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"synthetic reply\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, reply.getStatusCode(), reply.getBody());
        JsonNode view = tree(reply);
        assertEquals("awaiting_patient", view.get("conversation").get("state").asString());
        assertEquals(2, view.get("conversation").get("version").asInt());
        assertEquals(1, view.get("messages").size());
        JsonNode message = view.get("messages").get(0);
        assertEquals(1, message.get("sequence").asInt(),
                "the reply is the first message; open() only appends a work-item fact");
        assertEquals("staff", message.get("senderKind").asString());
        assertEquals(LAB_STAFF, message.get("senderSubject").asString());
        assertEquals("synthetic reply", message.get("body").asString());

        ResponseEntity<String> inboxAfter = send(HttpMethod.GET,
                "/api/v1/conversations/inbox", null, PATIENT, ORG_A);
        assertEquals(HttpStatus.OK, inboxAfter.getStatusCode(), inboxAfter.getBody());
        assertEquals(1, inboxEntry(inboxAfter, id).get("unreadCount").asInt(),
                "the staff reply is the first message past the patient read watermark");

        ResponseEntity<String> queue = send(HttpMethod.GET,
                "/api/v1/conversations/work-queue", null, LAB_STAFF, ORG_A);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertTrue(queueContains(queue, id), "lab_verifier sees its unit's open work item");
    }

    /** A patient has an inbox permission but no work-queue permission. */
    @Test
    void workQueueIsDeniedToPatientRole() {
        ResponseEntity<String> denied = send(HttpMethod.GET,
                "/api/v1/conversations/work-queue", null, PATIENT, ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", tree(denied).get("code").asString());
    }

    /** A reply whose If-Match version is no longer current is refused. */
    @Test
    void replyWithStaleIfMatchIsRejected() {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        ResponseEntity<String> stale = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"stale\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", tree(stale).get("code").asString());

        ResponseEntity<String> thread = send(HttpMethod.GET,
                "/api/v1/conversations/" + id, null, PATIENT, ORG_A);
        assertEquals(HttpStatus.OK, thread.getStatusCode(), thread.getBody());
        assertEquals(0, tree(thread).get("messages").size(), "a stale reply must not be appended");
    }

    /**
     * A conversation in another tenant is invisible even when the two tenants
     * use the very same local patient subject.
     */
    @Test
    void anotherTenantsConversationIsNotVisible() {
        ResponseEntity<String> inboxA = send(HttpMethod.GET,
                "/api/v1/conversations/inbox", null, PATIENT, ORG_A);
        assertEquals(HttpStatus.OK, inboxA.getStatusCode(), inboxA.getBody());
        assertFalse(hasInboxEntry(inboxA, FOREIGN_ID),
                "forced RLS hides the other tenant's thread from the inbox");

        ResponseEntity<String> hidden = send(HttpMethod.GET,
                "/api/v1/conversations/" + FOREIGN_ID, null, PATIENT, ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode(), hidden.getBody());

        // Control: the owning tenant sees the same row for the same subject, so
        // the 404 above is RLS concealment and not a missing or renamed row.
        ResponseEntity<String> visible = send(HttpMethod.GET,
                "/api/v1/conversations/" + FOREIGN_ID, null, PATIENT, ORG_B);
        assertEquals(HttpStatus.OK, visible.getStatusCode(), visible.getBody());
        assertEquals(PATIENT, tree(visible).get("conversation").get("patientSubject").asString());
        ResponseEntity<String> inboxB = send(HttpMethod.GET,
                "/api/v1/conversations/inbox", null, PATIENT, ORG_B);
        assertEquals(HttpStatus.OK, inboxB.getStatusCode(), inboxB.getBody());
        assertTrue(hasInboxEntry(inboxB, FOREIGN_ID), "the owning tenant reads its own thread");
    }

    /** A closed thread is read-only: a reply with the current version is refused. */
    @Test
    void closedThreadRefusesReply() {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        ResponseEntity<String> closed = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/close",
                "{\"reason\":\"synthetic close\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, closed.getStatusCode(), closed.getBody());
        assertEquals("closed", tree(closed).get("state").asString());
        assertEquals(2, tree(closed).get("version").asInt());

        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"after close\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("CLOSED_THREAD", tree(refused).get("code").asString());
    }

    /** A role allowed to read the queue still cannot see another unit's rows. */
    @Test
    void wrongUnitRoleCannotSeeUnitQueueRow() {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        ResponseEntity<String> clinicQueue = send(HttpMethod.GET,
                "/api/v1/conversations/work-queue", null, RECEPTION, ORG_A);
        assertEquals(HttpStatus.OK, clinicQueue.getStatusCode(), clinicQueue.getBody());
        assertFalse(queueContains(clinicQueue, id),
                "reception is a CLINIC role and cannot see a LAB work item");

        ResponseEntity<String> labQueue = send(HttpMethod.GET,
                "/api/v1/conversations/work-queue", null, LAB_STAFF, ORG_A);
        assertEquals(HttpStatus.OK, labQueue.getStatusCode(), labQueue.getBody());
        assertTrue(queueContains(labQueue, id), "the row exists and is visible to the eligible LAB role");
    }

    private static void seedOrganization(Statement statement, String organization) throws Exception {
        String shortOrg = organization.substring(0, 4);
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = 'bootstrap-" + shortOrg + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + organization + "', 'Synthetic " + shortOrg + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', 'bootstrap-" + shortOrg + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + organization + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String organization, String subject, String role)
            throws Exception {
        String shortOrg = organization.substring(0, 4);
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = 'bootstrap-" + shortOrg + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedLink(Statement statement, String organization, String subject, boolean active)
            throws Exception {
        String shortOrg = organization.substring(0, 4);
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = 'bootstrap-" + shortOrg + "'");
        statement.execute("INSERT INTO public.patient_link (organization_id, subject, patient_id, active, created_by) "
                + "SELECT '" + organization + "', '" + subject + "', id, " + active + ", "
                + "(SELECT id FROM public.user_identity WHERE subject = 'bootstrap-" + shortOrg + "') "
                + "FROM public.patient_record WHERE mrn = 'MRN-1'");
    }

    private static void seedForeignConversation(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_B + "'");
        statement.execute("SET app.actor = 'bootstrap-2222'");
        statement.execute("INSERT INTO public.conversation "
                + "(organization_id, id, patient_id, patient_subject, unit_key, connection_id, state) "
                + "VALUES ('" + ORG_B + "', '" + FOREIGN_ID + "', "
                + "(SELECT id FROM public.patient_record WHERE mrn = 'MRN-1'), "
                + "'" + PATIENT + "', 'lab', 'foreign-conn', 'awaiting_staff')");
    }

    private String open(String organization, String subject, String patientSubject, String unit) {
        String patientId = patientId(organization);
        String connectionId = seedConnection(organization, patientSubject, patientId, unit);
        String body = "{\"patientId\":\"" + patientId + "\",\"patientSubject\":\"" + patientSubject
                + "\",\"unitKey\":\"" + unit + "\",\"connectionId\":\"" + connectionId
                + "\",\"topic\":\"follow up\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/conversations", body, subject, organization);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return text(response, "id");
    }

    private String seedConnection(String organization, String patientSubject, String patientId, String unit) {
        UnitKey key = UnitKey.from(unit);
        String unitId = UUID.nameUUIDFromBytes(
                ("conversation-unit:" + key.wire()).getBytes(StandardCharsets.UTF_8)).toString();
        String connectionId = UUID.nameUUIDFromBytes(
                ("conversation-connection:" + organization + ":" + unit).getBytes(StandardCharsets.UTF_8)).toString();
        messagingStore.addMember(organization, patientSubject, "patient", true);
        messagingStore.linkPatient(organization, patientSubject, patientId);
        messagingStore.addUnit(new CareUnit(unitId, organization, "facility-" + unit, "Synthetic " + unit,
                List.of("messages"), true, unit));
        messagingStore.insertConnection(organization, new PatientUnitConnection(connectionId, organization,
                patientId, unitId, ConnectionContext.ORDER, "order-ref-" + unit, "rel-" + unit,
                ConnectionState.ACTIVE, patientSubject, Instant.now(), Instant.now(), null));
        return connectionId;
    }

    private static String patientId(String organization) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            var result = statement.executeQuery("SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'");
            assertTrue(result.next(), "the synthetic patient must be seeded");
            return result.getString(1);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        assertNotNull(response.getBody(), "a conversation response must carry a JSON body");
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse conversation response", exception);
        }
    }

    private static String text(ResponseEntity<String> response, String field) {
        return tree(response).get(field).asString();
    }

    private static JsonNode inboxEntry(ResponseEntity<String> inbox, String id) {
        for (JsonNode entry : tree(inbox).get("entries")) {
            if (id.equals(entry.get("conversation").get("id").asString())) {
                return entry;
            }
        }
        throw new AssertionError("conversation " + id + " is missing from inbox " + inbox.getBody());
    }

    private static boolean hasInboxEntry(ResponseEntity<String> inbox, String id) {
        for (JsonNode entry : tree(inbox).get("entries")) {
            if (id.equals(entry.get("conversation").get("id").asString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean queueContains(ResponseEntity<String> queue, String id) {
        for (JsonNode entry : tree(queue).get("entries")) {
            if (id.equals(entry.get("id").asString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keep this test on the deterministic directory it was written for. The
     * production bean is
     * {@link com.aurevia.messaging.units.JdbcMessagingStore}; a
     * primary test bean overrides it here.
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
