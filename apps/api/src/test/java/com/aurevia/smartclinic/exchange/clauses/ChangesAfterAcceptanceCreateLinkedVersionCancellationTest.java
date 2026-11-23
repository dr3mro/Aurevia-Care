package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * XCH-03 clause 2 at the HTTP boundary: a change or cancellation raised after
 * acceptance creates a linked revision or a pending request, and takes effect
 * only after an explicit recipient response.
 *
 * <p>Every case drives the real REST entry point against a dedicated PostgreSQL
 * database with two isolated tenants that share local order codes. Each test owns
 * its own seeded order because the ledgers are append-only and optimistic versions
 * would otherwise leak between cases. All names, identifiers and content are
 * synthetic; no patient data or credential appears here.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_c2_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class ChangesAfterAcceptanceCreateLinkedVersionCancellationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_c2_it";
    static final String CHECKSUM = "a".repeat(64);
    static final String NEXT_CHECKSUM = "b".repeat(64);
    static final int ORDER_COUNT = 10;
    static final int IDX_LINKED_CHANGE = 1;
    static final int IDX_CANCEL_CONFIRM = 2;
    static final int IDX_CANCEL_DECLINE = 3;
    static final int IDX_WRONG_ROLE = 4;
    static final int IDX_PEER_TENANT = 5;
    static final int IDX_STALE_VERSION = 6;
    static final int IDX_PRE_ACCEPT = 7;
    static final int IDX_MISSING_SUMMARY = 8;
    static final int IDX_MISSING_REASON = 9;
    static final int IDX_CHANGE_REJECT = 10;
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_c2_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch03_c2_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_c2_it WITH (FORCE)");
        }
    }

    /** One tenant with the same local order codes as its peer, plus its members. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        for (String member : new String[] {"admin", "lab-verifier", "clinician", "reception"}) {
            String subject = member + "-" + shortName;
            String role = switch (member) {
                case "lab-verifier" -> "lab_verifier";
                case "clinician" -> "clinician";
                case "reception" -> "reception";
                default -> "tenant_admin";
            };
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + org + "', '" + subject + "', 'Synthetic " + member + "')");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
            statement.execute("INSERT INTO public.role_assignment "
                    + "(organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = '" + subject + "'");
        }
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
        // One order per test: advanced versions and append-only ledgers must not leak.
        for (int index = 1; index <= ORDER_COUNT; index++) {
            statement.execute("INSERT INTO public.xch03_order "
                    + "(organization_id, id, order_code, state, revision, content_checksum, created_by) "
                    + "VALUES ('" + org + "', '" + orderId(org, index) + "', 'LAB-REQ-" + index
                    + "', 'requested', 1, '" + CHECKSUM
                    + "', (SELECT id FROM public.user_identity WHERE subject = 'admin-" + shortName + "'))");
            statement.execute("SET app.actor = 'admin-" + shortName + "'");
            statement.execute("INSERT INTO public.xch03_revision "
                    + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                    + "VALUES ('" + org + "', gen_random_uuid(), '" + orderId(org, index)
                    + "', 1, NULL, '" + CHECKSUM + "')");
            statement.execute("INSERT INTO public.xch03_event "
                    + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                    + "VALUES ('" + org + "', gen_random_uuid(), '" + orderId(org, index)
                    + "', 1, 'received', NULL, 'requested')");
        }
    }

    /** Both tenants use the same local order codes and id suffix: RLS is the only separator. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        String suffix = ORG_A.equals(org) ? "1111" : "2222";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject + "-" + suffix);
            statement.execute();
        }
    }

    private static long scalar(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private HttpHeaders headers(String subject, String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, ifMatch)));
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

    private String suffix(String org) {
        return ORG_A.equals(org) ? "1111" : "2222";
    }

    private String acceptBody(String org) throws Exception {
        return "{\"localPatientId\":\"" + patientId(org) + "\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"STANDARD\"}";
    }

    @Test
    void linkedChangeCreatesRevisionTwoAndServesItOnlyAfterRecipientAcceptance() throws Exception {
        String id = orderId(ORG_A, IDX_LINKED_CHANGE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        ResponseEntity<String> accepted = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1");
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("accepted", text(accepted, "/state"));
        assertEquals("2", text(accepted, "/version"));

        ResponseEntity<String> changed = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "2");
        assertEquals(HttpStatus.OK, changed.getStatusCode(), changed.getBody());
        assertEquals("change_pending", text(changed, "/state"));
        assertEquals("3", text(changed, "/version"));

        JsonNode pending = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("change_pending", pending.at("/order/state").asText());
        assertEquals(3, pending.at("/order/version").asInt());
        assertEquals(1, pending.at("/order/revision").asInt(),
                "a pending change must not become the current released revision");
        JsonNode revisions = pending.at("/revisions");
        assertEquals(2, revisions.size());
        assertEquals(1, revisions.at("/0/revision").asInt());
        assertTrue(revisions.at("/0/previousRevision").isNull());
        assertEquals(CHECKSUM, revisions.at("/0/contentChecksum").asText(),
                "the accepted snapshot must remain unchanged");
        assertEquals(2, revisions.at("/1/revision").asInt());
        assertEquals(1, revisions.at("/1/previousRevision").asInt(),
                "the new revision links back to the accepted revision");
        assertEquals(NEXT_CHECKSUM, revisions.at("/1/contentChecksum").asText());
        assertEquals(1, pending.at("/retained").size());
        assertEquals(1L, scalar(ORG_A, "SELECT count(*) FROM public.xch03_revision WHERE order_id = '" + id
                + "' AND revision = 2 AND previous_revision = 1"));
        String servedWhilePending = text(send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/retrieve", null, verifier, ORG_A, null), "/contentChecksum");
        assertEquals(CHECKSUM, servedWhilePending,
                "until the recipient responds, retrieval still serves revision 1");
        assertNotEquals(NEXT_CHECKSUM, servedWhilePending,
                "the pending revision must never be served before the recipient responds");

        ResponseEntity<String> responded = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"accept\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, responded.getStatusCode(), responded.getBody());
        assertEquals("accepted", text(responded, "/state"));
        assertEquals("4", text(responded, "/version"));
        assertEquals(2, json(responded).at("/revision").asInt());
        assertEquals(NEXT_CHECKSUM, text(send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/retrieve", null, verifier, ORG_A, null), "/contentChecksum"),
                "only the recipient's response serves the linked revision");

        JsonNode resolved = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        JsonNode retained = resolved.at("/retained");
        assertEquals(2, retained.size(), "the accepted change retains a second recipient copy");
        assertEquals(2, retained.at("/1/revision").asInt());
        assertEquals(NEXT_CHECKSUM, retained.at("/1/contentChecksum").asText());
        assertEquals("RESULT_REVIEW", retained.at("/1/permittedPurpose").asText());
        assertEquals("STANDARD", retained.at("/1/retentionPolicy").asText());
        assertEquals(CHECKSUM, resolved.at("/revisions/0/contentChecksum").asText());
        assertEquals("change_accept", resolved.at("/history/3/action").asText());
        assertEquals("change_pending", resolved.at("/history/3/fromState").asText());
        assertEquals("accepted", resolved.at("/history/3/toState").asText());
    }

    @Test
    void aRejectedChangeNeverBecomesTheRetrievableRevision() throws Exception {
        String id = orderId(ORG_A, IDX_CHANGE_REJECT);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "2").getStatusCode());

        ResponseEntity<String> rejected = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"reject\",\"reason\":\"wrong unit\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", text(rejected, "/state"));
        assertEquals("rejected", text(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null), "/order/state"));
        assertEquals(CHECKSUM, text(send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/retrieve", null, verifier, ORG_A, null), "/contentChecksum"),
                "a rejected linked version stays in the ledger but is never served");
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals(2, view.at("/revisions").size());
        assertEquals(1, view.at("/retained").size(),
                "a rejected change must not produce a second retained copy");
        assertEquals("change_reject", view.at("/history/3/action").asText());
        assertEquals("wrong unit", view.at("/history/3/reason").asText());
    }

    @Test
    void cancellationIsOnlyConfirmedByTheRecipient() throws Exception {
        String id = orderId(ORG_A, IDX_CANCEL_CONFIRM);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());

        ResponseEntity<String> requested = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/request-cancellation",
                "{\"reason\":\"order entered in error\"}", clinician, ORG_A, "2");
        assertEquals(HttpStatus.OK, requested.getStatusCode(), requested.getBody());
        assertEquals("cancellation_pending", text(requested, "/state"));
        assertEquals("3", text(requested, "/version"));
        assertEquals("order entered in error", text(requested, "/reason"));
        assertEquals("cancellation_pending", text(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null), "/order/state"));

        ResponseEntity<String> confirmed = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/cancellation-response",
                "{\"decision\":\"confirm\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("cancelled", text(confirmed, "/state"));
        assertEquals("4", text(confirmed, "/version"));
        assertEquals("cancelled", text(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null), "/order/state"));
        JsonNode events = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id + "/history",
                null, verifier, ORG_A, null));
        assertEquals("request_cancellation", events.at("/2/action").asText());
        assertEquals("cancellation_confirm", events.at("/3/action").asText());
        assertEquals("cancellation_pending", events.at("/3/fromState").asText());
        assertEquals("cancelled", events.at("/3/toState").asText());
    }

    @Test
    void aDeclinedCancellationReturnsToAccepted() throws Exception {
        String id = orderId(ORG_A, IDX_CANCEL_DECLINE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/request-cancellation",
                "{\"reason\":\"duplicate\"}", clinician, ORG_A, "2").getStatusCode());

        ResponseEntity<String> declined = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/cancellation-response",
                "{\"decision\":\"decline\",\"reason\":\"work in progress\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, declined.getStatusCode(), declined.getBody());
        assertEquals("accepted", text(declined, "/state"));
        assertEquals("accepted", text(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null), "/order/state"));
        assertEquals("cancellation_decline", text(send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/history", null, verifier, ORG_A, null), "/3/action"));
    }

    @Test
    void aRecipientCannotProposeAChange() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                verifier, ORG_A, "2");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", text(refused, "/code"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(2, view.at("/order/version").asInt());
        assertEquals(1, view.at("/revisions").size());
    }

    @Test
    void aPeerTenantCannotRespondToAnotherTenantsPendingChange() throws Exception {
        String id = orderId(ORG_A, IDX_PEER_TENANT);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "2").getStatusCode());

        ResponseEntity<String> concealed = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"accept\"}", "lab-verifier-" + suffix(ORG_B), ORG_B, "3");
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());
        assertEquals("NOT_FOUND", text(concealed, "/code"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("change_pending", view.at("/order/state").asText());
        assertEquals(3, view.at("/order/version").asInt());

        ResponseEntity<String> realResponse = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"accept\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, realResponse.getStatusCode(), realResponse.getBody());
        assertEquals("accepted", text(realResponse, "/state"));
    }

    @Test
    void aStaleVersionCannotProposeAChange() throws Exception {
        String id = orderId(ORG_A, IDX_STALE_VERSION);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());

        ResponseEntity<String> stale = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "99");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", text(stale, "/code"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(2, view.at("/order/version").asInt());
        assertEquals(1, view.at("/revisions").size());
    }

    @Test
    void aChangeCannotBeProposedBeforeAcceptance() throws Exception {
        String id = orderId(ORG_A, IDX_PRE_ACCEPT);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "1");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", text(refused, "/code"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("requested", view.at("/order/state").asText());
        assertEquals(1, view.at("/order/version").asInt());
        assertEquals(1, view.at("/revisions").size());
    }

    @Test
    void aChangeRequiresAChangeSummary() throws Exception {
        String id = orderId(ORG_A, IDX_MISSING_SUMMARY);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "2");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REQUIRED", text(refused, "/code"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(2, view.at("/order/version").asInt());
        assertEquals(1, view.at("/revisions").size());
    }

    @Test
    void aCancellationRequestRequiresAReason() throws Exception {
        String id = orderId(ORG_A, IDX_MISSING_REASON);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(ORG_A), verifier, ORG_A, "1").getStatusCode());

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/request-cancellation",
                "{\"reason\":\"  \"}", clinician, ORG_A, "2");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REQUIRED", text(refused, "/code"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(2, view.at("/order/version").asInt());
        assertEquals(2, view.at("/history").size());
    }
}
