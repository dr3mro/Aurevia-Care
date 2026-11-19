package com.aurevia.smartclinic.it.s_g2_03;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

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
 * S-G2-03 acceptance (XCH-03) against a real PostgreSQL: isolated tenants with
 * the same local order code, accept/reject/clarify, linked change and
 * cancellation responses, revocation blocking retrieval, append-only ledgers
 * and audit. All fixtures are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class Xch03ExchangeTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch03_it";
    static final String CHECKSUM = "a".repeat(64);
    static final String NEXT_CHECKSUM = "b".repeat(64);
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch03_it OWNER smartclinic_migrate");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch03_it WITH (FORCE)");
        }
    }

    /** One tenant with the same local order code as the peer, plus its members. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        for (String member : new String[] {"lab-verifier", "clinician", "reception", "auditor", "admin"}) {
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
        statement.execute("INSERT INTO public.xch03_order "
                + "(organization_id, id, order_code, state, revision, content_checksum, created_by) "
                + "VALUES ('" + org + "', '" + orderId(org) + "', 'LAB-REQ-1', 'requested', 1, '" + CHECKSUM
                + "', (SELECT id FROM public.user_identity WHERE subject = 'admin-" + shortName + "'))");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.xch03_revision "
                + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                + "VALUES ('" + org + "', gen_random_uuid(), '" + orderId(org)
                + "', 1, NULL, '" + CHECKSUM + "')");
        statement.execute("INSERT INTO public.xch03_event "
                + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                + "VALUES ('" + org + "', gen_random_uuid(), '" + orderId(org)
                + "', 1, 'received', NULL, 'requested')");
    }

    /** Both tenants use the same opaque order id: RLS is the only separator. */
    private static String orderId(String org) {
        return ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                : "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
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

    @Test
    void tenantsWithIdenticalOrderCodesStayIsolated() throws Exception {
        String subjectA = "lab-verifier-" + suffix(ORG_A);
        String subjectB = "lab-verifier-" + suffix(ORG_B);
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, "/api/v1/xch03/orders/" + orderId(ORG_B),
                null, subjectA, ORG_A, null).getStatusCode(),
                "tenant A must read only its own order with the same local code");

        ResponseEntity<String> concealed = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + orderId(ORG_B) + "/reject",
                "{\"reason\":\"wrong tenant\"}", subjectA, ORG_A, "1");
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());

        String accept = "{\"localPatientId\":\"" + patientId(ORG_A) + "\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"STANDARD\"}";
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + orderId(ORG_A) + "/accept", accept, subjectA, ORG_A, "1")
                .getStatusCode());
        assertEquals("requested", text(send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + orderId(ORG_B), null, subjectB, ORG_B, null), "/order/state"));
        assertEquals(1L, scalar(ORG_A, "SELECT count(*) FROM public.xch03_order WHERE order_code = 'LAB-REQ-1'"));
        assertEquals(1L, scalar(ORG_B, "SELECT count(*) FROM public.xch03_order WHERE order_code = 'LAB-REQ-1'"));
    }

    @Test
    void acceptRejectClarifyAndRetainPurposeWithAppendOnlyHistory() throws Exception {
        String id = orderId(ORG_A);
        String subject = "lab-verifier-" + suffix(ORG_A);
        String accept = "{\"localPatientId\":\"" + patientId(ORG_A) + "\",\"assignee\":\"clinician-1111\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"LEGAL_HOLD\"}";
        ResponseEntity<String> accepted = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                accept, subject, ORG_A, "1");
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("accepted", text(accepted, "/state"));
        assertEquals("2", text(accepted, "/version"));

        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id, null, subject, ORG_A, null));
        assertEquals(1, view.at("/retained").size());
        assertEquals("RESULT_REVIEW", view.at("/retained/0/permittedPurpose").asText());
        assertEquals("LEGAL_HOLD", view.at("/retained/0/retentionPolicy").asText());
        assertTrue(view.at("/retained/0/legalHold").asBoolean());
        assertEquals("lab-verifier-1111", view.at("/retained/0/actorId").asText());
        assertEquals(2, view.at("/history").size());

        ResponseEntity<String> clarified = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + orderId(ORG_B) + "/clarify",
                "{\"question\":\"Which specimen?\"}", "lab-verifier-" + suffix(ORG_B), ORG_B, "1");
        assertEquals(HttpStatus.OK, clarified.getStatusCode(), clarified.getBody());
        assertEquals("clarification_requested", text(clarified, "/state"));

        ResponseEntity<String> rejected = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + orderId(ORG_B) + "/reject",
                "{\"reason\":\"duplicate order\"}", "lab-verifier-" + suffix(ORG_B), ORG_B, "2");
        assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode(),
                "a clarified order must not be rejected from the requested branch");
        assertEquals("1", auditCount(ORG_A, "EXCHANGE_ACCEPT", id));
    }

    @Test
    void linkedChangeNeedsRecipientResponseAndNeverRewritesTheAcceptedSnapshot() throws Exception {
        String id = orderId(ORG_A);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        String accept = "{\"localPatientId\":\"" + patientId(ORG_A) + "\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"STANDARD\"}";
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept",
                accept, verifier, ORG_A, "1").getStatusCode());

        ResponseEntity<String> changed = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/propose-change",
                "{\"changeSummary\":\"corrected unit\",\"contentChecksum\":\"" + NEXT_CHECKSUM + "\"}",
                clinician, ORG_A, "2");
        assertEquals(HttpStatus.OK, changed.getStatusCode(), changed.getBody());
        assertEquals("change_pending", text(changed, "/state"));

        JsonNode revisions = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null)).at("/revisions");
        assertEquals(2, revisions.size());
        assertEquals(1, revisions.at("/0/revision").asInt());
        assertEquals(2, revisions.at("/1/revision").asInt());
        assertEquals(1, revisions.at("/1/previousRevision").asInt());
        assertEquals(CHECKSUM, revisions.at("/0/contentChecksum").asText(),
                "the accepted snapshot must remain unchanged");

        ResponseEntity<String> responded = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/change-response",
                "{\"decision\":\"accept\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, responded.getStatusCode(), responded.getBody());
        assertEquals("accepted", text(responded, "/state"));
        assertEquals(2, json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_A, null)).at("/retained").size());
    }

    @Test
    void cancellationIsARequestUntilTheRecipientResponds() throws Exception {
        String id = orderId(ORG_A);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        String clinician = "clinician-" + suffix(ORG_A);
        String accept = "{\"localPatientId\":\"" + patientId(ORG_A) + "\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"STANDARD\"}";
        send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept", accept, verifier, ORG_A, "1");

        ResponseEntity<String> requested = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/request-cancellation",
                "{\"reason\":\"order entered in error\"}", clinician, ORG_A, "2");
        assertEquals(HttpStatus.OK, requested.getStatusCode(), requested.getBody());
        assertEquals("cancellation_pending", text(requested, "/state"));

        ResponseEntity<String> declined = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/cancellation-response",
                "{\"decision\":\"decline\",\"reason\":\"work in progress\"}", verifier, ORG_A, "3");
        assertEquals(HttpStatus.OK, declined.getStatusCode(), declined.getBody());
        assertEquals("accepted", text(declined, "/state"));

        ResponseEntity<String> requestedAgain = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/request-cancellation",
                "{\"reason\":\"patient withdrew consent\"}", clinician, ORG_A, "4");
        assertEquals(HttpStatus.OK, requestedAgain.getStatusCode(), requestedAgain.getBody());
        ResponseEntity<String> confirmed = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/cancellation-response",
                "{\"decision\":\"confirm\"}", verifier, ORG_A, "5");
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("cancelled", text(confirmed, "/state"));
    }

    @Test
    void revocationBlocksRetrievalAndRecordsDownstreamNotification() throws Exception {
        String id = orderId(ORG_B);
        String verifier = "lab-verifier-" + suffix(ORG_B);
        String clinician = "clinician-" + suffix(ORG_B);
        String accept = "{\"localPatientId\":\"" + patientId(ORG_B) + "\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"STANDARD\"}";
        send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/accept", accept, verifier, ORG_B, "1");
        assertEquals(HttpStatus.OK, send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/retrieve", null, verifier, ORG_B, null).getStatusCode());

        ResponseEntity<String> revoked = send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/revoke",
                "{\"reason\":\"disclosure revoked\",\"retentionPolicy\":\"LEGAL_HOLD\","
                        + "\"target\":\"partner-lab\",\"channel\":\"secure-email\",\"eventId\":\"evt-xch03-1\"}",
                clinician, ORG_B, "2");
        assertEquals(HttpStatus.OK, revoked.getStatusCode(), revoked.getBody());
        assertEquals("revoked", text(revoked, "/state"));

        ResponseEntity<String> blocked = send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/retrieve", null, verifier, ORG_B, null);
        assertEquals(410, blocked.getStatusCode().value(), blocked.getBody());
        assertEquals("RETRIEVAL_REVOKED", text(blocked, "/code"));

        JsonNode view = json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id,
                null, verifier, ORG_B, null));
        assertEquals(1, view.at("/notifications").size());
        assertEquals("evt-xch03-1", view.at("/notifications/0/eventId").asText());
        assertEquals("partner-lab", view.at("/notifications/0/target").asText());
        assertEquals("NOTIFIED", view.at("/notifications/0/outcome").asText());
        assertEquals("1", auditCount(ORG_B, "EXCHANGE_REVOKE", id));
    }

    @Test
    void versionPreconditionsAuthorizationAndInputValidationAreEnforced() throws Exception {
        // checked exceptions from the JDBC assertions are declared on this method
        String id = orderId(ORG_A);
        String verifier = "lab-verifier-" + suffix(ORG_A);
        assertEquals(428, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"why\"}", verifier, ORG_A, null).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"why\"}", verifier, ORG_A, "bad").getStatusCode().value());
        assertEquals(412, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"why\"}", verifier, ORG_A, "7").getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/xch03/orders/" + id + "/reject",
                "{}", verifier, ORG_A, "1").getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject", "{\"reason\":\"why\"}", null, ORG_A, "1")
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject", "{\"reason\":\"why\"}",
                "reception-" + suffix(ORG_A), ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET,
                "/api/v1/xch03/orders/44444444-4444-4444-4444-444444444444",
                null, verifier, ORG_A, null).getStatusCode());
        assertTrue(auditCount(ORG_A, "EXCHANGE_REJECT", id) >= 1,
                "an authorization denial must be audited with the order id");

        // Append-only ledgers reject a mutation even by the migration owner.
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin");
            boolean refused = false;
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE public.xch03_event SET reason = 'tampered'");
            } catch (Exception exception) {
                refused = true;
            } finally {
                connection.rollback();
            }
            assertTrue(refused, "response history must be append-only");
        }
    }

    private static long auditCount(String organization, String action, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE action = ? AND resource_id = ?")) {
                statement.setString(1, action);
                statement.setString(2, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
