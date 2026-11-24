package com.aurevia.it.tenancy;

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

import org.flywaydb.core.Flyway;

/** ARC-03 acceptance: facility list paging, stable code ordering and forced-RLS tenant isolation. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_facility_list_it")
@ActiveProfiles("synthetic-auth")
class FacilityListIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_facility_list_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    static final List<String> ORG_A_CODES = List.of(
            "ALPHA-A", "BRAVO-A", "CHARLIE-A", "DELTA-A", "ECHO-A");
    // Organization B reuses two of organization A's local codes to prove RLS isolation.
    static final List<String> ORG_B_CODES = List.of("ALPHA-A", "BRAVO-A", "ZULU-B");

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
            statement.execute("DROP DATABASE IF EXISTS orevia_facility_list_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_facility_list_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_B, "admin-b", "tenant_admin");
            for (String code : ORG_A_CODES) {
                seedFacility(statement, ORG_A, code, "Org A " + code);
            }
            for (String code : ORG_B_CODES) {
                seedFacility(statement, ORG_B, code, "Org B " + code);
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_facility_list_it WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedFacility(Statement statement, String org, String code, String name)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'seed'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                + "VALUES ('" + org + "', '" + code + "', '" + name + "')");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri,
                                           String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> list(String cursor, String limit, String subject, String organization) {
        List<String> query = new ArrayList<>();
        if (cursor != null) {
            query.add("cursor=" + cursor);
        }
        if (limit != null) {
            query.add("limit=" + limit);
        }
        String uri = "/api/v1/facilities" + (query.isEmpty() ? "" : "?" + String.join("&", query));
        return request(HttpMethod.GET, uri, subject, organization);
    }

    private static List<String> values(String body, String needle) {
        List<String> result = new ArrayList<>();
        int index = 0;
        while ((index = body.indexOf(needle, index)) >= 0) {
            int start = index + needle.length();
            int end = body.indexOf('"', start);
            result.add(body.substring(start, end));
            index = end + 1;
        }
        return result;
    }

    private static List<String> codes(String body) {
        return values(body, "\"code\":\"");
    }

    private static String nextCursor(String body) {
        String needle = "\"nextCursor\":";
        int index = body.indexOf(needle);
        if (index < 0) {
            return null;
        }
        int start = index + needle.length();
        if (body.startsWith("null", start)) {
            return null;
        }
        int end = body.indexOf('"', start + 1);
        return body.substring(start + 1, end);
    }

    @Test
    void pagingReturnsEveryOrganizationRowExactlyOnceInOrder() {
        List<String> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<String> response = list(cursor, "2", "admin-a", ORG_A);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            collected.addAll(codes(response.getBody()));
            cursor = nextCursor(response.getBody());
            pages++;
            assertTrue(pages <= 10, "pagination must terminate");
        } while (cursor != null);

        assertEquals(ORG_A_CODES, collected);
        assertEquals(collected.size(), collected.stream().distinct().count());
        collected.forEach(code -> assertTrue(code.endsWith("-A"), "cross-tenant row leaked: " + code));
    }

    @Test
    void organizationBNeverSeesOrganizationARows() {
        ResponseEntity<String> response = list(null, null, "admin-b", ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals(ORG_B_CODES, codes(response.getBody()));
        List<String> orgIds = values(response.getBody(), "\"organizationId\":\"");
        assertFalse(orgIds.isEmpty());
        orgIds.forEach(org -> assertEquals(ORG_B, org, "cross-tenant row leaked: " + org));
        assertNull(nextCursor(response.getBody()));
    }

    @Test
    void invalidPagingInputsAreRejected() {
        assertEquals(422, list(null, "0", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, "201", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, list("not-a-cursor", null, "admin-a", ORG_A).getStatusCode().value());
    }

    @Test
    void unauthorizedAndForbiddenActorsAreDenied() {
        assertEquals(HttpStatus.UNAUTHORIZED, list(null, null, null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, list(null, null, "reception-a", ORG_A).getStatusCode());
        // admin-a has no membership in the selected organization B.
        assertEquals(HttpStatus.FORBIDDEN, list(null, null, "admin-a", ORG_B).getStatusCode());
    }
}
