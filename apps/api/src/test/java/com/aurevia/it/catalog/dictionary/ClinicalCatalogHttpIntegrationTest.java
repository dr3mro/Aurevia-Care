package com.aurevia.it.catalog.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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

import com.aurevia.catalog.dictionary.ClinicalDictionaryService;
import com.aurevia.configuration.ConfigurationService;

import tools.jackson.databind.ObjectMapper;

/**
 * HTTP-level acceptance for the wired CLN-04 clinical catalog endpoints.
 *
 * <p>Proves a real request reaches the services through the controller, drafts
 * stay invisible until a named approval by a distinct reviewer, two tenants with
 * identical local keys stay isolated, and a freshly constructed service reads the
 * same version back from durable storage. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_cln_dictionary_it")
@ActiveProfiles("synthetic-auth")
class ClinicalCatalogHttpIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_cln_dictionary_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    ConfigurationService configurations;
    @Autowired
    ObjectMapper json;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln_dictionary_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_cln_dictionary_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_A, "reviewer-a", "tenant_admin");
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln_dictionary_it WITH (FORCE)");
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

    private static String dictionaryBody() {
        return "{\"fields\":[{\"code\":\"chief_concern\","
                + "\"label\":{\"text\":\"Chief concern\",\"languageTag\":\"en\"},"
                + "\"requiredness\":\"REQUIRED\",\"allowedValues\":[]}]}";
    }

    private static String diagnosisBody() {
        return "{\"codes\":[{\"code\":\"R51\","
                + "\"originalText\":{\"text\":\"Headache\",\"languageTag\":\"en\"},"
                + "\"mappedCode\":null,\"mappedText\":null}]}";
    }

    @Test
    void dictionaryDraftBecomesEffectiveOnlyAfterADistinctReviewerApprovesIt() {
        ResponseEntity<String> defined = request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/versions",
                dictionaryBody(), "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, defined.getStatusCode(), defined.getBody());
        assertEquals("1", defined.getBody());

        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/effective", null, "admin-a", ORG_A)
                .getStatusCode());

        ResponseEntity<String> selfApproval = request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/versions/1/approve",
                "{\"note\":\"self\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, selfApproval.getStatusCode(), selfApproval.getBody());

        ResponseEntity<String> approved = request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/versions/1/approve",
                "{\"note\":\"governance\"}", "reviewer-a", ORG_A);
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
        assertTrue(approved.getBody().contains("chief_concern"));

        ResponseEntity<String> effective = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/effective", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        assertTrue(effective.getBody().contains("Chief concern"));

        ResponseEntity<String> history = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/history", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, history.getStatusCode());
        assertTrue(history.getBody().contains("governance"));
    }

    @Test
    void diagnosisSearchUsesTheApprovedVersionAndTenantsStayIsolated() {
        ResponseEntity<String> defined = request(HttpMethod.POST,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/versions",
                diagnosisBody(), "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, defined.getStatusCode(), defined.getBody());
        ResponseEntity<String> beforeApproval = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/search?query=head",
                null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, beforeApproval.getStatusCode(), beforeApproval.getBody());
        assertEquals("[]", beforeApproval.getBody());

        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/versions/1/approve",
                "{\"note\":\"governance\"}", "reviewer-a", ORG_A).getStatusCode());
        ResponseEntity<String> found = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/search?query=head",
                null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, found.getStatusCode(), found.getBody());
        assertTrue(found.getBody().contains("R51"));

        ResponseEntity<String> effective = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/effective", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, effective.getStatusCode(), effective.getBody());
        assertTrue(effective.getBody().contains("R51"));

        ResponseEntity<String> blank = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/search?query=%20",
                null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, blank.getStatusCode(), blank.getBody());
        assertEquals("[]", blank.getBody());

        // The same local key in a peer tenant is concealed until that tenant defines and approves its own.
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/effective", null, "admin-b", ORG_B)
                .getStatusCode());
        assertFalse(request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/effective", null, "admin-b", ORG_B)
                .getBody().contains("R51"));
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/versions",
                diagnosisBody(), "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/versions/1/approve",
                "{\"note\":\"governance\"}", "reviewer-b", ORG_B).getStatusCode());
        ResponseEntity<String> history = request(HttpMethod.GET,
                "/api/v1/clinical-catalog/diagnosis/diagnosis_catalog/history", null, "admin-a", ORG_A);
        assertEquals(1, history.getBody().split("\"version\"", -1).length - 1);
    }

    @Test
    void authorizationAndBoundaryInputsAreRejected() {
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/versions",
                dictionaryBody(), "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/versions",
                dictionaryBody(), null, ORG_A).getStatusCode());
        ResponseEntity<String> empty = request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/intake_dictionary/versions",
                "{\"fields\":[]}", "admin-a", ORG_A);
        assertEquals(422, empty.getStatusCode().value(), empty.getBody());
    }

    @Test
    void aFreshServiceInstanceReadsTheDurableVersionBack() {
        request(HttpMethod.POST, "/api/v1/clinical-catalog/dictionaries/durable_dictionary/versions",
                dictionaryBody(), "admin-a", ORG_A);
        request(HttpMethod.POST,
                "/api/v1/clinical-catalog/dictionaries/durable_dictionary/versions/1/approve",
                "{\"note\":\"governance\"}", "reviewer-a", ORG_A);

        ClinicalDictionaryService fresh = new ClinicalDictionaryService(configurations, json);
        TenantContextScope.run(ORG_A, "admin-a", () -> {
            assertEquals(1, fresh.effective("durable_dictionary").version());
            assertEquals("chief_concern",
                    fresh.effective("durable_dictionary").entries().get(0).code());
        });
    }

    /** Bind a tenant context for a directly invoked service assertion, then clear it. */
    private static final class TenantContextScope {
        private TenantContextScope() {
        }

        static void run(String org, String subject, Runnable assertion) {
            com.aurevia.tenant.TenantContext.set(
                    new com.aurevia.tenant.TenantContext(org, subject, "CATALOG", "corr-it"));
            try {
                assertion.run();
            } finally {
                com.aurevia.tenant.TenantContext.clear();
            }
        }
    }
}
