package com.aurevia.smartclinic.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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
 * IAM-04 clause 7 proof: an ambiguous order, patient-account link or grant blocks
 * chart consolidation until an authorized workflow resolves it. The rule is
 * exercised through its real HTTP entry point against a migrated PostgreSQL
 * database with synthetic patients only. This class proves only the blocking
 * rule; worklist and dry-run presentation belong to the neighbouring clause
 * evidence, and the all-or-nothing commit belongs to the lock clause.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_ambiguous_block_it")
@ActiveProfiles("synthetic-auth")
class BlockAmbiguousOrdersPatientAccountLinksGrantsTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_ambiguous_block_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Bind the HTTP client to the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create the roles, a fresh database, the V9+ schema and synthetic staff. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_ambiguous_block_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_ambiguous_block_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "reviewer-a", "tenant_admin");
            seed(statement, ORG_A, "approver-a", "privacy_auditor");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "approver-b", "privacy_auditor");
        }
    }

    /** Remove the disposable database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_ambiguous_block_it WITH (FORCE)");
        }
    }

    /**
     * Seed one synthetic staff identity with an active membership and a role.
     *
     * @param statement JDBC statement on the migrated test database
     * @param org       owning organization identifier
     * @param subject   synthetic subject used in the synthetic-auth header
     * @param role      bounded role granted to the membership
     */
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

    /**
     * Append one dependency row against the duplicate patient under forced RLS.
     * Resolution is append-only: a later row with the same (kind, reference)
     * supersedes this one in the repository's latest-state fold.
     */
    private static void addDependency(String patientId, String kind, String reference,
                                      boolean ambiguous, boolean resolved) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reviewer-a'");
            statement.execute("INSERT INTO public.consolidation_dependency "
                    + "(organization_id, patient_id, kind, reference, ambiguous, resolved) VALUES ('"
                    + ORG_A + "', '" + patientId + "', '" + kind + "', '" + reference + "', "
                    + ambiguous + ", " + resolved + ")");
        }
    }

    /** Count persisted rows for one tenant after a command, to prove no partial write occurred. */
    private static int count(String table, String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** Read one folded visibility value for a synthetic patient. */
    private static String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
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

    private String register(String name, String mrn, String dob, String language) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"CLINIC-A\""
                + ",\"dob\":\"" + dob + "\",\"language\":\"" + language + "\""
                + ",\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body,
                "reception-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    /** Register two same-tenant demographic duplicates and open a review for them. */
    private String[] pairWithReview(String prefix) {
        String survivor = register("Amina Khan", prefix + "-1", "1988-03-03", "en");
        String duplicate = register("Amina Khan", prefix + "-2", "1988-03-03", "ar");
        String reviewBody = "{\"survivorId\":\"" + survivor + "\",\"duplicateId\":\"" + duplicate + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        return new String[] {survivor, duplicate, field(opened.getBody(), "id")};
    }

    /** Approval confirming the two issuer-scoped MRNs of one synthetic pair. */
    private static String approvalBody(String prefix) {
        return "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\"" + prefix + "-1@CLINIC-A\",\""
                + prefix + "-2@CLINIC-A\"]}";
    }

    private ResponseEntity<String> approve(String reviewId, String prefix, String subject, String org) {
        return request(HttpMethod.POST, "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody(prefix), subject, org, "\"1\"");
    }

    /** Assert the specific clause-7 denial and that no association or event was appended. */
    private void assertBlocked(ResponseEntity<String> denied, String duplicateId, String reviewId)
            throws Exception {
        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode(), denied.getBody());
        assertEquals("UNRESOLVED_DEPENDENCY", field(denied.getBody(), "code"));
        assertTrue(denied.getBody().contains("ambiguous orders, links or grants must be resolved first"),
                denied.getBody());
        assertEquals(0, count("chart_association", "alias_id = '" + duplicateId + "'"),
                "a blocked command must leave no chart visibility change");
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"),
                "a blocked command must append no consolidation event");
        assertEquals(1, count("audit_event", "action = 'CONSOLIDATE' AND outcome = 'BLOCKED' "
                + "AND resource_id = '" + reviewId + "'"),
                "the refusal must be audited as a blocked identity action");
    }

    @Test
    void consolidationIsBlockedWhileAnAmbiguousOrderIsUnresolved() throws Exception {
        String[] pair = pairWithReview("DUP-ORDER");
        addDependency(pair[1], "ORDER", "order-ambiguous-1", true, false);

        assertBlocked(approve(pair[2], "DUP-ORDER", "approver-a", ORG_A), pair[1], pair[2]);
    }

    @Test
    void consolidationIsBlockedWhileAnAmbiguousPatientAccountLinkIsUnresolved() throws Exception {
        String[] pair = pairWithReview("DUP-LINK");
        addDependency(pair[1], "PATIENT_LINK", "patient-account-ambiguous-1", true, false);

        assertBlocked(approve(pair[2], "DUP-LINK", "approver-a", ORG_A), pair[1], pair[2]);
    }

    @Test
    void consolidationIsBlockedWhileAnAmbiguousGrantIsUnresolved() throws Exception {
        String[] pair = pairWithReview("DUP-GRANT");
        addDependency(pair[1], "GRANT", "grant-ambiguous-1", true, false);

        assertBlocked(approve(pair[2], "DUP-GRANT", "approver-a", ORG_A), pair[1], pair[2]);
    }

    @Test
    void consolidationProceedsOnlyAfterEveryAmbiguousDependencyIsResolved() throws Exception {
        String[] pair = pairWithReview("DUP-RESOLVE");
        addDependency(pair[1], "ORDER", "order-a", true, false);
        addDependency(pair[1], "PATIENT_LINK", "link-a", true, false);
        addDependency(pair[1], "GRANT", "grant-a", true, false);

        assertBlocked(approve(pair[2], "DUP-RESOLVE", "approver-a", ORG_A), pair[1], pair[2]);

        // Resolution is appended, never an UPDATE: the latest row per (kind,
        // reference) is folded by the repository, so each blocker must stop
        // blocking before the same approval is allowed to proceed.
        addDependency(pair[1], "ORDER", "order-a", true, true);
        addDependency(pair[1], "PATIENT_LINK", "link-a", true, true);
        addDependency(pair[1], "GRANT", "grant-a", true, true);

        ResponseEntity<String> done = approve(pair[2], "DUP-RESOLVE", "approver-a", ORG_A);
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));
        assertEquals(pair[0], scalar("SELECT public.effective_patient_id('" + pair[1] + "')::text"));
        assertEquals(1, count("chart_association", "alias_id = '" + pair[1] + "'"));
        assertEquals(1, count("consolidation", "review_id = '" + pair[2] + "'"));
    }

    @Test
    void nonAmbiguousAndResolvedDependenciesDoNotBlockConsolidation() throws Exception {
        String[] pair = pairWithReview("DUP-CLEAR");
        addDependency(pair[1], "ORDER", "order-known", false, false);
        addDependency(pair[1], "GRANT", "grant-resolved", true, true);

        ResponseEntity<String> done = approve(pair[2], "DUP-CLEAR", "approver-a", ORG_A);
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals(0, count("audit_event", "action = 'CONSOLIDATE' AND outcome = 'BLOCKED' "
                + "AND resource_id = '" + pair[2] + "'"));
        assertFalse(done.getBody().contains("UNRESOLVED_DEPENDENCY"));
    }
}
