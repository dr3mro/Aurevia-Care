package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

import org.flywaydb.core.Flyway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-05 clause 2 (API evidence) on a real PostgreSQL instance.
 *
 * <p>The positive test drives the real HTTP entry point and asserts that each
 * clinically significant fact (escalation attempt and its recipient and channel,
 * required read-back, clinician acknowledgment and follow-up) is appended as a
 * separate, ordered event bound to the revision the clinician read. The negative
 * tests assert the rule refuses what it must refuse: acknowledgment without a
 * delivered attempt, a required read-back that is missing, a read-back when none
 * was required, a verifier acting as a clinician, a peer tenant reading the
 * trail, and a stale version precondition.</p>
 *
 * <p>Two synthetic organizations share local identifiers so tenant isolation is
 * observable. All fixtures and narratives are synthetic; no patient data or
 * credentials are used. Forced row-level security confines every row to the
 * active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch05_tracking_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class TrackAttemptsRecipientReadBackRequiredAcknowledgmentTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch05_tracking_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final String BACKUP = "ONCALL_PHONE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @LocalServerPort
    int port;

    /** Create the HTTP client against the randomly assigned server port. */
    @BeforeEach
    void initRestClient() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, apply the production migrations and seed synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch05_tracking_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch05_tracking_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedOrganization(statement, org);
                String subjectSuffix = suffix(org);
                seedMember(statement, org, "lab_verifier-" + subjectSuffix, "lab_verifier");
                seedMember(statement, org, "clinician-" + subjectSuffix, "clinician");
                seedMember(statement, org, "backup-" + subjectSuffix, "clinician");
                seedMember(statement, org, "admin-" + subjectSuffix, "tenant_admin");
                seedPolicy(statement, org, "admin-" + subjectSuffix,
                        "critical.escalation.primary_channel", PRIMARY);
                seedPolicy(statement, org, "admin-" + subjectSuffix,
                        "critical.escalation.backup_channel", BACKUP);
                seedPolicy(statement, org, "admin-" + subjectSuffix,
                        "critical.escalation.backup_recipient", "backup-" + subjectSuffix);
            }
        }
    }

    /** Drop the isolated database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch05_tracking_it WITH (FORCE)");
        }
    }

    /** Seed one tenant with a bootstrap actor and a single synthetic patient. */
    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'lab_verifier-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix(org) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    /** Seed one active organization member with a single bounded role. */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Seed and approve one site escalation-policy entry. */
    private static void seedPolicy(Statement statement, String org, String actor, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    @Test
    void attemptsRecipientReadBackAcknowledgmentAndFollowUpAreSeparateEvents() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";

        ResponseEntity<String> created = register(org, lab, true);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");

        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());
        ResponseEntity<String> attempt = escalate(org, admin, id, 3, PRIMARY, clinician, "delivered", false);
        assertEquals(HttpStatus.OK, attempt.getStatusCode(), attempt.getBody());
        assertEquals("3", text(attempt, "/version"), "an escalation attempt is a fact, not a state change");
        assertEquals(HttpStatus.OK, readBack(org, clinician, id, 3).getStatusCode());
        assertEquals(HttpStatus.OK, acknowledge(org, clinician, id, 3).getStatusCode());
        assertEquals(HttpStatus.OK, followUp(org, clinician, id, 4).getStatusCode());

        JsonNode trail = json(get(org, admin, id)).at("/events");
        assertEquals(7, trail.size(), "each clinically significant fact is its own event");
        String[] expectedTypes = {"registered", "owner_assigned", "verified", "escalation_attempt",
                "read_back", "acknowledged", "follow_up"};
        Set<String> distinctTypes = new HashSet<>();
        for (int index = 0; index < expectedTypes.length; index++) {
            assertEquals(expectedTypes[index], trail.at("/" + index + "/eventType").asText());
            distinctTypes.add(trail.at("/" + index + "/eventType").asText());
        }
        assertEquals(7, distinctTypes.size(), "no two facts share an event type");

        JsonNode escalation = trail.at("/3");
        assertEquals(clinician, escalation.at("/recipient").asText(),
                "the notified recipient is tracked on the attempt");
        assertEquals(PRIMARY, escalation.at("/channel").asText(),
                "the approved channel used for the attempt is tracked");
        assertEquals("delivered", escalation.at("/outcome").asText());

        assertEquals("7.2", trail.at("/4/detail").asText(),
                "read-back records the exact value the receiver repeated");
        assertEquals(clinician, trail.at("/4/recipient").asText());

        JsonNode acknowledgment = trail.at("/5");
        assertEquals("3", acknowledgment.at("/criticalResultVersion").asText(),
                "acknowledgment is bound to the exact revision the clinician read");
        assertEquals(actorId(org, clinician), acknowledgment.at("/actorId").asText(),
                "the acknowledging clinician is the event actor");
        assertEquals("follow_up", trail.at("/6/eventType").asText(), "follow-up closes the ordered trail");
    }

    @Test
    void acknowledgmentWithoutADeliveredAttemptIsRefused() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";
        String id = text(register(org, lab, false), "/id");
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());

        // A patient release is a separate fact and never satisfies a delivery or an acknowledgment.
        assertEquals(HttpStatus.OK, release(org, admin, id, 3).getStatusCode());
        assertEquals("awaiting_ack", text(get(org, admin, id), "/result/state"),
                "release does not acknowledge the clinical alert");

        ResponseEntity<String> refused = acknowledge(org, clinician, id, 3);
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("NO_DELIVERED_ATTEMPT", text(refused, "/code"));
        JsonNode trail = json(get(org, admin, id)).at("/events");
        assertEquals("patient_released", trail.at("/3/eventType").asText());
        assertFalse(containsType(trail, "acknowledged"), "delivery and release never create an acknowledgment");
    }

    @Test
    void readBackIsRequiredBeforeAcknowledgmentIsRefused() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";
        String id = text(register(org, lab, true), "/id");
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());
        assertEquals(HttpStatus.OK, escalate(org, admin, id, 3, PRIMARY, clinician, "delivered", false)
                .getStatusCode());

        ResponseEntity<String> refused = acknowledge(org, clinician, id, 3);
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("READ_BACK_REQUIRED", text(refused, "/code"));
        assertEquals("awaiting_ack", text(get(org, admin, id), "/result/state"));
        assertFalse(containsType(json(get(org, admin, id)).at("/events"), "acknowledged"));
    }

    @Test
    void readBackIsRefusedWhenNotRequired() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";
        String id = text(register(org, lab, false), "/id");
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());
        assertEquals(HttpStatus.OK, escalate(org, admin, id, 3, PRIMARY, clinician, "delivered", false)
                .getStatusCode());

        ResponseEntity<String> refused = readBack(org, clinician, id, 3);
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("READ_BACK_NOT_REQUIRED", text(refused, "/code"));
        assertFalse(containsType(json(get(org, admin, id)).at("/events"), "read_back"));
    }

    @Test
    void aVerifierCannotAcknowledgeAndAPeerTenantCannotReadTheTrail() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";
        String id = text(register(org, lab, true), "/id");
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        assertEquals(HttpStatus.OK, verify(org, lab, id, 2).getStatusCode());
        assertEquals(HttpStatus.OK, escalate(org, admin, id, 3, PRIMARY, clinician, "delivered", false)
                .getStatusCode());
        assertEquals(HttpStatus.OK, readBack(org, clinician, id, 3).getStatusCode());

        ResponseEntity<String> verifierDenied = acknowledge(org, lab, id, 3);
        assertEquals(HttpStatus.FORBIDDEN, verifierDenied.getStatusCode(),
                "a verifier may not acknowledge a critical result");
        assertEquals("FORBIDDEN", text(verifierDenied, "/code"));
        ResponseEntity<String> peerDenied = get(ORG_B, "clinician-2222", id);
        assertEquals(HttpStatus.NOT_FOUND, peerDenied.getStatusCode(),
                "a peer tenant cannot read another tenant's event trail");
        assertEquals("NOT_FOUND", text(peerDenied, "/code"));
        assertEquals(HttpStatus.OK, acknowledge(org, clinician, id, 3).getStatusCode(),
                "the same command succeeds for the authorized clinician");
    }

    @Test
    void staleVersionCannotAdvanceTheTrail() {
        String org = ORG_A;
        String lab = "lab_verifier-1111";
        String clinician = "clinician-1111";
        String admin = "admin-1111";
        String id = text(register(org, lab, false), "/id");

        ResponseEntity<String> staleAssign = assign(org, admin, id, 9, clinician, future());
        assertEquals(412, staleAssign.getStatusCode().value());
        assertEquals("STALE_VERSION", text(staleAssign, "/code"));
        assertEquals("unverified", text(get(org, admin, id), "/result/state"));
        assertEquals(HttpStatus.OK, assign(org, admin, id, 1, clinician, future()).getStatusCode());
        ResponseEntity<String> staleVerify = verify(org, lab, id, 9);
        assertEquals(412, staleVerify.getStatusCode().value());
        assertEquals("STALE_VERSION", text(staleVerify, "/code"));
        assertEquals("unverified", text(get(org, admin, id), "/result/state"),
                "a stale precondition leaves the trail unchanged");
        assertFalse(containsType(json(get(org, admin, id)).at("/events"), "verified"));
    }

    /** Send a command or read as a synthetic subject in the given organization. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    /** Send a command with extra headers such as an If-Match version precondition. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Register a synthetic critical result with the given read-back requirement. */
    private ResponseEntity<String> register(String org, String subject, boolean readBack) {
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"sourceSystem\":\"LAB\","
                + "\"sourceId\":\"OBS-1\",\"resultSummary\":\"synthetic critical finding\","
                + "\"observedValue\":\"7.2\",\"unit\":\"mmol/L\",\"readBackRequired\":" + readBack + "}";
        return send(HttpMethod.POST, "/api/v1/critical-results", body, subject, org);
    }

    /** Assign the responsible owner and a future acknowledgment deadline. */
    private ResponseEntity<String> assign(String org, String subject, String id, int version,
                                          String owner, String dueAt) {
        String body = "{\"ownerSubject\":\"" + owner + "\",\"dueAt\":\"" + dueAt + "\"}";
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/assign", body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    /** Record the verifier's confirmation at the given version. */
    private ResponseEntity<String> verify(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/verify",
                "{\"note\":\"synthetic verification\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    /** Record one escalation notification attempt at the given version. */
    private ResponseEntity<String> escalate(String org, String subject, String id, int version,
                                            String channel, String recipient, String outcome, boolean systemDown) {
        String body = "{\"channel\":\"" + channel + "\",\"recipient\":\"" + recipient + "\","
                + "\"outcome\":\"" + outcome + "\",\"detail\":\"synthetic notification\","
                + "\"systemDown\":" + systemDown + "}";
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/escalate", body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    /** Record a verbatim read-back at the given version. */
    private ResponseEntity<String> readBack(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/read-back",
                "{\"repeatedValue\":\"7.2\"}", subject, org, Map.of("If-Match", String.valueOf(version)));
    }

    /** Acknowledge the alert as the given subject at the given version. */
    private ResponseEntity<String> acknowledge(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/acknowledge",
                "{\"note\":\"synthetic acknowledgment\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    /** Record clinical follow-up at the given version. */
    private ResponseEntity<String> followUp(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/follow-up",
                "{\"note\":\"synthetic follow-up\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    /** Record a patient release at the given version. */
    private ResponseEntity<String> release(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/critical-results/" + id + "/release",
                "{\"note\":\"synthetic patient release\"}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    /** Read one critical result and its trail as the given subject. */
    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/critical-results/" + id, null, subject, org);
    }

    /** Return true when the ordered trail contains an event of the given type. */
    private static boolean containsType(JsonNode trail, String type) {
        assertTrue(trail.isArray() && !trail.isEmpty(), "event trail must be present and non-empty");
        for (JsonNode event : trail) {
            if (type.equals(event.at("/eventType").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Derive the subject suffix shared by every fixture of an organization. */
    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    /** Resolve the synthetic patient's opaque id from the database under the tenant context. */
    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Resolve a synthetic subject's database actor id under the tenant context. */
    private static String actorId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity WHERE subject = ?")) {
                statement.setString(1, subject);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Bind the tenant and actor transaction-local settings used by forced RLS. */
    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Build a future ISO-8601 acknowledgment deadline. */
    private static String future() {
        return Instant.now().plus(1, ChronoUnit.HOURS).toString();
    }

    /** Read a scalar field from a JSON response, failing when the pointer is absent. */
    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    /** Parse a response body as a JSON tree. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
