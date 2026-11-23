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
 * XCH-03 clause 5 (API evidence): law and policy govern recipient retention.
 *
 * <p>An approved retention policy is required at acceptance; a legal hold is
 * derived from that policy and is recorded with the permitted purpose, the
 * released checksum and the authenticated actor in an immutable retained copy.
 * An accepted change re-retains the next revision under the original policy,
 * while a rejected change leaves the retained copy untouched. The negative
 * cases show that an unapproved policy, an actor without a recipient role, a
 * caller from another tenant, a stale version and a second acceptance cannot
 * create or alter a retained copy.</p>
 *
 * <p>All requests go through the real {@code /api/v1/xch03/orders} entry point
 * against a fresh database created from the production Flyway migrations, so
 * the forced-RLS tenant boundary is exercised as well as the HTTP contract.
 * Fixtures are synthetic: no real identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_recipient_retention_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class ApplyLawPolicyRecipientRetentionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String PATIENT_B = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_recipient_retention_it";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String NEXT_CHECKSUM = "b".repeat(64);
    private static final String MIGRATION_USER = "smartclinic_migrate";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Each test owns one order because the XCH-03 ledgers are append-only. */
    private static final int ORDER_COUNT = 10;
    private static final int IDX_LEGAL_HOLD = 1;
    private static final int IDX_STANDARD = 2;
    private static final int IDX_CHANGE_RETAIN = 3;
    private static final int IDX_FORBIDDEN_POLICY = 4;
    private static final int IDX_WRONG_ROLE = 5;
    private static final int IDX_CROSS_TENANT = 6;
    private static final int IDX_STALE = 7;
    private static final int IDX_DOUBLE_ACCEPT = 8;
    private static final int IDX_CHANGE_REJECT = 9;
    private static final int IDX_EXTENDED = 10;

    private RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed two tenants with synthetic members, patients and orders.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_recipient_retention_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_recipient_retention_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, MIGRATION_USER, "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, MIGRATION_USER, "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, PATIENT_A);
            seedTenant(statement, ORG_B, PATIENT_B);
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_recipient_retention_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     *
     * @param port the port chosen by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** One tenant with its members, one synthetic patient and one order per test. */
    private static void seedTenant(Statement statement, String org, String patient) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
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
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + patient + "', 1, 'MRN-RETENTION', 'CLINIC', "
                + "'Synthetic Retention Patient', 'synthetic retention patient', DATE '1990-05-20')");
        for (int index = 1; index <= ORDER_COUNT; index++) {
            String id = orderId(org, index);
            statement.execute("INSERT INTO public.xch03_order "
                    + "(organization_id, id, order_code, state, revision, content_checksum, created_by) "
                    + "VALUES ('" + org + "', '" + id + "', 'LAB-REQ-" + index
                    + "', 'requested', 1, '" + CHECKSUM
                    + "', (SELECT id FROM public.user_identity WHERE subject = 'admin-" + shortName + "'))");
            statement.execute("SET app.actor = 'admin-" + shortName + "'");
            statement.execute("INSERT INTO public.xch03_revision "
                    + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                    + "VALUES ('" + org + "', gen_random_uuid(), '" + id
                    + "', 1, NULL, '" + CHECKSUM + "')");
            statement.execute("INSERT INTO public.xch03_event "
                    + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                    + "VALUES ('" + org + "', gen_random_uuid(), '" + id
                    + "', 1, 'received', NULL, 'requested')");
        }
    }

    /** Both tenants use the same local order codes; RLS is the only separator. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    /** The synthetic subject of a role in the given tenant. */
    private static String subject(String role, String org) {
        return role + "-" + org.substring(0, 4);
    }

    private HttpHeaders headers(String actor, String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (actor != null) {
            headers.set("X-Synthetic-Subject", actor);
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

    /** Send a request and capture every status instead of throwing on an error response. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String actor,
                                        String organization, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(actor, organization, ifMatch)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> accept(String id, String role, String org, String purpose,
                                          String policy, String ifMatch) {
        String body = "{\"localPatientId\":\"" + (ORG_A.equals(org) ? PATIENT_A : PATIENT_B) + "\","
                + "\"assignee\":\"" + subject("clinician", org) + "\","
                + "\"permittedPurpose\":\"" + purpose + "\",\"retentionPolicy\":\"" + policy + "\"}";
        return send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                body, subject(role, org), org, ifMatch);
    }

    private JsonNode view(String org, String id, String role) {
        return json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, subject(role, org), org, null));
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            // A parse failure only needs the status; the HTTP log already records
            // the request, and an unexpected payload need not reach a stack trace.
            throw new IllegalStateException("cannot parse response with status " + response.getStatusCode(),
                    exception);
        }
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer);
        return value.isNull() ? null : value.asText();
    }

    /** The opaque identity id that the actor trigger writes into a retained copy. */
    private static String identityId(String org, String actor) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, MIGRATION_USER, "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity "
                            + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
                statement.setString(1, org);
                statement.setString(2, actor);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "missing synthetic identity " + actor);
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** Bind the migration owner to the active tenant: FORCE RLS applies to it too. */
    private static void bind(Connection connection, String org, String role) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject(role, org));
            statement.execute();
        }
    }

    /** Read an aggregate as the active tenant, rolling back the read-only binding. */
    private static long scalar(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, MIGRATION_USER, "")) {
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

    private static long retainedCount(String org, String id) throws Exception {
        return scalar(org, "SELECT count(*) FROM public.xch03_retained_copy "
                + "WHERE order_id = CAST('" + id + "' AS uuid)");
    }

    /** The durable retention values of one revision, read under the active tenant. */
    private record RetainedRow(String purpose, String policy, boolean legalHold,
                               String actorId, String checksum) {
    }

    private static RetainedRow retainedRow(String org, String id, int revision) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, MIGRATION_USER, "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT permitted_purpose, retention_policy, legal_hold, actor_id::text, content_checksum "
                            + "FROM public.xch03_retained_copy "
                            + "WHERE order_id = CAST(? AS uuid) AND revision = ?")) {
                statement.setString(1, id);
                statement.setInt(2, revision);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "missing retained row for revision " + revision);
                    RetainedRow row = new RetainedRow(result.getString(1), result.getString(2),
                            result.getBoolean(3), result.getString(4), result.getString(5));
                    assertFalse(result.next(), "more than one retained row for revision " + revision);
                    return row;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertRetainedCopy(JsonNode copy, int revision, String purpose, String policy,
                                           boolean legalHold, String actor, String checksum) {
        assertEquals(revision, copy.path("revision").asInt());
        assertEquals(purpose, copy.path("permittedPurpose").asText());
        assertEquals(policy, copy.path("retentionPolicy").asText());
        assertTrue(copy.path("legalHold").isBoolean(), "a retained copy must expose a boolean legal hold state");
        assertEquals(legalHold, copy.path("legalHold").asBoolean());
        assertEquals(actor, copy.path("actorId").asText());
        assertEquals(checksum, copy.path("contentChecksum").asText());
    }

    @Test
    void legalHoldPolicyRetainsPurposeAndActorInLedgerAndDatabase() throws Exception {
        String id = orderId(ORG_A, IDX_LEGAL_HOLD);
        assertEquals(HttpStatus.OK, accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "1").getStatusCode());

        JsonNode retained = view(ORG_A, id, "lab-verifier").at("/retained");
        assertEquals(1, retained.size());
        assertRetainedCopy(retained.get(0), 1, "RESULT_REVIEW", "LEGAL_HOLD", true,
                identityId(ORG_A, subject("lab-verifier", ORG_A)), CHECKSUM);

        RetainedRow row = retainedRow(ORG_A, id, 1);
        assertEquals("RESULT_REVIEW", row.purpose());
        assertEquals("LEGAL_HOLD", row.policy());
        assertTrue(row.legalHold());
        assertEquals(identityId(ORG_A, subject("lab-verifier", ORG_A)), row.actorId());
        assertEquals(CHECKSUM, row.checksum());
    }

    @Test
    void standardPolicyRetainsACopyWithoutLegalHold() throws Exception {
        String id = orderId(ORG_A, IDX_STANDARD);
        assertEquals(HttpStatus.OK, accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "STANDARD", "1").getStatusCode());

        JsonNode retained = view(ORG_A, id, "lab-verifier").at("/retained");
        assertEquals(1, retained.size());
        assertRetainedCopy(retained.get(0), 1, "RESULT_REVIEW", "STANDARD", false,
                identityId(ORG_A, subject("lab-verifier", ORG_A)), CHECKSUM);
        assertEquals("STANDARD", retainedRow(ORG_A, id, 1).policy());
        assertFalse(retainedRow(ORG_A, id, 1).legalHold());
    }

    @Test
    void extendedPolicyIsApprovedWithoutLegalHold() throws Exception {
        String id = orderId(ORG_A, IDX_EXTENDED);
        assertEquals(HttpStatus.OK, accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "EXTENDED", "1").getStatusCode());

        JsonNode retained = view(ORG_A, id, "lab-verifier").at("/retained");
        assertEquals(1, retained.size());
        assertRetainedCopy(retained.get(0), 1, "RESULT_REVIEW", "EXTENDED", false,
                identityId(ORG_A, subject("lab-verifier", ORG_A)), CHECKSUM);
        assertEquals("EXTENDED", retainedRow(ORG_A, id, 1).policy());
        assertFalse(retainedRow(ORG_A, id, 1).legalHold());
    }

    @Test
    void acceptedChangeReRetainsNextRevisionUnderOriginalPolicy() throws Exception {
        String id = orderId(ORG_A, IDX_CHANGE_RETAIN);
        String verifier = subject("lab-verifier", ORG_A);
        assertEquals(HttpStatus.OK, accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "STANDARD", "1").getStatusCode());
        ResponseEntity<String> change = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                subject("clinician", ORG_A), ORG_A, "2");
        assertEquals(HttpStatus.OK, change.getStatusCode(), change.getBody());
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"accept\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        JsonNode retained = view(ORG_A, id, "lab-verifier").at("/retained");
        assertEquals(2, retained.size());
        assertRetainedCopy(retained.get(0), 1, "RESULT_REVIEW", "STANDARD", false,
                identityId(ORG_A, verifier), CHECKSUM);
        assertRetainedCopy(retained.get(1), 2, "RESULT_REVIEW", "STANDARD", false,
                identityId(ORG_A, verifier), NEXT_CHECKSUM);
        assertEquals(2L, retainedCount(ORG_A, id));
        assertEquals("STANDARD", retainedRow(ORG_A, id, 2).policy());
        assertFalse(retainedRow(ORG_A, id, 2).legalHold());
    }

    @Test
    void unapprovedRetentionPolicyIsRefusedAndNoCopyIsWritten() throws Exception {
        String id = orderId(ORG_A, IDX_FORBIDDEN_POLICY);
        ResponseEntity<String> refused = accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "FOREVER", "1");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_RETENTION_POLICY", text(refused, "/code"));
        assertEquals(0L, retainedCount(ORG_A, id));
    }

    @Test
    void actorWithoutRecipientRoleCannotApplyRetentionPolicy() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);
        ResponseEntity<String> refused = accept(id, "reception", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "1");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", text(refused, "/code"));
        assertEquals(0L, retainedCount(ORG_A, id));
    }

    @Test
    void otherTenantCannotApplyRetentionPolicy() throws Exception {
        String id = orderId(ORG_B, IDX_CROSS_TENANT);
        ResponseEntity<String> refused = accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "1");
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", text(refused, "/code"));
        assertEquals(0L, retainedCount(ORG_B, id));
    }

    @Test
    void staleVersionCannotApplyRetentionPolicy() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        ResponseEntity<String> refused = accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "9");
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", text(refused, "/code"));
        assertEquals(0L, retainedCount(ORG_A, id));
    }

    @Test
    void secondAcceptCannotCreateAnotherRetainedCopy() throws Exception {
        String id = orderId(ORG_A, IDX_DOUBLE_ACCEPT);
        assertEquals(HttpStatus.OK, accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "1").getStatusCode());
        ResponseEntity<String> refused = accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "2");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", text(refused, "/code"));
        assertEquals(1L, retainedCount(ORG_A, id));
    }

    @Test
    void rejectedChangeLeavesRetainedCopyUnchanged() throws Exception {
        String id = orderId(ORG_A, IDX_CHANGE_REJECT);
        assertEquals(HttpStatus.OK, accept(id, "lab-verifier", ORG_A,
                "RESULT_REVIEW", "LEGAL_HOLD", "1").getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                subject("clinician", ORG_A), ORG_A, "2").getStatusCode());
        ResponseEntity<String> response = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"reject\",\"reason\":\"wrong unit\"}",
                subject("lab-verifier", ORG_A), ORG_A, "3");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        JsonNode retained = view(ORG_A, id, "lab-verifier").at("/retained");
        assertEquals(1, retained.size());
        assertRetainedCopy(retained.get(0), 1, "RESULT_REVIEW", "LEGAL_HOLD", true,
                identityId(ORG_A, subject("lab-verifier", ORG_A)), CHECKSUM);
        assertEquals(1L, retainedCount(ORG_A, id));
        assertEquals(CHECKSUM, retainedRow(ORG_A, id, 1).checksum());
    }
}
