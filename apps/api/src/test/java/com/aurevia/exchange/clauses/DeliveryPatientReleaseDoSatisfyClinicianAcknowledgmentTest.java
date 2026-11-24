package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * XCH-05 clause 4 acceptance: delivery and patient release never satisfy the
 * clinician acknowledgment of a critical result.
 *
 * <p>Every behavior is exercised through the real HTTP entry points against a
 * real PostgreSQL database. A delivered escalation and a patient release are
 * appended as their own immutable facts while the clinical state stays
 * {@code awaiting_ack}; only an authorized clinician acknowledging the exact
 * revision advances it. The negatives prove that acknowledgment is refused
 * without a delivered attempt, that the releasing administrator lacks the
 * acknowledgment permission, and that a clinician of another tenant cannot
 * acknowledge the released alert. All fixtures are synthetic; no patient data
 * or credential is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_xch05_c04_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_xch05_c04_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP = "ONCALL_PHONE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Bind the HTTP client to the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate a private database and seed two synthetic tenants with roles and escalation policies. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch05_c04_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_xch05_c04_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B}) {
                String suffix = suffix(org);
                seedMember(statement, org, "lab_verifier-" + suffix, "lab_verifier");
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "backup-" + suffix, "clinician");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedMember(statement, org, "auditor-" + suffix, "privacy_auditor");
                seedMember(statement, org, "reception-" + suffix, "reception");
            }
            for (String org : new String[] {ORG_A, ORG_B}) {
                String suffix = suffix(org);
                seedPolicy(statement, org, "admin-" + suffix, "critical.escalation.primary_channel", PRIMARY);
                seedPolicy(statement, org, "admin-" + suffix, "critical.escalation.backup_channel", BACKUP);
                seedPolicy(statement, org, "admin-" + suffix, "critical.escalation.backup_recipient",
                        "backup-" + suffix);
            }
        }
    }

    /** Drop the private database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch05_c04_it WITH (FORCE)");
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
                                            String channel, String recipient, String outcome) {
        String body = "{\"channel\":\"" + channel + "\",\"recipient\":\"" + recipient + "\","
                + "\"outcome\":\"" + outcome + "\",\"detail\":\"synthetic notification\","
                + "\"systemDown\":false}";
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/escalate", body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> readBack(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/read-back",
                "{\"repeatedValue\":\"7.2\"}", subject, org, Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> release(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/release",
                "{\"note\":\"synthetic patient release\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> acknowledge(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/acknowledge",
                "{\"note\":\"synthetic acknowledgment\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/critical-results/" + id, null, subject, org);
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
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
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
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

    private static String future() {
        return Instant.now().plus(1, ChronoUnit.HOURS).toString();
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        return text(json(response), pointer);
    }

    private static String text(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + node);
        return value.isNull() ? null : value.asText();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode event(JsonNode view, String type) {
        JsonNode found = null;
        for (JsonNode candidate : view.at("/events")) {
            if (type.equals(candidate.at("/eventType").asText())) {
                found = candidate;
                break;
            }
        }
        assertNotNull(found, "missing " + type + " event in " + view);
        return found;
    }

    private static int eventCount(JsonNode view, String type) {
        int count = 0;
        for (JsonNode candidate : view.at("/events")) {
            if (type.equals(candidate.at("/eventType").asText())) {
                count++;
            }
        }
        return count;
    }

    private long auditCount(String org, String resultId, String action) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
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

    @Test
    void patientReleaseAndDeliveryLeaveTheResultAwaitingClinicianAcknowledgment() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";

        ResponseEntity<String> created = register(org, lab, true);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");
        assertEquals("unverified", text(created, "/state"));
        assertEquals("1", text(created, "/version"));

        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());
        assertEquals("awaiting_ack", text(get(org, admin, id), "/result/state"));
        assertEquals("3", text(get(org, admin, id), "/result/version"));

        ResponseEntity<String> delivered = escalate(org, admin, id, 3, PRIMARY, clinician, "delivered");
        assertEquals(HttpStatus.OK, delivered.getStatusCode(), delivered.getBody());
        assertEquals("3", text(delivered, "/version"), "a delivered attempt is a fact, not a state move");
        assertEquals("delivered", event(json(get(org, admin, id)), "escalation_attempt").at("/outcome").asText());

        ResponseEntity<String> released = release(org, admin, id, 3);
        assertEquals(HttpStatus.OK, released.getStatusCode(), released.getBody());
        assertEquals("awaiting_ack", text(released, "/state"), "patient release must not acknowledge");
        assertEquals("3", text(released, "/version"), "patient release must not change the revision");

        JsonNode afterRelease = json(get(org, admin, id));
        assertEquals("awaiting_ack", text(afterRelease, "/result/state"));
        assertEquals("3", text(afterRelease, "/result/version"));
        assertEquals(1, eventCount(afterRelease, "patient_released"));
        assertEquals(0, eventCount(afterRelease, "acknowledged"));
        assertEquals("3", event(afterRelease, "patient_released").at("/criticalResultVersion").asText());
        assertEquals(0L, auditCount(org, id, "CRITICAL_ACKNOWLEDGE"));

        // Delivery alone is not acknowledgment: read-back is still a separate gate.
        ResponseEntity<String> beforeReadBack = acknowledge(org, clinician, id, 3);
        assertEquals(409, beforeReadBack.getStatusCode().value(), beforeReadBack.getBody());
        assertEquals("READ_BACK_REQUIRED", text(beforeReadBack, "/code"));

        assertEquals(HttpStatus.OK, readBack(org, clinician, id, 3).getStatusCode());

        ResponseEntity<String> acknowledged = acknowledge(org, clinician, id, 3);
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));
        assertEquals("4", text(acknowledged, "/version"));

        JsonNode afterAcknowledgment = json(get(org, admin, id));
        assertEquals("acknowledged", text(afterAcknowledgment, "/result/state"));
        assertEquals("4", text(afterAcknowledgment, "/result/version"));
        assertEquals(1L, auditCount(org, id, "CRITICAL_ACKNOWLEDGE"));
        assertEquals("3", event(afterAcknowledgment, "patient_released").at("/criticalResultVersion").asText());
        JsonNode acknowledgedEvent = event(afterAcknowledgment, "acknowledged");
        assertEquals("3", acknowledgedEvent.at("/criticalResultVersion").asText(),
                "acknowledgment is bound to the exact revision the clinician read");
        assertEquals(actorId(org, clinician), acknowledgedEvent.at("/actorId").asText());

        JsonNode events = json(get(org, admin, id)).at("/events");
        assertEquals(7, events.size());
        assertEquals("registered", events.at("/0/eventType").asText());
        assertEquals("owner_assigned", events.at("/1/eventType").asText());
        assertEquals("verified", events.at("/2/eventType").asText());
        assertEquals("escalation_attempt", events.at("/3/eventType").asText());
        assertEquals("patient_released", events.at("/4/eventType").asText());
        assertEquals("read_back", events.at("/5/eventType").asText());
        assertEquals("acknowledged", events.at("/6/eventType").asText());
    }

    @Test
    void acknowledgeWithoutADeliveredEscalationIsRejected() {
        String org = ORG_A;
        String id = text(register(org, "lab_verifier-1111", true), "/id");
        assertEquals(HttpStatus.OK,
                assign(org, "admin-1111", id, 1, "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", id, 2).getStatusCode());

        ResponseEntity<String> refused = acknowledge(org, "clinician-1111", id, 3);
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NO_DELIVERED_ATTEMPT", text(refused, "/code"));

        JsonNode view = json(get(org, "admin-1111", id));
        assertEquals("awaiting_ack", text(view, "/result/state"));
        assertEquals("3", text(view, "/result/version"));
        assertEquals(0, eventCount(view, "acknowledged"));
        assertEquals(0L, auditCount(org, id, "CRITICAL_ACKNOWLEDGE"));
    }

    @Test
    void anAdministratorWhoReleasesCannotAcknowledge() {
        String org = ORG_A;
        String clinician = "clinician-1111";
        String id = text(register(org, "lab_verifier-1111", true), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-1111", id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", id, 2).getStatusCode());
        assertEquals(HttpStatus.OK,
                escalate(org, "admin-1111", id, 3, PRIMARY, clinician, "delivered").getStatusCode());
        assertEquals(HttpStatus.OK, readBack(org, clinician, id, 3).getStatusCode());

        ResponseEntity<String> released = release(org, "admin-1111", id, 3);
        assertEquals(HttpStatus.OK, released.getStatusCode(), released.getBody());
        assertEquals("awaiting_ack", text(released, "/state"));
        assertEquals("3", text(released, "/version"));

        ResponseEntity<String> denied = acknowledge(org, "admin-1111", id, 3);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));

        JsonNode afterDenial = json(get(org, "admin-1111", id));
        assertEquals("awaiting_ack", text(afterDenial, "/result/state"));
        assertEquals("3", text(afterDenial, "/result/version"));
        assertEquals(0, eventCount(afterDenial, "acknowledged"));
        assertEquals("3", event(afterDenial, "patient_released").at("/criticalResultVersion").asText());

        ResponseEntity<String> acknowledged = acknowledge(org, clinician, id, 3);
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));
        assertEquals("4", text(acknowledged, "/version"));
        assertEquals(actorId(org, clinician),
                event(json(get(org, "admin-1111", id)), "acknowledged").at("/actorId").asText());
    }

    @Test
    void aClinicianOfAnotherTenantCannotAcknowledgeTheReleasedAlert() {
        String org = ORG_A;
        String id = text(register(org, "lab_verifier-1111", false), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-1111", id, 1, "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", id, 2).getStatusCode());
        assertEquals(HttpStatus.OK,
                escalate(org, "admin-1111", id, 3, PRIMARY, "clinician-1111", "delivered").getStatusCode());
        assertEquals(HttpStatus.OK, release(org, "admin-1111", id, 3).getStatusCode());

        // The subject has no active membership in the organization it names as the active scope.
        ResponseEntity<String> wrongScope = acknowledge(org, "clinician-2222", id, 3);
        assertEquals(HttpStatus.FORBIDDEN, wrongScope.getStatusCode(), wrongScope.getBody());
        assertEquals("FORBIDDEN", text(wrongScope, "/code"));

        // With the other tenant as the active scope, forced RLS conceals the ORG_A row.
        ResponseEntity<String> wrongTenant = acknowledge(ORG_B, "clinician-2222", id, 3);
        assertEquals(HttpStatus.NOT_FOUND, wrongTenant.getStatusCode(), wrongTenant.getBody());
        assertEquals("NOT_FOUND", text(wrongTenant, "/code"));

        JsonNode view = json(get(org, "admin-1111", id));
        assertEquals("awaiting_ack", text(view, "/result/state"));
        assertEquals("3", text(view, "/result/version"));
        assertEquals(0, eventCount(view, "acknowledged"));

        // The denials did not consume or corrupt the alert: its own clinician can still acknowledge it.
        ResponseEntity<String> acknowledged = acknowledge(org, "clinician-1111", id, 3);
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));
    }
}
