package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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

/**
 * MSG-05 clause 1 (API evidence): publication, generic notification dispatch,
 * first in-app view and requested receipt acknowledgment are four separate
 * append-only facts bound to one instruction revision.
 *
 * <p>Every request travels through the real HTTP entry point against a fresh
 * database built from the production Flyway migrations and the real
 * {@code JdbcInstructionFactStore} bean, so a gap between the controller, the
 * store and the tenant boundary cannot be hidden by an in-memory fixture. The
 * positive test walks the four facts in order; the negative tests show a
 * wrong-role publication, a cross-tenant read, a second first view, an
 * unrequested acknowledgment and a stale revision being refused.</p>
 *
 * <p>The dispatch fact carries only a generic template key; the opaque content
 * reference of the instruction must never appear in a fact. All subjects,
 * charts and identifiers are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_instruction_facts_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RecordMessagePublicationNotificationDispatchFirstAppTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_RECORD = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_ACCOUNT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String UNIT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String CONNECTION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String STAFF_A = "clause-staff-a";
    private static final String STAFF_B = "clause-staff-b";
    private static final String PATIENT_SUBJECT = "clause-patient";
    private static final String RECEPTION = "clause-reception";
    private static final String CONTENT_REF = "synthetic-content-ref-DO-NOT-LEAK";
    private static final String DUE_AT = "2026-12-31T00:00:00Z";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_instruction_facts_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations
     * and seed two tenants with identical local identifiers.
     *
     * @throws Exception when the database cannot be created or seeded
     */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_instruction_facts_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_instruction_facts_clause_it "
                    + "OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, STAFF_A, "clinician");
            seedTenant(statement, ORG_B, STAFF_B, "clinician");
            // The tenant administrator roles are irrelevant here: the wrong-role
            // subject has an active membership but no fact permission at all.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + STAFF_A + "'");
            insertIdentity(statement, ORG_A, RECEPTION);
            insertMembership(statement, ORG_A, RECEPTION);
            insertRole(statement, ORG_A, RECEPTION, "reception", STAFF_A);
        }
    }

    /**
     * Drop the disposable fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_instruction_facts_clause_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedTenant(Statement statement, String org, String staff, String role)
            throws Exception {
        // FORCE RLS applies to the migration owner too, so every seed statement
        // runs with the tenant and actor set as the HTTP interceptor would.
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + staff + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        insertIdentity(statement, org, staff);
        insertMembership(statement, org, staff);
        insertRole(statement, org, staff, role, staff);

        insertIdentity(statement, org, PATIENT_SUBJECT);
        insertMembership(statement, org, PATIENT_SUBJECT);
        insertRole(statement, org, PATIENT_SUBJECT, "patient", staff);

        // IAM-05 verified chart link: the authoritative current_chart_links fold.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + "dob_unknown_reason, created_by) VALUES ('" + org + "', '" + PATIENT_RECORD
                + "', 1, 'SYN-MRN-" + org.substring(0, 2) + "', 'SYNTHETIC', 'Synthetic Clause Patient', "
                + "'synthetic clause patient', 'synthetic fixture', "
                + "(SELECT id FROM public.user_identity WHERE subject = '" + staff + "'))");
        statement.execute("INSERT INTO public.patient_account (organization_id, id, subject, display_name) "
                + "VALUES ('" + org + "', '" + PATIENT_ACCOUNT + "', '" + PATIENT_SUBJECT + "', 'patient')");
        statement.execute("INSERT INTO public.account_chart_link "
                + "(organization_id, id, account_id, chart_id, event, method) VALUES ('" + org
                + "', gen_random_uuid(), '" + PATIENT_ACCOUNT + "', '" + PATIENT_RECORD
                + "', 'linked', 'invitation')");
        // V60 authoritative patient-unit connection.
        statement.execute("INSERT INTO public.patient_unit_connection "
                + "(organization_id, id, patient_id, unit_id, context, context_reference, "
                + "relationship_id, state, verified_by, verified_at) VALUES ('" + org + "', '"
                + CONNECTION + "', '" + PATIENT_RECORD + "', '" + UNIT
                + "', 'care_relationship', 'synthetic-ref', gen_random_uuid(), 'active', '"
                + staff + "', '" + DUE_AT + "')");
        // No authoritative staff-to-unit assignment exists; V63 seeds the reference.
        statement.execute("INSERT INTO public.instruction_fact_unit_assignment "
                + "(organization_id, subject, unit_id) VALUES ('" + org + "', '" + staff
                + "', '" + UNIT + "')");
    }

    private static void insertIdentity(Statement statement, String org, String subject) throws Exception {
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
    }

    private static void insertMembership(Statement statement, String org, String subject) throws Exception {
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
    }

    private static void insertRole(Statement statement, String org, String subject, String role,
                                   String grantedBy) throws Exception {
        // The membership belongs to the grantee; granted_by is a separate user
        // identity. Selecting both from one joined row would insert no role.
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', "
                + "(SELECT id FROM public.user_identity WHERE subject = '" + grantedBy
                + "' AND organization_id = '" + org + "') "
                + "FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
    }

    /**
     * Send one request through the real MVC pipeline with synthetic identity headers.
     *
     * @param method  HTTP verb
     * @param uri     route
     * @param body    serialized command, or {@code null} for a GET
     * @param subject synthetic authenticated subject
     * @param org     active organization UUID
     * @return the raw response, including 4xx and 5xx bodies
     */
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

    private ResponseEntity<String> publish(String id, int revision, boolean requiresAcknowledgment,
                                           boolean sendNotification, String clientId,
                                           String subject, String org) {
        String body = "{\"instructionId\":\"" + id + "\",\"patientId\":\"" + PATIENT_RECORD
                + "\",\"unitId\":\"" + UNIT + "\",\"revision\":" + revision
                + ",\"contentRef\":\"" + CONTENT_REF + "\""
                + ",\"requiresAcknowledgment\":" + requiresAcknowledgment
                + (requiresAcknowledgment ? ",\"acknowledgmentDueAt\":\"" + DUE_AT + "\"" : "")
                + ",\"sendNotification\":" + sendNotification
                + ",\"clientId\":\"" + clientId + "\"}";
        return request(HttpMethod.POST, "/api/v1/instructions/publish", body, subject, org);
    }

    private ResponseEntity<String> post(String uri, String id, int revision, String clientId,
                                        String subject, String org) {
        String body = "{\"instructionId\":\"" + id + "\",\"revision\":" + revision
                + ",\"clientId\":\"" + clientId + "\"}";
        return request(HttpMethod.POST, uri, body, subject, org);
    }

    private ResponseEntity<String> facts(String id, String subject, String org) {
        return request(HttpMethod.GET, "/api/v1/instructions/" + id + "/facts", null, subject, org);
    }

    private static String field(String json, String key) {
        return fieldAt(json, 0, key);
    }

    private static String fieldAt(String json, int index, String key) {
        String needle = "\"" + key + "\":";
        int start = -1;
        for (int i = 0; i <= index; i++) {
            start = json.indexOf(needle, start + 1);
            if (start < 0) {
                return "";
            }
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end < 0 ? "" : json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /**
     * Read a field of the nth fact object in a timeline response.
     *
     * <p>Fact kinds are distinguished by record components such as
     * {@code genericKey} and {@code scope}, not by a serialized {@code type}
     * field, so the object boundary must be found before the field is read.</p>
     *
     * @param json      serialized timeline
     * @param factIndex zero-based position in the {@code facts} array
     * @param key       record component to read
     * @return the field value, or an empty string when absent
     */
    private static String factField(String json, int factIndex, String key) {
        String marker = "\"facts\":[";
        int cursor = json.indexOf(marker);
        if (cursor < 0) {
            return "";
        }
        cursor += marker.length();
        for (int i = 0; i <= factIndex; i++) {
            int open = json.indexOf('{', cursor);
            int close = json.indexOf('}', open);
            if (open < 0 || close < 0) {
                return "";
            }
            if (i == factIndex) {
                return field(json.substring(open, close + 1), key);
            }
            cursor = close + 1;
        }
        return "";
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private int countRows(String org, String table, String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void publicationDispatchFirstViewAndAcknowledgmentAreFourSeparateOrderedFacts() throws Exception {
        String id = "10000000-0000-0000-0000-000000000001";
        assertEquals(HttpStatus.OK,
                publish(id, 1, true, true, "publish-1", STAFF_A, ORG_A).getStatusCode());

        ResponseEntity<String> view = post("/api/v1/instructions/first-view", id, 1, "view-1",
                PATIENT_SUBJECT, ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        assertEquals(PATIENT_SUBJECT, field(view.getBody(), "actor"));

        ResponseEntity<String> acknowledgment = post("/api/v1/instructions/acknowledge", id, 1,
                "ack-1", PATIENT_SUBJECT, ORG_A);
        assertEquals(HttpStatus.OK, acknowledgment.getStatusCode(), acknowledgment.getBody());
        assertEquals("RECEIPT", field(acknowledgment.getBody(), "scope"));

        ResponseEntity<String> response = facts(id, PATIENT_SUBJECT, ORG_A);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        String body = response.getBody();
        assertEquals(4, countOccurrences(body, "\"instructionId\""), body);
        // The ordinal tiebreak keeps the four kinds ordered even when the real
        // clock gives two facts the same occurrence instant.
        assertEquals(STAFF_A, factField(body, 0, "actor"), body);
        assertEquals("NEW_INSTRUCTION", factField(body, 1, "genericKey"), body);
        assertFalse(body.contains(CONTENT_REF), "facts must not carry clinical content: " + body);
        assertEquals(PATIENT_SUBJECT, factField(body, 2, "actor"), body);
        assertEquals("RECEIPT", factField(body, 3, "scope"), body);
        for (int i = 0; i < 4; i++) {
            assertEquals("1", factField(body, i, "revision"), body);
        }

        String byInstruction = "instruction_id = CAST('" + id + "' AS uuid)";
        assertEquals(1, countRows(ORG_A, "instruction_fact_publication", byInstruction));
        assertEquals(1, countRows(ORG_A, "instruction_fact_dispatch", byInstruction));
        assertEquals(1, countRows(ORG_A, "instruction_fact_first_view", byInstruction));
        assertEquals(1, countRows(ORG_A, "instruction_fact_acknowledgment", byInstruction));
    }

    @Test
    void dispatchRetryRecordsAnotherSeparateGenericFactOverHttp() throws Exception {
        String id = "10000000-0000-0000-0000-000000000002";
        assertEquals(HttpStatus.OK,
                publish(id, 1, false, false, "publish-2", STAFF_A, ORG_A).getStatusCode());

        ResponseEntity<String> retry = post("/api/v1/instructions/dispatch/retry", id, 1,
                "retry-2a", STAFF_A, ORG_A);
        assertEquals(HttpStatus.OK, retry.getStatusCode(), retry.getBody());
        assertEquals("NEW_INSTRUCTION", field(retry.getBody(), "genericKey"));
        assertFalse(retry.getBody().contains(CONTENT_REF), retry.getBody());

        String body = facts(id, STAFF_A, ORG_A).getBody();
        assertEquals(2, countOccurrences(body, "\"instructionId\""), body);
        assertEquals("NEW_INSTRUCTION", factField(body, 1, "genericKey"), body);
        assertEquals("1", factField(body, 1, "revision"), body);

        // A second retry with a fresh idempotency key appends another fact; a
        // dispatch is never merged into the publication or into another dispatch.
        assertEquals(HttpStatus.OK,
                post("/api/v1/instructions/dispatch/retry", id, 1, "retry-2b", STAFF_A, ORG_A)
                        .getStatusCode());
        assertEquals(3, countOccurrences(facts(id, STAFF_A, ORG_A).getBody(), "\"instructionId\""));
        assertEquals(2, countRows(ORG_A, "instruction_fact_dispatch",
                "instruction_id = CAST('" + id + "' AS uuid)"));
    }

    @Test
    void repeatedFirstViewDoesNotCreateAnAcknowledgment() throws Exception {
        String id = "10000000-0000-0000-0000-000000000003";
        assertEquals(HttpStatus.OK,
                publish(id, 1, true, false, "publish-3", STAFF_A, ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK,
                post("/api/v1/instructions/first-view", id, 1, "view-3a", PATIENT_SUBJECT, ORG_A)
                        .getStatusCode());
        // The view command's client id is validated but not an idempotency key;
        // uniqueness of (instruction, revision, viewer) is enforced by V63's
        // first-view key, so a second view returns the original fact.
        assertEquals(HttpStatus.OK,
                post("/api/v1/instructions/first-view", id, 1, "view-3b", PATIENT_SUBJECT, ORG_A)
                        .getStatusCode());

        String body = facts(id, PATIENT_SUBJECT, ORG_A).getBody();
        assertEquals(2, countOccurrences(body, "\"instructionId\""), body);
        assertEquals(PATIENT_SUBJECT, factField(body, 1, "actor"), body);
        assertEquals("", factField(body, 1, "scope"), body);
        String byInstruction = "instruction_id = CAST('" + id + "' AS uuid)";
        assertEquals(1, countRows(ORG_A, "instruction_fact_first_view", byInstruction));
        assertEquals(0, countRows(ORG_A, "instruction_fact_acknowledgment", byInstruction));
    }

    @Test
    void receptionStaffCannotPublishAnInstruction() throws Exception {
        String id = "10000000-0000-0000-0000-000000000004";
        ResponseEntity<String> denied = publish(id, 1, false, false, "publish-4", RECEPTION, ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertTrue(denied.getBody().contains("FORBIDDEN"), denied.getBody());
        assertEquals(0, countRows(ORG_A, "instruction_fact_instruction",
                "id = CAST('" + id + "' AS uuid)"));
    }

    @Test
    void otherTenantCannotReadFactsAndSeesNotFound() throws Exception {
        String id = "10000000-0000-0000-0000-000000000005";
        assertEquals(HttpStatus.OK,
                publish(id, 1, true, true, "publish-5", STAFF_A, ORG_A).getStatusCode());

        ResponseEntity<String> denied = facts(id, STAFF_B, ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertTrue(denied.getBody().contains("NOT_FOUND"), denied.getBody());
        assertFalse(denied.getBody().contains(PATIENT_RECORD), denied.getBody());
        assertFalse(denied.getBody().contains(CONTENT_REF), denied.getBody());

        assertEquals(0, countRows(ORG_B, "instruction_fact_instruction",
                "id = CAST('" + id + "' AS uuid)"));
        // The owner still sees its own publication and generic dispatch; the
        // other tenant sees neither. No view or receipt was performed here.
        String ownerTimeline = facts(id, STAFF_A, ORG_A).getBody();
        assertEquals(2, countOccurrences(ownerTimeline, "\"instructionId\""), ownerTimeline);
        assertEquals("NEW_INSTRUCTION", factField(ownerTimeline, 1, "genericKey"), ownerTimeline);
    }

    @Test
    void acknowledgmentIsRefusedWhenNotRequested() throws Exception {
        String id = "10000000-0000-0000-0000-000000000006";
        assertEquals(HttpStatus.OK,
                publish(id, 1, false, false, "publish-6", STAFF_A, ORG_A).getStatusCode());

        ResponseEntity<String> denied = post("/api/v1/instructions/acknowledge", id, 1, "ack-6",
                PATIENT_SUBJECT, ORG_A);
        assertEquals(422, denied.getStatusCode().value(), denied.getBody());
        assertTrue(denied.getBody().contains("ACKNOWLEDGMENT_NOT_REQUESTED"), denied.getBody());
        assertEquals(0, countRows(ORG_A, "instruction_fact_acknowledgment",
                "instruction_id = CAST('" + id + "' AS uuid)"));
    }

    @Test
    void acknowledgingAnOlderRevisionIsRefused() throws Exception {
        String id = "10000000-0000-0000-0000-000000000007";
        assertEquals(HttpStatus.OK,
                publish(id, 1, true, true, "publish-7a", STAFF_A, ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK,
                publish(id, 2, true, true, "publish-7b", STAFF_A, ORG_A).getStatusCode());

        ResponseEntity<String> stale = post("/api/v1/instructions/acknowledge", id, 1, "ack-7-old",
                PATIENT_SUBJECT, ORG_A);
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode(), stale.getBody());
        assertTrue(stale.getBody().contains("STALE_REVISION"), stale.getBody());

        String byInstruction = "instruction_id = CAST('" + id + "' AS uuid)";
        assertEquals(0, countRows(ORG_A, "instruction_fact_acknowledgment", byInstruction));
        ResponseEntity<String> current = post("/api/v1/instructions/acknowledge", id, 2,
                "ack-7-current", PATIENT_SUBJECT, ORG_A);
        assertEquals(HttpStatus.OK, current.getStatusCode(), current.getBody());
        assertEquals(1, countRows(ORG_A, "instruction_fact_acknowledgment",
                byInstruction + " AND revision = 2"));
    }
}
