package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
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
 * IAM-01 clause 4 (API evidence): facility scope may further narrow access.
 *
 * <p>The fixture issues a reception invitation bound to the MAIN facility and accepts
 * it, so the resulting membership carries the facility assignment that the specification
 * describes as "assigned facilities". The first test asserts the rule is met: the scoped
 * user can read the worklist of the facility it was assigned to. The second test asserts
 * the rule refuses what it must refuse: the same user, whose role still permits
 * {@code WORKLIST_READ}, cannot read a different facility of the same organization.</p>
 *
 * <p>Facility scope is persisted on the membership by the invitation acceptance
 * migration and consulted by the worklist authorization decision. A member without an
 * assigned facility keeps the previous organization-wide behaviour: facility scope may
 * further narrow access, it does not create a new prerequisite for legacy memberships.
 * The negative test proves that a member assigned to one facility is refused a sibling
 * facility of the same organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_facility_scope_it")
@ActiveProfiles("synthetic-auth")
class FacilityScopeFurtherNarrowAccessTest {

    private static final String ORG_A = "33333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_facility_scope_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static String mainFacilityId;
    private static String northFacilityId;

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS orevia_facility_scope_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_facility_scope_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'admin-a'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES "
                    + "('" + ORG_A + "', 'Facility Scope Org')");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG_A + "', 'admin-a', 'Admin A')");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + ORG_A + "', id, 'active' FROM public.user_identity "
                    + "WHERE subject = 'admin-a'");
            statement.execute("INSERT INTO public.role_assignment "
                    + "(organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + ORG_A + "', m.id, 'tenant_admin', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                    + "AND u.id = m.user_id WHERE u.subject = 'admin-a'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) VALUES "
                    + "('" + ORG_A + "', 'MAIN', 'Main'), ('" + ORG_A + "', 'NORTH', 'North')");
            mainFacilityId = scalar(statement, "SELECT id::text FROM public.facility WHERE code = 'MAIN'");
            northFacilityId = scalar(statement, "SELECT id::text FROM public.facility WHERE code = 'NORTH'");
        }
    }

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void facilityScopeAllowsAccessToTheAssignedFacility() {
        createFacilityScopedReception("scope-reception-main", mainFacilityId);
        ResponseEntity<String> response = worklist("scope-reception-main", "MAIN");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    @Test
    void facilityScopeDeniesAccessToAnotherFacilityOfTheSameOrganization() {
        createFacilityScopedReception("scope-reception-other", mainFacilityId);
        ResponseEntity<String> response = worklist("scope-reception-other", "NORTH");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    void facilityScopeDeniesIntakeToAnotherFacilityOfTheSameOrganization() {
        createFacilityScopedReception("scope-reception-intake", mainFacilityId);
        String body = "{\"facilityCode\":\"NORTH\",\"patientId\":"
                + "\"55555555-5555-5555-5555-555555555555\",\"reason\":\"walk-in\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/clinic/worklist/intake",
                body, "scope-reception-intake");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
    }

    @Test
    void tenantAdminKeepsOrganizationWideAccessDespiteAnAssignedFacility() {
        createFacilityScopedStaff("scope-admin", northFacilityId, "\"tenant_admin\"");
        ResponseEntity<String> response = worklist("scope-admin", "MAIN");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    @Test
    void acceptancePersistsTheInvitationFacilityOnTheMembership() throws Exception {
        createFacilityScopedReception("scope-reception-persist", mainFacilityId);
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery("""
                     SELECT f.code
                       FROM public.membership_facility mf
                       JOIN public.membership m
                         ON m.organization_id = mf.organization_id AND m.id = mf.membership_id
                       JOIN public.user_identity u
                         ON u.organization_id = m.organization_id AND u.id = m.user_id
                       JOIN public.facility f
                         ON f.organization_id = mf.organization_id AND f.id = mf.facility_id
                      WHERE u.subject = 'scope-reception-persist'
                     """)) {
                assertTrue(result.next(), "acceptance must persist the invitation facility");
                assertEquals("MAIN", result.getString(1));
            }
        }
    }

    private void createFacilityScopedReception(String subject, String facilityId) {
        createFacilityScopedStaff(subject, facilityId, "\"reception\"");
    }

    private void createFacilityScopedStaff(String subject, String facilityId, String roles) {
        String invitation = "{\"subject\":\"" + subject + "\",\"displayName\":\"" + subject
                + "\",\"facilityId\":\"" + facilityId + "\",\"roles\":[" + roles + "],"
                + "\"ttlSeconds\":3600}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/staff-invitations",
                invitation, "admin-a");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String token = field(created.getBody(), "token");
        ResponseEntity<String> accepted = request(HttpMethod.POST, "/api/v1/staff-invitations/accept",
                "{\"token\":\"" + token + "\"}", "admin-a");
        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals(subject, field(accepted.getBody(), "subject"));
    }

    private ResponseEntity<String> worklist(String subject, String facilityCode) {
        return request(HttpMethod.GET, "/api/v1/clinic/worklist?facilityCode=" + facilityCode, null, subject);
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        headers.set("X-Active-Organization", ORG_A);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static String scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new IllegalStateException("no row returned by " + sql);
            }
            return result.getString(1);
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
}
