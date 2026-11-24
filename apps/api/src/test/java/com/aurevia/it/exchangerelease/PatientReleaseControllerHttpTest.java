package com.aurevia.it.exchangerelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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

/**
 * HTTP security contract for every {@code PatientReleaseController} route (XCH-06, ARC-03, ARC-04).
 *
 * <p>Each endpoint is exercised with no subject (401), a subject without an
 * active membership (403) and a malformed active organization (422). A final
 * chained test drives every route with an allowed subject and asserts the
 * documented success status. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_releaseauth_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PatientReleaseControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_releaseauth_http_it";
    private static final String DB = "aurevia_releaseauth_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CLINICIAN = "clinician-1111";
    private static final String ADMIN_SUBJECT = "admin-1111";
    private static final String ID = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String CORRELATION = "11111111-2222-3333-4444-555555555555";

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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
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
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG + "', '" + PATIENT + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                    + "'synthetic patient', '1990-05-20')");
            seedMember(statement, CLINICIAN, "clinician");
            seedMember(statement, ADMIN_SUBJECT, "tenant_admin");
            seedMember(statement, "reception-1111", "reception");
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

    private static String authorizeBody(String revision) {
        return "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"IMMEDIATE\"}";
    }

    private static String holdBody(String revision) {
        String expiry = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        return "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"HOLD\",\"holdReason\":\"synthetic review\","
                + "\"holdExpiresAt\":\"" + expiry + "\"}";
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("authorize", HttpMethod.POST, "/api/v1/releases",
                        authorizeBody("HTTP-AUTH-A"), null),
                new Endpoint("withdraw", HttpMethod.POST, "/api/v1/releases/" + ID + "/withdraw",
                        "{\"reason\":\"synthetic retraction\"}", "1"),
                new Endpoint("correct", HttpMethod.POST, "/api/v1/releases/" + ID + "/correct",
                        "{\"correctedRevisionId\":\"HTTP-CORR-A\",\"correctedRevisionVersion\":2,"
                                + "\"reason\":\"synthetic correction\"}", "1"),
                new Endpoint("liftHold", HttpMethod.POST, "/api/v1/releases/" + ID + "/lift-hold",
                        "{\"note\":\"synthetic review\"}", "1"),
                new Endpoint("get", HttpMethod.GET, "/api/v1/releases/" + ID, null, null),
                new Endpoint("history", HttpMethod.GET, "/api/v1/releases/" + ID + "/history", null, null));
    }

    private ResponseEntity<String> send(Endpoint endpoint, String subject, String organization) {
        return send(endpoint, subject, organization, null);
    }

    private ResponseEntity<String> send(Endpoint endpoint, String subject, String organization,
            String correlation) {
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
        if (correlation != null) {
            // A canonical UUID is reused by both the correlation filter and the
            // tenant interceptor, so body and response header cannot diverge.
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(endpoint.method()).uri(endpoint.path())
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = endpoint.body() == null ? spec : spec.body(endpoint.body());
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> command(String id, String action, String body, String ifMatch, String subject) {
        return send(new Endpoint(action, HttpMethod.POST, "/api/v1/releases/" + id + "/" + action,
                body, ifMatch), subject, ORG);
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
        assertEquals(422, send(endpoint, CLINICIAN, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @Test
    void emptyBodyReturnsUniformErrorBody() {
        ResponseEntity<String> response = send(new Endpoint("emptyAuthorize", HttpMethod.POST,
                "/api/v1/releases", "", null), CLINICIAN, ORG, CORRELATION);
        assertMalformedBody(response);
    }

    @Test
    void malformedJsonReturnsUniformErrorBody() {
        ResponseEntity<String> response = send(new Endpoint("malformedAuthorize", HttpMethod.POST,
                "/api/v1/releases", "{", null), CLINICIAN, ORG, CORRELATION);
        assertMalformedBody(response);
        // The parser's detail ("Unexpected end-of-input ...") must not leak.
        assertFalse(response.getBody().contains("Unexpected end-of-input"), response.getBody());
    }

    @Test
    void emptyJsonObjectReturnsUniformErrorBody() {
        // An empty JSON object is unreadable by the record creator on this
        // stack, so it takes the binding path rather than the domain path.
        ResponseEntity<String> response = send(new Endpoint("emptyJsonAuthorize", HttpMethod.POST,
                "/api/v1/releases", "{}", null), CLINICIAN, ORG, CORRELATION);
        assertMalformedBody(response);
    }

    private static void assertMalformedBody(ResponseEntity<String> response) {
        assertEquals(400, response.getStatusCode().value(), response.getBody());
        assertEquals("MALFORMED_REQUEST_BODY", field(response.getBody(), "code"), response.getBody());
        assertEquals("request body could not be read", field(response.getBody(), "message"), response.getBody());
        assertEquals(CORRELATION, field(response.getBody(), "correlationId"), response.getBody());
        assertEquals(CORRELATION, response.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void invalidBodyReturnsUnprocessableWithErrorCode() {
        // A syntactically complete but domain-invalid body reaches the service
        // validation and yields 422 with the machine-readable error code. An
        // empty JSON object is rejected earlier by Jackson as a 400.
        String body = "{\"revisionId\":\"\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"IMMEDIATE\"}";
        ResponseEntity<String> response = send(new Endpoint("invalidAuthorize", HttpMethod.POST,
                "/api/v1/releases", body, null), CLINICIAN, ORG);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"INVALID_REVISION_ID\""), response.getBody());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        ResponseEntity<String> created = send(new Endpoint("authorize", HttpMethod.POST,
                "/api/v1/releases", authorizeBody("HTTP-AUTH-1"), null), CLINICIAN, ORG);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        String id = field(created.getBody(), "id");

        assertEquals(200, send(new Endpoint("get", HttpMethod.GET, "/api/v1/releases/" + id,
                null, null), CLINICIAN, ORG).getStatusCode().value());
        assertEquals(200, send(new Endpoint("history", HttpMethod.GET, "/api/v1/releases/" + id + "/history",
                null, null), CLINICIAN, ORG).getStatusCode().value());
        assertEquals(200, command(id, "withdraw", "{\"reason\":\"synthetic retraction\"}", "1", ADMIN_SUBJECT)
                .getStatusCode().value());

        ResponseEntity<String> held = send(new Endpoint("authorize", HttpMethod.POST,
                "/api/v1/releases", holdBody("HTTP-HOLD-1"), null), CLINICIAN, ORG);
        assertEquals(201, held.getStatusCode().value(), held.getBody());
        String holdId = field(held.getBody(), "id");
        assertEquals(200, command(holdId, "lift-hold", "{\"note\":\"synthetic review\"}", "1", ADMIN_SUBJECT)
                .getStatusCode().value());

        ResponseEntity<String> toCorrect = send(new Endpoint("authorize", HttpMethod.POST,
                "/api/v1/releases", authorizeBody("HTTP-CORR-1"), null), CLINICIAN, ORG);
        assertEquals(201, toCorrect.getStatusCode().value(), toCorrect.getBody());
        assertEquals(200, command(field(toCorrect.getBody(), "id"), "correct",
                "{\"correctedRevisionId\":\"HTTP-CORR-2\",\"correctedRevisionVersion\":2,"
                        + "\"reason\":\"synthetic correction\"}", "1", ADMIN_SUBJECT).getStatusCode().value());
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }
}
