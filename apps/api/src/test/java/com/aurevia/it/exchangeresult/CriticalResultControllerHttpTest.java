package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP security contract for every {@code CriticalResultController} route (XCH-05, ARC-03, ARC-04).
 *
 * <p>Each endpoint is exercised with no subject (401), a subject without an
 * active membership (403) and a malformed active organization (422). A final
 * chained test drives the real critical-result workflow with allowed subjects
 * and asserts the documented success statuses. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_criticalhttp_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CriticalResultControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_criticalhttp_it";
    private static final String DB = "orevia_criticalhttp_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String LAB = "lab_verifier-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String ADMIN_SUBJECT = "admin-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PRIMARY = "SECURE_MESSAGE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Endpoint(String name, HttpMethod method, String path, String body, String ifMatch) {
    }

    private RestClient rest;
    private String patientId;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientId = patientId();
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG + "', 'Synthetic 1111')");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG + "', 'bootstrap-1111', 'Bootstrap')");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                    + "'synthetic patient', '1990-05-20')");
            seedMember(statement, LAB, "lab_verifier");
            seedMember(statement, CLINICIAN, "clinician");
            seedMember(statement, "backup-1111", "clinician");
            seedMember(statement, ADMIN_SUBJECT, "tenant_admin");
            seedMember(statement, "auditor-1111", "privacy_auditor");
            seedMember(statement, "reception-1111", "reception");
            seedPolicy(statement, "critical.escalation.primary_channel", PRIMARY);
            seedPolicy(statement, "critical.escalation.backup_channel", "ONCALL_PHONE");
            seedPolicy(statement, "critical.escalation.backup_recipient", "backup-1111");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedMember(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG + "'");
        statement.execute("SET app.actor = 'bootstrap-1111'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPolicy(Statement statement, String key, String value) throws Exception {
        statement.execute("SET app.current_org = '" + ORG + "'");
        statement.execute("SET app.actor = '" + ADMIN_SUBJECT + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + ORG + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + ORG + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    private static String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, LAB);
                bind.execute();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                return result.next() ? result.getString(1) : ID;
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private String registerBody() {
        return "{\"patientId\":\"" + patientId + "\",\"sourceSystem\":\"LAB\",\"sourceId\":\"OBS-HTTP\","
                + "\"orderReference\":\"ORD-HTTP\",\"accession\":\"ACC-HTTP\","
                + "\"resultSummary\":\"synthetic critical finding\",\"observedValue\":\"7.2\","
                + "\"unit\":\"mmol/L\",\"readBackRequired\":true}";
    }

    private static String future() {
        return Instant.now().plus(1, ChronoUnit.DAYS).toString();
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("register", HttpMethod.POST, "/api/v1/critical-results",
                        registerBodyStatic(), null),
                new Endpoint("report", HttpMethod.GET, "/api/v1/critical-results/report", null, null),
                new Endpoint("get", HttpMethod.GET, "/api/v1/critical-results/" + ID, null, null),
                new Endpoint("verify", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/verify",
                        "{\"note\":\"synthetic verification\"}", "1"),
                new Endpoint("assign", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/assign",
                        "{\"ownerSubject\":\"clinician-1111\",\"dueAt\":\"" + future() + "\"}", "1"),
                new Endpoint("escalate", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/escalate",
                        "{\"channel\":\"SECURE_MESSAGE\",\"recipient\":\"clinician-1111\","
                                + "\"outcome\":\"delivered\",\"detail\":\"synthetic notification\","
                                + "\"systemDown\":false}", "1"),
                new Endpoint("readBack", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/read-back",
                        "{\"repeatedValue\":\"7.2\"}", "1"),
                new Endpoint("acknowledge", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/acknowledge",
                        "{\"note\":\"synthetic acknowledgment\"}", "1"),
                new Endpoint("followUp", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/follow-up",
                        "{\"note\":\"synthetic follow-up\"}", "1"),
                new Endpoint("release", HttpMethod.POST, "/api/v1/critical-results/" + ID + "/release",
                        "{\"note\":\"synthetic release\"}", "1"));
    }

    /**
     * A UUID-shaped patient id is enough for boundary validation; the real
     * seeded id is injected by the instance registerBody helper for the success path.
     */
    private static String registerBodyStatic() {
        return "{\"patientId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\",\"sourceSystem\":\"LAB\","
                + "\"sourceId\":\"OBS-HTTP\",\"resultSummary\":\"synthetic critical finding\","
                + "\"observedValue\":\"7.2\",\"unit\":\"mmol/L\",\"readBackRequired\":true}";
    }

    private ResponseEntity<String> send(Endpoint endpoint, String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (endpoint.ifMatch() != null) {
            headers.set("If-Match", endpoint.ifMatch());
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(endpoint.method()).uri(endpoint.path())
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = endpoint.body() == null ? spec : spec.body(endpoint.body());
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> command(String id, String action, String body, int version, String subject) {
        return send(new Endpoint(action, HttpMethod.POST, "/api/v1/critical-results/" + id + "/" + action,
                body, String.valueOf(version)), subject, ORG);
    }

    @ParameterizedTest(name = "{0}: no subject is rejected")
    @MethodSource("endpoints")
    void missingSubjectReturnsUnauthorized(Endpoint endpoint) {
        assertEquals(401, send(endpoint, null, ORG).getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: subject without active membership is denied")
    @MethodSource("endpoints")
    void unauthorizedRoleReturnsForbidden(Endpoint endpoint) {
        assertEquals(403, send(endpoint, "ghost-1111", ORG).getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: malformed active organization is rejected")
    @MethodSource("endpoints")
    void malformedOrganizationReturnsUnprocessable(Endpoint endpoint) {
        assertEquals(422, send(endpoint, LAB, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @Test
    void invalidBodyReturnsUnprocessableWithErrorCode() {
        ResponseEntity<String> response = send(new Endpoint("invalidRegister", HttpMethod.POST,
                "/api/v1/critical-results", "{}", null), LAB, ORG);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"INVALID_PATIENTID\""), response.getBody());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        ResponseEntity<String> created = send(new Endpoint("register", HttpMethod.POST,
                "/api/v1/critical-results", registerBody(), null), LAB, ORG);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = tree(created).at("/id").asText();

        assertEquals(200, command(id, "assign",
                "{\"ownerSubject\":\"clinician-1111\",\"dueAt\":\"" + future() + "\"}", 1, ADMIN_SUBJECT)
                .getStatusCode().value());
        assertEquals(200, command(id, "verify", "{\"note\":\"synthetic verification\"}", 2, LAB)
                .getStatusCode().value());
        assertEquals(200, command(id, "escalate",
                "{\"channel\":\"SECURE_MESSAGE\",\"recipient\":\"clinician-1111\","
                        + "\"outcome\":\"delivered\",\"detail\":\"synthetic notification\",\"systemDown\":false}",
                3, ADMIN_SUBJECT).getStatusCode().value());
        assertEquals(200, command(id, "read-back", "{\"repeatedValue\":\"7.2\"}", 3, CLINICIAN)
                .getStatusCode().value());
        assertEquals(200, command(id, "acknowledge", "{\"note\":\"synthetic acknowledgment\"}", 3, CLINICIAN)
                .getStatusCode().value());
        assertEquals(200, command(id, "follow-up", "{\"note\":\"synthetic follow-up\"}", 4, CLINICIAN)
                .getStatusCode().value());

        ResponseEntity<String> another = send(new Endpoint("register", HttpMethod.POST,
                "/api/v1/critical-results", registerBody(), null), LAB, ORG);
        assertEquals(201, another.getStatusCode().value(), another.getBody());
        assertEquals(200, command(tree(another).at("/id").asText(), "release",
                "{\"note\":\"synthetic release\"}", 1, ADMIN_SUBJECT).getStatusCode().value());

        assertEquals(200, send(new Endpoint("get", HttpMethod.GET, "/api/v1/critical-results/" + id,
                null, null), ADMIN_SUBJECT, ORG).getStatusCode().value());
        assertEquals(200, send(new Endpoint("report", HttpMethod.GET, "/api/v1/critical-results/report",
                null, null), ADMIN_SUBJECT, ORG).getStatusCode().value());
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse critical-result response", exception);
        }
    }
}
