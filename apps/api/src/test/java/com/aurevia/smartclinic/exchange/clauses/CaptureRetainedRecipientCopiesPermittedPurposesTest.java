package com.aurevia.smartclinic.exchange.clauses;

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
 * XCH-03 clause 3 acceptance (HTTP level, real PostgreSQL, synthetic fixtures only).
 *
 * <p>Proves that accepting a released order captures an immutable retained recipient
 * copy carrying the permitted purpose, approved retention policy, released checksum
 * and authenticated actor; that an accepted post-acceptance change retains a new
 * linked revision under the purpose captured at the original acceptance; and that the
 * rule refuses to capture outside its input, role, tenant, version and state
 * boundaries. Every order gets its own index because the ledgers are append-only and
 * a shared order would leak advanced versions between tests.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_c03_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class CaptureRetainedRecipientCopiesPermittedPurposesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_c03_it";
    static final String CHECKSUM = "a".repeat(64);
    static final String NEXT_CHECKSUM = "b".repeat(64);

    /* One order per test; reject and clarify need two distinct requested orders. */
    static final int ORDER_COUNT = 9;
    static final int IDX_ACCEPT = 1;
    static final int IDX_CHANGE = 2;
    static final int IDX_RETENTION = 3;
    static final int IDX_PURPOSE = 4;
    static final int IDX_ROLE = 5;
    static final int IDX_PEER = 6;
    static final int IDX_STALE = 7;
    static final int IDX_REJECT = 8;
    static final int IDX_CLARIFY = 9;

    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a fresh migrated database so no other suite's append-only state leaks in. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_c03_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch03_c03_it OWNER smartclinic_migrate");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_c03_it WITH (FORCE)");
        }
    }

    /** One tenant with its members, one synthetic patient and its own requested orders. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        // The grantor identity must exist before the first role assignment:
        // V4's trigger resolves granted_by from app.actor in the active tenant.
        for (String member : new String[] {"admin", "lab-verifier", "clinician", "reception", "auditor"}) {
            String subject = member + "-" + shortName;
            String role = switch (member) {
                case "lab-verifier" -> "lab_verifier";
                case "clinician" -> "clinician";
                case "reception" -> "reception";
                case "auditor" -> "privacy_auditor";
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

    /** Both tenants use the same local codes and id suffix: RLS is the only separator. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    private static String suffix(String org) {
        return ORG_A.equals(org) ? "1111" : "2222";
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

    /** The opaque identity id that the actor trigger writes into the ledgers. */
    private static String identityId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity "
                            + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
                statement.setString(1, org);
                statement.setString(2, subject);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "missing synthetic identity " + subject);
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic identity", exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject + "-" + suffix(org));
            statement.execute();
        }
    }

    /** Count retained rows in the owning tenant; a peer tenant would see zero under RLS. */
    private static long retainedCount(String org, String orderId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + orderId + "'")) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /** A valid accept payload so a negative result is attributable to the one rule under test. */
    private static String acceptBody(String org, String purpose, String retention) {
        return "{\"localPatientId\":\"" + patientId(org) + "\",\"assignee\":\"clinician-"
                + suffix(org) + "\",\"permittedPurpose\":\"" + purpose
                + "\",\"retentionPolicy\":\"" + retention + "\"}";
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

    @Test
    void acceptCapturesRetainedCopyWithPermittedPurposeAndRetentionPolicy() throws Exception {
        String id = orderId(ORG_A, IDX_ACCEPT);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String uri = "/api/v1/xch03/orders/" + id;

        ResponseEntity<String> accepted = send(HttpMethod.POST, uri + "/accept",
                acceptBody(ORG_A, "RESULT_REVIEW", "LEGAL_HOLD"), verifier, ORG_A, "1");
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("accepted", text(accepted, "/state"));
        assertEquals("2", text(accepted, "/version"));

        JsonNode view = json(send(HttpMethod.GET, uri, null, verifier, ORG_A, null));
        assertEquals(1, view.at("/retained").size());
        JsonNode retained = view.at("/retained/0");
        assertFalse(retained.isMissingNode(), "accept must write a retained recipient copy");
        assertEquals(1, retained.at("/revision").asInt());
        assertEquals(CHECKSUM, retained.at("/contentChecksum").asText());
        assertEquals("RESULT_REVIEW", retained.at("/permittedPurpose").asText());
        assertEquals("LEGAL_HOLD", retained.at("/retentionPolicy").asText());
        assertTrue(retained.at("/legalHold").asBoolean(), "a legal hold must be captured on the copy");
        assertEquals(identityId(ORG_A, verifier), retained.at("/actorId").asText(),
                "the retained actor is the authenticated user's opaque id, not its subject");
        assertEquals(1L, retainedCount(ORG_A, id), "exactly one retained copy is durably stored");
    }

    @Test
    void acceptedChangeRetainsNewRevisionUnderOriginalPurpose() throws Exception {
        String id = orderId(ORG_A, IDX_CHANGE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        String uri = "/api/v1/xch03/orders/" + id;

        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept",
                acceptBody(ORG_A, "RESULT_REVIEW", "STANDARD"), verifier, ORG_A, "1").getStatusCode());
        ResponseEntity<String> proposed = send(HttpMethod.POST, uri + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "2");
        assertEquals(HttpStatus.OK, proposed.getStatusCode(), proposed.getBody());
        assertEquals("change_pending", text(proposed, "/state"));

        ResponseEntity<String> responded = send(HttpMethod.POST, uri + "/change-response",
                "{\"decision\":\"accept\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, responded.getStatusCode(), responded.getBody());
        assertEquals("accepted", text(responded, "/state"));

        JsonNode view = json(send(HttpMethod.GET, uri, null, verifier, ORG_A, null));
        assertEquals(2, view.at("/retained").size());
        JsonNode second = view.at("/retained/1");
        assertEquals(2, second.at("/revision").asInt());
        assertEquals(NEXT_CHECKSUM, second.at("/contentChecksum").asText(),
                "the new retained copy must point at the linked revision's checksum");
        assertEquals("RESULT_REVIEW", second.at("/permittedPurpose").asText(),
                "a change is retained under the purpose captured at original acceptance");
        assertEquals("STANDARD", second.at("/retentionPolicy").asText());
        assertFalse(second.at("/legalHold").isMissingNode(), "the retained copy must carry legalHold");
        assertFalse(second.at("/legalHold").asBoolean());
        JsonNode revision = view.at("/revisions/1");
        assertEquals(2, revision.at("/revision").asInt());
        assertEquals(1, revision.at("/previousRevision").asInt(), "the new revision links to the accepted one");
        assertEquals(NEXT_CHECKSUM, revision.at("/contentChecksum").asText());
        assertEquals(2L, retainedCount(ORG_A, id));
    }

    @Test
    void refusesUnapprovedRetentionPolicyWithoutCapturingACopy() throws Exception {
        String id = orderId(ORG_A, IDX_RETENTION);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String uri = "/api/v1/xch03/orders/" + id;
        long before = retainedCount(ORG_A, id);

        ResponseEntity<String> refused = send(HttpMethod.POST, uri + "/accept",
                acceptBody(ORG_A, "RESULT_REVIEW", "FOREVER"), verifier, ORG_A, "1");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_RETENTION_POLICY", text(refused, "/code"));
        assertEquals(before, retainedCount(ORG_A, id), "a refused policy must not write a retained copy");
        assertEquals("requested", text(send(HttpMethod.GET, uri, null, verifier, ORG_A, null), "/order/state"));
    }

    @Test
    void refusesMalformedPermittedPurposeWithoutCapturingACopy() throws Exception {
        String id = orderId(ORG_A, IDX_PURPOSE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String uri = "/api/v1/xch03/orders/" + id;
        long before = retainedCount(ORG_A, id);

        // Invalid characters, not a blank value: a blank would fail as REQUIRED instead.
        ResponseEntity<String> refused = send(HttpMethod.POST, uri + "/accept",
                acceptBody(ORG_A, "BAD PURPOSE!", "STANDARD"), verifier, ORG_A, "1");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_INPUT", text(refused, "/code"));
        assertEquals(before, retainedCount(ORG_A, id), "a malformed purpose must not write a retained copy");
        assertEquals("requested", text(send(HttpMethod.GET, uri, null, verifier, ORG_A, null), "/order/state"));
    }

    @Test
    void refusesAcceptByNonRecipientRolesWithoutCapturingACopy() throws Exception {
        String id = orderId(ORG_A, IDX_ROLE);
        String uri = "/api/v1/xch03/orders/" + id;
        String body = acceptBody(ORG_A, "RESULT_REVIEW", "STANDARD");
        long before = retainedCount(ORG_A, id);

        for (String role : new String[] {"reception", "auditor"}) {
            ResponseEntity<String> refused = send(HttpMethod.POST, uri + "/accept", body,
                    role + "-" + suffix(ORG_A), ORG_A, "1");
            assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
            assertEquals("FORBIDDEN", text(refused, "/code"));
            assertEquals(before, retainedCount(ORG_A, id), role + " must not capture a retained copy");
        }
        assertEquals("requested", text(send(HttpMethod.GET, uri, null,
                "lab-verifier-" + suffix(ORG_A), ORG_A, null), "/order/state"));
    }

    @Test
    void concealsRetainedLedgerFromAnotherTenant() throws Exception {
        String id = orderId(ORG_A, IDX_PEER);
        String owner = "lab-verifier-" + suffix(ORG_A);
        String uri = "/api/v1/xch03/orders/" + id;
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept",
                acceptBody(ORG_A, "RESULT_REVIEW", "STANDARD"), owner, ORG_A, "1").getStatusCode());
        assertEquals("RESULT_REVIEW", text(send(HttpMethod.GET, uri, null, owner, ORG_A, null),
                "/retained/0/permittedPurpose"), "the owner can read its own retained copy");
        assertEquals(1L, retainedCount(ORG_A, id));

        // The peer subject is an active ORG_B member; the order and copy live in ORG_A,
        // so forced RLS conceals the row. The retained array is inside GET /{id}.
        ResponseEntity<String> concealed = send(HttpMethod.GET, uri, null,
                "lab-verifier-" + suffix(ORG_B), ORG_B, null);
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(),
                "a peer tenant cannot read the owner's retained ledger under RLS");
        assertEquals(1L, retainedCount(ORG_A, id), "a concealed read writes no retained copy");
    }

    @Test
    void refusesStaleAcceptReplayWithoutCapturingASecondCopy() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String uri = "/api/v1/xch03/orders/" + id;
        String body = acceptBody(ORG_A, "RESULT_REVIEW", "STANDARD");

        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept", body, verifier, ORG_A, "1")
                .getStatusCode());
        assertEquals(1L, retainedCount(ORG_A, id));

        ResponseEntity<String> replay = send(HttpMethod.POST, uri + "/accept", body, verifier, ORG_A, "1");
        assertEquals(HttpStatus.PRECONDITION_FAILED, replay.getStatusCode(), replay.getBody());
        assertEquals("STALE_VERSION", text(replay, "/code"));
        assertEquals(1L, retainedCount(ORG_A, id), "a stale replay must not capture a second copy");
        assertEquals(1, json(send(HttpMethod.GET, uri, null, verifier, ORG_A, null)).at("/retained").size());
    }

    @Test
    void rejectAndClarifyNeverCaptureARetainedCopy() throws Exception {
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String rejectId = orderId(ORG_A, IDX_REJECT);
        String clarifyId = orderId(ORG_A, IDX_CLARIFY);

        ResponseEntity<String> rejected = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + rejectId + "/reject",
                "{\"reason\":\"duplicate order\"}", verifier, ORG_A, "1");
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", text(rejected, "/state"));

        ResponseEntity<String> clarified = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + clarifyId + "/clarify",
                "{\"question\":\"Which specimen?\"}", verifier, ORG_A, "1");
        assertEquals(HttpStatus.OK, clarified.getStatusCode(), clarified.getBody());
        assertEquals("clarification_requested", text(clarified, "/state"));

        assertEquals(0L, retainedCount(ORG_A, rejectId), "a rejection must not capture a retained copy");
        assertEquals(0L, retainedCount(ORG_A, clarifyId), "a clarification must not capture a retained copy");
        assertEquals(0, json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + rejectId,
                null, verifier, ORG_A, null)).at("/retained").size());
        assertEquals(0, json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + clarifyId,
                null, verifier, ORG_A, null)).at("/retained").size());
    }
}
