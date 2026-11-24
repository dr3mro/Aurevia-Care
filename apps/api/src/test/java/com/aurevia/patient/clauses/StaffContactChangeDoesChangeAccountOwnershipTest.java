package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IAM-05 clause 4 acceptance over the real patient-access HTTP route (PAT-03).
 *
 * <p>A staff member may replace a verified patient's recovery contact, but the
 * account it belongs to must keep the same identity, organization, verified chart
 * links and fact digest. Positive and negative requests travel through the normal
 * HTTP adapter, which derives the caller from the bound tenant context and freshly
 * resolved active roles; the durable row is read back under forced row-level
 * security. Two tenants share a chart identifier so a cross-tenant denial proves
 * isolation rather than absence. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_iam05_clause4_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class StaffContactChangeDoesChangeAccountOwnershipTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACTS = "site-verified-facts";
    private static final String NEW_CONTACT = "new-site-verified-contact";
    private static final String BASE = "/api/v1/patient/access";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_iam05_clause4_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String RECEPTION = "reception-clause4";
    private static final String RECEPTION_B = "reception-clause4-b";
    private static final String PRIVACY = "privacy-clause4";
    private static final String UNKNOWN_ACCOUNT = "33333333-3333-3333-3333-333333333333";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Create the roles, a fresh database and the two synthetic tenants.
     *
     * <p>Roles and the database are created in one method: JUnit does not order
     * separate {@code @BeforeAll} methods, so a later role creation would leave
     * {@code CREATE DATABASE ... OWNER aurevia_migrate} without its owner.</p>
     */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam05_clause4_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_iam05_clause4_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "MRN-IAM05-C4-A");
            seedTenant(statement, ORG_B, "MRN-IAM05-C4-B");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, PRIVACY, "privacy_auditor");
            seedMember(statement, ORG_B, RECEPTION_B, "reception");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam05_clause4_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client once the random port is assigned.
     *
     * @param port server port chosen by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Seed one tenant with a chart, a seeder membership and forced RLS in force. */
    private static void seedTenant(Statement statement, String org, String mrn) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Synthetic " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'synthetic-seeder', 'Seeder')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + CHART + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /** Seed a member with an active membership and one bounded role. */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** A unique patient subject so each test's verified account cannot collide. */
    private static String freshPatient() {
        return "syn-c4-" + UUID.randomUUID();
    }

    /** Seed a fresh patient identity with an active patient role in one tenant. */
    private static void seedPatient(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, org, subject, "patient");
        }
    }

    /** Send a tenant-scoped request, never expecting a rejected status to throw. */
    private ResponseEntity<String> send(HttpMethod method, String path, String subject, String org, String body) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .contentType(MediaType.APPLICATION_JSON);
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, String subject, String org, String body) {
        return send(HttpMethod.POST, BASE + path, subject, org, body);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return send(HttpMethod.GET, BASE + path, subject, org, null);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse patient-access response: " + response.getBody(), exception);
        }
    }

    private static String field(ResponseEntity<String> response, String name) {
        JsonNode node = tree(response).get(name);
        assertTrue(node != null, "missing response field " + name);
        return node.asText();
    }

    private static String issueBody() {
        return "{\"chartId\":\"" + CHART + "\",\"relationship\":{\"relationshipId\":\"enc\",\"siteVerified\":true},"
                + "\"factDigest\":\"" + FACTS + "\"}";
    }

    private String issueInvitation(String org) {
        ResponseEntity<String> response = post("/invitations", RECEPTION, org, issueBody());
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return field(response, "code");
    }

    private String enroll(String code, String subject, String org) {
        String body = "{\"code\":\"" + code + "\",\"factDigest\":\"" + FACTS + "\"}";
        ResponseEntity<String> response = post("/invitations/redeem", subject, org, body);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return field(response, "id");
    }

    private static String contactBody(String accountId, String contactDigest) {
        return "{\"accountId\":\"" + accountId + "\",\"contactDigest\":\"" + contactDigest + "\"}";
    }

    private static int count(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'synthetic-seeder'");
            try (ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String scalar(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'synthetic-seeder'");
            try (ResultSet rows = statement.executeQuery(sql)) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * Prove a staff contact edit leaves account ownership and verified access intact.
     *
     * <p>The same account identity, subject, organization, chart links, fact digest
     * and created time survive the edit, chart access is still allowed, the
     * append-only audit records {@code ownership_unchanged}, and the durable row
     * under forced RLS holds only the new digest — never the raw contact.</p>
     */
    @Test
    void staffContactChangePreservesAccountOwnershipAndChartAccess() throws Exception {
        String code = issueInvitation(ORG_A);
        String patient = freshPatient();
        seedPatient(ORG_A, patient);
        String redeemBody = "{\"code\":\"" + code + "\",\"factDigest\":\"" + FACTS + "\"}";
        ResponseEntity<String> enrolled = post("/invitations/redeem", patient, ORG_A, redeemBody);
        assertEquals(200, enrolled.getStatusCode().value(), enrolled.getBody());
        JsonNode before = tree(enrolled);
        String accountId = before.get("id").asText();

        ResponseEntity<String> changed = post("/accounts/contact", RECEPTION, ORG_A,
                contactBody(accountId, NEW_CONTACT));
        assertEquals(200, changed.getStatusCode().value(), changed.getBody());
        JsonNode after = tree(changed);
        assertEquals(before.get("id").asText(), after.get("id").asText());
        assertEquals(before.get("organizationId").asText(), after.get("organizationId").asText());
        assertEquals(before.get("subject").asText(), after.get("subject").asText());
        assertEquals(before.get("linkedChartIds").toString(), after.get("linkedChartIds").toString());
        assertEquals(before.get("factDigest").asText(), after.get("factDigest").asText());
        assertEquals(before.get("state").asText(), after.get("state").asText());
        assertEquals(before.get("failedAttempts").asInt(), after.get("failedAttempts").asInt());
        // PostgreSQL stores timestamptz at microsecond precision, so the stored
        // creation time can differ from the in-memory Instant by sub-microsecond
        // rounding. Millisecond precision proves the same creation event.
        assertEquals(Instant.parse(before.get("createdAt").asText()).toEpochMilli(),
                Instant.parse(after.get("createdAt").asText()).toEpochMilli());
        assertNotEquals(before.get("contactDigest").asText(), after.get("contactDigest").asText());
        assertNotEquals(NEW_CONTACT, after.get("contactDigest").asText());
        assertEquals(64, after.get("contactDigest").asText().length());

        ResponseEntity<String> access = get("/accounts/" + accountId + "/chart-access?chartId=" + CHART,
                patient, ORG_A);
        assertEquals(200, access.getStatusCode().value(), access.getBody());
        assertTrue(tree(access).get("allowed").asBoolean(), access.getBody());

        String audit = get("/audit", PRIVACY, ORG_A).getBody();
        assertTrue(audit.contains("\"eventType\":\"contact_changed\""), audit);
        assertTrue(audit.contains("\"detail\":\"ownership_unchanged\""), audit);

        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE id = '" + accountId + "' AND subject = '" + patient + "' "
                + "AND linked_chart_ids = ARRAY['" + CHART + "']::text[] "
                + "AND contact_digest = '" + after.get("contactDigest").asText() + "'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE contact_digest = '" + NEW_CONTACT + "'"));
    }

    /**
     * Prove the staff contact command refuses callers and inputs that must not pass.
     *
     * <p>A patient and a privacy auditor lack the clerical contact-edit action,
     * another tenant is concealed by forced RLS, blank input and an unknown
     * account are rejected, and none of the refusals changes the stored digest.</p>
     */
    @Test
    void staffContactChangeIsRefusedForWrongRoleOtherTenantAndInvalidInput() throws Exception {
        String code = issueInvitation(ORG_A);
        String patient = freshPatient();
        seedPatient(ORG_A, patient);
        String accountId = enroll(code, patient, ORG_A);
        String originalDigest = scalar(ORG_A, "SELECT contact_digest FROM public.patient_access_account "
                + "WHERE id = '" + accountId + "'");

        ResponseEntity<String> self = post("/accounts/contact", patient, ORG_A,
                contactBody(accountId, NEW_CONTACT));
        assertEquals(403, self.getStatusCode().value(), self.getBody());
        assertEquals("FORBIDDEN", field(self, "code"));

        ResponseEntity<String> privacy = post("/accounts/contact", PRIVACY, ORG_A,
                contactBody(accountId, NEW_CONTACT));
        assertEquals(403, privacy.getStatusCode().value(), privacy.getBody());
        assertEquals("FORBIDDEN", field(privacy, "code"));

        ResponseEntity<String> foreign = post("/accounts/contact", RECEPTION_B, ORG_B,
                contactBody(accountId, NEW_CONTACT));
        assertEquals(404, foreign.getStatusCode().value(), foreign.getBody());
        assertEquals("NOT_FOUND", field(foreign, "code"));

        ResponseEntity<String> blank = post("/accounts/contact", RECEPTION, ORG_A,
                contactBody(accountId, " "));
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertEquals("INVALID_CONTACTDIGEST", field(blank, "code"));

        ResponseEntity<String> missing = post("/accounts/contact", RECEPTION, ORG_A,
                contactBody(UNKNOWN_ACCOUNT, NEW_CONTACT));
        assertEquals(404, missing.getStatusCode().value(), missing.getBody());
        assertEquals("NOT_FOUND", field(missing, "code"));

        // A fresh account has no contact digest, so compare the stored value
        // directly instead of embedding a null in SQL equality.
        assertEquals(originalDigest, scalar(ORG_A, "SELECT contact_digest FROM public.patient_access_account "
                + "WHERE id = '" + accountId + "'"), "a refused contact change must not persist a digest");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE contact_digest = '" + NEW_CONTACT + "'"));
    }
}
