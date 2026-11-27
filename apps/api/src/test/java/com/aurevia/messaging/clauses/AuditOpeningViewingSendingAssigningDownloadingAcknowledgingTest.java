package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * MSG-07 clause 1 (API evidence): opening, viewing, sending, assigning,
 * downloading, acknowledging, filing, correcting and restricting are nine
 * separate audit facts, readable through the governance audit endpoint and
 * persisted in the tenant-scoped append-only ledger.
 *
 * <p>Every request goes through the real controller, service and PostgreSQL
 * store on a fresh database created from the production Flyway migrations. The
 * negative cases show that a role without the action permission is refused and
 * that the refusal is itself recorded, that a second tenant cannot read the
 * first tenant's facts, and that a governance command or a malformed identifier
 * cannot be smuggled through the workflow recorder.</p>
 *
 * <p>All identifiers and actors are synthetic. Test methods use different
 * resource identifiers because the fixture database is shared by the class.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_msg_audit_clause_it")
@ActiveProfiles("synthetic-auth")
class AuditOpeningViewingSendingAssigningDownloadingAcknowledgingTest {

    private static final String DATABASE = "aurevia_msg_audit_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "patient-a";
    private static final String CLINICIAN = "clinician-a";
    private static final String ADMIN_SUBJECT = "admin-a";
    private static final String AUDITOR_A = "auditor-a";
    private static final String AUDITOR_B = "auditor-b";
    private static final String PURPOSE = "MSG_GOVERNANCE";

    private static final String RESOURCE_WORKFLOW = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String RESOURCE_DENIAL = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String RESOURCE_CROSS_TENANT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String RESOURCE_COMMAND = "dddddddd-dddd-dddd-dddd-dddddddddddd";

