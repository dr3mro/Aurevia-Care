package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import org.flywaydb.core.Flyway;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RX-01 clause 4 (API evidence): check against verified allergy ingredients and
 * surface unknowns and mismatches.
 *
 * <p>The evidence drives the real HTTP entry point over a real PostgreSQL
 * instance: only verified assertions may produce a conflict or a warning, while
 * an unknown, unverified or unmappable assessment is surfaced as unresolved and
 * never reported as safe. Negative cases prove that the route fails closed for
 * the wrong role, another tenant, a missing subject, a malformed organization
 * and an invalid catalog entry, and that a verified conflict cannot be selected
 * without a documented override.</p>
 *
 * <p>Every catalog entry, allergy assertion, draft and actor in this class is
 * synthetic. Forced row-level security confines each row to its active
 * organization. The fixture helpers are private to this class, following the
 * established clause-test pattern, so no shared production or test type is
 * changed.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_rx_allergy_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class CheckAgainstVerifiedAllergyIngredientsSurfaceUnknownsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_rx_allergy_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String CLINICIAN_A = "clinician-1111";
    private static final String RECEPTION_A = "reception-1111";
    private static final String CLINICIAN_B = "clinician-2222";

    private static final String PATIENT_LIST = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PATIENT_NONE = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String PATIENT_UNKNOWN = "aaaaaaaa-0000-0000-0000-000000000003";
    private static final String PATIENT_UNVERIFIED = "aaaaaaaa-0000-0000-0000-000000000004";
    private static final String PATIENT_PEER = "bbbbbbbb-0000-0000-0000-000000000001";

    private static final String AMOX500 = "aaaa1111-0000-0000-0000-000000000001";
    private static final String AMPICILLIN = "aaaa1111-0000-0000-0000-000000000003";
    private static final String CIPRO = "aaaa1111-0000-0000-0000-000000000004";
    private static final String UNMAPPED = "aaaa1111-0000-0000-0000-000000000005";

    private static final String MARKET = "synthetic-market-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate the dedicated database, apply the production migrations and seed synthetic facts. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_rx_allergy_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_rx_allergy_clause_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, CLINICIAN_A, "clinician");
            seedMember(statement, ORG_A, RECEPTION_A, "reception");
            seedMember(statement, ORG_B, CLINICIAN_B, "clinician");

            seedPatient(statement, ORG_A, PATIENT_LIST, "MRN-LIST");
            seedPatient(statement, ORG_A, PATIENT_NONE, "MRN-NONE");
            seedPatient(statement, ORG_A, PATIENT_UNKNOWN, "MRN-UNKNOWN");
            seedPatient(statement, ORG_A, PATIENT_UNVERIFIED, "MRN-UNVERIFIED");
            seedPatient(statement, ORG_B, PATIENT_PEER, "MRN-PEER");

            seedCatalog(statement, ORG_A);
            seedCatalog(statement, ORG_B);

            seedAllergyStatus(statement, ORG_A, "MRN-LIST", "verified_list");
            seedAllergy(statement, ORG_A, "MRN-LIST", "AMOX-SUB", "BETA_LACTAM", "verified");
            seedAllergyStatus(statement, ORG_A, "MRN-NONE", "verified_none");
            // MRN-UNKNOWN has no status row on purpose: the repository folds its
            // absence into the explicit UNKNOWN state rather than "no allergy".
            seedAllergyStatus(statement, ORG_A, "MRN-UNVERIFIED", "unverified");
            seedAllergy(statement, ORG_A, "MRN-UNVERIFIED", "AMOX-SUB", "BETA_LACTAM", "unverified");
        }
    }

    /** Drop the dedicated database so parallel workers never inherit these fixtures. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_rx_allergy_clause_it WITH (FORCE)");
        }
    }

    /** Create one tenant organization with its bootstrap actor. */
    private static void seedOrganization(Statement statement, String org) throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix + "', 'Bootstrap')");
    }

    /** Add one active member with a single role in the given organization. */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Add one synthetic patient with an explicit identifier and MRN. */
    private static void seedPatient(Statement statement, String org, String id, String mrn)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + id + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /** Seed the effective product entries used by the allergy checks, including an unmapped one. */
    private static void seedCatalog(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + org.substring(0, 4) + "'");
        seedDrug(statement, org, AMOX500, "Amoxicillin", "Amoxil", "AMOX-SUB", "BETA_LACTAM");
        seedDrug(statement, org, AMPICILLIN, "Ampicillin", "Ampicil", "AMP-SUB", "BETA_LACTAM");
        seedDrug(statement, org, CIPRO, "Ciprofloxacin", "Ciproxin", "CIPRO-SUB", "QUINOLONE");
        seedDrug(statement, org, UNMAPPED, "Unmapped", "Unmapped", null, null);
    }

    /** Insert an approved, currently effective catalog entry with its structured substance identity. */
    private static void seedDrug(Statement statement, String org, String key, String generic,
                                 String trade, String substance, String group) throws Exception {
        String arrays = substance == null
                ? "ARRAY[NULL::text]::text[], ARRAY[NULL::text]::text[], ARRAY[NULL::integer]::integer[]"
                : "ARRAY['" + substance + "']::text[], ARRAY['" + group + "']::text[], ARRAY[1]::integer[]";
        statement.execute("INSERT INTO public.drug_catalog_entry "
                + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                + " strength_value, strength_unit, substance_ids, substance_groups, group_versions, "
                + " approved, effective_from, approval_provenance) "
                + "VALUES ('" + org + "', '" + key + "', 1, '" + generic + "', '" + trade + "', 'tablet', "
                + "'500', 'mg', " + arrays + ", true, now() - interval '1 day', '" + MARKET + "')");
    }

    /** Record the overall allergy status for one synthetic patient. */
    private static void seedAllergyStatus(Statement statement, String org, String mrn, String status)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.patient_allergy_status "
                + "(organization_id, patient_id, status, source) "
                + "SELECT '" + org + "', id, '" + status + "', 'synthetic.fixture' "
                + "FROM public.patient_record WHERE mrn = '" + mrn + "'");
    }

    /** Record one synthetic allergy assertion with an explicit verification state. */
    private static void seedAllergy(Statement statement, String org, String mrn, String substance,
                                    String group, String verification) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.patient_allergy "
                + "(organization_id, patient_id, substance_id, group_code, group_version, "
                + " verification_state, source) "
                + "SELECT '" + org + "', id, '" + substance + "', '" + group + "', 1, '" + verification
                + "', 'synthetic.fixture' FROM public.patient_record WHERE mrn = '" + mrn + "'");
    }

    /** Build the synthetic authentication and tenant headers for one request. */
    private HttpHeaders headers(String subject, String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        return headers;
    }

    /** Send a request through the real HTTP server without throwing on error statuses. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    /** Send a request with extra headers through the real HTTP server. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, extra)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Create a draft for a synthetic patient and return its identifier. */
    private String createDraft(String org, String subject, String patientId) {
        String body = "{\"patientId\":\"" + patientId + "\",\"encounterReference\":\"synthetic-encounter\","
                + "\"destination\":\"synthetic-clinic\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/prescription-drafts",
                body, subject, org);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        String id = text(json(response), "/id");
        assertNotNull(id, "a created draft has an identifier");
        return id;
    }

    /** Run the allergy-check HTTP command for one catalog entry. */
    private ResponseEntity<String> check(String org, String subject, String draftId, String entryId) {
        return send(HttpMethod.POST, "/api/v1/prescription-drafts/" + draftId + "/allergy-check",
                "{\"catalogEntryId\":\"" + entryId + "\"}", subject, org);
    }

    /** Submit a medication selection with an optional documented override reason. */
    private ResponseEntity<String> select(String org, String subject, String draftId, int version,
                                          String entryId, String overrideReason) {
        String body = "{\"catalogEntryId\":\"" + entryId + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                + "\"route\":\"oral\",\"frequency\":\"once daily\",\"quantity\":\"10\","
                + "\"quantityUnit\":\"tablet\",\"instructions\":\"synthetic instruction\""
                + (overrideReason == null ? "" : ",\"overrideReason\":\"" + overrideReason + "\"") + "}";
        return send(HttpMethod.POST, "/api/v1/prescription-drafts/" + draftId + "/select-medication",
                body, subject, org, Map.of("If-Match", String.valueOf(version)));
    }

    /** Parse a response body into a JSON tree with a useful failure message. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse prescription response: " + response.getBody(),
                    exception);
        }
    }

    /** Read a scalar field, failing when the response does not carry the field at all. */
    private static String text(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + node);
        return value.isNull() ? null : value.asText();
    }

    /** Report whether the check retained a finding of the given kind. */
    private static boolean hasFinding(JsonNode check, String kind) {
        for (JsonNode finding : check.at("/findings")) {
            if (kind.equals(finding.path("kind").asText())) {
                return true;
            }
        }
        return false;
    }

    /** Read a field of the first retained finding of the given kind. */
    private static String findingField(JsonNode check, String kind, String field) {
        for (JsonNode finding : check.at("/findings")) {
            if (kind.equals(finding.path("kind").asText())) {
                return text(finding, "/" + field);
            }
        }
        return "";
    }

    @Test
    void verifiedAllergyCheckSurfacesConflictsWarningsAndUnknownsOverHttp() {
        String listDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);

        JsonNode exact = json(check(ORG_A, CLINICIAN_A, listDraft, AMOX500));
        assertEquals("conflict", text(exact, "/outcome"));
        assertEquals("verified_list", text(exact, "/allergyStatus"));
        assertEquals(MARKET, text(exact, "/catalogVersion"));
        assertTrue(hasFinding(exact, "exact_match"));

        JsonNode warning = json(check(ORG_A, CLINICIAN_A, listDraft, AMPICILLIN));
        assertEquals("warning", text(warning, "/outcome"));
        assertEquals("verified_list", text(warning, "/allergyStatus"));
        assertTrue(hasFinding(warning, "cross_reactivity"));

        String unknownDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_UNKNOWN);
        JsonNode unknown = json(check(ORG_A, CLINICIAN_A, unknownDraft, AMOX500));
        assertEquals("unresolved", text(unknown, "/outcome"));
        assertEquals("unknown", text(unknown, "/allergyStatus"));
        assertTrue(hasFinding(unknown, "incomplete_assessment"));

        String unverifiedDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_UNVERIFIED);
        JsonNode unverified = json(check(ORG_A, CLINICIAN_A, unverifiedDraft, AMOX500));
        assertEquals("unresolved", text(unverified, "/outcome"));
        assertEquals("unverified", text(unverified, "/allergyStatus"));
        assertTrue(hasFinding(unverified, "incomplete_assessment"));

        String noneDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_NONE);
        JsonNode unmapped = json(check(ORG_A, CLINICIAN_A, noneDraft, UNMAPPED));
        assertEquals("unresolved", text(unmapped, "/outcome"));
        assertEquals("verified_none", text(unmapped, "/allergyStatus"));
        assertTrue(hasFinding(unmapped, "unresolved_ingredient"));

        JsonNode clean = json(check(ORG_A, CLINICIAN_A, noneDraft, CIPRO));
        assertEquals("no_conflict", text(clean, "/outcome"));
        assertEquals("verified_none", text(clean, "/allergyStatus"));
        assertEquals(0, clean.at("/findings").size(), "a verified clean assessment has no findings");

        // A different approved group must not be treated as a match: only a
        // structured substance or an approved cross-reactivity group may match.
        JsonNode mismatch = json(check(ORG_A, CLINICIAN_A, listDraft, CIPRO));
        assertEquals("no_conflict", text(mismatch, "/outcome"));
        assertEquals("verified_list", text(mismatch, "/allergyStatus"));
        assertEquals(0, mismatch.at("/findings").size(), "a mismatched group retains no finding");
    }

    @Test
    void exactVerifiedIngredientSurfacesConflictOverHttp() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> response = check(ORG_A, CLINICIAN_A, draft, AMOX500);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode check = json(response);
        assertEquals("conflict", text(check, "/outcome"));
        assertEquals("verified_list", text(check, "/allergyStatus"));
        assertEquals(MARKET, text(check, "/catalogVersion"));
        assertTrue(hasFinding(check, "exact_match"));
        assertEquals("AMOX-SUB", findingField(check, "exact_match", "substanceId"));
        assertEquals("BETA_LACTAM", findingField(check, "exact_match", "group"));
    }

    @Test
    void sharedCrossReactivityGroupSurfacesWarningOverHttp() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> response = check(ORG_A, CLINICIAN_A, draft, AMPICILLIN);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode check = json(response);
        assertEquals("warning", text(check, "/outcome"));
        assertEquals("verified_list", text(check, "/allergyStatus"));
        assertTrue(hasFinding(check, "cross_reactivity"));
        assertEquals("AMP-SUB", findingField(check, "cross_reactivity", "substanceId"));
        assertEquals("BETA_LACTAM", findingField(check, "cross_reactivity", "group"));
        assertFalse(hasFinding(check, "exact_match"),
                "a different substance in the same group is a warning, not a conflict");
    }

    @Test
    void unknownUnverifiedAndUnmappedDataSurfaceUnresolvedOverHttp() {
        String unknownDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_UNKNOWN);
        JsonNode unknown = json(check(ORG_A, CLINICIAN_A, unknownDraft, AMOX500));
        assertEquals("unresolved", text(unknown, "/outcome"));
        assertEquals("unknown", text(unknown, "/allergyStatus"));
        assertTrue(hasFinding(unknown, "incomplete_assessment"));

        String unverifiedDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_UNVERIFIED);
        JsonNode unverified = json(check(ORG_A, CLINICIAN_A, unverifiedDraft, AMOX500));
        assertEquals("unresolved", text(unverified, "/outcome"));
        assertEquals("unverified", text(unverified, "/allergyStatus"));
        assertTrue(hasFinding(unverified, "incomplete_assessment"));
        assertFalse(hasFinding(unverified, "exact_match"),
                "an unverified assertion is surfaced but is not a verified contraindication");

        String noneDraft = createDraft(ORG_A, CLINICIAN_A, PATIENT_NONE);
        JsonNode unmapped = json(check(ORG_A, CLINICIAN_A, noneDraft, UNMAPPED));
        assertEquals("unresolved", text(unmapped, "/outcome"));
        assertEquals("verified_none", text(unmapped, "/allergyStatus"));
        assertTrue(hasFinding(unmapped, "unresolved_ingredient"));
    }

    @Test
    void verifiedNoneWithoutFindingsIsNoConflict() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_NONE);
        JsonNode check = json(check(ORG_A, CLINICIAN_A, draft, AMOX500));
        assertEquals("no_conflict", text(check, "/outcome"));
        assertEquals("verified_none", text(check, "/allergyStatus"));
        assertEquals(0, check.at("/findings").size());
    }

    @Test
    void receptionRoleIsDeniedTheAllergyCheck() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> response = check(ORG_A, RECEPTION_A, draft, AMOX500);
        assertEquals(403, response.getStatusCode().value(), response.getBody());
        assertEquals("FORBIDDEN", text(json(response), "/code"));
    }

    @Test
    void otherTenantCannotCheckAnotherOrganizationsDraft() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> response = check(ORG_B, CLINICIAN_B, draft, AMOX500);
        assertEquals(404, response.getStatusCode().value(), response.getBody());
        assertEquals("NOT_FOUND", text(json(response), "/code"));
    }

    @Test
    void missingSubjectReturnsUnauthorized() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> response = check(ORG_A, null, draft, AMOX500);
        assertEquals(401, response.getStatusCode().value(), response.getBody());
        assertEquals("UNAUTHENTICATED", text(json(response), "/code"));
    }

    @Test
    void malformedOrganizationReturnsUnprocessable() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> response = check("not-a-uuid", CLINICIAN_A, draft, AMOX500);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertEquals("INVALID_TENANT_SELECTION", text(json(response), "/code"));
    }

    @Test
    void invalidCatalogEntryIdIsRejected() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> malformed = check(ORG_A, CLINICIAN_A, draft, "not-a-uuid");
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_CATALOGENTRYID", text(json(malformed), "/code"));
        ResponseEntity<String> unknownEntry = check(ORG_A, CLINICIAN_A, draft,
                "55555555-5555-5555-5555-555555555555");
        assertEquals(422, unknownEntry.getStatusCode().value(),
                "an unknown catalog entry has no effective version");
        assertEquals("INVALID_CATALOG_ENTRY", text(json(unknownEntry), "/code"));
    }

    @Test
    void selectMedicationWithoutOverrideReasonIsRefusedOnConflict() {
        String draft = createDraft(ORG_A, CLINICIAN_A, PATIENT_LIST);
        ResponseEntity<String> refused = select(ORG_A, CLINICIAN_A, draft, 1, AMOX500, null);
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("ALLERGY_OVERRIDE_REQUIRED", text(json(refused), "/code"));

        // The refused selection must not leave a partial draft or override fact.
        JsonNode view = json(send(HttpMethod.GET, "/api/v1/prescription-drafts/" + draft,
                null, CLINICIAN_A, ORG_A));
        assertEquals("1", text(view, "/draft/version"));
        assertTrue(view.at("/override").isNull(), "no override is recorded for a refused selection");
    }
}
