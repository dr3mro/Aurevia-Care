package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.flywaydb.core.Flyway;

/** S-G0-03 acceptance: versioned tenant configuration, named approval, effective dates, append-only history (ADM-01/OPS-02). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_config_it")
@ActiveProfiles("synthetic-auth")
class ConfigurationIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_config_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_config_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_config_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_config_it WITH (FORCE)");
        }
    }

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

    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> create(String key, String value, String effectiveFrom,
                                          String actor, String organization, String correlation) {
        String body = "{\"key\":\"" + key + "\",\"value\":\"" + value + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"") + "}";
        return request(HttpMethod.POST, "/api/v1/configuration", body, actor, organization, correlation);
    }

    private ResponseEntity<String> approve(String key, int version, String actor,
                                           String organization, String correlation, String body) {
        return request(HttpMethod.POST, "/api/v1/configuration/" + key + "/versions/" + version + "/approve",
                body == null ? "{}" : body, actor, organization, correlation);
    }

    private ResponseEntity<String> get(String key, String actor, String organization) {
        return get(key, actor, organization, "cfg-read");
    }

    private ResponseEntity<String> get(String key, String actor, String organization, String correlation) {
        return request(HttpMethod.GET, "/api/v1/configuration/" + key, null, actor, organization, correlation);
    }

    private ResponseEntity<String> history(String key, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/configuration/" + key + "/history",
                null, actor, organization, "cfg-history");
    }

    private String adminId(String organization, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
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
    void approveMakesConfigurationEffective() throws Exception {
        ResponseEntity<String> draft = create("scheduling.slot", "30", null,
                "admin-a", ORG_A, "cfg-create-1");
        assertEquals(HttpStatus.CREATED, draft.getStatusCode(), draft.getBody());
        assertEquals("pending", field(draft.getBody(), "status"));
        assertEquals(HttpStatus.NOT_FOUND, get("scheduling.slot", "admin-a", ORG_A).getStatusCode());

        ResponseEntity<String> approved = approve("scheduling.slot", 1, "admin-a", ORG_A,
                "cfg-approve-1", "{\"note\":\"G0 policy\"}");
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
        assertEquals("approved", field(approved.getBody(), "status"));
        assertEquals(adminId(ORG_A, "admin-a"), field(approved.getBody(), "approvedBy"));

        ResponseEntity<String> effective = get("scheduling.slot", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, effective.getStatusCode());
        assertEquals("30", field(effective.getBody(), "value"));
        assertEquals("approved", field(effective.getBody(), "status"));
    }

    @Test
    void futureDatedApprovalIsNotEffectiveEarly() {
        String future = Instant.now().plus(Duration.ofDays(1)).toString();
        assertEquals(HttpStatus.CREATED,
                create("release.timing", "immediate", future, "admin-a", ORG_A, "cfg-create-2").getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("release.timing", 1, "admin-a", ORG_A, "cfg-approve-2", null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("release.timing", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void newerApprovedVersionSupersedesOlder() {
        assertEquals(HttpStatus.CREATED,
                create("coverage.hours", "08-18", Instant.now().minusSeconds(60).toString(),
                        "admin-a", ORG_A, "cfg-create-3").getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("coverage.hours", 1, "admin-a", ORG_A, "cfg-approve-3", null).getStatusCode());
        assertEquals(HttpStatus.CREATED,
                create("coverage.hours", "09-17", Instant.now().minusSeconds(30).toString(),
                        "admin-a", ORG_A, "cfg-create-4").getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("coverage.hours", 2, "admin-a", ORG_A, "cfg-approve-4", null).getStatusCode());
        assertEquals("09-17", field(get("coverage.hours", "admin-a", ORG_A).getBody(), "value"));
        assertEquals(2, countObjects(history("coverage.hours", "admin-a", ORG_A).getBody(), "entries"));
    }

    @Test
    void entriesAndApprovalsAreAppendOnly() throws Exception {
        create("ledger.mode", "append", null, "admin-a", ORG_A, "cfg-create-5");
        approve("ledger.mode", 1, "admin-a", ORG_A, "cfg-approve-5", null);
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.config_entry SET value = 'changed'"));
            assertThrows(SQLException.class, () -> statement.execute("DELETE FROM public.config_approval"));
        }
    }

    @Test
    void crossTenantConfigurationIsConcealed() {
        assertEquals(HttpStatus.CREATED,
                create("secret.policy", "hidden", null, "admin-a", ORG_A, "cfg-create-6").getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("secret.policy", 1, "admin-a", ORG_A, "cfg-approve-6", null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("secret.policy", "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, history("secret.policy", "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                approve("secret.policy", 1, "admin-b", ORG_B, "cfg-approve-7", null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("secret.policy", "admin-a", ORG_B).getStatusCode());
    }

    @Test
    void unauthorizedAndInvalidRequestsAreDenied() {
        assertEquals(HttpStatus.FORBIDDEN,
                create("x.policy", "v", null, "reception-a", ORG_A, "cfg-deny-1").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                create("x.policy", "v", null, null, ORG_A, "cfg-deny-2").getStatusCode());
        assertEquals(422,
                create("x.policy", "v", null, "admin-a", "bad-org", "cfg-deny-3").getStatusCode().value());
        assertEquals(422,
                create("Bad", "v", null, "admin-a", ORG_A, "cfg-deny-4").getStatusCode().value());
        assertEquals(422,
                create("x.policy", " ", null, "admin-a", ORG_A, "cfg-deny-5").getStatusCode().value());
        assertEquals(422,
                create("x.policy", "v", "not-a-date", "admin-a", ORG_A, "cfg-deny-6").getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND,
                approve("x.policy", 99, "admin-a", ORG_A, "cfg-deny-7", null).getStatusCode());
    }

    @Test
    void approvalIsNotTakenFromRequestBody() throws Exception {
        create("grant.policy", "v", null, "admin-a", ORG_A, "cfg-create-8");
        String forged = "{\"note\":\"x\",\"approvedBy\":\"" + adminId(ORG_B, "admin-b") + "\"}";
        ResponseEntity<String> approved = approve("grant.policy", 1, "admin-a", ORG_A, "cfg-approve-8", forged);
        assertEquals(HttpStatus.OK, approved.getStatusCode());
        assertEquals(adminId(ORG_A, "admin-a"), field(approved.getBody(), "approvedBy"));
    }

    @Test
    void duplicateApprovalConflicts() {
        create("once.policy", "v", null, "admin-a", ORG_A, "cfg-create-9");
        assertEquals(HttpStatus.OK,
                approve("once.policy", 1, "admin-a", ORG_A, "cfg-approve-9", null).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                approve("once.policy", 1, "admin-a", ORG_A, "cfg-approve-10", null).getStatusCode());
    }

    @Test
    void configurationActionsAreAudited() throws Exception {
        create("audit.policy", "v", null, "admin-a", ORG_A, "cfg-audit-1");
        approve("audit.policy", 1, "admin-a", ORG_A, "cfg-audit-2", null);
        get("audit.policy", "admin-a", ORG_A, "cfg-audit-3");
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            List<String> actions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT action FROM public.audit_event WHERE correlation_id IN "
                    + "('cfg-audit-1','cfg-audit-2','cfg-audit-3') ORDER BY seq")) {
                while (result.next()) {
                    actions.add(result.getString(1));
                }
            }
            assertEquals(List.of("CONFIG_CREATE", "CONFIG_APPROVE", "CONFIG_READ"), actions);
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE correlation_id IN "
                    + "('cfg-audit-1','cfg-audit-2','cfg-audit-3') AND actor_id = '" + adminId(ORG_A, "admin-a")
                    + "' AND purpose = 'CONFIG_ADMIN' AND scope = 'CONFIG' AND outcome = 'SUCCESS'")) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
            }
        }
    }

    @Test
    void historyHasNoCrossTenantRows() {
        create("isolated.policy", "v", null, "admin-a", ORG_A, "cfg-create-10");
        String body = history("isolated.policy", "admin-a", ORG_A).getBody();
        assertFalse(body.contains(ORG_B));
    }
}
