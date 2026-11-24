package com.aurevia.lab.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * LAB-01 clause 4 HTTP acceptance on real PostgreSQL: rejecting a specimen keeps
 * its reason and notification on the current row, in the read view and in the
 * append-only history, and refuses a rejection from the wrong role, another
 * tenant, a stale version, blank input, a non-positive version or a source state
 * that cannot move to rejected. Every fixture is synthetic; the peer organization
 * uses the same local codes so isolation is tested at the tenant boundary.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_reject_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RejectedSpecimensRetainReasonNotificationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    // Distinct specimens keep every test's row and ledger independent in the shared database.
    private static final String RECEIVED = "aaaaaaaa-0000-0000-0000-000000000101";
    private static final String WRONG_ROLE = "aaaaaaaa-0000-0000-0000-000000000102";
    private static final String PEER_TENANT = "aaaaaaaa-0000-0000-0000-000000000103";
    private static final String STALE = "aaaaaaaa-0000-0000-0000-000000000104";
    private static final String BLANK_REASON = "aaaaaaaa-0000-0000-0000-000000000105";
    private static final String BLANK_NOTIFICATION = "aaaaaaaa-0000-0000-0000-000000000106";
    private static final String BAD_STATE = "aaaaaaaa-0000-0000-0000-000000000107";
    private static final String BAD_VERSION = "aaaaaaaa-0000-0000-0000-000000000108";

    private static final String COLLECTOR_A = "lab-a";
    private static final String VERIFIER_A = "verifier-a";
    private static final String VERIFIER_B = "verifier-b";
    private static final String REASON = "Hemolysed sample";
    private static final String NOTIFICATION = "notify-clinic";

    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_reject_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_reject_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_reject_clause_it OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, ORG_A, COLLECTOR_A, "lab_collector");
            seedMember(statement, ORG_A, VERIFIER_A, "lab_verifier");
            // The peer must hold the same reject permission, otherwise authorization
            // would fail before the tenant guard and the 404 would prove nothing.
            seedMember(statement, ORG_B, VERIFIER_B, "lab_verifier");
            seedPatient(statement);
            seedAccession(statement, RECEIVED, "ACC-RECEIVED", "received");
            seedAccession(statement, WRONG_ROLE, "ACC-ROLE", "collected");
            seedAccession(statement, PEER_TENANT, "ACC-PEER", "collected");
            seedAccession(statement, STALE, "ACC-STALE", "collected");
            seedAccession(statement, BLANK_REASON, "ACC-BLANK-R", "collected");
            seedAccession(statement, BLANK_NOTIFICATION, "ACC-BLANK-N", "collected");
            seedAccession(statement, BAD_STATE, "ACC-STATE", "accessioned");
            seedAccession(statement, BAD_VERSION, "ACC-VERSION", "collected");
            // One prior history row proves the rejection appends to history rather
            // than replacing it. It occupies version 1, so the reject event at
            // expectedVersion + 1 = 2 cannot collide with the ledger's unique key.
            seedPriorReceivedEvent(statement);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_reject_clause_it WITH (FORCE)");
        }
    }

    private static void bind(Statement statement, String org, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        bind(statement, org, subject);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic " + org.substring(0, 4) + "' "
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

    private static void seedPatient(Statement statement) throws Exception {
        bind(statement, ORG_A, COLLECTOR_A);
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + "dob, created_by) "
                + "SELECT '" + ORG_A + "', '" + PATIENT_A + "', 1, 'MRN-REJECT-CLAUSE', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', '1990-05-20', u.id "
                + "FROM public.user_identity u WHERE u.organization_id = '" + ORG_A + "' "
                + "AND u.subject = '" + COLLECTOR_A + "'");
    }

    private static void seedAccession(Statement statement, String id, String number, String state)
            throws Exception {
        bind(statement, ORG_A, COLLECTOR_A);
        StringBuilder columns = new StringBuilder(
                "organization_id, id, version, accession_no, accession_issuer, patient_id, "
                        + "source_order_ref, state, created_at");
        StringBuilder values = new StringBuilder("'" + ORG_A + "', '" + id + "', 1, '" + number
                + "', 'LAB-A', '" + PATIENT_A + "', 'ORD-SYN', '" + state + "', now()");
        if (!"accessioned".equals(state)) {
            // The collected fields are carried forward by reject(), so a seeded
            // collected/received row must carry them to satisfy V26's CHECK.
            columns.append(", specimen_type, site_code, collector, collected_at");
            values.append(", 'PLASMA', 'SITE-A', 'Sam Collector', '2026-11-19T10:15:30Z'");
            if ("received".equals(state)) {
                columns.append(", received_at, quality");
                values.append(", '2026-11-19T11:15:30Z', 'acceptable'");
            }
        }
        statement.execute("INSERT INTO public.lab_accession (" + columns + ") VALUES (" + values + ")");
    }

    private static void seedPriorReceivedEvent(Statement statement) throws Exception {
        bind(statement, ORG_A, VERIFIER_A);
        statement.execute("INSERT INTO public.lab_accession_event "
                + "(organization_id, accession_id, accession_version, action, from_state, to_state, "
                + "reason, matched_patient_id) "
                + "VALUES ('" + ORG_A + "', '" + RECEIVED + "', 1, 'received', 'collected', "
                + "'received', NULL, '" + PATIENT_A + "')");
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String rejectBody(String reason, String notification, int version) {
        return "{\"reason\":" + quote(reason) + ",\"notification\":" + quote(notification)
                + ",\"expectedVersion\":" + version + "}";
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static JsonNode event(JsonNode events, String action) {
        for (JsonNode candidate : events) {
            if (action.equals(candidate.get("action").asText())) {
                return candidate;
            }
        }
        return null;
    }

    /** Current row snapshot, read inside the ORG_A forced-RLS scope. */
    private record RowState(int rows, String state, int version, String reason, String notification) {
    }

    /** Rejection-ledger snapshot: how many events were appended and what they carry. */
    private record LedgerState(int rejected, int received, String reason, String fromState,
                               String toState, int version) {
    }

    private static void bindScope(Connection connection, String org, String actor) throws Exception {
        try (PreparedStatement bind = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            bind.setString(1, org);
            bind.setString(2, actor);
            bind.execute();
        }
    }

    private static RowState rowState(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, ORG_A, VERIFIER_A);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT count(*), max(state), coalesce(max(version), 0),
                           max(rejection_reason), max(rejection_notification)
                      FROM public.lab_accession WHERE id = CAST(? AS uuid)
                    """)) {
                query.setString(1, id);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return new RowState(result.getInt(1), result.getString(2), result.getInt(3),
                            result.getString(4), result.getString(5));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static LedgerState ledgerState(String id) throws Exception {
        return ledgerState(id, ORG_A, VERIFIER_A);
    }

    private static LedgerState ledgerState(String id, String org, String actor) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, org, actor);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT count(*) FILTER (WHERE action = 'rejected'),
                           count(*) FILTER (WHERE action = 'received'),
                           max(reason) FILTER (WHERE action = 'rejected'),
                           max(from_state) FILTER (WHERE action = 'rejected'),
                           max(to_state) FILTER (WHERE action = 'rejected'),
                           coalesce(max(accession_version) FILTER (WHERE action = 'rejected'), 0)
                      FROM public.lab_accession_event WHERE accession_id = CAST(? AS uuid)
                    """)) {
                query.setString(1, id);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return new LedgerState(result.getInt(1), result.getInt(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getInt(6));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** Assert a refused rejection left the ORG_A row and ledger exactly as seeded. */
    private static void assertUnchanged(String id, String expectedState) throws Exception {
        RowState row = rowState(id);
        assertEquals(1, row.rows(), "seeded ORG_A row must be visible under forced RLS");
        assertEquals(expectedState, row.state());
        assertEquals(1, row.version());
        assertNull(row.reason());
        assertNull(row.notification());
        assertEquals(0, ledgerState(id).rejected(), "a refused rejection must append no rejected event");
    }

    @Test
    void rejectRetainsReasonAndNotification() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + RECEIVED + "/reject",
                rejectBody(REASON, NOTIFICATION, 1), VERIFIER_A, ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode rejected = json(response);
        assertEquals("rejected", rejected.get("state").asText());
        assertEquals(REASON, rejected.get("rejectionReason").asText());
        assertEquals(NOTIFICATION, rejected.get("rejectionNotification").asText());
        assertEquals(2, rejected.get("version").asInt());

        JsonNode view = json(get("/api/v1/lab/accessions/" + RECEIVED, VERIFIER_A, ORG_A));
        JsonNode accession = view.get("accession");
        assertEquals("rejected", accession.get("state").asText());
        assertEquals(REASON, accession.get("rejectionReason").asText());
        assertEquals(NOTIFICATION, accession.get("rejectionNotification").asText());
        JsonNode events = view.get("events");
        assertNotNull(event(events, "received"), "the prior history entry must survive the rejection");
        JsonNode rejectedEvent = event(events, "rejected");
        assertNotNull(rejectedEvent);
        assertEquals(REASON, rejectedEvent.get("reason").asText());
        assertEquals("received", rejectedEvent.get("fromState").asText());
        assertEquals("rejected", rejectedEvent.get("toState").asText());
        assertEquals(2, rejectedEvent.get("accessionVersion").asInt());

        RowState row = rowState(RECEIVED);
        assertEquals(1, row.rows());
        assertEquals("rejected", row.state());
        assertEquals(2, row.version());
        assertEquals(REASON, row.reason());
        assertEquals(NOTIFICATION, row.notification());
        LedgerState ledger = ledgerState(RECEIVED);
        assertEquals(1, ledger.rejected());
        assertEquals(1, ledger.received(), "the prior event must remain in the append-only ledger");
        assertEquals(REASON, ledger.reason());
        assertEquals("received", ledger.fromState());
        assertEquals("rejected", ledger.toState());
        assertEquals(2, ledger.version());
    }

    @Test
    void labCollectorCannotRejectSpecimen() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + WRONG_ROLE + "/reject",
                rejectBody(REASON, NOTIFICATION, 1), COLLECTOR_A, ORG_A);
        assertEquals(403, response.getStatusCode().value(), response.getBody());
        assertEquals("FORBIDDEN", json(response).get("code").asText());
        // Isolate the role branch from the no-active-membership branch.
        assertEquals("missing laboratory permission", json(response).get("message").asText());
        assertUnchanged(WRONG_ROLE, "collected");
    }

    @Test
    void peerTenantCannotRejectSpecimen() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + PEER_TENANT + "/reject",
                rejectBody(REASON, NOTIFICATION, 1), VERIFIER_B, ORG_B);
        assertEquals(404, response.getStatusCode().value(), response.getBody());
        assertEquals("NOT_FOUND", json(response).get("code").asText());
        assertUnchanged(PEER_TENANT, "collected");
        assertEquals(0, ledgerState(PEER_TENANT, ORG_B, VERIFIER_B).rejected(),
                "a concealed row must not produce a rejection event in the peer tenant either");
    }

    @Test
    void staleVersionCannotRejectSpecimen() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + STALE + "/reject",
                rejectBody(REASON, NOTIFICATION, 9), VERIFIER_A, ORG_A);
        assertEquals(412, response.getStatusCode().value(), response.getBody());
        assertEquals("STALE_VERSION", json(response).get("code").asText());
        assertUnchanged(STALE, "collected");
    }

    @Test
    void blankReasonIsRejected() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + BLANK_REASON + "/reject",
                rejectBody("", NOTIFICATION, 1), VERIFIER_A, ORG_A);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertEquals("INVALID_REASON", json(response).get("code").asText());
        assertUnchanged(BLANK_REASON, "collected");
    }

    @Test
    void blankNotificationIsRejected() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + BLANK_NOTIFICATION + "/reject",
                rejectBody(REASON, "", 1), VERIFIER_A, ORG_A);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertEquals("INVALID_NOTIFICATION", json(response).get("code").asText());
        assertUnchanged(BLANK_NOTIFICATION, "collected");
    }

    @Test
    void accessionedSpecimenCannotBeRejected() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + BAD_STATE + "/reject",
                rejectBody(REASON, NOTIFICATION, 1), VERIFIER_A, ORG_A);
        assertEquals(409, response.getStatusCode().value(), response.getBody());
        assertEquals("INVALID_TRANSITION", json(response).get("code").asText());
        assertUnchanged(BAD_STATE, "accessioned");
    }

    @Test
    void nonPositiveVersionIsRejected() throws Exception {
        ResponseEntity<String> response = post("/api/v1/lab/accessions/" + BAD_VERSION + "/reject",
                rejectBody(REASON, NOTIFICATION, 0), VERIFIER_A, ORG_A);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertEquals("INVALID_VERSION", json(response).get("code").asText());
        assertUnchanged(BAD_VERSION, "collected");
    }
}
