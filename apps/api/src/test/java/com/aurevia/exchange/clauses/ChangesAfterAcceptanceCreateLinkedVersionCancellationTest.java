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
 * XCH-03 clause 2 (API evidence): changes after acceptance create a linked
 * version or a cancellation request, and both require a recipient response.
 *
 * <p>The fixture drives the real HTTP controller against a dedicated real
 * PostgreSQL database with forced row-level security and append-only ledgers.
 * The orders used by the positive test and the role/version negatives are
 * seeded directly in {@code accepted} state with their first released revision
 * and retained recipient copy, so those tests observe only post-acceptance
 * behavior; two extra orders provide the before-acceptance and already-pending
 * states for the state and recipient-response negatives. The positive test proves both branches of the
 * clause: a proposed change stays pending and is not served as the released
 * revision until a recipient responds, and a cancellation request stays
 * pending until a recipient confirms it. The negative tests prove that a
 * recipient cannot raise either command, that a stale version is refused, and
 * that a change cannot be raised before acceptance. All identifiers and
 * checksums are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_exchange_response_change_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class ChangesAfterAcceptanceCreateLinkedVersionCancellationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_exchange_response_change_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "a".repeat(64);
    private static final String NEXT_CHECKSUM = "b".repeat(64);
    private static final String SENDER = "clinician-1111";
    private static final String RECIPIENT = "lab-verifier-1111";

    private static final String CHANGE_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-000000000001";
    private static final String CANCEL_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-000000000002";
    private static final String FORBIDDEN_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-000000000003";
    private static final String STALE_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-000000000004";
    private static final String REQUESTED_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-000000000005";
    private static final String PENDING_CHANGE_ORDER = "aaaaaaaa-aaaa-aaaa-aaaa-000000000006";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_exchange_response_change_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_exchange_response_change_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_exchange_response_change_it WITH (FORCE)");
        }
    }

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Seed one tenant with its members and six independent synthetic orders.
     * Each test owns its own order so the append-only ledgers cannot leak state.
     */
    private static void seedTenant(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG + "'");
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + ORG + "', 'Synthetic Change Org')");
        // The grantor identity must exist before the first role assignment:
        // V4's trigger resolves granted_by from app.actor in the active tenant.
        for (String member : new String[] {"admin", "clinician", "lab-verifier", "reception"}) {
            String subject = member + "-1111";
            String role = switch (member) {
                case "clinician" -> "clinician";
                case "lab-verifier" -> "lab_verifier";
                case "reception" -> "reception";
                default -> "tenant_admin";
            };
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG + "', '" + subject + "', 'Synthetic " + member + "')");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + ORG + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
            statement.execute("INSERT INTO public.role_assignment "
                    + "(organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + ORG + "', m.id, '" + role + "', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = '" + subject + "'");
        }
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG + "', 1, 'MRN-CHANGE', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");

        seedAcceptedOrder(statement, CHANGE_ORDER, "LAB-CHANGE-1");
        seedAcceptedOrder(statement, CANCEL_ORDER, "LAB-CANCEL-1");
        seedAcceptedOrder(statement, FORBIDDEN_ORDER, "LAB-FORBIDDEN-1");
        seedAcceptedOrder(statement, STALE_ORDER, "LAB-STALE-1");
        seedRequestedOrder(statement);
        seedPendingChangeOrder(statement);
    }

    /** Seed an accepted work item with its first released revision and retained copy. */
    private static void seedAcceptedOrder(Statement statement, String id, String code) throws Exception {
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.exchange_response_order "
                + "(organization_id, id, order_code, state, version, revision, content_checksum, "
                + "local_patient_id, assignee, permitted_purpose, retention_policy, created_by) "
                + "VALUES ('" + ORG + "', '" + id + "', '" + code + "', 'accepted', 2, 1, '" + CHECKSUM + "', "
                + "(SELECT id FROM public.patient_record WHERE organization_id = CAST('" + ORG + "' AS uuid) "
                + "AND mrn = 'MRN-CHANGE'), 'clinician-1111', 'RESULT_REVIEW', 'STANDARD', "
                + "(SELECT id FROM public.user_identity WHERE subject = 'admin-1111'))");
        statement.execute("INSERT INTO public.exchange_response_revision "
                + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + id + "', 1, NULL, '" + CHECKSUM + "')");
        statement.execute("INSERT INTO public.exchange_response_retained_copy "
                + "(organization_id, id, order_id, revision, content_checksum, permitted_purpose, retention_policy) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + id + "', 1, '" + CHECKSUM + "', "
                + "'RESULT_REVIEW', 'STANDARD')");
        statement.execute("INSERT INTO public.exchange_response_event "
                + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + id + "', 1, 'received', NULL, 'requested')");
        statement.execute("INSERT INTO public.exchange_response_event "
                + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + id + "', 2, 'accept', 'requested', 'accepted')");
    }

    /** Seed one order still in requested state for the before-acceptance negative. */
    private static void seedRequestedOrder(Statement statement) throws Exception {
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.exchange_response_order "
                + "(organization_id, id, order_code, state, version, revision, content_checksum, created_by) "
                + "VALUES ('" + ORG + "', '" + REQUESTED_ORDER + "', 'LAB-REQUESTED-1', 'requested', 1, 1, '"
                + CHECKSUM + "', (SELECT id FROM public.user_identity WHERE subject = 'admin-1111'))");
        statement.execute("INSERT INTO public.exchange_response_revision "
                + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + REQUESTED_ORDER + "', 1, NULL, '"
                + CHECKSUM + "')");
        statement.execute("INSERT INTO public.exchange_response_event "
                + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + REQUESTED_ORDER
                + "', 1, 'received', NULL, 'requested')");
    }

    /** Seed a change already awaiting a recipient response for the separation-of-duties negative. */
    private static void seedPendingChangeOrder(Statement statement) throws Exception {
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.exchange_response_order "
                + "(organization_id, id, order_code, state, version, revision, content_checksum, "
                + "local_patient_id, assignee, permitted_purpose, retention_policy, created_by) "
                + "VALUES ('" + ORG + "', '" + PENDING_CHANGE_ORDER + "', 'LAB-PENDING-1', 'change_pending', 3, 2, '"
                + CHECKSUM + "', (SELECT id FROM public.patient_record WHERE organization_id = CAST('" + ORG
                + "' AS uuid) AND mrn = 'MRN-CHANGE'), 'clinician-1111', 'RESULT_REVIEW', 'STANDARD', "
                + "(SELECT id FROM public.user_identity WHERE subject = 'admin-1111'))");
        statement.execute("INSERT INTO public.exchange_response_revision "
                + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + PENDING_CHANGE_ORDER + "', 1, NULL, '"
                + CHECKSUM + "')");
        statement.execute("INSERT INTO public.exchange_response_revision "
                + "(organization_id, id, order_id, revision, previous_revision, content_checksum, change_summary) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + PENDING_CHANGE_ORDER + "', 2, 1, '"
                + NEXT_CHECKSUM + "', 'corrected unit')");
        statement.execute("INSERT INTO public.exchange_response_event "
                + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                + "VALUES ('" + ORG + "', gen_random_uuid(), '" + PENDING_CHANGE_ORDER
                + "', 1, 'received', NULL, 'requested'), ('" + ORG + "', gen_random_uuid(), '"
                + PENDING_CHANGE_ORDER + "', 2, 'accept', 'requested', 'accepted'), ('" + ORG
                + "', gen_random_uuid(), '" + PENDING_CHANGE_ORDER
                + "', 3, 'propose_change', 'accepted', 'change_pending')");
    }

    @Test
    void changesAfterAcceptanceCreateALinkedRevisionAndCancellationRequestAwaitingRecipientResponse()
            throws Exception {
        String changePath = "/api/v1/xch03/orders/" + CHANGE_ORDER;
        ResponseEntity<String> proposed = send(HttpMethod.POST, changePath + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                SENDER, "2");
        assertEquals(HttpStatus.OK, proposed.getStatusCode(), proposed.getBody());
        assertEquals("change_pending", text(proposed, "/state"));
        assertEquals("3", text(proposed, "/version"));

        JsonNode revisions = json(send(HttpMethod.GET, changePath, null, RECIPIENT, null)).at("/revisions");
        assertEquals(2, revisions.size(), "a change adds a linked revision, never rewrites the first one");
        assertEquals(1, revisions.at("/0/revision").asInt());
        assertEquals(CHECKSUM, revisions.at("/0/contentChecksum").asText(),
                "the accepted snapshot remains unchanged");
        assertEquals(2, revisions.at("/1/revision").asInt());
        assertEquals(1, revisions.at("/1/previousRevision").asInt());
        assertEquals(NEXT_CHECKSUM, revisions.at("/1/contentChecksum").asText());
        assertEquals(1L, count("SELECT count(*) FROM public.exchange_response_revision WHERE order_id = CAST('"
                + CHANGE_ORDER + "' AS uuid) AND revision = 2 AND previous_revision = 1"));
        assertEquals(1L, count("SELECT count(*) FROM public.exchange_response_event WHERE order_id = CAST('"
                + CHANGE_ORDER + "' AS uuid) AND action = 'propose_change'"));

        // The linked revision lives only in the ledger while the response is pending:
        // retrieval must keep serving the accepted release in the meantime.
        assertEquals(CHECKSUM, text(send(HttpMethod.GET, changePath + "/retrieve", null, RECIPIENT, null),
                "/contentChecksum"));

        ResponseEntity<String> changeResponded = send(HttpMethod.POST, changePath + "/change-response",
                "{\"decision\":\"accept\"}", RECIPIENT, "3");
        assertEquals(HttpStatus.OK, changeResponded.getStatusCode(), changeResponded.getBody());
        assertEquals("accepted", text(changeResponded, "/state"));
        assertEquals(1L, count("SELECT count(*) FROM public.exchange_response_event WHERE order_id = CAST('"
                + CHANGE_ORDER + "' AS uuid) AND action = 'change_accept'"));
        assertEquals(NEXT_CHECKSUM, text(send(HttpMethod.GET, changePath + "/retrieve", null, RECIPIENT, null),
                "/contentChecksum"));

        String cancelPath = "/api/v1/xch03/orders/" + CANCEL_ORDER;
        ResponseEntity<String> requested = send(HttpMethod.POST, cancelPath + "/request-cancellation",
                "{\"reason\":\"order entered in error\"}", SENDER, "2");
        assertEquals(HttpStatus.OK, requested.getStatusCode(), requested.getBody());
        assertEquals("cancellation_pending", text(requested, "/state"));
        assertEquals("3", text(requested, "/version"));
        assertEquals(1L, count("SELECT count(*) FROM public.exchange_response_event WHERE order_id = CAST('"
                + CANCEL_ORDER + "' AS uuid) AND action = 'request_cancellation'"));
        assertEquals(1L, count("SELECT count(*) FROM public.exchange_response_revision WHERE order_id = CAST('"
                + CANCEL_ORDER + "' AS uuid)"), "a cancellation request is not a linked version");

        ResponseEntity<String> confirmed = send(HttpMethod.POST, cancelPath + "/cancellation-response",
                "{\"decision\":\"confirm\"}", RECIPIENT, "3");
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("cancelled", text(confirmed, "/state"));
        assertEquals(1L, count("SELECT count(*) FROM public.exchange_response_event WHERE order_id = CAST('"
                + CANCEL_ORDER + "' AS uuid) AND action = 'cancellation_confirm'"));
    }

    @Test
    void aRecipientRoleCannotProposeAChangeOrRequestCancellation() throws Exception {
        String path = "/api/v1/xch03/orders/" + FORBIDDEN_ORDER;
        ResponseEntity<String> change = send(HttpMethod.POST, path + "/propose-change",
                "{\"changeSummary\":\"not mine\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                RECIPIENT, "2");
        assertEquals(HttpStatus.FORBIDDEN, change.getStatusCode(), change.getBody());
        assertEquals("FORBIDDEN", text(change, "/code"));
        ResponseEntity<String> cancellation = send(HttpMethod.POST, path + "/request-cancellation",
                "{\"reason\":\"not mine\"}", RECIPIENT, "2");
        assertEquals(HttpStatus.FORBIDDEN, cancellation.getStatusCode(), cancellation.getBody());
        assertEquals("FORBIDDEN", text(cancellation, "/code"));
        assertEquals(0L, count("SELECT count(*) FROM public.exchange_response_revision WHERE order_id = CAST('"
                + FORBIDDEN_ORDER + "' AS uuid) AND revision = 2"), "a denied command writes no revision");
        assertEquals(2L, count("SELECT count(*) FROM public.exchange_response_event WHERE order_id = CAST('"
                + FORBIDDEN_ORDER + "' AS uuid)"), "a denied command writes no history event");
    }

    @Test
    void aStaleIfMatchCannotProposeAChange() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + STALE_ORDER + "/propose-change",
                "{\"changeSummary\":\"stale\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                SENDER, "1");
        assertEquals(HttpStatus.PRECONDITION_FAILED, response.getStatusCode(), response.getBody());
        assertEquals("STALE_VERSION", text(response, "/code"));
        assertEquals(0L, count("SELECT count(*) FROM public.exchange_response_revision WHERE order_id = CAST('"
                + STALE_ORDER + "' AS uuid) AND revision = 2"));
    }

    @Test
    void aChangeBeforeAcceptanceIsRefused() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + REQUESTED_ORDER + "/propose-change",
                "{\"changeSummary\":\"too early\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                SENDER, "1");
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), response.getBody());
        assertEquals("INVALID_TRANSITION", text(response, "/code"));
        assertEquals(0L, count("SELECT count(*) FROM public.exchange_response_revision WHERE order_id = CAST('"
                + REQUESTED_ORDER + "' AS uuid) AND revision = 2"));
    }

    @Test
    void aSenderRoleCannotRespondToAPendingChange() throws Exception {
        ResponseEntity<String> response = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + PENDING_CHANGE_ORDER + "/change-response",
                "{\"decision\":\"accept\"}", "reception-1111", "3");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals("FORBIDDEN", text(response, "/code"));
        assertEquals("3", text(send(HttpMethod.GET, "/api/v1/xch03/orders/" + PENDING_CHANGE_ORDER,
                null, RECIPIENT, null), "/order/version"), "a denied response must not advance the order");
        assertEquals(3L, count("SELECT count(*) FROM public.exchange_response_event WHERE order_id = CAST('"
                + PENDING_CHANGE_ORDER + "' AS uuid)"));
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body,
                                        String subject, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        headers.set("X-Active-Organization", ORG);
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer);
        return value.isNull() ? null : value.asText();
    }

    /** Count rows through the tenant-guarded migration role with forced RLS active. */
    private static long count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, "admin-1111");
                bind.execute();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next(), "no count row for " + sql);
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }
}
