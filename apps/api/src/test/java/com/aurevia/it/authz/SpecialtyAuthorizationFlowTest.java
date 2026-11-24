package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
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
 * S-G4-06 acceptance (PRD-06, IAM-01/02, ARC-01/02/03, OPS-02) on real PostgreSQL.
 *
 * <p>Two synthetic organizations share identical local subject and patient
 * identifiers, so role scope and tenant isolation are observable. Every
 * narrative, identifier and document reference is synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_specialty_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class SpecialtyAuthorizationFlowTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_specialty_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final List<String> ROLES = List.of(
            "lab_collector", "lab_verifier", "radiology_staff", "radiology_interpreter",
            "pt_clinician", "pharmacy_staff", "clinician", "tenant_admin",
            "privacy_auditor", "reception");

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_specialty_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_specialty_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : List.of(ORG_A, ORG_B)) {
                seedOrganization(statement, org);
            }
            for (String org : List.of(ORG_A, ORG_B)) {
                for (String role : ROLES) {
                    seedMember(statement, org, role + "-" + suffix(org), role);
                }
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_specialty_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix(org) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
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

    private static String suffix(String org) {
        return org.substring(0, 4);
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

    private ResponseEntity<String> permissions(String org, String subject) {
        return send(HttpMethod.GET, "/api/v1/specialty/permissions", null, subject, org);
    }

    private String attest(String org, String subject, String action, String role, String patientId,
                          String revision) {
        return attest(org, subject, action, role, patientId, revision, "ORD-1");
    }

    private ResponseEntity<String> attestResponse(String org, String subject, String action, String role,
                                                  String patientId, String revision) {
        String body = "{\"actionKey\":\"" + action + "\",\"roleKey\":\"" + role + "\","
                + "\"patientId\":\"" + patientId + "\",\"resourceRef\":\"ORD-1\","
                + "\"attestedText\":\"synthetic clinical decision\"}";
        return send(HttpMethod.POST, "/api/v1/specialty/attestations", body, subject, org,
                revision == null ? Map.of() : Map.of("If-Match", revision));
    }

    private String attest(String org, String subject, String action, String role, String patientId,
                          String revision, String resourceRef) {
        String body = "{\"actionKey\":\"" + action + "\",\"roleKey\":\"" + role + "\","
                + "\"patientId\":\"" + patientId + "\",\"resourceRef\":\"" + resourceRef + "\","
                + "\"attestedText\":\"synthetic clinical decision\"}";
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/specialty/attestations", body,
                subject, org, revision == null ? Map.of() : Map.of("If-Match", revision));
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return text(response, "/id");
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/specialty/attestations/" + id, null, subject, org);
    }

    private ResponseEntity<String> list(String org, String subject, String query) {
        return send(HttpMethod.GET, "/api/v1/specialty/attestations" + query, null, subject, org);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
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
            bind(connection, org, "lab_verifier-" + suffix(org));
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

    private static boolean executes(String org, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean executesWithoutActor(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                statement.setString(1, org);
                statement.execute();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long auditCount(String org, String action, String outcome) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "lab_verifier-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE action = ? AND outcome = ?")) {
                statement.setString(1, action);
                statement.setString(2, outcome);
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

    @Test
    void permissionsReflectOnlyActiveSpecialtyRoles() {
        JsonNode verifier = json(permissions(ORG_A, "lab_verifier-1111"));
        assertEquals("lab_verifier-1111", verifier.at("/subject").asText());
        assertEquals(ORG_A, verifier.at("/organizationId").asText());
        assertEquals(1, verifier.at("/roles").size());
        assertEquals("lab_verifier", verifier.at("/roles/0").asText());
        List<String> actions = new java.util.ArrayList<>();
        for (JsonNode node : verifier.at("/actions")) {
            actions.add(node.asText());
        }
        assertTrue(actions.contains("SPECIALTY_LAB_VERIFY_RELEASE"));
        assertTrue(actions.contains("SPECIALTY_ATTEST"));
        assertFalse(actions.contains("SPECIALTY_LAB_ACCESSION"));
        assertFalse(actions.contains("SPECIALTY_RAD_SIGN_INTERPRETATION"));

        JsonNode collector = json(permissions(ORG_A, "lab_collector-1111"));
        assertTrue(collector.at("/actions").toString().contains("SPECIALTY_LAB_ACCESSION"));
        assertFalse(collector.at("/actions").toString().contains("SPECIALTY_LAB_VERIFY_RELEASE"));
        JsonNode admin = json(permissions(ORG_A, "tenant_admin-1111"));
        assertFalse(admin.at("/actions").toString().contains("SPECIALTY_ATTEST"));
    }

    @Test
    void fullAttestationFlowIsHumanAuthorizedDerivableAndAudited() {
        String org = ORG_A;
        String subject = "lab_verifier-1111";
        long before = auditCount(org, "SPECIALTY_ATTEST", "SUCCESS");
        String id = attest(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier",
                patientId(org), "3", "ACC-1");

        ResponseEntity<String> response = get(org, subject, id);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(HttpStatus.CREATED, attestResponse(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE",
                "lab_verifier", patientId(org), "3").getStatusCode());
        assertFalse(id.isBlank());
        assertEquals("1", text(response, "/version"));
        assertEquals("SPECIALTY_LAB_VERIFY_RELEASE", text(response, "/actionKey"));
        assertEquals("lab_verifier", text(response, "/roleKey"));
        assertEquals("RELEASE", text(response, "/decision"));
        assertEquals(subject, text(response, "/subject"));
        assertEquals(actorId(org, subject), text(response, "/actorId"));
        assertEquals("3", text(response, "/attestedRevision"));
        assertEquals("ACC-1", text(response, "/resourceRef"));
        assertEquals("synthetic clinical decision", text(response, "/attestedText"));
        assertNotNull(text(response, "/occurredAt"));

        assertEquals(before + 2, auditCount(org, "SPECIALTY_ATTEST", "SUCCESS"));
        JsonNode list = json(list(org, subject, "?limit=10"));
        assertTrue(list.isArray());
        boolean found = false;
        for (JsonNode node : list) {
            if (id.equals(node.at("/id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "the attestation is visible in the bounded tenant list");
    }

    @Test
    void separationOfDutiesAndRoleSpoofingAreDenied() {
        String org = ORG_A;
        String patient = patientId(org);
        long before = auditCount(org, "SPECIALTY_ATTEST", "DENIED");

        assertEquals(403, attestResponse(org, "lab_collector-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "lab_collector", patient, "1").getStatusCode().value());
        assertEquals(403, attestResponse(org, "radiology_staff-1111", "SPECIALTY_RAD_SIGN_INTERPRETATION",
                "radiology_staff", patient, "1").getStatusCode().value());
        assertEquals(403, attestResponse(org, "reception-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "reception", patient, "1").getStatusCode().value());
        assertEquals(403, attestResponse(org, "pharmacy_staff-1111", "SPECIALTY_PHARMACY_REVIEW",
                "pharmacy_staff", patient, "1").getStatusCode().value());
        assertEquals(403, attestResponse(org, "lab_verifier-1111", "SPECIALTY_RAD_SIGN_INTERPRETATION",
                "lab_verifier", patient, "1").getStatusCode().value());
        assertEquals(403, attestResponse(org, "lab_verifier-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "radiology_interpreter", patient, "1").getStatusCode().value(),
                "a declared role that is not active cannot authorize the action");

        JsonNode denial = json(attestResponse(org, "lab_collector-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "lab_collector", patient, "1"));
        assertEquals("FORBIDDEN", denial.at("/code").asText());
        assertEquals(before + 7, auditCount(org, "SPECIALTY_ATTEST", "DENIED"));
    }

    @Test
    void administrativeAndClericalRolesCannotReadClinicalAttestations() {
        String org = ORG_A;
        String id = attest(org, "lab_verifier-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "lab_verifier", patientId(org), "1", "ACC-ADMIN");

        assertEquals(HttpStatus.FORBIDDEN, get(org, "tenant_admin-1111", id).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get(org, "reception-1111", id).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, list(org, "tenant_admin-1111", "").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, list(org, "privacy_auditor-1111", "").getStatusCode());
        assertEquals(HttpStatus.OK, get(org, "pharmacy_staff-1111", id).getStatusCode(),
                "clinical readers can see the evidence; administrators cannot");
    }

    @Test
    void crossTenantResourcesAndSubjectsAreConcealed() {
        String patientA = patientId(ORG_A);
        String id = attest(ORG_A, "lab_verifier-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "lab_verifier", patientA, "1", "ACC-A");

        assertEquals(HttpStatus.NOT_FOUND, get(ORG_B, "lab_verifier-2222", id).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, attestResponse(ORG_B, "lab_verifier-2222",
                "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier", patientA, "1").getStatusCode(),
                "a patient from another tenant is concealed");
        assertEquals(HttpStatus.FORBIDDEN, attestResponse(ORG_B, "lab_verifier-1111",
                "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier", patientId(ORG_B), "1").getStatusCode(),
                "a subject with no membership in the active tenant fails closed");
        JsonNode listB = json(list(ORG_B, "lab_verifier-2222", ""));
        for (JsonNode node : listB) {
            assertFalse(id.equals(node.at("/id").asText()));
        }
    }

    @Test
    void revokedMembershipFailsClosedOnTheNextRequest() throws Exception {
        String org = ORG_A;
        String id = attest(org, "lab_verifier-1111", "SPECIALTY_LAB_VERIFY_RELEASE",
                "lab_verifier", patientId(org), "1", "ACC-REVOKE");
        execute(org, "tenant_admin-1111", "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'revoked' FROM public.user_identity "
                + "WHERE subject = 'lab_verifier-1111'");

        assertEquals(HttpStatus.FORBIDDEN, permissions(org, "lab_verifier-1111").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get(org, "lab_verifier-1111", id).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, attestResponse(org, "lab_verifier-1111",
                "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier", patientId(org), "1").getStatusCode());
        assertEquals(HttpStatus.OK, get(org, "clinician-1111", id).getStatusCode(),
                "another authorized clinical role can still read the retained evidence");
    }

    @Test
    void invalidInputsAndPreconditionsAreRejected() {
        String org = ORG_A;
        String patient = patientId(org);
        String subject = "lab_verifier-1111";

        assertEquals(HttpStatus.UNAUTHORIZED, permissions(org, null).getStatusCode());
        assertEquals(422, send(HttpMethod.GET, "/api/v1/specialty/permissions", null, subject, "not-a-uuid")
                .getStatusCode().value());
        assertEquals(428, attestResponse(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier",
                patient, null).getStatusCode().value());
        assertEquals(422, attestResponse(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier",
                patient, "nope").getStatusCode().value());
        assertEquals(422, attestResponse(org, subject, "UNKNOWN_ACTION", "lab_verifier",
                patient, "1").getStatusCode().value());
        assertEquals(422, attestResponse(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE", "unknown-role",
                patient, "1").getStatusCode().value());
        assertEquals(422, attestResponse(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier",
                "not-a-uuid", "1").getStatusCode().value());
        assertEquals(422, attestResponse(org, subject, "SPECIALTY_ATTESTATION_READ", "lab_verifier",
                patient, "1").getStatusCode().value(), "a non-decision action cannot be attested");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/specialty/attestations",
                "{\"actionKey\":\"SPECIALTY_LAB_VERIFY_RELEASE\",\"roleKey\":\"lab_verifier\","
                        + "\"patientId\":\"" + patient + "\",\"resourceRef\":\"ORD\",\"attestedText\":\"   \"}",
                subject, org, Map.of("If-Match", "1")).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND, get(org, subject, "99999999-9999-9999-9999-999999999999")
                .getStatusCode());
        assertEquals(422, get(org, subject, "not-a-uuid").getStatusCode().value());
        assertEquals(422, list(org, subject, "?limit=0").getStatusCode().value());
        assertEquals(422, list(org, subject, "?limit=101").getStatusCode().value());
        assertEquals(422, list(org, subject, "?limit=abc").getStatusCode().value());
    }

    @Test
    void attestationLedgerIsAppendOnlyActorDerivedAndTenantScoped() throws Exception {
        String org = ORG_A;
        String subject = "lab_verifier-1111";
        String id = attest(org, subject, "SPECIALTY_LAB_VERIFY_RELEASE", "lab_verifier",
                patientId(org), "7", "ACC-IMMUTABLE");

        assertFalse(executes(org, subject, "UPDATE public.specialty_attestation "
                + "SET attested_text = 'changed' WHERE id = '" + id + "'"));
        assertFalse(executes(org, subject, "DELETE FROM public.specialty_attestation WHERE id = '" + id + "'"));
        assertFalse(executesWithoutActor(org, "INSERT INTO public.specialty_attestation "
                + "(organization_id, patient_id, action_key, role_key, decision, attested_revision, "
                + "attested_text, resource_ref) "
                + "VALUES ('" + org + "', '" + patientId(org) + "', 'SPECIALTY_LAB_VERIFY_RELEASE', "
                + "'lab_verifier', 'RELEASE', 1, 'no actor', 'ORD')"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "")) {
            assertEquals(0L, rows(connection, "SELECT count(*) FROM public.specialty_attestation"),
                    "no tenant context exposes no rows");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), false)")) {
                statement.setString(1, ORG_A);
                statement.execute();
            }
            assertTrue(rows(connection, "SELECT count(*) FROM public.specialty_attestation") > 0);
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT relrowsecurity, relforcerowsecurity "
                     + "FROM pg_class WHERE oid = 'public.specialty_attestation'::regclass")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
            assertTrue(result.getBoolean(2));
        }
    }

    private static long rows(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
