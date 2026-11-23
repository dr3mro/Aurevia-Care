package com.aurevia.smartclinic.tenant.clauses;

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
 * ARC-01 clause 7 acceptance: patient attribution survives duplicate consolidation
 * independently of merge aliases. A consolidation appends a visibility association;
 * it never rewrites a clinical record's owning patient. This suite proves that the
 * chart entry stays bound to the patient it was created for, that only the read-time
 * visibility fold follows the survivor, and that a wrong role, a peer tenant or a
 * stale version cannot consolidate and re-attribute the record.
 *
 * <p>Synthetic fixtures only, on real PostgreSQL with forced row-level security.
 * No production code is added by this suite; it exercises existing HTTP entry points.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_attribution_aliases_it")
@ActiveProfiles("synthetic-auth")
class PreservePatientAttributionIndependentlyMergeAliasesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_attribution_aliases_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Build a REST client against the randomly assigned application port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, run the production migrations and seed synthetic staff. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_attribution_aliases_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_attribution_aliases_it OWNER smartclinic_migrate");
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
            seed(statement, ORG_A, "clinician-a", "clinician");
            // Both peer-tenant callers are authorized inside ORG_B, so a denial is
            // RLS concealment of ORG_A data rather than a wrong-role setup failure.
            seed(statement, ORG_B, "admin-b", "privacy_auditor");
            seed(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    /** Drop the per-class database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_attribution_aliases_it WITH (FORCE)");
        }
    }

    /** Seed an organization, an active membership and one bounded role for a synthetic subject. */
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

    /** Send one synthetic HTTP request, binding subject, active organization and optional version. */
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

    /** Register a synthetic patient in the source tenant and return its opaque id. */
    private String register(String mrn, String issuer, String subject, String org) {
        String body = "{\"displayName\":\"Amina Khan\",\"mrn\":\"" + mrn + "\","
                + "\"issuer\":\"" + issuer + "\",\"dob\":\"1988-03-03\",\"language\":\"en\","
                + "\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, subject, org, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    /** Register two same-name, same-DOB records with distinct issuer-scoped MRNs. */
    private String[] twoDuplicates(String prefix, String subject) {
        return new String[] {
                register(prefix + "-1", "CLINIC-A", subject, ORG_A),
                register(prefix + "-2", "CLINIC-B", subject, ORG_A)
        };
    }

    /** Capture an unverified allergy entry on the given patient through the chart entry point. */
    private String recordAllergy(String patient, String text, String subject, String org) {
        String body = "{\"patientId\":\"" + patient + "\",\"type\":\"ALLERGY\""
                + ",\"originalText\":\"" + text + "\",\"source\":\"CLINICIAN_OBSERVED\""
                + ",\"presence\":\"ABSENT\",\"effectiveFrom\":\"2026-01-01T00:00:00Z\""
                + ",\"effectiveTo\":\"2026-06-30T23:59:59Z\",\"clinicalAuthor\":\"" + subject + "\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/chart/records/entries",
                body, subject, org, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals(patient, field(created.getBody(), "patientId"));
        return field(created.getBody(), "id");
    }

    /** Open a review ticket for the pair as the first authorized reviewer. */
    private String openReview(String survivor, String duplicate) {
        String body = "{\"survivorId\":\"" + survivor + "\",\"duplicateId\":\"" + duplicate + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                body, "reviewer-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        return field(opened.getBody(), "id");
    }

    /** Build the second reviewer's approval naming both issuer-scoped MRNs. */
    private static String approval(String firstMrn, String firstIssuer,
                                   String secondMrn, String secondIssuer) {
        return "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\""
                + firstMrn + "@" + firstIssuer + "\",\"" + secondMrn + "@" + secondIssuer + "\"]}";
    }

    /** Read a scalar JSON string field without binding the whole response to a DTO. */
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

    /** Count rows in the source tenant; the GUC must be set because FORCE RLS applies to the owner. */
    private static int count(String table, String where) throws Exception {
        return countInOrg(ORG_A, table, where);
    }

    /** Count rows seen by an explicit active organization. */
    private static int countInOrg(String org, String table, String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** Read one scalar in the source tenant; the GUC is set in the same session as the query. */
    private static String scalar(String sql) throws Exception {
        return scalarInOrg(ORG_A, sql);
    }

    /** Read one scalar under an explicit active organization. */
    private static String scalarInOrg(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getString(1);
            }
        }
    }

    /** Approval leaves the clinical entry on its original patient; only visibility folds to the survivor. */
    @Test
    void consolidationLeavesChartEntryAttributedToOriginalPatient() throws Exception {
        String[] pair = twoDuplicates("ATTR-A", "reception-a");
        String survivor = pair[0];
        String duplicate = pair[1];
        String entryId = recordAllergy(duplicate, "synthetic no known allergies", "clinician-a", ORG_A);
        String reviewId = openReview(survivor, duplicate);

        ResponseEntity<String> done = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approval("ATTR-A-1", "CLINIC-A", "ATTR-A-2", "CLINIC-B"), "approver-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));

        // Attribution is the raw stored owner, not the effective visibility fold.
        assertEquals(duplicate, scalar("SELECT patient_id::text FROM public.chart_entry "
                + "WHERE id = CAST('" + entryId + "' AS uuid)"));
        assertEquals("1", scalar("SELECT count(*)::text FROM public.chart_entry "
                + "WHERE id = CAST('" + entryId + "' AS uuid) AND patient_id = CAST('" + duplicate + "' AS uuid)"));
        assertEquals("0", scalar("SELECT count(*)::text FROM public.chart_entry "
                + "WHERE patient_id = CAST('" + survivor + "' AS uuid)"),
                "no clinical record is re-pointed at the survivor");

        // Only chart visibility resolves to the survivor; the survivor keeps its own chart.
        assertEquals(survivor, scalar("SELECT public.effective_patient_id(CAST('" + duplicate + "' AS uuid))::text"));
        assertEquals(survivor, scalar("SELECT public.effective_patient_id(CAST('" + survivor + "' AS uuid))::text"));

        // Both original identities survive untouched, and the alias snapshots their values.
        assertEquals("2", scalar("SELECT count(DISTINCT id)::text FROM public.patient_record "
                + "WHERE id IN ('" + survivor + "','" + duplicate + "')"));
        assertEquals("ATTR-A-2", scalar("SELECT mrn FROM public.current_patients() WHERE id = '" + duplicate + "'"));
        assertEquals(1, count("chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals("ACTIVE", scalar("SELECT status FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals(survivor, scalar("SELECT survivor_id::text FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals("ATTR-A-2", scalar("SELECT preserved_mrn FROM public.chart_association WHERE alias_id = '" + duplicate + "'"));
        assertEquals("CLINIC-B", scalar("SELECT preserved_issuer FROM public.chart_association WHERE alias_id = '" + duplicate + "'"),
                "the duplicate's own issuer is snapshotted, not the survivor's CLINIC-A");

        // The chart read path still lists the entry under its original patient, even though
        // the review read path now exposes the folded visibility.
        ResponseEntity<String> entry = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + entryId, null, "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.OK, entry.getStatusCode(), entry.getBody());
        assertEquals(duplicate, field(entry.getBody(), "patientId"));
        ResponseEntity<String> duplicateChart = request(HttpMethod.GET,
                "/api/v1/chart/records/patients/" + duplicate + "/entries", null, "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.OK, duplicateChart.getStatusCode(), duplicateChart.getBody());
        assertTrue(duplicateChart.getBody().contains(entryId));
        ResponseEntity<String> survivorChart = request(HttpMethod.GET,
                "/api/v1/chart/records/patients/" + survivor + "/entries", null, "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.OK, survivorChart.getStatusCode(), survivorChart.getBody());
        assertFalse(survivorChart.getBody().contains(entryId));
        ResponseEntity<String> detail = request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + survivor + "/" + duplicate, null, "admin-a", ORG_A, null);
        assertEquals(HttpStatus.OK, detail.getStatusCode(), detail.getBody());
        assertTrue(detail.getBody().contains("\"chartVisibilityId\":\"" + survivor + "\""));
    }

    /** A role without identity-administration permission cannot consolidate and re-attribute. */
    @Test
    void receptionRoleCannotConsolidateAndReattribute() throws Exception {
        String[] pair = twoDuplicates("ATTR-B", "reception-a");
        String survivor = pair[0];
        String duplicate = pair[1];
        String entryId = recordAllergy(duplicate, "synthetic role denial", "clinician-a", ORG_A);
        String reviewId = openReview(survivor, duplicate);

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approval("ATTR-B-1", "CLINIC-A", "ATTR-B-2", "CLINIC-B"), "reception-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
        assertEquals(0, count("chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"));
        assertEquals(duplicate, scalar("SELECT patient_id::text FROM public.chart_entry "
                + "WHERE id = CAST('" + entryId + "' AS uuid)"));
        assertEquals(duplicate, scalar("SELECT public.effective_patient_id(CAST('" + duplicate + "' AS uuid))::text"));
    }

    /** A peer tenant cannot see the identity or the entry, so it cannot consolidate or re-attribute it. */
    @Test
    void otherTenantCannotReadOrReattributeAlias() throws Exception {
        String[] pair = twoDuplicates("ATTR-C", "reception-a");
        String survivor = pair[0];
        String duplicate = pair[1];
        String entryId = recordAllergy(duplicate, "synthetic tenant concealment", "clinician-a", ORG_A);
        String reviewId = openReview(survivor, duplicate);

        // Each caller holds the right role in ORG_B, so a 404 is forced RLS concealment.
        ResponseEntity<String> hiddenReview = request(HttpMethod.GET,
                "/api/v1/duplicate-reviews/" + survivor + "/" + duplicate, null, "admin-b", ORG_B, null);
        assertEquals(HttpStatus.NOT_FOUND, hiddenReview.getStatusCode(), hiddenReview.getBody());
        assertEquals("NOT_FOUND", field(hiddenReview.getBody(), "code"));
        ResponseEntity<String> hiddenConsolidate = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approval("ATTR-C-1", "CLINIC-A", "ATTR-C-2", "CLINIC-B"), "admin-b", ORG_B, "\"1\"");
        assertEquals(HttpStatus.NOT_FOUND, hiddenConsolidate.getStatusCode(), hiddenConsolidate.getBody());
        assertEquals("NOT_FOUND", field(hiddenConsolidate.getBody(), "code"));
        ResponseEntity<String> hiddenEntry = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + entryId, null, "clinician-b", ORG_B, null);
        assertEquals(HttpStatus.NOT_FOUND, hiddenEntry.getStatusCode(), hiddenEntry.getBody());
        assertEquals("NOT_FOUND", field(hiddenEntry.getBody(), "code"));

        assertEquals(0, countInOrg(ORG_B, "chart_entry", "id = CAST('" + entryId + "' AS uuid)"));
        assertEquals(0, countInOrg(ORG_B, "patient_record", "id IN ('" + survivor + "','" + duplicate + "')"));
        assertEquals(0, countInOrg(ORG_B, "chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals(1, countInOrg(ORG_A, "chart_entry", "id = CAST('" + entryId + "' AS uuid) "
                + "AND patient_id = CAST('" + duplicate + "' AS uuid)"));
        assertEquals(0, countInOrg(ORG_A, "chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals(duplicate, scalar("SELECT patient_id::text FROM public.chart_entry "
                + "WHERE id = CAST('" + entryId + "' AS uuid)"));
        assertEquals(duplicate, scalar("SELECT public.effective_patient_id(CAST('" + duplicate + "' AS uuid))::text"));
    }

    /** A stale version precondition refuses the command and appends no alias or re-attribution. */
    @Test
    void staleVersionCannotReattributeAndWritesNoAssociation() throws Exception {
        String[] pair = twoDuplicates("ATTR-D", "reception-a");
        String survivor = pair[0];
        String duplicate = pair[1];
        String entryId = recordAllergy(duplicate, "synthetic stale version", "clinician-a", ORG_A);
        String reviewId = openReview(survivor, duplicate);

        ResponseEntity<String> stale = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approval("ATTR-D-1", "CLINIC-A", "ATTR-D-2", "CLINIC-B"), "approver-a", ORG_A, "\"9\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(0, count("chart_association", "alias_id = '" + duplicate + "'"));
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"));
        assertEquals(duplicate, scalar("SELECT patient_id::text FROM public.chart_entry "
                + "WHERE id = CAST('" + entryId + "' AS uuid)"));
        assertEquals(duplicate, scalar("SELECT public.effective_patient_id(CAST('" + duplicate + "' AS uuid))::text"));
    }
}
