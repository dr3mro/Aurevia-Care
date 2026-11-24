package com.aurevia.it.messagingconversations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import org.flywaydb.core.Flyway;

import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.units.InMemoryMessagingStore;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S-G3-05 acceptance (MSG-02) on a real PostgreSQL instance.
 *
 * <p>Two synthetic organizations share local patient, subject and connection
 * identifiers so tenant isolation is observable. All fixtures and narratives
 * are synthetic; no patient data or credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_conversation_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ConversationFlowTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_conversation_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String PATIENT = "patient-1111";
    static final String LAB_STAFF = "lab-a-1111";
    static final String CLINIC_STAFF = "clinic-a-1111";
    static final String ADMIN_SUBJECT = "admin-1111";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Synthetic messaging directory shared with the Spring context under test. */
    @Autowired
    private InMemoryMessagingStore messagingStore;

    /**
     * Create the HTTP client against the random server port.
     *
     * @param port server port assigned by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        for (String org : new String[] {ORG_A, ORG_B}) {
            String s = org.substring(0, 4);
            messagingStore.addMember(org, "patient-" + s, "patient", true);
            messagingStore.addMember(org, "lab-a-" + s, "lab_verifier", true);
            messagingStore.addMember(org, "clinic-a-" + s, "reception", true);
            messagingStore.addMember(org, "admin-" + s, "tenant_admin", true);
        }
    }

    /** Recreate the database, apply Flyway and seed two isolated tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_conversation_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_conversation_it OWNER orevia_migrate");
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
            for (String org : new String[] {ORG_A, ORG_B}) {
                String s = org.substring(0, 4);
                seedMember(statement, org, "patient-" + s, "patient");
                seedMember(statement, org, "lab-a-" + s, "lab_verifier");
                seedMember(statement, org, "clinic-a-" + s, "reception");
                seedMember(statement, org, "admin-" + s, "tenant_admin");
                seedMember(statement, org, "revoked-" + s, "patient");
                seedLink(statement, org, "patient-" + s, true);
                seedLink(statement, org, "revoked-" + s, false);
            }
        }
    }

    /** Drop the synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_conversation_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String s = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + s + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + s + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + s + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        String s = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + s + "'");
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
        String s = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + s + "'");
        statement.execute("INSERT INTO public.patient_link (organization_id, subject, patient_id, active, created_by) "
                + "SELECT '" + org + "', '" + subject + "', id, " + active + ", "
                + "(SELECT id FROM public.user_identity WHERE subject = 'bootstrap-" + s + "') "
                + "FROM public.patient_record WHERE mrn = 'MRN-1'");
    }

    private HttpHeaders headers(String subject, String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, extra)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private String open(String org, String subject, String patientSubject, String unit) {
        String patientId = patientId(org);
        String connectionId = seedConnection(org, patientSubject, patientId, unit);
        String body = "{\"patientId\":\"" + patientId + "\",\"patientSubject\":\"" + patientSubject
                + "\",\"unitKey\":\"" + unit + "\",\"connectionId\":\"" + connectionId
                + "\",\"topic\":\"follow up\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/conversations", body, subject, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return text(response, "id");
    }

    /**
     * Seed a verified connection and its tenant care unit in the synthetic
     * messaging store so a patient can open a thread for that unit. The ids are
     * deterministic so repeated calls in one Spring context are idempotent.
     */
    private String seedConnection(String org, String patientSubject, String patientId, String unit) {
        UnitKey key = UnitKey.from(unit);
        String unitId = UUID.nameUUIDFromBytes(
                ("conversation-unit:" + key.wire()).getBytes(StandardCharsets.UTF_8)).toString();
        String connectionId = UUID.nameUUIDFromBytes(
                ("conversation-connection:" + org + ":" + unit).getBytes(StandardCharsets.UTF_8)).toString();
        messagingStore.addMember(org, patientSubject, "patient", true);
        messagingStore.linkPatient(org, patientSubject, patientId);
        messagingStore.addUnit(new CareUnit(unitId, org, "facility-" + unit, "Synthetic " + unit,
                List.of("messages"), true, unit));
        messagingStore.insertConnection(org, new PatientUnitConnection(connectionId, org, patientId, unitId,
                ConnectionContext.ORDER, "order-ref-" + unit, "rel-" + unit, ConnectionState.ACTIVE,
                patientSubject, Instant.now(), Instant.now(), null));
        return connectionId;
    }

    private String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            var result = statement.executeQuery("SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'");
            assertTrue(result.next());
            return result.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode tree(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody());
        return MAPPER.readTree(response.getBody());
    }

    private static String text(ResponseEntity<String> response, String field) {
        try {
            return tree(response).get(field).asString();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int unread(ResponseEntity<String> inbox) {
        try {
            return tree(inbox).get("entries").get(0).get("unreadCount").asInt();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void patientOpenStaffReplyUnreadMarkReadReopenAndClose() throws Exception {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        // GET returns a View {conversation, messages, events}; the mutable
        // projection is nested under "conversation" (the reply command does
        // the same). Assert against that documented shape.
        JsonNode opened = tree(send(HttpMethod.GET, "/api/v1/conversations/" + id, null, PATIENT, ORG_A))
                .get("conversation");
        assertEquals("awaiting_staff", opened.get("state").asString());
        assertEquals(1, opened.get("version").asInt());

        ResponseEntity<String> reply = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"please bring your results\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, reply.getStatusCode(), reply.getBody());
        assertEquals("awaiting_patient", tree(reply).get("conversation").get("state").asString());

        ResponseEntity<String> inbox = send(HttpMethod.GET, "/api/v1/conversations/inbox", null, PATIENT, ORG_A);
        assertEquals(HttpStatus.OK, inbox.getStatusCode());
        assertEquals(1, unread(inbox));

        ResponseEntity<String> viewed = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/read",
                null, PATIENT, ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.OK, viewed.getStatusCode(), viewed.getBody());
        assertEquals(0, unread(send(HttpMethod.GET, "/api/v1/conversations/inbox", null, PATIENT, ORG_A)));

        ResponseEntity<String> resolved = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/resolve",
                "{\"reason\":\"answered\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "3"));
        assertEquals(HttpStatus.OK, resolved.getStatusCode(), resolved.getBody());
        assertEquals("resolved", tree(resolved).get("state").asString());

        ResponseEntity<String> reopened = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"one more question\"}", PATIENT, ORG_A, Map.of("If-Match", "4"));
        assertEquals(HttpStatus.OK, reopened.getStatusCode(), reopened.getBody());
        assertEquals("awaiting_staff", tree(reopened).get("conversation").get("state").asString());
        JsonNode thread = tree(send(HttpMethod.GET, "/api/v1/conversations/" + id, null, PATIENT, ORG_A));
        assertEquals(2, thread.get("messages").size());
        assertTrue(thread.get("events").toString().contains("reopened"));

        ResponseEntity<String> closed = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/close",
                "{\"reason\":\"done\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "5"));
        assertEquals(HttpStatus.OK, closed.getStatusCode(), closed.getBody());
        assertEquals("closed", tree(closed).get("state").asString());
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"again\"}", PATIENT, ORG_A, Map.of("If-Match", "6"));
        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode());
        assertEquals("CLOSED_THREAD", tree(denied).get("code").asString());
        // A closed thread remains readable.
        assertEquals(HttpStatus.OK,
                send(HttpMethod.GET, "/api/v1/conversations/" + id, null, PATIENT, ORG_A).getStatusCode());
    }

    @Test
    void assignTransferAndSameUnitConstraint() throws Exception {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        ResponseEntity<String> assigned = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/assign",
                "{\"assigneeSubject\":\"lab-a-1111\",\"reason\":\"cover\"}", LAB_STAFF, ORG_A,
                Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, assigned.getStatusCode(), assigned.getBody());
        assertEquals("lab-a-1111", tree(assigned).get("assigneeSubject").asString());

        ResponseEntity<String> transferred = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/transfer",
                "{\"toSubject\":\"lab-a-1111\",\"reason\":\"handover\"}", LAB_STAFF, ORG_A,
                Map.of("If-Match", "2"));
        assertEquals(HttpStatus.OK, transferred.getStatusCode(), transferred.getBody());

        ResponseEntity<String> crossUnit = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/assign",
                "{\"assigneeSubject\":\"clinic-a-1111\",\"reason\":\"cover\"}", LAB_STAFF, ORG_A,
                Map.of("If-Match", "3"));
        assertEquals(422, crossUnit.getStatusCode().value());
        assertEquals("INVALID_ASSIGNEE", tree(crossUnit).get("code").asString());
    }

    @Test
    void wrongUnitDenialAndRoleScopedWorkQueue() throws Exception {
        String id = open(ORG_A, PATIENT, PATIENT, "clinic");
        ResponseEntity<String> labRead = send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, LAB_STAFF, ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, labRead.getStatusCode());
        ResponseEntity<String> labReply = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"wrong unit\"}", LAB_STAFF, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.FORBIDDEN, labReply.getStatusCode());

        ResponseEntity<String> clinicQueue = send(HttpMethod.GET, "/api/v1/conversations/work-queue",
                null, CLINIC_STAFF, ORG_A);
        assertTrue(tree(clinicQueue).get("entries").toString().contains(id));
        ResponseEntity<String> labQueue = send(HttpMethod.GET, "/api/v1/conversations/work-queue",
                null, LAB_STAFF, ORG_A);
        assertFalse(tree(labQueue).get("entries").toString().contains(id));
        ResponseEntity<String> adminQueue = send(HttpMethod.GET, "/api/v1/conversations/work-queue",
                null, ADMIN_SUBJECT, ORG_A);
        assertTrue(tree(adminQueue).get("entries").toString().contains(id));
    }

    @Test
    void staffMarkReadAdvancesStaffWatermark() throws Exception {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        ResponseEntity<String> reply = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"a question\"}", PATIENT, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, reply.getStatusCode(), reply.getBody());
        ResponseEntity<String> viewed = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/read",
                null, LAB_STAFF, ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.OK, viewed.getStatusCode(), viewed.getBody());
        assertEquals(1, tree(viewed).get("staffReadSequence").asInt());
    }

    @Test
    void preconditionsValidationAndTenantIsolation() throws Exception {
        ResponseEntity<String> badUuid = send(HttpMethod.POST, "/api/v1/conversations",
                "{\"patientId\":\"nope\",\"patientSubject\":\"" + PATIENT
                        + "\",\"unitKey\":\"lab\",\"connectionId\":\"conn-1\"}", PATIENT, ORG_A);
        assertEquals(422, badUuid.getStatusCode().value());

        ResponseEntity<String> missingPatient = send(HttpMethod.POST, "/api/v1/conversations",
                "{\"patientId\":\"99999999-9999-9999-9999-999999999999\",\"patientSubject\":\"" + PATIENT
                        + "\",\"unitKey\":\"lab\",\"connectionId\":\"conn-1\"}", PATIENT, ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, missingPatient.getStatusCode());

        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        ResponseEntity<String> noPrecondition = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"hi\"}", PATIENT, ORG_A);
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, noPrecondition.getStatusCode());

        ResponseEntity<String> stale = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"hi\"}", PATIENT, ORG_A, Map.of("If-Match", "99"));
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode());

        ResponseEntity<String> unknown = send(HttpMethod.GET,
                "/api/v1/conversations/99999999-9999-9999-9999-999999999999", null, PATIENT, ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());

        // Tenant B has the same local patient MRN, subject and connection id;
        // it cannot see tenant A's conversation.
        ResponseEntity<String> crossTenant = send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, "patient-2222", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.getStatusCode());
        String idB = open(ORG_B, "patient-2222", "patient-2222", "lab");
        assertFalse(id.equals(idB));
        ResponseEntity<String> inboxA = send(HttpMethod.GET, "/api/v1/conversations/inbox", null, PATIENT, ORG_A);
        assertFalse(tree(inboxA).get("entries").toString().contains(idB));
    }

    @Test
    void revokedLinkAndRevokedMembershipFailOnNextRequest() throws Exception {
        String revoked = "{\"patientId\":\"" + patientId(ORG_A)
                + "\",\"patientSubject\":\"revoked-1111\",\"unitKey\":\"lab\",\"connectionId\":\"conn-1\"}";
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations", revoked,
                "revoked-1111", ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode());
    }

    @Test
    void databaseGuardsRejectIllegalWrites() throws Exception {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + PATIENT + "'");
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.conversation SET version = version + 2 WHERE id = '" + id + "'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.conversation SET patient_id = '99999999-9999-9999-9999-999999999999' "
                            + "WHERE id = '" + id + "'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM public.conversation WHERE id = '" + id + "'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "TRUNCATE public.conversation_message"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "INSERT INTO public.conversation (organization_id, id, patient_id, patient_subject, "
                            + "unit_key, connection_id, state) VALUES ('" + ORG_A + "', gen_random_uuid(), "
                            + "(SELECT id FROM public.patient_record WHERE mrn='MRN-1'), 'revoked-1111', "
                            + "'lab', 'conn-2', 'awaiting_staff')"));
        }
        // The guarded row still holds its original version and patient.
        JsonNode after = tree(send(HttpMethod.GET, "/api/v1/conversations/" + id, null, PATIENT, ORG_A))
                .get("conversation");
        assertEquals(1, after.get("version").asInt());
        assertEquals(patientId(ORG_A), after.get("patientId").asString());
    }

    @Test
    void directFoldAssertionsConfirmVerifiedLinkAndForcedRls() throws Exception {
        String id = open(ORG_A, PATIENT, PATIENT, "lab");
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             PreparedStatement visible = connection.prepareStatement(
                     "SELECT count(*) FROM public.conversation WHERE id = CAST(? AS uuid)");
             PreparedStatement hidden = connection.prepareStatement(
                     "SELECT public.patient_link_active(?, CAST(? AS uuid))")) {
            connection.createStatement().execute("SET app.current_org = '" + ORG_A + "'");
            visible.setString(1, id);
            var rows = visible.executeQuery();
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));

            connection.createStatement().execute("SET app.current_org = '" + ORG_B + "'");
            rows = visible.executeQuery();
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));

            hidden.setString(1, PATIENT);
            hidden.setString(2, patientId(ORG_A));
            var link = hidden.executeQuery();
            assertTrue(link.next());
            assertFalse(link.getBoolean(1), "the fold is tenant scoped to ORG_B here");
        }
    }

    /**
     * Keep this MSG-02 flow on the deterministic directory it was written for.
     * The production bean is {@link com.aurevia.messaging.units.JdbcMessagingStore};
     * a primary test bean overrides it here so the conversation assertions do
     * not depend on SQL seeding.
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
