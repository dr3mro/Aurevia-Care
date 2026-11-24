package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * IAM-04 clause 5 (API evidence): a consolidation must preserve the original
 * identifiers, signed artifacts, signatures and source references of the merged
 * duplicate instead of destroying or overwriting them.
 *
 * <p>The green tests prove the part that exists today: the duplicate's issuer-scoped
 * MRN and its opaque identifier/consent evidence are snapshotted verbatim onto an
 * append-only chart association, and a refused or repeated consolidation writes no
 * second preservation snapshot. The disabled test documents the uncovered part —
 * signed artifacts, signatures and source references have no first-class,
 * typed preservation — so a later task can enable it once that behaviour is built.</p>
 *
 * <p>Synthetic fixtures only; real PostgreSQL with forced row-level security.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_preserve_identity_it")
@ActiveProfiles("synthetic-auth")
class PreserveOriginalIdentifiersSignedArtifactsSignaturesSourceTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_preserve_identity_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    // JSON string values: the request DTO stores these as opaque text, so the
    // inner quotes must be escaped for the surrounding JSON request body.
    private static final String IDENTIFIERS =
            "[{\\\"type\\\":\\\"MRN\\\",\\\"value\\\":\\\"PRES-2\\\",\\\"issuer\\\":\\\"CLINIC-B\\\"}]";
    private static final String CONSENTS =
            "[{\\\"type\\\":\\\"TREATMENT\\\",\\\"status\\\":\\\"signed\\\"}]";

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_preserve_identity_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_preserve_identity_it OWNER aurevia_migrate");
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
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_preserve_identity_it WITH (FORCE)");
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

    private String register(String name, String mrn, String issuer, String language,
                            String identifiers, String consents, String actor, String org) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + ",\"dob\":\"1988-03-03\",\"language\":\"" + language + "\""
                + ",\"contacts\":\"{}\",\"identifiers\":\"" + identifiers + "\",\"consents\":\"" + consents + "\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, actor, org, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    /** Register two same-name, same-DOB synthetic records with distinct issuer-scoped MRNs. */
    private String[] likelyPair(String prefix) {
        String survivor = register("Amina Khan", prefix + "-1", "CLINIC-A", "en", "[]", "[]",
                "reception-a", ORG_A);
        String duplicate = register("Amina Khan", prefix + "-2", "CLINIC-B", "ar",
                IDENTIFIERS, CONSENTS, "reception-a", ORG_A);
        return new String[] {survivor, duplicate};
    }

    private String openReview(String[] pair) {
        String body = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                body, "reviewer-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        return field(opened.getBody(), "id");
    }

    private static String approvalBody(String prefix) {
        return "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\""
                + prefix + "-1@CLINIC-A\",\"" + prefix + "-2@CLINIC-B\"]}";
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

    private static int count(String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.chart_association WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int consolidationCount(String reviewId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.consolidation WHERE review_id = '" + reviewId + "'")) {
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

    @Test
    void consolidationSnapshotsTheDuplicatesOriginalIdentifiersOnTheAssociation() throws Exception {
        String[] pair = likelyPair("PRES-A");
        String reviewId = openReview(pair);

        ResponseEntity<String> done = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody("PRES-A"), "approver-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));

        // The append-only association carries the merged record's original issuer-scoped
        // identity and its opaque identifier/consent evidence, byte for byte.
        String alias = "alias_id = '" + pair[1] + "' AND status = 'ACTIVE'";
        assertEquals(1, count(alias));
        assertEquals("PRES-A-2", scalar("SELECT preserved_mrn FROM public.chart_association WHERE " + alias));
        assertEquals("CLINIC-B", scalar("SELECT preserved_issuer FROM public.chart_association WHERE " + alias));
        assertEquals("PRES-2", scalar("SELECT preserved_identifiers->0->>'value' "
                + "FROM public.chart_association WHERE " + alias));
        assertEquals("CLINIC-B", scalar("SELECT preserved_identifiers->0->>'issuer' "
                + "FROM public.chart_association WHERE " + alias));
        assertEquals("signed", scalar("SELECT preserved_consents->0->>'status' "
                + "FROM public.chart_association WHERE " + alias));

        // Nothing is destroyed: the original patient row keeps its own identifiers.
        assertEquals("PRES-A-2", scalar("SELECT mrn FROM public.current_patients() "
                + "WHERE id = '" + pair[1] + "'"));
    }

    @Test
    void staleVersionRefusalWritesNoPreservationSnapshot() throws Exception {
        String[] pair = likelyPair("PRES-B");
        String reviewId = openReview(pair);

        assertEquals(HttpStatus.PRECONDITION_FAILED, request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody("PRES-B"), "approver-a", ORG_A, "\"9\"").getStatusCode());

        assertEquals(0, count("alias_id = '" + pair[1] + "'"),
                "a refused command must not append a preservation snapshot");
        assertEquals(0, consolidationCount(reviewId));
        assertEquals("PRES-B-2", scalar("SELECT mrn FROM public.current_patients() "
                + "WHERE id = '" + pair[1] + "'"));
    }

    @Test
    void repeatedConsolidationIsRefusedKeepingOnePreservationSnapshot() throws Exception {
        String[] pair = likelyPair("PRES-C");
        String reviewId = openReview(pair);
        String command = "/api/v1/duplicate-reviews/" + reviewId + "/consolidate";

        assertEquals(HttpStatus.OK, request(HttpMethod.POST, command,
                approvalBody("PRES-C"), "approver-a", ORG_A, "\"1\"").getStatusCode());

        // A second approval must be refused; otherwise two ACTIVE associations would
        // leave ambiguity about which preserved identity is authoritative.
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, command,
                approvalBody("PRES-C"), "approver-a", ORG_A, "\"1\"").getStatusCode());

        String alias = "alias_id = '" + pair[1] + "' AND status = 'ACTIVE'";
        assertEquals(1, count(alias));
        assertEquals("PRES-C-2", scalar("SELECT preserved_mrn FROM public.chart_association WHERE " + alias));
        assertEquals(1, consolidationCount(reviewId));
    }

    /**
     * Full clause evidence: signed artifacts, signatures and source references must be
     * preserved as first-class, typed data and remain attributable after consolidation.
     * Disabled because the chart association snapshots only MRN/issuer/identifiers/consents;
     * there are no preserved signed-artifact, signature or source-reference columns and
     * no read-path assertion that the duplicate's signed content survives the merge.
     */
    @Test
    @Disabled("GAP: signed artifacts, signatures and source references are not first-class "
            + "on chart_association (only preserved_mrn/issuer/identifiers/consents exist). "
            + "A schema+service change and a read-path proof are needed in a later task.")
    void consolidationPreservesSignedArtifactsSignaturesAndSourceReferences() throws Exception {
        String[] pair = likelyPair("PRES-G");
        String reviewId = openReview(pair);
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody("PRES-G"), "approver-a", ORG_A, "\"1\"").getStatusCode());

        String alias = "alias_id = '" + pair[1] + "' AND status = 'ACTIVE'";
        assertNotNull(scalar("SELECT preserved_signed_artifacts::text "
                + "FROM public.chart_association WHERE " + alias));
        assertNotNull(scalar("SELECT preserved_signatures::text "
                + "FROM public.chart_association WHERE " + alias));
        assertNotNull(scalar("SELECT preserved_source_references::text "
                + "FROM public.chart_association WHERE " + alias));
        assertFalse(scalar("SELECT preserved_source_references::text "
                + "FROM public.chart_association WHERE " + alias).isEmpty());
    }
}
