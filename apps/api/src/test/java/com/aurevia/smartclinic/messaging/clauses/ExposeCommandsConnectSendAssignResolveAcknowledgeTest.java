package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-07 clause 8 (API evidence): every command the clause names — connect,
 * send, assign, resolve, acknowledge and withdraw or replace instructions — is
 * posted through its real HTTP route and the resulting state is asserted.
 * Synthetic tenants, charts and subjects only.
 *
 * <p>The positive test drives one chain: a verified patient connects to a care
 * unit, opens a conversation and sends a message, staff assign and resolve the
 * work item, a clinician sends then replaces and withdraws an instruction, and
 * the patient acknowledges the published revision. The negative tests assert
 * that each refusal fails closed with an unchanged stored state: a patient
 * role may not assign or resolve, a subject of another tenant may not mutate
 * the conversation, a stale {@code If-Match} is refused, invalid input is
 * refused, and a withdrawn instruction cannot be replaced.</p>
 *
 * <p>One directory is used throughout: the production {@code JdbcMessagingStore}
 * reads the {@code care_unit} and {@code eligible_relationship} rows seeded
 * below. The instruction domain also uses its production bean,
 * {@link com.aurevia.smartclinic.messaging.instructions.JdbcInstructionStore},
 * which derives roles, verified charts and unit assignment from the
 * authoritative folds seeded in {@code prepare()} and reads the patient-unit
 * connection written by the HTTP connect command. No production code is
 * changed by this test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_arc07_commands_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExposeCommandsConnectSendAssignResolveAcknowledgeTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String UNIT = "44444444-4444-4444-4444-444444444444";
    private static final String INSTRUCTION = "55555555-5555-5555-5555-555555555555";
    private static final String PATIENT_SUBJECT = "arc07-patient";
    private static final String CLINICIAN_SUBJECT = "arc07-clinician";
    private static final String OTHER_TENANT_SUBJECT = "arc07-other-staff";
    private static final String UNIT_KEY = "clinic";
    private static final String REFERENCE_PREFIX = "arc07-order-reference-";
    private static final int RELATIONSHIP_COUNT = 20;
    private static final AtomicInteger REFERENCE_INDEX = new AtomicInteger();
    private static final String DUE_AT = "2026-12-01T00:00:00Z";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_arc07_commands_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    RestClient rest;

    /** Build a fresh database with only the production migrations applied. */
    @BeforeAll
    static void prepare() throws Exception {
        // A Surefire rerun in the same JVM rebuilds the database, so the
        // verified-reference pool must start from the same seed too.
        REFERENCE_INDEX.set(0);
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_arc07_commands_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_arc07_commands_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, ORG, PATIENT_SUBJECT, "patient");
            seedMember(statement, ORG, CLINICIAN_SUBJECT, "clinician");
            seedMember(statement, OTHER_ORG, OTHER_TENANT_SUBJECT, "lab_collector");
            statement.execute("SET app.current_org = '" + ORG + "'");
            statement.execute("SET app.actor = '" + PATIENT_SUBJECT + "'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG + "', '" + PATIENT + "', 1, 'MRN-ARC07', 'CLINIC', "
                    + "'Synthetic Arc07 Patient', 'synthetic arc07 patient', DATE '1990-05-20')");
            statement.execute("INSERT INTO public.patient_link "
                    + "(organization_id, subject, patient_id, active, created_by) "
                    + "SELECT '" + ORG + "', '" + PATIENT_SUBJECT + "', '" + PATIENT + "', true, id "
                    + "FROM public.user_identity WHERE organization_id = '" + ORG
                    + "' AND subject = '" + PATIENT_SUBJECT + "'");
            statement.execute("INSERT INTO public.patient_account (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG + "', '" + PATIENT_SUBJECT + "', 'Synthetic Patient Account')");
            statement.execute("INSERT INTO public.account_chart_link "
                    + "(organization_id, account_id, chart_id, event, method) "
                    + "SELECT '" + ORG + "', id, '" + PATIENT + "', 'linked', 'synthetic' "
                    + "FROM public.patient_account WHERE organization_id = '" + ORG
                    + "' AND subject = '" + PATIENT_SUBJECT + "'");
            statement.execute("INSERT INTO public.care_unit "
                    + "(organization_id, id, facility_id, name, topics, active, unit_key) "
                    + "VALUES ('" + ORG + "', '" + UNIT + "', '" + PATIENT + "', 'Synthetic Clinic Unit', "
                    + "ARRAY['messages', 'follow-up'], true, '" + UNIT_KEY + "')");
            // One eligible relationship per command so every connection has a
            // distinct verified reference, as required by the unique constraint.
            for (int index = 0; index < RELATIONSHIP_COUNT; index++) {
                String relationship = UUID.nameUUIDFromBytes(
                        ("arc07-relationship-" + index).getBytes(StandardCharsets.UTF_8)).toString();
                statement.execute("INSERT INTO public.eligible_relationship "
                        + "(organization_id, id, patient_id, unit_id, context, reference, active) "
                        + "VALUES ('" + ORG + "', '" + relationship + "', '" + PATIENT + "', '" + UNIT
                        + "', 'order', '" + REFERENCE_PREFIX + index + "', true)");
            }
            statement.execute("INSERT INTO public.instruction_fact_unit_assignment "
                    + "(organization_id, subject, unit_id) VALUES ('" + ORG + "', '"
                    + CLINICIAN_SUBJECT + "', '" + UNIT + "')");
        }
    }

    /** Remove the scratch database; no fixture is shared between runs. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_arc07_commands_it WITH (FORCE)");
        }
    }

    /** Seed one organization member with an active membership and one role. */
    private static void seedMember(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + organization + "', 'Org " + organization + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '"
                + organization + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity "
                + "WHERE organization_id = '" + organization + "' AND subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE m.organization_id = '" + organization
                + "' AND u.subject = '" + subject + "'");
    }

    /** Bind the HTTP client; instruction folds are seeded by {@code prepare()}. */
    @BeforeEach
    void setUp(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Never leak a thread-bound tenant into another test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void connectSendAssignResolveAcknowledgeAndWithdrawOrReplaceInstructionsAreExposedOverHttp() {
        // Connect: POST /api/v1/patient-unit-connections returns 201 and an active connection.
        String connection = connect();
        JsonNode connectionView = tree(get("/api/v1/patient-unit-connections?patientId=" + PATIENT,
                PATIENT_SUBJECT, ORG, null));
        assertTrue(containsConnection(connectionView, connection),
                "the created connection must be listed as active for the verified chart");

        // Send a message: open the conversation through the verified connection, then reply.
        String conversation = openConversation(connection);
        JsonNode opened = tree(get("/api/v1/conversations/" + conversation, PATIENT_SUBJECT, ORG, null));
        assertEquals("awaiting_staff", opened.at("/conversation/state").asText());
        assertEquals(1, opened.at("/conversation/version").asInt());
        assertEquals(200, post("/api/v1/conversations/" + conversation + "/reply",
                "{\"body\":\"synthetic question\"}", PATIENT_SUBJECT, ORG, "\"1\"")
                .getStatusCode().value());
        assertEquals(2, tree(get("/api/v1/conversations/" + conversation, PATIENT_SUBJECT, ORG, null))
                .at("/conversation/version").asInt());

        // Assign and resolve the work item over HTTP, each at the version just read.
        ResponseEntity<String> assignResponse = post("/api/v1/conversations/" + conversation + "/assign",
                "{\"assigneeSubject\":\"" + CLINICIAN_SUBJECT + "\",\"reason\":\"synthetic cover\"}",
                CLINICIAN_SUBJECT, ORG, "\"2\"");
        assertEquals(200, assignResponse.getStatusCode().value(), assignResponse.getBody());
        JsonNode assigned = tree(assignResponse);
        assertEquals(CLINICIAN_SUBJECT, assigned.at("/assigneeSubject").asText());
        assertEquals(3, assigned.at("/version").asInt());
        JsonNode resolved = tree(post("/api/v1/conversations/" + conversation + "/resolve",
                "{\"reason\":\"synthetic answer\"}", CLINICIAN_SUBJECT, ORG, "\"3\""));
        assertEquals("resolved", resolved.at("/state").asText());
        assertEquals(4, resolved.at("/version").asInt());
        assertEquals("resolved", tree(get("/api/v1/conversations/" + conversation,
                PATIENT_SUBJECT, ORG, null)).at("/conversation/state").asText());

        // Send an instruction, then replace it and withdraw the replacement.
        String thread = sendInstruction(connection);
        JsonNode sent = tree(get("/api/v1/patient-instructions/" + thread, CLINICIAN_SUBJECT, ORG, null));
        assertEquals("SENT", sent.at("/current/state").asText());
        JsonNode replaced = tree(post("/api/v1/patient-instructions/" + thread + "/replace",
                replaceBody(), CLINICIAN_SUBJECT, ORG, "\"1\""));
        assertEquals(2, replaced.at("/revision").asInt());
        assertEquals("SENT", replaced.at("/state").asText());
        JsonNode withdrawn = tree(post("/api/v1/patient-instructions/" + thread + "/withdraw",
                "{\"reason\":\"synthetic cancellation\"}", CLINICIAN_SUBJECT, ORG, "\"2\""));
        assertEquals("WITHDRAWN", withdrawn.at("/state").asText());
        assertEquals("WITHDRAWN", tree(get("/api/v1/patient-instructions/" + thread,
                CLINICIAN_SUBJECT, ORG, null)).at("/current/state").asText());

        // Publish a receipt-requesting revision and acknowledge that exact revision.
        assertEquals(200, post("/api/v1/instructions/publish", publishBody(),
                CLINICIAN_SUBJECT, ORG, null).getStatusCode().value());
        JsonNode acknowledgment = tree(post("/api/v1/instructions/acknowledge",
                "{\"instructionId\":\"" + INSTRUCTION + "\",\"revision\":1,\"clientId\":\"ack-arc07-1\"}",
                PATIENT_SUBJECT, ORG, null));
        assertEquals("RECEIPT", acknowledgment.at("/scope").asText());
        assertEquals(PATIENT_SUBJECT, acknowledgment.at("/actor").asText());
        JsonNode facts = tree(get("/api/v1/instructions/" + INSTRUCTION + "/facts",
                PATIENT_SUBJECT, ORG, null));
        assertTrue(containsReceiptFact(facts, PATIENT_SUBJECT),
                "the receipt-only acknowledgment must be a durable fact: " + facts.toString());
    }

    @Test
    void wrongRoleCannotAssignOrResolveAConversation() {
        String conversation = openConversation(connect());
        assertEquals(403, post("/api/v1/conversations/" + conversation + "/assign",
                "{\"assigneeSubject\":\"" + CLINICIAN_SUBJECT + "\",\"reason\":\"cover\"}",
                PATIENT_SUBJECT, ORG, "\"1\"").getStatusCode().value());
        assertEquals(403, post("/api/v1/conversations/" + conversation + "/resolve",
                "{\"reason\":\"answered\"}", PATIENT_SUBJECT, ORG, "\"1\"").getStatusCode().value());

        JsonNode unchanged = tree(get("/api/v1/conversations/" + conversation,
                PATIENT_SUBJECT, ORG, null));
        assertEquals("awaiting_staff", unchanged.at("/conversation/state").asText());
        assertEquals(1, unchanged.at("/conversation/version").asInt());
        assertTrue(unchanged.at("/conversation/assigneeSubject").isNull(),
                "a denied role must not leave an assignment behind");
    }

    @Test
    void anotherTenantCannotMutateTheConnectedConversation() {
        String conversation = openConversation(connect());
        // The ORG_B subject has an active membership, so authorization passes and
        // the RLS-bounded load returns no row: the command fails closed as 404.
        ResponseEntity<String> response = post("/api/v1/conversations/" + conversation + "/assign",
                "{\"assigneeSubject\":\"" + OTHER_TENANT_SUBJECT + "\",\"reason\":\"cover\"}",
                OTHER_TENANT_SUBJECT, OTHER_ORG, "\"1\"");
        assertEquals(404, response.getStatusCode().value(), response.getBody());
        assertEquals(404, post("/api/v1/conversations/" + conversation + "/resolve",
                "{\"reason\":\"answered\"}", OTHER_TENANT_SUBJECT, OTHER_ORG, "\"1\"")
                .getStatusCode().value());

        JsonNode unchanged = tree(get("/api/v1/conversations/" + conversation,
                PATIENT_SUBJECT, ORG, null));
        assertEquals("awaiting_staff", unchanged.at("/conversation/state").asText());
        assertEquals(1, unchanged.at("/conversation/version").asInt());
    }

    @Test
    void staleVersionCannotAssignResolveWithdrawOrReplace() {
        String connection = connect();
        String conversation = openConversation(connection);
        assertEquals(412, post("/api/v1/conversations/" + conversation + "/assign",
                "{\"assigneeSubject\":\"" + CLINICIAN_SUBJECT + "\",\"reason\":\"cover\"}",
                CLINICIAN_SUBJECT, ORG, "\"9\"").getStatusCode().value());
        assertEquals(412, post("/api/v1/conversations/" + conversation + "/resolve",
                "{\"reason\":\"answered\"}", CLINICIAN_SUBJECT, ORG, "\"9\"").getStatusCode().value());
        JsonNode conversationState = tree(get("/api/v1/conversations/" + conversation,
                PATIENT_SUBJECT, ORG, null));
        assertEquals("awaiting_staff", conversationState.at("/conversation/state").asText());
        assertEquals(1, conversationState.at("/conversation/version").asInt());

        String thread = sendInstruction(connection);
        assertEquals(412, post("/api/v1/patient-instructions/" + thread + "/replace",
                replaceBody(), CLINICIAN_SUBJECT, ORG, "\"9\"").getStatusCode().value());
        assertEquals(412, post("/api/v1/patient-instructions/" + thread + "/withdraw",
                "{\"reason\":\"stale cancellation\"}", CLINICIAN_SUBJECT, ORG, "\"9\"")
                .getStatusCode().value());
        JsonNode instructionState = tree(get("/api/v1/patient-instructions/" + thread,
                CLINICIAN_SUBJECT, ORG, null));
        assertEquals("SENT", instructionState.at("/current/state").asText());
        assertEquals(1, instructionState.at("/current/revision").asInt());
    }

    @Test
    void missingInputIsRefused() {
        assertEquals(422, post("/api/v1/patient-unit-connections",
                "{\"patientId\":null,\"unitId\":\"" + UNIT + "\",\"context\":\"order\","
                        + "\"contextReference\":\"missing-patient\"}",
                PATIENT_SUBJECT, ORG, null).getStatusCode().value());
        String conversation = openConversation(connect());
        assertEquals(422, post("/api/v1/conversations/" + conversation + "/resolve",
                "{\"reason\":\"\"}", CLINICIAN_SUBJECT, ORG, "\"1\"").getStatusCode().value());
        assertEquals(422, post("/api/v1/instructions/acknowledge",
                "{\"instructionId\":\"not-a-uuid\",\"revision\":1,\"clientId\":\"bad-input\"}",
                PATIENT_SUBJECT, ORG, null).getStatusCode().value());

        JsonNode unchanged = tree(get("/api/v1/conversations/" + conversation,
                PATIENT_SUBJECT, ORG, null));
        assertEquals("awaiting_staff", unchanged.at("/conversation/state").asText());
        assertEquals(1, unchanged.at("/conversation/version").asInt());
    }

    @Test
    void withdrawnInstructionCannotBeReplaced() {
        String connection = connect();
        String thread = sendInstruction(connection);
        assertEquals(200, post("/api/v1/patient-instructions/" + thread + "/withdraw",
                "{\"reason\":\"terminal\"}", CLINICIAN_SUBJECT, ORG, "\"1\"")
                .getStatusCode().value());
        ResponseEntity<String> response = post("/api/v1/patient-instructions/" + thread + "/replace",
                replaceBody(), CLINICIAN_SUBJECT, ORG, "\"1\"");
        assertEquals(409, response.getStatusCode().value(), response.getBody());

        JsonNode unchanged = tree(get("/api/v1/patient-instructions/" + thread,
                CLINICIAN_SUBJECT, ORG, null));
        assertEquals("WITHDRAWN", unchanged.at("/current/state").asText());
        assertEquals(1, unchanged.at("/current/revision").asInt());
    }

    /** Connect a verified chart to the seeded unit; each call uses a fresh verified reference. */
    private String connect() {
        String reference = REFERENCE_PREFIX + REFERENCE_INDEX.getAndIncrement();
        ResponseEntity<String> response = post("/api/v1/patient-unit-connections",
                "{\"patientId\":\"" + PATIENT + "\",\"unitId\":\"" + UNIT
                        + "\",\"context\":\"order\",\"contextReference\":\"" + reference + "\"}",
                PATIENT_SUBJECT, ORG, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).at("/id").asText();
    }

    /** Open a conversation at version 1 through the verified connection. */
    private String openConversation(String connection) {
        ResponseEntity<String> response = post("/api/v1/conversations", openBody(connection),
                PATIENT_SUBJECT, ORG, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).at("/id").asText();
    }

    /** Publish a revision from bounded free text; no template seed is needed. */
    private String sendInstruction(String connection) {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"connectionId\":\"" + connection
                + "\",\"unitId\":\"" + UNIT + "\",\"kind\":\"COLLECTION\","
                + "\"title\":\"Synthetic collection\",\"text\":\"Collect after breakfast.\","
                + "\"language\":\"en\",\"attachmentIds\":[]}";
        ResponseEntity<String> response = post("/api/v1/patient-instructions", body,
                CLINICIAN_SUBJECT, ORG, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).at("/threadId").asText();
    }

    /** A replacement carries new free text and a non-sensitive reason. */
    private static String replaceBody() {
        return "{\"title\":\"Updated collection\",\"text\":\"Collect before breakfast.\","
                + "\"language\":\"en\",\"attachmentIds\":[],\"reason\":\"synthetic update\"}";
    }

    /** A publication that requests a receipt acknowledgment for one exact revision. */
    private static String publishBody() {
        return "{\"instructionId\":\"" + INSTRUCTION + "\",\"patientId\":\"" + PATIENT
                + "\",\"unitId\":\"" + UNIT + "\",\"revision\":1,\"contentRef\":\"synthetic-artifact\","
                + "\"requiresAcknowledgment\":true,\"acknowledgmentDueAt\":\"" + DUE_AT + "\","
                + "\"sendNotification\":false,\"clientId\":\"publish-arc07-1\"}";
    }

    /** The open command names the patient subject and the connection the patient verified. */
    private static String openBody(String connection) {
        return "{\"patientId\":\"" + PATIENT + "\",\"patientSubject\":\"" + PATIENT_SUBJECT
                + "\",\"unitKey\":\"" + UNIT_KEY + "\",\"connectionId\":\"" + connection
                + "\",\"topic\":\"follow up\"}";
    }

    /** Read a JSON tree from a response so a wrong field cannot pass unnoticed. */
    private JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response: " + response.getBody(), exception);
        }
    }

    /** True when the connection list contains the given active connection. */
    private static boolean containsConnection(JsonNode list, String connection) {
        for (JsonNode entry : list.at("/connections")) {
            if (connection.equals(entry.at("/id").asText())
                    && "active".equals(entry.at("/state").asText())) {
                return true;
            }
        }
        return false;
    }

    /** True when the fact timeline contains the patient's receipt-only acknowledgment. */
    private static boolean containsReceiptFact(JsonNode timeline, String actor) {
        for (JsonNode fact : timeline.at("/facts")) {
            if ("RECEIPT".equals(fact.at("/scope").asText())
                    && actor.equals(fact.at("/actor").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Issue an HTTP command with synthetic-auth headers and an optional version precondition. */
    private ResponseEntity<String> post(String uri, String body, String subject, String organization,
                                        String ifMatch) {
        return send(HttpMethod.POST, uri, body, subject, organization, ifMatch);
    }

    /** Issue an HTTP read with the same synthetic-auth headers. */
    private ResponseEntity<String> get(String uri, String subject, String organization, String ifMatch) {
        return send(HttpMethod.GET, uri, null, subject, organization, ifMatch);
    }

    /** Send one request and return the raw response, including error statuses. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }
}
