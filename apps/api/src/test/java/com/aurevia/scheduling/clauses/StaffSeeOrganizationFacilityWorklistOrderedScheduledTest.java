package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * CLN-02 clause 2 (API evidence): staff see an organization/facility worklist.
 *
 * <p>The rule under test is the read side only: a member with the worklist
 * permission sees the facility worklist ordered by scheduled start and then by
 * the stored (site-configured) priority, with the current assignee and both
 * timestamps carried on each row. The positive test drives
 * {@code GET /api/v1/clinic/worklist} through HTTP against a real PostgreSQL
 * database seeded with three synthetic appointments and worklist rows whose
 * starts-at and priority force a deterministic order. The two negative tests
 * prove the rule refuses what it must refuse: a role without {@code WORKLIST_READ}
 * is denied, and another tenant never sees this tenant's rows.</p>
 *
 * <p>Fixtures are synthetic. Two organizations share the facility code
 * {@code MAIN}; no real patient data or credentials are used. The suite runs
 * under the full Maven verify gate and the worker integration tier; the
 * {@code integration} tag keeps it out of the fast unit tier. This task adds
 * proof only — no production code, migration, contract or existing test is
 * changed.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_cln02_worklist_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class StaffSeeOrganizationFacilityWorklistOrderedScheduledTest {

    private static final String ORG_A = "c1020202-0000-4000-8000-000000000001";
    private static final String ORG_B = "c1020202-0000-4000-8000-000000000002";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_cln02_worklist_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String APPOINTMENT_EARLY_HIGH = "80000001-0000-4000-8000-000000000001";
    private static final String APPOINTMENT_EARLY_LOW = "80000002-0000-4000-8000-000000000002";
    private static final String APPOINTMENT_LATE_LOW = "80000003-0000-4000-8000-000000000003";
    private static final String ENTRY_EARLY_HIGH = "90000001-0000-4000-8000-000000000001";
    private static final String ENTRY_EARLY_LOW = "90000002-0000-4000-8000-000000000002";
    private static final String ENTRY_LATE_LOW = "90000003-0000-4000-8000-000000000003";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Create an isolated database, apply the production migrations and seed the
     * synthetic tenant fixtures. Worklist and appointment rows are inserted
     * directly so scheduled time, priority and assignment are exact and the
     * ordering assertion cannot be disturbed by other test classes.
     *
     * @throws Exception when database setup or seeding fails
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
            statement.execute("DROP DATABASE IF EXISTS orevia_cln02_worklist_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_cln02_worklist_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "nurse-a", "clinical_assistant");
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedSchedulingRows(statement);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_B, "reception-b", "reception");
        }
    }

    /**
     * Drop the scratch database so the worker can run this class repeatedly.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_cln02_worklist_it WITH (FORCE)");
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

    /**
     * The worklist of one facility is ordered by scheduled time and then by
     * stored (site-configured) priority, and each row carries its assignment
     * and timestamps.
     */
    @Test
    void worklistIsOrderedByScheduledTimeThenPriorityWithAssignmentAndTimestamps() {
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/clinic/worklist?facilityCode=MAIN", null, "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());

        JsonNode items = json(response);
        List<String> ordered = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            if (ENTRY_EARLY_HIGH.equals(id) || ENTRY_EARLY_LOW.equals(id) || ENTRY_LATE_LOW.equals(id)) {
                ordered.add(id);
            }
        }
        assertEquals(List.of(ENTRY_EARLY_HIGH, ENTRY_EARLY_LOW, ENTRY_LATE_LOW), ordered,
                "a 09:00 item with priority 7 precedes a 09:00 item with priority 2, "
                        + "and both precede an 11:00 item");

        JsonNode high = item(items, ENTRY_EARLY_HIGH);
        assertEquals(7, high.path("priority").asInt());
        assertNotNull(text(high, "startsAt"), "the scheduled start must be visible on the row");

        JsonNode assigned = item(items, ENTRY_EARLY_LOW);
        assertEquals(2, assigned.path("priority").asInt());
        assertEquals("nurse-a", text(assigned, "assigneeSubject"));
        assertNotNull(text(assigned, "assignedAt"), "the assignment timestamp must be visible");
    }

    /** A role without the worklist permission is refused the worklist. */
    @Test
    void worklistDeniesAStaffRoleWithoutWorklistPermission() {
        ResponseEntity<String> denied = request(HttpMethod.GET,
                "/api/v1/clinic/worklist?facilityCode=MAIN", null, "auditor-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
    }

    /** A worklist read in one tenant never exposes another tenant's entries. */
    @Test
    void worklistDoesNotExposeAnotherTenantsEntries() {
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/clinic/worklist?facilityCode=MAIN", null, "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertFalse(response.getBody().contains(ENTRY_EARLY_LOW),
                "another tenant must not see this tenant's worklist entry");
    }

    private static void seedOrganization(Statement statement, String organization) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + organization + "', 'Synthetic worklist " + organization.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', 'bootstrap', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + organization + "', 'MAIN', 'Main clinic', 'UTC')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + organization + "', 'NEW', 30)");
    }

    private static void seedMember(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    private static void seedSchedulingRows(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'reception-a'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_A + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
        for (String[] resource : new String[][] {{"DR-A", "clinician"}, {"ROOM-1", "room"},
                {"DR-B", "clinician"}, {"ROOM-2", "room"}}) {
            statement.execute("INSERT INTO public.scheduling_resource "
                    + "(organization_id, facility_id, kind, code, display_name) "
                    + "SELECT '" + ORG_A + "', id, '" + resource[1] + "', '" + resource[0]
                    + "', 'Synthetic " + resource[0] + "' FROM public.facility WHERE code = 'MAIN'");
        }

        insertAppointment(statement, APPOINTMENT_EARLY_HIGH, "DR-B", "ROOM-2", "09:00", "09:30");
        insertAppointment(statement, APPOINTMENT_EARLY_LOW, "DR-A", "ROOM-1", "09:00", "09:30");
        insertAppointment(statement, APPOINTMENT_LATE_LOW, "DR-A", "ROOM-1", "11:00", "11:30");

        insertWorklistEntry(statement, ENTRY_EARLY_HIGH, APPOINTMENT_EARLY_HIGH, 7, null);
        insertWorklistEntry(statement, ENTRY_EARLY_LOW, APPOINTMENT_EARLY_LOW, 2,
                "'nurse-a', TIMESTAMPTZ '2027-01-04 09:05:00+00'");
        insertWorklistEntry(statement, ENTRY_LATE_LOW, APPOINTMENT_LATE_LOW, 2, null);
    }

    private static void insertAppointment(Statement statement, String id, String clinician,
                                          String room, String from, String to) throws Exception {
        statement.execute("INSERT INTO public.appointment "
                + "(organization_id, id, patient_id, facility_id, clinician_resource_id, "
                + " room_resource_id, visit_type, starts_at, ends_at, state) "
                + "SELECT '" + ORG_A + "', '" + id + "', "
                + "(SELECT id FROM public.patient_record WHERE mrn = 'MRN-1'), "
                + "(SELECT id FROM public.facility WHERE code = 'MAIN'), "
                + "(SELECT id FROM public.scheduling_resource WHERE code = '" + clinician + "'), "
                + "(SELECT id FROM public.scheduling_resource WHERE code = '" + room + "'), "
                + "'NEW', TIMESTAMPTZ '2027-01-04 " + from + ":00+00', "
                + "TIMESTAMPTZ '2027-01-04 " + to + ":00+00', 'booked'");
    }

    private static void insertWorklistEntry(Statement statement, String entryId, String appointmentId,
                                            int priority, String assignment) throws Exception {
        String columns = "organization_id, id, appointment_id, patient_id, facility_id, priority";
        String values = "'" + ORG_A + "', '" + entryId + "', "
                + "CAST('" + appointmentId + "' AS uuid), "
                + "(SELECT patient_id FROM public.appointment WHERE id = '" + appointmentId + "'), "
                + "(SELECT facility_id FROM public.appointment WHERE id = '" + appointmentId + "'), "
                + priority;
        if (assignment != null) {
            columns += ", assignee_subject, assigned_at";
            values += ", " + assignment;
        }
        statement.execute("INSERT INTO public.worklist_entry (" + columns + ") VALUES (" + values + ")");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject,
                                           String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    private static JsonNode item(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.path("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("worklist entry " + id + " is missing from " + items);
    }

    private static String text(JsonNode item, String key) {
        JsonNode value = item.path(key);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}
