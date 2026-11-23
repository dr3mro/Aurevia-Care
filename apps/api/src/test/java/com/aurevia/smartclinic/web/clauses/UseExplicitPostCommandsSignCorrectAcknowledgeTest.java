package com.aurevia.smartclinic.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.api.authorization.CommandCatalog;
import com.aurevia.smartclinic.api.authorization.CommandDefinition;
import com.aurevia.smartclinic.authz.specialty.SpecialtyActions;
import com.aurevia.smartclinic.exchange.critical.CriticalActions;
import com.aurevia.smartclinic.exchange.release.ReleaseActionPolicy;
import com.aurevia.smartclinic.exchange.response.ExchangeResponseActionPolicy;

/**
 * ARC-03 clause 3 (API evidence): sign, correct, acknowledge, release and
 * exchange are explicit POST commands.
 *
 * <p>The catalog-level tests assert that every family named by the clause is a
 * registered command whose HTTP method is POST and that an unregistered command
 * fails closed with {@code 422 UNKNOWN_COMMAND}. The HTTP-level tests drive the
 * release family through its real controller: {@code POST /api/v1/releases}
 * authorizes an immutable revision and {@code POST /api/v1/releases/{id}/correct}
 * supersedes it with a new revision. That route is the only one of the five
 * catalog paths with a live controller at the catalog path; the other four are
 * recorded as path drift in the companion evidence document. Every refusal
 * below asserts that the stored state did not change, not just the status code.
 * All tenants, subjects and revision identifiers are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_arc03_post_commands_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class UseExplicitPostCommandsSignCorrectAcknowledgeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CLINICIAN_A = "clinician-arc03-a";
    private static final String RECEPTION_A = "reception-arc03-a";
    private static final String CLINICIAN_B = "clinician-arc03-b";
    private static final String DATABASE = "smartclinic_arc03_post_commands_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private RestClient rest;

    /** Build a fresh database with the production migrations and synthetic members only. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, CLINICIAN_A, "clinician");
            seedStaff(statement, ORG_A, RECEPTION_A, "reception");
            seedStaff(statement, ORG_B, CLINICIAN_B, "clinician");
            seedPatient(statement, ORG_A, CLINICIAN_A);
            // A second tenant owns a patient with the same local id; forced RLS
            // must keep the other tenant's release invisible to ORG_A.
            seedPatient(statement, ORG_B, CLINICIAN_B);
        }
    }

    /** Remove the scratch database; no fixture is shared between runs. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Bind a new HTTP client for each test on the randomly assigned port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Seed one active member with exactly one role in the named tenant. */
    private static void seedStaff(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + organization + "', 'Org " + subject + "' "
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

    /** Seed one synthetic chart in the named tenant. */
    private static void seedPatient(Statement statement, String organization, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + organization + "', '" + PATIENT + "', 1, 'MRN-" + organization.substring(0, 4)
                + "', 'SITE', 'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /**
     * Every command family named by the clause is a registered POST command.
     *
     * <p>The catalog is the machine-readable registry shared by the authorization
     * gate and the generated client; a family absent from it cannot be invoked at
     * all, and a non-POST entry is rejected by the definition itself.</p>
     */
    @Test
    void syntheticCatalogRegistersAllFiveFamiliesAsPostCommands() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        assertPostCommand(catalog, "signInterpretation", SpecialtyActions.RAD_SIGN_INTERPRETATION,
                "/api/v1/radiology/interpretations/{id}/sign", CommandCatalog.SPECIALTY_POLICY);
        assertPostCommand(catalog, "correctRelease", ReleaseActionPolicy.CORRECT,
                "/api/v1/releases/{id}/correct", CommandCatalog.RELEASE_POLICY);
        assertPostCommand(catalog, "acknowledgeCriticalResult", CriticalActions.ACKNOWLEDGE,
                "/api/v1/critical/{id}/acknowledge", CommandCatalog.CRITICAL_POLICY);
        assertPostCommand(catalog, "authorizeRelease", ReleaseActionPolicy.AUTHORIZE,
                "/api/v1/releases/{id}/authorize", CommandCatalog.RELEASE_POLICY);
        assertPostCommand(catalog, "acceptOrder", ExchangeResponseActionPolicy.EXCHANGE_ACCEPT,
                "/api/v1/orders/{id}/accept", CommandCatalog.EXCHANGE_RESPONSE_POLICY);
        assertPostCommand(catalog, "rejectOrder", ExchangeResponseActionPolicy.EXCHANGE_REJECT,
                "/api/v1/orders/{id}/reject", CommandCatalog.EXCHANGE_RESPONSE_POLICY);
        assertTrue(catalog.commands().stream().allMatch(command -> "POST".equals(command.method())),
                "the catalog only admits explicit POST commands");
    }

    /** Assert one family's registered method, action, route and owning policy. */
    private static void assertPostCommand(CommandCatalog catalog, String id, String action,
                                          String path, String policy) {
        CommandDefinition definition = catalog.find(id).orElseThrow();
        assertEquals("POST", definition.method(), id);
        assertEquals(action, definition.action(), id);
        assertEquals(path, definition.path(), id);
        assertEquals(policy, definition.policy(), id);
    }

    /** A command that is not in the catalog fails closed with a machine-readable 422. */
    @Test
    void unknownCommandIsRefusedWithUnknownCommand() {
        CommandCatalog catalog = CommandCatalog.synthetic();
        assertTrue(catalog.find("signPatientChart").isEmpty());
        ApiException denial = assertThrows(ApiException.class, () -> catalog.require("signPatientChart"));
        assertEquals(422, denial.status());
        assertEquals("UNKNOWN_COMMAND", denial.code());
    }

    /**
     * The catalog still names five sign/acknowledge/release/exchange routes that
     * no controller serves. Asserting the 404 keeps that gap test-enforced rather
     * than merely documented; the live families are exercised below.
     */
    @Test
    void catalogPathsForSignAcknowledgeAndExchangeHaveNoLiveRoute() {
        for (String path : List.of("/api/v1/radiology/interpretations/arc03/sign",
                "/api/v1/critical/arc03/acknowledge",
                "/api/v1/releases/arc03/authorize",
                "/api/v1/orders/arc03/accept",
                "/api/v1/orders/arc03/reject")) {
            assertEquals(HttpStatus.NOT_FOUND,
                    request(HttpMethod.POST, path, "{}", CLINICIAN_A, ORG_A, null).getStatusCode(),
                    path);
        }
    }

    /**
     * The correct command is a real POST route: it changes the serving revision,
     * links the replacement to the original and keeps the decision history.
     */
    @Test
    void correctReleasePostedAtCatalogPathSupersedesOriginal() {
        ResponseEntity<String> created = authorize("ARC03-CORRECT-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String originalId = field(created.getBody(), "id");
        assertTrue(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-CORRECT-1\""));

        ResponseEntity<String> corrected = correct(originalId, "\"1\"", "ARC03-CORRECT-2", 2,
                "synthetic correction", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertEquals(originalId, field(corrected.getBody(), "supersedesId"));
        assertEquals("ARC03-CORRECT-2", field(corrected.getBody(), "revisionId"));
        assertEquals("AUTHORIZED", field(corrected.getBody(), "state"));

        // Read the stored projection back: the replacement serves access and the
        // original stays in the history as CORRECTED rather than being deleted.
        String access = visible(CLINICIAN_A, ORG_A).getBody();
        assertTrue(access.contains("\"ARC03-CORRECT-2\""), access);
        assertFalse(access.contains("\"ARC03-CORRECT-1\""), access);
        assertEquals("CORRECTED", field(get(originalId, CLINICIAN_A, ORG_A).getBody(), "state"));
        assertTrue(history(originalId, CLINICIAN_A, ORG_A).getBody()
                .contains("\"action\":\"RELEASE_CORRECT\""));
    }

    /** The release command family is a real POST route that opens chart visibility. */
    @Test
    void releaseFamilyPostedAtLiveRouteChangesVisibility() {
        ResponseEntity<String> created = authorize("ARC03-RELEASE-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("AUTHORIZED", field(created.getBody(), "state"));
        assertTrue(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-RELEASE-1\""),
                "a posted release must change current chart access");
    }

    /** A role that may read a release may not correct it, and access stays unchanged. */
    @Test
    void wrongRoleCannotCorrectReleaseAndStateIsUnchanged() {
        ResponseEntity<String> created = authorize("ARC03-ROLE-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.FORBIDDEN, correct(id, "\"1\"", "ARC03-ROLE-2", 2,
                "unauthorised", RECEPTION_A, ORG_A).getStatusCode());
        assertEquals("AUTHORIZED", field(get(id, CLINICIAN_A, ORG_A).getBody(), "state"));
        assertTrue(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-ROLE-1\""));
        assertFalse(history(id, CLINICIAN_A, ORG_A).getBody().contains("\"action\":\"RELEASE_CORRECT\""));
    }

    /** A member of another tenant cannot correct the release; forced RLS conceals it. */
    @Test
    void otherTenantCannotCorrectReleaseAndStateIsUnchanged() {
        ResponseEntity<String> created = authorize("ARC03-TENANT-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> refused = correct(id, "\"1\"", "ARC03-TENANT-2", 2,
                "cross tenant", CLINICIAN_B, ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        // A concealed resource must not leak its id or revision in the refusal
        // body either: the response is indistinguishable from a true not-found.
        assertFalse(refused.getBody().contains("ARC03-TENANT-1"), refused.getBody());
        assertFalse(refused.getBody().contains(id), refused.getBody());
        assertEquals("AUTHORIZED", field(get(id, CLINICIAN_A, ORG_A).getBody(), "state"));
        assertTrue(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-TENANT-1\""));
        assertFalse(visible(CLINICIAN_B, ORG_B).getBody().contains("\"ARC03-TENANT-1\""));
    }

    /** A stale version precondition refuses the correction with 412 and no state change. */
    @Test
    void staleVersionCannotCorrectReleaseAndStateIsUnchanged() {
        ResponseEntity<String> created = authorize("ARC03-STALE-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> stale = correct(id, "\"9\"", "ARC03-STALE-2", 2,
                "stale", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals("AUTHORIZED", field(get(id, CLINICIAN_A, ORG_A).getBody(), "state"));
        assertFalse(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-STALE-2\""));
    }

    /** A withdrawn release is terminal: a later correction is refused with 409. */
    @Test
    void forbiddenStateCannotCorrectWithdrawnRelease() {
        ResponseEntity<String> created = authorize("ARC03-STATE-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals(HttpStatus.OK, withdraw(id, "\"1\"", "synthetic retraction",
                CLINICIAN_A, ORG_A).getStatusCode());

        ResponseEntity<String> refused = correct(id, "\"2\"", "ARC03-STATE-2", 2,
                "late correction", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", field(refused.getBody(), "code"));
        assertEquals("WITHDRAWN", field(get(id, CLINICIAN_A, ORG_A).getBody(), "state"));
        assertFalse(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-STATE-2\""));
    }

    /** Missing command input is refused with 422 before any transition is attempted. */
    @Test
    void missingInputCannotCorrectReleaseAndStateIsUnchanged() {
        ResponseEntity<String> created = authorize("ARC03-INPUT-1", CLINICIAN_A, ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> refused = correct(id, "\"1\"", "", 2,
                "synthetic correction", CLINICIAN_A, ORG_A);
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_REVISION_ID", field(refused.getBody(), "code"));
        assertEquals("AUTHORIZED", field(get(id, CLINICIAN_A, ORG_A).getBody(), "state"));
        assertTrue(visible(CLINICIAN_A, ORG_A).getBody().contains("\"ARC03-INPUT-1\""));
    }

    /** Authorize one immutable revision through the real controller. */
    private ResponseEntity<String> authorize(String revision, String actor, String organization) {
        String body = "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"IMMEDIATE\"}";
        return request(HttpMethod.POST, "/api/v1/releases", body, actor, organization, null);
    }

    /** Correct one authorized release at the version just read. */
    private ResponseEntity<String> correct(String id, String ifMatch, String correctedRevision,
                                           int correctedVersion, String reason,
                                           String actor, String organization) {
        String body = "{\"correctedRevisionId\":\"" + correctedRevision + "\",\"correctedRevisionVersion\":"
                + correctedVersion + ",\"reason\":\"" + reason + "\"}";
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/correct",
                body, actor, organization, ifMatch);
    }

    /** Withdraw one authorized release at the version just read. */
    private ResponseEntity<String> withdraw(String id, String ifMatch, String reason,
                                            String actor, String organization) {
        String body = "{\"reason\":\"" + reason + "\"}";
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/withdraw",
                body, actor, organization, ifMatch);
    }

    /** Read the current projection of one release. */
    private ResponseEntity<String> get(String id, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id, null, actor, organization, null);
    }

    /** Read the append-only decision history of one release. */
    private ResponseEntity<String> history(String id, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id + "/history",
                null, actor, organization, null);
    }

    /** Read the revisions currently visible on the synthetic chart. */
    private ResponseEntity<String> visible(String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/patients/" + PATIENT + "/released-revisions",
                null, actor, organization, null);
    }

    /** Send one request with synthetic-auth headers and return the raw response. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject,
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

    /** Read one top-level string field from a JSON response body. */
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
