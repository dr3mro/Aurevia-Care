package com.aurevia.pharmacyflow.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PHARM-01 acceptance (RX-02, XCH-01) over HTTP on a real PostgreSQL instance.
 *
 * <p>Four synthetic organizations share local subject and prescription
 * identifiers so tenant isolation is observable. Two pharmacies inside one
 * organization prove that forced RLS alone is not enough: the explicit routing
 * predicate is required. All fixtures and narratives are synthetic; no patient
 * data or credentials are used. The suite is tagged {@code integration}: the
 * swarm fast gate skips it and the integrator runs it with PostgreSQL.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_pharmacy_inbox_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PharmacyInboxHttpTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_pharmacy_inbox_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String LATEST_X = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String X_TWO = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String X_THREE = "aaaaaaaa-0000-0000-0000-000000000003";
    private static final String X_FOUR = "aaaaaaaa-0000-0000-0000-000000000004";
    private static final String Y_ONE = "aaaaaaaa-0000-0000-0000-000000000005";
    private static final String UNROUTED = "aaaaaaaa-0000-0000-0000-000000000006";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Initialize the REST client against the random local port.
     *
     * @param port assigned server port
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Recreate the database, migrate it and seed two tenants with two pharmacies each.
     *
     * @throws Exception on database setup failure
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_pharmacy_inbox_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_pharmacy_inbox_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
            }
            seedTenantA(statement);
            seedTenantB(statement);
        }
    }

    /**
     * Drop the synthetic database.
     *
     * @throws Exception on database cleanup failure
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_pharmacy_inbox_it WITH (FORCE)");
        }
    }

    private static void bind(Statement statement, String org, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        bind(statement, org, "bootstrap");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap', 'Synthetic Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-" + org.substring(0, 4) + "', 'SYNTHETIC', "
                + "'Synthetic Patient', 'synthetic patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role,
                                   String membershipStatus) throws Exception {
        bind(statement, org, "bootstrap");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + membershipStatus
                + "' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedAssignment(Statement statement, String org, String subject, String pharmacy)
            throws Exception {
        String key = PharmacyInboxRepository.ASSIGNMENT_KEY_PREFIX + subject;
        bind(statement, org, "admin-" + org.substring(0, 4));
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + pharmacy + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic pharmacy assignment' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    private static void seedPrescription(Statement statement, String org, String id, String destination,
                                         String state, String createdAt, String medication) throws Exception {
        bind(statement, org, "clinician-" + org.substring(0, 4));
        statement.execute("INSERT INTO public.prescription_transmission "
                + "(organization_id, id, patient_id, medication, strength, dose, route, frequency, quantity, "
                + " prescriber_subject, destination_pharmacy, state, created_at) "
                + "VALUES ('" + org + "', '" + id + "', '" + PATIENT + "', '" + medication
                + "', '500 mg', '1 capsule', 'oral', 'twice daily', 30, 'clinician-" + org.substring(0, 4)
                + "', " + (destination == null ? "NULL" : "'" + destination + "'") + ", '" + state
                + "', CAST('" + createdAt + "' AS timestamptz))");
    }

    private static String roleOf(String subject) {
        if (subject.startsWith("pharmacy") || subject.startsWith("inactive")
                || subject.startsWith("unassigned")) {
            return "pharmacy_staff";
        }
        if (subject.startsWith("admin")) {
            return "tenant_admin";
        }
        if (subject.startsWith("clinician")) {
            return "clinician";
        }
        return "reception";
    }

    private static void seedTenantA(Statement statement) throws Exception {
        for (String subject : new String[] {"pharmacy-a-1111", "pharmacy-b-1111", "clinician-1111",
                "reception-1111", "admin-1111", "unassigned-1111", "inactive-1111"}) {
            String role = roleOf(subject);
            String status = subject.equals("inactive-1111") ? "revoked" : "active";
            seedMember(statement, ORG_A, subject, role, status);
        }
        seedAssignment(statement, ORG_A, "pharmacy-a-1111", "pharmacy-x");
        seedAssignment(statement, ORG_A, "pharmacy-b-1111", "pharmacy-y");
        seedAssignment(statement, ORG_A, "inactive-1111", "pharmacy-x");
        seedPrescription(statement, ORG_A, LATEST_X, "pharmacy-x", "accepted",
                "2026-11-20T09:05:00Z", "amoxicillin-latest");
        seedPrescription(statement, ORG_A, X_TWO, "pharmacy-x", "delivery_confirmed",
                "2026-11-20T09:04:00Z", "amoxicillin-two");
        seedPrescription(statement, ORG_A, X_THREE, "pharmacy-x", "delivery_confirmed",
                "2026-11-20T09:03:00Z", "amoxicillin-three");
        seedPrescription(statement, ORG_A, X_FOUR, "pharmacy-x", "delivery_confirmed",
                "2026-11-20T09:02:00Z", "amoxicillin-four");
        seedPrescription(statement, ORG_A, Y_ONE, "pharmacy-y", "delivery_confirmed",
                "2026-11-20T09:06:00Z", "medicine-other-pharmacy");
        seedPrescription(statement, ORG_A, UNROUTED, null, "delivery_confirmed",
                "2026-11-20T09:07:00Z", "medicine-unrouted");
    }

    private static void seedTenantB(Statement statement) throws Exception {
        for (String subject : new String[] {"pharmacy-a-2222", "clinician-2222", "admin-2222"}) {
            seedMember(statement, ORG_B, subject, roleOf(subject), "active");
        }
        seedAssignment(statement, ORG_B, "pharmacy-a-2222", "pharmacy-x");
        seedPrescription(statement, ORG_B, LATEST_X, "pharmacy-x", "accepted",
                "2026-11-20T09:05:00Z", "penicillin-other-tenant");
    }

    private ResponseEntity<String> get(String subject, String org, String path) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.set("X-Correlation-Id", "corr-inbox");
        return rest.method(HttpMethod.GET).uri(path)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private JsonNode json(ResponseEntity<String> response) {
        assertNotNull(response.getBody());
        return MAPPER.readTree(response.getBody());
    }

    private List<String> ids(ResponseEntity<String> response) {
        JsonNode items = json(response).at("/items");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            result.add(items.at("/" + index + "/id").asText());
        }
        return result;
    }

    @Test
    void listsOnlyRoutedItemsNewestFirstWithMinimumData() {
        ResponseEntity<String> response = get("pharmacy-a-1111", ORG_A, "/api/v1/pharmacy/inbox");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals(List.of(LATEST_X, X_TWO, X_THREE, X_FOUR), ids(response));
        JsonNode first = json(response).at("/items/0");
        Set<String> keys = new HashSet<>();
        first.properties().forEach(entry -> keys.add(entry.getKey()));
        assertEquals(Set.of("id", "version", "patientId", "medication", "strength", "dose", "route",
                "frequency", "quantity", "state", "submissionId", "prescriberSubject",
                "destinationPharmacy", "createdAt"), keys);
        assertEquals("amoxicillin-latest", first.at("/medication").asText());
        assertEquals("accepted", first.at("/state").asText());
        assertFalse(keys.contains("detail"), "event free text is never part of the inbox projection");
        assertFalse(keys.contains("chart"), "routing grants no chart access");
    }

    @Test
    void statusFilterAndCursorPagingAreStable() {
        ResponseEntity<String> firstPage = get("pharmacy-a-1111", ORG_A,
                "/api/v1/pharmacy/inbox?status=delivery_confirmed&limit=2");
        assertEquals(HttpStatus.OK, firstPage.getStatusCode(), firstPage.getBody());
        assertEquals(List.of(X_TWO, X_THREE), ids(firstPage));
        String cursor = json(firstPage).at("/nextCursor").asText();
        assertFalse(cursor.isBlank());

        ResponseEntity<String> secondPage = get("pharmacy-a-1111", ORG_A,
                "/api/v1/pharmacy/inbox?status=delivery_confirmed&limit=2&cursor=" + cursor);
        assertEquals(HttpStatus.OK, secondPage.getStatusCode(), secondPage.getBody());
        assertEquals(List.of(X_FOUR), ids(secondPage));
        assertTrue(json(secondPage).at("/nextCursor").isNull());

        ResponseEntity<String> wrongStatus = get("pharmacy-a-1111", ORG_A,
                "/api/v1/pharmacy/inbox?status=not-a-state");
        assertEquals(422, wrongStatus.getStatusCode().value());
        assertEquals("INVALID_STATUS", json(wrongStatus).at("/code").asText());
    }

    @Test
    void anotherPharmacyItemIsInvisibleInListAndDetail() {
        ResponseEntity<String> response = get("pharmacy-a-1111", ORG_A, "/api/v1/pharmacy/inbox");
        assertFalse(ids(response).contains(Y_ONE));
        assertEquals(HttpStatus.NOT_FOUND, get("pharmacy-a-1111", ORG_A,
                "/api/v1/pharmacy/inbox/" + Y_ONE).getStatusCode());

        ResponseEntity<String> other = get("pharmacy-b-1111", ORG_A, "/api/v1/pharmacy/inbox");
        assertEquals(List.of(Y_ONE), ids(other));
        assertEquals(HttpStatus.NOT_FOUND, get("pharmacy-b-1111", ORG_A,
                "/api/v1/pharmacy/inbox/" + LATEST_X).getStatusCode());
    }

    @Test
    void unroutedPrescriptionIsInvisible() {
        assertFalse(ids(get("pharmacy-a-1111", ORG_A, "/api/v1/pharmacy/inbox")).contains(UNROUTED));
        ResponseEntity<String> detail = get("pharmacy-a-1111", ORG_A, "/api/v1/pharmacy/inbox/" + UNROUTED);
        assertEquals(HttpStatus.NOT_FOUND, detail.getStatusCode());
        assertFalse(json(detail).toString().contains("medicine-unrouted"));
    }

    @Test
    void detailReadsOnlyRoutedItem() {
        ResponseEntity<String> response = get("pharmacy-a-1111", ORG_A,
                "/api/v1/pharmacy/inbox/" + LATEST_X);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertEquals(LATEST_X, json(response).at("/id").asText());
        assertEquals("pharmacy-x", json(response).at("/destinationPharmacy").asText());
    }

    @Test
    void pharmacyUserCannotReadTheChart() {
        ResponseEntity<String> chart = get("pharmacy-a-1111", ORG_A,
                "/api/v1/chart/records/patients/" + PATIENT + "/entries");
        assertEquals(HttpStatus.FORBIDDEN, chart.getStatusCode(), chart.getBody());
        assertFalse(json(chart).toString().contains("amoxicillin"));
    }

    @Test
    void tenantIsolationHoldsForIdenticalLocalIdsAndForeignCursors() {
        ResponseEntity<String> own = get("pharmacy-a-1111", ORG_A, "/api/v1/pharmacy/inbox/" + LATEST_X);
        assertEquals("amoxicillin-latest", json(own).at("/medication").asText());
        ResponseEntity<String> otherTenant = get("pharmacy-a-2222", ORG_B,
                "/api/v1/pharmacy/inbox/" + LATEST_X);
        assertEquals("penicillin-other-tenant", json(otherTenant).at("/medication").asText());
        assertFalse(ids(get("pharmacy-a-1111", ORG_A, "/api/v1/pharmacy/inbox")).stream()
                .anyMatch(id -> id.equals(Y_ONE)));

        String token = com.aurevia.api.contract.Cursor.encode(
                new com.aurevia.api.contract.Cursor(ORG_B, "2026-11-20T09:05:00Z", LATEST_X));
        ResponseEntity<String> rejected = get("pharmacy-a-1111", ORG_A,
                "/api/v1/pharmacy/inbox?cursor=" + token);
        assertEquals(422, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("INVALID_CURSOR", json(rejected).at("/code").asText());
    }

    @Test
    void authorizationsFailClosed() {
        for (String subject : List.of("clinician-1111", "reception-1111", "admin-1111",
                "unassigned-1111", "inactive-1111")) {
            ResponseEntity<String> denied = get(subject, ORG_A, "/api/v1/pharmacy/inbox");
            assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), subject + " " + denied.getBody());
            assertEquals("FORBIDDEN", json(denied).at("/code").asText());
        }
        assertEquals(HttpStatus.UNAUTHORIZED, get(null, ORG_A, "/api/v1/pharmacy/inbox").getStatusCode());
        assertEquals(422,
                get("pharmacy-a-1111", "not-a-uuid", "/api/v1/pharmacy/inbox").getStatusCode().value());
    }
}
