package com.aurevia.it.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.messaging.service.ServiceProfileContract.WorkItemFact;
import com.aurevia.messaging.service.ServiceProfileContract.WorkState;
import com.aurevia.messaging.service.ServiceProfileStore;
import com.aurevia.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-06 HTTP acceptance over the durable, tenant-forced-RLS service-profile
 * store. Every request carries only a synthetic subject and an explicitly
 * selected active organization; roles, chart links and connections come from
 * the seeded authoritative folds. The chained publish/approve/serve test also
 * reads the committed rows directly, proving the authorization state is in the
 * database rather than process-local. All values are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_service_profile_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ServiceProfileHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHART_UNCONNECTED = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String UNKNOWN = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/orevia_service_profile_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String SEEDER = "seeder-http";
    private static final String PUBLISHER = "publisher-http";
    private static final String APPROVER = "approver-http";
    private static final String PATIENT = "patient-http";
    private static final String UNCONNECTED_PATIENT = "patient-unconnected-http";
    private static final String SUPERVISOR = "supervisor-http";
    private static final String UNASSIGNED_SUPERVISOR = "supervisor-unassigned-http";
    private static final String PRIVACY = "privacy-http";
    private static final String NO_ROLE = "no-role-http";
    private static final String WORK_ITEM_ONE = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String WORK_ITEM_TWO = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ServiceProfileStore store;

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
            statement.execute("DROP DATABASE IF EXISTS orevia_service_profile_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_service_profile_http_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "MRN-SP-HTTP-A", true);
            seedTenant(statement, ORG_B, "MRN-SP-HTTP-B", false);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_service_profile_http_it WITH (FORCE)");
        }
    }

    @BeforeEach
    void initRestClient(@org.springframework.boot.test.web.server.LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedTenant(Statement statement, String org, String mrn, boolean connected)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Synthetic " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + SEEDER + "', 'Seeder')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + CHART + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + CHART_UNCONNECTED + "', 1, '" + mrn + "-U', 'CLINIC', "
                + "'Synthetic Unconnected Patient', 'synthetic unconnected patient', DATE '1991-06-21')");
        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + org + "', '" + UNIT + "', '" + CHART + "', 'Synthetic Clinic Unit', "
                + "ARRAY['collection'], true, 'clinic')");

        seedMember(statement, org, PUBLISHER, "tenant_admin");
        seedMember(statement, org, APPROVER, "tenant_admin");
        seedMember(statement, org, PRIVACY, "privacy_auditor");
        seedMember(statement, org, SUPERVISOR, "unit_supervisor");
        seedMember(statement, org, UNASSIGNED_SUPERVISOR, "unit_supervisor");
        seedMember(statement, org, NO_ROLE, "reception");
        seedMember(statement, org, PATIENT, "patient");
        seedMember(statement, org, UNCONNECTED_PATIENT, "patient");
        // The publish path now enforces the site's approved channels. Seed an
        // effective named approval for the legacy fixture codes used here.
        seedApprovedChannels(statement, org, "msg06.approved_urgent_channels", "urgent");
        seedApprovedChannels(statement, org, "msg06.approved_downtime_channels", "downtime");

        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        linkChart(statement, org, PATIENT, CHART);
        linkChart(statement, org, UNCONNECTED_PATIENT, CHART_UNCONNECTED);
        if (connected) {
            insertConnection(statement, org, PATIENT, CHART, "active");
            insertConnection(statement, org, UNCONNECTED_PATIENT, CHART_UNCONNECTED, "revoked");
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + SEEDER + "'");
            statement.execute("INSERT INTO public.service_profile_unit_assignment "
                    + "(organization_id, subject, unit_id) VALUES ('" + org + "', '" + SUPERVISOR
                    + "', '" + UNIT + "')");
        }
    }

    private static void linkChart(Statement statement, String org, String subject, String chart)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', (SELECT id FROM public.patient_account WHERE organization_id = '"
                + org + "' AND subject = '" + subject + "'), '" + chart + "', 'linked', 'synthetic')");
    }

    private static void insertConnection(Statement statement, String org, String subject, String chart,
                                         String state) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, patient_id, unit_id, context, context_reference, relationship_id, "
                + " state, verified_by, verified_at, created_at) "
                + "VALUES ('" + org + "', '" + chart + "', '" + UNIT + "', 'care_relationship', 'ref-"
                + subject + "', '" + UUID.nameUUIDFromBytes(("rel-" + subject).getBytes()).toString()
                + "', '" + state + "', '" + SEEDER + "', now(), now())");
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

    /** Seed one approved channel policy through the versioned configuration fold. */
    private static void seedApprovedChannels(Statement statement, String org, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', 1, '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    @Test
    void publishesApprovesAndServesDurableProfileThroughHttp() throws Exception {
        String base = "/api/v1/care-units/" + UNIT;

        // No effective profile yet: the read reaches the store and finds nothing.
        assertEquals(404, get(base + "/service-profile", PUBLISHER, ORG_A).getStatusCode().value());

        ResponseEntity<String> created = post(base + "/service-profiles", PUBLISHER, ORG_A,
                publishBody(0), null);
        assertEquals(201, created.getStatusCode().value());
        JsonNode draft = tree(created);
        assertEquals("draft", draft.get("status").asText());
        assertEquals(1, draft.get("version").asInt());
        String profileId = draft.get("id").asText();

        // The draft is a durable row, not a process-local map entry.
        assertEquals(1, countProfiles(ORG_A));

        // Stale precondition and same-approver are refused through HTTP.
        assertEquals(412, post(base + "/service-profiles", PUBLISHER, ORG_A,
                publishBody(0), null).getStatusCode().value());
        assertEquals(409, post(base + "/service-profiles/" + profileId + "/approve?note=site-policy",
                PUBLISHER, ORG_A, null, "W/\"1\"").getStatusCode().value());

        // A distinct named tenant_admin approves the draft.
        ResponseEntity<String> approved = post(base + "/service-profiles/" + profileId
                + "/approve?note=site-policy", APPROVER, ORG_A, null, "W/\"1\"");
        assertEquals(200, approved.getStatusCode().value());
        assertEquals("approved", tree(approved).get("status").asText());

        // Re-approval, a stale version and a missing profile are each refused.
        assertEquals(409, post(base + "/service-profiles/" + profileId + "/approve?note=again",
                APPROVER, ORG_A, null, "W/\"1\"").getStatusCode().value());
        assertEquals(412, post(base + "/service-profiles/" + profileId + "/approve?note=again",
                APPROVER, ORG_A, null, "W/\"9\"").getStatusCode().value());
        assertEquals(404, post(base + "/service-profiles/" + UNKNOWN + "/approve?note=again",
                APPROVER, ORG_A, null, "W/\"1\"").getStatusCode().value());

        // A connected patient and an assigned supervisor can read the effective profile.
        ResponseEntity<String> view = get(base + "/service-profile", PATIENT, ORG_A);
        assertEquals(200, view.getStatusCode().value());
        assertEquals(profileId, tree(view).get("id").asText());
        assertEquals(200, get(base + "/availability", PATIENT, ORG_A).getStatusCode().value());
        assertEquals(1, tree(get(base + "/service-profile/versions", PRIVACY, ORG_A))
                .get("versions").size());

        // No work facts yet: the snapshot is derived from an empty durable list.
        assertEquals(0, tree(get(base + "/supervisor-snapshot", SUPERVISOR, ORG_A))
                .get("backlog").asInt());

        // Seed durable work facts through the same store the service uses.
        bindContext(ORG_A, SUPERVISOR);
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WORK_ITEM_ONE, UNIT,
                WorkState.AWAITING_STAFF, null, Instant.parse("2020-01-01T00:00:00Z"), null, false));
        store.addWorkItem(ORG_A, new WorkItemFact(ORG_A, WORK_ITEM_TWO, UNIT,
                WorkState.AWAITING_STAFF, "nurse", Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"), true));
        TenantContext.clear();

        JsonNode snapshot = tree(get(base + "/supervisor-snapshot", SUPERVISOR, ORG_A));
        assertEquals(2, snapshot.get("backlog").asInt());
        assertEquals(1, snapshot.get("unassigned").asInt());
        assertEquals(1, snapshot.get("failedNotifications").asInt());
        assertEquals(1, snapshot.get("overdueResponses").asInt());

        // A replayed insert finds the winner row instead of duplicating a version.
        bindContext(ORG_A, APPROVER);
        var stored = store.findProfile(ORG_A, profileId).orElseThrow();
        assertEquals(profileId, store.insertProfile(ORG_A, stored).id());
        assertEquals(1, countProfiles(ORG_A));
        assertTrue(store.auditEvents(ORG_A).size() >= 10);
        TenantContext.clear();

        // Content and approval state are guarded in the database itself.
        assertThrows(SQLException.class, () -> executeRaw(ORG_A,
                "UPDATE public.service_profile SET response_target_minutes = 999 "
                        + "WHERE id = CAST('" + profileId + "' AS uuid)"));
        assertThrows(SQLException.class, () -> executeRaw(ORG_A,
                "DELETE FROM public.service_profile WHERE id = CAST('" + profileId + "' AS uuid)"));
        assertEquals(1, countProfiles(ORG_A));
    }

    @Test
    void deniedSubjectsCannotCrossTenantOrActWithoutAuthority() {
        String base = "/api/v1/care-units/" + UNIT;
        assertEquals(403, get(base + "/service-profile", "ghost-http", ORG_A).getStatusCode().value());
        assertEquals(403, post(base + "/service-profiles", NO_ROLE, ORG_A,
                publishBody(0), null).getStatusCode().value());
        assertEquals(404, get(base + "/service-profile", UNCONNECTED_PATIENT, ORG_A).getStatusCode().value());
        assertEquals(404, get(base + "/supervisor-snapshot", UNASSIGNED_SUPERVISOR, ORG_A)
                .getStatusCode().value());
        assertEquals(404, get(base + "/service-profile", PUBLISHER, ORG_B).getStatusCode().value());
        // The other tenant sees none of the first tenant's durable profile rows
        // even though the same local unit identifier exists there.
        bindContext(ORG_B, PUBLISHER);
        assertTrue(store.versions(ORG_B, UNIT).isEmpty());
        TenantContext.clear();
    }

    private static void bindContext(String org, String subject) {
        TenantContext.set(new TenantContext(org, subject, "MSG06_TEST", "http-it"));
    }

    private String publishBody(int expectedVersion) {
        return "{\"unitId\":\"" + UNIT + "\",\"timeZone\":\"UTC\","
                + "\"windows\":[\"MONDAY|09:00|17:00\"],\"responseTargetMinutes\":30,"
                + "\"queueOwner\":\"nurse\",\"backupCoverage\":[\"backup\"],"
                + "\"escalationChannel\":\"escalation\",\"urgentChannel\":\"urgent\","
                + "\"downtimeChannel\":\"downtime\",\"topics\":[\"collection\"],"
                + "\"urgentInstructions\":[{\"language\":\"en\",\"value\":\"call us\"},"
                + "{\"language\":\"ar\",\"value\":\"اتصل بنا\"}],"
                + "\"effectiveFrom\":\"2020-01-01T00:00:00Z\",\"expectedVersion\":"
                + expectedVersion + "}";
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
            throw new IllegalStateException("cannot parse service-profile response: " + body, exception);
        }
    }

    private static int countProfiles(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bindGuc(statement, org);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.service_profile "
                            + "WHERE unit_id = CAST('" + UNIT + "' AS uuid)")) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    private static void executeRaw(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bindGuc(statement, org);
            statement.execute(sql);
        }
    }

    private static void bindGuc(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
    }
}
