package com.aurevia.it.exchangeorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * S-G2-01 acceptance (XCH-01) against a real PostgreSQL.
 *
 * <p>Synthetic organizations A, B and C share the same local order code. A is
 * the sender, B the approved destination and C an unrelated tenant. The
 * assertions cover authorization, the immutable released snapshot, the
 * disclosure ledger, the bounded destination work item and the absence of any
 * general chart access gained by sharing an order.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_exchange_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class OrderExchangeIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_exchange_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String GRANT = "55555555-5555-5555-5555-555555555555";
    static final String EXPIRED_GRANT = "55555555-5555-5555-5555-555555555556";

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_exchange_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_exchange_it OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C}) {
                seedOrganization(statement, org);
            }
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
            seedMember(statement, ORG_B, "reception-b", "reception");
            seedMember(statement, ORG_C, "clinician-c", "clinician");
            seedAuthorization(statement, ORG_A, GRANT, "lab review", 60, "1 day");
            seedAuthorization(statement, ORG_A, EXPIRED_GRANT, "lab review", 60, "-1 day");
            seedUnmatchedPatient(statement);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_exchange_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedAuthorization(Statement statement, String org, String id,
                                          String purpose, int max, String offset) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-a'");
        String effective = offset.startsWith("-") ? "now() - interval '2 days'" : "now() - interval '1 day'";
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + org + "', '" + id + "', 'CBC', '"
                + ORG_B + "', '" + purpose + "', " + max + ", " + effective + ", "
                + "now() + interval '" + offset + "', true)");
    }

    /** A second receiver patient with a different MRN, used for the wrong-patient denial. */
    private static void seedUnmatchedPatient(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_B + "'");
        statement.execute("SET app.actor = 'admin-b'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_B + "', 1, 'MRN-2', 'CLINIC', 'Other Synthetic Patient', "
                + "'other synthetic patient', '1991-06-21')");
    }

    private static String patientId(String org, String mrn) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = '" + mrn + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static long scalar(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static String scalarText(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void execute(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        HttpHeaders headers = headers(subject, organization);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer, exception);
        }
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response", exception);
        }
    }

    private String workItemId(String orderId, String subject, String organization) {
        ResponseEntity<String> inbox = send(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                subject, organization);
        assertEquals(HttpStatus.OK, inbox.getStatusCode(), inbox.getBody());
        for (JsonNode item : json(inbox)) {
            if (orderId.equals(item.at("/orderRef").asText())) {
                return item.at("/id").asText();
            }
        }
        throw new IllegalStateException("no work item projected for the released order");
    }

    private static String orderBody(String patient, String code, String destination, String purpose,
                                    String grant, int duration) {
        return "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"ENC-SYN-1\","
                + "\"orderCode\":\"" + code + "\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"Synthetic CBC question\",\"destinationOrg\":\""
                + destination + "\",\"purpose\":\"" + purpose + "\",\"authorizationId\":\""
                + grant + "\",\"responsibleClinician\":\"dr-a\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":" + duration + "}";
    }

    private ResponseEntity<String> createOrder(String code, String destination, String purpose,
                                               String grant, int duration, String subject, String org) {
        return send(HttpMethod.POST, "/api/v1/orders",
                orderBody(patientId(org), code, destination, purpose, grant, duration), subject, org);
    }

    @Test
    void releasesAnAuthorizedOrderWithSnapshotDisclosureAndReceiverWorkItem() throws Exception {
        ResponseEntity<String> created = createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");
        assertEquals("1", text(created, "/version"));
        assertEquals("clinician-a", text(created, "/sourceActor"));
        assertEquals(ORG_B, text(created, "/destinationOrg"));
        assertEquals(36, text(created, "/snapshotFingerprint").length());

        assertEquals(1, scalar(ORG_A, "admin-a", "SELECT count(*) FROM public.exchange_order_snapshot "
                + "WHERE order_id = '" + id + "'"));
        String snapshot = scalarText(ORG_A, "admin-a", "SELECT content FROM public.exchange_order_snapshot "
                + "WHERE order_id = '" + id + "'");
        assertTrue(snapshot.contains("Synthetic CBC question"));
        assertTrue(snapshot.contains("CBC"));
        assertEquals(1, scalar(ORG_A, "admin-a", "SELECT count(*) FROM public.exchange_disclosure "
                + "WHERE order_id = '" + id + "' AND recipient_org = '" + ORG_B + "'"));
        assertEquals(1, scalar(ORG_A, "admin-a", "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'ORDER_DISCLOSURE' AND resource_id = '" + id + "'"));
        assertEquals(1, scalar(ORG_B, "admin-b", "SELECT count(*) FROM public.exchange_work_item "
                + "WHERE order_ref = '" + id + "' AND destination_org = '" + ORG_B + "'"));

        ResponseEntity<String> own = send(HttpMethod.GET, "/api/v1/orders/" + id, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertEquals(id, text(own, "/id"));

        ResponseEntity<String> inbox = send(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, inbox.getStatusCode(), inbox.getBody());
        boolean found = false;
        for (JsonNode item : json(inbox)) {
            if (id.equals(item.at("/orderRef").asText())) {
                found = true;
                assertEquals("Synthetic CBC question", item.at("/clinicalQuestion").asText());
                assertEquals("awaiting_local_patient", item.at("/state").asText());
                assertFalse(item.at("/patientVerified").asBoolean());
                assertEquals(text(created, "/snapshotFingerprint"), item.at("/fingerprint").asText());
            }
        }
        assertTrue(found, "the approved destination must see the bounded work item");
    }

    @Test
    void deniesPayloadRecipientPurposeAndDurationOutsideTheAuthorization() {
        assertEquals(HttpStatus.FORBIDDEN, createOrder("CMP", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, createOrder("CBC", ORG_C, "lab review", GRANT, 30,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, createOrder("CBC", ORG_B, "marketing", GRANT, 30,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, createOrder("CBC", ORG_B, "lab review", GRANT, 61,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, createOrder("CBC", ORG_B, "lab review", EXPIRED_GRANT, 30,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals("DISCLOSURE_NOT_AUTHORIZED", text(createOrder("CBC", ORG_B, "lab review",
                GRANT, 61, "clinician-a", ORG_A), "/code"));
    }

    @Test
    void enforcesMembershipRolesAndTenantConcealment() {
        String body = orderBody(patientId(ORG_A), "CBC", ORG_B, "lab review", GRANT, 30);
        assertEquals(HttpStatus.UNAUTHORIZED, send(HttpMethod.POST, "/api/v1/orders", body,
                null, ORG_A).getStatusCode());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/orders", body, "clinician-a", null)
                .getStatusCode().value());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/api/v1/orders", body,
                "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/api/v1/orders", body,
                "auditor-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/api/v1/orders", body,
                "clinician-c", ORG_A).getStatusCode());
        // A patient of the peer tenant is concealed in the sender's context.
        String foreign = orderBody(patientId(ORG_B), "CBC", ORG_B, "lab review", GRANT, 30);
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.POST, "/api/v1/orders", foreign,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/orders",
                orderBody("not-a-uuid", "CBC", ORG_B, "lab review", GRANT, 30),
                "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/orders",
                orderBody(patientId(ORG_A), "CBC", ORG_B, "lab review", GRANT, 0),
                "clinician-a", ORG_A).getStatusCode().value());
    }

    @Test
    void verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess() throws Exception {
        String orderId = text(createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A), "/id");
        String workId = workItemId(orderId, "clinician-b", ORG_B);

        // The receiving tenant cannot use the sender's patient or order identifiers
        // to reach any chart or order outside the bounded projection.
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, "/api/v1/orders/" + orderId, null,
                "clinician-b", ORG_B).getStatusCode());
        int chartStatus = send(HttpMethod.GET, "/api/v1/patients/" + patientId(ORG_A),
                null, "clinician-b", ORG_B).getStatusCode().value();
        assertTrue(chartStatus == 403 || chartStatus == 404,
                "sharing an order must not grant receiver chart access");
        // A role that does have patient permission still cannot read the sender's chart,
        // while its own patient remains readable (so the denial is not vacuous).
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, "/api/v1/patients/" + patientId(ORG_A),
                null, "reception-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, "/api/v1/patients/" + patientId(ORG_B),
                null, "reception-b", ORG_B).getStatusCode());
        assertEquals(0, scalar(ORG_B, "admin-b", "SELECT count(*) FROM public.exchange_order "
                + "WHERE id = '" + orderId + "'"));

        // The sender cannot verify its own projection into the receiving tenant.
        assertTrue(send(HttpMethod.POST,
                "/api/v1/exchange/work-items/" + workId + "/verify-patient",
                "{\"localPatientId\":\"" + patientId(ORG_A) + "\",\"localMrn\":\"MRN-1\","
                        + "\"mrnIssuer\":\"CLINIC\",\"attestation\":\"site-verified MRN match\"}",
                "clinician-a", ORG_A).getStatusCode().value() >= 400);

        // A different receiver patient with a different MRN must not be bound.
        assertEquals(403, send(HttpMethod.POST,
                "/api/v1/exchange/work-items/" + workId + "/verify-patient",
                "{\"localPatientId\":\"" + patientId(ORG_B, "MRN-2") + "\",\"localMrn\":\"MRN-2\","
                        + "\"mrnIssuer\":\"CLINIC\",\"attestation\":\"site-verified MRN match\"}",
                "clinician-b", ORG_B).getStatusCode().value());

        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.POST,
                "/api/v1/exchange/work-items/" + workId + "/verify-patient",
                "{\"localPatientId\":\"" + patientId(ORG_A) + "\",\"localMrn\":\"MRN-1\",\"mrnIssuer\":\"CLINIC\",\"attestation\":\"site-verified MRN match\"}", "clinician-b", ORG_B)
                .getStatusCode());
        ResponseEntity<String> verified = send(HttpMethod.POST,
                "/api/v1/exchange/work-items/" + workId + "/verify-patient",
                "{\"localPatientId\":\"" + patientId(ORG_B) + "\",\"localMrn\":\"MRN-1\",\"mrnIssuer\":\"CLINIC\",\"attestation\":\"site-verified MRN match\"}", "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("ready", text(verified, "/state"));
        assertEquals(patientId(ORG_B), text(verified, "/localPatientId"));
        assertEquals(409, send(HttpMethod.POST,
                "/api/v1/exchange/work-items/" + workId + "/verify-patient",
                "{\"localPatientId\":\"" + patientId(ORG_B) + "\",\"localMrn\":\"MRN-1\",\"mrnIssuer\":\"CLINIC\",\"attestation\":\"site-verified MRN match\"}", "clinician-b", ORG_B)
                .getStatusCode().value());
        ResponseEntity<String> unrelated = send(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                "clinician-c", ORG_C);
        assertEquals(HttpStatus.OK, unrelated.getStatusCode(), unrelated.getBody());
        assertEquals(0, json(unrelated).size(), "an unrelated tenant sees no projected work items");
    }

    @Test
    void snapshotAndDisclosureAreImmutableAndOtherTenantsStayIsolated() throws Exception {
        String orderId = text(createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A), "/id");
        String fingerprint = text(send(HttpMethod.GET, "/api/v1/orders/" + orderId, null,
                "clinician-a", ORG_A), "/snapshotFingerprint");

        boolean updateRejected = false;
        boolean deleteRejected = false;
        try {
            execute(ORG_A, "admin-a", "UPDATE public.exchange_order_snapshot SET content = 'changed' "
                    + "WHERE order_id = '" + orderId + "'");
        } catch (Exception exception) {
            updateRejected = true;
        }
        try {
            execute(ORG_A, "admin-a", "DELETE FROM public.exchange_order_snapshot "
                    + "WHERE order_id = '" + orderId + "'");
        } catch (Exception exception) {
            deleteRejected = true;
        }
        assertTrue(updateRejected, "a released snapshot cannot be rewritten");
        assertTrue(deleteRejected, "a released snapshot cannot be deleted");
        assertEquals(fingerprint, text(send(HttpMethod.GET, "/api/v1/orders/" + orderId, null,
                "clinician-a", ORG_A), "/snapshotFingerprint"));

        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, "/api/v1/orders/" + orderId, null,
                "clinician-c", ORG_C).getStatusCode());
        assertEquals(0, scalar(ORG_C, "admin-a", "SELECT count(*) FROM public.exchange_work_item "
                + "WHERE order_ref = '" + orderId + "'"));
        assertEquals(0, scalar(ORG_C, "admin-a", "SELECT count(*) FROM public.exchange_disclosure "
                + "WHERE order_id = '" + orderId + "'"));
        assertEquals(0, scalar(ORG_C, "admin-a", "SELECT count(*) FROM public.exchange_order_snapshot "
                + "WHERE order_id = '" + orderId + "'"));
        // The approved destination sees the bounded projection only, never the
        // sender's snapshot or disclosure ledger.
        assertEquals(0, scalar(ORG_B, "admin-b", "SELECT count(*) FROM public.exchange_order_snapshot "
                + "WHERE order_id = '" + orderId + "'"));
        assertEquals(0, scalar(ORG_B, "admin-b", "SELECT count(*) FROM public.exchange_disclosure "
                + "WHERE order_id = '" + orderId + "'"));

        ResponseEntity<String> createdByAdmin = send(HttpMethod.POST, "/api/v1/exchange/authorizations",
                "{\"orderCode\":\"CBC\",\"destinationOrg\":\"" + ORG_B + "\","
                        + "\"purpose\":\"lab review\",\"maxDurationMinutes\":60,\"validForDays\":2}",
                "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, createdByAdmin.getStatusCode(), createdByAdmin.getBody());
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/api/v1/exchange/authorizations",
                "{\"orderCode\":\"CBC\",\"destinationOrg\":\"" + ORG_B + "\","
                        + "\"purpose\":\"lab review\",\"maxDurationMinutes\":60,\"validForDays\":2}",
                "clinician-a", ORG_A).getStatusCode());
    }

}
