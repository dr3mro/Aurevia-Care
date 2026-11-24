package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
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

/**
 * IAM-03 clause 2 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>A receptionist registers patients whose names carry case, diacritics and
 * punctuation. A search term is normalized for matching, so an ASCII, differently
 * cased or punctuated query still finds the record, while the stored and returned
 * spelling keeps the original accents. The rule is exercised through the normal
 * HTTP entry points and the durable rows are read back under forced row-level
 * security, so a result proves end-to-end behavior rather than unit coverage.</p>
 *
 * <p>The fixtures, database setup and request helpers mirror the neighbouring
 * {@code authz.clauses} acceptance tests. Synthetic identities and MRNs only.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_iam03_clause2_it")
@ActiveProfiles("synthetic-auth")
class NormalizeSearchWhilePreservingOriginalSpellingTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_iam03_clause2_it";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam03_clause2_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_iam03_clause2_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_B, "reception-b", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam03_clause2_it WITH (FORCE)");
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
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.set("X-Correlation-Id", "iam03-clause2-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> search(String name, String subject, String organization) {
        RestClient.RequestHeadersSpec<?> spec = rest.method(HttpMethod.GET)
                .uri(uriBuilder -> uriBuilder.path("/api/v1/patients").queryParam("name", name).build())
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        return spec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> register(String displayName, String mrn, String subject,
                                            String organization) {
        String body = "{\"displayName\":\"" + displayName + "\",\"mrn\":\"" + mrn
                + "\",\"issuer\":\"CLINIC-A\",\"dob\":\"1990-05-20\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        return request(HttpMethod.POST, "/api/v1/patients", body, subject, organization);
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

    private static int objectCount(String json) {
        int count = 0;
        boolean inString = false;
        for (char c : json.toCharArray()) {
            if (c == '"') {
                inString = !inString;
            } else if (c == '{' && !inString) {
                count++;
            }
        }
        return count;
    }

    private static List<String> storedPatient(String mrn) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT display_name, normalized_name FROM public.current_patients() WHERE mrn = '" + mrn + "'")) {
                if (result.next()) {
                    values.add(result.getString(1));
                    values.add(result.getString(2));
                }
            }
        }
        return values;
    }

    @Test
    void searchNormalizesInputAndPreservesOriginalSpelling() throws Exception {
        ResponseEntity<String> registered = register("José Álvarez", "MRN-ALVAREZ-1",
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, registered.getStatusCode(), registered.getBody());
        assertEquals("José Álvarez", field(registered.getBody(), "displayName"));
        assertEquals("jose alvarez", field(registered.getBody(), "normalizedName"));

        // An ASCII, differently cased query matches the accented record because
        // the search term is normalized the same way as the stored search form.
        ResponseEntity<String> ascii = search("Jose Alvarez", "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, ascii.getStatusCode(), ascii.getBody());
        assertEquals(1, objectCount(ascii.getBody()));
        assertEquals("José Álvarez", field(ascii.getBody(), "displayName"));
        assertEquals("jose alvarez", field(ascii.getBody(), "normalizedName"));

        // A lowercase variant and a punctuated variant also normalize to the
        // same form, while the returned display name is never normalized.
        assertEquals("José Álvarez",
                field(search("jose alvarez", "reception-a", ORG_A).getBody(), "displayName"));
        assertEquals("José Álvarez",
                field(search("Jose-Alvarez", "reception-a", ORG_A).getBody(), "displayName"));

        // The durable row keeps both forms: original spelling for display and
        // the folded form only for search.
        assertEquals(List.of("José Álvarez", "jose alvarez"), storedPatient("MRN-ALVAREZ-1"));
    }

    @Test
    void searchIsRefusedForWrongRole() {
        // tenant_admin maintains organization configuration but has no patient
        // read permission, so a search is denied even with an active membership.
        assertEquals(HttpStatus.FORBIDDEN,
                search("Jose Alvarez", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void searchIsScopedToTheActiveTenant() {
        register("Fatima El-Amin", "MRN-AMIN-SCOPE", "reception-a", ORG_A);
        // The normalized name exists only in organization A. A search from
        // organization B must not see it because forced RLS scopes the query.
        ResponseEntity<String> otherTenant = search("Fatima El Amin", "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, otherTenant.getStatusCode(), otherTenant.getBody());
        assertTrue(otherTenant.getBody().equals("[]"), otherTenant.getBody());
    }

    @Test
    void searchRejectsInvalidName() {
        // A name with a forbidden character never reaches the repository.
        assertEquals(422, search("bad name!", "reception-a", ORG_A).getStatusCode().value());
    }
}
