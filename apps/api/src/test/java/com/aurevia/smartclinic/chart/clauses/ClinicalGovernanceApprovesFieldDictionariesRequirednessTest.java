package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.springframework.beans.factory.annotation.Autowired;
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
 * CLN-04 clause 6 (API evidence): clinical governance approves field dictionaries
 * and requiredness.
 *
 * <p>The active tests drive the only reachable dictionary HTTP entry point and
 * prove the parts of the clause that are actually built: a draft carrying
 * {@code REQUIRED} and {@code OPTIONAL} field definitions is concealed until a
 * distinct named approval, and that approval and both requiredness values are
 * preserved in the effective version and in history. Negative tests prove that
 * the rule refuses a draft before approval, a self-approval, a peer tenant and
 * an empty field set.</p>
 *
 * <p>The reachable approval action is granted only to {@code tenant_admin} by
 * {@code authz.ActionPolicy}. The {@code clinical_governance} role named by the
 * clause lives in {@code clinical.templates.TemplateService}, which has no
 * HTTP controller, so that half cannot be proven yet; the complete test for it
 * is recorded in the clause note for a follow-up task. All fixtures are
 * synthetic and forced row level security confines every row to the active
 * organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_governance_it")
@ActiveProfiles("synthetic-auth")
class ClinicalGovernanceApprovesFieldDictionariesRequirednessTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_governance_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    static final String APPROVAL_NOTE = "governance-approval-c06";

    static String reviewerUserId;

    @Autowired
    ObjectMapper json;

    @LocalServerPort
    int port;

    RestClient rest;

    /** Create the isolated database, migrate it and seed the synthetic role fixtures. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln_governance_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln_governance_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            reviewerUserId = seedUser(statement, ORG_A, "reviewer-a", "tenant_admin");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    /** Remove the isolated database after the suite so later classes start clean. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln_governance_it WITH (FORCE)");
        }
    }

    /** Seed one active organization member with one role and return its user id. */
    private static String seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
        try (ResultSet result = statement.executeQuery("SELECT id FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "'")) {
            result.next();
            return result.getString(1);
        }
    }

    /** Bind the HTTP client to the randomly assigned server port for each test. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** A required and an optional field, each with an explicit localized label. */
    private static String dictionaryBody() {
        return "{\"fields\":["
                + "{\"code\":\"chief_concern\",\"label\":{\"text\":\"Chief concern\",\"languageTag\":\"en\"},"
                + "\"requiredness\":\"REQUIRED\",\"allowedValues\":[]},"
                + "{\"code\":\"care_plan\",\"label\":{\"text\":\"Care plan\",\"languageTag\":\"en\"},"
                + "\"requiredness\":\"OPTIONAL\",\"allowedValues\":[]}]}";
    }

    /** Send one synthetic request and keep the real status and body for assertions. */
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
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Define a pending dictionary version as the given synthetic subject. */
    private ResponseEntity<String> define(String key, String subject, String org) {
        return request(HttpMethod.POST, "/api/v1/clinical-catalog/dictionaries/" + key + "/versions",
                dictionaryBody(), subject, org);
    }

    /** Approve a dictionary version with a named note as the given synthetic subject. */
    private ResponseEntity<String> approve(String key, int version, String subject, String org) {
        return request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/" + key + "/versions/" + version + "/approve",
                "{\"note\":\"" + APPROVAL_NOTE + "\"}", subject, org);
    }

    /** Read the effective version of a dictionary as the given synthetic subject. */
    private ResponseEntity<String> effective(String key, String subject, String org) {
        return request(HttpMethod.GET, "/api/v1/clinical-catalog/dictionaries/" + key + "/effective",
                null, subject, org);
    }

    /** Parse an HTTP body so assertions can address nested version and approval fields. */
    private JsonNode tree(ResponseEntity<String> response) {
        String body = response.getBody();
        return json.readTree(body == null ? "{}" : body);
    }

    /**
     * A draft stays concealed until a distinct reviewer approves it; the approval
     * and both requiredness values then survive in the effective version and in
     * history. The reachable approver is a {@code tenant_admin} because that is
     * the only role {@code ActionPolicy} grants the approval action to; the
     * governance-actor half is recorded in the clause note for a follow-up task.
     */
    @Test
    void governanceApprovalMakesARequirednessDictionaryEffective() {
        String key = "governance_requiredness_dict";
        ResponseEntity<String> defined = define(key, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, defined.getStatusCode(), defined.getBody());
        assertEquals("1", defined.getBody());

        assertEquals(HttpStatus.NOT_FOUND, effective(key, "admin-a", ORG_A).getStatusCode(),
                "a draft must not be effective before approval");

        ResponseEntity<String> approved = approve(key, 1, "reviewer-a", ORG_A);
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
        JsonNode approvedVersion = tree(approved);
        assertEquals(APPROVAL_NOTE, approvedVersion.get("approval").get("note").asString());
        assertEquals(reviewerUserId, approvedVersion.get("approval").get("approver").asString());
        assertEquals("REQUIRED", approvedVersion.get("entries").get(0).get("requiredness").asString());
        assertEquals("OPTIONAL", approvedVersion.get("entries").get(1).get("requiredness").asString());
        assertEquals("en", approvedVersion.get("entries").get(0).get("label").get("languageTag").asString());

        ResponseEntity<String> effective = effective(key, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        JsonNode effectiveVersion = tree(effective);
        assertEquals("REQUIRED", effectiveVersion.get("entries").get(0).get("requiredness").asString());
        assertEquals("OPTIONAL", effectiveVersion.get("entries").get(1).get("requiredness").asString());
        assertEquals(APPROVAL_NOTE, effectiveVersion.get("approval").get("note").asString());

        ResponseEntity<String> history = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/dictionaries/" + key + "/history", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, history.getStatusCode(), history.getBody());
        JsonNode historyVersions = tree(history);
        assertEquals(1, historyVersions.size());
        assertEquals("REQUIRED", historyVersions.get(0).get("entries").get(0).get("requiredness").asString());
        assertEquals(APPROVAL_NOTE, historyVersions.get(0).get("approval").get("note").asString());
    }

    /** A defined but unapproved dictionary has no effective version. */
    @Test
    void unapprovedDictionaryIsNotEffective() {
        String key = "unapproved_dictionary";
        assertEquals(HttpStatus.OK, define(key, "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, effective(key, "admin-a", ORG_A).getStatusCode(),
                "a draft must stay concealed without an approval");
    }

    /** The author cannot approve their own draft, so no version is published. */
    @Test
    void authorCannotApproveOwnDictionary() {
        String key = "self_approval_dictionary";
        assertEquals(HttpStatus.OK, define(key, "admin-a", ORG_A).getStatusCode());

        ResponseEntity<String> selfApproval = approve(key, 1, "admin-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, selfApproval.getStatusCode(), selfApproval.getBody());
        assertEquals(HttpStatus.NOT_FOUND, effective(key, "reviewer-a", ORG_A).getStatusCode(),
                "a refused self-approval must not publish a version");
    }

    /** Forced row level security hides a peer tenant's draft from both reads and approval. */
    @Test
    void anotherTenantCannotReadOrApproveTheDictionary() {
        String key = "tenant_scoped_dictionary";
        assertEquals(HttpStatus.OK, define(key, "admin-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.NOT_FOUND, effective(key, "admin-b", ORG_B).getStatusCode(),
                "a draft must be concealed from another tenant");
        ResponseEntity<String> crossTenantApproval = approve(key, 1, "admin-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, crossTenantApproval.getStatusCode(),
                crossTenantApproval.getBody());
        assertEquals(HttpStatus.NOT_FOUND, effective(key, "admin-a", ORG_A).getStatusCode(),
                "a cross-tenant approval must not publish a version in the owning tenant");
    }

    /** A dictionary with no field definitions is refused and never becomes effective. */
    @Test
    void emptyFieldsAreRefused() {
        String key = "empty_fields_dictionary";
        ResponseEntity<String> empty = request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/" + key + "/versions",
                "{\"fields\":[]}", "admin-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT.value(), empty.getStatusCode().value(), empty.getBody());
        assertEquals(HttpStatus.NOT_FOUND, effective(key, "admin-a", ORG_A).getStatusCode(),
                "a refused draft must not become effective");
    }
}
