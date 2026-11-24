package com.aurevia.it.exchange.order;

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
 * ARC-03 acceptance for the order list against a real PostgreSQL: forced-RLS
 * tenant isolation, stable creation-time paging and the work-item status filter.
 * All organizations, patients and orders are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_order_list_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class OrderListIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String PATIENT_B = "bbbbbbbb-0000-0000-0000-000000000002";
    static final String GRANT_A = "55555555-5555-5555-5555-555555555555";
    static final String GRANT_B = "55555555-5555-5555-5555-555555555556";
    static final String FINGERPRINT = "77777777-7777-7777-7777-777777777777";
    static final String ORDER_A1 = "aaaaaaaa-1111-1111-1111-111111111111";
    static final String ORDER_A2 = "aaaaaaaa-2222-2222-2222-222222222222";
    static final String ORDER_A3 = "aaaaaaaa-3333-3333-3333-333333333333";
    static final String ORDER_B1 = "bbbbbbbb-1111-1111-1111-111111111111";
    static final String ORDER_B2 = "bbbbbbbb-2222-2222-2222-222222222222";
    static final List<String> ORDERS_A = List.of(ORDER_A3, ORDER_A2, ORDER_A1);
    static final List<String> ORDERS_B = List.of(ORDER_B2, ORDER_B1);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_order_list_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_order_list_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_order_list_it OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, "a");
            seedOrganization(statement, ORG_B, "b");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
            seedPatient(statement, ORG_A, PATIENT_A, "MRN-A", "clinician-a");
            seedPatient(statement, ORG_B, PATIENT_B, "MRN-B", "clinician-b");
            seedAuthorization(statement, ORG_A, GRANT_A, ORG_B, "bootstrap-a");
            seedAuthorization(statement, ORG_B, GRANT_B, ORG_A, "bootstrap-b");
            seedOrder(statement, ORG_A, ORDER_A1, PATIENT_A, "MRN-A", GRANT_A, ORG_B,
                    "Synthetic question A1", "clinician-a", 3);
            seedOrder(statement, ORG_A, ORDER_A2, PATIENT_A, "MRN-A", GRANT_A, ORG_B,
                    "Synthetic question A2", "clinician-a", 2);
            seedOrder(statement, ORG_A, ORDER_A3, PATIENT_A, "MRN-A", GRANT_A, ORG_B,
                    "Synthetic question A3", "clinician-a", 1);
            seedOrder(statement, ORG_B, ORDER_B1, PATIENT_B, "MRN-B", GRANT_B, ORG_A,
                    "Synthetic question B1", "clinician-b", 2);
            seedOrder(statement, ORG_B, ORDER_B2, PATIENT_B, "MRN-B", GRANT_B, ORG_A,
                    "Synthetic question B2", "clinician-b", 1);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_order_list_it WITH (FORCE)");
        }
    }

    private static void bind(Statement statement, String org, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
    }

    private static void seedOrganization(Statement statement, String org, String label) throws Exception {
        bind(statement, org, "bootstrap-" + label);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic org " + label + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + label + "', 'Bootstrap " + label + "')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        bind(statement, org, subject);
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

    private static void seedAuthorization(Statement statement, String org, String id,
                                          String destination, String actor) throws Exception {
        bind(statement, org, actor);
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + org + "', '" + id + "', 'CBC', '"
                + destination + "', 'lab review', 60, now() - interval '1 day', "
                + "now() + interval '1 day', true)");
    }

    private static String relatedId(String orderId, char prefix) {
        return prefix + orderId.substring(1);
    }

    private static void seedOrder(Statement statement, String org, String orderId, String patient,
                                  String mrn, String grant, String destination, String question,
                                  String actor, int minutesAgo) throws Exception {
        bind(statement, org, actor);
        String snapshot = relatedId(orderId, 'c');
        String disclosure = relatedId(orderId, 'd');
        String workItem = relatedId(orderId, 'e');
        String created = "now() - interval '" + minutesAgo + " minutes'";
        statement.execute("INSERT INTO public.exchange_order "
                + "(organization_id, id, version, patient_id, encounter_ref, order_code, priority, "
                + "clinical_question, destination_org, responsible_clinician, work_queue, "
                + "authorization_id, snapshot_id, disclosure_id, work_item_id, created_at) VALUES ('"
                + org + "', '" + orderId + "', 1, '" + patient + "', 'ENC-SYN', 'CBC', 'routine', '"
                + question + "', '" + destination + "', 'dr-a', 'lab-queue', '" + grant + "', '"
                + snapshot + "', '" + disclosure + "', '" + workItem + "', " + created + ")");
        statement.execute("INSERT INTO public.exchange_order_snapshot "
                + "(organization_id, id, order_id, version, content, fingerprint) VALUES ('"
                + org + "', '" + snapshot + "', '" + orderId + "', 1, 'synthetic released content', '"
                + FINGERPRINT + "')");
        statement.execute("INSERT INTO public.exchange_disclosure "
                + "(organization_id, id, order_id, recipient_org, purpose, duration_minutes, "
                + "expires_at, fingerprint) VALUES ('" + org + "', '" + disclosure + "', '"
                + orderId + "', '" + destination + "', 'lab review', 30, "
                + "now() + interval '1 day', '" + FINGERPRINT + "')");
        statement.execute("INSERT INTO public.exchange_work_item "
                + "(organization_id, destination_org, authorization_id, id, order_ref, patient_ref, "
                + "patient_mrn, mrn_issuer, priority, clinical_question, work_queue, fingerprint, "
                + "created_at) VALUES ('" + org + "', '" + destination + "', '" + grant + "', '"
                + workItem + "', '" + orderId + "', '" + patient + "', '" + mrn + "', 'CLINIC', "
                + "'routine', '" + question + "', 'lab-queue', '" + FINGERPRINT + "', " + created + ")");
    }

    private ResponseEntity<String> list(String cursor, String limit, String status,
                                        String subject, String organization) {
        List<String> query = new ArrayList<>();
        if (cursor != null) {
            query.add("cursor=" + cursor);
        }
        if (limit != null) {
            query.add("limit=" + limit);
        }
        if (status != null) {
            query.add("status=" + status);
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
                .uri("/api/v1/orders" + (query.isEmpty() ? "" : "?" + String.join("&", query)))
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
        return nodes.stream().map(node -> node.get("id").asText()).toList();
    }

    @Test
    void organizationBNeverSeesOrganizationARows() {
        ResponseEntity<String> response = list(null, null, null, "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        List<String> found = ids(items(response));
        assertEquals(ORDERS_B, found);
        found.forEach(id -> assertFalse(ORDERS_A.contains(id), "cross-tenant row leaked: " + id));
        assertNull(nextCursor(response));
    }

    @Test
    void pagingReturnsEveryOrganizationRowExactlyOnceInOrder() {
        List<JsonNode> collected = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ResponseEntity<String> response = list(cursor, "2", null, "clinician-a", ORG_A);
            assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
            collected.addAll(items(response));
            cursor = nextCursor(response);
            pages++;
            assertTrue(pages <= 10, "pagination must terminate");
        } while (cursor != null);

        List<String> found = ids(collected);
        assertEquals(ORDERS_A, found);
        assertEquals(found.size(), found.stream().distinct().count());
        found.forEach(id -> assertFalse(ORDERS_B.contains(id), "cross-tenant row leaked: " + id));
        for (int index = 1; index < collected.size(); index++) {
            String previous = collected.get(index - 1).get("createdAt").asText()
                    + ":" + collected.get(index - 1).get("id").asText();
            String current = collected.get(index).get("createdAt").asText()
                    + ":" + collected.get(index).get("id").asText();
            assertTrue(previous.compareTo(current) > 0, "paging must be creation-time descending");
        }
    }

    @Test
    void statusFilterSelectsOnlyMatchingOrders() {
        ResponseEntity<String> awaiting = list(null, null, "awaiting_local_patient", "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, awaiting.getStatusCode(), awaiting.getBody());
        assertEquals(ORDERS_A, ids(items(awaiting)));

        ResponseEntity<String> ready = list(null, null, "ready", "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, ready.getStatusCode(), ready.getBody());
        assertTrue(items(ready).isEmpty());
    }

    @Test
    void invalidPagingInputsAreRejected() {
        assertEquals(422, list(null, "0", null, "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, "201", null, "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(422, list("not-a-cursor", null, null, "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(422, list(null, null, "bogus", "clinician-a", ORG_A).getStatusCode().value());
    }

    @Test
    void unauthorizedAndForbiddenActorsAreDenied() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                list(null, null, null, null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                list(null, null, null, "reception-a", ORG_A).getStatusCode());
    }
}
