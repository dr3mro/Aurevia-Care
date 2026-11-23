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
 * XCH-03 clause 4 proof through the real HTTP entry point: revoking a release
 * blocks every later sender-hosted retrieval and records an append-only
 * downstream notification.
 *
 * <p>The positive case accepts a release, retrieves it once, revokes it through
 * the privileged sender role, then asserts both halves of the clause: the next
 * retrieval is refused with 410, and the notification row (with its caller-supplied
 * event id) is durably recorded and visible on the order view. Three negative
 * cases refute the rule in reverse: a recipient role and a stale version must not
 * revoke, and another tenant must not even see the order; in each case the
 * release stays retrievable and no notification is written. Every test owns a
 * distinct order because the response ledgers are append-only. All fixtures are
 * synthetic; the dedicated database is created and dropped by this class.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_revoke_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class RevocationBlocksFutureSenderHostedRetrievalRecordsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_revoke_clause_it";
    private static final String CHECKSUM = "a".repeat(64);
    private static final int ORDER_COUNT = 4;
    private static final int IDX_REVOKE = 1;
    private static final int IDX_WRONG_ROLE = 2;
    private static final int IDX_STALE = 3;
    private static final int IDX_TENANT = 4;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Create an isolated database and apply the production migrations once. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            // The application datasource connects as smartclinic_app; both roles must
            // exist before Flyway creates the tenant-owned, forced-RLS tables.
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_revoke_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch03_revoke_clause_it OWNER smartclinic_migrate");
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

    /** Remove the dedicated database so the next run starts from a clean schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_revoke_clause_it WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Seed one tenant, its synthetic members, a patient and one order per test. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        // The grantor identity must exist before the first role assignment:
        // V4's trigger resolves granted_by from app.actor in the active tenant.
        String[][] members = {
                {"admin", "tenant_admin"},
                {"lab-verifier", "lab_verifier"},
                {"clinician", "clinician"},
                {"reception", "reception"}
        };
        for (String[] member : members) {
            String subject = member[0] + "-" + shortName;
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + org + "', '" + subject + "', 'Synthetic " + member[0] + "')");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
            statement.execute("INSERT INTO public.role_assignment "
                    + "(organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + org + "', m.id, '" + member[1] + "', u.id FROM public.membership m "
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

    /** Both tenants use the same local order codes: RLS is the only separator. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    private static String suffix(String org) {
        return ORG_A.equals(org) ? "1111" : "2222";
    }

    /** Read the tenant's synthetic patient through the migration owner fold. */
    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next(), "missing synthetic patient");
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    /** Bind the tenant and actor for a direct ledger assertion under FORCE RLS. */
    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject + "-" + suffix(org));
            statement.execute();
        }
    }

    /** Count the durable notification rows for one caller-supplied event id. */
    private static long notificationCount(String org, String eventId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.xch03_notification WHERE event_id = ?")) {
                statement.setString(1, eventId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "notification count query returned no row");
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot count synthetic notification", exception);
        }
    }

    /** Count the audit rows that record an exchange command's outcome. */
    private static long auditCount(String org, String action, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE action = ? AND resource_id = ?")) {
                statement.setString(1, action);
                statement.setString(2, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "audit count query returned no row");
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot count synthetic audit event", exception);
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

    private String acceptBody(String org) {
        return "{\"localPatientId\":\"" + patientId(org) + "\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"STANDARD\"}";
    }

    private static String revokeBody(String eventId) {
        return "{\"reason\":\"disclosure revoked\",\"retentionPolicy\":\"LEGAL_HOLD\","
                + "\"target\":\"partner-lab\",\"channel\":\"secure-email\",\"eventId\":\"" + eventId + "\"}";
    }

    @Test
    void revocationBlocksFutureSenderHostedRetrievalAndRecordsDownstreamNotification() throws Exception {
        String id = orderId(ORG_A, IDX_REVOKE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        String eventId = "evt-xch03-revoke-clause";
        String uri = "/api/v1/xch03/orders/" + id;

        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept", acceptBody(ORG_A),
                verifier, ORG_A, "1").getStatusCode());
        ResponseEntity<String> before = send(HttpMethod.GET, uri + "/retrieve", null, verifier, ORG_A, null);
        assertEquals(HttpStatus.OK, before.getStatusCode(), before.getBody());
        assertEquals(CHECKSUM, text(before, "/contentChecksum"));

        // Only the sender's clinician role is allowed to revoke; the caller's
        // event id becomes the durable deduplication key on the notification.
        ResponseEntity<String> revoked = send(HttpMethod.POST, uri + "/revoke", revokeBody(eventId),
                clinician, ORG_A, "2");
        assertEquals(HttpStatus.OK, revoked.getStatusCode(), revoked.getBody());
        assertEquals("revoked", text(revoked, "/state"));

        ResponseEntity<String> blocked = send(HttpMethod.GET, uri + "/retrieve", null, verifier, ORG_A, null);
        assertEquals(410, blocked.getStatusCode().value(), blocked.getBody());
        assertEquals("RETRIEVAL_REVOKED", text(blocked, "/code"));

        JsonNode view = json(send(HttpMethod.GET, uri, null, verifier, ORG_A, null));
        assertEquals(1, view.at("/notifications").size());
        assertEquals(eventId, view.at("/notifications/0/eventId").asText());
        assertEquals("partner-lab", view.at("/notifications/0/target").asText());
        assertEquals("secure-email", view.at("/notifications/0/channel").asText());
        assertEquals("NOTIFIED", view.at("/notifications/0/outcome").asText());
        assertEquals(1L, notificationCount(ORG_A, eventId));
        assertEquals(1L, auditCount(ORG_A, "EXCHANGE_REVOKE", id));
    }

    @Test
    void aRecipientRoleCannotRevokeAndRetrievalStillSucceeds() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String eventId = "evt-xch03-wrong-role";
        String uri = "/api/v1/xch03/orders/" + id;

        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept", acceptBody(ORG_A),
                verifier, ORG_A, "1").getStatusCode());
        // The current version is supplied, so the 403 is attributable to the role
        // and not to a stale precondition (which is checked after authorization).
        ResponseEntity<String> refused = send(HttpMethod.POST, uri + "/revoke", revokeBody(eventId),
                verifier, ORG_A, "2");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", text(refused, "/code"));

        JsonNode view = json(send(HttpMethod.GET, uri, null, verifier, ORG_A, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(0, view.at("/notifications").size());
        assertEquals(0L, notificationCount(ORG_A, eventId));
        ResponseEntity<String> retrieval = send(HttpMethod.GET, uri + "/retrieve", null, verifier, ORG_A, null);
        assertEquals(HttpStatus.OK, retrieval.getStatusCode(), retrieval.getBody());
        assertEquals(CHECKSUM, text(retrieval, "/contentChecksum"));
    }

    @Test
    void staleVersionRevokeIsRefusedAndRetrievalStillSucceeds() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        String eventId = "evt-xch03-stale";
        String uri = "/api/v1/xch03/orders/" + id;

        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept", acceptBody(ORG_A),
                verifier, ORG_A, "1").getStatusCode());
        ResponseEntity<String> stale = send(HttpMethod.POST, uri + "/revoke", revokeBody(eventId),
                clinician, ORG_A, "1");
        assertEquals(412, stale.getStatusCode().value(), stale.getBody());
        assertEquals("STALE_VERSION", text(stale, "/code"));

        JsonNode view = json(send(HttpMethod.GET, uri, null, verifier, ORG_A, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(0, view.at("/notifications").size());
        assertEquals(0L, notificationCount(ORG_A, eventId));
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, uri + "/retrieve", null, verifier, ORG_A, null)
                .getStatusCode());
    }

    @Test
    void revokeForAnotherTenantIsConcealedAndItsReleaseStaysRetrievable() throws Exception {
        String id = orderId(ORG_B, IDX_TENANT);
        String verifier = "lab-verifier-" + suffix(ORG_B);
        String clinicianOfOtherTenant = "clinician-" + suffix(ORG_A);
        String eventId = "evt-xch03-wrong-tenant";
        String uri = "/api/v1/xch03/orders/" + id;

        assertEquals(HttpStatus.OK, send(HttpMethod.POST, uri + "/accept", acceptBody(ORG_B),
                verifier, ORG_B, "1").getStatusCode());
        // The actor is a valid clinician in its own tenant, so authorization
        // passes; forced RLS in findForUpdate conceals the victim's order.
        ResponseEntity<String> concealed = send(HttpMethod.POST, uri + "/revoke", revokeBody(eventId),
                clinicianOfOtherTenant, ORG_A, "2");
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());
        assertEquals("NOT_FOUND", text(concealed, "/code"));

        JsonNode view = json(send(HttpMethod.GET, uri, null, verifier, ORG_B, null));
        assertEquals("accepted", view.at("/order/state").asText());
        assertEquals(0, view.at("/notifications").size());
        assertEquals(0L, notificationCount(ORG_B, eventId));
        assertEquals(0L, notificationCount(ORG_A, eventId));
        ResponseEntity<String> retrieval = send(HttpMethod.GET, uri + "/retrieve", null, verifier, ORG_B, null);
        assertEquals(HttpStatus.OK, retrieval.getStatusCode(), retrieval.getBody());
        assertEquals(CHECKSUM, text(retrieval, "/contentChecksum"));
    }
}
