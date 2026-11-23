package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * XCH-05 clause 4 acceptance through the real HTTP API: a delivered critical
 * notification and a patient release never satisfy clinician acknowledgment.
 *
 * <p>The clause is about events being separate facts, so this class asserts the
 * append-only event trail as well as the result state: delivery and release each
 * leave the result {@code awaiting_ack} with no {@code acknowledged} event, and
 * only an authorized clinician's read-back plus acknowledgment appends exactly
 * one {@code acknowledged} event bound to the exact revision. Negative tests
 * show the rule refusing acknowledgment without a delivered attempt, from the
 * wrong role, from another tenant and with a stale version. This class runs on
 * its own synthetic PostgreSQL database and proves clause 4 only; the broader
 * XCH-05 workflow has its own acceptance suite. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_critical_ack_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_critical_ack_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP = "ONCALL_PHONE";

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_critical_ack_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_critical_ack_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
                String suffix = suffix(org);
                seedMember(statement, org, "lab_verifier-" + suffix, "lab_verifier");
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "backup-" + suffix, "clinician");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedPolicy(statement, org, "admin-" + suffix, "critical.escalation.primary_channel", PRIMARY);
                seedPolicy(statement, org, "admin-" + suffix, "critical.escalation.backup_channel", BACKUP);
                seedPolicy(statement, org, "admin-" + suffix, "critical.escalation.backup_recipient",
                        "backup-" + suffix);
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_critical_ack_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'lab_verifier-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix(org) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPolicy(Statement statement, String org, String actor, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    @Test
    void deliveryAndReleaseLeaveResultAwaitingUntilClinicianAcknowledges() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String admin = "admin-1111";
        String clinician = "clinician-1111";

        ResponseEntity<String> created = register(org, lab, true);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());

        ResponseEntity<String> delivered = escalate(org, admin, id, 3, PRIMARY, clinician, "delivered", false);
        assertEquals(HttpStatus.OK, delivered.getStatusCode(), delivered.getBody());
        JsonNode trail = trail(org, admin, id);
        assertTrue(hasDeliveredAttempt(trail, 3), "a delivered escalation attempt is a separate fact");
        assertEquals(0, ackCount(trail), "a delivered notification does not append an acknowledgment");
        assertEquals("awaiting_ack", text(get(org, admin, id), "/result/state"));

        ResponseEntity<String> released = release(org, admin, id, 3);
        assertEquals(HttpStatus.OK, released.getStatusCode(), released.getBody());
        trail = trail(org, admin, id);
        assertTrue(hasEvent(trail, "patient_released", 3), "patient release is recorded as its own fact");
        assertEquals(0, ackCount(trail), "a patient release still does not append an acknowledgment");
        assertEquals("awaiting_ack", text(get(org, admin, id), "/result/state"));
        assertEquals("3", text(get(org, admin, id), "/result/version"));

        assertEquals(HttpStatus.OK, readBack(org, clinician, id, 3).getStatusCode());
        ResponseEntity<String> acknowledged = acknowledge(org, clinician, id, 3);
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));
        assertEquals("4", text(acknowledged, "/version"));

        trail = trail(org, admin, id);
        assertEquals(1, ackCount(trail), "only the clinician command appends an acknowledgment");
        JsonNode acknowledgment = findEvent(trail, "acknowledged");
        assertEquals("3", acknowledgment.at("/criticalResultVersion").asText(),
                "acknowledgment binds to the exact revision the clinician read");
        assertEquals(actorId(org, clinician), acknowledgment.at("/actorId").asText());
        assertEquals(1, auditCount(org, id, "CRITICAL_ACKNOWLEDGE"));
    }

    @Test
    void deliveryWithoutReadBackCannotAcknowledge() {
        String org = ORG_A;
        String id = registerAndPrepare(org, true);
        assertEquals(HttpStatus.OK, escalate(org, "admin-1111", id, 3, PRIMARY,
                "clinician-1111", "delivered", false).getStatusCode());

        ResponseEntity<String> refused = acknowledge(org, "clinician-1111", id, 3);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals("READ_BACK_REQUIRED", text(refused, "/code"));
        assertEquals("awaiting_ack", text(get(org, "admin-1111", id), "/result/state"));
        assertEquals("3", text(get(org, "admin-1111", id), "/result/version"));
        assertEquals(0, ackCount(trail(org, "admin-1111", id)));
    }

    @Test
    void patientReleaseWithoutDeliveryCannotAcknowledge() {
        String org = ORG_A;
        String id = registerAndPrepare(org, true);
        assertEquals(HttpStatus.OK, release(org, "admin-1111", id, 3).getStatusCode());
        assertEquals("awaiting_ack", text(get(org, "admin-1111", id), "/result/state"));
        assertEquals(0, ackCount(trail(org, "admin-1111", id)));

        ResponseEntity<String> refused = acknowledge(org, "clinician-1111", id, 3);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode());
        assertEquals("NO_DELIVERED_ATTEMPT", text(refused, "/code"));
    }

    @Test
    void clinicianAcknowledgmentRequiresClinicianRole() {
        String org = ORG_A;
        String id = acknowledgmentReady(org);

        ResponseEntity<String> adminAcknowledgment = acknowledge(org, "admin-1111", id, 3);
        assertEquals(HttpStatus.FORBIDDEN, adminAcknowledgment.getStatusCode());
        assertEquals("FORBIDDEN", text(adminAcknowledgment, "/code"));
        assertEquals(0, ackCount(trail(org, "admin-1111", id)));

        ResponseEntity<String> clinicianRelease = release(org, "clinician-1111", id, 3);
        assertEquals(HttpStatus.FORBIDDEN, clinicianRelease.getStatusCode());
        assertEquals("FORBIDDEN", text(clinicianRelease, "/code"));
        assertEquals("awaiting_ack", text(get(org, "admin-1111", id), "/result/state"));
    }

    @Test
    void anotherTenantCannotAcknowledge() {
        String org = ORG_A;
        String id = acknowledgmentReady(org);

        ResponseEntity<String> refused = acknowledge(ORG_B, "clinician-2222", id, 3);
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode());
        assertEquals("NOT_FOUND", text(refused, "/code"));
        assertEquals("awaiting_ack", text(get(org, "admin-1111", id), "/result/state"));
        assertEquals("3", text(get(org, "admin-1111", id), "/result/version"));
        assertEquals(0, ackCount(trail(org, "admin-1111", id)));
    }

    @Test
    void staleVersionAcknowledgmentIsRefused() {
        String org = ORG_A;
        String id = acknowledgmentReady(org);

        ResponseEntity<String> refused = acknowledge(org, "clinician-1111", id, 1);
        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode());
        assertEquals("STALE_VERSION", text(refused, "/code"));
        assertEquals("awaiting_ack", text(get(org, "admin-1111", id), "/result/state"));
        assertEquals("3", text(get(org, "admin-1111", id), "/result/version"));
        assertEquals(0, ackCount(trail(org, "admin-1111", id)));
    }

    private String registerAndPrepare(String org, boolean readBack) {
        String suffix = suffix(org);
        String id = text(register(org, "lab_verifier-" + suffix, readBack), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-" + suffix, id, 1,
                "clinician-" + suffix, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-" + suffix, id, 2).getStatusCode());
        return id;
    }

    private String acknowledgmentReady(String org) {
        String suffix = suffix(org);
        String id = registerAndPrepare(org, true);
        assertEquals(HttpStatus.OK, escalate(org, "admin-" + suffix, id, 3, PRIMARY,
                "clinician-" + suffix, "delivered", false).getStatusCode());
        assertEquals(HttpStatus.OK, readBack(org, "clinician-" + suffix, id, 3).getStatusCode());
        return id;
    }

    private HttpHeaders headers(String subject, String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, extra)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> register(String org, String subject, boolean readBack) {
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"sourceSystem\":\"LAB\","
                + "\"sourceId\":\"OBS-1\",\"resultSummary\":\"synthetic critical finding\","
                + "\"observedValue\":\"7.2\",\"unit\":\"mmol/L\",\"readBackRequired\":" + readBack + "}";
        return send(HttpMethod.POST, "/api/v1/critical-results", body, subject, org);
    }

    private ResponseEntity<String> assign(String org, String subject, String id, int version,
                                          String owner, String dueAt) {
        String body = "{\"ownerSubject\":\"" + owner + "\",\"dueAt\":\"" + dueAt + "\"}";
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/assign", body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> verify(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/verify",
                "{\"note\":\"synthetic verification\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> escalate(String org, String subject, String id, int version,
                                            String channel, String recipient, String outcome,
                                            boolean systemDown) {
        String body = "{\"channel\":\"" + channel + "\",\"recipient\":\"" + recipient + "\","
                + "\"outcome\":\"" + outcome + "\",\"detail\":\"synthetic notification\","
                + "\"systemDown\":" + systemDown + "}";
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/escalate", body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> readBack(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/read-back",
                "{\"repeatedValue\":\"7.2\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> acknowledge(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/acknowledge",
                "{\"note\":\"synthetic acknowledgment\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> release(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/release",
                "{\"note\":\"synthetic patient release\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/critical-results/" + id, null, subject, org);
    }

    private JsonNode trail(String org, String subject, String id) {
        return json(get(org, subject, id)).at("/events");
    }

    private static long ackCount(JsonNode trail) {
        long count = 0;
        for (JsonNode event : trail) {
            if ("acknowledged".equals(event.at("/eventType").asText())) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasEvent(JsonNode trail, String eventType, int version) {
        for (JsonNode event : trail) {
            if (eventType.equals(event.at("/eventType").asText())
                    && event.at("/criticalResultVersion").asInt() == version) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDeliveredAttempt(JsonNode trail, int version) {
        for (JsonNode event : trail) {
            if ("escalation_attempt".equals(event.at("/eventType").asText())
                    && event.at("/criticalResultVersion").asInt() == version
                    && "delivered".equals(event.at("/outcome").asText())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode findEvent(JsonNode trail, String eventType) {
        for (JsonNode event : trail) {
            if (eventType.equals(event.at("/eventType").asText())) {
                return event;
            }
        }
        throw new AssertionError("no " + eventType + " event in " + trail);
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String future() {
        return Instant.now().plus(1, ChronoUnit.HOURS).toString();
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String actorId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity WHERE subject = ?")) {
                statement.setString(1, subject);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
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

    private static long auditCount(String org, String resultId, String action) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE resource_id = ? AND action = ?")) {
                statement.setString(1, resultId);
                statement.setString(2, action);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
