package com.aurevia.prescription.clauses;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RX-01 clause 5 acceptance on a fresh real-PostgreSQL database: a reviewed
 * allergy override keeps the clinician's reason, the authenticated author and a
 * deterministic snapshot of the exact matched catalog and allergy data on an
 * append-only record. The author is derived by the V43 database trigger from
 * {@code app.actor}, never from an (unknown, silently ignored) JSON field, and a
 * peer tenant cannot see the row because forced RLS confines it to the active
 * organization. Fixture setup follows the neighbouring HTTP integration tests but
 * is re-implemented locally with a class-unique database because their seed
 * helpers are private and their database would collide with parallel workers.
 * Every catalog entry, allergy statement, patient and subject is synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_override_reason_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class KeepOverrideReasonAuthorMatchedDataTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CLINICIAN_A = "clinician-1111";
    private static final String CLINICIAN_B = "clinician-2222";
    private static final String DB = "orevia_override_reason_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String AMOX500 = "aaaa1111-0000-0000-0000-000000000001";
    private static final String CIPRO = "aaaa1111-0000-0000-0000-000000000004";
    private static final String REASON = "documented clinical override for synthetic patient";
    private static final String SPOOF_AUTHOR = CLINICIAN_B;
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedMember(statement, org, "clinician-" + suffix(org), "clinician");
            }
            for (String org : new String[] {ORG_A, ORG_B}) {
                seedCatalog(statement, org);
            }
            // ORG_A verifies an exact allergy to AMOX-SUB; selecting AMOX500 is a
            // conflict that must be documented before it can be overridden.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + CLINICIAN_A + "'");
            statement.execute("INSERT INTO public.patient_allergy_status "
                    + "(organization_id, patient_id, status, source) "
                    + "SELECT '" + ORG_A + "', id, 'verified_list', 'synthetic.fixture' "
                    + "FROM public.patient_record WHERE mrn = 'MRN-1'");
            statement.execute("INSERT INTO public.patient_allergy "
                    + "(organization_id, patient_id, substance_id, group_code, group_version, "
                    + " verification_state, source) "
                    + "SELECT '" + ORG_A + "', id, 'AMOX-SUB', 'BETA_LACTAM', 1, "
                    + "'verified', 'synthetic.fixture' "
                    + "FROM public.patient_record WHERE mrn = 'MRN-1'");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String suffix = suffix(org);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
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
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + suffix(org) + "'");
        seedDrug(statement, org, AMOX500, "Amoxicillin", "AMOX-SUB", "BETA_LACTAM");
        seedDrug(statement, org, CIPRO, "Ciprofloxacin", "CIPRO-SUB", "QUINOLONE");
    }

    private static void seedDrug(Statement statement, String org, String key, String generic,
                                 String substance, String group) throws Exception {
        statement.execute("INSERT INTO public.drug_catalog_entry "
                + "(organization_id, entry_key, version, generic_name, trade_name, formulation, "
                + " strength_value, strength_unit, substance_ids, substance_groups, group_versions, "
                + " approved, effective_from, approval_provenance) "
                + "VALUES ('" + org + "', '" + key + "', 1, '" + generic + "', '" + generic + "', 'tablet', "
                + "'500', 'mg', ARRAY['" + substance + "']::text[], ARRAY['" + group + "']::text[], "
                + "ARRAY[1]::integer[], true, now() - interval '1 day', 'synthetic-market-v1')");
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    /** Persistent projection of the immutable override, read through forced RLS. */
    private record OverrideRow(String reason, String author, String snapshot, int draftVersion) {
    }

    private static OverrideRow overrideRow(String org, String subject, String draftId) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT override_reason, author_subject, matched_snapshot, draft_version "
                            + "FROM public.prescription_override "
                            + "WHERE prescription_draft_id = CAST(? AS uuid) "
                            + "ORDER BY created_at DESC, id DESC LIMIT 1")) {
                statement.setString(1, draftId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? new OverrideRow(result.getString(1), result.getString(2),
                                    result.getString(3), result.getInt(4))
                            : null;
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int overrideCount(String org, String subject, String draftId) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.prescription_override "
                            + "WHERE prescription_draft_id = CAST(? AS uuid)")) {
                statement.setString(1, draftId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> create(String org, String subject, String patientId) {
        String body = "{\"patientId\":\"" + patientId + "\",\"encounterReference\":\"synthetic-encounter\","
                + "\"destination\":\"synthetic-clinic\"}";
        return send(HttpMethod.POST, "/api/v1/prescription-drafts", body, subject, org, Map.of());
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/prescription-drafts/" + id, null, subject, org, Map.of());
    }

    private static String selectBody(String entryId, String overrideReason, String spoofedAuthor) {
        return "{\"catalogEntryId\":\"" + entryId + "\",\"doseValue\":\"500\",\"doseUnit\":\"mg\","
                + "\"route\":\"oral\",\"frequency\":\"once daily\",\"quantity\":\"10\","
                + "\"quantityUnit\":\"tablet\",\"refills\":2,\"instructions\":\"synthetic instruction\""
                + (overrideReason == null ? "" : ",\"overrideReason\":\"" + overrideReason + "\"")
                + (spoofedAuthor == null ? "" : ",\"authorSubject\":\"" + spoofedAuthor + "\"") + "}";
    }

    private ResponseEntity<String> select(String org, String subject, String draftId, int version,
                                          String entryId, String overrideReason) {
        return select(org, subject, draftId, version, entryId, overrideReason, null);
    }

    private ResponseEntity<String> select(String org, String subject, String draftId, int version,
                                          String entryId, String overrideReason, String spoofedAuthor) {
        return send(HttpMethod.POST, "/api/v1/prescription-drafts/" + draftId + "/select-medication",
                selectBody(entryId, overrideReason, spoofedAuthor), subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private static String text(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + node);
        return value.isNull() ? null : value.asText();
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        return text(json(response), pointer);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void documentedOverrideKeepsReasonAuthorAndMatchedSnapshot() {
        String id = text(create(ORG_A, CLINICIAN_A, patientId(ORG_A)), "/id");

        JsonNode check = json(send(HttpMethod.POST,
                "/api/v1/prescription-drafts/" + id + "/allergy-check",
                "{\"catalogEntryId\":\"" + AMOX500 + "\"}", CLINICIAN_A, ORG_A, Map.of()));
        assertEquals("conflict", text(check, "/outcome"));
        assertEquals("exact_match", text(check, "/findings/0/kind"));
        assertEquals("AMOX-SUB", text(check, "/findings/0/substanceId"));

        // authorSubject is not a component of SelectMedicationRequest: Spring's
        // default Jackson configuration ignores the unknown field, so a client
        // cannot claim authorship. The V43 trigger must derive it from app.actor.
        ResponseEntity<String> selected = select(ORG_A, CLINICIAN_A, id, 1, AMOX500, REASON, SPOOF_AUTHOR);
        assertEquals(HttpStatus.OK, selected.getStatusCode(), selected.getBody());

        JsonNode view = json(get(ORG_A, CLINICIAN_A, id));
        assertEquals("2", text(view, "/draft/version"));
        assertEquals(AMOX500, text(view, "/draft/selectedEntryId"));
        assertEquals(REASON, text(view, "/override/reason"));
        assertEquals(CLINICIAN_A, text(view, "/override/authorSubject"));
        String snapshot = text(view, "/override/matchedSnapshot");
        assertNotNull(snapshot);
        assertEquals("catalog=" + AMOX500 + ":1;allergy=verified_list;outcome=conflict;"
                + "findings=exact_match:AMOX-SUB:BETA_LACTAM", snapshot, snapshot);

        OverrideRow row = overrideRow(ORG_A, CLINICIAN_A, id);
        assertNotNull(row, "the override is persisted");
        assertEquals(REASON, row.reason());
        assertEquals(CLINICIAN_A, row.author());
        assertEquals(snapshot, row.snapshot());
        assertEquals(1, row.draftVersion());
    }

    @Test
    void verifiedConflictWithoutReasonIsRefused() {
        String id = text(create(ORG_A, CLINICIAN_A, patientId(ORG_A)), "/id");

        ResponseEntity<String> refused = select(ORG_A, CLINICIAN_A, id, 1, AMOX500, null);

        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("ALLERGY_OVERRIDE_REQUIRED", text(refused, "/code"));
        assertEquals(0, overrideCount(ORG_A, CLINICIAN_A, id));
    }

    @Test
    void blankAndOverlongReasonsAreRefused() {
        String id = text(create(ORG_A, CLINICIAN_A, patientId(ORG_A)), "/id");

        // optionalText maps blank to null, so the conflict rule refuses it (409).
        ResponseEntity<String> blank = select(ORG_A, CLINICIAN_A, id, 1, AMOX500, "   ");
        assertEquals(409, blank.getStatusCode().value(), blank.getBody());
        assertEquals("ALLERGY_OVERRIDE_REQUIRED", text(blank, "/code"));
        assertEquals(0, overrideCount(ORG_A, CLINICIAN_A, id));

        // A present reason over the 1000-character boundary fails validation (422).
        ResponseEntity<String> overlong = select(ORG_A, CLINICIAN_A, id, 1, AMOX500, "x".repeat(1001));
        assertEquals(422, overlong.getStatusCode().value(), overlong.getBody());
        assertEquals("INVALID_OVERRIDEREASON", text(overlong, "/code"));
        assertEquals(0, overrideCount(ORG_A, CLINICIAN_A, id));
    }

    @Test
    void staleVersionCannotAttachAnOverride() {
        String id = text(create(ORG_A, CLINICIAN_A, patientId(ORG_A)), "/id");

        ResponseEntity<String> refused = select(ORG_A, CLINICIAN_A, id, 9, AMOX500, REASON);

        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", text(refused, "/code"));
        assertEquals(0, overrideCount(ORG_A, CLINICIAN_A, id));
    }

    @Test
    void peerTenantCannotReadTheOverride() {
        String id = text(create(ORG_A, CLINICIAN_A, patientId(ORG_A)), "/id");
        assertEquals(HttpStatus.OK, select(ORG_A, CLINICIAN_A, id, 1, AMOX500, REASON).getStatusCode());
        assertEquals(1, overrideCount(ORG_A, CLINICIAN_A, id));

        ResponseEntity<String> peerRead = get(ORG_B, CLINICIAN_B, id);
        assertEquals(404, peerRead.getStatusCode().value(), peerRead.getBody());
        assertEquals(0, overrideCount(ORG_B, CLINICIAN_B, id),
                "forced RLS hides the peer tenant's override row");
    }

    @Test
    void overrideRowIsAppendOnly() {
        String id = text(create(ORG_A, CLINICIAN_A, patientId(ORG_A)), "/id");
        assertEquals(HttpStatus.OK, select(ORG_A, CLINICIAN_A, id, 1, AMOX500, REASON).getStatusCode());

        Exception update = assertThrows(Exception.class, () -> execute(ORG_A, CLINICIAN_A,
                "UPDATE public.prescription_override SET override_reason = 'rewritten' "
                        + "WHERE prescription_draft_id = '" + id + "'"));
        assertTrue(update.getMessage().contains("append-only"), update.getMessage());
        Exception delete = assertThrows(Exception.class, () -> execute(ORG_A, CLINICIAN_A,
                "DELETE FROM public.prescription_override WHERE prescription_draft_id = '" + id + "'"));
        assertTrue(delete.getMessage().contains("append-only"), delete.getMessage());

        assertEquals(REASON, text(get(ORG_A, CLINICIAN_A, id), "/override/reason"));
        assertEquals(REASON, overrideRow(ORG_A, CLINICIAN_A, id).reason());
    }
}
