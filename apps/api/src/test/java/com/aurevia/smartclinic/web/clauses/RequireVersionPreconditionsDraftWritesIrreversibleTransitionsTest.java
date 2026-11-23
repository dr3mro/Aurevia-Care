package com.aurevia.smartclinic.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.scheduling.Appointment;
import com.aurevia.smartclinic.scheduling.AppointmentRepository;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-03 clause 4 acceptance through the real HTTP entry point on PostgreSQL.
 *
 * <p>A temporary hold is the draft: creating it needs no precondition, but the
 * irreversible {@code confirm} command that turns it into a booked appointment
 * requires a matching {@code If-Match} version. The version is parsed at the web
 * boundary (missing 428, malformed 422) and compared by the domain service
 * (stale 412); a second confirm is refused as an illegal transition. Negative
 * cases also prove that a caller without the permission, and a caller in another
 * tenant, cannot perform the transition. All identifiers are synthetic and the
 * stored snapshot is read back through the same RLS-bounded repository.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_web_version_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RequireVersionPreconditionsDraftWritesIrreversibleTransitionsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_web_version_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "reception-2222";

    // Each test owns a distinct Sunday so one test's committed hold never
    // clashes with another's protected clinician or room.
    private static final String CONFIRM_SLOT = "2027-03-07T14:00:00Z";
    private static final String MISSING_SLOT = "2027-03-14T14:00:00Z";
    private static final String MALFORMED_SLOT = "2027-03-21T14:00:00Z";
    private static final String STALE_SLOT = "2027-03-28T14:00:00Z";
    private static final String DENIED_SLOT = "2027-04-04T14:00:00Z";

    @Autowired
    AppointmentRepository appointments;

    @Autowired
    TransactionTemplate transactions;

    RestClient rest;

    /**
     * Create the scratch database, apply the real migrations and seed two
     * synthetic tenants with the same local reference-data codes.
     *
     * @throws Exception when database setup fails
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, "America/New_York");
            seedOrganization(statement, ORG_B, "America/New_York");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_B, PEER, "reception");
        }
    }

    /**
     * Drop the scratch database after the suite.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org, String zone) throws Exception {
        String bootstrap = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', '" + zone + "')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'room', 'ROOM-1', 'Synthetic room' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', id, 7, '09:00', '12:00' "
                + "FROM public.scheduling_resource WHERE code IN ('DR-A', 'ROOM-1')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static String patientId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, subject);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? result.getString(1) : UUID.randomUUID().toString();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Bind the RestClient to the randomly assigned port.
     *
     * @param port the running application's HTTP port
     */
    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Send a request through the real HTTP entry point, capturing every status.
     *
     * <p>The {@code If-Match} header is set only when a value is supplied, so a
     * missing precondition stays missing rather than defaulting to a version.</p>
     *
     * @param method  HTTP method
     * @param uri     request path
     * @param body    optional JSON body
     * @param subject synthetic authenticated subject
     * @param org     active organization
     * @param ifMatch optional version precondition
     * @return the captured response
     */
    private ResponseEntity<String> request(String method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (ifMatch != null) {
            headers.set("If-Match", "\"" + ifMatch + "\"");
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.valueOf(method))
                .uri(uri).headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { }).toEntity(String.class);
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

    /**
     * Bind the active tenant and open a guarded transaction for a direct store
     * read. The transaction manager sets the tenant GUCs after the connection is
     * checked out, which is what forced row-level security requires.
     */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "SCHEDULING", "version-preconditions-it"));
        try {
            return transactions.execute(status -> action.get());
        } finally {
            TenantContext.clear();
        }
    }

    private Appointment stored(String org, String subject, String id) {
        return as(org, subject, () -> appointments.find(id).orElseThrow());
    }

    private ResponseEntity<String> hold(String slot) throws Exception {
        String body = "{\"patientId\":\"" + patientId(ORG_A, RECEPTION) + "\",\"facilityCode\":\"MAIN\","
                + "\"visitType\":\"NEW\",\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\","
                + "\"startsAt\":\"" + slot + "\",\"overrideReason\":null}";
        ResponseEntity<String> created = request("POST", "/api/v1/appointments/holds", body,
                RECEPTION, ORG_A, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return created;
    }

    @Test
    void irreversibleCommandWithMatchingIfMatchAdvancesVersionAndPersists() throws Exception {
        ResponseEntity<String> held = hold(CONFIRM_SLOT);
        String id = field(held.getBody(), "id");
        assertNotNull(id);
        assertEquals("held", field(held.getBody(), "state"));
        assertEquals("1", field(held.getBody(), "version"));

        ResponseEntity<String> confirmed = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, RECEPTION, ORG_A, "1");
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals(id, field(confirmed.getBody(), "id"));
        assertEquals("booked", field(confirmed.getBody(), "state"));
        assertEquals("2", field(confirmed.getBody(), "version"));

        ResponseEntity<String> again = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, RECEPTION, ORG_A, "2");
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode(), again.getBody());
        assertEquals("INVALID_TRANSITION", field(again.getBody(), "code"));

        Appointment persisted = stored(ORG_A, RECEPTION, id);
        assertEquals(id, persisted.id());
        assertEquals(2, persisted.version(), "the confirmed draft advances exactly one version");
        assertEquals("booked", persisted.state().wire(), "the stored irreversible transition is durable");
    }

    @Test
    void irreversibleCommandWithoutIfMatchIsRejected() throws Exception {
        String id = field(hold(MISSING_SLOT).getBody(), "id");

        ResponseEntity<String> missing = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, RECEPTION, ORG_A, null);
        assertEquals(HttpStatus.PRECONDITION_REQUIRED, missing.getStatusCode(), missing.getBody());
        assertEquals("PRECONDITION_REQUIRED", field(missing.getBody(), "code"));

        Appointment persisted = stored(ORG_A, RECEPTION, id);
        assertEquals(1, persisted.version(), "a missing precondition does not write");
        assertEquals("held", persisted.state().wire(), "the draft stays a draft");
    }

    @Test
    void malformedIfMatchIsRejectedBeforeAnyWrite() throws Exception {
        String id = field(hold(MALFORMED_SLOT).getBody(), "id");

        ResponseEntity<String> malformed = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, RECEPTION, ORG_A, "not-a-version");
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_IF_MATCH", field(malformed.getBody(), "code"));

        Appointment persisted = stored(ORG_A, RECEPTION, id);
        assertEquals(1, persisted.version(), "a malformed precondition does not write");
        assertEquals("held", persisted.state().wire());
    }

    @Test
    void staleIfMatchIsRejectedWithoutChangingState() throws Exception {
        String id = field(hold(STALE_SLOT).getBody(), "id");

        ResponseEntity<String> stale = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, RECEPTION, ORG_A, "99");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));

        Appointment persisted = stored(ORG_A, RECEPTION, id);
        assertEquals(1, persisted.version(), "a stale precondition does not advance the version");
        assertEquals("held", persisted.state().wire(), "a stale precondition does not change state");
    }

    @Test
    void wrongRoleOrOtherTenantIsDenied() throws Exception {
        String id = field(hold(DENIED_SLOT).getBody(), "id");

        // A valid precondition is supplied so the denial comes from the domain
        // authorization check, not from the earlier header parse.
        ResponseEntity<String> wrongRole = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, CLINICIAN, ORG_A, "1");
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());
        assertEquals("FORBIDDEN", field(wrongRole.getBody(), "code"));

        ResponseEntity<String> crossTenant = request("POST", "/api/v1/appointments/" + id + "/confirm",
                null, PEER, ORG_B, "1");
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.getStatusCode(), crossTenant.getBody());
        assertEquals("NOT_FOUND", field(crossTenant.getBody(), "code"));
        assertTrue(as(ORG_B, PEER, () -> appointments.find(id)).isEmpty(),
                "the other tenant cannot see the draft through the RLS-bounded store");

        Appointment persisted = stored(ORG_A, RECEPTION, id);
        assertEquals(1, persisted.version());
        assertEquals("held", persisted.state().wire(), "neither denial changes the owner's draft");
    }
}
