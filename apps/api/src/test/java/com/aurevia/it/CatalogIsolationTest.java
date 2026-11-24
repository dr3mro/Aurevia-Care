package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

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

/** S-G0-03 catalog slice acceptance: versioned tenant catalogs with named approval (ADM-01/OPS-02). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_catalog_it")
@ActiveProfiles("synthetic-auth")
class CatalogIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_catalog_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_catalog_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_catalog_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_catalog_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> define(String family, String code, String value,
                                          String actor, String org) {
        return request(HttpMethod.POST, "/api/v1/catalogs/" + family + "/entries",
                "{\"code\":\"" + code + "\",\"value\":\"" + value + "\"}", actor, org);
    }

    @Test
    void approvalMakesCatalogEntryEffective() {
        ResponseEntity<String> defined = define("diagnosis", "R51", "Headache", "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, defined.getStatusCode(), defined.getBody());
        assertEquals("/api/v1/catalogs/diagnosis/entries/R51",
                defined.getHeaders().getFirst(HttpHeaders.LOCATION));
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/catalogs/diagnosis/entries/R51", null, "admin-a", ORG_A)
                        .getStatusCode());
        assertEquals(HttpStatus.OK,
                request(HttpMethod.POST, "/api/v1/catalogs/diagnosis/entries/R51/versions/1/approve",
                        "{}", "admin-a", ORG_A).getStatusCode());
        ResponseEntity<String> effective = request(HttpMethod.GET,
                "/api/v1/catalogs/diagnosis/entries/R51", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        assertTrue(effective.getBody().contains("Headache"));
        assertTrue(effective.getBody().contains("diagnosis"));
    }

    @Test
    void familyListsOnlyEffectiveEntriesInTheActiveTenant() {
        define("diagnosis", "R51", "Headache", "admin-a", ORG_A);
        request(HttpMethod.POST, "/api/v1/catalogs/diagnosis/entries/R51/versions/1/approve",
                "{}", "admin-a", ORG_A);
        define("diagnosis", "R52", "Migraine", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK,
                request(HttpMethod.POST, "/api/v1/catalogs/diagnosis/entries/R52/versions/1/approve",
                        "{}", "admin-a", ORG_A).getStatusCode());
        ResponseEntity<String> family = request(HttpMethod.GET,
                "/api/v1/catalogs/diagnosis", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, family.getStatusCode());
        assertTrue(family.getBody().contains("R51"));
        assertTrue(family.getBody().contains("R52"));

        // A peer tenant with the same local code is concealed.
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/catalogs/diagnosis/entries/R51", null, "admin-b", ORG_B)
                        .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.GET, "/api/v1/catalogs/diagnosis", null, "admin-a", ORG_B)
                        .getStatusCode());
    }

    @Test
    void invalidInputsAndRolesAreDenied() {
        assertEquals(HttpStatus.FORBIDDEN, define("diagnosis", "R51", "x", "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, define("diagnosis", "R51", "x", null, ORG_A).getStatusCode());
        assertEquals(422, define("Bad", "R51", "x", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, define("diagnosis", "Bad Code", "x", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, define("diagnosis", "R51", " ", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, define("diagnosis", "R51", "x", "admin-a", "bad-org").getStatusCode().value());
        assertFalse(request(HttpMethod.GET, "/api/v1/catalogs/diagnosis", null, "admin-a", ORG_A)
                .getBody().contains("org-b"));
    }
}
