package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import org.flywaydb.core.Flyway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S-G4-03 acceptance (RX-01) on a real PostgreSQL instance.
 *
 * <p>Four synthetic organizations share local patient and catalog identifiers so
 * tenant isolation is observable. Every catalog entry, allergy assertion and
 * narrative is synthetic; no patient data or credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_rx01_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class PrescriptionFlowTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String ORG_D = "44444444-4444-4444-4444-444444444444";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_rx01_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    static final String AMOX500 = "aaaa1111-0000-0000-0000-000000000001";
    static final String AMOX250 = "aaaa1111-0000-0000-0000-000000000002";
    static final String AMPICILLIN = "aaaa1111-0000-0000-0000-000000000003";
    static final String CIPRO = "aaaa1111-0000-0000-0000-000000000004";
    static final String UNMAPPED = "aaaa1111-0000-0000-0000-000000000005";

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_rx01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_rx01_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D}) {
                String suffix = org.substring(0, 4);
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedMember(statement, org, "auditor-" + suffix, "privacy_auditor");
                seedMember(statement, org, "reception-" + suffix, "reception");
            }
            // A dedicated clinician is revoked by one test so no shared fixture is poisoned.
            seedMember(statement, ORG_A, "revocable-1111", "clinician");
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D}) {
                seedCatalog(statement, org);
            }
            // A: verified exact allergy; B: verified none; C: no assessment;
            // D: unverified assertion. All synthetic.
            seedAllergy(statement, ORG_A, "verified_list", "verified");
            seedAllergy(statement, ORG_B, "verified_none", null);
            seedAllergy(statement, ORG_C, null, null);
            seedAllergy(statement, ORG_D, "unverified", "unverified");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_rx01_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

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

    private static void seedCatalog(Statement statement, String org) throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + suffix + "'");
        seedDrug(statement, org, AMOX500, "Amoxicillin", "Amoxil", "AMOX-SUB", "BETA_LACTAM", true, false);
        seedDrug(statement, org, AMOX250, "Amoxicillin", "Amoxil", "AMOX-SUB", "BETA_LACTAM", true, false);
        seedDrug(statement, org, AMPICILLIN, "Ampicillin", "Ampicil", "AMP-SUB", "BETA_LACTAM", true, false);
        seedDrug(statement, org, CIPRO, "Ciprofloxacin", "Ciproxin", "CIPRO-SUB", "QUINOLONE", true, false);
        seedDrug(statement, org, UNMAPPED, "Unmapped", "Unmapped", null, null, true, false);
        seedDrug(statement, org, "bbbb1111-0000-0000-0000-000000000001", "Future", "Future",
                "FUTURE-SUB", "FUTURE-GROUP", true, true);
        seedDrug(statement, org, "bbbb1111-0000-0000-0000-000000000002", "Unapproved", "Unapproved",
                "UNP-SUB", "UNP-GROUP", false, false);
    }

    private static void seedDrug(Statement statement, String org, String key, String generic,
                                 String trade, String substance, String group, boolean approved,
                                 boolean future) throws Exception {
        String arrays = substance == null
                ? "ARRAY[NULL::text]::text[], ARRAY[NULL::text]::text[], ARRAY[NULL::integer]::integer[]"
                : "ARRAY['" + substance + "']::text[], ARRAY['" + group + "']::text[], ARRAY[1]::integer[]";
        statement.execute("INSERT INTO public.drug_catalog_entry "
                + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                + " strength_value, strength_unit, substance_ids, substance_groups, group_versions, "
                + " approved, effective_from, approval_provenance) "
                + "VALUES ('" + org + "', '" + key + "', 1, '" + generic + "', '" + trade + "', 'tablet', "
                + "'500', 'mg', " + arrays + ", " + approved + ", "
                + (future ? "now() + interval '1 day'" : "now() - interval '1 day'") + ", "
                + (approved ? "'synthetic-market-v1'" : "NULL") + ")");
    }

    private static void seedAllergy(Statement statement, String org, String status, String verification)
            throws Exception {
        String suffix = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + suffix + "'");
        if (status != null) {
            statement.execute("INSERT INTO public.patient_allergy_status "
                    + "(organization_id, patient_id, status, source) "
                    + "SELECT '" + org + "', id, '" + status + "', 'synthetic.fixture' "
                    + "FROM public.patient_record WHERE mrn = 'MRN-1'");
        }
        if (verification != null) {
            statement.execute("INSERT INTO public.patient_allergy "
                    + "(organization_id, patient_id, substance_id, group_code, group_version, "
                    + " verification_state, source) "
                    + "SELECT '" + org + "', id, 'AMOX-SUB', 'BETA_LACTAM', 1, '" + verification
                    + "', 'synthetic.fixture' FROM public.patient_record WHERE mrn = 'MRN-1'");
        }
    }

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

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, extra)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> create(String org, String subject, String patientId) {
        String body = "{\"patientId\":\"" + patientId + "\",\"encounterReference\":\"synthetic-encounter\","
                + "\"destination\":\"synthetic-clinic\"}";
        return send(HttpMethod.POST, "/api/v1/prescription-drafts", body, subject, org);
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/prescription-drafts/" + id, null, subject, org);
    }

    private ResponseEntity<String> search(String org, String subject, String query, String formulation) {
        String uri = "/api/v1/drug-catalog/search";
        if (query != null || formulation != null) {
            uri += "?" + (query == null ? "" : "query=" + query)
                    + (formulation == null ? "" : (query == null ? "" : "&") + "formulation=" + formulation);
        }
        return send(HttpMethod.GET, uri, null, subject, org);
    }

    private ResponseEntity<String> check(String org, String subject, String draftId, String entryId) {
        String body = "{\"catalogEntryId\":\"" + entryId + "\"}";
        return send(HttpMethod.POST, "/api/v1/prescription-drafts/" + draftId + "/allergy-check",
                body, subject, org);
    }

    private ResponseEntity<String> select(String org, String subject, String draftId, int version,
                                          String entryId, String overrideReason) {
        String body = "{\"catalogEntryId\":\"" + entryId + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                + "\"route\":\"oral\",\"frequency\":\"once daily\",\"quantity\":\"10\","
                + "\"quantityUnit\":\"tablet\",\"refills\":2,\"instructions\":\"synthetic instruction\""
                + (overrideReason == null ? "" : ",\"overrideReason\":\"" + overrideReason + "\"") + "}";
        return send(HttpMethod.POST, "/api/v1/prescription-drafts/" + draftId + "/select-medication",
                body, subject, org, Map.of("If-Match", String.valueOf(version)));
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String actorId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity WHERE subject = ?")) {
                statement.setString(1, subject);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static void execute(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        return text(json(response), pointer);
    }

    private static String text(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + node);
        return value.isNull() ? null : value.asText();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void fullWorkflowKeepsValidationOverrideAndAuditSeparate() {
        String org = ORG_A;
        String clinician = "clinician-1111";
        ResponseEntity<String> created = create(org, clinician, patientId(org));
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");
        assertEquals("1", text(created, "/version"));
        assertEquals("draft", text(created, "/state"));
        assertEquals(clinician, text(created, "/prescriberSubject"),
                "the prescriber is derived from the authenticated actor");

        JsonNode hits = json(search(org, clinician, "amox", null));
        JsonNode amox = null;
        for (JsonNode hit : hits) {
            if (AMOX500.equals(hit.at("/entry/entryId").asText())) {
                amox = hit;
            }
        }
        assertNotNull(amox, "the approved catalog entry is searchable");
        JsonNode alternatives = amox.at("/alternatives");
        assertTrue(alternatives.size() >= 1);
        assertEquals("suggestion", alternatives.at("/0/kind").asText());
        assertEquals("true", alternatives.at("/0/requiresClinicianSelection").asText());

        ResponseEntity<String> check = check(org, clinician, id, AMOX500);
        assertEquals(HttpStatus.OK, check.getStatusCode(), check.getBody());
        assertEquals("conflict", text(check, "/outcome"));
        assertEquals("exact_match", text(check, "/findings/0/kind"));
        assertEquals("synthetic-market-v1", text(check, "/catalogVersion"));

        assertEquals(409, select(org, clinician, id, 1, AMOX500, null).getStatusCode().value(),
                "a verified exact match needs a documented override");
        assertEquals("ALLERGY_OVERRIDE_REQUIRED", text(select(org, clinician, id, 1, AMOX500, null), "/code"));

        ResponseEntity<String> selected = select(org, clinician, id, 1, AMOX500,
                "documented clinical override");
        assertEquals(HttpStatus.OK, selected.getStatusCode(), selected.getBody());
        assertEquals("2", text(selected, "/version"));
        assertEquals(AMOX500, text(selected, "/selectedEntryId"));
        assertEquals("Amoxicillin", text(selected, "/drugGenericName"));

        JsonNode view = json(get(org, clinician, id));
        assertNotNull(view.at("/override/reason"));
        assertEquals("documented clinical override", text(view, "/override/reason"));
        assertEquals(clinician, text(view, "/override/authorSubject"),
                "the override author is DB-derived, never client supplied");
        assertTrue(text(view, "/override/matchedSnapshot").contains("catalog=" + AMOX500 + ":1"));
        assertTrue(text(view, "/override/matchedSnapshot").contains("exact_match"));
        assertTrue(auditCount(org, id, "RX_SELECT_MEDICATION") >= 1);
        assertEquals(1, auditCount(org, id, "RX_CREATE_DRAFT"));
    }

    @Test
    void allergyStatusesAreDistinctAndNeverSilentlySafe() {
        String a = "clinician-1111";
        String b = "clinician-2222";
        String c = "clinician-3333";
        String d = "clinician-4444";

        String idA = text(create(ORG_A, a, patientId(ORG_A)), "/id");
        assertEquals("conflict", text(check(ORG_A, a, idA, AMOX500), "/outcome"));
        assertEquals("warning", text(check(ORG_A, a, idA, AMPICILLIN), "/outcome"),
                "a shared approved cross-reactivity group is a warning");

        String idB = text(create(ORG_B, b, patientId(ORG_B)), "/id");
        assertEquals("no_conflict", text(check(ORG_B, b, idB, AMOX500), "/outcome"));
        assertEquals("unresolved", text(check(ORG_B, b, idB, UNMAPPED), "/outcome"),
                "an unmapped ingredient is surfaced even with verified NKA");

        String idC = text(create(ORG_C, c, patientId(ORG_C)), "/id");
        ResponseEntity<String> unknown = check(ORG_C, c, idC, AMOX500);
        assertEquals("unresolved", text(unknown, "/outcome"));
        assertEquals("unknown", text(unknown, "/allergyStatus"));
        assertEquals("incomplete_assessment", text(unknown, "/findings/0/kind"));

        String idD = text(create(ORG_D, d, patientId(ORG_D)), "/id");
        assertEquals("unresolved", text(check(ORG_D, d, idD, AMOX500), "/outcome"));
        assertEquals("unverified", text(check(ORG_D, d, idD, AMOX500), "/allergyStatus"));
    }

    @Test
    void versionPreconditionsAndAllergyConflictAreEnforced() {
        String org = ORG_A;
        String clinician = "clinician-1111";
        String id = text(create(org, clinician, patientId(org)), "/id");

        assertEquals(428, send(HttpMethod.POST, "/api/v1/prescription-drafts/" + id + "/select-medication",
                "{\"catalogEntryId\":\"" + CIPRO + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                        + "\"route\":\"oral\",\"frequency\":\"once daily\",\"quantity\":\"10\","
                        + "\"quantityUnit\":\"tablet\",\"instructions\":\"synthetic\"}",
                clinician, org).getStatusCode().value());
        assertEquals(412, select(org, clinician, id, 9, CIPRO, null).getStatusCode().value());
        assertEquals("STALE_VERSION", text(select(org, clinician, id, 9, CIPRO, null), "/code"));
        assertEquals(HttpStatus.OK, select(org, clinician, id, 1, CIPRO, null).getStatusCode());
        assertEquals(412, select(org, clinician, id, 1, CIPRO, null).getStatusCode().value(),
                "the second selection uses the stale version");
    }

    @Test
    void authorizationAndTenantIsolationHoldOnEveryCommand() throws Exception {
        String org = ORG_A;
        String peer = ORG_B;
        assertEquals(HttpStatus.UNAUTHORIZED, create(org, null, patientId(org)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, create(org, "reception-1111", patientId(org)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                create(org, "clinician-2222", patientId(org)).getStatusCode(), "a peer clinician is denied");
        assertEquals(404, create(org, "clinician-1111", patientId(peer)).getStatusCode().value(),
                "a cross-tenant patient is concealed");

        String id = text(create(org, "clinician-1111", patientId(org)), "/id");
        assertEquals(HttpStatus.NOT_FOUND, get(peer, "clinician-2222", id).getStatusCode());
        assertEquals(HttpStatus.OK, get(org, "auditor-1111", id).getStatusCode(),
                "a privacy auditor may read without prescribing rights");
        assertEquals(HttpStatus.FORBIDDEN, search(org, "reception-1111", null, null).getStatusCode());

        assertEquals(HttpStatus.OK, check(org, "auditor-1111", id, AMOX500).getStatusCode(),
                "a privacy auditor may read decision support without prescribing rights");
        execute(org, "admin-1111", "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'revoked' FROM public.user_identity "
                + "WHERE subject = 'revocable-1111'");
        assertEquals(HttpStatus.FORBIDDEN, get(org, "revocable-1111", id).getStatusCode(),
                "revocation denies the very next protected request");
        assertEquals(2, auditCount(org, id, "RX_READ_DRAFT"),
                "the auditor read and the revoked clinician's denial are both audited");
    }

    @Test
    void invalidInputsAreRejected() {
        String org = ORG_A;
        String clinician = "clinician-1111";
        assertEquals(422, create(org, clinician, "not-a-uuid").getStatusCode().value());
        assertEquals(404, create(org, clinician, "55555555-5555-5555-5555-555555555555")
                .getStatusCode().value(), "an invisible patient is concealed");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/prescription-drafts", "{}", clinician, org)
                .getStatusCode().value());

        String id = text(create(org, clinician, patientId(org)), "/id");
        assertEquals(404, get(org, clinician, "55555555-5555-5555-5555-555555555555").getStatusCode().value());
        assertEquals(422, get(org, clinician, "not-a-uuid").getStatusCode().value());
        assertEquals(422, check(org, clinician, id, "not-a-uuid").getStatusCode().value());
        assertEquals(422, check(org, clinician, id, "55555555-5555-5555-5555-555555555555").getStatusCode().value(),
                "an unknown catalog entry is rejected");

        String badRefills = "{\"catalogEntryId\":\"" + CIPRO + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                + "\"route\":\"oral\",\"frequency\":\"daily\",\"quantity\":\"10\","
                + "\"quantityUnit\":\"tablet\",\"refills\":99,\"instructions\":\"synthetic\"}";
        assertEquals(422, send(HttpMethod.POST, "/api/v1/prescription-drafts/" + id + "/select-medication",
                badRefills, clinician, org, Map.of("If-Match", "1")).getStatusCode().value());
        assertEquals(422, select(org, clinician, id, 1, CIPRO, "x".repeat(1001)).getStatusCode().value());
    }

    @Test
    void searchHidesUnapprovedAndFutureVersionsAndForcesRLS() {
        String clinician = "clinician-1111";
        JsonNode hits = json(search(ORG_A, clinician, null, null));
        boolean unapproved = false;
        boolean future = false;
        for (JsonNode hit : hits) {
            String id = hit.at("/entry/entryId").asText();
            unapproved |= "bbbb1111-0000-0000-0000-000000000002".equals(id);
            future |= "bbbb1111-0000-0000-0000-000000000001".equals(id);
        }
        assertFalse(unapproved, "an unapproved entry is not searchable");
        assertFalse(future, "a future-dated entry does not apply early");
        assertFalse(hits.isEmpty(), "at least one approved entry is searchable");

        String idA = text(create(ORG_A, clinician, patientId(ORG_A)), "/id");
        String idB = text(create(ORG_B, "clinician-2222", patientId(ORG_B)), "/id");
        assertFalse(idA.equals(idB));
        assertEquals(404, get(ORG_B, "clinician-2222", idA).getStatusCode().value());
    }

    @Test
    void immutableLedgerAndDatabaseVersionGuardHold() throws Exception {
        String org = ORG_A;
        String clinician = "clinician-1111";
        String id = text(create(org, clinician, patientId(org)), "/id");
        assertEquals(HttpStatus.OK, select(org, clinician, id, 1, AMOX500, "documented override")
                .getStatusCode());

        assertEquals("documented override", text(json(get(org, clinician, id)), "/override/reason"));
        Exception update = assertThrows(Exception.class, () -> execute(org, clinician,
                "UPDATE public.prescription_override "
                        + "SET override_reason = 'rewritten' WHERE prescription_draft_id = '" + id + "'"));
        assertTrue(update.getMessage().contains("append-only"));
        assertThrows(Exception.class, () -> execute(org, clinician,
                "DELETE FROM public.prescription_draft WHERE id = '" + id + "'"));
        assertThrows(Exception.class, () -> execute(org, clinician,
                "UPDATE public.prescription_draft SET version = version + 2 WHERE id = '" + id + "'"));
        assertEquals("2", text(get(org, clinician, id), "/draft/version"),
                "a rejected direct update left the row unchanged");
    }

    private long auditCount(String org, String resourceId, String action) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE resource_id = ? AND action = ?")) {
                statement.setString(1, resourceId);
                statement.setString(2, action);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
