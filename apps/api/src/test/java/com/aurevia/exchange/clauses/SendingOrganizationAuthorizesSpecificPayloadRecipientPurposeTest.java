package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * XCH-01 clause 2 (API test): a sending organization may disclose an order only
 * when its payload, recipient, purpose and duration match an approved authorization.
 *
 * <p>The entry point is {@code POST /api/v1/orders}. Every assertion is made over
 * HTTP against a real PostgreSQL with the V19 row policies in force, so an
 * authorization owned by another tenant is concealed by the same RLS boundary as
 * in production. Each negative case uses its own grant, which keeps a refusal
 * attributable to exactly one term of the authorization check. Only synthetic
 * organizations A, B and C are used; no real patient data or credentials appear.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_sender_authz_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class SendingOrganizationAuthorizesSpecificPayloadRecipientPurposeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_sender_authz_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String GRANT = "55555555-5555-5555-5555-555555555555";
    private static final String EXPIRED_GRANT = "55555555-5555-5555-5555-555555555556";
    private static final String INACTIVE_GRANT = "55555555-5555-5555-5555-555555555557";
    private static final String SHORT_WINDOW_GRANT = "55555555-5555-5555-5555-555555555558";
    private static final String FUTURE_GRANT = "55555555-5555-5555-5555-555555555559";
    private static final String FOREIGN_GRANT = "55555555-5555-5555-5555-555555555560";

    // V19 requires expires_at > effective_from, so an expired grant needs an
    // earlier effective instant than its expiry, not the same one.
    private static final String EFFECTIVE_TWO_DAYS_AGO = "now() - interval '2 days'";
    private static final String EFFECTIVE_YESTERDAY = "now() - interval '1 day'";
    private static final String EXPIRED_YESTERDAY = "now() - interval '1 day'";
    private static final String EXPIRES_IN_TEN_MINUTES = "now() + interval '10 minutes'";
    private static final String EFFECTIVE_TOMORROW = "now() + interval '1 day'";
    private static final String EXPIRES_IN_TWO_DAYS = "now() + interval '2 days'";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Bind the HTTP client to the randomly assigned application port.
     *
     * @param port port chosen by Spring Boot for this test context
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Rebuild the synthetic database, apply the real migrations and seed the
     * organizations, members and authorizations used by every test in this class.
     *
     * @throws Exception if database setup or migration fails
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
            statement.execute("DROP DATABASE IF EXISTS orevia_sender_authz_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_sender_authz_it OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C}) {
                seedOrganization(statement, org);
            }
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_C, "clinician-c", "clinician");

            // The matching grant covers CBC to ORG_B for "lab review", up to 60 minutes.
            seedGrant(statement, ORG_A, GRANT, "lab review", 60,
                    EFFECTIVE_YESTERDAY, EXPIRES_IN_TWO_DAYS, true, "admin-a");
            // Each refusal has its own grant so the denial cannot be explained by a
            // different failing term of the same authorization check.
            seedGrant(statement, ORG_A, EXPIRED_GRANT, "lab review", 60,
                    EFFECTIVE_TWO_DAYS_AGO, EXPIRED_YESTERDAY, true, "admin-a");
            seedGrant(statement, ORG_A, INACTIVE_GRANT, "lab review", 60,
                    EFFECTIVE_YESTERDAY, EXPIRES_IN_TWO_DAYS, false, "admin-a");
            seedGrant(statement, ORG_A, SHORT_WINDOW_GRANT, "lab review", 60,
                    EFFECTIVE_YESTERDAY, EXPIRES_IN_TEN_MINUTES, true, "admin-a");
            seedGrant(statement, ORG_A, FUTURE_GRANT, "lab review", 60,
                    EFFECTIVE_TOMORROW, EXPIRES_IN_TWO_DAYS, true, "admin-a");
            // Owned by ORG_C: the sender's RLS policy makes it invisible to ORG_A.
            seedGrant(statement, ORG_C, FOREIGN_GRANT, "lab review", 60,
                    EFFECTIVE_YESTERDAY, EXPIRES_IN_TWO_DAYS, true, "clinician-c");
        }
    }

    /**
     * Drop the dedicated database after the class so parallel workers cannot see
     * leftover rows.
     *
     * @throws Exception if teardown fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_sender_authz_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
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

    /**
     * Insert one approved authorization with explicit validity bounds.
     *
     * <p>The V19 created-by trigger resolves the actor inside the grant's own
     * tenant, so a foreign grant must be seeded with an actor of that tenant.</p>
     *
     * @param statement     open statement on the synthetic database
     * @param org           owning organization
     * @param id            authorization identifier
     * @param purpose       approved purpose
     * @param maxMinutes    maximum permitted validity of one release
     * @param effectiveFrom SQL instant from which the grant applies
     * @param expiresAt     SQL instant after which the grant no longer applies
     * @param active        whether the grant is still in force
     * @param actor         subject of a user in the owning organization
     * @throws Exception if the insert fails
     */
    private static void seedGrant(Statement statement, String org, String id, String purpose,
                                  int maxMinutes, String effectiveFrom, String expiresAt,
                                  boolean active, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + org + "', '" + id + "', 'CBC', '"
                + ORG_B + "', '" + purpose + "', " + maxMinutes + ", " + effectiveFrom + ", "
                + expiresAt + ", " + active + ")");
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /**
     * Read one scalar in the sender's tenant under V19 row policies.
     *
     * @param sql query whose first column is read
     * @return the first column as a long
     * @throws Exception if the query fails
     */
    private static long scalarLong(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read scalar", exception);
        }
    }

    private static long orderCount() throws Exception {
        return scalarLong("SELECT count(*) FROM public.exchange_order");
    }

    private static long disclosureCount() throws Exception {
        return scalarLong("SELECT count(*) FROM public.exchange_disclosure");
    }

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

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        HttpHeaders headers = headers(subject, organization);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer, exception);
        }
    }

    private static String orderBody(String patient, String code, String destination, String purpose,
                                    String grant, int duration) {
        return "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"ENC-SYN-1\","
                + "\"orderCode\":\"" + code + "\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"Synthetic CBC question\",\"destinationOrg\":\""
                + destination + "\",\"purpose\":\"" + purpose + "\",\"authorizationId\":\""
                + grant + "\",\"responsibleClinician\":\"dr-a\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":" + duration + "}";
    }

    private ResponseEntity<String> createOrder(String code, String destination, String purpose,
                                               String grant, int duration, String subject, String org) {
        return send(HttpMethod.POST, "/api/v1/orders",
                orderBody(patientId(org), code, destination, purpose, grant, duration), subject, org);
    }

    private static void assertRefused(ResponseEntity<String> response, String expectedCode) {
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertEquals(expectedCode, text(response, "/code"));
    }

    private static void assertNothingDisclosed(long ordersBefore, long disclosuresBefore) throws Exception {
        assertEquals(ordersBefore, orderCount(), "a refused release must not write an order");
        assertEquals(disclosuresBefore, disclosureCount(), "a refused release must not write a disclosure");
    }

    @Test
    void matchingPayloadRecipientPurposeAndDurationArePermitted() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> created = createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A);

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("CBC", text(created, "/orderCode"));
        assertEquals(ORG_B, text(created, "/destinationOrg"));
        assertEquals(GRANT, text(created, "/authorizationId"));
        assertEquals(orders + 1, orderCount(), "a permitted release writes exactly one order");
        assertEquals(disclosures + 1, disclosureCount(), "a permitted release records one disclosure");
        assertEquals(1, scalarLong("SELECT count(*) FROM public.exchange_disclosure "
                + "WHERE recipient_org = '" + ORG_B + "' AND purpose = 'lab review' AND duration_minutes = 30"));
    }

    @Test
    void orderCodeOutsideTheGrantIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CMP", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void recipientOutsideTheGrantIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CBC", ORG_C, "lab review", GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void unapprovedPurposeIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CBC", ORG_B, "marketing", GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void durationAboveTheGrantedMaximumIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", GRANT, 61,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void disclosureOutlivingTheGrantWindowIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        // The grant is live but expires in ten minutes; a thirty-minute disclosure
        // would keep sharing content after the sender's permission has lapsed.
        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", SHORT_WINDOW_GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void expiredGrantIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", EXPIRED_GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void inactiveGrantIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", INACTIVE_GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void grantNotYetEffectiveIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", FUTURE_GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void grantOwnedByAnotherTenantIsRefused() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        // The grant exists in ORG_C. Read as ORG_A under V19 RLS it is invisible,
        // so the authorization lookup yields no grant at all.
        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", FOREIGN_GRANT, 30,
                "clinician-a", ORG_A);

        assertRefused(response, "DISCLOSURE_NOT_AUTHORIZED");
        assertNothingDisclosed(orders, disclosures);
    }

    @Test
    void clinicianRoleIsRequiredToDisclose() throws Exception {
        long orders = orderCount();
        long disclosures = disclosureCount();

        // Active membership but no exchange ordering permission: denied by deny(),
        // with a different code from the grant check so the two boundaries stay distinct.
        ResponseEntity<String> response = createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "reception-a", ORG_A);

        assertRefused(response, "FORBIDDEN");
        assertNothingDisclosed(orders, disclosures);
    }
}
