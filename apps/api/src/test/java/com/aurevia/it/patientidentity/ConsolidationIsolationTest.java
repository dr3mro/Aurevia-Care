package com.aurevia.it.patientidentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/** S-G0-06 acceptance against a real database: IAM-04 review, lock, consolidation and correction. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_consolidation_it")
@ActiveProfiles("synthetic-auth")
class ConsolidationIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_consolidation_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_consolidation_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_consolidation_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "reviewer-a", "tenant_admin");
            seed(statement, ORG_A, "approver-a", "privacy_auditor");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_B, "reception-b", "reception");
            seed(statement, ORG_B, "reviewer-b", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_consolidation_it WITH (FORCE)");
        }
    }

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

    private String register(String name, String mrn, String issuer, String dob, String language,
                            String actor, String org) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + ",\"dob\":\"" + dob + "\",\"language\":\"" + language + "\""
                + ",\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, actor, org, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
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

    private static int count(String table, String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static void addDependency(String patientId, boolean resolved) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reviewer-a'");
            statement.execute("INSERT INTO public.consolidation_dependency "
                    + "(organization_id, patient_id, kind, reference, ambiguous, resolved) VALUES ('"
                    + ORG_A + "', '" + patientId + "', 'ORDER', 'order-9', true, " + resolved + ")");
        }
    }

    /** IAM-04 consolidation approval confirming both issuer-scoped MRNs as one person. */
    private static String approvalBody(String mrnPrefix) {
        return "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\""
                + mrnPrefix + "-1@CLINIC-A\",\"" + mrnPrefix + "-2@CLINIC-A\"]}";
    }

    private String[] twoDuplicates(String mrnPrefix) {
        String survivor = register("Amina Khan", mrnPrefix + "-1", "CLINIC-A", "1988-03-03",
                "en", "reception-a", ORG_A);
        String duplicate = register("Amina Khan", mrnPrefix + "-2", "CLINIC-A", "1988-03-03",
                "ar", "reception-a", ORG_A);
        return new String[] {survivor, duplicate};
    }

    @Test
    void worklistIsSameTenantOnly() {
        String[] pair = twoDuplicates("DUP-W");
        register("Amina Khan", "DUP-W-1", "CLINIC-A", "1988-03-03", "en", "reception-b", ORG_B);
        register("Amina Khan", "DUP-W-2", "CLINIC-A", "1988-03-03", "ar", "reception-b", ORG_B);

        ResponseEntity<String> inA = request(HttpMethod.GET, "/api/v1/duplicate-reviews",
                null, "admin-a", ORG_A, null);
        assertEquals(HttpStatus.OK, inA.getStatusCode(), inA.getBody());
        assertTrue(inA.getBody().contains(pair[0]));
        assertTrue(inA.getBody().contains(pair[1]));

        ResponseEntity<String> inB = request(HttpMethod.GET, "/api/v1/duplicate-reviews",
                null, "admin-b", ORG_B, null);
        assertEquals(HttpStatus.OK, inB.getStatusCode(), inB.getBody());
        assertFalse(inB.getBody().contains(pair[0]), "peer tenant must not see the other tenant's pair");
    }

    @Test
    void reviewShowsConflictsDependenciesDryRunAndVisibility() throws Exception {
        String[] pair = twoDuplicates("DUP-R");
        String body = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        assertEquals(HttpStatus.CREATED,
                request(HttpMethod.POST, "/api/v1/duplicate-reviews", body, "reviewer-a", ORG_A, null)
                        .getStatusCode());
        addDependency(pair[1], false);

        ResponseEntity<String> detail = request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + pair[0] + "/" + pair[1], null, "auditor-a", ORG_A, null);
        assertEquals(HttpStatus.OK, detail.getStatusCode(), detail.getBody());
        assertTrue(detail.getBody().contains("\"conflicts\""));
        assertTrue(detail.getBody().contains("\"dependencies\""));
        assertTrue(detail.getBody().contains("blockers=1"));
        assertTrue(detail.getBody().contains("ORDER"));
        assertTrue(detail.getBody().contains("\"chartVisibilityId\":\"" + pair[1] + "\""));
    }

    @Test
    void consolidationNeedsSecondReviewerAndUnblockedDependencies() throws Exception {
        String[] pair = twoDuplicates("DUP-C");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        String reviewId = field(opened.getBody(), "id");

        String command = "/api/v1/duplicate-reviews/" + reviewId + "/consolidate";
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, command, approvalBody("DUP-C"),
                "reviewer-a", ORG_A, "\"1\"").getStatusCode());

        addDependency(pair[1], false);
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, command, approvalBody("DUP-C"),
                "approver-a", ORG_A, "\"1\"").getStatusCode());
        assertEquals(0, count("chart_association", "alias_id = '" + pair[1] + "'"),
                "blocked command must write nothing");
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"),
                "blocked command must write nothing");

        addDependency(pair[1], true);
        ResponseEntity<String> done = request(HttpMethod.POST, command, approvalBody("DUP-C"),
                "approver-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));
        assertEquals("approver-a", field(done.getBody(), "approverSubject"));
        assertEquals("reviewer-a", field(done.getBody(), "openedBySubject"));
        assertEquals(1, count("chart_association", "alias_id = '" + pair[1] + "'"));
        assertEquals(1, count("consolidation", "review_id = '" + reviewId + "'"));
        assertEquals(pair[0], scalar("SELECT public.effective_patient_id('" + pair[1] + "')::text"));

        ResponseEntity<String> after = request(HttpMethod.GET, "/api/v1/duplicate-reviews",
                null, "admin-a", ORG_A, null);
        assertFalse(after.getBody().contains(pair[1]), "consolidated pair leaves the worklist");

        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, command, approvalBody("DUP-C"),
                "approver-a", ORG_A, "\"1\"").getStatusCode(), "consolidation is not repeatable");
    }

    @Test
    void staleVersionAndUnauthorizedRoleAreRejected() {
        String[] pair = twoDuplicates("DUP-S");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A, null);
        String reviewId = field(opened.getBody(), "id");
        String command = "/api/v1/duplicate-reviews/" + reviewId + "/consolidate";
        assertEquals(HttpStatus.PRECONDITION_FAILED,
                request(HttpMethod.POST, command, approvalBody("DUP-S"), "approver-a", ORG_A, "\"9\"")
                        .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.POST, command, approvalBody("DUP-S"), "reception-a", ORG_A, "\"1\"")
                        .getStatusCode());
    }

    @Test
    void correctionReversesVisibilityAndAuditsAndHistoryIsImmutable() throws Exception {
        String[] pair = twoDuplicates("DUP-X");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A, null);
        String reviewId = field(opened.getBody(), "id");
        String command = "/api/v1/duplicate-reviews/" + reviewId + "/consolidate";
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, command, approvalBody("DUP-X"),
                "approver-a", ORG_A, "\"1\"").getStatusCode(), opened.getBody());

        ResponseEntity<String> corrected = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/correct", "{\"reason\":\"wrong patient\"}",
                "admin-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertEquals("CORRECTED", field(corrected.getBody(), "status"));
        assertEquals("wrong patient", field(corrected.getBody(), "correctionReason"));
        assertEquals(pair[1], scalar("SELECT public.effective_patient_id('" + pair[1] + "')::text"));
        assertEquals(2, count("chart_association", "alias_id = '" + pair[1] + "'"));
        assertEquals(2, count("consolidation", "review_id = '" + reviewId + "'"));
        assertTrue(count("audit_event", "action = 'CONSOLIDATION_CORRECT' AND outcome = 'SUCCESS' "
                + "AND resource_id = '" + reviewId + "'") >= 1);

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.chart_association SET reason = 'tampered'"));
        }
    }

    @Test
    void peerTenantCannotReviewOrConsolidateAnotherTenantsPair() {
        String[] pair = twoDuplicates("DUP-T");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A, null);
        String reviewId = field(opened.getBody(), "id");

        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + pair[0] + "/" + pair[1], null, "reviewer-b", ORG_A, null)
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + pair[0] + "/" + pair[1], null, "reviewer-b", ORG_B, null)
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate", approvalBody("DUP-T"),
                "reviewer-b", ORG_A, "\"1\"").getStatusCode());
    }

    @Test
    void identifierConfirmationIsEnforcedAgainstTheTwoRecords() throws Exception {
        String[] pair = twoDuplicates("DUP-I");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", ORG_A, null);
        String reviewId = field(opened.getBody(), "id");
        String command = "/api/v1/duplicate-reviews/" + reviewId + "/consolidate";

        // A name+DOB warning alone is not grounds for a merge: two identifiers
        // must be confirmed and must match the two records being consolidated.
        String wrong = "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\"X-1@CLINIC-Z\",\"X-2@CLINIC-Y\"]}";
        // Compare the wire code: Spring 7 renamed the 422 enum constant.
        assertEquals(422, request(HttpMethod.POST, command, wrong, "approver-a", ORG_A, "\"1\"")
                .getStatusCode().value());
        assertEquals(0, count("chart_association", "alias_id = '" + pair[1] + "'"));
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"));

        // When identifiers cannot be confirmed, the documented approved process
        // is required and the decision is audited with its basis.
        String alternate = "{\"reason\":\"merge\",\"alternateProcess\":\"site_witness\"}";
        ResponseEntity<String> done = request(HttpMethod.POST, command, alternate, "approver-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals(pair[0], scalar("SELECT public.effective_patient_id('" + pair[1] + "')::text"));
        assertTrue(count("audit_event", "action = 'CONSOLIDATION_IDENTIFIER_CONFIRM' "
                + "AND outcome = 'ALTERNATE:site_witness' AND resource_id = '" + reviewId + "'") >= 1);
    }
}
