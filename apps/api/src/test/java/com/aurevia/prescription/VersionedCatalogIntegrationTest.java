package com.aurevia.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import org.flywaydb.core.Flyway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * BUILD-09 (RX-01) acceptance on real PostgreSQL: versioned search, a draft
 * bound to the catalog version it was built from and rejection of unapproved or
 * retired items. All identifiers and clinical content are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_build09_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class VersionedCatalogIntegrationTest {

    private static final String ORG_A = "61111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "62222222-2222-2222-2222-222222222222";
    private static final String ORG_C = "63333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_build09_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN = "clinician-build09";

    private static final String APPROVED = "aaaa2222-0000-0000-0000-000000000001";
    private static final String UNAPPROVED = "aaaa2222-0000-0000-0000-000000000002";
    private static final String RETIRED = "aaaa2222-0000-0000-0000-000000000003";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS orevia_build09_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_build09_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A);
            seed(statement, ORG_B);
            seed(statement, ORG_C);
            // ORG_A publishes version 1; ORG_B exercises the baseline; ORG_C is
            // reserved for the version-publication test so tests stay independent.
            bind(statement, ORG_A);
            statement.execute("INSERT INTO public.medication_catalog_version "
                    + "(organization_id, version, market, provenance) VALUES "
                    + "('" + ORG_A + "', 1, 'synthetic-market', 'synthetic-market-v1')");
            bind(statement, ORG_C);
            statement.execute("INSERT INTO public.medication_catalog_version "
                    + "(organization_id, version, market, provenance) VALUES "
                    + "('" + ORG_C + "', 1, 'synthetic-market', 'synthetic-market-v1')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_build09_it WITH (FORCE)");
        }
    }

    private static void bind(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-build09'");
    }

    private static void seed(Statement statement, String org) throws Exception {
        bind(statement, org);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic Build09')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-build09', 'Bootstrap'), "
                + "('" + org + "', '" + CLINICIAN + "', 'Clinician')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + CLINICIAN + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'clinician', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + CLINICIAN + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-B09', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
        statement.execute("INSERT INTO public.patient_allergy_status "
                + "(organization_id, patient_id, status, source) "
                + "SELECT '" + org + "', id, 'verified_none', 'synthetic.fixture' "
                + "FROM public.patient_record WHERE mrn = 'MRN-B09'");
        statement.execute("INSERT INTO public.drug_catalog_entry "
                + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                + " strength_value, strength_unit, route, substance_ids, substance_groups, group_versions, "
                + " approved, effective_from, effective_to, approval_provenance) VALUES "
                + "('" + org + "', '" + APPROVED + "', 1, 'Amoxicillin', 'Amoxil', 'tablet', "
                + " '500', 'mg', 'oral', ARRAY['AMOX-SUB']::text[], ARRAY['BETA_LACTAM']::text[], "
                + " ARRAY[1]::integer[], true, now() - interval '2 days', null, 'synthetic-market-v1'), "
                + "('" + org + "', '" + UNAPPROVED + "', 1, 'Unapproved', 'Unapproved', 'tablet', "
                + " '500', 'mg', 'oral', ARRAY['UNP-SUB']::text[], ARRAY['UNP-GROUP']::text[], "
                + " ARRAY[1]::integer[], false, now() - interval '2 days', null, NULL), "
                + "('" + org + "', '" + RETIRED + "', 1, 'Retired', 'Retired', 'tablet', "
                + " '500', 'mg', 'oral', ARRAY['RET-SUB']::text[], ARRAY['RET-GROUP']::text[], "
                + " ARRAY[1]::integer[], true, now() - interval '4 days', now() - interval '2 days', "
                + " 'synthetic-market-v1')");
    }

    private HttpHeaders headers(String org) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", CLINICIAN);
        headers.set("X-Active-Organization", org);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String org) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(org)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String patientId(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bind(statement, org);
            try (java.sql.ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-B09'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private String createDraft(String org) throws Exception {
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"encounterReference\":null,\"destination\":null}";
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/prescription-drafts", body, org);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        return json(created).at("/id").asText();
    }

    private ResponseEntity<String> select(String org, String draftId, int version, String entryId) {
        return select(org, draftId, version, entryId, "oral");
    }

    private ResponseEntity<String> select(String org, String draftId, int version, String entryId, String route) {
        String body = "{\"catalogEntryId\":\"" + entryId + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                + "\"route\":\"" + route + "\",\"frequency\":\"once daily\",\"quantity\":\"10\","
                + "\"quantityUnit\":\"tablet\",\"instructions\":\"synthetic\"}";
        return rest.method(HttpMethod.POST)
                .uri("/api/v1/prescription-drafts/" + draftId + "/select-medication")
                .headers(httpHeaders -> {
                    httpHeaders.addAll(headers(org));
                    httpHeaders.set("If-Match", String.valueOf(version));
                })
                .body(body).retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    @Test
    void versionedSearchReturnsThePublishedVersionAndApprovedRoute() {
        JsonNode result = json(send(HttpMethod.GET, "/api/v1/drug-catalog/search-with-version?query=amox", null, ORG_A));
        assertEquals(1, result.at("/version/version").asInt());
        assertEquals("synthetic-market", result.at("/version/market").asText());
        assertEquals("synthetic-market-v1", result.at("/version/provenance").asText());
        JsonNode hits = result.at("/items");
        assertEquals(1, hits.size(), "only the approved, effective entry is searchable");
        assertEquals(APPROVED, hits.at("/0/drug/entryId").asText());
        assertEquals("oral", hits.at("/0/route").asText());
    }

    @Test
    void baselineVersionIsReturnedWhenNoVersionWasPublished() {
        JsonNode result = json(send(HttpMethod.GET, "/api/v1/drug-catalog/search-with-version?query=amox", null, ORG_B));
        assertEquals(1, result.at("/version/version").asInt());
        assertEquals("synthetic-baseline", result.at("/version/provenance").asText());
        assertEquals(1, result.at("/items").size());
    }

    @Test
    void aDraftKeepsItsCatalogVersionAfterANewPublication() throws Exception {
        String first = createDraft(ORG_C);
        assertEquals(200, select(ORG_C, first, 1, APPROVED).getStatusCode().value());
        JsonNode firstBinding = json(send(HttpMethod.GET,
                "/api/v1/prescription-drafts/" + first + "/catalog-version", null, ORG_C));
        assertEquals(first, firstBinding.at("/draftId").asText());
        assertEquals(1, firstBinding.at("/catalogVersion").asInt());

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            bind(statement, ORG_C);
            statement.execute("INSERT INTO public.medication_catalog_version "
                    + "(organization_id, version, market, provenance) VALUES "
                    + "('" + ORG_C + "', 2, 'synthetic-market', 'synthetic-market-v2')");
        }

        String second = createDraft(ORG_C);
        assertEquals(200, select(ORG_C, second, 1, APPROVED).getStatusCode().value());
        JsonNode secondBinding = json(send(HttpMethod.GET,
                "/api/v1/prescription-drafts/" + second + "/catalog-version", null, ORG_C));
        assertEquals(2, secondBinding.at("/catalogVersion").asInt(), "a new draft uses the new version");

        assertEquals(200, select(ORG_C, first, 2, APPROVED).getStatusCode().value());
        JsonNode stillFirst = json(send(HttpMethod.GET,
                "/api/v1/prescription-drafts/" + first + "/catalog-version", null, ORG_C));
        assertEquals(1, stillFirst.at("/catalogVersion").asInt(),
                "an old draft keeps the version it was built from");
        assertEquals(2, json(send(HttpMethod.GET,
                "/api/v1/drug-catalog/search-with-version?query=amox", null, ORG_C))
                .at("/version/version").asInt(), "search reports the current version");
    }

    @Test
    void aRouteThatContradictsTheApprovedCatalogRouteIsRejected() throws Exception {
        String draft = createDraft(ORG_A);
        ResponseEntity<String> wrongRoute = select(ORG_A, draft, 1, APPROVED, "intrathecal");
        assertEquals(422, wrongRoute.getStatusCode().value());
        assertEquals("ROUTE_MISMATCH", json(wrongRoute).at("/code").asText());
        JsonNode view = json(send(HttpMethod.GET,
                "/api/v1/prescription-drafts/" + draft, null, ORG_A));
        assertTrue(view.at("/draft/selectedEntryId").isNull(), "a wrong-route selection is not persisted");
    }

    @Test
    void unapprovedAndRetiredItemsCannotBeAddedToANewDraft() throws Exception {
        String draft = createDraft(ORG_A);
        ResponseEntity<String> unapproved = select(ORG_A, draft, 1, UNAPPROVED);
        assertEquals(422, unapproved.getStatusCode().value());
        assertEquals("INVALID_CATALOG_ENTRY", json(unapproved).at("/code").asText());

        ResponseEntity<String> retired = select(ORG_A, draft, 1, RETIRED);
        assertEquals(422, retired.getStatusCode().value());
        assertEquals("INVALID_CATALOG_ENTRY", json(retired).at("/code").asText());
        JsonNode binding = json(send(HttpMethod.GET,
                "/api/v1/prescription-drafts/" + draft + "/catalog-version", null, ORG_A));
        assertTrue(binding.at("/catalogVersion").isNull(), "a failed selection never binds a version");
        JsonNode view = json(send(HttpMethod.GET,
                "/api/v1/prescription-drafts/" + draft, null, ORG_A));
        assertTrue(view.at("/draft/selectedEntryId").isNull(), "no medication was selected");
    }
}