    /** The nine workflow actions in the clause, with their stable wire keys. */
    private static final Set<String> CLAUSE_ACTIONS = Set.of(
            "open", "view", "send", "assign", "download", "acknowledge", "file", "correct", "restrict");

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships in two tenants.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, PATIENT, "patient");
            seedUser(statement, ORG_A, CLINICIAN, "clinician");
            seedUser(statement, ORG_A, ADMIN_SUBJECT, "tenant_admin");
            seedUser(statement, ORG_A, AUDITOR_A, "privacy_auditor");
            // The other tenant needs a real privacy-auditor membership, otherwise
            // a cross-tenant read would be refused for the wrong reason.
            seedUser(statement, ORG_B, AUDITOR_B, "privacy_auditor");
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned server port. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + org + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
    }

    private ResponseEntity<String> postAction(String action, String resourceId, String unitId,
                                              String subject, String org, String correlation) {
        String body = "{\"action\":\"" + action + "\",\"resourceId\":\"" + resourceId
                + "\",\"unitId\":\"" + unitId + "\"}";
        return request(HttpMethod.POST, "/api/v1/messaging-governance/actions", body,
                subject, org, correlation);
    }

    private ResponseEntity<String> audit(String subject, String org, String correlation) {
        return request(HttpMethod.GET, "/api/v1/messaging-governance/audit", null,
                subject, org, correlation);
    }

    private ResponseEntity<String> auditFiltered(String subject, String org, String resourceId,
                                                  String correlation) {
        return request(HttpMethod.GET, "/api/v1/messaging-governance/audit?resourceId=" + resourceId,
                null, subject, org, correlation);
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", java.util.UUID.randomUUID().toString());
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Split a JSON array of audit facts into its object strings. Audit facts are
     * flat, so a top-level object boundary is unambiguous for these synthetic
     * values.
     *
     * @param json response body containing a JSON array
     * @return one string per array element, empty when the body is an empty array
     */
    private static List<String> events(String json) {
        String body = json.trim();
        String inner = body.substring(1, body.length() - 1).trim();
        if (inner.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String piece : inner.split("\\},")) {
            String object = piece.trim();
            if (!object.startsWith("{")) {
                object = "{" + object;
            }
            if (!object.endsWith("}")) {
                object = object + "}";
            }
            result.add(object);
        }
        return result;
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /** One persisted V62 audit row reduced to the fields the clause cares about. */
    private record AuditRow(String action, String outcome, String subject, String unitId,
                            String purpose, String correlationId, String organizationId) {
    }

    /**
     * Read persisted audit rows through the migration owner, binding the tenant
     * because V62 applies FORCE ROW LEVEL SECURITY to the owner as well.
     *
     * @param org        owning organization
     * @param resourceId optional exact resource filter, or {@code null} for all
     * @return persisted rows in insertion order
     * @throws Exception when the rows cannot be read
     */
    private static List<AuditRow> auditRows(String org, String resourceId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            String sql = "SELECT action, outcome, subject, unit_id, purpose, correlation_id, organization_id "
                    + "FROM public.messaging_audit_event WHERE organization_id = '" + org + "'"
                    + (resourceId == null ? "" : " AND resource_id = '" + resourceId + "'")
                    + " ORDER BY seq";
            List<AuditRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    rows.add(new AuditRow(result.getString("action"), result.getString("outcome"),
                            result.getString("subject"), result.getString("unit_id"),
                            result.getString("purpose"), result.getString("correlation_id"),
                            result.getString("organization_id")));
                }
            }
            return rows;
        }
    }

    /**
     * Count every audit row owned by a tenant without appending an audit fact.
     *
     * @param org owning organization
     * @return number of persisted facts
     * @throws Exception when the count cannot be read
     */
    private static long countAuditRows(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.messaging_audit_event WHERE organization_id = '" + org + "'")) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    @Test
    void recordEveryWorkflowActionAppearsInTheTenantAuditTrail() throws Exception {
        // Each action is recorded by a role the action matrix explicitly allows.
        String[][] cases = {
                {"open", PATIENT},
                {"view", PATIENT},
                {"send", CLINICIAN},
                {"assign", ADMIN_SUBJECT},
                {"download", PATIENT},
                {"acknowledge", PATIENT},
                {"file", CLINICIAN},
                {"correct", CLINICIAN},
                {"restrict", ADMIN_SUBJECT}
        };
        Set<String> recorded = new LinkedHashSet<>();
        for (String[] testCase : cases) {
            String action = testCase[0];
            String subject = testCase[1];
            String correlation = UUID.randomUUID().toString();
            ResponseEntity<String> response = postAction(action, RESOURCE_WORKFLOW, "unit-a",
                    subject, ORG_A, correlation);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            String body = response.getBody();
            assertEquals(action, field(body, "action"), body);
            assertEquals("success", field(body, "outcome"), body);
            assertEquals(subject, field(body, "subject"), body);
            assertEquals("unit-a", field(body, "unitId"), body);
            assertEquals(PURPOSE, field(body, "purpose"), body);
            assertEquals(correlation, field(body, "correlationId"), body);
            assertEquals(ORG_A, field(body, "organizationId"), body);
            assertEquals(RESOURCE_WORKFLOW, field(body, "resourceId"), body);
            recorded.add(action);
        }
        assertEquals(9, recorded.size());

        // The tenant audit endpoint returns each action as a separate fact and
        // appends the review itself as an additional audit_read fact.
        ResponseEntity<String> trail = audit(AUDITOR_A, ORG_A, "audit-all");
        assertEquals(HttpStatus.OK, trail.getStatusCode(), trail.getBody());
        List<String> trailEvents = events(trail.getBody());
        Set<String> trailActions = trailEvents.stream()
                .map(event -> field(event, "action")).collect(Collectors.toSet());
        assertTrue(trailActions.containsAll(CLAUSE_ACTIONS), trail.getBody());
        assertTrue(trailActions.contains("audit_read"), "reading the trail is appended: " + trail.getBody());
        assertTrue(trailEvents.stream().allMatch(event -> ORG_A.equals(field(event, "organizationId"))),
                trail.getBody());
        assertTrue(trailEvents.stream()
                .filter(event -> CLAUSE_ACTIONS.contains(field(event, "action")))
                .allMatch(event -> "success".equals(field(event, "outcome"))), trail.getBody());

        // The resource-scoped read returns only that conversation's facts.
        ResponseEntity<String> filtered = auditFiltered(AUDITOR_A, ORG_A, RESOURCE_WORKFLOW, "audit-filtered");
        assertEquals(HttpStatus.OK, filtered.getStatusCode(), filtered.getBody());
        List<String> filteredEvents = events(filtered.getBody());
        assertTrue(filteredEvents.stream().allMatch(
                event -> RESOURCE_WORKFLOW.equals(field(event, "resourceId"))), filtered.getBody());
        Set<String> filteredActions = filteredEvents.stream()
                .map(event -> field(event, "action")).collect(Collectors.toSet());
        assertTrue(filteredActions.containsAll(CLAUSE_ACTIONS), filtered.getBody());
        assertTrue(filteredActions.contains("audit_read"), filtered.getBody());

        // The same nine success facts survive in V62's append-only ledger.
        List<AuditRow> rows = auditRows(ORG_A, RESOURCE_WORKFLOW);
        Set<String> storedActions = rows.stream().map(AuditRow::action).collect(Collectors.toSet());
        assertTrue(storedActions.containsAll(CLAUSE_ACTIONS), storedActions.toString());
        assertTrue(rows.stream().filter(row -> CLAUSE_ACTIONS.contains(row.action()))
                .allMatch(row -> "success".equals(row.outcome())
                        && PURPOSE.equals(row.purpose())
                        && "unit-a".equals(row.unitId())
                        && ORG_A.equals(row.organizationId())), rows.toString());
    }

    @Test
    void wrongRoleCannotRecordFileOrRestrict() throws Exception {
        ResponseEntity<String> file = postAction("file", RESOURCE_DENIAL, "unit-a",
                PATIENT, ORG_A, "deny-file");
        assertEquals(403, file.getStatusCode().value(), file.getBody());
        assertEquals("FORBIDDEN", field(file.getBody(), "code"), file.getBody());

        ResponseEntity<String> restrict = postAction("restrict", RESOURCE_DENIAL, "unit-a",
                PATIENT, ORG_A, "deny-restrict");
        assertEquals(403, restrict.getStatusCode().value(), restrict.getBody());
        assertEquals("FORBIDDEN", field(restrict.getBody(), "code"), restrict.getBody());

        // Each refusal appends its own non-success fact for the attempted action.
        List<AuditRow> denied = auditRows(ORG_A, RESOURCE_DENIAL).stream()
                .filter(row -> "denied".equals(row.outcome())).toList();
        assertEquals(2, denied.size(), denied.toString());
        assertEquals(Set.of("file", "restrict"),
                denied.stream().map(AuditRow::action).collect(Collectors.toSet()));
        assertTrue(denied.stream().allMatch(row -> PATIENT.equals(row.subject())
                && ORG_A.equals(row.organizationId())), denied.toString());

        ResponseEntity<String> trail = auditFiltered(AUDITOR_A, ORG_A, RESOURCE_DENIAL, "audit-denials");
        assertEquals(HttpStatus.OK, trail.getStatusCode(), trail.getBody());
        List<String> trailEvents = events(trail.getBody());
        assertTrue(trailEvents.stream().anyMatch(event -> "file".equals(field(event, "action"))
                && "denied".equals(field(event, "outcome"))), trail.getBody());
        assertTrue(trailEvents.stream().anyMatch(event -> "restrict".equals(field(event, "action"))
                && "denied".equals(field(event, "outcome"))), trail.getBody());
    }

    @Test
    void crossTenantCannotReadAnotherTenantsAuditFacts() throws Exception {
        ResponseEntity<String> written = postAction("open", RESOURCE_CROSS_TENANT, "unit-a",
                PATIENT, ORG_A, "cross-a-write");
        assertEquals(HttpStatus.OK, written.getStatusCode(), written.getBody());
        assertEquals(ORG_A, field(written.getBody(), "organizationId"), written.getBody());
        assertEquals(1, auditRows(ORG_A, RESOURCE_CROSS_TENANT).stream()
                .filter(row -> "open".equals(row.action())).count());

        // The other tenant's privacy auditor can read its own trail, but none of
        // the first tenant's facts appear in it.
        ResponseEntity<String> otherTrail = audit(AUDITOR_B, ORG_B, "cross-b-read");
        assertEquals(HttpStatus.OK, otherTrail.getStatusCode(), otherTrail.getBody());
        List<String> otherEvents = events(otherTrail.getBody());
        assertTrue(otherEvents.stream().allMatch(event -> ORG_B.equals(field(event, "organizationId"))),
                otherTrail.getBody());
        assertTrue(otherEvents.stream().noneMatch(event -> RESOURCE_CROSS_TENANT.equals(
                field(event, "resourceId")) && "open".equals(field(event, "action"))), otherTrail.getBody());
        assertTrue(otherEvents.stream().anyMatch(event -> "audit_read".equals(field(event, "action"))),
                "the ORG_B read is appended, so the assertion is not vacuous: " + otherTrail.getBody());
        assertTrue(auditRows(ORG_B, RESOURCE_CROSS_TENANT).isEmpty(),
                "ORG_B must hold no copy of ORG_A's fact");

        // Even an explicit filter by the other tenant's resource returns no
        // workflow fact; only the read's own audit_read comes back.
        ResponseEntity<String> filtered = auditFiltered(AUDITOR_B, ORG_B,
                RESOURCE_CROSS_TENANT, "cross-b-filtered");
        assertEquals(HttpStatus.OK, filtered.getStatusCode(), filtered.getBody());
        List<String> filteredEvents = events(filtered.getBody());
        assertFalse(filteredEvents.isEmpty(), "the ORG_B read appends its own fact: " + filtered.getBody());
        assertTrue(filteredEvents.stream().anyMatch(event -> "audit_read".equals(field(event, "action"))
                && AUDITOR_B.equals(field(event, "subject"))), filtered.getBody());
        assertTrue(filteredEvents.stream().noneMatch(
                event -> CLAUSE_ACTIONS.contains(field(event, "action"))), filtered.getBody());
        assertTrue(filteredEvents.stream().allMatch(
                event -> ORG_B.equals(field(event, "organizationId"))), filtered.getBody());
    }

    @Test
    void governanceCommandCannotBeSelfReportedAsWorkflowAction() throws Exception {
        long before = countAuditRows(ORG_A);
        ResponseEntity<String> refused = postAction("hold_place", RESOURCE_COMMAND, "unit-a",
                ADMIN_SUBJECT, ORG_A, "self-report-hold");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_ACTION", field(refused.getBody(), "code"), refused.getBody());
        assertEquals(before, countAuditRows(ORG_A), "a refused command appends no fact");
        assertTrue(auditRows(ORG_A, RESOURCE_COMMAND).isEmpty());

        ResponseEntity<String> trail = auditFiltered(AUDITOR_A, ORG_A, RESOURCE_COMMAND, "audit-self-report");
        assertEquals(HttpStatus.OK, trail.getStatusCode(), trail.getBody());
        assertTrue(events(trail.getBody()).stream()
                .allMatch(event -> "audit_read".equals(field(event, "action"))), trail.getBody());
    }

    @Test
    void invalidResourceIdIsRefused() throws Exception {
        long before = countAuditRows(ORG_A);
        ResponseEntity<String> refused = postAction("open", "not-a-uuid", "unit-a",
                PATIENT, ORG_A, "bad-resource-id");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_RESOURCEID", field(refused.getBody(), "code"), refused.getBody());
        assertEquals(before, countAuditRows(ORG_A), "validation failure appends no audit fact");
    }
}
