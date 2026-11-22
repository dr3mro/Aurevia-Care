package com.aurevia.smartclinic.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * ADM-01 clause 3 acceptance: pilot site onboarding tests each configured channel.
 *
 * <p>This behaviour is not built. The configuration domain only supports draft, named
 * approval, effective reads and append-only history, and there is no channel-test
 * operation, channel invocation adapter or per-channel outcome record. Every method
 * below is complete and compile-safe but disabled with a GAP reason so a later
 * task can enable it against the surface described in
 * {@code docs/spec/clauses/configuration/pilot-site-onboarding-tests-configured-channel.md}.
 * No production code is added by this test.</p>
 *
 * <p>The fixture mirrors {@code it/ConfigurationIsolationTest}: a real PostgreSQL
 * database migrated with Flyway, two synthetic tenants, the synthetic-auth header set
 * and HTTP requests whose paths stay string literals so the class compiles today
 * without a new production type.</p>
 */
@Tag("integration")
@Disabled("GAP: the ADM-01 clause 3 channel-test surface is not implemented. The class is "
        + "disabled so no Spring context or database is required; each method also records "
        + "its own reason. No production code is added by this test.")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_adm01_channel_it")
@ActiveProfiles("synthetic-auth")
class PilotSiteOnboardingTestsConfiguredChannelTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_adm01_channel_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    // The primary/backup keys are real critical-result escalation configuration keys;
    // the urgent/downtime keys are synthetic placeholders (V92 models those two channels
    // as service-profile columns, not configuration keys).
    private static final String PRIMARY_KEY = "critical.escalation.primary_channel";
    private static final String BACKUP_KEY = "critical.escalation.backup_channel";
    private static final String URGENT_KEY = "messaging.urgent_channel";
    private static final String DOWNTIME_KEY = "messaging.downtime_channel";
    private static final String UNRELATED_KEY = "release.timing";

    private static final String PRIMARY_VALUE = "synthetic-primary";
    private static final String BACKUP_VALUE = "synthetic-backup";
    private static final String URGENT_VALUE = "synthetic-urgent";
    private static final String DOWNTIME_VALUE = "synthetic-downtime";
    private static final String UNRELATED_VALUE = "unrelated-policy";
    private static final String FOREIGN_VALUE = "other-tenant-hidden";

    // Would-be surface: a server-selected channel test, so a caller cannot omit the
    // backup channel. The path is a string literal: no Java type refers to it yet.
    private static final String CHANNEL_TEST_PATH = "/api/v1/configuration/channel-tests";
    private static final String CHANNEL_TEST_ACTION = "CONFIG_CHANNEL_TEST";

    private static final List<String> CHANNEL_KEYS = List.of(
            PRIMARY_KEY, BACKUP_KEY, URGENT_KEY, DOWNTIME_KEY);

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_adm01_channel_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_adm01_channel_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_adm01_channel_it WITH (FORCE)");
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

    private ResponseEntity<String> create(String key, String value, String actor,
                                          String organization, String correlation) {
        String body = "{\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
        return request(HttpMethod.POST, "/api/v1/configuration", body, actor, organization, correlation);
    }

    private ResponseEntity<String> approve(String key, String actor, String organization, String correlation) {
        return request(HttpMethod.POST,
                "/api/v1/configuration/" + key + "/versions/1/approve", "{}",
                actor, organization, correlation);
    }

    private ResponseEntity<String> testChannels(String subject, String organization, String correlation) {
        return request(HttpMethod.POST, CHANNEL_TEST_PATH, "{}", subject, organization, correlation);
    }

    private void configureChannel(String key, String value, String actor, String organization, String prefix) {
        assertEquals(HttpStatus.CREATED, create(key, value, actor, organization, prefix + "-create").getStatusCode());
        assertEquals(HttpStatus.OK, approve(key, actor, organization, prefix + "-approve").getStatusCode());
    }

    private void configureChannels() {
        configureChannel(PRIMARY_KEY, PRIMARY_VALUE, "admin-a", ORG_A, "adm01-setup-primary");
        configureChannel(BACKUP_KEY, BACKUP_VALUE, "admin-a", ORG_A, "adm01-setup-backup");
        configureChannel(URGENT_KEY, URGENT_VALUE, "admin-a", ORG_A, "adm01-setup-urgent");
        configureChannel(DOWNTIME_KEY, DOWNTIME_VALUE, "admin-a", ORG_A, "adm01-setup-downtime");
        // An approved, unrelated key must not be treated as a channel under test.
        configureChannel(UNRELATED_KEY, UNRELATED_VALUE, "admin-a", ORG_A, "adm01-setup-unrelated");
        // The same channel key in another tenant must never be selected.
        configureChannel(PRIMARY_KEY, FOREIGN_VALUE, "admin-b", ORG_B, "adm01-setup-foreign");
    }

    private int auditCount(String correlation, String action, String outcome) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE correlation_id = '" + correlation
                            + "' AND action = '" + action + "' AND outcome = '" + outcome + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
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

    /**
     * Rule met: onboarding exercises every configured channel and records each outcome.
     * Disabled because no channel-test operation, channel adapter or outcome record exists.
     */
    @Test
    @Disabled("GAP: no channel-test onboarding operation, channel invocation adapter or "
            + "per-channel outcome/audit record exists. ConfigurationController only exposes "
            + "draft/approve/get/history. No production code is added by this test.")
    void onboardingTestsEveryConfiguredChannel() throws Exception {
        configureChannels();

        ResponseEntity<String> response = testChannels("admin-a", ORG_A, "adm01-channel-ok");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals(ORG_A, field(response.getBody(), "organizationId"));
        assertEquals(4, countObjects(response.getBody(), "results"));
        for (String key : CHANNEL_KEYS) {
            assertTrue(response.getBody().contains("\"" + key + "\""), "channel not tested: " + key);
        }
        assertTrue(response.getBody().contains(PRIMARY_VALUE));
        assertTrue(response.getBody().contains(BACKUP_VALUE));
        assertTrue(response.getBody().contains(URGENT_VALUE));
        assertTrue(response.getBody().contains(DOWNTIME_VALUE));
        assertFalse(response.getBody().contains(UNRELATED_VALUE));
        assertFalse(response.getBody().contains(FOREIGN_VALUE));
        assertFalse(response.getBody().contains(ORG_B));
        assertEquals(4, auditCount("adm01-channel-ok", CHANNEL_TEST_ACTION, "SUCCESS"));
    }

    /**
     * Negative: a reception role cannot run a channel test.
     * Disabled because the protected operation does not exist yet.
     */
    @Test
    @Disabled("GAP: no protected channel-test onboarding operation exists, so wrong-role "
            + "refusal and its audit cannot be exercised. No production code is added by this test.")
    void onboardingTestRefusesWrongRole() throws Exception {
        configureChannels();

        ResponseEntity<String> response = testChannels("reception-a", ORG_A, "adm01-channel-denied-role");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals("FORBIDDEN", field(response.getBody(), "code"));
        assertFalse(response.getBody().contains("results"));
        assertEquals(0, auditCount("adm01-channel-denied-role", CHANNEL_TEST_ACTION, "SUCCESS"));
    }

    /**
     * Negative: an active identity asserting another tenant is refused and no channel is invoked.
     * Disabled because the protected, tenant-scoped operation does not exist yet.
     */
    @Test
    @Disabled("GAP: no tenant-scoped channel-test onboarding operation or channel invocation "
            + "exists, so foreign-tenant refusal cannot be exercised. No production code is added by this test.")
    void onboardingTestRefusesAnotherTenant() throws Exception {
        configureChannels();

        ResponseEntity<String> foreign = testChannels("admin-a", ORG_B, "adm01-channel-denied-tenant");
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatusCode(), foreign.getBody());
        assertFalse(foreign.getBody().contains(PRIMARY_VALUE));
        assertFalse(foreign.getBody().contains(ORG_A));
        assertEquals(0, auditCount("adm01-channel-denied-tenant", CHANNEL_TEST_ACTION, "SUCCESS"));

        ResponseEntity<String> own = testChannels("admin-a", ORG_A, "adm01-channel-repeat");
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertEquals(4, countObjects(own.getBody(), "results"));
    }

    /**
     * Negative: a missing or unapproved channel configuration fails closed before any test runs.
     * Disabled because no channel-test operation reads and validates approved channel config.
     */
    @Test
    @Disabled("GAP: no channel-test onboarding operation validates approved channel configuration "
            + "before invoking channels. No production code is added by this test.")
    void onboardingTestRejectsMissingOrUnapprovedChannelConfig() throws Exception {
        configureChannel(PRIMARY_KEY, PRIMARY_VALUE, "admin-a", ORG_A, "adm01-partial-primary");
        configureChannel(BACKUP_KEY, BACKUP_VALUE, "admin-a", ORG_A, "adm01-partial-backup");
        configureChannel(DOWNTIME_KEY, DOWNTIME_VALUE, "admin-a", ORG_A, "adm01-partial-downtime");

        // URGENT_KEY was never configured: the operation must refuse before testing anything.
        ResponseEntity<String> missing = testChannels("admin-a", ORG_A, "adm01-channel-missing");
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());
        assertFalse(missing.getBody().contains("results"));
        assertEquals(0, auditCount("adm01-channel-missing", CHANNEL_TEST_ACTION, "SUCCESS"));

        // A created but unapproved channel version is not effective and must also refuse.
        assertEquals(HttpStatus.CREATED,
                create(URGENT_KEY, URGENT_VALUE, "admin-a", ORG_A, "adm01-channel-pending-create").getStatusCode());
        ResponseEntity<String> pending = testChannels("admin-a", ORG_A, "adm01-channel-pending");
        assertEquals(422, pending.getStatusCode().value(), pending.getBody());
        assertTrue(pending.getBody().contains(URGENT_KEY));
        assertFalse(pending.getBody().contains(URGENT_VALUE));
        assertFalse(pending.getBody().contains("results"));
        assertEquals(0, auditCount("adm01-channel-pending", CHANNEL_TEST_ACTION, "SUCCESS"));
    }
}
