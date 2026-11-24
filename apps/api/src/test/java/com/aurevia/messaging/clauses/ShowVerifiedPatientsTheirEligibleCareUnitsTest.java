package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
 * MSG-01 clause 1 (API evidence): a verified patient sees only their own eligible
 * care units, each carrying its organization, facility, name and topics.
 *
 * <p>The rule under test is the read side of the care-unit directory. The
 * positive case drives {@code GET /api/v1/care-units} through HTTP against a real
 * PostgreSQL database seeded with two tenants that share local identifiers. The
 * eligible unit is backed by an active, unconsumed, unexpired verified
 * relationship; five control units are seeded to prove that an inactive unit, a
 * consumed relationship, an expired relationship, an inactive relationship and a
 * missing relationship are all refused. The negative cases prove the rule refuses
 * what it must refuse: a role without {@code UNIT_LIST} is denied, a patient with
 * no current {@code linked} chart is denied, and a patient in one tenant never
 * sees another tenant's directory row even when both share the same local unit
 * identifier.</p>
 *
 * <p>Every value is synthetic. No real patient data, credential, production
 * source, migration, contract or existing test is changed by this proof.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_msg01_units_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ShowVerifiedPatientsTheirEligibleCareUnitsTest {

    private static final String ORG_A = "a1000000-0000-4000-8000-000000000001";
    private static final String ORG_B = "a1000000-0000-4000-8000-000000000002";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_msg01_units_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    /** The linked chart and the eligible unit use the same local ids in both tenants. */
    private static final String PATIENT_ID = "b1000000-0000-4000-8000-000000000001";
    private static final String ELIGIBLE_UNIT_ID = "c1000000-0000-4000-8000-000000000001";
    private static final String INACTIVE_UNIT_ID = "c1000000-0000-4000-8000-000000000002";
    private static final String CONSUMED_UNIT_ID = "c1000000-0000-4000-8000-000000000003";
    private static final String EXPIRED_UNIT_ID = "c1000000-0000-4000-8000-000000000004";
    private static final String INACTIVE_RELATIONSHIP_UNIT_ID = "c1000000-0000-4000-8000-000000000005";
    private static final String NO_RELATIONSHIP_UNIT_ID = "c1000000-0000-4000-8000-000000000006";
    private static final String BETA_ONLY_UNIT_ID = "c1000000-0000-4000-8000-000000000007";
    private static final String FACILITY_ID = "e1000000-0000-4000-8000-000000000001";

    private static final String SUBJECT_A = "patient-a";
    private static final String SUBJECT_B = "patient-b";
    private static final String SUBJECT_NO_CHART = "patient-no-chart";
    private static final String SUBJECT_WRONG_ROLE = "reception-a";

    private static final String ALPHA_NAME = "Alpha Clinic Unit";
    private static final String BETA_NAME = "Beta Clinic Unit";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Create an isolated database, apply the production migrations and seed the
     * synthetic tenants. Control care units and their relationships are inserted
     * together so a filtered-out unit can only be excluded by the intended flag,
     * not by a missing row in the {@code relationshipsFor} join.
     *
     * @throws Exception when database setup or seeding fails
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_msg01_units_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_msg01_units_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, SUBJECT_A, "MRN-MSG01-A", ALPHA_NAME, false);
            seedMember(statement, ORG_A, SUBJECT_NO_CHART, "patient");
            seedMember(statement, ORG_A, SUBJECT_WRONG_ROLE, "reception");
            seedTenant(statement, ORG_B, SUBJECT_B, "MRN-MSG01-B", BETA_NAME, true);
        }
    }

    /**
     * Drop the scratch database so the class runs repeatedly.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_msg01_units_it WITH (FORCE)");
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
     * The directory of a verified patient contains their eligible unit with the
     * organization, facility, name and supported topics, and excludes every unit
     * that fails an eligibility rule or belongs to another tenant.
     */
    @Test
    void verifiedPatientSeesOnlyOwnEligibleUnitsWithOrganizationFacilityNameAndTopics() {
        ResponseEntity<String> response = request(HttpMethod.GET, "/api/v1/care-units", SUBJECT_A, ORG_A);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        JsonNode units = json(response).path("units");
        assertTrue(units.isArray(), "the directory response must carry a units array");

        JsonNode eligible = unit(units, ELIGIBLE_UNIT_ID);
        assertEquals(ORG_A, eligible.path("organizationId").asText());
        assertEquals(FACILITY_ID, eligible.path("facilityId").asText());
        assertEquals(ALPHA_NAME, eligible.path("name").asText());
        JsonNode topics = eligible.path("topics");
        assertTrue(topics.isArray(), "supported topics must be a JSON array");
        assertEquals(List.of("messages", "follow-up"), texts(topics));

        List<String> visible = ids(units);
        assertFalse(visible.contains(INACTIVE_UNIT_ID), "an inactive unit is not in the directory");
        assertFalse(visible.contains(CONSUMED_UNIT_ID), "a consumed relationship confers no eligibility");
        assertFalse(visible.contains(EXPIRED_UNIT_ID), "an expired relationship confers no eligibility");
        assertFalse(visible.contains(INACTIVE_RELATIONSHIP_UNIT_ID),
                "an inactive relationship confers no eligibility");
        assertFalse(visible.contains(NO_RELATIONSHIP_UNIT_ID),
                "a unit with no verified relationship is not in the directory");
        assertFalse(visible.contains(BETA_ONLY_UNIT_ID),
                "a unit that exists only in another tenant is not in this directory");
    }

    /** A member without the {@code UNIT_LIST} permission is refused the directory. */
    @Test
    void patientWithoutUnitListRoleIsDeniedTheDirectory() {
        ResponseEntity<String> denied = request(HttpMethod.GET, "/api/v1/care-units", SUBJECT_WRONG_ROLE, ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", json(denied).path("code").asText());
    }

    /** A patient with no current verified chart is refused the directory. */
    @Test
    void patientWithoutAVerifiedChartIsDeniedTheDirectory() {
        ResponseEntity<String> denied = request(HttpMethod.GET, "/api/v1/care-units", SUBJECT_NO_CHART, ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("NO_VERIFIED_CHART", json(denied).path("code").asText());
    }

    /** A directory read in one tenant never exposes another tenant's unit. */
    @Test
    void directoryInOneTenantDoesNotExposeAnotherTenantsUnit() {
        ResponseEntity<String> response = request(HttpMethod.GET, "/api/v1/care-units", SUBJECT_B, ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        JsonNode units = json(response).path("units");
        assertTrue(units.isArray(), "the directory response must carry a units array");

        JsonNode own = unit(units, ELIGIBLE_UNIT_ID);
        assertEquals(ORG_B, own.path("organizationId").asText());
        assertEquals(BETA_NAME, own.path("name").asText(),
                "the shared local unit id must resolve to this tenant's directory row");
        assertEquals(List.of("messages", "follow-up"), texts(own.path("topics")));
        assertTrue(ids(units).contains(BETA_ONLY_UNIT_ID), "this tenant's eligible unit is visible");

        for (JsonNode item : units) {
            assertFalse(ALPHA_NAME.equals(item.path("name").asText()),
                    "another tenant's directory row must never be returned");
            assertFalse(ORG_A.equals(item.path("organizationId").asText()),
                    "every returned unit must belong to the active tenant");
        }
    }

    /**
     * Seed one tenant with a linked chart, a facility, six care units and the
     * verifying relationships. Tenant B additionally owns a unit id that tenant A
     * cannot see, so isolation is observable by id as well as by value.
     *
     * @param statement  seeding statement with a bound tenant context
     * @param org        tenant identifier
     * @param subject    verified patient subject
     * @param mrn        synthetic chart identifier
     * @param unitName   tenant-specific name for the shared eligible unit id
     * @param extraUnit  whether to add the tenant-B-only unit
     * @throws Exception when a seed statement fails
     */
    private static void seedTenant(Statement statement, String org, String subject,
                                   String mrn, String unitName, boolean extraUnit) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Synthetic directory " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.facility (organization_id, id, code, name) VALUES ('"
                + org + "', '" + FACILITY_ID + "', 'MAIN', 'Synthetic Main Facility')");
        seedMember(statement, org, subject, "patient");

        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT_ID + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Directory Patient', 'synthetic directory patient', DATE '1990-05-20')");
        statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        String account = "(SELECT id FROM public.patient_account WHERE organization_id = '" + org
                + "' AND subject = '" + subject + "')";
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', " + account + ", '" + PATIENT_ID + "', 'linked', 'synthetic')");

        insertUnit(statement, org, ELIGIBLE_UNIT_ID, unitName, true);
        insertUnit(statement, org, INACTIVE_UNIT_ID, "Inactive Unit", false);
        insertUnit(statement, org, CONSUMED_UNIT_ID, "Consumed Unit", true);
        insertUnit(statement, org, EXPIRED_UNIT_ID, "Expired Unit", true);
        insertUnit(statement, org, INACTIVE_RELATIONSHIP_UNIT_ID, "Inactive Relationship Unit", true);
        insertUnit(statement, org, NO_RELATIONSHIP_UNIT_ID, "No Relationship Unit", true);

        insertRelationship(statement, org, "eligible", ELIGIBLE_UNIT_ID,
                "true", "false", "NULL");
        insertRelationship(statement, org, "inactive-unit", INACTIVE_UNIT_ID,
                "true", "false", "NULL");
        insertRelationship(statement, org, "consumed", CONSUMED_UNIT_ID,
                "true", "true", "NULL");
        insertRelationship(statement, org, "expired", EXPIRED_UNIT_ID,
                "true", "false", "TIMESTAMPTZ '2020-01-01 00:00:00+00'");
        insertRelationship(statement, org, "inactive-relationship", INACTIVE_RELATIONSHIP_UNIT_ID,
                "false", "false", "NULL");
        if (extraUnit) {
            insertUnit(statement, org, BETA_ONLY_UNIT_ID, "Beta Only Unit", true);
            insertRelationship(statement, org, "beta-only", BETA_ONLY_UNIT_ID,
                    "true", "false", "NULL");
        }
    }

    /**
     * Seed an active member with exactly one role. The member's own identity is
     * the grantor so no bootstrap identity is required inside a tenant.
     *
     * @param statement  seeding statement with a bound tenant context
     * @param org        tenant identifier
     * @param subject    member subject
     * @param role       single active role key
     * @throws Exception when a seed statement fails
     */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    /**
     * Insert an active or inactive care unit inside the bound tenant.
     *
     * @param statement  seeding statement with a bound tenant context
     * @param org        tenant identifier
     * @param id         local unit identifier, reused across tenants on purpose
     * @param name       display name
     * @param active     whether the unit is staffed and accepting messages
     * @throws Exception when the insert fails
     */
    private static void insertUnit(Statement statement, String org, String id, String name, boolean active)
            throws Exception {
        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + org + "', '" + id + "', '" + FACILITY_ID + "', '" + name
                + "', ARRAY['messages', 'follow-up'], " + active + ", 'clinic')");
    }

    /**
     * Insert a verified relationship with a stable per-unit identifier.
     *
     * @param statement  seeding statement with a bound tenant context
     * @param org        tenant identifier
     * @param seed       stable seed for the local relationship identifier
     * @param unitId     local recipient unit identifier
     * @param active     whether the relationship is active
     * @param consumed   whether the single-use relationship was consumed
     * @param expiresAt  SQL expression for the expiry, or {@code NULL}
     * @throws Exception when the insert fails
     */
    private static void insertRelationship(Statement statement, String org, String seed, String unitId,
                                           String active, String consumed, String expiresAt) throws Exception {
        statement.execute("INSERT INTO public.eligible_relationship "
                + "(organization_id, id, patient_id, unit_id, context, reference, active, consumed, expires_at) "
                + "VALUES ('" + org + "', '" + id(org + "-" + seed) + "', '" + PATIENT_ID + "', '"
                + unitId + "', 'order', 'ref-" + seed + "', " + active + ", " + consumed
                + ", " + expiresAt + ")");
    }

    /** Build a stable UUID from a synthetic seed string. */
    private static String id(String seed) {
        return java.util.UUID.nameUUIDFromBytes(
                seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * Send an HTTP request with the synthetic identity headers and keep error
     * bodies readable so denial codes can be asserted.
     *
     * @param method       HTTP method
     * @param uri          request path
     * @param subject      synthetic subject header
     * @param organization active-organization header
     * @return the HTTP response with its body
     */
    private ResponseEntity<String> request(HttpMethod method, String uri, String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a JSON response body. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /** Find one directory row by its local unit identifier. */
    private static JsonNode unit(JsonNode units, String id) {
        for (JsonNode item : units) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("unit " + id + " is missing from " + units);
    }

    /** Collect the local unit identifiers present in a directory response. */
    private static List<String> ids(JsonNode units) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : units) {
            values.add(item.path("id").asText());
        }
        return values;
    }

    /** Collect a JSON string array as a typed list. */
    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }
}
