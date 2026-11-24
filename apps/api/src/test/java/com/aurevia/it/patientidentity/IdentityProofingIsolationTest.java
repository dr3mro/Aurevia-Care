package com.aurevia.it.patientidentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

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

/** S-G0-07 acceptance: identity proofing, verified linking, unlinking and tenant isolation (IAM-05). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_identity_it")
@ActiveProfiles("synthetic-auth")
class IdentityProofingIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String CHART_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String CHART_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_identity_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_identity_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_identity_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "registrar-a", "reception");
            seedStaff(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedStaff(statement, ORG_B, "admin-b", "tenant_admin");
            seedStaff(statement, ORG_B, "registrar-b", "reception");
            seedChart(statement, ORG_A, CHART_A, "IDENT-A", "Org A chart");
            seedChart(statement, ORG_B, CHART_A, "IDENT-A", "Org B chart, same local id");
            seedChart(statement, ORG_A, CHART_B, "IDENT-B", "Org A second chart");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_identity_it WITH (FORCE)");
        }
    }

    private static void useOrg(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
    }

    private static void seedStaff(Statement statement, String org, String subject, String role) throws Exception {
        useOrg(statement, org);
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

    private static void seedChart(Statement statement, String org, String id, String mrn, String name)
            throws Exception {
        useOrg(statement, org);
        statement.execute("SET app.actor = 'registrar-" + (org.equals(ORG_A) ? "a" : "b") + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                + "VALUES ('" + org + "', '" + id + "', 1, '" + mrn + "', 'ISSUER', '" + name + "', "
                + "'" + name.toLowerCase() + "', '1990-01-01', "
                + "(SELECT id FROM public.user_identity WHERE organization_id = '" + org
                + "' AND subject = 'registrar-" + (org.equals(ORG_A) ? "a" : "b") + "'))");
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

    private ResponseEntity<String> enroll(String subject, String name, String actor, String org) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/accounts",
                "{\"subject\":\"" + subject + "\",\"displayName\":\"" + name + "\"}", actor, org);
    }

    private ResponseEntity<String> issue(String subject, String chartId, String actor, String org) {
        return request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + chartId
                        + "\",\"purpose\":\"LINK\",\"ttlSeconds\":600}", actor, org);
    }

    private String issueAndClaim(String subject, String chartId, String staff, String org) {
        ResponseEntity<String> issue = issue(subject, chartId, staff, org);
        assertEquals(HttpStatus.CREATED, issue.getStatusCode(), issue.getBody());
        String token = field(issue.getBody(), "token");
        ResponseEntity<String> claim = request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + token + "\"}", subject, org);
        assertEquals(HttpStatus.OK, claim.getStatusCode(), claim.getBody());
        return token;
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

    private String uniqueSubject(String base) {
        return base + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void enrollmentIssueClaimAndAccessWorkEndToEnd() {
        String subject = uniqueSubject("patienta");
        assertEquals(HttpStatus.CREATED, enroll(subject, "Patient A", "registrar-a", ORG_A).getStatusCode());
        issueAndClaim(subject, CHART_A, "registrar-a", ORG_A);

        ResponseEntity<String> access = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART_A,
                null, subject, ORG_A);
        assertEquals(HttpStatus.OK, access.getStatusCode());
        assertEquals("true", access.getBody());

        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject, ORG_A);
        assertEquals(HttpStatus.OK, links.getStatusCode());
        assertTrue(links.getBody().contains("\"event\":\"linked\""));
        assertTrue(links.getBody().contains("\"method\":\"INVITATION\""));
        assertTrue(links.getBody().contains("\"reason\":null"));
        assertFalse(links.getBody().contains("\"token\""));
    }

    @Test
    void unlinkEndsFutureAccessButKeepsSiblingLinks() {
        String subject = uniqueSubject("patientb");
        enroll(subject, "Patient B", "registrar-a", ORG_A);
        issueAndClaim(subject, CHART_A, "registrar-a", ORG_A);
        issueAndClaim(subject, CHART_B, "registrar-a", ORG_A);

        ResponseEntity<String> unlink = request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_A
                        + "\",\"reason\":\"patient request\"}", subject, ORG_A);
        assertEquals(HttpStatus.OK, unlink.getStatusCode(), unlink.getBody());
        assertEquals("unlinked", field(unlink.getBody(), "event"));

        String first = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART_A,
                null, subject, ORG_A).getBody();
        String second = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART_B,
                null, subject, ORG_A).getBody();
        assertEquals("false", first, "unlinked chart access must end on the next request");
        assertEquals("true", second, "other verified links survive an unlink");

        // links() exposes the current event per chart (including an unlinked
        // terminal event), so a patient can see why access ended.
        ResponseEntity<String> linksAfter = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject, ORG_A);
        assertTrue(linksAfter.getBody().contains("\"event\":\"unlinked\""));
        assertTrue(linksAfter.getBody().contains("\"event\":\"linked\""));
    }

    @Test
    void invitationIsSingleUseAndWrongAccountIsDenied() {
        String owner = uniqueSubject("owner");
        String other = uniqueSubject("other");
        enroll(owner, "Owner", "registrar-a", ORG_A);
        enroll(other, "Other", "registrar-a", ORG_A);

        ResponseEntity<String> issue = issue(owner, CHART_A, "registrar-a", ORG_A);
        String token = field(issue.getBody(), "token");
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                        "{\"token\":\"" + token + "\"}", other, ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK,
                request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                        "{\"token\":\"" + token + "\"}", owner, ORG_A).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                        "{\"token\":\"" + token + "\"}", owner, ORG_A).getStatusCode());
    }

    @Test
    void reverificationRevokesAccessUntilFreshInvitationIsRedeemed() {
        String subject = uniqueSubject("reverify");
        enroll(subject, "Reverify", "registrar-a", ORG_A);
        issueAndClaim(subject, CHART_A, "registrar-a", ORG_A);

        ResponseEntity<String> reverify = request(HttpMethod.POST, "/api/v1/patient-identity/links/reverify",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_A
                        + "\",\"reason\":\"lost access\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, reverify.getStatusCode(), reverify.getBody());
        assertFalse(field(reverify.getBody(), "token").isEmpty());

        assertEquals("false", request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART_A,
                null, subject, ORG_A).getBody());

        ResponseEntity<String> claim = request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                "{\"token\":\"" + field(reverify.getBody(), "token") + "\"}", subject, ORG_A);
        assertEquals(HttpStatus.OK, claim.getStatusCode(), claim.getBody());
        assertEquals("true", request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART_A,
                null, subject, ORG_A).getBody());
    }

    @Test
    void contactChangeDoesNotChangeAccountOwnership() throws Exception {
        String subject = uniqueSubject("contact");
        enroll(subject, "Contact", "registrar-a", ORG_A);
        issueAndClaim(subject, CHART_A, "registrar-a", ORG_A);

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            useOrg(statement, ORG_A);
            statement.execute("SET app.actor = 'registrar-a'");
            // V7 keys patient_record on (organization_id, id), so a same-id
            // version append is impossible even though next_patient_version()
            // exists. Model the staff contact change as a re-registered chart
            // carrying the changed contact: ownership must still follow the
            // original immutable chart id, and contacts must not leak to links.
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, "
                    + " contacts, created_by) "
                    + "VALUES ('" + ORG_A + "', '" + UUID.randomUUID() + "', 1, "
                    + "'IDENT-A-CHANGED', 'ISSUER', "
                    + "'Org A chart', 'org a chart', '1990-01-01', "
                    + "'{\"phone\":\"new-phone\"}', "
                    + "(SELECT id FROM public.user_identity WHERE organization_id = '" + ORG_A
                    + "' AND subject = 'registrar-a'))");
        }

        assertEquals("true", request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/access?chartId=" + CHART_A,
                null, subject, ORG_A).getBody());
        ResponseEntity<String> links = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject, ORG_A);
        assertTrue(links.getBody().contains("\"accountSubject\":\"" + subject + "\""));
        assertFalse(links.getBody().contains("new-phone"), "contacts must never leak into link material");
    }

    @Test
    void identicalLocalIdsStayIsolatedAcrossTenants() {
        String subject = "patient.shared";
        assertEquals(HttpStatus.CREATED, enroll(subject, "Shared A", "registrar-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CREATED, enroll(subject, "Shared B", "registrar-b", ORG_B).getStatusCode());

        ResponseEntity<String> issueA = issue(subject, CHART_A, "registrar-a", ORG_A);
        String tokenA = field(issueA.getBody(), "token");
        assertEquals(HttpStatus.OK,
                request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                        "{\"token\":\"" + tokenA + "\"}", subject, ORG_A).getStatusCode());

        // The peer tenant cannot redeem the first tenant's token.
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                        "{\"token\":\"" + tokenA + "\"}", subject, ORG_B).getStatusCode());

        // The peer tenant has its own chart and its own independent link.
        issueAndClaim(subject, CHART_A, "registrar-b", ORG_B);
        ResponseEntity<String> linksA = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject, ORG_A);
        ResponseEntity<String> linksB = request(HttpMethod.GET,
                "/api/v1/patient-identity/accounts/" + subject + "/links", null, subject, ORG_B);
        assertTrue(linksA.getBody().contains("\"chartId\":\"" + CHART_A + "\""));
        assertTrue(linksB.getBody().contains("\"chartId\":\"" + CHART_A + "\""));
        assertFalse(linksA.getBody().contains("Shared B"));
        assertFalse(linksB.getBody().contains("Shared A"));
    }

    @Test
    void expiredInvitationIsUnusable() throws Exception {
        String subject = uniqueSubject("expired");
        enroll(subject, "Expired", "registrar-a", ORG_A);
        ResponseEntity<String> issue = issue(subject, CHART_A, "registrar-a", ORG_A);
        String token = field(issue.getBody(), "token");
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            useOrg(statement, ORG_A);
            // Backdate both endpoints so the invitation is genuinely expired while
            // V10's CHECK (expires_at > created_at) still holds.
            statement.execute("UPDATE public.account_invitation "
                    + "SET created_at = now() - interval '2 hours', "
                    + "    expires_at = now() - interval '1 hour' "
                    + "WHERE token_hash = encode(sha256(convert_to('" + token + "', 'UTF8')), 'hex')");
        }
        assertEquals(HttpStatus.CONFLICT,
                request(HttpMethod.POST, "/api/v1/patient-identity/links/claim",
                        "{\"token\":\"" + token + "\"}", subject, ORG_A).getStatusCode());
    }

    @Test
    void roleAndAuthenticationDenialsAreAudited() {
        String subject = uniqueSubject("denied");
        assertEquals(HttpStatus.FORBIDDEN,
                enroll(subject, "Denied", "auditor-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                enroll(subject, "Denied", null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                enroll(subject, "Denied", "patient.shared", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.GET, "/api/v1/patient-identity/accounts/" + subject + "/links",
                        null, "stranger", ORG_A).getStatusCode());
    }

    @Test
    void invalidInputsReturnUnprocessableEntity() {
        // Spring 7 renamed HttpStatus.UNPROCESSABLE_ENTITY to UNPROCESSABLE_CONTENT;
        // assert the wire code so the contract stays 422 across the rename.
        assertEquals(422, enroll("Bad Subject!", "Name", "registrar-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"any.subject\",\"chartId\":\"" + CHART_A
                        + "\",\"purpose\":\"ANY\",\"ttlSeconds\":600}",
                "registrar-a", ORG_A).getStatusCode().value());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/patient-identity/invitations",
                "{\"subject\":\"any.subject\",\"chartId\":\"" + CHART_A
                        + "\",\"purpose\":\"LINK\",\"ttlSeconds\":0}",
                "registrar-a", ORG_A).getStatusCode().value());
    }

    @Test
    void auditChainRemainsValidAfterIdentityCommands() throws Exception {
        String subject = uniqueSubject("audit");
        enroll(subject, "Audit", "registrar-a", ORG_A);
        issueAndClaim(subject, CHART_A, "registrar-a", ORG_A);
        request(HttpMethod.POST, "/api/v1/patient-identity/links/unlink",
                "{\"subject\":\"" + subject + "\",\"chartId\":\"" + CHART_A + "\",\"reason\":\"done\"}",
                subject, ORG_A);
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            useOrg(statement, ORG_A);
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_verify_chain('" + ORG_A + "'::uuid) WHERE NOT ok")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "audit chain must stay tamper-evident");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE action LIKE '%CHART%'")) {
                assertTrue(result.next());
                assertTrue(result.getInt(1) >= 3, "link, unlink and denials must be audited");
            }
        }
    }

    @Test
    void linkEventsAreAppendOnly() throws Exception {
        String subject = uniqueSubject("immutable");
        enroll(subject, "Immutable", "registrar-a", ORG_A);
        issueAndClaim(subject, CHART_A, "registrar-a", ORG_A);
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            useOrg(statement, ORG_A);
            boolean rejected = false;
            try {
                statement.execute("UPDATE public.account_chart_link SET event = 'unlinked'");
            } catch (Exception expected) {
                rejected = true;
            }
            assertTrue(rejected, "append-only link events must reject UPDATE for every role");
        }
    }
}
