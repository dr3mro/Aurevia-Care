package com.aurevia.it.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.units.InMemoryMessagingStore;
import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP security contract for every ConversationController route (MSG-02).
 *
 * <p>Each endpoint is exercised with no subject (401), a malformed active
 * organization (422) and a subject without an active membership (403). A final
 * chained test drives every route with an allowed subject and asserts the
 * success status. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_conversation_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ConversationHttpSecurityTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_conversation_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PATIENT = "patient-1111";
    private static final String LAB_STAFF = "lab-a-1111";
    private static final String ADMIN_SUBJECT = "admin-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Endpoint(String name, HttpMethod method, String path, String body, String ifMatch) {
    }

    private RestClient rest;

    @Autowired
    private InMemoryMessagingStore messagingStore;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        messagingStore.addMember(ORG, PATIENT, "patient", true);
        messagingStore.addMember(ORG, LAB_STAFF, "lab_verifier", true);
        messagingStore.addMember(ORG, ADMIN_SUBJECT, "tenant_admin", true);
    }

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_conversation_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_conversation_http_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
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
            seedMember(statement, PATIENT, "patient");
            seedMember(statement, LAB_STAFF, "lab_verifier");
            seedMember(statement, ADMIN_SUBJECT, "tenant_admin");
            statement.execute("INSERT INTO public.patient_link "
                    + "(organization_id, subject, patient_id, active, created_by) "
                    + "SELECT '" + ORG + "', '" + PATIENT + "', id, true, "
                    + "(SELECT id FROM public.user_identity WHERE subject = 'bootstrap-1111') "
                    + "FROM public.patient_record WHERE mrn = 'MRN-1'");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_conversation_http_it WITH (FORCE)");
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

    private String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, PATIENT);
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

    private String seedConnection(String unit) {
        UnitKey key = UnitKey.from(unit);
        String unitId = UUID.nameUUIDFromBytes(
                ("conversation-unit:" + key.wire()).getBytes(StandardCharsets.UTF_8)).toString();
        String connectionId = UUID.nameUUIDFromBytes(
                ("conversation-connection:" + ORG + ":" + unit).getBytes(StandardCharsets.UTF_8)).toString();
        String patient = patientId();
        messagingStore.addMember(ORG, PATIENT, "patient", true);
        messagingStore.linkPatient(ORG, PATIENT, patient);
        messagingStore.addUnit(new CareUnit(unitId, ORG, "facility-" + unit, "Synthetic " + unit,
                List.of("messages"), true, unit));
        messagingStore.insertConnection(ORG, new PatientUnitConnection(connectionId, ORG, patient, unitId,
                ConnectionContext.ORDER, "order-ref-" + unit, "rel-" + unit, ConnectionState.ACTIVE,
                PATIENT, Instant.now(), Instant.now(), null));
        return connectionId;
    }

    private String openBody(String unit) {
        String connectionId = seedConnection(unit);
        return "{\"patientId\":\"" + patientId() + "\",\"patientSubject\":\"" + PATIENT
                + "\",\"unitKey\":\"" + unit + "\",\"connectionId\":\"" + connectionId
                + "\",\"topic\":\"follow up\"}";
    }

    private static Stream<Endpoint> endpoints() {
        String ID = "aaaaaaaa-0000-0000-0000-000000000001";
        String createBody = "{\"patientId\":\"" + ID + "\",\"patientSubject\":\"patient-1111\","
                + "\"unitKey\":\"lab\",\"connectionId\":\"" + ID + "\",\"topic\":\"follow up\"}";
        return Stream.of(
                new Endpoint("open", HttpMethod.POST, "/api/v1/conversations", createBody, null),
                new Endpoint("inbox", HttpMethod.GET, "/api/v1/conversations/inbox", null, null),
                new Endpoint("workQueue", HttpMethod.GET, "/api/v1/conversations/work-queue", null, null),
                new Endpoint("get", HttpMethod.GET, "/api/v1/conversations/" + ID, null, null),
                new Endpoint("reply", HttpMethod.POST, "/api/v1/conversations/" + ID + "/reply",
                        "{\"body\":\"synthetic reply\"}", "1"),
                new Endpoint("assign", HttpMethod.POST, "/api/v1/conversations/" + ID + "/assign",
                        "{\"assigneeSubject\":\"lab-a-1111\",\"reason\":\"cover\"}", "1"),
                new Endpoint("transfer", HttpMethod.POST, "/api/v1/conversations/" + ID + "/transfer",
                        "{\"toSubject\":\"lab-a-1111\",\"reason\":\"handover\"}", "2"),
                new Endpoint("resolve", HttpMethod.POST, "/api/v1/conversations/" + ID + "/resolve",
                        "{\"reason\":\"answered\"}", "3"),
                new Endpoint("close", HttpMethod.POST, "/api/v1/conversations/" + ID + "/close",
                        "{\"reason\":\"done\"}", "5"),
                new Endpoint("read", HttpMethod.POST, "/api/v1/conversations/" + ID + "/read", null, "2"));
    }

    private HttpHeaders headers(String subject, String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(Endpoint endpoint, String subject, String organization) {
        return send(endpoint, subject, organization, endpoint.ifMatch());
    }

    private ResponseEntity<String> send(Endpoint endpoint, String subject, String organization, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(endpoint.method()).uri(endpoint.path())
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, ifMatch)));
        RestClient.RequestHeadersSpec<?> headerSpec = endpoint.body() == null ? spec : spec.body(endpoint.body());
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    @ParameterizedTest(name = "{0}: no subject is rejected")
    @MethodSource("endpoints")
    void missingSubjectReturnsUnauthorized(Endpoint endpoint) {
        assertEquals(401, send(endpoint, null, ORG).getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: malformed active organization is rejected")
    @MethodSource("endpoints")
    void malformedOrganizationReturnsUnprocessable(Endpoint endpoint) {
        assertEquals(422, send(endpoint, PATIENT, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: subject without active membership is denied")
    @MethodSource("endpoints")
    void unauthorizedRoleReturnsForbidden(Endpoint endpoint) {
        assertEquals(403, send(endpoint, "ghost-1111", ORG).getStatusCode().value(), endpoint.name());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        ResponseEntity<String> opened = send(new Endpoint("open", HttpMethod.POST, "/api/v1/conversations",
                openBody("lab"), null), PATIENT, ORG);
        assertEquals(201, opened.getStatusCode().value(), opened.getBody());
        String id = tree(opened).at("/id").asText();

        assertEquals(200, send(new Endpoint("get", HttpMethod.GET,
                "/api/v1/conversations/" + id, null, null), PATIENT, ORG).getStatusCode().value());
        assertEquals(200, command(id, "reply", "{\"body\":\"please bring your results\"}", "1", LAB_STAFF));
        assertEquals(200, command(id, "read", null, "2", PATIENT));
        assertEquals(200, send(new Endpoint("inbox", HttpMethod.GET,
                "/api/v1/conversations/inbox", null, null), PATIENT, ORG).getStatusCode().value());
        assertEquals(200, command(id, "resolve", "{\"reason\":\"answered\"}", "3", LAB_STAFF));
        assertEquals(200, command(id, "reply", "{\"body\":\"one more question\"}", "4", PATIENT));
        assertEquals(200, command(id, "close", "{\"reason\":\"done\"}", "5", LAB_STAFF));
        assertEquals(200, send(new Endpoint("workQueue", HttpMethod.GET,
                "/api/v1/conversations/work-queue", null, null), LAB_STAFF, ORG).getStatusCode().value());

        ResponseEntity<String> second = send(new Endpoint("open", HttpMethod.POST, "/api/v1/conversations",
                openBody("lab"), null), PATIENT, ORG);
        assertEquals(201, second.getStatusCode().value(), second.getBody());
        String secondId = tree(second).at("/id").asText();
        assertEquals(200, command(secondId, "assign",
                "{\"assigneeSubject\":\"lab-a-1111\",\"reason\":\"cover\"}", "1", LAB_STAFF));
        assertEquals(200, command(secondId, "transfer",
                "{\"toSubject\":\"lab-a-1111\",\"reason\":\"handover\"}", "2", LAB_STAFF));
    }

    private int command(String id, String operation, String body, String version, String subject) {
        return send(new Endpoint(operation, HttpMethod.POST,
                "/api/v1/conversations/" + id + "/" + operation, body, version), subject, ORG)
                .getStatusCode().value();
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse conversation response", exception);
        }
    }

    /**
     * Keep this HTTP security contract on the deterministic directory it was
     * written for. The production bean is
     * {@link com.aurevia.messaging.units.JdbcMessagingStore}; a primary
     * test bean overrides it here.
     */
    @TestConfiguration
    static class SyntheticDirectory {
        /**
         * @return the deterministic, test-only messaging directory
         */
        @Bean
        @Primary
        InMemoryMessagingStore inMemoryMessagingStore() {
            return new InMemoryMessagingStore();
        }
    }
}
