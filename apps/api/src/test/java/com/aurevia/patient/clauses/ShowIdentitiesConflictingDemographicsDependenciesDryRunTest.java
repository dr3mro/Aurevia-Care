package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * IAM-04 clause 2: a reviewer can show both identities, their conflicting
 * demographics, the same-tenant dependencies and a non-mutating dry run.
 *
 * <p>The proof drives the HTTP entry point against real PostgreSQL with forced
 * row-level security under the synthetic-auth profile. Only synthetic fixtures
 * are used. The negative tests show the read refusing a caller without the
 * review permission, a peer tenant and an invalid pair.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_show_identities_it")
@ActiveProfiles("synthetic-auth")
class ShowIdentitiesConflictingDemographicsDependenciesDryRunTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_show_identities_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

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
            statement.execute("DROP DATABASE IF EXISTS orevia_show_identities_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_show_identities_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "reviewer-a", "tenant_admin");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_B, "reviewer-b", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_show_identities_it WITH (FORCE)");
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

    /** Send an HTTP request, keeping the status so negative cases are asserted, not thrown. */
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

    /** Register a synthetic patient through the normal intake entry point. */
    private String register(String name, String mrn, String issuer, String dob, String language,
                            String actor, String org) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + ",\"dob\":\"" + dob + "\",\"language\":\"" + language + "\""
                + ",\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, actor, org);
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

    /**
     * Two same-tenant patients with one normalized name and DOB but different demographics.
     *
     * @param mrnPrefix unique prefix so each test's synthetic MRNs cannot collide
     * @return the survivor and duplicate patient identifiers
     */
    private String[] conflictingPair(String mrnPrefix) {
        String survivor = register("Amina Khan", mrnPrefix + "-1", "CLINIC-A", "1988-03-03",
                "en", "reception-a", ORG_A);
        String duplicate = register("Amina  Khan", mrnPrefix + "-2", "CLINIC-B", "1988-03-03",
                "ar", "reception-a", ORG_A);
        return new String[] {survivor, duplicate};
    }

    /** Open a review ticket so the GET has a review to show alongside the pair. */
    private void openReview(String survivor, String duplicate) {
        String body = "{\"survivorId\":\"" + survivor + "\",\"duplicateId\":\"" + duplicate + "\"}";
        assertEquals(HttpStatus.CREATED,
                request(HttpMethod.POST, "/api/v1/duplicate-reviews", body, "reviewer-a", ORG_A).getStatusCode());
    }

    /** Record an ambiguous, unresolved order dependency on the duplicate patient. */
    private static void addDependency(String patientId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reviewer-a'");
            statement.execute("INSERT INTO public.consolidation_dependency "
                    + "(organization_id, patient_id, kind, reference, ambiguous, resolved) VALUES ('"
                    + ORG_A + "', '" + patientId + "', 'ORDER', 'order-9', true, false)");
        }
    }

    /** Count chart associations for a patient; a dry run must never append one. */
    private static int associationCount(String aliasId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.chart_association WHERE alias_id = '" + aliasId + "'")) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private ResponseEntity<String> review(String survivor, String duplicate, String subject, String org) {
        return request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + survivor + "/" + duplicate, null, subject, org);
    }

    @Test
    void reviewShowsBothIdentitiesConflictsDependenciesAndDryRun() throws Exception {
        String[] pair = conflictingPair("SHOW-P");
        openReview(pair[0], pair[1]);
        addDependency(pair[1]);
        int associationsBefore = associationCount(pair[1]);

        ResponseEntity<String> response = review(pair[0], pair[1], "auditor-a", ORG_A);

        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        String body = response.getBody();
        // Both identities are present, with their own original MRN values.
        assertTrue(body.contains("\"survivor\""));
        assertTrue(body.contains("\"duplicate\""));
        assertTrue(body.contains(pair[0]));
        assertTrue(body.contains(pair[1]));
        assertTrue(body.contains("\"mrn\":\"SHOW-P-1\""));
        assertTrue(body.contains("\"mrn\":\"SHOW-P-2\""));
        // Conflicting demographics are shown side by side.
        assertTrue(body.contains("\"conflicts\""));
        assertTrue(body.contains("\"field\":\"mrn\""));
        assertTrue(body.contains("\"field\":\"mrnIssuer\""));
        assertTrue(body.contains("\"field\":\"displayName\""));
        assertTrue(body.contains("\"field\":\"language\""));
        // The conflicting values are shown side by side, not just named.
        assertTrue(body.contains("\"survivorValue\":\"SHOW-P-1\""));
        assertTrue(body.contains("\"duplicateValue\":\"SHOW-P-2\""));
        assertTrue(body.contains("\"survivorValue\":\"en\""));
        assertTrue(body.contains("\"duplicateValue\":\"ar\""));
        // The ambiguous dependency is listed and counted as a blocker.
        assertTrue(body.contains("\"dependencies\""));
        assertTrue(body.contains("ORDER"));
        assertTrue(body.contains("order-9"));
        // The dry run describes the move without changing visibility.
        assertTrue(body.contains("\"dryRun\""));
        assertTrue(body.contains("move " + pair[1]));
        assertTrue(body.contains("preserve SHOW-P-2@CLINIC-B"));
        assertTrue(body.contains("blockers=1"));
        assertTrue(body.contains("\"chartVisibilityId\":\"" + pair[1] + "\""));
        // The dry run is read-only: no chart visibility association was appended.
        assertEquals(associationsBefore, associationCount(pair[1]));
    }

    @Test
    void reviewRefusesCallerWithoutReviewPermission() {
        String[] pair = conflictingPair("SHOW-R");
        openReview(pair[0], pair[1]);

        assertEquals(HttpStatus.FORBIDDEN, review(pair[0], pair[1], "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void reviewRefusesPeerTenant() {
        String[] pair = conflictingPair("SHOW-T");
        openReview(pair[0], pair[1]);

        // No membership in the selected organization: refused before any read.
        assertEquals(HttpStatus.FORBIDDEN, review(pair[0], pair[1], "reviewer-b", ORG_A).getStatusCode());
        // Active in the peer organization: the other tenant's patients are invisible.
        assertEquals(HttpStatus.NOT_FOUND, review(pair[0], pair[1], "reviewer-b", ORG_B).getStatusCode());
    }

    @Test
    void reviewRefusesInvalidPairAndMalformedIdentifier() {
        String[] pair = conflictingPair("SHOW-I");

        assertEquals(422, review(pair[0], pair[0], "auditor-a", ORG_A).getStatusCode().value());
        assertEquals(422, review("not-a-uuid", pair[1], "auditor-a", ORG_A).getStatusCode().value());
    }
}
