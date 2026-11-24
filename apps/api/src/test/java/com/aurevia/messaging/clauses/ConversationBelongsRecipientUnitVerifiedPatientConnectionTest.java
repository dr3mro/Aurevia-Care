package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.messaging.units.JdbcMessagingStore;
import com.aurevia.messaging.units.MessagingStore;

/**
 * ARC-07 clause 1 acceptance: one conversation belongs to one recipient unit and
 * one verified patient connection (real PostgreSQL, synthetic fixtures only).
 *
 * <p>The rule is exercised through the normal HTTP entry points
 * {@code POST /api/v1/conversations} and
 * {@code POST /api/v1/conversations/{id}/reply}. Unlike the neighbouring
 * conversation HTTP tests, this fixture deliberately keeps the production
 * {@link com.aurevia.messaging.units.JdbcMessagingStore}: care units,
 * verified chart links and patient-unit connections are resolved from tenant-owned
 * tables under forced row-level security, so a cross-tenant or revoked connection
 * cannot be smuggled in through a test double. Each command that must be refused
 * is checked for its exact status and for an unchanged set of durable rows.</p>
 *
 * <p>Every identifier and name is synthetic; no patient data or credential is
 * used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_arc07_c01_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ConversationBelongsRecipientUnitVerifiedPatientConnectionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_arc07_c01_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String BOOTSTRAP = "arc07-bootstrap";
    private static final String PATIENT_ONE = "arc07-patient-one";
    private static final String PATIENT_TWO = "arc07-patient-two";
    private static final String CHART_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String CHART_TWO = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String CHART_B = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String LAB_UNIT_A = "cccccccc-0000-0000-0000-000000000001";
    private static final String RADIOLOGY_UNIT_A = "cccccccc-0000-0000-0000-000000000002";
    private static final String LAB_UNIT_B = "dddddddd-0000-0000-0000-000000000001";

    // A distinct lab connection per command keeps revocation and other writes
    // local to one test: JUnit does not guarantee method order and the V33
    // append-only triggers do not allow cleanup between tests.
    private static final String CONNECTION_OPEN = "eeeeeeee-0000-0000-0000-000000000001";
    private static final String CONNECTION_WRONG_UNIT = "eeeeeeee-0000-0000-0000-000000000002";
    private static final String CONNECTION_REVOKE = "eeeeeeee-0000-0000-0000-000000000003";
    private static final String CONNECTION_STALE = "eeeeeeee-0000-0000-0000-000000000004";
    private static final String CONNECTION_OTHER_PATIENT = "eeeeeeee-0000-0000-0000-000000000005";
    private static final String CONNECTION_FOREIGN = "ffffffff-0000-0000-0000-000000000001";

    @Autowired
    private MessagingStore messagingStore;

    private RestClient rest;

    /** Create the HTTP client and lock the real-store premise for every test. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        assertTrue(messagingStore instanceof JdbcMessagingStore,
                "this clause test must resolve connections through the production JDBC store");
    }

    /** Recreate the database, apply Flyway and seed two isolated synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_arc07_c01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_arc07_c01_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "ARC07-A", CHART_A);
            seedTenant(statement, ORG_B, "ARC07-B", CHART_B);
            // A second verified patient in ORG_A owns a different chart and
            // connection, isolating where the stored patient identity comes from.
            seedChart(statement, ORG_A, CHART_TWO, "ARC07-A2");
            seedMember(statement, ORG_A, PATIENT_TWO, "patient");
            seedVerifiedChart(statement, ORG_A, PATIENT_TWO, CHART_TWO);
            seedPatientLink(statement, ORG_A, PATIENT_TWO, CHART_TWO);
            seedCareUnit(statement, ORG_A, RADIOLOGY_UNIT_A, "radiology");
            seedConnection(statement, ORG_A, CONNECTION_OTHER_PATIENT, CHART_TWO, LAB_UNIT_A,
                    relationshipId(CONNECTION_OTHER_PATIENT), "order-other-patient");

            seedConnection(statement, ORG_A, CONNECTION_OPEN, CHART_A, LAB_UNIT_A,
                    relationshipId(CONNECTION_OPEN), "order-open");
            seedConnection(statement, ORG_A, CONNECTION_WRONG_UNIT, CHART_A, LAB_UNIT_A,
                    relationshipId(CONNECTION_WRONG_UNIT), "order-wrong-unit");
            seedConnection(statement, ORG_A, CONNECTION_REVOKE, CHART_A, LAB_UNIT_A,
                    relationshipId(CONNECTION_REVOKE), "order-revoke");
            seedConnection(statement, ORG_A, CONNECTION_STALE, CHART_A, LAB_UNIT_A,
                    relationshipId(CONNECTION_STALE), "order-stale");
            seedConnection(statement, ORG_B, CONNECTION_FOREIGN, CHART_B, LAB_UNIT_B,
                    relationshipId(CONNECTION_FOREIGN), "order-foreign");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_arc07_c01_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, String mrn, String chart)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + BOOTSTRAP + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + BOOTSTRAP + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, "
                + "role_key, granted_by) SELECT '" + org + "', m.id, 'tenant_admin', u.id "
                + "FROM public.membership m JOIN public.user_identity u "
                + "ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + BOOTSTRAP + "' AND u.organization_id = '" + org + "'");
        seedChart(statement, org, chart, mrn);
        seedMember(statement, org, PATIENT_ONE, "patient");
        seedVerifiedChart(statement, org, PATIENT_ONE, chart);
        seedPatientLink(statement, org, PATIENT_ONE, chart);
        seedCareUnit(statement, org, LAB_UNIT_A, "lab");
    }

    private static void seedChart(Statement statement, String org, String chart, String mrn)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + chart + "', 1, '" + mrn + "', 'ARC07-ISSUER', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, "
                + "role_key, granted_by) SELECT '" + org + "', m.id, '" + role + "', u.id "
                + "FROM public.membership m JOIN public.user_identity u "
                + "ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    private static void seedVerifiedChart(Statement statement, String org, String subject, String chart)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', (SELECT id FROM public.patient_account "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "'), "
                + "'" + chart + "', 'linked', 'synthetic')");
    }

    private static void seedPatientLink(Statement statement, String org, String subject, String chart)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.patient_link "
                + "(organization_id, subject, patient_id, active, created_by) "
                + "VALUES ('" + org + "', '" + subject + "', '" + chart + "', true, "
                + "(SELECT id FROM public.user_identity WHERE organization_id = '" + org
                + "' AND subject = '" + BOOTSTRAP + "'))");
    }

    private static void seedCareUnit(Statement statement, String org, String unit, String unitKey)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + org + "', '" + unit + "', '" + CHART_A + "', "
                + "'Synthetic " + unitKey + "', ARRAY['messages'], true, '" + unitKey + "')");
    }

    private static void seedConnection(Statement statement, String org, String connection,
                                       String chart, String unit, String relationship,
                                       String reference) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.eligible_relationship "
                + "(organization_id, id, patient_id, unit_id, context, reference, active) "
                + "VALUES ('" + org + "', '" + relationship + "', '" + chart + "', '" + unit
                + "', 'order', '" + reference + "', true)");
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, id, patient_id, unit_id, context, context_reference, "
                + "relationship_id, state, verified_by, verified_at, created_at) "
                + "VALUES ('" + org + "', '" + connection + "', '" + chart + "', '" + unit
                + "', 'order', '" + reference + "', '" + relationship
                + "', 'active', '" + BOOTSTRAP + "', now(), now())");
    }

    private static String relationshipId(String connection) {
        return UUID.nameUUIDFromBytes(("arc07-relationship:" + connection)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private HttpHeaders headers(String subject, String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", "arc07-c01-corr");
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body,
                                        String subject, String organization) {
        return send(method, uri, body, subject, organization, null);
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body,
                                        String subject, String organization, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, ifMatch)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> open(String connection, String unitKey) {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/conversations",
                openRequest(connection, unitKey), PATIENT_ONE, ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return response;
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

    /** Count durable rows for one tenant, reading under forced row-level security. */
    private static int count(String organization, String table, String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (var result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** Read one scalar from the active tenant under forced row-level security. */
    private static String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (var result = statement.executeQuery(sql)) {
                result.next();
                return result.getString(1);
            }
        }
    }

    /** Conversation, message and work-event row totals under the active tenant. */
    private static int[] rowCounts() throws Exception {
        return new int[] {
                count(ORG_A, "conversation", "1 = 1"),
                count(ORG_A, "conversation_message", "1 = 1"),
                count(ORG_A, "work_item_event", "1 = 1")
        };
    }

    @Test
    void conversationIsBoundToOneRecipientUnitAndOneVerifiedConnection() throws Exception {
        ResponseEntity<String> response = open(CONNECTION_OPEN, "lab");
        String id = field(response.getBody(), "id");

        // The HTTP response and the durable row both carry the connection's
        // authoritative identities, not a client-supplied guess.
        assertEquals("lab", field(response.getBody(), "unitKey"));
        assertEquals(CONNECTION_OPEN, field(response.getBody(), "connectionId"));
        assertEquals(CHART_A, field(response.getBody(), "patientId"));
        assertEquals("awaiting_staff", field(response.getBody(), "state"));
        String row = "SELECT %s FROM public.conversation WHERE id = CAST('" + id + "' AS uuid)";
        assertEquals("lab", scalar(String.format(row, "unit_key")));
        assertEquals(CONNECTION_OPEN, scalar(String.format(row, "connection_id")));
        assertEquals(CHART_A, scalar(String.format(row, "patient_id::text")));
        assertEquals("awaiting_staff", scalar(String.format(row, "state")));
        assertEquals("1", scalar(String.format(row, "version::text")));
        assertEquals("lab", scalar("SELECT unit_key FROM public.care_unit WHERE id = CAST('"
                + LAB_UNIT_A + "' AS uuid)"));
        assertEquals(CHART_A, scalar("SELECT patient_id::text FROM public.patient_unit_connection "
                + "WHERE id = CAST('" + CONNECTION_OPEN + "' AS uuid)"));
    }

    @Test
    void conversationRefusesAWrongRecipientUnitForItsConnection() throws Exception {
        int[] before = rowCounts();
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations",
                openRequest(CONNECTION_WRONG_UNIT, "radiology"), PATIENT_ONE, ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
        assertArrayEquals(before, rowCounts());
        assertEquals(0, count(ORG_A, "conversation", "connection_id = '" + CONNECTION_WRONG_UNIT + "'"));
    }

    @Test
    void conversationRefusesAnotherTenantsUnitOrConnection() throws Exception {
        int[] before = rowCounts();
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations",
                openRequest(CONNECTION_FOREIGN, "lab"), PATIENT_ONE, ORG_A);

        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertEquals("NOT_FOUND", field(denied.getBody(), "code"));
        assertArrayEquals(before, rowCounts());
        assertEquals(0, count(ORG_A, "conversation", "connection_id = '" + CONNECTION_FOREIGN + "'"));
        assertEquals(1, count(ORG_B, "patient_unit_connection",
                "id = CAST('" + CONNECTION_FOREIGN + "' AS uuid)"));
        assertEquals(0, count(ORG_A, "patient_unit_connection",
                "id = CAST('" + CONNECTION_FOREIGN + "' AS uuid)"));
    }

    @Test
    void revokedConnectionCannotReplyToTheConversation() throws Exception {
        String id = field(open(CONNECTION_REVOKE, "lab").getBody(), "id");
        ResponseEntity<String> revoked = send(HttpMethod.POST,
                "/api/v1/patient-unit-connections/" + CONNECTION_REVOKE + "/revoke",
                null, PATIENT_ONE, ORG_A);
        assertEquals(HttpStatus.OK, revoked.getStatusCode(), revoked.getBody());
        assertEquals("revoked", field(revoked.getBody(), "state"));

        int[] before = rowCounts();
        ResponseEntity<String> reply = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"please bring the results\"}", PATIENT_ONE, ORG_A, "1");
        assertEquals(HttpStatus.FORBIDDEN, reply.getStatusCode(), reply.getBody());
        assertEquals("FORBIDDEN", field(reply.getBody(), "code"));
        assertArrayEquals(before, rowCounts());
    }

    @Test
    void replyRefusesStaleVersion() throws Exception {
        String id = field(open(CONNECTION_STALE, "lab").getBody(), "id");
        int[] before = rowCounts();
        ResponseEntity<String> reply = send(HttpMethod.POST,
                "/api/v1/conversations/" + id + "/reply",
                "{\"body\":\"please bring the results\"}", PATIENT_ONE, ORG_A, "99");

        assertEquals(HttpStatus.PRECONDITION_FAILED, reply.getStatusCode(), reply.getBody());
        assertEquals("STALE_VERSION", field(reply.getBody(), "code"));
        assertArrayEquals(before, rowCounts());
    }

    @Test
    void conversationRefusesAnotherPatientsConnectionForItsOwnChart() throws Exception {
        int[] before = rowCounts();
        String body = "{\"patientId\":\"" + CHART_A + "\",\"patientSubject\":\"" + PATIENT_ONE
                + "\",\"unitKey\":\"lab\",\"connectionId\":\"" + CONNECTION_OTHER_PATIENT
                + "\",\"topic\":\"results\"}";
        ResponseEntity<String> denied = send(HttpMethod.POST, "/api/v1/conversations", body,
                PATIENT_ONE, ORG_A);

        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertEquals("NOT_FOUND", field(denied.getBody(), "code"));
        assertArrayEquals(before, rowCounts());
    }

    private static String openRequest(String connection, String unitKey) {
        return "{\"patientId\":\"" + CHART_A + "\",\"patientSubject\":\"" + PATIENT_ONE
                + "\",\"unitKey\":\"" + unitKey + "\",\"connectionId\":\"" + connection
                + "\",\"topic\":\"results\"}";
    }
}
