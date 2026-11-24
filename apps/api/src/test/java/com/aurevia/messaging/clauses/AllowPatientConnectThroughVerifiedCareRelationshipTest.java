package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.messaging.units.JdbcMessagingStore;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-01 clause 2 API evidence: a verified patient connects to one care unit
 * through each approved relationship basis, and every ineligible or foreign
 * connection is refused.
 *
 * <p>The command drives the real {@code POST /api/v1/patient-unit-connections}
 * route against the production {@link JdbcMessagingStore} and a dedicated
 * Flyway-migrated PostgreSQL database. There is no in-memory store override, so
 * forced row-level security and the tenant fold functions are part of the proof.
 * Every identifier is synthetic; single-use consumption and the absence of a
 * cross-tenant row are asserted with a direct, tenant-bound SQL read because no
 * HTTP surface exposes raw relationship or connection rows.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_care_unit_connect_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class AllowPatientConnectThroughVerifiedCareRelationshipTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    private static final String OTHER_PATIENT = "cccccccc-0000-0000-0000-000000000002";
    private static final String UNLINKED_PATIENT = "cccccccc-0000-0000-0000-000000000003";
    private static final String SUBJECT = "patient-a";
    private static final String OTHER_SUBJECT = "patient-b";
    private static final String STAFF_SUBJECT = "staff-a";
    private static final String UNVERIFIED_SUBJECT = "unverified-a";
    private static final String DB = "aurevia_care_unit_connect_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    @Autowired
    private MessagingStore messagingStore;

    private RestClient rest;

    /** Create an isolated database, migrate it and seed the synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        seedOwnerTenant();
        seedForeignTenant();
    }

    /** Drop the dedicated database so repeated runs never share state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Bind the real HTTP client to the random port of the running application. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedOwnerTenant() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            setTenant(statement, ORG, SUBJECT);
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                    + ORG + "', 'Synthetic care unit tenant')");
            seedMember(statement, ORG, SUBJECT, "patient");
            seedMember(statement, ORG, STAFF_SUBJECT, "lab_verifier");
            seedMember(statement, ORG, UNVERIFIED_SUBJECT, "patient");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG + "', '" + PATIENT + "', 1, 'MRN-CARE-1', 'CLINIC', "
                    + "'Synthetic Care Patient', 'synthetic care patient', DATE '1990-05-20')");
            // A second chart that exists but is not linked to this account: a
            // connection must not be granted through an unlinked chart.
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG + "', '" + UNLINKED_PATIENT + "', 1, 'MRN-CARE-2', 'CLINIC', "
                    + "'Synthetic Unlinked Patient', 'synthetic unlinked patient', DATE '1991-06-21')");
            statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG + "', '" + SUBJECT + "', '" + SUBJECT + "')");
            statement.execute("INSERT INTO public.account_chart_link "
                    + "(organization_id, account_id, chart_id, event, method) "
                    + "VALUES ('" + ORG + "', (SELECT id FROM public.patient_account WHERE organization_id = '"
                    + ORG + "' AND subject = '" + SUBJECT + "'), '" + PATIENT + "', 'linked', 'synthetic')");
            statement.execute("INSERT INTO public.care_unit "
                    + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                    + "VALUES ('" + ORG + "', '" + UNIT + "', '" + PATIENT + "', 'Synthetic Care Unit', "
                    + "ARRAY['messages', 'follow-up'], true, 'clinic')");
            for (ConnectionContext kind : ConnectionContext.values()) {
                seedRelationship(statement, relationshipId(kind), reference(kind), kind.wire(),
                        true, false, null);
            }
            seedRelationship(statement, relationshipId("replay"), reference("replay"), "order",
                    true, false, null);
            seedRelationship(statement, relationshipId("conflict-revoked"), reference("conflict-revoked"),
                    "order", true, false, null);
            seedRelationship(statement, relationshipId("inactive"), reference("inactive"), "order",
                    false, false, null);
            seedRelationship(statement, relationshipId("consumed"), reference("consumed"), "order",
                    true, true, null);
            seedRelationship(statement, relationshipId("expired"), reference("expired"), "order",
                    true, false, "2020-01-01T00:00:00Z");
            seedRelationship(statement, relationshipId("crosstenant"), reference("crosstenant"), "order",
                    true, false, null);
            seedRelationship(statement, relationshipId("wrongrole"), reference("wrongrole"), "order",
                    true, false, null);
            seedRelationship(statement, relationshipId("unverified"), reference("unverified"), "order",
                    true, false, null);
            seedRelationship(statement, relationshipId("otherchart"), reference("otherchart"), "order",
                    true, false, null);
            // Prior connections whose shape the POST endpoint itself can never
            // create: a different context, and an already revoked state.
            seedConnection(statement, "conflict-context", ConnectionContext.ORDER, ConnectionState.ACTIVE);
            seedConnection(statement, "conflict-revoked", ConnectionContext.ORDER, ConnectionState.REVOKED);
        }
    }

    private static void seedForeignTenant() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            setTenant(statement, OTHER_ORG, OTHER_SUBJECT);
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                    + OTHER_ORG + "', 'Synthetic foreign tenant')");
            seedMember(statement, OTHER_ORG, OTHER_SUBJECT, "patient");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + OTHER_ORG + "', '" + OTHER_PATIENT + "', 1, 'MRN-OTHER', 'CLINIC', "
                    + "'Synthetic Foreign Patient', 'synthetic foreign patient', DATE '1992-07-22')");
            statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                    + "VALUES ('" + OTHER_ORG + "', '" + OTHER_SUBJECT + "', '" + OTHER_SUBJECT + "')");
            statement.execute("INSERT INTO public.account_chart_link "
                    + "(organization_id, account_id, chart_id, event, method) "
                    + "VALUES ('" + OTHER_ORG + "', (SELECT id FROM public.patient_account WHERE organization_id = '"
                    + OTHER_ORG + "' AND subject = '" + OTHER_SUBJECT + "'), '"
                    + OTHER_PATIENT + "', 'linked', 'synthetic')");
        }
    }

    private static void setTenant(Statement statement, String org, String subject) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedRelationship(Statement statement, String relationshipId, String reference,
                                         String context, boolean active, boolean consumed, String expiresAt)
            throws Exception {
        setTenant(statement, ORG, SUBJECT);
        statement.execute("INSERT INTO public.eligible_relationship "
                + "(organization_id, id, patient_id, unit_id, context, reference, active, consumed, expires_at) "
                + "VALUES ('" + ORG + "', '" + relationshipId + "', '" + PATIENT + "', '" + UNIT + "', '"
                + context + "', '" + reference + "', " + active + ", " + consumed + ", "
                + (expiresAt == null ? "NULL" : "CAST('" + expiresAt + "' AS timestamptz)") + ")");
    }

    private static void seedConnection(Statement statement, String seed, ConnectionContext context,
                                       ConnectionState state) throws Exception {
        setTenant(statement, ORG, SUBJECT);
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, id, patient_id, unit_id, context, context_reference, "
                + "relationship_id, state, verified_by, verified_at, created_at, revoked_at) "
                + "VALUES ('" + ORG + "', '" + id("prior-connection-" + seed) + "', '" + PATIENT + "', '"
                + UNIT + "', '" + context.wire() + "', '" + reference(seed) + "', '"
                + relationshipId(seed) + "', '" + state.wire() + "', '" + SUBJECT
                + "', CAST('" + NOW + "' AS timestamptz), CAST('" + NOW + "' AS timestamptz), "
                + (state == ConnectionState.REVOKED ? "CAST('" + NOW + "' AS timestamptz)" : "NULL") + ")");
    }

    private static String id(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String relationshipId(ConnectionContext kind) {
        return relationshipId(kind.wire());
    }

    private static String relationshipId(String seed) {
        return id("relationship:" + seed);
    }

    private static String reference(ConnectionContext kind) {
        return reference(kind.wire());
    }

    private static String reference(String seed) {
        return "ref-" + seed;
    }

    /** Post one connect command with the synthetic identity headers. */
    private ResponseEntity<String> post(String subject, String org, String body) {
        return rest.post()
                .uri("/api/v1/patient-unit-connections")
                .headers(headers -> {
                    headers.set("X-Synthetic-Subject", subject);
                    headers.set("X-Active-Organization", org);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String connectBody(String patientId, ConnectionContext kind, String reference) {
        return "{\"patientId\":\"" + patientId + "\",\"unitId\":\"" + UNIT
                + "\",\"context\":\"" + kind.wire()
                + "\",\"contextReference\":\"" + reference + "\"}";
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse the connect response", exception);
        }
    }

    /** Count the connections a reference has produced in one tenant, RLS-bound. */
    private static int connectionCount(String org, String reference) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            setTenant(statement, org, SUBJECT);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.patient_unit_connection "
                            + "WHERE patient_id = '" + PATIENT + "' AND unit_id = '" + UNIT
                            + "' AND context_reference = '" + reference + "'")) {
                return rows.next() ? rows.getInt(1) : -1;
            }
        }
    }

    /** Read the single-use flag of a relationship inside its owning tenant. */
    private static boolean isConsumed(String relationshipId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            setTenant(statement, ORG, SUBJECT);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT consumed FROM public.eligible_relationship WHERE id = '" + relationshipId + "'")) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    @Test
    void productionMessagingStoreIsTheJdbcAdapter() {
        assertTrue(messagingStore instanceof JdbcMessagingStore,
                "the HTTP route must use the real PostgreSQL store, not an in-memory fake");
    }

    @ParameterizedTest(name = "{0} is an approved verified relationship basis")
    @EnumSource(ConnectionContext.class)
    void connectsPatientThroughEachVerifiedRelationshipContext(ConnectionContext kind) throws Exception {
        String reference = reference(kind);
        ResponseEntity<String> response = post(SUBJECT, ORG, connectBody(PATIENT, kind, reference));

        assertEquals(201, response.getStatusCode().value(), response.getBody());
        JsonNode connection = tree(response);
        assertEquals(ORG, connection.at("/organizationId").asText());
        assertEquals(PATIENT, connection.at("/patientId").asText());
        assertEquals(UNIT, connection.at("/unitId").asText());
        assertEquals(kind.wire(), connection.at("/context").asText());
        assertEquals(reference, connection.at("/contextReference").asText());
        assertEquals(relationshipId(kind), connection.at("/relationshipId").asText());
        assertEquals("active", connection.at("/state").asText());
        assertEquals(SUBJECT, connection.at("/verifiedBy").asText());
        String connectionId = connection.at("/id").asText();
        assertNotEquals("", connectionId);
        assertNotEquals("", connection.at("/verifiedAt").asText());
        assertNotEquals("", connection.at("/createdAt").asText());
        assertTrue(connection.at("/revokedAt").isNull() || connection.at("/revokedAt").isMissingNode());
        assertTrue(response.getHeaders().getLocation().toString().endsWith("/" + connectionId));
        assertEquals(1, connectionCount(ORG, reference));

        if (kind == ConnectionContext.INVITATION) {
            assertTrue(isConsumed(relationshipId(kind)),
                    "a unit-issued invitation is single use and must be consumed on the first connect");
        }
    }

    @Test
    void replayOfTheSameVerifiedReferenceReturnsTheOriginalConnection() throws Exception {
        String reference = reference("replay");
        String body = connectBody(PATIENT, ConnectionContext.ORDER, reference);
        ResponseEntity<String> first = post(SUBJECT, ORG, body);
        ResponseEntity<String> second = post(SUBJECT, ORG, body);

        assertEquals(201, first.getStatusCode().value(), first.getBody());
        assertEquals(201, second.getStatusCode().value(), second.getBody());
        assertEquals(tree(first).at("/id").asText(), tree(second).at("/id").asText());
        assertEquals(1, connectionCount(ORG, reference),
                "a retry must not open a parallel conversation for the same verified reference");
    }

    @Test
    void connectWithAConflictingOrInactiveReferenceIsRefused() {
        // The prior connection is an order; this retry asks for a referral with
        // the same reference, so the existing context must win the conflict.
        ResponseEntity<String> differentContext = post(SUBJECT, ORG,
                connectBody(PATIENT, ConnectionContext.REFERRAL, reference("conflict-context")));
        assertEquals(409, differentContext.getStatusCode().value(), differentContext.getBody());
        assertEquals("CONFLICT", tree(differentContext).at("/code").asText());

        // "Inactive" is the prior connection's revoked state, not an unusable
        // relationship: that separate case is asserted as 422 below.
        ResponseEntity<String> revokedPrior = post(SUBJECT, ORG,
                connectBody(PATIENT, ConnectionContext.ORDER, reference("conflict-revoked")));
        assertEquals(409, revokedPrior.getStatusCode().value(), revokedPrior.getBody());
        assertEquals("CONFLICT", tree(revokedPrior).at("/code").asText());
    }

    @ParameterizedTest(name = "unusable relationship fixture {0} is refused")
    @ValueSource(strings = {"inactive", "consumed", "expired"})
    void connectWithAnUnusableRelationshipIsRefused(String fixture) {
        ResponseEntity<String> response = post(SUBJECT, ORG,
                connectBody(PATIENT, ConnectionContext.ORDER, reference(fixture)));
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", tree(response).at("/code").asText());
    }

    @Test
    void connectWithoutAnEligibleVerifiedRelationshipIsRefused() {
        ResponseEntity<String> response = post(SUBJECT, ORG,
                connectBody(PATIENT, ConnectionContext.ORDER, "ref-not-verified"));
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertEquals("NO_ELIGIBLE_RELATIONSHIP", tree(response).at("/code").asText());
    }

    @Test
    void connectForAnotherTenantsOrganizationIsRefused() throws Exception {
        String reference = reference("crosstenant");
        ResponseEntity<String> response = post(OTHER_SUBJECT, OTHER_ORG,
                connectBody(PATIENT, ConnectionContext.ORDER, reference));

        assertEquals(404, response.getStatusCode().value(), response.getBody());
        assertEquals("NOT_FOUND", tree(response).at("/code").asText());
        assertEquals(0, connectionCount(ORG, reference),
                "a foreign tenant request must not write into the owning tenant");
        assertEquals(0, connectionCount(OTHER_ORG, reference),
                "a foreign tenant request must not write a cross-tenant row");
    }

    @Test
    void connectWithAWrongRoleIsRefused() {
        ResponseEntity<String> response = post(STAFF_SUBJECT, ORG,
                connectBody(PATIENT, ConnectionContext.ORDER, reference("wrongrole")));
        assertEquals(403, response.getStatusCode().value(), response.getBody());
        assertEquals("FORBIDDEN", tree(response).at("/code").asText());
    }

    @Test
    void connectForAnUnverifiedChartIsRefused() {
        ResponseEntity<String> response = post(UNVERIFIED_SUBJECT, ORG,
                connectBody(PATIENT, ConnectionContext.ORDER, reference("unverified")));
        assertEquals(403, response.getStatusCode().value(), response.getBody());
        assertEquals("NO_VERIFIED_CHART", tree(response).at("/code").asText());
    }

    @Test
    void connectForAnotherVerifiedChartIsRefused() {
        ResponseEntity<String> response = post(SUBJECT, ORG,
                connectBody(UNLINKED_PATIENT, ConnectionContext.ORDER, reference("otherchart")));
        assertEquals(403, response.getStatusCode().value(), response.getBody());
        assertEquals("FORBIDDEN", tree(response).at("/code").asText());
    }
}
