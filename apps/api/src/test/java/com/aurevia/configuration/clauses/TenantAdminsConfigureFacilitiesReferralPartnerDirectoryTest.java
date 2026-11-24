package com.aurevia.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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
 * ADM-01 clause 1 (API evidence): a tenant admin can configure the built administration
 * surfaces and the versioned policy mechanism those surfaces share.
 *
 * <p>The positive tests define a facility, a referral partner directory entry and a
 * scheduling policy through their HTTP entry points, then approve a configured policy so
 * it becomes effective with a named approver and effective instant. The negative tests
 * show the same surfaces refusing a non-admin role, another tenant, an unknown version and
 * missing input. Every test uses its own configuration key and tenant-local code so the
 * shared synthetic database cannot produce cross-test conflicts.</p>
 *
 * <p>This class proves clause 1 only. The deeper audit-event and append-only history
 * assertions for the second ADM-01 sentence are already covered by
 * {@code com.aurevia.it.ConfigurationIsolationTest} and will be covered for the
 * clinical/market policy framing by the planned sibling clause test
 * {@code ClinicalMarketPoliciesRequireNamedApprovalEffectiveTest}.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_admin_configuration_it")
@ActiveProfiles("synthetic-auth")
class TenantAdminsConfigureFacilitiesReferralPartnerDirectoryTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_admin_configuration_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private RestClient rest;

    /** Create the throwaway tenant-isolated database and seed synthetic memberships. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_admin_configuration_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_admin_configuration_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
        }
    }

    /** Remove the throwaway database after the class finishes. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_admin_configuration_it WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned application port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * The built configure surfaces accept an administrator's definitions: a facility, a
     * referral partner directory entry that becomes effective after a named approval, and
     * a scheduling policy in the form of a visit type and its duration.
     */
    @Test
    void tenantAdminDefinesFacilityReferralPartnerAndSchedulingPolicy() {
        ResponseEntity<String> facility = request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"ADMCF01\",\"name\":\"Admin Clause Facility\","
                        + "\"timeZone\":\"Europe/London\"}",
                "admin-a", ORG_A, "admin-configure-facility");
        assertEquals(HttpStatus.CREATED, facility.getStatusCode(), facility.getBody());
        assertEquals("ADMCF01", field(facility.getBody(), "code"));

        ResponseEntity<String> partner = request(HttpMethod.POST, "/api/v1/catalogs/referral_partner/entries",
                "{\"code\":\"RPA01\",\"value\":\"Synthetic referral partner\"}",
                "admin-a", ORG_A, "admin-configure-partner");
        assertEquals(HttpStatus.CREATED, partner.getStatusCode(), partner.getBody());
        assertEquals("pending", field(partner.getBody(), "status"));

        ResponseEntity<String> approval = request(HttpMethod.POST,
                "/api/v1/catalogs/referral_partner/entries/RPA01/versions/1/approve",
                "{\"note\":\"Synthetic partner directory approval\"}",
                "admin-a", ORG_A, "admin-configure-partner-approve");
        assertEquals(HttpStatus.OK, approval.getStatusCode(), approval.getBody());
        assertEquals("approved", field(approval.getBody(), "status"));

        ResponseEntity<String> effective = request(HttpMethod.GET,
                "/api/v1/catalogs/referral_partner/entries/RPA01", null,
                "admin-a", ORG_A, "admin-configure-partner-read");
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        assertEquals("Synthetic referral partner", field(effective.getBody(), "value"));

        ResponseEntity<String> visitType = request(HttpMethod.POST, "/api/v1/scheduling/visit-types",
                "{\"code\":\"ADMFOLLOW\",\"durationMinutes\":30}",
                "admin-a", ORG_A, "admin-configure-scheduling");
        assertEquals(HttpStatus.CREATED, visitType.getStatusCode(), visitType.getBody());
        assertEquals("ADMFOLLOW", field(visitType.getBody(), "code"));
        assertEquals("30", field(visitType.getBody(), "durationMinutes"));
    }

    /**
     * An administrator approves a configured escalation policy so it becomes effective,
     * carrying the named approver and effective instant derived from the actor rather than
     * from the request body.
     */
    @Test
    void tenantAdminApprovesConfiguredPolicySoItBecomesEffective() throws Exception {
        ResponseEntity<String> draft = create("policy.escalation.approval", "notify-owner", null,
                "admin-a", ORG_A, "admin-policy-create");
        assertEquals(HttpStatus.CREATED, draft.getStatusCode(), draft.getBody());
        assertEquals("pending", field(draft.getBody(), "status"));

        ResponseEntity<String> approved = approve("policy.escalation.approval", 1, "admin-a", ORG_A,
                "admin-policy-approve", "{\"note\":\"Synthetic clinical policy approval\"}");
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
        assertEquals("approved", field(approved.getBody(), "status"));
        assertEquals(adminId(ORG_A, "admin-a"), field(approved.getBody(), "approvedBy"));
        assertEquals("Synthetic clinical policy approval", field(approved.getBody(), "note"));
        assertTrue(!field(approved.getBody(), "approvedAt").isEmpty());

        ResponseEntity<String> effective = get("policy.escalation.approval", "admin-a", ORG_A,
                "admin-policy-read");
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        assertEquals("notify-owner", field(effective.getBody(), "value"));
        assertEquals("1", field(effective.getBody(), "version"));
        assertEquals("approved", field(effective.getBody(), "status"));
    }

    /** A reception actor is not a tenant admin and cannot create configuration. */
    @Test
    void configurationCreationIsRefusedForWrongRole() throws Exception {
        ResponseEntity<String> denied = create("policy.access.denied", "v", null,
                "reception-a", ORG_A, "admin-deny-role");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals(0, countConfigEntries(ORG_A, "policy.access.denied"));
    }

    /** Facility administration is likewise refused for an actor without the admin action. */
    @Test
    void facilityCreationIsRefusedForWrongRole() {
        ResponseEntity<String> denied = request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"ADMCR02\",\"name\":\"Denied Facility\"}",
                "reception-a", ORG_A, "admin-deny-facility-role");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
    }

    /** Forced row-level security conceals one tenant's configured policy from another. */
    @Test
    void configurationIsConcealedFromAnotherTenant() {
        assertEquals(HttpStatus.CREATED,
                create("policy.tenant.hidden", "hidden", null, "admin-a", ORG_A, "admin-hide-create")
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                approve("policy.tenant.hidden", 1, "admin-a", ORG_A, "admin-hide-approve", null)
                        .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                get("policy.tenant.hidden", "admin-b", ORG_B, "admin-hide-read").getStatusCode());
    }

    /** Approving a version that was never appended is refused without creating anything. */
    @Test
    void approvalOfMissingVersionIsRefused() {
        assertEquals(HttpStatus.CREATED,
                create("policy.version.missing", "v", null, "admin-a", ORG_A, "admin-missing-create")
                        .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                approve("policy.version.missing", 2, "admin-a", ORG_A, "admin-missing-approve", null)
                        .getStatusCode());
    }

    /** An opaque configuration value must contain at least one non-blank character. */
    @Test
    void blankValueIsRefused() {
        assertEquals(422,
                create("policy.value.blank", " ", null, "admin-a", ORG_A, "admin-deny-blank")
                        .getStatusCode().value());
    }

    /** Seed one synthetic organization, user identity, membership and role assignment. */
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

    /** Build the synthetic identity and tenant headers; a null part is simply omitted. */
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

    /** Issue an HTTP request and return the raw response so error statuses remain assertable. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Append a draft configuration version through the published HTTP entry point. */
    private ResponseEntity<String> create(String key, String value, String effectiveFrom,
                                          String actor, String organization, String correlation) {
        String body = "{\"key\":\"" + key + "\",\"value\":\"" + value + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"") + "}";
        return request(HttpMethod.POST, "/api/v1/configuration", body, actor, organization, correlation);
    }

    /** Append a named approval through the published HTTP entry point. */
    private ResponseEntity<String> approve(String key, int version, String actor,
                                           String organization, String correlation, String body) {
        return request(HttpMethod.POST, "/api/v1/configuration/" + key + "/versions/" + version + "/approve",
                body == null ? "{}" : body, actor, organization, correlation);
    }

    /** Read the effective configuration version through the published HTTP entry point. */
    private ResponseEntity<String> get(String key, String actor, String organization, String correlation) {
        return request(HttpMethod.GET, "/api/v1/configuration/" + key, null, actor, organization, correlation);
    }

    /** Resolve a synthetic actor to its database identifier under the active tenant. */
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

    /** Count visible draft rows for a key under forced row-level security. */
    private int countConfigEntries(String organization, String key) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.config_entry WHERE key = '" + key + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /** Read a scalar JSON field from a response body without adding a dependency. */
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
}
