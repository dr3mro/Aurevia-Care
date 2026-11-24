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
 * XCH-03 clause 5 HTTP acceptance on real PostgreSQL. An approved retention
 * policy and a legal hold are captured on the recipient's retained copy; an
 * unapproved policy, a role without accept permission, a peer tenant, a stale
 * version, a missing purpose and a forbidden state are all refused before any
 * retained copy is written.
 *
 * <p>What this class does not prove is time-based expiry or the suspension of
 * expiry by a legal hold: the policy fields are declared and persisted but no
 * enforcement job exists. That gap is designed in the companion clause document
 * so a later task can add the missing behaviour and test.</p>
 *
 * <p>Every fixture is synthetic and both tenants use the same local order codes,
 * so isolation is exercised at the forced-RLS tenant boundary rather than by
 * giving the peer different names. Each test owns its own order or orders, never
 * shared with another test, because the ledgers are append-only and a command
 * advances the order version.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_xch03_retention_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ApplyLawPolicyRecipientRetentionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_xch03_retention_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "a".repeat(64);
    private static final String VERIFIER_A = "lab-verifier-1111";
    private static final String VERIFIER_B = "lab-verifier-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // One requested order per test: a command advances the version and the ledgers
    // are append-only, so a reused order would leak state between tests.
    private static final int IDX_LEGAL_HOLD = 1;
    private static final int IDX_STANDARD = 2;
    private static final int IDX_POLICY = 3;
    private static final int IDX_ROLE = 4;
    private static final int IDX_TENANT = 5;
    private static final int IDX_STALE = 6;
    private static final int IDX_PURPOSE = 7;
    private static final int IDX_STATE = 8;
    private static final int ORDER_COUNT = 8;

    private RestClient rest;

    /** Point the HTTP client at the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a fresh migrated database with two synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Remove the dedicated database so the next run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /**
     * Seed one tenant with the same local codes as its peer and its own members.
     *
     * @param statement migration-owner statement in the dedicated test database
     * @param org       synthetic organization identifier
     * @throws Exception when a synthetic fixture cannot be inserted
     */
    private static void seedTenant(Statement statement, String org) throws Exception {
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

    /** Both tenants use the same local order codes and id suffix: RLS is the only separator. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    /** Read the synthetic patient id in the active tenant with RLS bound. */
    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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

    /** Bind the transaction-local tenant and actor for a direct database assertion. */
    private static void bind(Connection connection, String org, String subject) throws Exception {
        String subjectSuffix = ORG_A.equals(org) ? "1111" : "2222";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject + "-" + subjectSuffix);
            statement.execute();
        }
    }

    /** Count rows with a tenant-bound direct query, bypassing the HTTP surface. */
    private static long scalar(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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

    /** Build the synthetic accept request headers for one actor and tenant. */
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

    /** Send one request and return the raw response so an error status can be asserted. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, ifMatch)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a response body into a JSON tree. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /** Read one text field from a JSON response body. */
    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer);
        return value.isNull() ? null : value.asText();
    }

    /** Build a synthetic accept body with an approved or unapproved policy code. */
    private static String acceptBody(String patient, String assignee, String policy) {
        return "{\"localPatientId\":\"" + patient + "\",\"assignee\":\"" + assignee
                + "\",\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"" + policy + "\"}";
    }

    /** A legal hold and an approved policy are captured on the retained copy. */
    @Test
    void acceptAppliesApprovedPolicyAndLegalHoldToRetainedCopy() throws Exception {
        String heldOrder = orderId(ORG_A, IDX_LEGAL_HOLD);
        String standardOrder = orderId(ORG_A, IDX_STANDARD);
        String patient = patientId(ORG_A);

        ResponseEntity<String> held = send(HttpMethod.POST, "/api/v1/xch03/orders/" + heldOrder + "/accept",
                acceptBody(patient, "clinician-1111", "LEGAL_HOLD"), VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, held.getStatusCode(), held.getBody());
        assertEquals("accepted", text(held, "/state"));

        JsonNode heldView = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + heldOrder,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(1, heldView.at("/retained").size());
        assertEquals("LEGAL_HOLD", heldView.at("/retained/0/retentionPolicy").asText());
        assertTrue(heldView.at("/retained/0/legalHold").asBoolean());
        assertEquals("RESULT_REVIEW", heldView.at("/retained/0/permittedPurpose").asText());

        ResponseEntity<String> standard = send(HttpMethod.POST, "/api/v1/xch03/orders/" + standardOrder + "/accept",
                acceptBody(patient, "clinician-1111", "STANDARD"), VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, standard.getStatusCode(), standard.getBody());
        JsonNode standardView = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + standardOrder,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(1, standardView.at("/retained").size());
        assertEquals("STANDARD", standardView.at("/retained/0/retentionPolicy").asText());
        assertFalse(standardView.at("/retained/0/legalHold").asBoolean());

        assertEquals(1L, scalar(ORG_A, "SELECT count(*) FROM public.xch03_retained_copy "
                + "WHERE order_id = '" + heldOrder + "' AND retention_policy = 'LEGAL_HOLD' AND legal_hold = true"));
        assertEquals(1L, scalar(ORG_A, "SELECT count(*) FROM public.xch03_retained_copy "
                + "WHERE order_id = '" + standardOrder + "' AND retention_policy = 'STANDARD' AND legal_hold = false"));
    }

    /** An unapproved retention policy is refused before a retained copy is written. */
    @Test
    void unapprovedRetentionPolicyIsRefusedBeforeAnyRetainedCopy() throws Exception {
        String id = orderId(ORG_A, IDX_POLICY);
        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "UNAPPROVED"), VERIFIER_A, ORG_A, "1");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_RETENTION_POLICY", text(refused, "/code"));
        assertEquals(0L, scalar(ORG_A,
                "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + id + "'"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(0, view.at("/retained").size());
        assertEquals("requested", view.at("/order/state").asText());
        assertEquals(1, view.at("/order/version").asInt());
    }

    /** A role without the accept permission cannot retain a copy. */
    @Test
    void roleWithoutExchangeAcceptCannotRetain() throws Exception {
        String id = orderId(ORG_A, IDX_ROLE);
        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "STANDARD"), "reception-1111", ORG_A, "1");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", text(refused, "/code"));
        assertEquals(0L, scalar(ORG_A,
                "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + id + "'"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(0, view.at("/retained").size());
        assertEquals("requested", view.at("/order/state").asText());
    }

    /** A peer tenant cannot see or retain another tenant's order under forced RLS. */
    @Test
    void peerTenantCannotSeeOrRetainAnotherTenantsOrder() throws Exception {
        String peerOrder = orderId(ORG_B, IDX_TENANT);
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, "/api/v1/xch03/orders/" + peerOrder,
                null, VERIFIER_A, ORG_A, null).getStatusCode(),
                "tenant A cannot read tenant B's order even though the local code is identical");

        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/xch03/orders/" + peerOrder + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "LEGAL_HOLD"), VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals(0L, scalar(ORG_B,
                "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + peerOrder + "'"));

        JsonNode peerView = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + peerOrder,
                null, VERIFIER_B, ORG_B, null));
        assertEquals("requested", peerView.at("/order/state").asText());
        assertEquals(0, peerView.at("/retained").size());
    }

    /** A stale If-Match version cannot overwrite a retained copy. */
    @Test
    void staleVersionCannotOverwriteARetainedCopy() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "LEGAL_HOLD"), VERIFIER_A, ORG_A, "9");
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", text(refused, "/code"));
        assertEquals(0L, scalar(ORG_A,
                "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + id + "'"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(0, view.at("/retained").size());
        assertEquals("requested", view.at("/order/state").asText());
    }

    /** A missing permitted purpose is refused before a retained copy is written. */
    @Test
    void missingPermittedPurposeIsRefusedBeforeAnyRetainedCopy() throws Exception {
        String id = orderId(ORG_A, IDX_PURPOSE);
        String body = "{\"localPatientId\":\"" + patientId(ORG_A) + "\",\"assignee\":\"clinician-1111\","
                + "\"permittedPurpose\":\"  \",\"retentionPolicy\":\"STANDARD\"}";
        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                body, VERIFIER_A, ORG_A, "1");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REQUIRED", text(refused, "/code"));
        assertEquals(0L, scalar(ORG_A,
                "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + id + "'"));
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(0, view.at("/retained").size());
        assertEquals("requested", view.at("/order/state").asText());
    }

    /** An already accepted order cannot apply a second retention policy. */
    @Test
    void alreadyAcceptedOrderCannotApplyAnotherRetentionPolicy() throws Exception {
        String id = orderId(ORG_A, IDX_STATE);
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "STANDARD"), VERIFIER_A, ORG_A, "1")
                .getStatusCode());

        ResponseEntity<String> refused = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "LEGAL_HOLD"), VERIFIER_A, ORG_A, "2");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", text(refused, "/code"));

        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, VERIFIER_A, ORG_A, null));
        assertEquals(1, view.at("/retained").size());
        assertEquals("STANDARD", view.at("/retained/0/retentionPolicy").asText());
        assertFalse(view.at("/retained/0/legalHold").asBoolean());
        assertEquals(1L, scalar(ORG_A,
                "SELECT count(*) FROM public.xch03_retained_copy WHERE order_id = '" + id + "'"));
    }

}
