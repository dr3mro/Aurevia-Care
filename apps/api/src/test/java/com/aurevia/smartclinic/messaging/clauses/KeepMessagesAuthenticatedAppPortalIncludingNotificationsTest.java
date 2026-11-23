package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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

import com.aurevia.smartclinic.messaging.appointments.GenericPushNotice;
import com.aurevia.smartclinic.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.smartclinic.messaging.units.InMemoryMessagingStore;
import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.smartclinic.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.smartclinic.messaging.units.MessagingContract.PatientUnitConnection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-07 clause 7 API evidence: a message posted through the authenticated
 * conversation route stays stored and readable in the thread and the patient
 * inbox, and the authenticated boundary refuses a wrong unit, a peer tenant, a
 * stale version, blank text and a closed thread.
 *
 * <p>The "including when notifications fail" half is not wired into the
 * conversation flow: no notification port exists on
 * {@link com.aurevia.smartclinic.messaging.conversations.ConversationService}.
 * The complete test for that half is preserved in the clause document's Gap
 * section; this class deliberately contains no skipped test.</p>
 *
 * <p>Two synthetic organizations share the local patient MRN, display name and
 * care-unit key while their subjects, patient identifiers and connection
 * identifiers are tenant-scoped, so forced row level security is observable.
 * All fixtures are synthetic; no patient data or credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_keep_messages_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class KeepMessagesAuthenticatedAppPortalIncludingNotificationsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DATABASE = "smartclinic_keep_messages_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String PATIENT_A = "patient-1111";
    private static final String PATIENT_B = "patient-2222";
    private static final String LAB_STAFF_A = "lab-a-1111";
    private static final String MESSAGE_BODY = "synthetic reply about follow-up results";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Synthetic messaging directory shared with the Spring context under test. */
    @Autowired
    private InMemoryMessagingStore messagingStore;

    /** Recreate the dedicated database, apply Flyway and seed two isolated tenants. */
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
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B}) {
                String s = org.substring(0, 4);
                seedMember(statement, org, "patient-" + s, "patient");
                seedMember(statement, org, "lab-a-" + s, "lab_verifier");
                seedLink(statement, org, "patient-" + s, true);
            }
        }
    }

    /** Drop the dedicated synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
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

    /**
     * Create the HTTP client against the random server port and seed both
     * tenants' synthetic memberships in the deterministic messaging directory.
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
        }
    }

    /**
     * Seed a verified connection and its tenant care unit, then open a thread.
     *
     * @param org     owning synthetic organization
     * @param subject patient subject that opens the thread
     * @param unit    recipient care-unit key
     * @return the created conversation id
     */
    private String open(String org, String subject, String unit) {
        String patient = patientId(org);
        String connectionId = seedConnection(org, subject, patient, unit);
        String body = "{\"patientId\":\"" + patient + "\",\"patientSubject\":\"" + subject
                + "\",\"unitKey\":\"" + unit + "\",\"connectionId\":\"" + connectionId
                + "\",\"topic\":\"follow up\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/conversations", body, subject, org);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return tree(response).get("id").asString();
    }

    /**
     * Seed a verified patient-unit connection with deterministic ids so repeated
     * calls in one Spring context stay idempotent.
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

    /**
     * Read the synthetic patient chart id for one tenant under tenant context.
     *
     * @param org owning synthetic organization
     * @return the patient identifier
     */
    private String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            var result = statement.executeQuery("SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'");
            assertTrue(result.next());
            return result.getString(1);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private HttpHeaders headers(String subject, String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body,
                                         String subject, String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body,
                                         String subject, String organization, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, extra)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse conversation response", exception);
        }
    }

    /**
     * Positive proof: a staff reply is committed and remains readable in both
     * the authenticated thread view and the patient inbox on a later request.
     */
    @Test
    void aReplyRemainsReadableInInboxAndThread() {
        String id = open(ORG_A, PATIENT_A, "lab");
        ResponseEntity<String> reply = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"" + MESSAGE_BODY + "\"}", LAB_STAFF_A, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, reply.getStatusCode(), reply.getBody());

        JsonNode thread = tree(send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, PATIENT_A, ORG_A));
        assertEquals(MESSAGE_BODY, thread.get("messages").get(0).get("body").asString());
        assertEquals("staff", thread.get("messages").get(0).get("senderKind").asString());

        JsonNode inbox = tree(send(HttpMethod.GET, "/api/v1/conversations/inbox",
                null, PATIENT_A, ORG_A));
        assertTrue(inbox.get("entries").toString().contains(id));
        assertEquals(1, inbox.get("entries").get(0).get("unreadCount").asInt());

        JsonNode reread = tree(send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, PATIENT_A, ORG_A));
        assertEquals(MESSAGE_BODY, reread.get("messages").get(0).get("body").asString());
    }

    /** Negative proof: a staff member outside the conversation's unit is denied. */
    @Test
    void aStaffMemberOutsideTheUnitCannotReadTheMessage() {
        String id = open(ORG_A, PATIENT_A, "clinic");
        ResponseEntity<String> denied = send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, LAB_STAFF_A, ORG_A);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
    }

    /** Negative proof: a peer tenant sharing only the MRN and unit key cannot read the thread. */
    @Test
    void aPeerTenantCannotReadTheMessage() {
        String id = open(ORG_A, PATIENT_A, "lab");
        ResponseEntity<String> denied = send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, PATIENT_B, ORG_B);
        assertEquals(404, denied.getStatusCode().value(), denied.getBody());
    }

    /** Negative proof: a reply carrying a stale version precondition is refused. */
    @Test
    void aStaleIfMatchCannotPostAReply() {
        String id = open(ORG_A, PATIENT_A, "lab");
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"" + MESSAGE_BODY + "\"}", PATIENT_A, ORG_A, Map.of("If-Match", "99"));
        assertEquals(412, denied.getStatusCode().value(), denied.getBody());
        assertEquals("STALE_VERSION", tree(denied).get("code").asString());
    }

    /** Negative proof: blank reply text is refused before any message is stored. */
    @Test
    void aBlankReplyBodyIsRefused() {
        String id = open(ORG_A, PATIENT_A, "lab");
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"   \"}", PATIENT_A, ORG_A, Map.of("If-Match", "1"));
        assertEquals(422, denied.getStatusCode().value(), denied.getBody());
        assertEquals("INVALID_BODY", tree(denied).get("code").asString());
    }

    /** Negative proof: a closed thread stays readable but refuses a new reply. */
    @Test
    void aReplyOnAClosedThreadIsRefused() {
        String id = open(ORG_A, PATIENT_A, "lab");
        ResponseEntity<String> closed = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/close",
                "{\"reason\":\"done\"}", LAB_STAFF_A, ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, closed.getStatusCode(), closed.getBody());

        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"" + MESSAGE_BODY + "\"}", PATIENT_A, ORG_A, Map.of("If-Match", "2"));
        assertEquals(409, denied.getStatusCode().value(), denied.getBody());
        assertEquals("CLOSED_THREAD", tree(denied).get("code").asString());
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, "/api/v1/conversations/" + id,
                null, PATIENT_A, ORG_A).getStatusCode());
    }

    /**
     * Cross-check for the "in the authenticated app" boundary: the only push
     * payload available carries fixed generic text and reveals no message body,
     * while the positive test above proves the body is retrievable in the app.
     */
    @Test
    void thePushNoticeCarriesNoMessageContent() {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        assertEquals(GenericPushNotice.GENERIC_TITLE, notice.title());
        assertEquals(GenericPushNotice.GENERIC_BODY, notice.body());
        assertEquals(GenericPushNotice.GENERIC_LINK, notice.deepLink());
        assertFalse(notice.reveals(MESSAGE_BODY));
        assertFalse(notice.reveals(PATIENT_A));
    }

    /**
     * Keep this clause proof on the deterministic directory it was written for.
     * The production bean is
     * {@link com.aurevia.smartclinic.messaging.units.JdbcMessagingStore}; a primary
     * test bean overrides it here.
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
