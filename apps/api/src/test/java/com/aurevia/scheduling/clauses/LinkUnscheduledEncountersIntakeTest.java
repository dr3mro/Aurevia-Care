package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

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
 * CLN-02 clause 5 acceptance: an unscheduled arrival (a patient with no booked
 * appointment) is linked to intake and placed on the organization/facility
 * worklist. This class proves only that clause through its real HTTP entry
 * point on a real PostgreSQL instance, with one positive case and two refusals
 * (a role that may not link intake and a patient owned by another tenant).
 *
 * <p>Fixtures are synthetic: two organizations share the same facility and
 * patient-local codes, and no real patient data or credentials are used. The
 * database name is unique to this class so parallel clause suites cannot
 * collide. The suite carries the {@code integration} tag and is excluded from
 * the fast swarm tier; Maven verify runs it.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_cln02_intake_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class LinkUnscheduledEncountersIntakeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_cln02_intake_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Point the test client at the randomly assigned application port.
     *
     * @param port local HTTP port of the running application
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create a fresh database from the production Flyway migrations and seed two
     * synthetic tenants with the same facility and patient codes.
     *
     * @throws Exception when database preparation fails
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln02_intake_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_cln02_intake_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_B, "reception-b", "reception");
        }
    }

    /**
     * Drop the synthetic database after the class so no state leaks.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln02_intake_it WITH (FORCE)");
        }
    }

    /**
     * Seed an organization, its shared facility code and one synthetic patient.
     *
     * @param statement open statement bound to the test database
     * @param org       organization identifier
     * @throws Exception when seeding fails
     */
    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', 'UTC')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    /**
     * Seed an active member with one role in an organization.
     *
     * @param statement open statement bound to the test database
     * @param org       organization identifier
     * @param subject   synthetic subject of the member
     * @param role      role key granted to the member
     * @throws Exception when seeding fails
     */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /**
     * Read the synthetic patient identifier scoped to one organization.
     *
     * @param organization organization the patient belongs to
     * @return the patient's opaque identifier
     */
    private static String patientId(String organization) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "bootstrap");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    /**
     * Bind tenant context on a read-only connection so RLS resolves the tenant.
     *
     * @param connection  connection to bind
     * @param organization active organization identifier
     * @param subject      acting subject
     * @throws Exception when binding fails
     */
    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /**
     * Count rows with a tenant-bound read under row level security.
     *
     * @param organization organization to scope the read to
     * @param sql          aggregate query returning one long value
     * @return the counted value
     * @throws Exception when the query fails
     */
    private static long count(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "bootstrap");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Build synthetic-auth request headers for one actor and active organization.
     *
     * @param subject      synthetic subject, or {@code null} for unauthenticated
     * @param organization active organization identifier
     * @return the prepared headers
     */
    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Send an intake command through the real HTTP entry point.
     *
     * @param patientId   patient to link to intake
     * @param reason      why the arrival has no appointment
     * @param subject     acting synthetic subject
     * @param organization active organization identifier
     * @return the HTTP response
     */
    private ResponseEntity<String> link(String patientId, String reason, String subject,
                                        String organization) {
        String body = "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patientId + "\","
                + "\"reason\":\"" + reason + "\"}";
        return rest.method(HttpMethod.POST).uri("/api/v1/clinic/worklist/intake")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Read the worklist for a facility through its real HTTP entry point.
     *
     * @param subject      reading synthetic subject
     * @param organization active organization identifier
     * @return the HTTP response
     */
    private ResponseEntity<String> worklist(String subject, String organization) {
        return rest.method(HttpMethod.GET).uri("/api/v1/clinic/worklist?facilityCode=MAIN")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /**
     * Parse a response body as JSON.
     *
     * @param response HTTP response to parse
     * @return the parsed JSON tree
     */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /**
     * Read a text field from a JSON response body.
     *
     * @param response HTTP response to read
     * @param pointer  JSON pointer to the field
     * @return the field text, or {@code null} for a JSON null
     */
    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    /**
     * A receptionist links a patient with no appointment to intake; the new
     * worklist row carries the reason, has no appointment, is visible only on
     * its own facility worklist and leaves a successful audit row.
     */
    @Test
    void unscheduledArrivalLinksToIntakeAndAppearsOnOwnFacilityWorklist() throws Exception {
        ResponseEntity<String> created = link(patientId(ORG_A), "walk-in", "reception-a", ORG_A);

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String itemId = text(created, "/id");
        assertNotNull(itemId);
        assertEquals("walk-in", text(created, "/intakeReason"));
        assertTrue(json(created).at("/appointmentId").isNull(),
                "a walk-in must not be linked to an appointment");
        assertEquals("MAIN", text(created, "/facilityCode"));

        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.worklist_entry "
                + "WHERE intake_reason = 'walk-in' AND appointment_id IS NULL"));
        assertTrue(json(worklist("reception-a", ORG_A)).toString().contains(itemId),
                "the walk-in must appear on its own facility worklist");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'INTAKE_LINK' AND outcome = 'SUCCESS' AND resource_id = '" + itemId + "'"));
    }

    /**
     * A clinician is an active member but has no intake-link permission, so the
     * rule refuses the walk-in and records a denial audit row.
     */
    @Test
    void clinicianCannotLinkUnscheduledArrivalToIntake() throws Exception {
        String patient = patientId(ORG_A);

        ResponseEntity<String> denied = link(patient, "clinician-attempt", "clinician-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.worklist_entry "
                + "WHERE intake_reason = 'clinician-attempt'"));
        assertTrue(count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'INTAKE_LINK' AND outcome = 'DENIED' AND resource_id = '" + patient + "'") >= 1);
    }

    /**
     * A patient owned by another tenant cannot be linked into this tenant's
     * intake, even by a receptionist who holds the intake-link permission.
     */
    @Test
    void intakeOfAnotherTenantsPatientIsRefused() throws Exception {
        String foreignPatient = patientId(ORG_B);

        ResponseEntity<String> refused = link(foreignPatient, "cross-tenant-attempt", "reception-a", ORG_A);

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", text(refused, "/code"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.worklist_entry "
                + "WHERE intake_reason = 'cross-tenant-attempt'"));
        assertFalse(json(worklist("reception-a", ORG_A)).toString().contains(foreignPatient),
                "a foreign patient must never appear on this tenant's worklist");
    }
}
