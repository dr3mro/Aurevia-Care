package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-01 clause 5 proof at the order HTTP entry point: sharing an order grants
 * no general chart access.
 *
 * <p>Three synthetic organizations share the same local identifiers: A sends an
 * order, B is the approved destination and C is unrelated. After a release, B
 * holds only a bounded work item. Its projected patient reference is an opaque
 * referral identifier, not a key into A's chart: A's order, patient and chart
 * reads stay behind their own tenant and role boundaries, and B can bind only
 * one of its own patients through a checked, attested identity match. A seeded
 * chart entry in A makes the "receiver sees nothing" assertion non-vacuous.</p>
 *
 * <p>No production code is added by this suite; it exercises existing routes
 * and forced-RLS tables with synthetic fixtures only.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_exchange_no_chart_access_it")
@ActiveProfiles("synthetic-auth")
@Timeout(600)
class SharingOrderGrantsGeneralChartAccessTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_exchange_no_chart_access_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    static final String GRANT = "55555555-5555-5555-5555-555555555555";
    static final String CHART_SENTINEL = "SYNTHETIC-SENDER-ONLY-CHART-ENTRY";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, apply the real migrations and seed three tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_exchange_no_chart_access_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_exchange_no_chart_access_it "
                    + "OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C}) {
                seedOrganization(statement, org);
            }
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
            seedMember(statement, ORG_B, "reception-b", "reception");
            seedMember(statement, ORG_C, "clinician-c", "clinician");
            seedAuthorization(statement);
            seedSecondReceiverPatient(statement);
            seedSenderChartEntry(statement);
        }
    }

    /** Drop the isolated database so a rerun starts from an empty schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_exchange_no_chart_access_it WITH (FORCE)");
        }
    }

    /** Seed one tenant with its bootstrap actor and its own MRN-1 synthetic patient. */
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

    /** Seed an active membership and one role for a synthetic subject of a tenant. */
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

    /** Seed the single active CBC -> ORG_B authorization used by every release in this suite. */
    private static void seedAuthorization(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'admin-a'");
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + ORG_A + "', '" + GRANT + "', 'CBC', '"
                + ORG_B + "', 'lab review', 60, now() - interval '1 day', now() + interval '1 day', true)");
    }

    /** Seed a second receiver patient whose MRN does not match the projected referral. */
    private static void seedSecondReceiverPatient(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_B + "'");
        statement.execute("SET app.actor = 'admin-b'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_B + "', 1, 'MRN-2', 'CLINIC', 'Other Synthetic Patient', "
                + "'other synthetic patient', '1991-06-21')");
    }

    /**
     * Seed one real chart entry in the sender tenant. Without it, an empty chart read
     * from the receiver would be vacuous: there would be no hidden row to conceal.
     */
    private static void seedSenderChartEntry(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'clinician-a'");
        statement.execute("INSERT INTO public.chart_entry "
                + "(organization_id, patient_id, type, original_text, source, presence, verification, "
                + "clinical_author, data_entry_actor) VALUES ('" + ORG_A + "', CAST('"
                + patientId(ORG_A) + "' AS uuid), 'PROBLEM', '" + CHART_SENTINEL + "', "
                + "'CLINICIAN_OBSERVED', 'PRESENT', 'UNVERIFIED', 'clinician-a', 'clinician-a')");
    }

    /** Read a synthetic patient identifier through the tenant-scoped connection. */
    private static String patientId(String org, String mrn) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = '" + mrn + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    /** Read the MRN-1 synthetic patient identifier of a tenant. */
    private static String patientId(String org) {
        return patientId(org, "MRN-1");
    }

    /** Read a scalar under an explicit tenant and actor, rolling back the probe. */
    private static long scalar(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /** Bind the transaction-local tenant and actor used by every RLS decision. */
    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Build the synthetic identity headers; both are always set so a 401 is never mistaken for a denial. */
    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Send an HTTP request to the running application and keep the body of every status. */
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

    /** Read a required text field from a JSON response. */
    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer, exception);
        }
    }

    /** Parse a JSON response body. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response", exception);
        }
    }

    /** Build a synthetic release command bound to an approved grant. */
    private static String orderBody(String patient, String code, String destination, String purpose,
                                    String grant, int duration) {
        return "{\"patientId\":\"" + patient + "\",\"encounterRef\":\"ENC-SYN-1\","
                + "\"orderCode\":\"" + code + "\",\"priority\":\"routine\","
                + "\"clinicalQuestion\":\"Synthetic CBC question\",\"destinationOrg\":\""
                + destination + "\",\"purpose\":\"" + purpose + "\",\"authorizationId\":\""
                + grant + "\",\"responsibleClinician\":\"dr-a\",\"workQueue\":\"lab-queue\","
                + "\"durationMinutes\":" + duration + "}";
    }

    /** Release a synthetic order through the HTTP entry point. */
    private ResponseEntity<String> createOrder(String code, String destination, String purpose,
                                               String grant, int duration, String subject, String org) {
        return send(HttpMethod.POST, "/api/v1/orders",
                orderBody(patientId(org), code, destination, purpose, grant, duration), subject, org);
    }

    /** Build a receiver verification command for a specific local patient and MRN. */
    private static String verifyBody(String localPatientId, String mrn) {
        return "{\"localPatientId\":\"" + localPatientId + "\",\"localMrn\":\"" + mrn + "\","
                + "\"mrnIssuer\":\"CLINIC\",\"attestation\":\"site-verified MRN match\"}";
    }

    /** Find the exactly one work item projected for an order, failing when it is missing or duplicated. */
    private JsonNode workItemFor(ResponseEntity<String> inbox, String orderId) {
        JsonNode match = null;
        for (JsonNode item : json(inbox)) {
            if (orderId.equals(item.at("/orderRef").asText())) {
                assertTrue(match == null, "one work item per released order revision");
                match = item;
            }
        }
        assertTrue(match != null, "the approved destination must see the projected work item");
        return match;
    }

    /**
     * Rule met: the receiver gets a bounded work item and no sender chart, patient
     * or order read; the sender's chart really holds a row that the receiver cannot see.
     */
    @Test
    void sharingAnOrderGrantsOnlyABoundedWorkItemNotSenderChartAccess() throws Exception {
        ResponseEntity<String> created = createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String orderId = text(created, "/id");
        String senderPatientId = text(created, "/patientId");
        String fingerprint = text(created, "/snapshotFingerprint");

        ResponseEntity<String> inbox = send(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, inbox.getStatusCode(), inbox.getBody());
        JsonNode item = workItemFor(inbox, orderId);
        Set<String> actual = new HashSet<>();
        item.properties().forEach(entry -> actual.add(entry.getKey()));
        assertEquals(Set.of("id", "senderOrg", "destinationOrg", "orderRef", "patientRef",
                "patientMrn", "mrnIssuer", "localPatientId", "patientVerified", "priority",
                "clinicalQuestion", "workQueue", "fingerprint", "state", "createdAt"), actual,
                "the projection carries exchange fields only, never chart or order fields");
        assertEquals(orderId, item.at("/orderRef").asText());
        assertEquals(ORG_A, item.at("/senderOrg").asText());
        assertEquals(ORG_B, item.at("/destinationOrg").asText());
        assertEquals(fingerprint, item.at("/fingerprint").asText());
        assertEquals("awaiting_local_patient", item.at("/state").asText());
        assertFalse(item.at("/patientVerified").asBoolean());
        assertTrue(item.at("/localPatientId").isNull());

        // The sender's own identifiers do not open its order, patient or chart from the receiver.
        ResponseEntity<String> orderRead = send(HttpMethod.GET, "/api/v1/orders/" + orderId, null,
                "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, orderRead.getStatusCode(), orderRead.getBody());
        assertEquals("NOT_FOUND", text(orderRead, "/code"));

        String chartPath = "/api/v1/chart/records/patients/" + senderPatientId + "/entries";
        ResponseEntity<String> senderChart = send(HttpMethod.GET, chartPath, null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, senderChart.getStatusCode(), senderChart.getBody());
        assertTrue(json(senderChart).at("/entries").size() >= 1, "the sender chart must hold a row");
        assertTrue(senderChart.getBody().contains(CHART_SENTINEL), "the sentinel row must be visible to its owner");
        ResponseEntity<String> receiverChart = send(HttpMethod.GET, chartPath, null, "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, receiverChart.getStatusCode(), receiverChart.getBody());
        assertEquals(0, json(receiverChart).at("/entries").size(), "RLS hides every sender chart row");
        assertFalse(receiverChart.getBody().contains(CHART_SENTINEL));
        ResponseEntity<String> singleEntry = send(HttpMethod.GET, "/api/v1/chart/records/entries/"
                + text(senderChart, "/entries/0/id"), null, "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, singleEntry.getStatusCode(), singleEntry.getBody());

        // Clinicians lack PATIENT_READ; a role that has it still cannot see a concealed foreign patient.
        ResponseEntity<String> patientAsClinician = send(HttpMethod.GET,
                "/api/v1/patients/" + senderPatientId, null, "clinician-b", ORG_B);
        assertEquals(HttpStatus.FORBIDDEN, patientAsClinician.getStatusCode(), patientAsClinician.getBody());
        assertEquals("FORBIDDEN", text(patientAsClinician, "/code"));
        assertEquals("missing patient permission", text(patientAsClinician, "/message"));
        ResponseEntity<String> patientAsReception = send(HttpMethod.GET,
                "/api/v1/patients/" + senderPatientId, null, "reception-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, patientAsReception.getStatusCode(), patientAsReception.getBody());
        assertEquals("NOT_FOUND", text(patientAsReception, "/code"));
        assertEquals("patient not found", text(patientAsReception, "/message"));

        // Positive control: the same receiver role can read its own patient, so 404 above is real concealment.
        String receiverPatientId = patientId(ORG_B);
        ResponseEntity<String> ownPatient = send(HttpMethod.GET, "/api/v1/patients/" + receiverPatientId,
                null, "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, ownPatient.getStatusCode(), ownPatient.getBody());
        assertEquals(receiverPatientId, text(ownPatient, "/patient/id"));
        assertEquals(0, scalar(ORG_B, "admin-b", "SELECT count(*) FROM public.exchange_order "
                + "WHERE id = '" + orderId + "'"), "the sender order row never enters the receiver tenant");
    }

    /**
     * Negative: a sender patient cannot be bound, a mismatched receiver patient is
     * refused with an identity error, and a completed verification cannot be repeated.
     */
    @Test
    void receiverClinicianCannotVerifyAgainstTheSendersPatient() throws Exception {
        ResponseEntity<String> created = createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String workId = workItemFor(send(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                "clinician-b", ORG_B), text(created, "/id")).at("/id").asText();
        String path = "/api/v1/exchange/work-items/" + workId + "/verify-patient";
        String senderPatientId = text(created, "/patientId");
        String receiverPatientId = patientId(ORG_B);
        String senderChartPath = "/api/v1/chart/records/patients/" + senderPatientId + "/entries";
        ResponseEntity<String> senderChart = send(HttpMethod.GET, senderChartPath, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, senderChart.getStatusCode(), senderChart.getBody());
        String senderEntryId = text(senderChart, "/entries/0/id");

        ResponseEntity<String> senderPatient = send(HttpMethod.POST, path,
                verifyBody(senderPatientId, "MRN-1"), "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, senderPatient.getStatusCode(), senderPatient.getBody());
        assertEquals("NOT_FOUND", text(senderPatient, "/code"));
        assertEquals("local patient not found", text(senderPatient, "/message"));

        ResponseEntity<String> mismatched = send(HttpMethod.POST, path,
                verifyBody(patientId(ORG_B, "MRN-2"), "MRN-2"), "clinician-b", ORG_B);
        assertEquals(HttpStatus.FORBIDDEN, mismatched.getStatusCode(), mismatched.getBody());
        assertEquals("IDENTITY_MISMATCH", text(mismatched, "/code"));
        assertEquals("the local patient does not match the referred patient identity",
                text(mismatched, "/message"));

        ResponseEntity<String> verified = send(HttpMethod.POST, path,
                verifyBody(receiverPatientId, "MRN-1"), "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("ready", text(verified, "/state"));
        assertEquals(receiverPatientId, text(verified, "/localPatientId"));
        assertTrue(json(verified).at("/patientVerified").asBoolean());

        ResponseEntity<String> repeated = send(HttpMethod.POST, path,
                verifyBody(receiverPatientId, "MRN-1"), "clinician-b", ORG_B);
        assertEquals(HttpStatus.CONFLICT, repeated.getStatusCode(), repeated.getBody());
        assertEquals("ALREADY_VERIFIED", text(repeated, "/code"));
        assertEquals("the work item is already verified", text(repeated, "/message"));

        // Binding a local patient widens no chart access: the sender's entry stays concealed.
        ResponseEntity<String> chartAfterVerify = send(HttpMethod.GET, senderChartPath, null,
                "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, chartAfterVerify.getStatusCode(), chartAfterVerify.getBody());
        assertEquals(0, json(chartAfterVerify).at("/entries").size());
        assertFalse(chartAfterVerify.getBody().contains(CHART_SENTINEL));
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + senderEntryId, null, "clinician-b", ORG_B)
                .getStatusCode());
    }

    /**
     * Negative: an unrelated tenant sees no projection and cannot reach the work
     * item, and a receiver role without the clinician permission cannot verify it.
     */
    @Test
    void unrelatedTenantAndWrongRoleSeeNoReceiverProjection() throws Exception {
        ResponseEntity<String> created = createOrder("CBC", ORG_B, "lab review", GRANT, 30,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String workId = workItemFor(send(HttpMethod.GET, "/api/v1/exchange/work-items", null,
                "clinician-b", ORG_B), text(created, "/id")).at("/id").asText();
        String path = "/api/v1/exchange/work-items/" + workId + "/verify-patient";

        ResponseEntity<String> unrelatedInbox = send(HttpMethod.GET, "/api/v1/exchange/work-items",
                null, "clinician-c", ORG_C);
        assertEquals(HttpStatus.OK, unrelatedInbox.getStatusCode(), unrelatedInbox.getBody());
        assertEquals(0, json(unrelatedInbox).size(), "an unrelated tenant sees no projected work items");

        ResponseEntity<String> unrelatedVerify = send(HttpMethod.POST, path,
                verifyBody(patientId(ORG_B), "MRN-1"), "clinician-c", ORG_C);
        assertEquals(HttpStatus.NOT_FOUND, unrelatedVerify.getStatusCode(), unrelatedVerify.getBody());
        assertEquals("NOT_FOUND", text(unrelatedVerify, "/code"));
        assertEquals("work item not found", text(unrelatedVerify, "/message"));

        ResponseEntity<String> wrongRole = send(HttpMethod.POST, path,
                verifyBody(patientId(ORG_B), "MRN-1"), "reception-b", ORG_B);
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());
        assertEquals("FORBIDDEN", text(wrongRole, "/code"));
        assertEquals("missing exchange permission", text(wrongRole, "/message"));
    }
}
