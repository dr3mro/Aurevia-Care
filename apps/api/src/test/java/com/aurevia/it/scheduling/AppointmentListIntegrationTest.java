package com.aurevia.it.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.flywaydb.core.Flyway;

/**
 * ARC-03 acceptance: appointment list paging, stable scheduled-time ordering and
 * forced-RLS tenant isolation on a real PostgreSQL instance.
 *
 * <p>Both organizations use the same local facility and resource codes. All
 * records are synthetic; no patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_appointment_list_it")
@ActiveProfiles("synthetic-auth")
class AppointmentListIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_appointment_list_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String FROM = "2027-01-01T00:00:00Z";
    static final String TO = "2027-01-31T00:00:00Z";

    static final List<String> IDS_A = List.of(
            "aaaaaaaa-0000-0000-0000-000000000001",
            "aaaaaaaa-0000-0000-0000-000000000002",
            "aaaaaaaa-0000-0000-0000-000000000003");
    static final List<String> STARTS_A = List.of(
            "2027-01-05T09:00:00Z", "2027-01-06T09:00:00Z", "2027-01-07T09:00:00Z");
    static final List<String> IDS_B = List.of(
            "bbbbbbbb-0000-0000-0000-000000000001",
            "bbbbbbbb-0000-0000-0000-000000000002");
    static final List<String> STARTS_B = List.of("2027-01-05T09:00:00Z", "2027-01-06T09:00:00Z");

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

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
            statement.execute("DROP DATABASE IF EXISTS orevia_appointment_list_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_appointment_list_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, "admin-a", "reception-a");
            seedOrganization(statement, ORG_B, "admin-b", "reception-b");
            seedAppointments(statement, ORG_A, "admin-a", IDS_A, STARTS_A);
            seedAppointments(statement, ORG_B, "admin-b", IDS_B, STARTS_B);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_appointment_list_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org, String admin, String reception)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + admin + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        seedMember(statement, org, admin, "tenant_admin");
        seedMember(statement, org, reception, "reception");
        seedMember(statement, org, "auditor-" + org.substring(0, 4), "privacy_auditor");
        statement.execute("SET app.actor = '" + admin + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', 'UTC')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30)");
        for (String code : List.of("DR-A", "ROOM-1")) {
            statement.execute("INSERT INTO public.scheduling_resource "
                    + "(organization_id, facility_id, kind, code, display_name) "
                    + "SELECT '" + org + "', id, "
                    + (code.startsWith("DR") ? "'clinician'" : "'room'") + ", '" + code + "', 'Synthetic "
                    + code + "' FROM public.facility WHERE code = 'MAIN'");
        }
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

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

    private static void seedAppointments(Statement statement, String org, String actor,
                                         List<String> ids, List<String> starts) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        for (int index = 0; index < ids.size(); index++) {
            String end = java.time.Instant.parse(starts.get(index)).plusSeconds(30L * 60).toString();
            statement.execute("INSERT INTO public.appointment "
                    + "(organization_id, id, version, patient_id, facility_id, clinician_resource_id, "
                    + "room_resource_id, visit_type, starts_at, ends_at, state) "
                    + "SELECT '" + org + "', CAST('" + ids.get(index) + "' AS uuid), 1, "
                    + "(SELECT id FROM public.patient_record WHERE organization_id = '" + org + "' LIMIT 1), "
                    + "(SELECT id FROM public.facility WHERE organization_id = '" + org + "' AND code = 'MAIN'), "
                    + "(SELECT id FROM public.scheduling_resource WHERE organization_id = '" + org
                    + "' AND code = 'DR-A'), "
                    + "(SELECT id FROM public.scheduling_resource WHERE organization_id = '" + org
                    + "' AND code = 'ROOM-1'), "
                    + "'NEW', CAST('" + starts.get(index) + "' AS timestamptz), "
                    + "CAST('" + end + "' AS timestamptz), 'booked'");
            statement.execute("INSERT INTO public.appointment_event "
                    + "(organization_id, appointment_id, appointment_version, action, to_state) "
                    + "VALUES ('" + org + "', CAST('" + ids.get(index) + "' AS uuid), 1, 'booked', 'booked')");
        }
    }

    private ResponseEntity<String> list(String cursor, String limit, String from, String to,
                                        String resource, String state, String subject, String organization) {
        List<String> query = new ArrayList<>();
        if (cursor != null) {
            query.add("cursor=" + cursor);
        }
        if (limit != null) {
            query.add("limit=" + limit);
        }
        if (from != null) {
            query.add("from=" + from);
        }
        if (to != null) {
            query.add("to=" + to);
        }
        if (resource != null) {
            query.add("resource=" + resource);
        }
        if (state != null) {
            query.add("state=" + state);
        }
        String uri = "/api/v1/appointments" + (query.isEmpty() ? "" : "?" + String.join("&", query));
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(HttpMethod.GET).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode root(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    private static List<String> ids(String body) throws Exception {
        List<String> result = new ArrayList<>();
        for (JsonNode item : root(body).get("items")) {
            result.add(item.get("appointment").get("id").asText());
        }
        return result;
    }

    private static List<String> starts(String body) throws Exception {
        List<String> result = new ArrayList<>();
        for (JsonNode item : root(body).get("items")) {
            result.add(item.get("appointment").get("startsAt").asText());
        }
        return result;
    }

    private static String nextCursor(String body) throws Exception {
        JsonNode node = root(body).get("nextCursor");
        return node == null || node.isNull() ? null : node.asText();
    }

    @Test
    void pagingReturnsEveryOrganizationRowExactlyOnceInOrder() throws Exception {
        List<String> collected = new ArrayList<>();
        List<String> orderedStarts = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<String> response = list(cursor, "2", FROM, TO, null, null, "admin-a", ORG_A);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            collected.addAll(ids(response.getBody()));
            orderedStarts.addAll(starts(response.getBody()));
            cursor = nextCursor(response.getBody());
            pages++;
            assertTrue(pages <= 10, "pagination must terminate");
        } while (cursor != null);

        assertEquals(IDS_A, collected);
        assertEquals(STARTS_A, orderedStarts);
        assertEquals(collected.size(), collected.stream().distinct().count());
        collected.forEach(id -> assertFalse(id.startsWith("bbbbbbbb"), "cross-tenant row leaked: " + id));
    }

    @Test
    void organizationBNeverSeesOrganizationARows() throws Exception {
        ResponseEntity<String> response = list(null, null, FROM, TO, null, null, "admin-b", ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals(IDS_B, ids(response.getBody()));
        assertEquals(STARTS_B, starts(response.getBody()));
        ids(response.getBody()).forEach(id -> assertFalse(id.startsWith("aaaaaaaa"),
                "cross-tenant row leaked: " + id));
        assertNull(nextCursor(response.getBody()));
    }

    @Test
    void resourceAndStateFiltersAreApplied() throws Exception {
        ResponseEntity<String> clinician = list(null, null, FROM, TO, "DR-A", "booked", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, clinician.getStatusCode(), clinician.getBody());
        assertEquals(IDS_A, ids(clinician.getBody()));

        ResponseEntity<String> room = list(null, null, FROM, TO, "ROOM-1", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, room.getStatusCode(), room.getBody());
        assertEquals(IDS_A, ids(room.getBody()));

        ResponseEntity<String> unknownResource = list(null, null, FROM, TO, "UNKNOWN", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, unknownResource.getStatusCode(), unknownResource.getBody());
        assertEquals(List.of(), ids(unknownResource.getBody()));

        ResponseEntity<String> otherState = list(null, null, FROM, TO, null, "cancelled", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, otherState.getStatusCode(), otherState.getBody());
        assertEquals(List.of(), ids(otherState.getBody()));
    }

    @Test
    void invalidPagingInputsAreRejected() {
        assertEquals(422, list(null, "0", FROM, TO, null, null, "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, "201", FROM, TO, null, null, "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, list("not-a-cursor", null, FROM, TO, null, null, "admin-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, list(null, null, TO, FROM, null, null, "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, null, FROM, "2027-02-02T00:00:00Z", null, null, "admin-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, list(null, null, FROM, TO, null, "unknown", "admin-a", ORG_A)
                .getStatusCode().value());
    }

    @Test
    void unauthorizedAndForbiddenActorsAreDenied() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                list(null, null, FROM, TO, null, null, null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                list(null, null, FROM, TO, null, null, "auditor-1111", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                list(null, null, FROM, TO, null, null, "admin-a", ORG_B).getStatusCode());
    }
}
