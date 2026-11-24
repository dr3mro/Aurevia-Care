package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * HTTP security contract for the PrescriptionController and DrugCatalogController
 * routes (RX-01).
 *
 * <p>Each endpoint is exercised with no subject (401), a malformed active
 * organization (422) and a role without prescribing permission (403). A final
 * test drives every route with an allowed subject and asserts the success
 * status. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_prescription_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class PrescriptionHttpSecurityTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_prescription_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN = "clinician-1111";
    private static final String RECEPTION = "reception-1111";
    private static final String CIPRO = "aaaa1111-0000-0000-0000-000000000004";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_prescription_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_prescription_http_it OWNER aurevia_migrate");
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
            seedMember(statement, CLINICIAN, "clinician");
            seedMember(statement, RECEPTION, "reception");
            seedCatalog(statement);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_prescription_http_it WITH (FORCE)");
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

    private static void seedCatalog(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG + "'");
        statement.execute("SET app.actor = '" + CLINICIAN + "'");
        statement.execute("INSERT INTO public.drug_catalog_entry "
                + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                + " strength_value, strength_unit, substance_ids, substance_groups, group_versions, "
                + " approved, effective_from, approval_provenance) "
                + "VALUES ('" + ORG + "', '" + CIPRO + "', 1, 'Ciprofloxacin', 'Ciproxin', 'tablet', "
                + "'500', 'mg', ARRAY['CIPRO-SUB']::text[], ARRAY['QUINOLONE']::text[], ARRAY[1]::integer[], "
                + "true, now() - interval '1 day', 'synthetic-market-v1')");
    }

    private static String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, CLINICIAN);
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
        return "{\"patientId\":\"" + patientId() + "\",\"encounterReference\":\"synthetic-encounter\","
                + "\"destination\":\"synthetic-clinic\"}";
    }

    private static String checkBody() {
        return "{\"catalogEntryId\":\"" + CIPRO + "\"}";
    }

    private static String selectBody() {
        return "{\"catalogEntryId\":\"" + CIPRO + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                + "\"route\":\"oral\",\"frequency\":\"once daily\",\"quantity\":\"10\","
                + "\"quantityUnit\":\"tablet\",\"refills\":2,\"instructions\":\"synthetic instruction\"}";
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("createDraft", HttpMethod.POST, "/api/v1/prescription-drafts", createBody(), null),
                new Endpoint("getDraft", HttpMethod.GET, "/api/v1/prescription-drafts/" + ID, null, null),
                new Endpoint("allergyCheck", HttpMethod.POST,
                        "/api/v1/prescription-drafts/" + ID + "/allergy-check", checkBody(), null),
                new Endpoint("selectMedication", HttpMethod.POST,
                        "/api/v1/prescription-drafts/" + ID + "/select-medication", selectBody(), "1"),
                new Endpoint("searchCatalog", HttpMethod.GET, "/api/v1/drug-catalog/search?query=cipro", null, null));
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
        assertEquals(422, send(endpoint, CLINICIAN, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: role without prescribing permission is denied")
    @MethodSource("endpoints")
    void unauthorizedRoleReturnsForbidden(Endpoint endpoint) {
        assertEquals(403, send(endpoint, RECEPTION, ORG).getStatusCode().value(), endpoint.name());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        ResponseEntity<String> created = send(endpoint("createDraft"), CLINICIAN, ORG);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = tree(created).at("/id").asText();

        assertEquals(200, send(new Endpoint("getDraft", HttpMethod.GET,
                "/api/v1/prescription-drafts/" + id, null, null), CLINICIAN, ORG).getStatusCode().value());
        assertEquals(200, send(new Endpoint("allergyCheck", HttpMethod.POST,
                "/api/v1/prescription-drafts/" + id + "/allergy-check", checkBody(), null),
                CLINICIAN, ORG).getStatusCode().value());
        assertEquals(200, send(new Endpoint("selectMedication", HttpMethod.POST,
                "/api/v1/prescription-drafts/" + id + "/select-medication", selectBody(), "1"),
                CLINICIAN, ORG).getStatusCode().value());
        assertEquals(200, send(endpoint("searchCatalog"), CLINICIAN, ORG).getStatusCode().value());
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
