package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * XCH-05 clause 5 (API evidence): report overdue items, roster gaps and failed
 * notification attempts.
 *
 * <p>The rule under test is the read side only. The positive test drives
 * {@code GET /api/v1/critical-results/report} through HTTP against a real
 * PostgreSQL database and asserts that one specific alert is reported in all
 * three sections: overdue (past due time while awaiting acknowledgment),
 * roster gap (an unavailable approved backup recipient) and failed notification
 * (an escalation attempt recorded with a failed outcome). The two negative
 * tests prove the rule refuses what it must refuse: a role without
 * {@code CRITICAL_READ} is denied, and another tenant's report is empty.</p>
 *
 * <p>The clause is already implemented end to end and is also exercised by
 * {@code it.exchangeresult.CriticalResultFlowTest}; this per-clause suite adds
 * exact result-id binding on every reported row so a report that merely returns
 * the right event types but the wrong alert cannot pass. Fixtures are synthetic
 * and isolated in a dedicated database; no patient data or credentials are
 * used. This task adds proof only — no production code, migration, contract or
 * existing test is changed.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_xch05_report_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ReportOverdueItemsRosterGapsFailedNotificationTest {

    private static final String ORG_D = "44444444-4444-4444-4444-444444444444";
    private static final String ORG_E = "55555555-5555-5555-5555-555555555555";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_xch05_report_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP = "ONCALL_PHONE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Create an isolated database, apply the production migrations and seed two
     * synthetic organizations. The first owns the alert and its facts; the peer
     * organization owns no critical results so tenant isolation is observable.
     *
     * @throws Exception when database setup or seeding fails
     */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_xch05_report_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_xch05_report_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_D, ORG_E}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_D, ORG_E}) {
                String suffix = suffix(org);
                seedMember(statement, org, "lab_verifier-" + suffix, "lab_verifier");
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "backup-" + suffix, "clinician");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedMember(statement, org, "reception-" + suffix, "reception");
            }
            // Assignment snapshots these approved escalation settings on the alert.
            String suffix = suffix(ORG_D);
            seedPolicy(statement, ORG_D, "admin-" + suffix, "critical.escalation.primary_channel", PRIMARY);
            seedPolicy(statement, ORG_D, "admin-" + suffix, "critical.escalation.backup_channel", BACKUP);
            seedPolicy(statement, ORG_D, "admin-" + suffix, "critical.escalation.backup_recipient",
                    "backup-" + suffix);
        }
    }

    /**
     * Drop the scratch database so the worker can run this class repeatedly.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_xch05_report_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the running test server.
     *
     * @param port injected random port of the test server
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * One alert is reported as overdue, as a roster gap and as a failed
     * notification, each bound to its exact identifier.
     */
    @Test
    void reportListsOverdueAlertsRosterGapsAndFailedNotifications() throws Exception {
        String org = ORG_D;
        String admin = "admin-" + suffix(org);
        String lab = "lab_verifier-" + suffix(org);
        String owner = "clinician-" + suffix(org);
        String backup = "backup-" + suffix(org);

        String id = register(org, lab);
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, owner, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());

        // Record the failed attempt first, while the backup clinician is still
        // active: a revoked backup would short-circuit escalation into a roster
        // gap before the failure fact could be appended.
        ResponseEntity<String> failedAttempt = escalate(org, admin, id, 3, PRIMARY, owner, "failed", false);
        assertEquals(HttpStatus.OK, failedAttempt.getStatusCode(), failedAttempt.getBody());

        // A control alert with a future deadline and a delivered attempt exercises
        // the false side of all three report predicates.
        String controlId = register(org, lab);
        assertEquals(HttpStatus.OK, assign(org, admin, controlId, 1, owner, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, controlId, 2).getStatusCode());
        assertEquals(HttpStatus.OK, escalate(org, admin, controlId, 3, PRIMARY, owner, "delivered", false)
                .getStatusCode());

        // Force the first alert past its deadline, advancing the version exactly once.
        execute(org, admin, "UPDATE public.critical_result "
                + "SET due_at = now() - interval '1 minute', version = version + 1 WHERE id = '" + id + "'");

        // Revoke the approved backup and escalate again: the service records a
        // roster-gap fact and refuses the backup channel with 409.
        execute(org, admin, "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_D + "', id, 'revoked' FROM public.user_identity "
                + "WHERE subject = '" + backup + "'");
        ResponseEntity<String> gap = escalate(org, admin, id, 4, BACKUP, backup, "delivered", true);
        assertEquals(409, gap.getStatusCode().value(), gap.getBody());

        ResponseEntity<String> response = report(org, admin);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        JsonNode body = json(response);

        JsonNode overdue = body.path("overdue");
        JsonNode overdueRow = findById(overdue, id);
        assertNotNull(overdueRow, "the overdue alert must be reported");
        assertEquals(id, overdueRow.path("id").asText());
        assertEquals("awaiting_ack", overdueRow.path("state").asText());
        assertNull(findById(overdue, controlId),
                "an alert with a future deadline is not overdue");

        JsonNode gaps = body.path("rosterGaps");
        JsonNode gapEvent = findEventByResult(gaps, id);
        assertNotNull(gapEvent, "the roster gap must be reported");
        assertEquals("roster_gap", gapEvent.path("eventType").asText());
        assertEquals(id, gapEvent.path("criticalResultId").asText());
        assertNull(findEventByResult(gaps, controlId),
                "an alert with an available backup has no roster gap");

        JsonNode failures = body.path("failedNotifications");
        JsonNode failedEvent = findEventByResult(failures, id);
        assertNotNull(failedEvent, "the failed notification must be reported");
        assertEquals("escalation_attempt", failedEvent.path("eventType").asText());
        assertEquals("failed", failedEvent.path("outcome").asText());
        assertEquals(id, failedEvent.path("criticalResultId").asText());
        assertNull(findEventByResult(failures, controlId),
                "a delivered attempt is not a failed notification");
    }

    /** A role without the read permission is refused the report. */
    @Test
    void reportIsForbiddenForReceptionRole() {
        ResponseEntity<String> denied = report(ORG_D, "reception-" + suffix(ORG_D));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
    }

    /** A report from another tenant never exposes this tenant's rows. */
    @Test
    void reportFromPeerTenantIsEmpty() throws Exception {
        // Seed a reportable fact in the victim tenant so the empty peer report
        // cannot pass vacuously when this method runs before the positive test.
        String victimId = seedOverdueSentinel(ORG_D);
        ResponseEntity<String> victimReport = report(ORG_D, "admin-" + suffix(ORG_D));
        assertEquals(HttpStatus.OK, victimReport.getStatusCode(), victimReport.getBody());
        assertNotNull(findById(json(victimReport).path("overdue"), victimId),
                "the victim tenant must own a reportable fact");

        ResponseEntity<String> response = report(ORG_E, "admin-" + suffix(ORG_E));
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        JsonNode body = json(response);
        assertEquals(0, body.path("overdue").size());
        assertEquals(0, body.path("rosterGaps").size());
        assertEquals(0, body.path("failedNotifications").size());
        assertFalse(body.toString().contains(victimId), "the peer report must not name the victim's alert");
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

    /**
     * Insert one overdue, awaiting-acknowledgment alert directly for the tenant
     * isolation test, so an empty peer report proves RLS rather than an empty
     * database.
     *
     * @param org organization that owns the alert
     * @return the inserted result identifier
     * @throws Exception when the insert fails
     */
    private static String seedOverdueSentinel(String org) throws Exception {
        String id = "aaaaaaaa-0000-4000-8000-000000000001";
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO public.critical_result "
                        + "(organization_id, id, version, patient_id, source_system, source_id, "
                        + " result_summary, observed_value, unit, read_back_required, state, "
                        + " owner_subject, due_at, primary_channel, backup_channel, backup_recipient) "
                        + "VALUES ('" + org + "', '" + id + "', 1, "
                        + " (SELECT id FROM public.patient_record WHERE mrn = 'MRN-1'), "
                        + " 'LAB', 'SENTINEL-1', 'synthetic sentinel finding', '7.2', 'mmol/L', false, "
                        + " 'awaiting_ack', 'clinician-" + suffix(org) + "', now() - interval '1 minute', "
                        + " '" + PRIMARY + "', '" + BACKUP + "', 'backup-" + suffix(org) + "')");
            }
            connection.commit();
            return id;
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                         String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private String register(String org, String subject) {
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"sourceSystem\":\"LAB\","
                + "\"sourceId\":\"OBS-1\",\"resultSummary\":\"synthetic critical finding\","
                + "\"observedValue\":\"7.2\",\"unit\":\"mmol/L\",\"readBackRequired\":false}";
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/critical-results", body, subject, org,
                Map.of());
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return text(json(created), "/id");
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
                                            String channel, String recipient, String outcome, boolean systemDown) {
        String body = "{\"channel\":\"" + channel + "\",\"recipient\":\"" + recipient + "\","
                + "\"outcome\":\"" + outcome + "\",\"detail\":\"synthetic notification\","
                + "\"systemDown\":" + systemDown + "}";
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/escalate", body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> report(String org, String subject) {
        return send(HttpMethod.GET, "/api/v1/critical-results/report", null, subject, org, Map.of());
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next(), "a synthetic patient must exist in " + org);
                return result.getString(1);
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + node);
        return value.asText();
    }

    private static JsonNode findById(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }

    private static JsonNode findEventByResult(JsonNode events, String resultId) {
        for (JsonNode event : events) {
            if (resultId.equals(event.path("criticalResultId").asText())) {
                return event;
            }
        }
        return null;
    }
}
