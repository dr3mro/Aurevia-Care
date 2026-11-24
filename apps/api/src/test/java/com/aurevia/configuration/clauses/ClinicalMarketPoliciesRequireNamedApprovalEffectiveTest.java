package com.aurevia.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
 * ADM-01 clause 2 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>Clinical and market policies are stored as versioned tenant configuration. A
 * draft is never effective on its own: a separately named approval must be appended,
 * the approval is attributed to the authenticated actor rather than the request body,
 * the version is served only from its effective instant and every command leaves a
 * correlated audit event. This class proves only that clause through the normal HTTP
 * entry point; the negative cases show a wrong role, a future window, another tenant,
 * malformed input and a repeated approval being refused. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_adm01_clause2_it")
@ActiveProfiles("synthetic-auth")
class ClinicalMarketPoliciesRequireNamedApprovalEffectiveTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_adm01_clause2_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Bind the HTTP client to the random port chosen for this context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create an isolated database with the migrated configuration schema and three
     * synthetic principals: an admin and a receptionist in ORG_A, an admin in ORG_B.
     * The dedicated name prevents collision with other clause suites that drop their
     * own database in setup.
     *
     * @throws Exception when PostgreSQL or Flyway setup fails
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
            statement.execute("DROP DATABASE IF EXISTS orevia_adm01_clause2_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_adm01_clause2_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    /**
     * Drop this suite's database so a later run starts from a clean schema.
     *
     * @throws Exception when PostgreSQL is unreachable
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_adm01_clause2_it WITH (FORCE)");
        }
    }

    /**
     * Seed one synthetic member with an active membership and one role in their
     * organization. The actor GUC is set because the V4 triggers derive the grant
     * identity from the authenticated subject.
     */
    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Build the synthetic identity, tenant and correlation headers of one request. */
    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Send one HTTP request and capture its status and body without throwing on errors. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Append one draft version through the normal create endpoint. */
    private ResponseEntity<String> create(String key, String value, String effectiveFrom,
                                          String actor, String organization, String correlation) {
        String body = "{\"key\":\"" + key + "\",\"value\":\"" + value + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"") + "}";
        return request(HttpMethod.POST, "/api/v1/configuration", body, actor, organization, correlation);
    }

    /** Append one named approval through the normal approve endpoint. */
    private ResponseEntity<String> approve(String key, int version, String actor,
                                           String organization, String correlation, String body) {
        return request(HttpMethod.POST, "/api/v1/configuration/" + key + "/versions/" + version + "/approve",
                body == null ? "{}" : body, actor, organization, correlation);
    }

    /** Read the effective version of a configuration key. */
    private ResponseEntity<String> get(String key, String actor, String organization, String correlation) {
        return request(HttpMethod.GET, "/api/v1/configuration/" + key,
                null, actor, organization, correlation);
    }

    /** Read the append-only history of a configuration key. */
    private ResponseEntity<String> history(String key, String actor, String organization, String correlation) {
        return request(HttpMethod.GET, "/api/v1/configuration/" + key + "/history",
                null, actor, organization, correlation);
    }

    /** Resolve the durable user identifier of a synthetic principal, RLS-scoped to its tenant. */
    private String adminId(String organization, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    /** Extract one top-level scalar from the simple JSON projections returned by the controller. */
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

    /** Count objects inside a top-level JSON array field of a history projection. */
    private static int countObjects(String json, String key) {
        String needle = "\"" + key + "\":[";
        int start = json.indexOf(needle);
        if (start < 0) {
            return 0;
        }
        int from = start + needle.length();
        int depth = 1;
        int i = from;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i++);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        String body = json.substring(from, i - 1);
        int count = 0;
        boolean inString = false;
        for (char c : body.toCharArray()) {
            if (c == '"') {
                inString = !inString;
            } else if (c == '{' && !inString) {
                count++;
            }
        }
        return count;
    }

    @Test
    void namedApprovalEffectiveDatesAuditAndVersionHistoryAreEnforced() throws Exception {
        String effectiveFrom = Instant.now().minusSeconds(60).toString();
        ResponseEntity<String> draft = create("clinical.release", "policy-v1", effectiveFrom,
                "admin-a", ORG_A, "adm01-c2-create");
        assertEquals(HttpStatus.CREATED, draft.getStatusCode(), draft.getBody());
        assertEquals("pending", field(draft.getBody(), "status"));
        assertEquals("policy-v1", field(draft.getBody(), "value"));
        // A draft with an already-open window is still invisible until a named
        // approval exists; otherwise "require named approval" is not proven.
        assertEquals(HttpStatus.NOT_FOUND,
                get("clinical.release", "admin-a", ORG_A, "adm01-c2-draft-read").getStatusCode());

        // The request body carries a forged approver; the database trigger and the
        // service must both derive the named approver from the authenticated actor.
        String forged = "{\"note\":\"release approved\",\"approvedBy\":\"" + adminId(ORG_B, "admin-b") + "\"}";
        ResponseEntity<String> approved = approve("clinical.release", 1, "admin-a", ORG_A,
                "adm01-c2-approve", forged);
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
        assertEquals("approved", field(approved.getBody(), "status"));
        assertEquals(adminId(ORG_A, "admin-a"), field(approved.getBody(), "approvedBy"));
        assertEquals("release approved", field(approved.getBody(), "note"));

        ResponseEntity<String> effective = get("clinical.release", "admin-a", ORG_A, "adm01-c2-read");
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        assertEquals("policy-v1", field(effective.getBody(), "value"));
        assertEquals("approved", field(effective.getBody(), "status"));

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // FORCE RLS hides every row without the tenant GUC, so set it before
            // reading the audit chain or trying to mutate the immutable ledgers.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            List<String> actions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT action FROM public.audit_event WHERE correlation_id IN "
                    + "('adm01-c2-create','adm01-c2-approve') ORDER BY seq")) {
                while (result.next()) {
                    actions.add(result.getString(1));
                }
            }
            assertEquals(List.of("CONFIG_CREATE", "CONFIG_APPROVE"), actions);
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE correlation_id IN "
                    + "('adm01-c2-create','adm01-c2-approve') AND actor_id = '" + adminId(ORG_A, "admin-a")
                    + "' AND purpose = 'CONFIG_ADMIN' AND scope = 'CONFIG' AND outcome = 'SUCCESS'")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            // The forged approver must never reach storage: the V5 actor trigger
            // overwrites approved_by from app.actor, not from the request body.
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.config_approval WHERE entry_id = "
                    + "(SELECT id FROM public.config_entry WHERE key = 'clinical.release' AND version = 1) "
                    + "AND approved_by = '" + adminId(ORG_A, "admin-a") + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }

            SQLException updateEntry = assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.config_entry SET value = 'changed'"));
            assertEquals("44000", updateEntry.getSQLState(), updateEntry.getMessage());
            SQLException deleteEntry = assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.config_entry"));
            assertEquals("44000", deleteEntry.getSQLState(), deleteEntry.getMessage());
            SQLException updateApproval = assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.config_approval SET note = 'changed'"));
            assertEquals("44000", updateApproval.getSQLState(), updateApproval.getMessage());
            SQLException deleteApproval = assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.config_approval"));
            assertEquals("44000", deleteApproval.getSQLState(), deleteApproval.getMessage());
        }

        ResponseEntity<String> history = history("clinical.release", "admin-a", ORG_A, "adm01-c2-history");
        assertEquals(HttpStatus.OK, history.getStatusCode(), history.getBody());
        assertEquals(1, countObjects(history.getBody(), "entries"));
        assertTrue(history.getBody().contains("\"version\":1"), history.getBody());
    }

    @Test
    void approvalByWrongRoleIsForbidden() {
        assertEquals(HttpStatus.CREATED,
                create("approval.role", "policy-v1", null, "admin-a", ORG_A, "adm01-c2-role-create")
                        .getStatusCode());
        ResponseEntity<String> denied = approve("approval.role", 1, "reception-a", ORG_A,
                "adm01-c2-role-deny", null);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
    }

    @Test
    void futureDatedApprovalIsNotEffectiveEarly() {
        String future = Instant.now().plus(Duration.ofDays(1)).toString();
        assertEquals(HttpStatus.CREATED,
                create("approval.timing", "policy-v1", future, "admin-a", ORG_A, "adm01-c2-future-create")
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("approval.timing", 1, "admin-a", ORG_A, "adm01-c2-future-approve", null)
                        .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("approval.timing", "admin-a", ORG_A, "adm01-c2-future-read").getStatusCode());
    }

    @Test
    void crossTenantConfigurationIsConcealed() {
        assertEquals(HttpStatus.CREATED,
                create("approval.secret", "hidden", null, "admin-a", ORG_A, "adm01-c2-tenant-create")
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("approval.secret", 1, "admin-a", ORG_A, "adm01-c2-tenant-approve", null)
                        .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("approval.secret", "admin-b", ORG_B, "adm01-c2-tenant-read").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                history("approval.secret", "admin-b", ORG_B, "adm01-c2-tenant-history").getStatusCode());
    }

    @Test
    void malformedEffectiveFromIsRefused() {
        ResponseEntity<String> refused = create("approval.invalid", "policy-v1", "not-a-date",
                "admin-a", ORG_A, "adm01-c2-invalid-create");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
    }

    @Test
    void duplicateApprovalIsRejected() {
        assertEquals(HttpStatus.CREATED,
                create("approval.once", "policy-v1", null, "admin-a", ORG_A, "adm01-c2-duplicate-create")
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("approval.once", 1, "admin-a", ORG_A, "adm01-c2-duplicate-approve", null)
                        .getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                approve("approval.once", 1, "admin-a", ORG_A, "adm01-c2-duplicate-again", null)
                        .getStatusCode());
    }
}
