package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

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
 * MSG-07 clause 6 (API evidence): a messaging resource's sender and recipient
 * retention windows are two distinct, durable values that survive a legal hold.
 *
 * <p>The domain contract ({@code MessagingAuditContract.RetentionDuty}), the
 * tenant-scoped store and V62's {@code messaging_retention_duty} table already
 * preserve the pair, and active tests prove those layers. This class records the
 * HTTP proof that is still missing: no controller route returns the duty pair and
 * no command creates one. Every test is complete but deliberately disabled with a
 * GAP reason so a later task can enable it when the route exists; no failing test
 * or production code is introduced here.</p>
 *
 * <p>Each test asserts a concrete status or code. In particular none of them reads
 * {@code GET /api/v1/messaging-governance/retention/{resourceId}}: that route
 * returns a retention eligibility decision, not the duty pair, so it cannot prove
 * this clause. Synthetic fixtures and forced row-level security only.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_retention_duty_it")
@ActiveProfiles("synthetic-auth")
class PreserveSenderRecipientRetentionDutiesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String RESOURCE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String RESOURCE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String UNKNOWN_RESOURCE = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_retention_duty_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    /** Would-be read route; named here only as future work, not wired by this test-only change. */
    static final String DUTY_ROUTE = "/api/v1/messaging-governance/retention-duty/";
    static final String HOLD_ROUTE = "/api/v1/messaging-governance/legal-holds";

    /** One reason shared by every disabled proof: the HTTP contract does not exist yet. */
    private static final String GAP = "GAP: no HTTP route returns the sender/recipient duty pair; "
            + "MessagingGovernanceService.retentionDuty has no controller entry point and no "
            + "HTTP command creates a duty. Enable these tests when that route is built.";

    RestClient rest;

    /** Build a client against the randomly assigned HTTP port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a fresh database, apply migrations and seed two isolated tenants and their duties. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_retention_duty_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_retention_duty_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "auditor-b", "privacy_auditor");
            seedResource(statement, ORG_A, RESOURCE_A);
            seedResource(statement, ORG_B, RESOURCE_B);
            seedDuty(statement, ORG_A, RESOURCE_A, 30L, 90L);
            seedDuty(statement, ORG_B, RESOURCE_B, 10L, 20L);
        }
    }

    /** Drop the disposable synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_retention_duty_it WITH (FORCE)");
        }
    }

    /** Create an organization, an active membership and one bounded role for a synthetic subject. */
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

    /** Seed one tracked messaging resource inside its tenant's forced-RLS scope. */
    private static void seedResource(Statement statement, String org, String resource) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.messaging_resource (organization_id, id, resource_type) "
                + "VALUES ('" + org + "', '" + resource + "', 'conversation')");
    }

    /** Seed distinct sender and recipient windows directly; V62 has no HTTP duty writer. */
    private static void seedDuty(Statement statement, String org, String resource,
                                 long senderDays, long recipientDays) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.messaging_retention_duty "
                + "(organization_id, resource_id, sender_retention_days, recipient_retention_days) "
                + "VALUES ('" + org + "', '" + resource + "', " + senderDays + ", " + recipientDays + ")");
    }

    /** Issue an HTTP request with explicit synthetic identity and active-organization headers. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                            String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Read one JSON scalar from a response body, quoted or numeric, without a JSON dependency. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
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

    /**
     * Rule met: the two windows are distinct durable values on the read path and a
     * legal hold never waives either party's duty. Disabled until the route exists.
     */
    @Test
    @Disabled(GAP)
    void retentionDutyKeepsBothDistinctWindowsAfterALegalHold() {
        ResponseEntity<String> before = request(HttpMethod.GET, DUTY_ROUTE + RESOURCE_A,
                null, "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, before.getStatusCode(), before.getBody());
        assertEquals("30", field(before.getBody(), "senderRetentionDays"));
        assertEquals("90", field(before.getBody(), "recipientRetentionDays"));

        String hold = "{\"resourceId\":\"" + RESOURCE_A + "\",\"reason\":\"Synthetic dispute\"}";
        ResponseEntity<String> placed = request(HttpMethod.POST, HOLD_ROUTE,
                hold, "auditor-a", ORG_A);
        assertEquals(HttpStatus.CREATED, placed.getStatusCode(), placed.getBody());

        ResponseEntity<String> after = request(HttpMethod.GET, DUTY_ROUTE + RESOURCE_A,
                null, "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, after.getStatusCode(), after.getBody());
        assertEquals("30", field(after.getBody(), "senderRetentionDays"));
        assertEquals("90", field(after.getBody(), "recipientRetentionDays"));
    }

    /**
     * Negative: a subject of another tenant must not see the first tenant's windows,
     * and the refusal must not leak either value. Disabled until the route exists.
     */
    @Test
    @Disabled(GAP)
    void anotherTenantCannotReadTheDutyAndSeesNoWindowValues() {
        ResponseEntity<String> refused = request(HttpMethod.GET, DUTY_ROUTE + RESOURCE_A,
                null, "auditor-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", field(refused.getBody(), "code"));
        assertEquals("", field(refused.getBody(), "senderRetentionDays"));
        assertEquals("", field(refused.getBody(), "recipientRetentionDays"));
        assertFalse(refused.getBody().contains("RetentionDays"),
                "a cross-tenant refusal must not carry either duty window");
    }

    /**
     * Negative: AUDIT_READ is reserved for governance roles; a reception member is
     * refused. Disabled until the route exists.
     */
    @Test
    @Disabled(GAP)
    void receptionCannotReadTheRetentionDuty() {
        ResponseEntity<String> denied = request(HttpMethod.GET, DUTY_ROUTE + RESOURCE_A,
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
    }

    /**
     * Negative: an unknown resource is concealed as a not-found duty rather than
     * answering with an empty or default pair. Disabled until the route exists.
     */
    @Test
    @Disabled(GAP)
    void unknownResourceHasNoReadableDuty() {
        ResponseEntity<String> missing = request(HttpMethod.GET, DUTY_ROUTE + UNKNOWN_RESOURCE,
                null, "auditor-a", ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode(), missing.getBody());
        assertEquals("NOT_FOUND", field(missing.getBody(), "code"));
    }
}
