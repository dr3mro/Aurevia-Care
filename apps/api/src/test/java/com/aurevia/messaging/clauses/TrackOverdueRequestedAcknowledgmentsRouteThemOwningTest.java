package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MSG-05 clause 3 proof through the normal HTTP entry point: an overdue
 * requested acknowledgment is routed to the owning unit's follow-up queue, and
 * the rule refuses input, roles, tenants and states it must not act on.
 *
 * <p>The suite recreates its own PostgreSQL database from the authoritative
 * Flyway migrations, seeds two isolated tenants with synthetic identities and
 * drives the real controller, service and JDBC store through HTTP under the
 * {@code synthetic-auth} profile. No production code, contract or migration is
 * changed. Every identifier, chart and subject is synthetic; no patient data or
 * credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_overdue_followups_it")
@ActiveProfiles("synthetic-auth")
class TrackOverdueRequestedAcknowledgmentsRouteThemOwningTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_overdue_followups_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Instant VERIFIED_AT = Instant.parse("2026-11-21T12:00:00Z");

    // A fixed instant in the distant past is reliably overdue against the
    // system-UTC Clock bean, without a timing-sensitive test.
    static final String DUE_PAST = "2020-01-01T00:00:00Z";

    static final String UNIT_ROUTE = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String UNIT_NOT_REQUESTED = "aaaaaaaa-0000-0000-0000-000000000002";
    static final String UNIT_OWN = "aaaaaaaa-0000-0000-0000-000000000003";
    static final String UNIT_OTHER = "aaaaaaaa-0000-0000-0000-000000000004";
    static final String UNIT_SHARED = "aaaaaaaa-0000-0000-0000-000000000005";
    static final String UNIT_UNASSIGNED = "aaaaaaaa-0000-0000-0000-000000000006";
    static final String UNIT_HIDE = "aaaaaaaa-0000-0000-0000-000000000007";
    static final String UNIT_PEER_ONLY = "bbbbbbbb-0000-0000-0000-000000000001";

    static final String PATIENT = "cccccccc-0000-0000-0000-000000000001";
    static final String PEER_PATIENT = "cccccccc-0000-0000-0000-000000000002";
    static final String ACCOUNT_A = "dddddddd-0000-0000-0000-000000000001";
    static final String ACCOUNT_B = "dddddddd-0000-0000-0000-000000000002";

    static final String STAFF = "overdue-clinician";
    static final String STAFF_OTHER = "overdue-other-clinician";
    static final String PEER_STAFF = "overdue-peer-clinician";
    static final String WRONG_ROLE = "overdue-reception";
    static final String PATIENT_SUBJECT = "overdue-patient";
    static final String PEER_PATIENT_SUBJECT = "overdue-peer-patient";

    static final String INSTRUCTION_ROUTE = "eeeeeeee-0000-0000-0000-000000000010";
    static final String INSTRUCTION_NOT_REQUESTED = "eeeeeeee-0000-0000-0000-000000000011";
    static final String INSTRUCTION_OWN = "eeeeeeee-0000-0000-0000-000000000012";
    static final String INSTRUCTION_OTHER = "eeeeeeee-0000-0000-0000-000000000013";
    static final String INSTRUCTION_SHARED = "eeeeeeee-0000-0000-0000-000000000001";
    static final String INSTRUCTION_HIDE_ACK = "eeeeeeee-0000-0000-0000-000000000014";
    static final String INSTRUCTION_HIDE_RESOLVE = "eeeeeeee-0000-0000-0000-000000000015";
    static final String INSTRUCTION_HIDE_SUPERSEDE = "eeeeeeee-0000-0000-0000-000000000016";

    RestClient rest;

    /** Point the HTTP client at the random port chosen for this test context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate the synthetic database from the authoritative migrations and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_overdue_followups_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_overdue_followups_it "
                    + "OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, ORG_A, STAFF, "clinician");
            seedMember(statement, ORG_A, STAFF_OTHER, "clinician");
            seedMember(statement, ORG_A, WRONG_ROLE, "reception");
            seedMember(statement, ORG_A, PATIENT_SUBJECT, "patient");
            seedPatient(statement, ORG_A, PATIENT, ACCOUNT_A, PATIENT_SUBJECT, STAFF);
            seedMember(statement, ORG_B, PEER_STAFF, "clinician");
            seedMember(statement, ORG_B, PEER_PATIENT_SUBJECT, "patient");
            seedPatient(statement, ORG_B, PEER_PATIENT, ACCOUNT_B, PEER_PATIENT_SUBJECT, PEER_STAFF);

            // The staff actor owns the units whose queues it may read.
            for (String unit : new String[] { UNIT_ROUTE, UNIT_NOT_REQUESTED, UNIT_OWN,
                    UNIT_SHARED, UNIT_HIDE, UNIT_UNASSIGNED }) {
                seedConnection(statement, ORG_A, PATIENT, unit, STAFF);
                if (!UNIT_UNASSIGNED.equals(unit)) {
                    seedAssignment(statement, ORG_A, STAFF, unit);
                }
            }
            // A second ORG_A clinician owns a different unit; its follow-up must
            // never appear in the first clinician's queue.
            seedConnection(statement, ORG_A, PATIENT, UNIT_OTHER, STAFF_OTHER);
            seedAssignment(statement, ORG_A, STAFF_OTHER, UNIT_OTHER);
            // Identical local unit id in the peer tenant proves RLS isolation.
            seedConnection(statement, ORG_B, PEER_PATIENT, UNIT_SHARED, PEER_STAFF);
            seedAssignment(statement, ORG_B, PEER_STAFF, UNIT_SHARED);
            // A unit that exists only in the peer tenant: ORG_A staff are never
            // assigned to it, so reading it is denied even with a valid id.
            seedConnection(statement, ORG_B, PEER_PATIENT, UNIT_PEER_ONLY, PEER_STAFF);
            seedAssignment(statement, ORG_B, PEER_STAFF, UNIT_PEER_ONLY);
        }
    }

    /** Drop the synthetic database so each run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_overdue_followups_it WITH (FORCE)");
        }
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + org + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE m.organization_id = '" + org + "' AND u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement, String org, String chart, String account,
                                    String patientSubject, String staff) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + staff + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + "dob_unknown_reason, created_by) VALUES ('" + org + "', '" + chart + "', 1, 'SYN-MRN-"
                + (ORG_A.equals(org) ? "A" : "B") + "', 'SYNTHETIC', 'Synthetic Patient', "
                + "'synthetic patient', 'synthetic fixture', "
                + "(SELECT id FROM public.user_identity WHERE organization_id = '" + org
                + "' AND subject = '" + staff + "'))");
        statement.execute("INSERT INTO public.patient_account (organization_id, id, subject, display_name) "
                + "VALUES ('" + org + "', '" + account + "', '" + patientSubject + "', 'patient')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, id, account_id, chart_id, event, method) "
                + "VALUES ('" + org + "', gen_random_uuid(), '" + account + "', '" + chart
                + "', 'linked', 'invitation')");
    }

    private static void seedConnection(Statement statement, String org, String patient, String unit,
                                       String verifiedBy) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + verifiedBy + "'");
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, id, patient_id, unit_id, context, context_reference, "
                + "relationship_id, state, verified_by, verified_at) VALUES ('"
                + org + "', gen_random_uuid(), '" + patient + "', '" + unit
                + "', 'care_relationship', 'synthetic-ref', gen_random_uuid(), 'active', '"
                + verifiedBy + "', '" + VERIFIED_AT + "')");
    }

    private static void seedAssignment(Statement statement, String org, String subject, String unit)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.instruction_fact_unit_assignment "
                + "(organization_id, subject, unit_id) VALUES ('" + org + "', '" + subject
                + "', '" + unit + "')");
    }

    /** Send a synthetic request, capturing any status instead of throwing on an error response. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Parse an HTTP JSON body, failing the test with a clear message on malformed content. */
    private static JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception invalid) {
            throw new AssertionError("response must be valid JSON: " + json, invalid);
        }
    }

    /** Assert a refusal carries both the expected status and its stable error code. */
    private static void assertDenied(ResponseEntity<String> response, HttpStatus status, String code) {
        assertEquals(status, response.getStatusCode(), response.getBody());
        assertEquals(code, parse(response.getBody()).get("code").asString());
    }

    /** Publish one synthetic instruction revision through the HTTP entry point. */
    private void publish(String org, String staff, String instructionId, int revision, String unit,
                         boolean requiresAcknowledgment, String dueAt) {
        String patient = ORG_A.equals(org) ? PATIENT : PEER_PATIENT;
        String body = "{\"instructionId\":\"" + instructionId + "\",\"patientId\":\"" + patient
                + "\",\"unitId\":\"" + unit + "\",\"revision\":" + revision
                + ",\"contentRef\":\"artifact-" + revision + "\",\"requiresAcknowledgment\":"
                + requiresAcknowledgment + ",\"acknowledgmentDueAt\":"
                + (dueAt == null ? "null" : "\"" + dueAt + "\"")
                + ",\"sendNotification\":false,\"clientId\":\"publish-" + instructionId + "-"
                + revision + "\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/instructions/publish",
                body, staff, org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    /** Acknowledge one exact revision as the synthetic patient via HTTP. */
    private void acknowledge(String org, String patientSubject, String instructionId, int revision,
                             String clientId) {
        String body = "{\"instructionId\":\"" + instructionId + "\",\"revision\":" + revision
                + ",\"clientId\":\"" + clientId + "\"}";
        ResponseEntity<String> response = request(HttpMethod.POST, "/api/v1/instructions/acknowledge",
                body, patientSubject, org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    /** Resolve a routed follow-up as an owning-unit staff member via HTTP. */
    private void resolve(String org, String staff, String instructionId, int revision, String reason) {
        String body = "{\"instructionId\":\"" + instructionId + "\",\"revision\":" + revision
                + ",\"reason\":\"" + reason + "\"}";
        ResponseEntity<String> response = request(HttpMethod.POST,
                "/api/v1/instructions/follow-up/resolve", body, staff, org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    }

    /** Read one unit's overdue follow-up queue via HTTP, asserting the read succeeded. */
    private JsonNode followUps(String org, String staff, String unitId) {
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/api/v1/instructions/overdue-follow-ups?unitId=" + unitId, null, staff, org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return parse(response.getBody());
    }

    /** Seed a follow-up row directly for a revision that the detector would never route. */
    private static void seedFollowUpRow(String org, String instructionId, int revision, String unit)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'overdue-setup'");
            statement.execute("INSERT INTO public.instruction_fact_follow_up "
                    + "(organization_id, instruction_id, revision, unit_id, routed_at, state) "
                    + "VALUES ('" + org + "', '" + instructionId + "', " + revision + ", '" + unit
                    + "', '" + DUE_PAST + "', 'routed')");
        }
    }

    /**
     * A requested acknowledgment that is past due becomes one ROUTED follow-up
     * on the owning unit's queue; an explicit receipt removes it from that queue.
     */
    @Test
    void routesAnOverdueRequestedAcknowledgmentToTheOwningUnit() {
        publish(ORG_A, STAFF, INSTRUCTION_ROUTE, 1, UNIT_ROUTE, true, DUE_PAST);

        JsonNode listed = followUps(ORG_A, STAFF, UNIT_ROUTE);
        assertEquals(1, listed.size());
        JsonNode followUp = listed.get(0);
        assertEquals(INSTRUCTION_ROUTE, followUp.get("instructionId").asString());
        assertEquals(1, followUp.get("revision").asInt());
        assertEquals(UNIT_ROUTE, followUp.get("unitId").asString());
        assertEquals("routed", followUp.get("state").asString(),
                "the follow-up is routed to the owning unit, not merely opened");
        assertEquals(1, followUps(ORG_A, STAFF, UNIT_ROUTE).size(),
                "re-reading the queue must not create a second follow-up");

        acknowledge(ORG_A, PATIENT_SUBJECT, INSTRUCTION_ROUTE, 1, "ack-route");
        assertEquals(0, followUps(ORG_A, STAFF, UNIT_ROUTE).size(),
                "an explicit receipt clears the unit's overdue follow-up queue");
    }

    /** An instruction that did not request acknowledgment is never routed, even when overdue. */
    @Test
    void doesNotRouteWhenAcknowledgmentWasNotRequested() throws Exception {
        publish(ORG_A, STAFF, INSTRUCTION_NOT_REQUESTED, 1, UNIT_NOT_REQUESTED, false, DUE_PAST);
        assertEquals(0, followUps(ORG_A, STAFF, UNIT_NOT_REQUESTED).size());

        // The detector never creates this row; the service's own re-check must
        // still hide it so a non-requested revision is never shown as open work.
        seedFollowUpRow(ORG_A, INSTRUCTION_NOT_REQUESTED, 1, UNIT_NOT_REQUESTED);
        assertEquals(0, followUps(ORG_A, STAFF, UNIT_NOT_REQUESTED).size());
    }

    /** A unit queue contains only follow-ups owned by that unit. */
    @Test
    void doesNotListFollowUpForAnotherUnit() {
        publish(ORG_A, STAFF, INSTRUCTION_OWN, 1, UNIT_OWN, true, DUE_PAST);
        publish(ORG_A, STAFF_OTHER, INSTRUCTION_OTHER, 1, UNIT_OTHER, true, DUE_PAST);

        JsonNode mine = followUps(ORG_A, STAFF, UNIT_OWN);
        assertEquals(1, mine.size());
        assertEquals(INSTRUCTION_OWN, mine.get(0).get("instructionId").asString());
        assertEquals(UNIT_OWN, mine.get(0).get("unitId").asString());
        assertFalse(mine.toString().contains(INSTRUCTION_OTHER),
                "another unit's follow-up must not appear in this unit's queue");
        JsonNode theirs = followUps(ORG_A, STAFF_OTHER, UNIT_OTHER);
        assertEquals(1, theirs.size());
        assertEquals(INSTRUCTION_OTHER, theirs.get(0).get("instructionId").asString(),
                "the other unit's queue is non-empty, so the filter is what excluded it");
    }

    /** Identical local ids in two tenants stay isolated under forced row-level security. */
    @Test
    void doesNotExposePeerTenantInstruction() {
        publish(ORG_A, STAFF, INSTRUCTION_SHARED, 1, UNIT_SHARED, true, DUE_PAST);
        publish(ORG_B, PEER_STAFF, INSTRUCTION_SHARED, 2, UNIT_SHARED, true, DUE_PAST);

        JsonNode mine = followUps(ORG_A, STAFF, UNIT_SHARED);
        assertEquals(1, mine.size());
        assertEquals(1, mine.get(0).get("revision").asInt());
        JsonNode peer = followUps(ORG_B, PEER_STAFF, UNIT_SHARED);
        assertEquals(1, peer.size());
        assertEquals(2, peer.get(0).get("revision").asInt());

        // ORG_A staff, active in ORG_A, cannot read a unit owned only by the
        // peer tenant: the assignment guard fails before any peer fact is read.
        assertDenied(request(HttpMethod.GET,
                        "/api/v1/instructions/overdue-follow-ups?unitId=" + UNIT_PEER_ONLY,
                        null, STAFF, ORG_A), HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    /** Neither a role without the follow-up permission nor an unassigned unit may read a queue. */
    @Test
    void refusesFollowUpListToWrongRoleOrUnassignedUnit() {
        assertDenied(request(HttpMethod.GET,
                        "/api/v1/instructions/overdue-follow-ups?unitId=" + UNIT_ROUTE,
                        null, WRONG_ROLE, ORG_A), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertDenied(request(HttpMethod.GET,
                        "/api/v1/instructions/overdue-follow-ups?unitId=" + UNIT_UNASSIGNED,
                        null, STAFF, ORG_A), HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    /** A missing or malformed unit identifier is refused before any tenant access. */
    @Test
    void refusesMissingOrInvalidUnitId() {
        assertDenied(request(HttpMethod.GET,
                        "/api/v1/instructions/overdue-follow-ups?unitId=",
                        null, STAFF, ORG_A), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_UNITID");
        assertDenied(request(HttpMethod.GET,
                        "/api/v1/instructions/overdue-follow-ups?unitId=not-a-uuid",
                        null, STAFF, ORG_A), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_UNITID");
    }

    /** Acknowledged, resolved and superseded follow-ups are hidden from the open queue. */
    @Test
    void hidesFollowUpOnceAcknowledgedResolvedOrSuperseded() {
        publish(ORG_A, STAFF, INSTRUCTION_HIDE_ACK, 1, UNIT_HIDE, true, DUE_PAST);
        assertEquals(1, followUps(ORG_A, STAFF, UNIT_HIDE).size());
        acknowledge(ORG_A, PATIENT_SUBJECT, INSTRUCTION_HIDE_ACK, 1, "ack-hide");
        assertEquals(0, followUps(ORG_A, STAFF, UNIT_HIDE).size());

        publish(ORG_A, STAFF, INSTRUCTION_HIDE_RESOLVE, 1, UNIT_HIDE, true, DUE_PAST);
        assertEquals(1, followUps(ORG_A, STAFF, UNIT_HIDE).size());
        resolve(ORG_A, STAFF, INSTRUCTION_HIDE_RESOLVE, 1, "owner contacted");
        assertEquals(0, followUps(ORG_A, STAFF, UNIT_HIDE).size());

        publish(ORG_A, STAFF, INSTRUCTION_HIDE_SUPERSEDE, 1, UNIT_HIDE, true, DUE_PAST);
        assertEquals(1, followUps(ORG_A, STAFF, UNIT_HIDE).size());
        publish(ORG_A, STAFF, INSTRUCTION_HIDE_SUPERSEDE, 2, UNIT_HIDE, true, DUE_PAST);
        JsonNode afterSupersede = followUps(ORG_A, STAFF, UNIT_HIDE);
        assertEquals(1, afterSupersede.size());
        assertEquals(2, afterSupersede.get(0).get("revision").asInt(),
                "the superseded revision's follow-up is never open work");
    }
}
