package com.aurevia.it.lab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * ARC-03 acceptance for the lab accession list against a real PostgreSQL:
 * forced-RLS tenant isolation, stable creation-time paging and the state filter.
 * All organizations, patients and accessions are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_accession_list_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class AccessionListIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String PATIENT_B = "bbbbbbbb-0000-0000-0000-000000000002";
    static final String ACCESSION_A1 = "aaaaaaaa-1111-1111-1111-111111111111";
    static final String ACCESSION_A2 = "aaaaaaaa-2222-2222-2222-222222222222";
    static final String ACCESSION_A3 = "aaaaaaaa-3333-3333-3333-333333333333";
    static final String ACCESSION_B1 = "bbbbbbbb-1111-1111-1111-111111111111";
    static final String ACCESSION_B2 = "bbbbbbbb-2222-2222-2222-222222222222";
    // A2 and A3 share a creation instant so paging must fall back to the id tie-break.
    static final List<String> ACCESSIONS_A = List.of(ACCESSION_A1, ACCESSION_A3, ACCESSION_A2);
    static final List<String> ACCESSIONS_B = List.of(ACCESSION_B1, ACCESSION_B2);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_accession_list_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_accession_list_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_accession_list_it OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, ORG_A, "lab-a", "lab_collector");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "lab-b", "lab_collector");
            seedPatient(statement, ORG_A, PATIENT_A, "MRN-A", "lab-a");
            seedPatient(statement, ORG_B, PATIENT_B, "MRN-B", "lab-b");
            seedAccession(statement, ORG_A, ACCESSION_A1, "ACC-A1", PATIENT_A, "lab-a", false, 3);
            seedAccession(statement, ORG_A, ACCESSION_A2, "ACC-A2", PATIENT_A, "lab-a", false, 5);
            seedAccession(statement, ORG_A, ACCESSION_A3, "ACC-A3", PATIENT_A, "lab-a", true, 5);
            seedAccession(statement, ORG_B, ACCESSION_B1, "ACC-B1", PATIENT_B, "lab-b", false, 2);
            seedAccession(statement, ORG_B, ACCESSION_B2, "ACC-B2", PATIENT_B, "lab-b", false, 4);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_accession_list_it WITH (FORCE)");
        }
    }

    private static void bind(Statement statement, String org, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        bind(statement, org, subject);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic org " + org + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement, String org, String id, String mrn,
                                    String createdBy) throws Exception {
        bind(statement, org, createdBy);
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + "dob, created_by) "
                + "SELECT '" + org + "', '" + id + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient " + mrn + "', 'synthetic patient " + mrn + "', '1990-05-20', u.id "
                + "FROM public.user_identity u WHERE u.organization_id = '" + org + "' "
                + "AND u.subject = '" + createdBy + "'");
    }

    private static void seedAccession(Statement statement, String org, String id, String number,
                                      String patient, String actor, boolean collected, int minutesAgo)
            throws Exception {
        bind(statement, org, actor);
        String created = "now() - interval '" + minutesAgo + " minutes'";
        if (collected) {
            statement.execute("INSERT INTO public.lab_accession "
                    + "(organization_id, id, accession_no, accession_issuer, patient_id, source_order_ref, "
                    + "state, specimen_type, site_code, collector, collected_at, created_at) "
                    + "VALUES ('" + org + "', '" + id + "', '" + number + "', 'LAB-A', '" + patient
                    + "', 'ORD-SYN', 'collected', 'PLASMA', 'SITE-A', 'Sam Collector', "
                    + created + ", " + created + ")");
        } else {
            statement.execute("INSERT INTO public.lab_accession "
                    + "(organization_id, id, accession_no, accession_issuer, patient_id, source_order_ref, "
                    + "state, created_at) "
                    + "VALUES ('" + org + "', '" + id + "', '" + number + "', 'LAB-A', '" + patient
                    + "', 'ORD-SYN', 'accessioned', " + created + ")");
        }
    }

    private ResponseEntity<String> list(String cursor, String limit, String state,
                                        String subject, String organization) {
        List<String> query = new ArrayList<>();
        if (cursor != null) {
            query.add("cursor=" + cursor);
        }
        if (limit != null) {
            query.add("limit=" + limit);
        }
        if (state != null) {
            query.add("state=" + state);
        }
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(HttpMethod.GET)
                .uri("/api/v1/lab/accessions" + (query.isEmpty() ? "" : "?" + String.join("&", query)))
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .onStatus(statusCode -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static String nextCursor(ResponseEntity<String> response) {
        try {
            JsonNode node = MAPPER.readTree(response.getBody()).get("nextCursor");
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response: " + response.getBody(), exception);
        }
    }

    private static List<JsonNode> items(ResponseEntity<String> response) {
        try {
            List<JsonNode> result = new ArrayList<>();
            for (JsonNode node : MAPPER.readTree(response.getBody()).get("items")) {
                result.add(node);
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response: " + response.getBody(), exception);
        }
    }

    private static List<String> ids(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("accession").get("id").asText()).toList();
    }

    @Test
    void organizationBNeverSeesOrganizationARows() {
        ResponseEntity<String> response = list(null, null, null, "lab-b", ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        List<String> found = ids(items(response));
        assertEquals(ACCESSIONS_B, found);
        found.forEach(id -> assertFalse(ACCESSIONS_A.contains(id), "cross-tenant row leaked: " + id));
        assertNull(nextCursor(response));
    }

    @Test
    void pagingReturnsEveryOrganizationRowExactlyOnceInOrder() {
        List<JsonNode> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<String> response = list(cursor, "2", null, "lab-a", ORG_A);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            collected.addAll(items(response));
            cursor = nextCursor(response);
            pages++;
            assertTrue(pages <= 10, "pagination must terminate");
        } while (cursor != null);

        List<String> found = ids(collected);
        assertEquals(ACCESSIONS_A, found);
        assertEquals(found.size(), found.stream().distinct().count());
        found.forEach(id -> assertFalse(ACCESSIONS_B.contains(id), "cross-tenant row leaked: " + id));
        for (int index = 1; index < collected.size(); index++) {
            String previous = collected.get(index - 1).get("accession").get("id").asText();
            String current = collected.get(index).get("accession").get("id").asText();
            assertTrue(previous.compareTo(current) != 0, "rows must be distinct");
        }
    }

    @Test
    void stateFilterSelectsOnlyMatchingAccessions() {
        ResponseEntity<String> collected = list(null, null, "collected", "lab-a", ORG_A);
        assertEquals(HttpStatus.OK, collected.getStatusCode(), collected.getBody());
        assertEquals(List.of(ACCESSION_A3), ids(items(collected)));

        ResponseEntity<String> accessioned = list(null, null, "accessioned", "lab-a", ORG_A);
        assertEquals(HttpStatus.OK, accessioned.getStatusCode(), accessioned.getBody());
        assertEquals(List.of(ACCESSION_A1, ACCESSION_A2), ids(items(accessioned)));
    }

    @Test
    void invalidPagingInputsAreRejected() {
        assertEquals(422, list(null, "0", null, "lab-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, "201", null, "lab-a", ORG_A).getStatusCode().value());
        assertEquals(422, list("not-a-cursor", null, null, "lab-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, null, "bogus", "lab-a", ORG_A).getStatusCode().value());
    }

    @Test
    void unauthorizedAndForbiddenActorsAreDenied() {
        assertEquals(HttpStatus.UNAUTHORIZED, list(null, null, null, null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                list(null, null, null, "reception-a", ORG_A).getStatusCode());
    }
}
