package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

/**
 * XCH-06 clause 1 proof through the normal HTTP entry point: a patient release is
 * a separate authorization that binds one immutable report revision (identifier and
 * version), the approved release policy version and the effective instant. The
 * returned authorization and its append-only decision event and notification are
 * asserted together, so the authorization is proven to be its own decision rather
 * than a side effect of publishing a report. Negative cases prove that a role
 * without decide permission, a foreign active organization, malformed references
 * and a correction re-pointed at the same revision are all refused without leaving
 * a release row or a decision record. Synthetic fixtures only; no patient data or
 * credentials are used.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_xch06_release_it")
@ActiveProfiles("synthetic-auth")
class PatientReleaseSeparateAuthorizationReferencingImmutableRevisionTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_xch06_release_it";
    static final String DB = "orevia_xch06_release_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String EFFECTIVE = "2030-01-01T00:00:00Z";

    RestClient rest;

    /** Point the HTTP client at the random port chosen for this test context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate the synthetic database from the authoritative migrations and seed bounded roles. */
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
            seedStaff(statement, ORG_A, "clinician-a", "clinician");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_B, "clinician-b", "clinician");
            // The same local patient id exists in both tenants by design.
            seedPatient(statement, ORG_A, PATIENT, "clinician-a");
            seedPatient(statement, ORG_B, PATIENT, "clinician-b");
        }
    }

    /** Drop the synthetic database so each run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Seed one synthetic tenant member with a bounded role, mirroring the neighbouring fixtures. */
    private static void seedStaff(Statement statement, String org, String subject, String role) throws Exception {
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

    /** Seed a tenant-local patient record so the authorization references a real chart id. */
    private static void seedPatient(Statement statement, String org, String patientId, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-" + org.substring(0, 4)
                + "', 'SITE', 'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /** Send a synthetic request, capturing any status instead of throwing on an error response. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Build an authorization body; actor, issuer and tenant are deliberately absent. */
    private static String authorizeBody(String revision, int revisionVersion, String patientId,
                                        int policyVersion, String kind, String effectiveFrom) {
        return "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":" + revisionVersion
                + ",\"patientId\":\"" + patientId + "\",\"policyVersion\":" + policyVersion
                + ",\"kind\":\"" + kind + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"")
                + "}";
    }

    /** Authorize a release through the HTTP entry point with a correlation id bound to the actor. */
    private ResponseEntity<String> authorize(String revision, int revisionVersion, int policyVersion,
                                             String kind, String effectiveFrom, String actor,
                                             String org, String correlation) {
        return request(HttpMethod.POST, "/api/v1/releases",
                authorizeBody(revision, revisionVersion, PATIENT, policyVersion, kind, effectiveFrom),
                actor, org, correlation, null);
    }

    /** Read one release as a synthetic tenant member. */
    private ResponseEntity<String> get(String id, String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id, null, actor, org, "xch06-read", null);
    }

    /** Correct a release using the current version precondition. */
    private ResponseEntity<String> correct(String id, String ifMatch, String correctedRevision,
                                           int correctedVersion, String correlation) {
        String body = "{\"correctedRevisionId\":\"" + correctedRevision + "\",\"correctedRevisionVersion\":"
                + correctedVersion + ",\"reason\":\"synthetic correction\"}";
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/correct", body,
                "clinician-a", ORG_A, correlation, ifMatch);
    }

    /** Read a simple field from a JSON body, matching the neighbouring fixtures. */
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

    /** Resolve the tenant-local user id of a seeded member for actor assertions. */
    private static String userId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (var result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    /** Query the release ledgers inside the named tenant, where forced RLS applies. */
    private static List<String> queryStrings(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (var result = statement.executeQuery(sql)) {
                List<String> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    /** Count release rows inside the named tenant, where forced RLS applies. */
    private static int queryCount(String org, String sql) throws Exception {
        return Integer.parseInt(queryStrings(org, sql).get(0));
    }

    @Test
    void authorizeRecordsImmutableRevisionPolicyVersionAndEffectiveTime() throws Exception {
        ResponseEntity<String> created = authorize("XCH06-REV-0001", 3, 7, "TIMED", EFFECTIVE,
                "clinician-a", ORG_A, "xch06-clause-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String body = created.getBody();

        // The authorization echoes all three references and the immutable revision identity.
        assertEquals("XCH06-REV-0001", field(body, "revisionId"));
        assertEquals("3", field(body, "revisionVersion"));
        assertEquals("7", field(body, "policyVersion"));
        assertEquals(Instant.parse(EFFECTIVE), Instant.parse(field(body, "effectiveFrom")));
        assertEquals("TIMED", field(body, "kind"));
        assertEquals("AUTHORIZED", field(body, "state"));
        assertEquals(ORG_A, field(body, "issuingOrganizationId"));
        assertEquals(userId(ORG_A, "clinician-a"), field(body, "createdBy"));
        String id = field(body, "id");
        assertTrue(created.getHeaders().getLocation().toString().endsWith("/api/v1/releases/" + id));

        // The references are persisted on the authorization row, not only echoed.
        assertEquals(1, queryCount(ORG_A, "SELECT count(*) FROM public.patient_release WHERE id = '" + id
                + "' AND revision_id = 'XCH06-REV-0001' AND revision_version = 3 AND policy_version = 7"
                + " AND effective_from = '" + EFFECTIVE + "'"));

        // The decision is recorded separately from the release row: an append-only
        // event with the deciding actor and a notification to the patient.
        assertEquals(List.of("RELEASE_AUTHORIZE"), queryStrings(ORG_A,
                "SELECT action FROM public.release_event WHERE release_id = '" + id + "'"));
        assertEquals(List.of(userId(ORG_A, "clinician-a")), queryStrings(ORG_A,
                "SELECT actor_id::text FROM public.release_event WHERE release_id = '" + id + "'"));
        assertEquals(List.of("RELEASE_AUTHORIZED"), queryStrings(ORG_A,
                "SELECT event_type FROM public.release_notification WHERE release_id = '" + id
                        + "' AND correlation_id = 'xch06-clause-1'"));
        assertEquals(List.of("linked-patient"), queryStrings(ORG_A,
                "SELECT recipient_scope FROM public.release_notification WHERE release_id = '" + id + "'"));
        assertEquals(1, queryCount(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE correlation_id = 'xch06-clause-1' AND purpose = 'RELEASE_ADMIN' "
                + "AND scope = 'RELEASE' AND action = 'RELEASE_AUTHORIZE' AND outcome = 'SUCCESS'"));
    }

    @Test
    void refusesReleaseAuthorizationForRoleWithoutDecidePermission() throws Exception {
        for (String actor : List.of("reception-a", "auditor-a")) {
            String revision = "XCH06-DENY-" + actor;
            ResponseEntity<String> denied = authorize(revision, 1, 2, "IMMEDIATE", null,
                    actor, ORG_A, "xch06-role-" + actor);
            assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
            assertTrue(denied.getBody().contains("\"code\":\"FORBIDDEN\""), denied.getBody());
            assertEquals(0, queryCount(ORG_A,
                    "SELECT count(*) FROM public.patient_release WHERE revision_id = '" + revision + "'"));
        }
        assertEquals(2, queryCount(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE correlation_id IN ('xch06-role-reception-a', 'xch06-role-auditor-a') "
                + "AND action = 'RELEASE_AUTHORIZE' AND outcome = 'DENIED'"));
        assertEquals(0, queryCount(ORG_A, "SELECT count(*) FROM public.release_event e "
                + "JOIN public.patient_release r ON r.organization_id = e.organization_id AND r.id = e.release_id "
                + "WHERE r.revision_id IN ('XCH06-DENY-reception-a', 'XCH06-DENY-auditor-a')"));
        assertEquals(0, queryCount(ORG_A, "SELECT count(*) FROM public.release_notification n "
                + "JOIN public.patient_release r ON r.organization_id = n.organization_id AND r.id = n.release_id "
                + "WHERE r.revision_id IN ('XCH06-DENY-reception-a', 'XCH06-DENY-auditor-a')"));
    }

    @Test
    void refusesReleaseAuthorizationFromAnotherTenant() throws Exception {
        // A member of ORG_A cannot decide while ORG_B is the active organization.
        ResponseEntity<String> wrongOrganization = authorize("XCH06-TENANT-B", 1, 2, "IMMEDIATE", null,
                "clinician-a", ORG_B, "xch06-tenant-1");
        assertEquals(HttpStatus.FORBIDDEN, wrongOrganization.getStatusCode(), wrongOrganization.getBody());
        assertTrue(wrongOrganization.getBody().contains("\"code\":\"FORBIDDEN\""), wrongOrganization.getBody());
        assertEquals(0, queryCount(ORG_B,
                "SELECT count(*) FROM public.patient_release WHERE revision_id = 'XCH06-TENANT-B'"));

        // An ORG_A authorization is not visible to a member of ORG_B, in the API or in the ledgers.
        ResponseEntity<String> created = authorize("XCH06-TENANT-A", 1, 2, "IMMEDIATE", null,
                "clinician-a", ORG_A, "xch06-tenant-2");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals(HttpStatus.NOT_FOUND, get(id, "clinician-b", ORG_B).getStatusCode());
        assertEquals(0, queryCount(ORG_B,
                "SELECT count(*) FROM public.patient_release WHERE revision_id = 'XCH06-TENANT-A'"));
        assertEquals(0, queryCount(ORG_B,
                "SELECT count(*) FROM public.release_event WHERE release_id = '" + id + "'"));
        assertEquals(0, queryCount(ORG_B,
                "SELECT count(*) FROM public.release_notification WHERE release_id = '" + id + "'"));
    }

    @Test
    void refusesReleaseWithMissingOrInvalidReferences() throws Exception {
        record Invalid(String revision, String patient, int revisionVersion, int policyVersion, String code) { }
        List<Invalid> cases = List.of(
                new Invalid("", PATIENT, 1, 2, "INVALID_REVISION_ID"),
                new Invalid("XCH06-BAD-VERSION", PATIENT, 0, 2, "INVALID_REVISION_VERSION"),
                new Invalid("XCH06-BAD-PATIENT", "not-a-uuid", 1, 2, "INVALID_PATIENT_ID"),
                new Invalid("XCH06-BAD-POLICY", PATIENT, 1, 0, "INVALID_POLICY_VERSION"));
        for (Invalid invalid : cases) {
            String body = authorizeBody(invalid.revision(), invalid.revisionVersion(), invalid.patient(),
                    invalid.policyVersion(), "IMMEDIATE", null);
            ResponseEntity<String> denied = request(HttpMethod.POST, "/api/v1/releases", body,
                    "clinician-a", ORG_A, "xch06-invalid-" + invalid.revision(), null);
            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, denied.getStatusCode(), denied.getBody());
            assertTrue(denied.getBody().contains("\"code\":\"" + invalid.code() + "\""), denied.getBody());
            assertEquals(0, queryCount(ORG_A,
                    "SELECT count(*) FROM public.patient_release WHERE revision_id = '" + invalid.revision() + "'"));
        }
    }

    @Test
    void refusesCorrectionOntoTheSameImmutableRevision() throws Exception {
        ResponseEntity<String> created = authorize("XCH06-CORR-0001", 1, 2, "IMMEDIATE", null,
                "clinician-a", ORG_A, "xch06-corr-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> conflict = correct(id, "1", "XCH06-CORR-0001", 2, "xch06-corr-2");
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertTrue(conflict.getBody().contains("\"code\":\"CONFLICT\""), conflict.getBody());
        assertEquals("AUTHORIZED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
        assertEquals(List.of("RELEASE_AUTHORIZE"), queryStrings(ORG_A,
                "SELECT action FROM public.release_event WHERE release_id = '" + id + "' ORDER BY occurred_at"));
        assertEquals(1, queryCount(ORG_A,
                "SELECT count(*) FROM public.patient_release WHERE revision_id = 'XCH06-CORR-0001'"));
    }
}
