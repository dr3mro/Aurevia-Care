package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-06 clause 6 acceptance through the real HTTP entry point: time-sensitive
 * care may use only the site's approved urgent and downtime channels.
 *
 * <p>The fixture seeds a synthetic tenant, three care units, a named publisher
 * and approver, a connected patient and an approved site-channel policy through
 * the authoritative {@code config_entry}/{@code config_approval} fold. Publish
 * now reads that fold and refuses any urgent or downtime code outside the
 * approved set through the audited denial path, so both tests run and pass.</p>
 *
 * <p>The policy keys and the {@code UNAPPROVED_CHANNEL} code mirror the
 * established approved-channel convention in {@code prescription.rx.RxService}.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_urgent_channels_it")
@ActiveProfiles("synthetic-auth")
class UseSiteSApprovedUrgentDowntimeChannelsTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/orevia_urgent_channels_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String SEEDER = "seeder-channels";
    private static final String PUBLISHER = "publisher-channels";
    private static final String APPROVER = "approver-channels";
    private static final String PATIENT = "patient-channels";
    private static final String UNIT_APPROVED = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String UNIT_UNAPPROVED = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String UNIT_BLANK = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String APPROVED_URGENT = "ONCALL_SMS";
    private static final String APPROVED_DOWNTIME = "ONCALL_PHONE";
    private static final String UNAPPROVED_CHANNEL = "FREE_TEXT_URGENT";
    private static final String URGENT_POLICY_KEY = "msg06.approved_urgent_channels";
    private static final String DOWNTIME_POLICY_KEY = "msg06.approved_downtime_channels";
    private static final String UNAPPROVED_CODE = "UNAPPROVED_CHANNEL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Initialize the HTTP client against the randomly assigned local port.
     *
     * @param port the port Spring Boot chose for this test class
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a fresh, migrated PostgreSQL database and seed only synthetic fixtures. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_urgent_channels_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_urgent_channels_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement);
        }
    }

    /** Drop the per-class database so a later run starts from an empty schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_urgent_channels_it WITH (FORCE)");
        }
    }

    /**
     * Seed a synthetic tenant, staff, a connected patient, three units and the
     * approved channel policy. All identifiers are fixtures, never real data.
     *
     * @param statement a statement on the freshly migrated test database
     * @throws Exception when a seed statement fails
     */
    private static void seedTenant(Statement statement) throws Exception {
        bindActor(statement, SEEDER);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + ORG + "', 'Synthetic channel tenant')");
        seedMember(statement, SEEDER, "tenant_admin");
        seedMember(statement, PUBLISHER, "tenant_admin");
        seedMember(statement, APPROVER, "tenant_admin");
        seedMember(statement, PATIENT, "patient");

        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG + "', '" + CHART + "', 1, 'MRN-CHANNELS', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
        seedUnit(statement, UNIT_APPROVED);
        seedUnit(statement, UNIT_UNAPPROVED);
        seedUnit(statement, UNIT_BLANK);

        bindActor(statement, SEEDER);
        statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                + "VALUES ('" + ORG + "', '" + PATIENT + "', '" + PATIENT + "')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, account_id, chart_id, event, method) "
                + "VALUES ('" + ORG + "', (SELECT id FROM public.patient_account WHERE organization_id = '"
                + ORG + "' AND subject = '" + PATIENT + "'), '" + CHART + "', 'linked', 'synthetic')");
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, patient_id, unit_id, context, context_reference, relationship_id, "
                + " state, verified_by, verified_at, created_at) "
                + "VALUES ('" + ORG + "', '" + CHART + "', '" + UNIT_APPROVED + "', 'care_relationship', "
                + "'ref-channels', '" + UUID.nameUUIDFromBytes("rel-channels".getBytes())
                + "', 'active', '" + SEEDER + "', now(), now())");

        // The doubled comma is intentional: a stray separator must not turn an
        // empty token into an approvable channel code.
        seedPolicy(statement, URGENT_POLICY_KEY, APPROVED_URGENT + ",,SECURE_PUSH");
        seedPolicy(statement, DOWNTIME_POLICY_KEY, APPROVED_DOWNTIME + ",PAGER");
    }

    /** Seed one synthetic staff identity with an active tenant-admin membership. */
    private static void seedMember(Statement statement, String subject, String role) throws Exception {
        bindActor(statement, SEEDER);
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Seed one synthetic connected care unit. */
    private static void seedUnit(Statement statement, String unit) throws Exception {
        bindActor(statement, SEEDER);
        statement.execute("INSERT INTO public.care_unit "
                + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                + "VALUES ('" + ORG + "', '" + unit + "', '" + CHART + "', 'Synthetic Unit " + unit + "', "
                + "ARRAY['collection'], true, 'clinic')");
    }

    /**
     * Seed one approved site-channel policy through the versioned config fold.
     *
     * @param statement the seed statement
     * @param key       tenant-local approved-channel policy key
     * @param value     comma-separated synthetic approved channel codes
     * @throws Exception when a seed statement fails
     */
    private static void seedPolicy(Statement statement, String key, String value) throws Exception {
        bindActor(statement, APPROVER);
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + ORG + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + ORG + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    /**
     * Evidence for the rule being met: a draft published with the site's approved
     * urgent and downtime channels can be approved and served with exactly those
     * channels, both in the HTTP response and in the committed row. This is the
     * anchor that proves approved channels still work; the refusal below is the
     * assertion that proves enforcement.
     */
    @Test
    void publishApprovesAndServesSiteApprovedUrgentAndDowntimeChannels() throws Exception {
        String base = "/api/v1/care-units/" + UNIT_APPROVED;

        ResponseEntity<String> created = post(base + "/service-profiles", PUBLISHER,
                publishBody(UNIT_APPROVED, APPROVED_URGENT, APPROVED_DOWNTIME, 0), null);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        JsonNode draft = tree(created);
        assertEquals("draft", draft.get("status").asText());
        String profileId = draft.get("id").asText();
        assertEquals(1, countProfiles(UNIT_APPROVED));

        ResponseEntity<String> approved = post(base + "/service-profiles/" + profileId
                + "/approve?note=site-policy", APPROVER, null, "W/\"1\"");
        assertEquals(200, approved.getStatusCode().value(), approved.getBody());
        assertEquals("approved", tree(approved).get("status").asText());

        ResponseEntity<String> view = get(base + "/service-profile", PATIENT);
        assertEquals(200, view.getStatusCode().value(), view.getBody());
        assertEquals(APPROVED_URGENT, tree(view).get("urgentChannel").asText());
        assertEquals(APPROVED_DOWNTIME, tree(view).get("downtimeChannel").asText());
        assertEquals(APPROVED_URGENT, channelColumn(UNIT_APPROVED, "urgent_channel"));
        assertEquals(APPROVED_DOWNTIME, channelColumn(UNIT_APPROVED, "downtime_channel"));
    }

    /**
     * Evidence for the rule refusing what it must refuse: a structurally valid
     * but unapproved urgent channel is rejected with a structured 422 and no row;
     * a blank downtime channel is likewise rejected. Both refusals are audited
     * with no durable row, because publish checks membership before parsing the
     * free-text fields and routes the refusal through its audited denial path.
     */
    @Test
    void publishRefusesUnapprovedAndBlankUrgentDowntimeChannels() throws Exception {
        ResponseEntity<String> unapproved = post(
                "/api/v1/care-units/" + UNIT_UNAPPROVED + "/service-profiles", PUBLISHER,
                publishBody(UNIT_UNAPPROVED, UNAPPROVED_CHANNEL, APPROVED_DOWNTIME, 0), null);
        assertEquals(422, unapproved.getStatusCode().value(), unapproved.getBody());
        assertEquals(UNAPPROVED_CODE, tree(unapproved).get("code").asText());

        ResponseEntity<String> blank = post(
                "/api/v1/care-units/" + UNIT_BLANK + "/service-profiles", PUBLISHER,
                publishBody(UNIT_BLANK, APPROVED_URGENT, "", 0), null);
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());

        assertEquals(0, countProfiles(UNIT_UNAPPROVED));
        assertEquals(0, countProfiles(UNIT_BLANK));
        assertTrue(countAudit(UNIT_UNAPPROVED, "PROFILE_PUBLISH", "DENIED") >= 1);
        assertTrue(countAudit(UNIT_BLANK, "PROFILE_PUBLISH", "DENIED") >= 1);
        assertEquals(0, countAudit(UNIT_UNAPPROVED, "PROFILE_PUBLISH", "SUCCESS"));
        assertEquals(0, countAudit(UNIT_BLANK, "PROFILE_PUBLISH", "SUCCESS"));
    }

    /** Build a publish command using only today's contract fields. */
    private static String publishBody(String unit, String urgent, String downtime, int expectedVersion) {
        return "{\"unitId\":\"" + unit + "\",\"timeZone\":\"UTC\","
                + "\"windows\":[\"MONDAY|09:00|17:00\"],\"responseTargetMinutes\":30,"
                + "\"queueOwner\":\"nurse\",\"backupCoverage\":[\"backup\"],"
                + "\"escalationChannel\":\"escalation\",\"urgentChannel\":\"" + urgent + "\","
                + "\"downtimeChannel\":\"" + downtime + "\",\"topics\":[\"collection\"],"
                + "\"urgentInstructions\":[{\"language\":\"en\",\"value\":\"call us\"},"
                + "{\"language\":\"ar\",\"value\":\"اتصل بنا\"}],"
                + "\"effectiveFrom\":\"2020-01-01T00:00:00Z\",\"expectedVersion\":"
                + expectedVersion + "}";
    }

    /** Send a request with synthetic identity and active-organization headers. */
    private ResponseEntity<String> get(String path, String subject) {
        return send(HttpMethod.GET, path, subject, null, null);
    }

    /** Send a publish or approve command with synthetic identity headers. */
    private ResponseEntity<String> post(String path, String subject, String body, String ifMatch) {
        return send(HttpMethod.POST, path, subject, body, ifMatch);
    }

    /** Send a request and capture every status so denials can be asserted. */
    private ResponseEntity<String> send(HttpMethod method, String path, String subject,
                                        String body, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG)
                .contentType(MediaType.APPLICATION_JSON);
        if (ifMatch != null) {
            spec.header("If-Match", ifMatch);
        }
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a JSON response body. */
    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response: " + response.getBody(), exception);
        }
    }

    /** Count durable profiles of one synthetic unit. */
    private static int countProfiles(String unit) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bindActor(statement, SEEDER);
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.service_profile "
                    + "WHERE unit_id = CAST('" + unit + "' AS uuid)")) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    /** Read one stored channel column of the effective profile of a unit. */
    private static String channelColumn(String unit, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bindActor(statement, SEEDER);
            try (ResultSet rows = statement.executeQuery("SELECT " + column
                    + " FROM public.service_profile WHERE unit_id = CAST('" + unit + "' AS uuid)")) {
                return rows.next() ? rows.getString(1) : "";
            }
        }
    }

    /** Count audit rows for one action and outcome of a synthetic unit. */
    private static int countAudit(String unit, String action, String outcome) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bindActor(statement, SEEDER);
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM public.service_profile_audit "
                    + "WHERE resource_id = '" + unit + "' AND action = '" + action
                    + "' AND outcome = '" + outcome + "'")) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    /** Bind the tenant and actor GUCs used by forced row level security. */
    private static void bindActor(Statement statement, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + ORG + "'");
        statement.execute("SET app.actor = '" + actor + "'");
    }
}
