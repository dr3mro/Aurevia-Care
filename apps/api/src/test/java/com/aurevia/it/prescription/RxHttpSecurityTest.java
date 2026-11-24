package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * HTTP security contract for every RxController route (RX-02).
 *
 * <p>Each endpoint is exercised with no subject (401), a malformed active
 * organization (422) and a subject without an active membership (403). A final
 * chained test drives every route with an allowed subject and asserts the
 * success status. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_rxhttp_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RxHttpSecurityTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_rxhttp_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PRESCRIBER = "clinician-1111";
    private static final String PHARMACY = "pharmacy-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String SID = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Endpoint(String name, HttpMethod method, String path, String body, String ifMatch) {
    }

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_rxhttp_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_rxhttp_it OWNER aurevia_migrate");
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
            seedMember(statement, PRESCRIBER, "clinician");
            seedMember(statement, PHARMACY, "pharmacy_staff");
            seedMember(statement, "admin-1111", "tenant_admin");
            seedPolicy(statement, "rx02.market", "US");
            seedPolicy(statement, "rx02.entitlement." + PRESCRIBER, "enabled");
            seedPolicy(statement, "rx02.approved_channels", "E_PRESCRIBE,PRINTABLE_LOCAL");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_rxhttp_it WITH (FORCE)");
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
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + ORG + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + ORG + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    private static String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, PRESCRIBER);
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

    private static String createBody() {
        return "{\"prescriptionId\":\"" + UUID.randomUUID() + "\",\"patientId\":\"" + patientId() + "\","
                + "\"medication\":\"amoxicillin\",\"strength\":\"500 mg\","
                + "\"dose\":\"1 capsule\",\"route\":\"oral\",\"frequency\":\"twice daily\","
                + "\"quantity\":30,\"prescriberSubject\":\"" + PRESCRIBER + "\","
                + "\"destinationPharmacy\":\"pharmacy-x\"}";
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("create", HttpMethod.POST, "/api/v1/prescriptions", createBody(), null),
                new Endpoint("get", HttpMethod.GET, "/api/v1/prescriptions/" + ID, null, null),
                new Endpoint("attest", HttpMethod.POST, "/api/v1/prescriptions/" + ID + "/attest",
                        "{\"note\":\"synthetic attestation\"}", "1"),
                new Endpoint("transmit", HttpMethod.POST, "/api/v1/prescriptions/" + ID + "/transmit",
                        "{\"channel\":\"E_PRESCRIBE\",\"outcome\":\"submitted\"}", "2"),
                new Endpoint("confirmDelivery", HttpMethod.POST,
                        "/api/v1/prescriptions/" + ID + "/confirm-delivery",
                        "{\"submissionId\":\"" + SID + "\"}", "3"),
                new Endpoint("verifyPartnerStatus", HttpMethod.POST,
                        "/api/v1/prescriptions/" + ID + "/verify-partner-status",
                        "{\"submissionId\":\"" + SID + "\",\"status\":\"absent\"}", "3"),
                new Endpoint("retry", HttpMethod.POST, "/api/v1/prescriptions/" + ID + "/retry",
                        "{\"submissionId\":\"" + SID + "\",\"outcome\":\"ambiguous\"}", "4"),
                new Endpoint("pharmacyResponse", HttpMethod.POST,
                        "/api/v1/prescriptions/" + ID + "/pharmacy-response",
                        "{\"submissionId\":\"" + SID + "\",\"outcome\":\"accepted\",\"note\":\"synthetic\"}", "4"),
                new Endpoint("dispensing", HttpMethod.POST, "/api/v1/prescriptions/" + ID + "/dispensing",
                        "{\"submissionId\":\"" + SID + "\",\"note\":\"synthetic evidence\"}", "5"),
                new Endpoint("renew", HttpMethod.POST, "/api/v1/prescriptions/" + ID + "/renew",
                        "{\"note\":\"synthetic renewal\"}", "6"),
                new Endpoint("cancel", HttpMethod.POST, "/api/v1/prescriptions/" + ID + "/cancel",
                        "{\"reason\":\"synthetic\"}", "1"));
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
        RestClient.RequestBodySpec spec = rest.method(endpoint.method()).uri(endpoint.path())
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, endpoint.ifMatch())));
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
        assertEquals(422, send(endpoint, PRESCRIBER, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: subject without active membership is denied")
    @MethodSource("endpoints")
    void unauthorizedRoleReturnsForbidden(Endpoint endpoint) {
        assertEquals(403, send(endpoint, "ghost-1111", ORG).getStatusCode().value(), endpoint.name());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        ResponseEntity<String> created = send(endpoint("create"), PRESCRIBER, ORG);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = tree(created).at("/id").asText();

        assertEquals(200, post(id, "attest", "{\"note\":\"synthetic attestation\"}", "1", PRESCRIBER));
        assertEquals(200, post(id, "transmit", "{\"channel\":\"E_PRESCRIBE\",\"outcome\":\"submitted\"}",
                "2", PRESCRIBER));
        String sid = tree(send(new Endpoint("get", HttpMethod.GET, "/api/v1/prescriptions/" + id, null, null),
                PRESCRIBER, ORG)).at("/prescription/submissionId").asText();
        assertEquals(200, post(id, "confirm-delivery", "{\"submissionId\":\"" + sid + "\"}", "3", PRESCRIBER));
        assertEquals(200, send(new Endpoint("get", HttpMethod.GET, "/api/v1/prescriptions/" + id, null, null),
                PRESCRIBER, ORG).getStatusCode().value());
        assertEquals(200, post(id, "pharmacy-response", "{\"submissionId\":\"" + sid
                + "\",\"outcome\":\"accepted\",\"note\":\"synthetic\"}", "4", PHARMACY));
        assertEquals(200, post(id, "dispensing", "{\"submissionId\":\"" + sid
                + "\",\"note\":\"synthetic evidence\"}", "5", PHARMACY));
        assertEquals(200, post(id, "renew", "{\"note\":\"synthetic renewal\"}", "6", PRESCRIBER));

        String ambiguousId = tree(send(endpoint("create"), PRESCRIBER, ORG)).at("/id").asText();
        assertEquals(200, post(ambiguousId, "attest", "{\"note\":\"synthetic attestation\"}", "1", PRESCRIBER));
        assertEquals(200, post(ambiguousId, "transmit",
                "{\"channel\":\"E_PRESCRIBE\",\"outcome\":\"ambiguous\"}", "2", PRESCRIBER));
        String ambiguousSid = tree(send(new Endpoint("get", HttpMethod.GET,
                "/api/v1/prescriptions/" + ambiguousId, null, null), PRESCRIBER, ORG))
                .at("/prescription/submissionId").asText();
        assertEquals(200, post(ambiguousId, "verify-partner-status", "{\"submissionId\":\""
                + ambiguousSid + "\",\"status\":\"absent\"}", "3", PRESCRIBER));
        assertEquals(200, post(ambiguousId, "retry", "{\"submissionId\":\"" + ambiguousSid
                + "\",\"outcome\":\"ambiguous\"}", "4", PRESCRIBER));

        String cancelId = tree(send(endpoint("create"), PRESCRIBER, ORG)).at("/id").asText();
        assertEquals(200, post(cancelId, "cancel", "{\"reason\":\"synthetic\"}", "1", PRESCRIBER));
    }

    private int post(String id, String operation, String body, String version, String subject) {
        Endpoint endpoint = new Endpoint(operation, HttpMethod.POST,
                "/api/v1/prescriptions/" + id + "/" + operation, body, version);
        return send(endpoint, subject, ORG).getStatusCode().value();
    }

    private static Endpoint endpoint(String name) {
        return endpoints().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse prescription response", exception);
        }
    }
}
