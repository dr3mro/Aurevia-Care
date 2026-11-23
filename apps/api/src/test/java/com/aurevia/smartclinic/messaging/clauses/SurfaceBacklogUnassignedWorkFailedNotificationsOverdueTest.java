package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.WorkItemFact;
import com.aurevia.smartclinic.messaging.service.ServiceProfileContract.WorkState;
import com.aurevia.smartclinic.messaging.service.ServiceProfileStore;
import com.aurevia.smartclinic.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-06 clause 5 (API evidence): surface backlog, unassigned work, failed
 * notifications and overdue responses to unit supervisors.
 *
 * <p>The positive test drives {@code GET /api/v1/care-units/{unitId}/supervisor-snapshot}
 * through HTTP against a real PostgreSQL database seeded with the production
 * Flyway migrations. Its work facts are chosen so the exact counters also prove
 * every branch of the calculator: open work is counted in the backlog while
 * resolved and closed work is excluded; null and blank assignees are unassigned;
 * a failed notification on a resolved item is still surfaced; an overdue
 * awaiting-staff item with an elapsed deadline is counted while an
 * already-responded item, an awaiting-patient item and a not-yet-due item are
 * not. Time anchors are far from the system clock so the assertions cannot be
 * flipped by the instant at which the test happens to run.</p>
 *
 * <p>The three negative tests prove the rule refuses what it must refuse: a
 * patient without the supervisor action gets 403, an unassigned supervisor gets
 * 404, and a supervisor from another tenant gets 404 while a direct store read
 * shows no cross-tenant work rows. Fixtures are synthetic; no patient data or
 * credentials are present. This task adds proof only and changes no production
 * code, migration, contract or existing test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_msg06_supervisor_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SurfaceBacklogUnassignedWorkFailedNotificationsOverdueTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CHART_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHART_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_msg06_supervisor_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String SEEDER = "seeder-msgsnap";
    private static final String PUBLISHER = "publisher-msgsnap";
    private static final String APPROVER = "approver-msgsnap";
    private static final String SUPERVISOR = "supervisor-msgsnap";
    private static final String UNASSIGNED_SUPERVISOR = "supervisor-unassigned-msgsnap";
    private static final String PATIENT = "patient-msgsnap";
    private static final String SUPERVISOR_B = "supervisor-b-msgsnap";

    private static final String WI_OVERDUE_NULL = "d0000001-0000-4000-8000-000000000001";
    private static final String WI_OPEN_BLANK = "d0000002-0000-4000-8000-000000000002";
    private static final String WI_AWAITING_PATIENT = "d0000003-0000-4000-8000-000000000003";
    private static final String WI_RESPONDED = "d0000004-0000-4000-8000-000000000004";
    private static final String WI_RESOLVED_FAILED = "d0000005-0000-4000-8000-000000000005";
    private static final String WI_CLOSED = "d0000006-0000-4000-8000-000000000006";

    /** Deadline anchors chosen years away from the 30-minute target and the system clock. */
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");
    /** A future anchor keeps a not-yet-due item unambiguously not overdue. */
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SNAPSHOT_PATH =
            "/api/v1/care-units/" + UNIT + "/supervisor-snapshot";

    @Autowired
    private ServiceProfileStore store;

    private RestClient rest;

    /**
     * Create the scratch database, apply the production migrations and seed two
     * tenants that share one local unit identifier. The positive actor is the
     * only supervisor assigned to that unit in the owning tenant.
     *
     * @throws Exception when database setup or seeding fails
     */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_msg06_supervisor_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_msg06_supervisor_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOwningTenant(statement);
            seedOtherTenant(statement);
            // Publish enforces site-approved urgent and downtime channels. Seed
            // the owning tenant's fold so publishAndApproveProfile succeeds;
            // ORG_B stays unseeded so cross-tenant concealment keeps its premise.
            seedApprovedChannelPolicy(statement, ORG_A, "msg06.approved_urgent_channels", "urgent");
            seedApprovedChannelPolicy(statement, ORG_A, "msg06.approved_downtime_channels", "downtime");
        }
    }

    /** Seed one site-approved channel policy through the versioned config fold. */
    private static void seedApprovedChannelPolicy(Statement statement, String org, String key,
                                                  String value) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + APPROVER + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    /** Drop the scratch database so the class can be run repeatedly.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_msg06_supervisor_it WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the running test server.
     *
     * @param port injected random port of the test server
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** The assigned supervisor sees exact counters for the unit, derived over HTTP. */
    @Test
    void supervisorSnapshotSurfacesBacklogUnassignedFailedNotificationsAndOverdueForOwnUnit() {
        publishAndApproveProfile();
        seedWorkItems();

        Instant before = Instant.now();
        ResponseEntity<String> response = get(SNAPSHOT_PATH, SUPERVISOR, ORG_A);
        Instant after = Instant.now();

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode snapshot = tree(response);
        assertEquals(4, snapshot.path("backlog").asInt(),
                "three awaiting-staff and one awaiting-patient item are open; resolved and closed are excluded");
        assertEquals(2, snapshot.path("unassigned").asInt(),
                "the null-assignee and blank-assignee open items are unassigned");
        assertEquals(1, snapshot.path("failedNotifications").asInt(),
                "the single failed notification is surfaced even though its item is resolved");
        assertEquals(1, snapshot.path("overdueResponses").asInt(),
                "only the awaiting-staff item with no response and an elapsed deadline is overdue");
        assertEquals(ORG_A, snapshot.path("organizationId").asText());
        assertEquals(UNIT, snapshot.path("unitId").asText());
        Instant generatedAt = Instant.parse(snapshot.path("generatedAt").asText());
        assertFalse(generatedAt.isBefore(before), "generatedAt must not precede the request");
        assertFalse(generatedAt.isAfter(after), "generatedAt must not follow the request");
    }

    /** A role without the supervisor action is refused, with no counter leakage. */
    @Test
    void patientWithoutSupervisorRoleIsDeniedTheSnapshot() {
        ResponseEntity<String> denied = get(SNAPSHOT_PATH, PATIENT, ORG_A);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
        assertEquals("FORBIDDEN", tree(denied).path("code").asText());
        assertEquals("missing SUPERVISOR_VIEW permission", tree(denied).path("message").asText(),
                "the patient is an active member, so the denial must be the missing action, not a missing membership");
        assertNoCounters(tree(denied));
    }

    /** A supervisor with no assignment to the unit cannot read its snapshot. */
    @Test
    void unassignedSupervisorCannotReadAnothersUnitSnapshot() {
        ResponseEntity<String> denied = get(SNAPSHOT_PATH, UNASSIGNED_SUPERVISOR, ORG_A);
        assertEquals(404, denied.getStatusCode().value(), denied.getBody());
        assertEquals("NOT_FOUND", tree(denied).path("code").asText());
        assertEquals("unit not found", tree(denied).path("message").asText());
        assertNoCounters(tree(denied));
    }

    /** Another tenant's supervisor is concealed and sees none of this tenant's work rows. */
    @Test
    void supervisorFromAnotherTenantCannotReadThisUnitsSnapshot() {
        ResponseEntity<String> denied = get(SNAPSHOT_PATH, SUPERVISOR_B, ORG_B);
        assertEquals(404, denied.getStatusCode().value(), denied.getBody());
        assertEquals("NOT_FOUND", tree(denied).path("code").asText());
        assertEquals("unit not found", tree(denied).path("message").asText());
        assertNoCounters(tree(denied));

        bindContext(ORG_B, SUPERVISOR_B);
        List<WorkItemFact> otherTenantItems = store.workItems(ORG_B, UNIT);
        TenantContext.clear();
        assertTrue(otherTenantItems.isEmpty(),
                "the other tenant holds no work items for the shared local unit id");
        assertFalse(otherTenantItems.stream().anyMatch(item -> WI_OVERDUE_NULL.equals(item.id())
                        || WI_RESOLVED_FAILED.equals(item.id())),
                "this tenant's work rows must never be visible in the other tenant");
    }

    /** A denial or concealment body must carry none of the four derived counters. */
    private static void assertNoCounters(JsonNode body) {
        assertNull(body.get("backlog"));
        assertNull(body.get("unassigned"));
        assertNull(body.get("failedNotifications"));
        assertNull(body.get("overdueResponses"));
    }

    /** Publish and name-approve a 30-minute effective profile through the normal HTTP route. */
    private void publishAndApproveProfile() {
        String base = "/api/v1/care-units/" + UNIT;
        ResponseEntity<String> created = post(base + "/service-profiles", PUBLISHER, ORG_A,
                publishBody(), null);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String profileId = tree(created).path("id").asText();
        ResponseEntity<String> approved = post(base + "/service-profiles/" + profileId
                + "/approve?note=site-policy", APPROVER, ORG_A, null, "W/\"1\"");
        assertEquals(200, approved.getStatusCode().value(), approved.getBody());
        assertEquals("approved", tree(approved).path("status").asText());
    }

    /**
     * Seed work facts through the same durable store the service reads.
     *
     * <p>The facts are chosen to take every calculator branch over HTTP: an
     * overdue open item with a null assignee, an open blank-assignee item that is
     * not yet due, an awaiting-patient item with an assigned owner, an
     * already-responded item, a resolved item with a failed notification, and a
     * closed item. Expected counters are backlog 4, unassigned 2, failures 1 and
     * overdue 1.</p>
     */
    private void seedWorkItems() {
        bindContext(ORG_A, SUPERVISOR);
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WI_OVERDUE_NULL, UNIT,
                WorkState.AWAITING_STAFF, null, PAST, null, false));
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WI_OPEN_BLANK, UNIT,
                WorkState.AWAITING_STAFF, "  ", FUTURE, null, false));
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WI_AWAITING_PATIENT, UNIT,
                WorkState.AWAITING_PATIENT, "nurse-b", PAST, null, false));
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WI_RESPONDED, UNIT,
                WorkState.AWAITING_STAFF, "nurse-a", PAST, PAST.plusSeconds(600), false));
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WI_RESOLVED_FAILED, UNIT,
                WorkState.RESOLVED, null, PAST, null, true));
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WI_CLOSED, UNIT,
                WorkState.CLOSED, null, PAST, null, false));
        TenantContext.clear();
    }

    private static String publishBody() {
        return "{\"unitId\":\"" + UNIT + "\",\"timeZone\":\"UTC\","
                + "\"windows\":[\"MONDAY|09:00|17:00\"],\"responseTargetMinutes\":30,"
                + "\"queueOwner\":\"nurse\",\"backupCoverage\":[\"backup\"],"
                + "\"escalationChannel\":\"escalation\",\"urgentChannel\":\"urgent\","
                + "\"downtimeChannel\":\"downtime\",\"topics\":[\"collection\"],"
                + "\"urgentInstructions\":[{\"language\":\"en\",\"value\":\"call us\"},"
                + "{\"language\":\"ar\",\"value\":\"اتصل بنا\"}],"
                + "\"effectiveFrom\":\"2020-01-01T00:00:00Z\",\"expectedVersion\":0}";
    }

    private static void seedOwningTenant(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + ORG_A + "', 'Synthetic snapshot owner')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + SEEDER + "', 'Seeder')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'active' FROM public.user_identity WHERE subject = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_A + "', '" + CHART_A + "', 1, 'MRN-SNAP-A', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + ORG_A + "', '" + UNIT + "', '" + CHART_A + "', 'Synthetic Clinic Unit', "
                + "ARRAY['collection'], true, 'clinic')");

        seedMember(statement, ORG_A, PUBLISHER, "tenant_admin");
        seedMember(statement, ORG_A, APPROVER, "tenant_admin");
        seedMember(statement, ORG_A, SUPERVISOR, "unit_supervisor");
        seedMember(statement, ORG_A, UNASSIGNED_SUPERVISOR, "unit_supervisor");
        seedConnectedPatient(statement, ORG_A, PATIENT, CHART_A);


        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.service_profile_unit_assignment "
                + "(organization_id, subject, unit_id) VALUES ('" + ORG_A + "', '" + SUPERVISOR
                + "', '" + UNIT + "')");
    }

    private static void seedOtherTenant(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_B + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + ORG_B + "', 'Synthetic snapshot other tenant')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_B + "', '" + SEEDER + "', 'Seeder')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_B + "', id, 'active' FROM public.user_identity WHERE subject = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + ORG_B + "', '" + UNIT + "', '" + CHART_B + "', 'Synthetic Clinic Unit', "
                + "ARRAY['collection'], true, 'clinic')");
        seedMember(statement, ORG_B, SUPERVISOR_B, "unit_supervisor");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedConnectedPatient(Statement statement, String org, String subject, String chart)
            throws Exception {
        // A patient acting through a protected route still needs an active
        // membership; the patient role alone must be what is denied here.
        seedMember(statement, org, subject, "patient");
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', (SELECT id FROM public.patient_account WHERE organization_id = '"
                + org + "' AND subject = '" + subject + "'), '" + chart + "', 'linked', 'synthetic')");
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        String relationship = UUID.nameUUIDFromBytes(("rel-" + subject).getBytes()).toString();
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, patient_id, unit_id, context, context_reference, relationship_id, "
                + " state, verified_by, verified_at, created_at) "
                + "VALUES ('" + org + "', '" + chart + "', '" + UNIT + "', 'care_relationship', 'ref-"
                + subject + "', '" + relationship + "', 'active', '" + SEEDER + "', now(), now())");
    }

    private static void bindContext(String org, String subject) {
        TenantContext.set(new TenantContext(org, subject, "MSG06-SNAPSHOT-IT", "http-it"));
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return send(HttpMethod.GET, path, subject, org, null, null);
    }

    private ResponseEntity<String> post(String path, String subject, String org, String body,
                                         String ifMatch) {
        return send(HttpMethod.POST, path, subject, org, body, ifMatch);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String subject, String org,
                                         String body, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .contentType(MediaType.APPLICATION_JSON);
        if (ifMatch != null) {
            spec.header("If-Match", ifMatch);
        }
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        return tree(response.getBody());
    }

    private static JsonNode tree(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse supervisor-snapshot response: " + body, exception);
        }
    }
}
