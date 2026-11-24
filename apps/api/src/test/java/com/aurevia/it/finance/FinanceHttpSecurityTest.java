package com.aurevia.it.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * HTTP security contract for every FinanceController route (FIN-01).
 *
 * <p>Each endpoint is exercised with no subject (401), a malformed active
 * organization (422) and a role without finance permission (403). A final
 * chained test drives every route with an allowed subject and asserts the
 * success status. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_finance_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class FinanceHttpSecurityTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_finance_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String FINANCE = "finance-1111";
    private static final String RECEPTION = "reception-1111";
    private static final String APPROVER = "admin-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_finance_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_finance_http_it OWNER aurevia_migrate");
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
            seedMember(statement, FINANCE, "finance");
            seedMember(statement, RECEPTION, "reception");
            seedMember(statement, APPROVER, "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_finance_http_it WITH (FORCE)");
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

    private static String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (java.sql.PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, FINANCE);
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
        return "{\"patientId\":\"" + patientId() + "\",\"currency\":\"usd\","
                + "\"serviceReference\":\"ENCOUNTER-HTTP\",\"items\":"
                + "[{\"description\":\"consultation\",\"quantity\":1,\"unitAmountMinor\":500}]}";
    }

    private static String paymentBody() {
        return "{\"amountMinor\":100,\"reference\":\"PAY-" + UUID.randomUUID() + "\"}";
    }

    private static String approvedBody() {
        return "{\"amountMinor\":100,\"reference\":\"ENT-" + UUID.randomUUID()
                + "\",\"reason\":\"synthetic write-off\",\"approverSubject\":\"" + APPROVER + "\"}";
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("createInvoice", HttpMethod.POST, "/api/v1/finance/invoices", createBody(), null),
                new Endpoint("issueInvoice", HttpMethod.POST, "/api/v1/finance/invoices/" + ID + "/issue", "{}", "1"),
                new Endpoint("payment", HttpMethod.POST, "/api/v1/finance/invoices/" + ID + "/payments", paymentBody(), "2"),
                new Endpoint("credit", HttpMethod.POST, "/api/v1/finance/invoices/" + ID + "/credits", approvedBody(), "2"),
                new Endpoint("refund", HttpMethod.POST, "/api/v1/finance/invoices/" + ID + "/refunds", approvedBody(), "2"),
                new Endpoint("voidInvoice", HttpMethod.POST, "/api/v1/finance/invoices/" + ID + "/void",
                        "{\"reason\":\"synthetic cancellation\",\"approverSubject\":\"" + APPROVER + "\"}", "1"),
                new Endpoint("getInvoice", HttpMethod.GET, "/api/v1/finance/invoices/" + ID, null, null),
                new Endpoint("reconciliation", HttpMethod.GET, "/api/v1/finance/reconciliation", null, null));
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

    private ResponseEntity<String> command(String operation, String id, String body, String ifMatch) {
        String path = "/api/v1/finance/invoices/" + id + "/" + operation;
        Endpoint endpoint = new Endpoint(operation, HttpMethod.POST, path, body, ifMatch);
        return send(endpoint, FINANCE, ORG);
    }

    @ParameterizedTest(name = "{0}: no subject is rejected")
    @MethodSource("endpoints")
    void missingSubjectReturnsUnauthorized(Endpoint endpoint) {
        assertEquals(401, send(endpoint, null, ORG).getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: malformed active organization is rejected")
    @MethodSource("endpoints")
    void malformedOrganizationReturnsUnprocessable(Endpoint endpoint) {
        assertEquals(422, send(endpoint, FINANCE, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: role without finance permission is denied")
    @MethodSource("endpoints")
    void unauthorizedRoleReturnsForbidden(Endpoint endpoint) {
        assertEquals(403, send(endpoint, RECEPTION, ORG).getStatusCode().value(), endpoint.name());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        Endpoint create = endpoint("createInvoice");
        ResponseEntity<String> created = send(create, FINANCE, ORG);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String first = tree(created).at("/invoice/id").asText();

        assertEquals(200, command("issue", first, "{}", "1").getStatusCode().value());

        String paid = tree(send(create, FINANCE, ORG)).at("/invoice/id").asText();
        assertEquals(200, command("issue", paid, "{}", "1").getStatusCode().value());
        assertEquals(200, command("payments", paid, paymentBody(), "2").getStatusCode().value());
        assertEquals(200, command("refunds", paid, approvedBody(), "3").getStatusCode().value());

        String credited = tree(send(create, FINANCE, ORG)).at("/invoice/id").asText();
        assertEquals(200, command("issue", credited, "{}", "1").getStatusCode().value());
        assertEquals(200, command("credits", credited, approvedBody(), "2").getStatusCode().value());

        String voided = tree(send(create, FINANCE, ORG)).at("/invoice/id").asText();
        assertEquals(200, command("void", voided,
                "{\"reason\":\"synthetic cancellation\",\"approverSubject\":\"" + APPROVER + "\"}", "1")
                .getStatusCode().value());

        assertEquals(200, send(new Endpoint("getInvoice", HttpMethod.GET,
                "/api/v1/finance/invoices/" + first, null, null), FINANCE, ORG).getStatusCode().value());
        assertEquals(200, send(endpoint("reconciliation"), FINANCE, ORG).getStatusCode().value());
    }

    private static Endpoint endpoint(String name) {
        return endpoints().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse finance response", exception);
        }
    }
}
