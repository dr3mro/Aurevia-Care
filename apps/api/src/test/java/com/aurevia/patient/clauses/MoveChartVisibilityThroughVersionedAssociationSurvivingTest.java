package com.aurevia.patient.clauses;

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
 * IAM-04 clause 4 acceptance: chart visibility moves through a versioned
 * association to the surviving record. After the second reviewer approves, the
 * duplicate chart resolves to the survivor, the current association carries the
 * original identifiers, and the patient rows are never edited. The negative
 * cases show that a stale version and a chart already moved by another review
 * are refused without appending another association. Scope is deliberately this
 * clause only; the worklist, dry run, dependencies, second-reviewer rule and
 * correction workflow belong to their own suites.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_chart_visibility_it")
@ActiveProfiles("synthetic-auth")
class MoveChartVisibilityThroughVersionedAssociationSurvivingTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_chart_visibility_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /**
     * Build a REST client against the randomly assigned application port.
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create an isolated database, run the production migrations and seed synthetic staff.
     */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_chart_visibility_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_chart_visibility_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG, "admin-a", "tenant_admin");
            seed(statement, ORG, "reviewer-a", "tenant_admin");
            seed(statement, ORG, "approver-a", "privacy_auditor");
            seed(statement, ORG, "reception-a", "reception");
        }
    }

    /**
     * Drop the per-class database after the suite.
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_chart_visibility_it WITH (FORCE)");
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
                                           String subject, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        headers.set("X-Active-Organization", ORG);
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

    private String register(String mrn, String subject) {
        String body = "{\"displayName\":\"Amina Khan\",\"mrn\":\"" + mrn + "\","
                + "\"issuer\":\"CLINIC-A\",\"dob\":\"1988-03-03\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, subject, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    private String openReview(String survivor, String duplicate) {
        String body = "{\"survivorId\":\"" + survivor + "\",\"duplicateId\":\"" + duplicate + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                body, "reviewer-a", null);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        return field(opened.getBody(), "id");
    }

    private static String approval(String firstMrn, String secondMrn) {
        return "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\""
                + firstMrn + "@CLINIC-A\",\"" + secondMrn + "@CLINIC-A\"]}";
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private String[] twoDuplicates(String prefix, String subject) {
        return new String[] {
                register(prefix + "-1", subject),
                register(prefix + "-2", subject)
        };
    }

    /**
     * Approval appends one ACTIVE association pointing the duplicate chart at the
     * survivor; the current visibility follows that version and originals survive.
     */
    @Test
    void consolidationMovesDuplicateChartVisibilityToTheSurvivingRecord() throws Exception {
        String[] pair = twoDuplicates("CHART-MOVE", "reception-a");
        String survivor = pair[0];
        String duplicate = pair[1];
        String reviewId = openReview(survivor, duplicate);

        ResponseEntity<String> before = request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + survivor + "/" + duplicate, null, "admin-a", null);
        assertEquals(HttpStatus.OK, before.getStatusCode(), before.getBody());
        assertTrue(before.getBody().contains("\"chartVisibilityId\":\"" + duplicate + "\""),
                "before approval the chart resolves to its own patient id");

        ResponseEntity<String> done = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approval("CHART-MOVE-1", "CHART-MOVE-2"), "approver-a", "\"1\"");
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));

        assertEquals(survivor, scalar("SELECT public.effective_patient_id('" + duplicate + "')::text"),
                "the duplicate chart now resolves to the survivor");
        assertEquals(1, count("chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals("ACTIVE", scalar("SELECT status FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals("1", scalar("SELECT version FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals(survivor, scalar("SELECT survivor_id::text FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals("CHART-MOVE-2", scalar("SELECT preserved_mrn FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals("CLINIC-A", scalar("SELECT preserved_issuer FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals("2", scalar("SELECT count(DISTINCT id)::text FROM public.patient_record "
                + "WHERE id IN ('" + survivor + "','" + duplicate + "')"),
                "the original patient records remain, never updated or removed");

        ResponseEntity<String> after = request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + survivor + "/" + duplicate, null, "admin-a", null);
        assertEquals(HttpStatus.OK, after.getStatusCode(), after.getBody());
        assertTrue(after.getBody().contains("\"chartVisibilityId\":\"" + survivor + "\""),
                "the review read path exposes the moved visibility");
    }

    /**
     * A stale version precondition refuses the move and appends no association.
     */
    @Test
    void staleVersionRefusesChartVisibilityMoveAndWritesNoAssociation() throws Exception {
        String[] pair = twoDuplicates("CHART-STALE", "reception-a");
        String survivor = pair[0];
        String duplicate = pair[1];
        String reviewId = openReview(survivor, duplicate);

        ResponseEntity<String> stale = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approval("CHART-STALE-1", "CHART-STALE-2"), "approver-a", "\"9\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals(0, count("chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"));
        assertEquals(duplicate, scalar("SELECT public.effective_patient_id('" + duplicate + "')::text"),
                "a refused move leaves visibility on the original record");
    }

    /**
     * A chart already moved by an earlier association cannot be moved again by a
     * second review, so no second current association or visibility cycle is created.
     */
    @Test
    void alreadyMovedChartCannotBeMovedAgainThroughANewAssociation() throws Exception {
        String firstSurvivor = register("CHART-AGAIN-1", "reception-a");
        String duplicate = register("CHART-AGAIN-2", "reception-a");
        String secondSurvivor = register("CHART-AGAIN-3", "reception-a");

        String firstReview = openReview(firstSurvivor, duplicate);
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + firstReview + "/consolidate",
                approval("CHART-AGAIN-1", "CHART-AGAIN-2"), "approver-a", "\"1\"").getStatusCode());
        assertEquals(firstSurvivor, scalar("SELECT public.effective_patient_id('" + duplicate + "')::text"));

        String secondReview = openReview(secondSurvivor, duplicate);
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + secondReview + "/consolidate",
                approval("CHART-AGAIN-3", "CHART-AGAIN-2"), "approver-a", "\"1\"");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals(1, count("chart_association", "alias_id = '" + duplicate + "'"),
                "the first ACTIVE association remains the only current version");
        assertEquals(firstSurvivor, scalar("SELECT public.effective_patient_id('" + duplicate + "')::text"),
                "visibility still follows the surviving record of the first review");
        assertFalse(request(HttpMethod.GET, "/api/v1/duplicate-reviews", null, "admin-a", null)
                .getBody().contains(duplicate), "a consolidated alias leaves the worklist");
    }
}
