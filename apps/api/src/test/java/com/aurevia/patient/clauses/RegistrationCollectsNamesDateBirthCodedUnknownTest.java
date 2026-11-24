package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * IAM-03 clause 3: registration collects names, date of birth or a coded unknown
 * reason, contacts, language, identifiers with issuer and verification state,
 * and applicable consent records. This suite drives the HTTP entry point
 * {@code POST /api/v1/patients} against real PostgreSQL with forced row-level
 * security. All fixtures are synthetic; two organizations share local MRN and
 * issuer codes so a cross-tenant read must not expose collected fields.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_reg_fields_it")
@ActiveProfiles("synthetic-auth")
class RegistrationCollectsNamesDateBirthCodedUnknownTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_reg_fields_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    RestClient rest;

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_reg_fields_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_reg_fields_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_B, "reception-b", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_reg_fields_it WITH (FORCE)");
        }
    }

    /** Create an organization, an active membership and one bounded role for a synthetic subject. */
    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Send one registration with every field of the clause, omitting null DOB or reason. */
    private ResponseEntity<String> register(String name, String mrn, String issuer, String dob,
                                            String reason, String language, String contacts,
                                            String identifiers, String consents,
                                            String actor, String org) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("displayName", name);
        fields.put("mrn", mrn);
        fields.put("issuer", issuer);
        if (dob != null) {
            fields.put("dob", dob);
        }
        if (reason != null) {
            fields.put("dobUnknownReason", reason);
        }
        fields.put("language", language);
        fields.put("contacts", contacts);
        fields.put("identifiers", identifiers);
        fields.put("consents", consents);
        return request(HttpMethod.POST, "/api/v1/patients", MAPPER.writeValueAsString(fields), actor, org);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    @Test
    void registrationCollectsDateOfBirthNamesContactsLanguageIdentifiersAndConsents() throws Exception {
        String contacts = "{\"phone\":\"+15550100001\",\"email\":\"amina@example.test\",\"preferred\":\"phone\"}";
        String identifiers = "[{\"system\":\"MRN\",\"value\":\"MRN-20\",\"issuer\":\"CLINIC-A\","
                + "\"verificationState\":\"verified\"},"
                + "{\"system\":\"PASSPORT\",\"value\":\"P-1234\",\"issuer\":\"NAT\","
                + "\"verificationState\":\"unverified\"}]";
        String consents = "[{\"kind\":\"treatment\",\"version\":\"1\",\"recordedAt\":\"2026-01-10T00:00:00Z\"}]";

        ResponseEntity<String> created = register("Amina Khan", "MRN-20", "CLINIC-A", "1988-03-03", null,
                "ar", contacts, identifiers, consents, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        JsonNode body = json(created);
        assertEquals("Amina Khan", body.get("displayName").asText());
        assertEquals("amina khan", body.get("normalizedName").asText());
        assertEquals("MRN-20", body.get("mrn").asText());
        assertEquals("CLINIC-A", body.get("mrnIssuer").asText());
        assertEquals("1988-03-03", body.get("dob").asText());
        assertTrue(body.get("dobUnknownReason").isNull());
        assertEquals("ar", body.get("language").asText());

        JsonNode storedContacts = MAPPER.readTree(body.get("contacts").asText());
        assertEquals("+15550100001", storedContacts.get("phone").asText());
        assertEquals("amina@example.test", storedContacts.get("email").asText());
        assertEquals("phone", storedContacts.get("preferred").asText());

        JsonNode storedIdentifiers = MAPPER.readTree(body.get("identifiers").asText());
        assertEquals(2, storedIdentifiers.size());
        assertEquals("CLINIC-A", storedIdentifiers.get(0).get("issuer").asText());
        assertEquals("verified", storedIdentifiers.get(0).get("verificationState").asText());
        assertEquals("NAT", storedIdentifiers.get(1).get("issuer").asText());
        assertEquals("unverified", storedIdentifiers.get(1).get("verificationState").asText());

        JsonNode storedConsents = MAPPER.readTree(body.get("consents").asText());
        assertEquals(1, storedConsents.size());
        assertEquals("treatment", storedConsents.get(0).get("kind").asText());
        assertEquals("1", storedConsents.get(0).get("version").asText());

        String id = body.get("id").asText();
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/patients/" + id,
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        JsonNode readBack = json(view).get("patient");
        assertEquals("amina@example.test", MAPPER.readTree(readBack.get("contacts").asText()).get("email").asText());
        assertEquals("verified", MAPPER.readTree(readBack.get("identifiers").asText())
                .get(0).get("verificationState").asText());
        assertEquals("treatment", MAPPER.readTree(readBack.get("consents").asText())
                .get(0).get("kind").asText());
    }

    @Test
    void registrationCollectsCodedUnknownReasonWhenDateOfBirthIsUnavailable() throws Exception {
        String contacts = "{\"phone\":\"+15550100002\"}";
        String identifiers = "[{\"system\":\"MRN\",\"value\":\"MRN-21\",\"issuer\":\"CLINIC-B\","
                + "\"verificationState\":\"verified\"}]";
        String consents = "[{\"kind\":\"privacy_notice\",\"version\":\"2\"}]";

        ResponseEntity<String> created = register("Unknown Minor", "MRN-21", "CLINIC-B", null,
                "not_disclosed", "en", contacts, identifiers, consents, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        JsonNode body = json(created);
        assertTrue(body.get("dob").isNull());
        assertEquals("not_disclosed", body.get("dobUnknownReason").asText());
        assertEquals("en", body.get("language").asText());
        assertEquals("+15550100002", MAPPER.readTree(body.get("contacts").asText()).get("phone").asText());
        assertEquals("CLINIC-B", MAPPER.readTree(body.get("identifiers").asText())
                .get(0).get("issuer").asText());
        assertEquals("verified", MAPPER.readTree(body.get("identifiers").asText())
                .get(0).get("verificationState").asText());
        assertEquals("privacy_notice", MAPPER.readTree(body.get("consents").asText())
                .get(0).get("kind").asText());
    }

    @Test
    void registrationRefusesMissingDateOfBirthAndMissingCodedReason() throws Exception {
        assertEquals(422, register("No Dob", "MRN-22", "CLINIC-A", null, null,
                "en", "{}", "[]", "[]", "reception-a", ORG_A).getStatusCode().value());
    }

    @Test
    void registrationRefusesDateOfBirthTogetherWithCodedReason() throws Exception {
        assertEquals(422, register("Both Dob", "MRN-23", "CLINIC-A", "1990-01-01", "not_disclosed",
                "en", "{}", "[]", "[]", "reception-a", ORG_A).getStatusCode().value());
    }

    @Test
    void registrationRefusesMalformedCodedReason() throws Exception {
        assertEquals(422, register("Bad Reason", "MRN-24", "CLINIC-A", null, "BAD-REASON!",
                "en", "{}", "[]", "[]", "reception-a", ORG_A).getStatusCode().value());
    }

    @Test
    void registrationByRoleWithoutPatientPermissionIsDenied() throws Exception {
        assertEquals(HttpStatus.FORBIDDEN,
                register("Auditor", "MRN-25", "CLINIC-A", "1990-01-01", null,
                        "en", "{}", "[]", "[]", "auditor-a", ORG_A).getStatusCode());
    }

    @Test
    void collectedFieldsAreNotVisibleFromAnotherTenant() throws Exception {
        ResponseEntity<String> created = register("Tenant Private", "MRN-26", "CLINIC-A", "1990-01-01", null,
                "en", "{\"phone\":\"+15550100003\"}", "[]", "[]", "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = json(created).get("id").asText();
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/patients/" + id, null, "reception-b", ORG_B).getStatusCode());
    }
}
