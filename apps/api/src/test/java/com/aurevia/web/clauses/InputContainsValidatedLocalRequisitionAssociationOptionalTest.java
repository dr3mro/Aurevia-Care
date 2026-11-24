package com.aurevia.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-04 clause 2 acceptance on real PostgreSQL: the accept input carries a
 * validated local requisition association and an optional receiving assignee.
 *
 * <p>Every assertion goes through the real HTTP route
 * {@code POST /api/v1/xch03/orders/{id}/accept} and reads the persisted
 * projection back through {@code GET /api/v1/xch03/orders/{id}}. Two synthetic
 * tenants share local order codes so forced RLS is the only separator; each
 * test owns an order because the response ledgers are append-only. The clause is
 * already implemented in production, so this class is proof only and adds no
 * production behavior. All identifiers, patients and staff are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/"
                + InputContainsValidatedLocalRequisitionAssociationOptionalTest.DB)
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class InputContainsValidatedLocalRequisitionAssociationOptionalTest {

    static final String DB = "aurevia_xch03_arc04_input_it";

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "a".repeat(64);
    private static final String VERIFIER_A = "lab-verifier-1111";
    private static final String VERIFIER_B = "lab-verifier-2222";
    private static final String AUDITOR_A = "auditor-1111";
    private static final String PURPOSE = "RESULT_REVIEW";
    private static final String STANDARD = "STANDARD";
    private static final String LEGAL_HOLD = "LEGAL_HOLD";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // One order per case: the response history and retained-copy ledgers are
    // append-only, so sharing an order would leak advanced versions between cases.
    private static final int ORDER_COUNT = 11;
    private static final int IDX_ACCEPT = 1;
    private static final int IDX_MALFORMED = 2;
    private static final int IDX_MISSING = 3;
    private static final int IDX_UNKNOWN_PATIENT = 4;
    private static final int IDX_BLANK_ASSIGNEE = 5;
    private static final int IDX_ABSENT_ASSIGNEE = 6;
    private static final int IDX_OVERLONG = 7;
    private static final int IDX_WRONG_ROLE = 8;
    private static final int IDX_PEER = 9;
    private static final int IDX_STALE = 10;
    private static final int IDX_PEER_PATIENT = 11;

    private RestClient rest;

    /** Bind the HTTP client to the randomly assigned application port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, migrate it and seed both synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Remove the disposable database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /**
     * Seed one tenant with members, one patient and one order per case.
     *
     * @param statement migration-owner statement; tenant scope comes from the
     *                  {@code app.current_org} setting below, never from a bypass
     *                  (the role is {@code NOBYPASSRLS} and V21 forces RLS)
     * @param org       synthetic organization identifier
     * @throws Exception when a seed statement fails
     */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        // V4's trigger resolves granted_by from app.actor in the active tenant,
        // so the grantor must exist before any role assignment.
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

    /** Both tenants use identical local order codes: only forced RLS separates them. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    /** Bind the migration connection to a tenant for authoritative fixture reads. */
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

    /**
     * Read the tenant-local id of the synthetic patient used by accept.
     *
     * @param org owning organization
     * @return the synthetic patient's opaque identifier
     */
    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                if (!result.next()) {
                    throw new IllegalStateException("missing synthetic patient");
                }
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    /** Read the opaque identity id written into the ledgers by the actor trigger. */
    private static String identityId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity "
                            + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
                statement.setString(1, org);
                statement.setString(2, subject);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("missing synthetic identity " + subject);
                    }
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic identity", exception);
        }
    }

    /** Send an authenticated accept command through the real HTTP route. */
    private ResponseEntity<String> post(String path, String body, String subject, String org, String ifMatch) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", java.util.UUID.randomUUID().toString())
                .header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Read one order view through the real HTTP route. */
    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a response body without exposing raw payloads on a parse error. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response body", exception);
        }
    }

    /** Read the persisted projection and its append-only ledgers. */
    private JsonNode view(String id, String subject, String org) {
        return json(get("/api/v1/xch03/orders/" + id, subject, org));
    }

    /** Build a complete accept payload with an explicit assignee. */
    private static String acceptBody(String patient, String assignee, String purpose, String retention) {
        return "{\"localPatientId\":\"" + patient + "\",\"assignee\":\"" + assignee + "\","
                + "\"permittedPurpose\":\"" + purpose + "\",\"retentionPolicy\":\"" + retention + "\"}";
    }

    /** Assert that a refused command left the order and its ledgers untouched. */
    private void assertUnchanged(String id, String subject, String org) {
        JsonNode detail = view(id, subject, org);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/order/version").asInt());
        assertEquals(1, detail.at("/history").size(), "a refused command appends no event");
        assertEquals(0, detail.at("/retained").size(), "a refused command retains no copy");
    }

    @Test
    void acceptWithValidatedLocalPatientAndOptionalAssigneeIsStored() throws Exception {
        String id = orderId(ORG_A, IDX_ACCEPT);
        String patient = patientId(ORG_A);

        ResponseEntity<String> accepted = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", PURPOSE, LEGAL_HOLD), VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        JsonNode result = json(accepted);
        assertEquals("accepted", result.get("state").asText());
        assertEquals(2, result.get("version").asInt());
        assertEquals(patient, result.get("localPatientId").asText());
        assertEquals("clinician-1111", result.get("assignee").asText());
        assertEquals(PURPOSE, result.get("permittedPurpose").asText());
        assertEquals(LEGAL_HOLD, result.get("retentionPolicy").asText());

        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("accepted", detail.at("/order/state").asText());
        assertEquals(patient, detail.at("/order/localPatientId").asText());
        assertEquals("clinician-1111", detail.at("/order/assignee").asText());
        assertEquals(2, detail.at("/history").size(), "seeded receive event plus one accept event");
        JsonNode event = detail.at("/history/1");
        assertEquals("accept", event.get("action").asText());
        assertEquals("requested", event.get("fromState").asText());
        assertEquals("accepted", event.get("toState").asText());
        assertEquals(identityId(ORG_A, VERIFIER_A), event.get("actorId").asText());
        assertEquals(1, detail.at("/retained").size());
        assertEquals(PURPOSE, detail.at("/retained/0/permittedPurpose").asText());
        assertEquals(LEGAL_HOLD, detail.at("/retained/0/retentionPolicy").asText());
        assertTrue(detail.at("/retained/0/legalHold").asBoolean());
        assertEquals(identityId(ORG_A, VERIFIER_A), detail.at("/retained/0/actorId").asText());
    }

    @Test
    void acceptWithMalformedLocalPatientIdIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_MALFORMED);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody("not-a-uuid", "clinician-1111", PURPOSE, STANDARD), VERIFIER_A, ORG_A, "1");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_INPUT", json(refused).get("code").asText());
        assertEquals("localPatientId is malformed", json(refused).get("message").asText());
        assertUnchanged(id, VERIFIER_A, ORG_A);
    }

    @Test
    void acceptWithoutLocalPatientIdIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_MISSING);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                "{\"assignee\":\"clinician-1111\",\"permittedPurpose\":\"" + PURPOSE
                        + "\",\"retentionPolicy\":\"" + STANDARD + "\"}", VERIFIER_A, ORG_A, "1");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_INPUT", json(refused).get("code").asText());
        assertEquals("localPatientId is malformed", json(refused).get("message").asText());
        assertUnchanged(id, VERIFIER_A, ORG_A);
    }

    @Test
    void validButNonExistentLocalPatientIdIsConcealedAsNotFound() throws Exception {
        String id = orderId(ORG_A, IDX_UNKNOWN_PATIENT);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody("cccccccc-cccc-cccc-cccc-cccccccccccc", "clinician-1111", PURPOSE, STANDARD),
                VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        assertEquals("referenced record not found", json(refused).get("message").asText());
        assertUnchanged(id, VERIFIER_A, ORG_A);

        // A genuine patient of another tenant is not a local association in this
        // tenant: the trigger resolves it under the active organization only.
        String peerPatientId = patientId(ORG_B);
        String peerCaseId = orderId(ORG_A, IDX_PEER_PATIENT);
        ResponseEntity<String> peerRefused = post("/api/v1/xch03/orders/" + peerCaseId + "/accept",
                acceptBody(peerPatientId, "clinician-1111", PURPOSE, STANDARD), VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.NOT_FOUND, peerRefused.getStatusCode(), peerRefused.getBody());
        assertEquals("NOT_FOUND", json(peerRefused).get("code").asText());
        assertEquals("referenced record not found", json(peerRefused).get("message").asText());
        assertUnchanged(peerCaseId, VERIFIER_A, ORG_A);
    }

    @Test
    void blankOrAbsentAssigneeIsOptionalAndStoredAsNull() throws Exception {
        String patient = patientId(ORG_A);
        String blankCaseId = orderId(ORG_A, IDX_BLANK_ASSIGNEE);
        ResponseEntity<String> blank = post("/api/v1/xch03/orders/" + blankCaseId + "/accept",
                acceptBody(patient, "   ", PURPOSE, STANDARD), VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, blank.getStatusCode(), blank.getBody());
        assertEquals(2, json(blank).get("version").asInt());
        assertTrue(json(blank).get("assignee").isNull());
        JsonNode blankDetail = view(blankCaseId, VERIFIER_A, ORG_A);
        assertTrue(blankDetail.at("/order/assignee").isNull());
        assertEquals(2, blankDetail.at("/history").size());
        assertEquals(1, blankDetail.at("/retained").size());

        String absentCaseId = orderId(ORG_A, IDX_ABSENT_ASSIGNEE);
        ResponseEntity<String> absent = post("/api/v1/xch03/orders/" + absentCaseId + "/accept",
                "{\"localPatientId\":\"" + patient + "\",\"permittedPurpose\":\"" + PURPOSE
                        + "\",\"retentionPolicy\":\"" + STANDARD + "\"}", VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, absent.getStatusCode(), absent.getBody());
        assertEquals(2, json(absent).get("version").asInt());
        assertTrue(json(absent).get("assignee").isNull());
        JsonNode absentDetail = view(absentCaseId, VERIFIER_A, ORG_A);
        assertTrue(absentDetail.at("/order/assignee").isNull());
        assertEquals(2, absentDetail.at("/history").size());
        assertEquals(1, absentDetail.at("/retained").size());
    }

    @Test
    void overlongAssigneeIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_OVERLONG);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "a".repeat(129), PURPOSE, STANDARD), VERIFIER_A, ORG_A, "1");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_INPUT", json(refused).get("code").asText());
        assertEquals("assignee is too long", json(refused).get("message").asText());
        assertUnchanged(id, VERIFIER_A, ORG_A);
    }

    @Test
    void nonRecipientRoleIsDenied() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);

        // The auditor has an active membership and may read, but privacy_auditor
        // is not a recipient role. A fully valid body keeps a 422 from masking
        // the authorization refusal.
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", PURPOSE, LEGAL_HOLD), AUDITOR_A, ORG_A, "1");

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        assertEquals("missing exchange permission", json(refused).get("message").asText());
        assertUnchanged(id, VERIFIER_A, ORG_A);
    }

    @Test
    void peerTenantCannotAcceptOtherTenantsOrder() throws Exception {
        String id = orderId(ORG_A, IDX_PEER);

        // The peer actor is a genuine lab_verifier in its own tenant, so the
        // 404 must come from RLS concealment rather than a missing role.
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", PURPOSE, LEGAL_HOLD), VERIFIER_B, ORG_B, "1");

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        assertEquals("order not found", json(refused).get("message").asText());
        assertUnchanged(id, VERIFIER_A, ORG_A);

        ResponseEntity<String> own = get("/api/v1/xch03/orders/" + orderId(ORG_B, IDX_PEER),
                VERIFIER_B, ORG_B);
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertEquals("requested", json(own).at("/order/state").asText());
    }

    @Test
    void staleIfMatchIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        String body = acceptBody(patientId(ORG_A), "clinician-1111", PURPOSE, LEGAL_HOLD);

        ResponseEntity<String> first = post("/api/v1/xch03/orders/" + id + "/accept",
                body, VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals(2, json(first).get("version").asInt());

        // The real command advanced the row to version 2, so reusing version 1
        // is an optimistic-lock race rather than a fabricated bad header.
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                body, VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        assertEquals("the order changed since it was read", json(refused).get("message").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("accepted", detail.at("/order/state").asText());
        assertEquals(2, detail.at("/order/version").asInt());
        assertEquals(2, detail.at("/history").size(), "the stale command appends no event after setup");
        assertEquals("accept", detail.at("/history/1").get("action").asText());
        assertEquals(1, detail.at("/retained").size(), "the stale command retains no second copy");
    }
}
