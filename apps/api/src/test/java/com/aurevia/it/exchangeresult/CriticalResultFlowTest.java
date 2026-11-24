package com.aurevia.it.exchangeresult;

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

import org.flywaydb.core.Flyway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S-G2-05 acceptance (XCH-05) on a real PostgreSQL instance.
 *
 * <p>Four synthetic organizations share local patient and subject identifiers
 * so tenant isolation is observable. All fixtures and narratives are synthetic;
 * no patient data or credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_critical_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class CriticalResultFlowTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String ORG_D = "44444444-4444-4444-4444-444444444444";
    static final String ORG_E = "55555555-5555-5555-5555-555555555555";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_critical_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String PRIMARY = "SECURE_MESSAGE";
    static final String BACKUP = "ONCALL_PHONE";

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_critical_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_critical_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D, ORG_E}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D, ORG_E}) {
                String suffix = org.substring(0, 4);
                seedMember(statement, org, "lab_verifier-" + suffix, "lab_verifier");
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "backup-" + suffix, "clinician");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedMember(statement, org, "auditor-" + suffix, "privacy_auditor");
                seedMember(statement, org, "reception-" + suffix, "reception");
            }
            // A, B and D publish an approved escalation policy; C intentionally has none.
            for (String org : new String[] {ORG_A, ORG_B, ORG_D, ORG_E}) {
                String suffix = org.substring(0, 4);
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_critical_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'lab_verifier-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
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

    private ResponseEntity<String> register(String org, String subject, boolean readBack,
                                            String sourceSystem, String sourceId, String orderRef,
                                            String accession) {
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"sourceSystem\":\"" + sourceSystem + "\","
                + "\"sourceId\":\"" + sourceId + "\""
                + (orderRef == null ? "" : ",\"orderReference\":\"" + orderRef + "\"")
                + (accession == null ? "" : ",\"accession\":\"" + accession + "\"")
                + ",\"resultSummary\":\"synthetic critical finding\",\"observedValue\":\"7.2\","
                + "\"unit\":\"mmol/L\",\"readBackRequired\":" + readBack + "}";
        return send(HttpMethod.POST, "/api/v1/critical-results", body, subject, org);
    }

    private ResponseEntity<String> register(String org, String subject, boolean readBack) {
        return register(org, subject, readBack, "LAB", "OBS-1", null, null);
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
                "{\"repeatedValue\":\"7.2\"}", subject, org, Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> acknowledge(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/acknowledge",
                "{\"note\":\"synthetic acknowledgment\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> followUp(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/follow-up",
                "{\"note\":\"synthetic follow-up\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/critical-results/" + id, null, subject, org);
    }

    private ResponseEntity<String> report(String org, String subject) {
        return send(HttpMethod.GET, "/api/v1/critical-results/report", null, subject, org);
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

    private static void execute(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
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

    @Test
    void fullWorkflowKeepsEveryCriticalFactSeparate() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";

        ResponseEntity<String> created = register(org, lab, true, "LAB", "OBS-1", "ORD-1", "ACC-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");
        assertEquals("unverified", text(created, "/state"));
        assertEquals("1", text(created, "/version"));
        assertEquals("ORD-1", text(created, "/orderReference"));
        assertEquals("ACC-1", text(created, "/accession"));
        assertEquals("true", text(created, "/readBackRequired"));

        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals("2", text(get(org, admin, id), "/result/version"));
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());
        assertEquals("awaiting_ack", text(get(org, admin, id), "/result/state"));

        ResponseEntity<String> attempt = escalate(org, admin, id, 3, PRIMARY, clinician, "delivered", false);
        assertEquals(HttpStatus.OK, attempt.getStatusCode(), attempt.getBody());
        assertEquals("3", text(attempt, "/version"), "an escalation attempt is a fact, not a state change");

        assertEquals(HttpStatus.OK, readBack(org, clinician, id, 3).getStatusCode());
        ResponseEntity<String> acknowledged = acknowledge(org, clinician, id, 3);
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));
        assertEquals("4", text(acknowledged, "/version"));

        ResponseEntity<String> closed = followUp(org, clinician, id, 4);
        assertEquals(HttpStatus.OK, closed.getStatusCode(), closed.getBody());
        assertEquals("closed", text(closed, "/state"));
        assertEquals("5", text(closed, "/version"));

        JsonNode events = json(get(org, admin, id)).at("/events");
        assertEquals(7, events.size());
        assertEquals("registered", events.at("/0/eventType").asText());
        assertEquals("owner_assigned", events.at("/1/eventType").asText());
        assertEquals("verified", events.at("/2/eventType").asText());
        assertEquals("escalation_attempt", events.at("/3/eventType").asText());
        assertEquals("read_back", events.at("/4/eventType").asText());
        assertEquals("acknowledged", events.at("/5/eventType").asText());
        assertEquals("follow_up", events.at("/6/eventType").asText());
        assertEquals(actorId(org, lab), events.at("/0/actorId").asText());
        assertEquals(actorId(org, admin), events.at("/1/actorId").asText());
        assertEquals(actorId(org, clinician), events.at("/5/actorId").asText());
        assertEquals("3", events.at("/5/criticalResultVersion").asText(),
                "acknowledgment is bound to the exact revision the clinician read");
        assertEquals(1, auditCount(org, id, "CRITICAL_ACKNOWLEDGE"));
    }

    @Test
    void deliveryAndPatientReleaseNeverAcknowledge() {
        String org = ORG_A;
        String id = text(register(org, "lab_verifier-1111", true), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-1111", id, 1, "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", id, 2).getStatusCode());
        assertEquals(HttpStatus.OK, escalate(org, "admin-1111", id, 3, PRIMARY, "clinician-1111",
                "delivered", false).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/release",
                "{\"note\":\"synthetic patient release\"}", "admin-1111", org,
                Map.of("If-Match", "3")).getStatusCode());

        assertEquals("awaiting_ack", text(get(org, "admin-1111", id), "/result/state"));
        assertEquals(409, acknowledge(org, "clinician-1111", id, 3).getStatusCode().value(),
                "a required read-back is still missing");
        assertEquals(HttpStatus.OK, readBack(org, "clinician-1111", id, 3).getStatusCode());
        assertEquals(HttpStatus.OK, acknowledge(org, "clinician-1111", id, 3).getStatusCode());

        JsonNode events = json(get(org, "admin-1111", id)).at("/events");
        boolean released = false;
        for (JsonNode event : events) {
            if ("patient_released".equals(event.at("/eventType").asText())) {
                released = true;
                assertEquals("3", event.at("/criticalResultVersion").asText());
            }
        }
        assertTrue(released, "patient release is recorded as its own fact");
    }

    @Test
    void overdueAndSystemDownUseApprovedBackupChannel() throws Exception {
        String org = ORG_B;
        String clinician = "clinician-2222";
        String id = text(register(org, "lab_verifier-2222", false), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-2222", id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-2222", id, 2).getStatusCode());

        assertEquals(422, escalate(org, "admin-2222", id, 3, BACKUP, "backup-2222",
                "delivered", false).getStatusCode().value());
        assertEquals(422, escalate(org, "admin-2222", id, 3, PRIMARY, "backup-2222",
                "delivered", false).getStatusCode().value());

        ResponseEntity<String> failed = escalate(org, "admin-2222", id, 3, BACKUP, "backup-2222",
                "failed", true);
        assertEquals(HttpStatus.OK, failed.getStatusCode(), failed.getBody());

        execute(org, "admin-2222", "UPDATE public.critical_result "
                + "SET due_at = now() - interval '1 minute', version = version + 1 WHERE id = '" + id + "'");
        assertEquals(422, escalate(org, "admin-2222", id, 4, PRIMARY, clinician,
                "delivered", false).getStatusCode().value());
        assertEquals(422, escalate(org, "admin-2222", id, 4, BACKUP, clinician,
                "delivered", false).getStatusCode().value(),
                "the approved backup recipient is required with the backup channel");

        ResponseEntity<String> delivered = escalate(org, "admin-2222", id, 4, BACKUP, "backup-2222",
                "delivered", false);
        assertEquals(HttpStatus.OK, delivered.getStatusCode(), delivered.getBody());
        assertEquals(409, readBack(org, clinician, id, 4).getStatusCode().value(),
                "read-back is refused when it was not required");
        assertEquals(HttpStatus.OK, acknowledge(org, clinician, id, 4).getStatusCode());

        JsonNode failedNotifications = json(report(org, "admin-2222")).at("/failedNotifications");
        assertEquals(1, failedNotifications.size());
        assertEquals("escalation_attempt", failedNotifications.at("/0/eventType").asText());
    }

    @Test
    void reportSurfacesOverdueAlertsAndRosterGaps() throws Exception {
        String org = ORG_D;
        String id = text(register(org, "lab_verifier-4444", false), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-4444", id, 1, "clinician-4444", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-4444", id, 2).getStatusCode());

        execute(org, "admin-4444", "UPDATE public.critical_result "
                + "SET due_at = now() - interval '1 minute', version = version + 1 WHERE id = '" + id + "'");
        JsonNode overdue = json(report(org, "admin-4444")).at("/overdue");
        assertEquals(1, overdue.size());
        assertEquals(id, overdue.at("/0/id").asText());

        execute(org, "admin-4444", "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '44444444-4444-4444-4444-444444444444', id, 'revoked' "
                + "FROM public.user_identity WHERE subject = 'backup-4444'");
        assertEquals(409, escalate(org, "admin-4444", id, 4, BACKUP, "backup-4444",
                "delivered", false).getStatusCode().value());

        JsonNode gaps = json(report(org, "admin-4444")).at("/rosterGaps");
        assertEquals(1, gaps.size());
        assertEquals("roster_gap", gaps.at("/0/eventType").asText());
    }

    @Test
    void backupRosterGapAtAssignmentAndAcknowledgmentNeedsDelivery() throws Exception {
        String gapOrg = ORG_E;
        String gapId = text(register(gapOrg, "lab_verifier-5555", false), "/id");
        execute(gapOrg, "admin-5555", "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '55555555-5555-5555-5555-555555555555', id, 'revoked' "
                + "FROM public.user_identity WHERE subject = 'backup-5555'");
        ResponseEntity<String> gap = assign(gapOrg, "admin-5555", gapId, 1,
                "clinician-5555", future());
        assertEquals(422, gap.getStatusCode().value());
        assertEquals("BACKUP_ROSTER_GAP", text(gap, "/code"));
        assertEquals(1, json(report(gapOrg, "admin-5555")).at("/rosterGaps").size());

        String org = ORG_A;
        String noDelivery = text(register(org, "lab_verifier-1111", false), "/id");
        assertEquals(HttpStatus.OK, assign(org, "admin-1111", noDelivery, 1,
                "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", noDelivery, 2).getStatusCode());
        ResponseEntity<String> refused = acknowledge(org, "clinician-1111", noDelivery, 3);
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("NO_DELIVERED_ATTEMPT", text(refused, "/code"));

        String noDue = text(register(org, "lab_verifier-1111", false), "/id");
        execute(org, "admin-1111", "UPDATE public.critical_result "
                + "SET owner_subject = 'clinician-1111', version = version + 1 "
                + "WHERE id = '" + noDue + "'");
        ResponseEntity<String> missingDue = verify(org, "lab_verifier-1111", noDue, 2);
        assertEquals(409, missingDue.getStatusCode().value());
        assertEquals("OWNER_REQUIRED", text(missingDue, "/code"));
    }

    @Test
    void verificationRequiresAnOwnerAndDueTime() {
        String org = ORG_A;
        String id = text(register(org, "lab_verifier-1111", false), "/id");
        assertEquals(409, verify(org, "lab_verifier-1111", id, 1).getStatusCode().value());
        assertEquals("OWNER_REQUIRED", text(verify(org, "lab_verifier-1111", id, 1), "/code"));
        assertEquals(HttpStatus.OK, assign(org, "admin-1111", id, 1, "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", id, 2).getStatusCode());
    }

    @Test
    void authorizationAndTenantIsolationHoldOnEveryCommand() {
        String org = ORG_A;
        String peer = ORG_B;
        assertEquals(HttpStatus.UNAUTHORIZED, register(org, null, false).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, register(org, "reception-1111", false).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, register(org, "auditor-1111", false).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, register(org, "lab_verifier-2222", false).getStatusCode());

        String id = text(register(org, "lab_verifier-1111", false), "/id");
        assertEquals(HttpStatus.NOT_FOUND, get(peer, "clinician-2222", id).getStatusCode());
        assertEquals(HttpStatus.OK, get(org, "auditor-1111", id).getStatusCode(),
                "a privacy auditor may read the trail without clinical write rights");
        assertEquals(HttpStatus.FORBIDDEN, report(org, "reception-1111").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                verify(org, "clinician-1111", id, 1).getStatusCode(), "only a verifier verifies");
        assertEquals(HttpStatus.OK, assign(org, "admin-1111", id, 1, "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/release",
                        "{\"note\":\"nope\"}", "clinician-1111", org,
                        Map.of("If-Match", "2")).getStatusCode());
    }

    @Test
    void invalidOwnersAndMissingPolicyAreRejected() {
        String noPolicy = text(register(ORG_C, "lab_verifier-3333", false), "/id");
        ResponseEntity<String> missing = assign(ORG_C, "admin-3333", noPolicy, 1,
                "clinician-3333", future());
        assertEquals(422, missing.getStatusCode().value());
        assertEquals("NO_ESCALATION_POLICY", text(missing, "/code"));

        String id = text(register(ORG_A, "lab_verifier-1111", false), "/id");
        assertEquals(422, assign(ORG_A, "admin-1111", id, 1, "no-such-user", future()).getStatusCode().value());
        assertEquals("INVALID_OWNER", text(assign(ORG_A, "admin-1111", id, 1, "no-such-user", future()), "/code"));
        assertEquals(422, assign(ORG_A, "admin-1111", id, 1, "lab_verifier-1111", future())
                .getStatusCode().value());
    }

    @Test
    void illegalTransitionsAndPreconditionsAreRejected() {
        String org = ORG_A;
        String id = text(register(org, "lab_verifier-1111", true), "/id");

        assertEquals(404, get(org, "admin-1111", "55555555-5555-5555-5555-555555555555")
                .getStatusCode().value());
        assertEquals(422, get(org, "admin-1111", "not-a-uuid").getStatusCode().value());
        String base = "{\"patientId\":\"" + patientId(org) + "\",\"sourceSystem\":\"LAB\","
                + "\"sourceId\":\"OBS\",\"resultSummary\":\"synthetic critical finding\","
                + "\"observedValue\":\"7.2\",\"unit\":\"mmol/L\",\"readBackRequired\":false}";
        assertEquals(422, send(HttpMethod.POST, "/api/v1/critical-results",
                base.replace("\"OBS\"", "\"" + "x".repeat(200) + "\""), "lab_verifier-1111", org)
                .getStatusCode().value(), "an over-long source id is rejected");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/critical-results",
                "{\"patientId\":\"nope\"}", "lab_verifier-1111", org).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/critical-results",
                base.replace(",\"resultSummary\":\"synthetic critical finding\"", ""),
                "lab_verifier-1111", org).getStatusCode().value(), "a missing summary is rejected");
        assertEquals(404, send(HttpMethod.POST, "/api/v1/critical-results",
                base.replace(patientId(org), "55555555-5555-5555-5555-555555555555"),
                "lab_verifier-1111", org).getStatusCode().value(), "an invisible patient is concealed");

        assertEquals(428, send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/assign",
                "{\"ownerSubject\":\"clinician-1111\",\"dueAt\":\"" + future() + "\"}",
                "admin-1111", org).getStatusCode().value());
        assertEquals(412, assign(org, "admin-1111", id, 9, "clinician-1111", future()).getStatusCode().value());
        assertEquals(422, escalate(org, "admin-1111", id, 1, "lowercase", "clinician-1111",
                "delivered", false).getStatusCode().value());
        assertEquals(409, escalate(org, "admin-1111", id, 1, PRIMARY, "clinician-1111",
                "delivered", false).getStatusCode().value());
        assertEquals(409, acknowledge(org, "clinician-1111", id, 1).getStatusCode().value());
        assertEquals(409, followUp(org, "clinician-1111", id, 1).getStatusCode().value());

        assertEquals(HttpStatus.OK, assign(org, "admin-1111", id, 1, "clinician-1111", future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, "lab_verifier-1111", id, 2).getStatusCode());
        assertEquals(409, verify(org, "lab_verifier-1111", id, 3).getStatusCode().value());
        assertEquals(409, readBack(org, "clinician-1111", id, 3).getStatusCode().value(),
                "no delivered attempt yet");
        assertEquals(HttpStatus.OK, escalate(org, "admin-1111", id, 3, PRIMARY, "clinician-1111",
                "delivered", false).getStatusCode());
        assertEquals(HttpStatus.OK, readBack(org, "clinician-1111", id, 3).getStatusCode());
        assertEquals(409, readBack(org, "clinician-1111", id, 3).getStatusCode().value(),
                "a second read-back is rejected");
        assertEquals(403, acknowledge(org, "lab_verifier-1111", id, 3).getStatusCode().value(),
                "a verifier cannot acknowledge a critical result");
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
}
