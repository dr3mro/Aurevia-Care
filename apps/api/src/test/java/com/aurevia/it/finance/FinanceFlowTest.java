package com.aurevia.it.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * S-G4-05 acceptance (FIN-01) on a real PostgreSQL instance.
 *
 * <p>Four synthetic organizations share local patient, subject, reference and
 * item identifiers so tenant isolation and replay dedup are observable. All
 * fixtures and narratives are synthetic; no patient data or credentials are
 * used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_finance_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class FinanceFlowTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String ORG_D = "44444444-4444-4444-4444-444444444444";
    static final String ORG_E = "55555555-5555-5555-5555-555555555555";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_finance_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String CLINICAL_MARKER = "CLINICAL_MARKER_DO_NOT_EXPOSE";

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
            statement.execute("DROP DATABASE IF EXISTS orevia_finance_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_finance_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D, ORG_E}) {
                seedOrganization(statement, org);
            }
            for (String org : new String[] {ORG_A, ORG_B, ORG_C, ORG_D, ORG_E}) {
                String s = suffix(org);
                seedMember(statement, org, "finance-" + s, "finance");
                seedMember(statement, org, "admin-" + s, "tenant_admin");
                seedMember(statement, org, "clinician-" + s, "clinician");
                seedMember(statement, org, "reception-" + s, "reception");
                seedMember(statement, org, "auditor-" + s, "privacy_auditor");
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_finance_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String s = suffix(org);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + s + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + s + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + s + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + s + "'");
        String displayName = org.equals(ORG_D) ? CLINICAL_MARKER : "Synthetic Patient";
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', '" + displayName + "', "
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

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String items() {
        return "[{\"description\":\"consultation fee\",\"quantity\":2,\"unitAmountMinor\":500},"
                + "{\"description\":\"synthetic handling\",\"quantity\":1,\"unitAmountMinor\":300}]";
    }

    private String createBody(String org) {
        return "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"usd\","
                + "\"serviceReference\":\"ENCOUNTER-1\",\"items\":" + items() + "}";
    }

    private ResponseEntity<String> create(String org, String subject) {
        return send(HttpMethod.POST, "/api/v1/finance/invoices", createBody(org), subject, org);
    }

    private ResponseEntity<String> issue(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/finance/invoices/" + id + "/issue", "{}", subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private static String entryBody(long amount, String reference, String reason, String approver) {
        return "{\"amountMinor\":" + amount + ",\"reference\":\"" + reference + "\""
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + (approver == null ? "" : ",\"approverSubject\":\"" + approver + "\"") + "}";
    }

    private ResponseEntity<String> postEntry(String org, String subject, String kind, String id,
                                             int version, String body) {
        return send(HttpMethod.POST, "/api/v1/finance/invoices/" + id + "/" + kind, body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/finance/invoices/" + id, null, subject, org);
    }

    private ResponseEntity<String> reconcile(String org, String subject) {
        return send(HttpMethod.GET, "/api/v1/finance/reconciliation", null, subject, org);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "finance-" + suffix(org));
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "finance-" + suffix(org));
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
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

    private long auditCount(String org, String action, String resourceId) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "finance-" + suffix(org));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE action = ? AND resource_id = ?")) {
                statement.setString(1, action);
                statement.setString(2, resourceId);
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
    void fullLifecyclePostsItemizedPaymentsAndNumberedReceipts() {
        String org = ORG_A;
        String finance = "finance-1111";
        String admin = "admin-1111";

        ResponseEntity<String> created = create(org, finance);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/invoice/id");
        assertEquals("draft", text(created, "/invoice/state"));
        assertEquals("USD", text(created, "/invoice/currency"));
        assertEquals("1300", text(created, "/invoice/totalMinor"));
        assertEquals("1300", text(created, "/invoice/balanceMinor"));
        assertEquals(null, text(created, "/invoice/issuedAt"));
        assertEquals(2, json(created).at("/items").size());
        assertEquals("consultation fee", text(created, "/items/0/description"));

        ResponseEntity<String> issued = issue(org, finance, id, 1);
        assertEquals(HttpStatus.OK, issued.getStatusCode(), issued.getBody());
        assertEquals("issued", text(issued, "/invoice/state"));
        assertEquals("2", text(issued, "/invoice/version"));
        assertNotNull(text(issued, "/invoice/issuedAt"));

        ResponseEntity<String> partial = postEntry(org, finance, "payments", id, 2,
                entryBody(300L, "PAY-A-1", "paid at desk", null));
        assertEquals(HttpStatus.OK, partial.getStatusCode(), partial.getBody());
        assertEquals("partially_paid", text(partial, "/invoice/state"));
        assertEquals("1000", text(partial, "/invoice/balanceMinor"));
        assertEquals("300", text(partial, "/invoice/paidMinor"));
        assertEquals("3", text(partial, "/invoice/version"));
        String firstReceipt = text(partial, "/entries/0/receiptNo");
        assertTrue(Long.parseLong(firstReceipt) > 0);

        ResponseEntity<String> paid = postEntry(org, finance, "payments", id, 3,
                entryBody(1000L, "PAY-A-2", null, null));
        assertEquals(HttpStatus.OK, paid.getStatusCode(), paid.getBody());
        assertEquals("paid", text(paid, "/invoice/state"));
        assertEquals("0", text(paid, "/invoice/balanceMinor"));
        assertEquals(Long.parseLong(firstReceipt) + 1,
                Long.parseLong(text(paid, "/entries/1/receiptNo")),
                "receipt numbers are consecutive within the tenant");
        assertEquals(2, json(paid).at("/entries").size());
        assertEquals("payment", text(paid, "/entries/0/type"));
        assertEquals(actorId(org, finance), text(paid, "/entries/0/actorId"),
                "the database derives the ledger actor from app.actor");
        assertEquals(1, auditCount(org, "FINANCE_INVOICE_CREATE", id));
        assertEquals(2, auditCount(org, "FINANCE_PAYMENT_RECORD", id));
        assertEquals(HttpStatus.OK, get(org, admin, id).getStatusCode());
    }

    @Test
    void refundReestablishesIssuedStateAndReplayIsANoOp() {
        String org = ORG_B;
        String id = text(create(org, "finance-2222"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-2222", id, 1).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-2222", "payments", id, 2,
                entryBody(1300L, "PAY-B-1", null, null)).getStatusCode());
        assertEquals("paid", text(get(org, "finance-2222", id), "/invoice/state"));

        ResponseEntity<String> refunded = postEntry(org, "finance-2222", "refunds", id, 3,
                entryBody(1300L, "REF-B-1", "patient cancellation", "admin-2222"));
        assertEquals(HttpStatus.OK, refunded.getStatusCode(), refunded.getBody());
        assertEquals("issued", text(refunded, "/invoice/state"),
                "a full refund restores the outstanding balance");
        assertEquals("1300", text(refunded, "/invoice/balanceMinor"));
        assertEquals("1300", text(refunded, "/invoice/refundedMinor"));
        assertEquals(2, json(refunded).at("/entries").size());
        String lastReceipt = text(refunded, "/entries/1/receiptNo");

        ResponseEntity<String> replay = postEntry(org, "finance-2222", "payments", id, 4,
                entryBody(1300L, "PAY-B-1", null, null));
        assertEquals(HttpStatus.OK, replay.getStatusCode(), replay.getBody());
        assertEquals("issued", text(replay, "/invoice/state"),
                "a replayed reference cannot change the committed balance");
        assertEquals("4", text(replay, "/invoice/version"));
        assertEquals(2, json(replay).at("/entries").size(), "a replay adds no ledger entry");
        assertEquals(lastReceipt, text(replay, "/entries/1/receiptNo"),
                "a replay allocates no receipt");
        assertEquals(1, auditCount(org, "FINANCE_PAYMENT_RECORD_REPLAY", id));
    }

    @Test
    void aReusedReferenceWithADifferentPayloadIsRejected() {
        String org = ORG_A;
        String id = text(create(org, "finance-1111"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-1111", id, 1).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-1111", "payments", id, 2,
                entryBody(500L, "SHARED-REF", null, null)).getStatusCode());

        ResponseEntity<String> clash = postEntry(org, "finance-1111", "refunds", id, 3,
                entryBody(500L, "SHARED-REF", "different payload", "admin-1111"));
        assertEquals(409, clash.getStatusCode().value(), clash.getBody());
        assertEquals("REFERENCE_CONFLICT", text(clash, "/code"));
        assertEquals("800", text(get(org, "finance-1111", id), "/invoice/balanceMinor"));
    }

    @Test
    void aReferenceCannotMoveToAnotherInvoice() {
        String org = ORG_A;
        String first = text(create(org, "finance-1111"), "/invoice/id");
        String second = text(create(org, "finance-1111"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-1111", first, 1).getStatusCode());
        assertEquals(HttpStatus.OK, issue(org, "finance-1111", second, 1).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-1111", "payments", first, 2,
                entryBody(500L, "CROSS-INVOICE-REF", null, null)).getStatusCode());

        ResponseEntity<String> clash = postEntry(org, "finance-1111", "payments", second, 2,
                entryBody(500L, "CROSS-INVOICE-REF", null, null));
        assertEquals(409, clash.getStatusCode().value(), clash.getBody());
        assertEquals("REFERENCE_CONFLICT", text(clash, "/code"));
        assertEquals("1300", text(get(org, "finance-1111", second), "/invoice/balanceMinor"),
                "the second invoice received no money and posted no entry");
    }

    @Test
    void reversalsRequireReasonAndAnIndependentApprover() {
        String org = ORG_A;
        String id = text(create(org, "finance-1111"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-1111", id, 1).getStatusCode());

        assertEquals(422, postEntry(org, "finance-1111", "credits", id, 2,
                entryBody(100L, "CR-NO-REASON", null, "admin-1111")).getStatusCode().value());
        assertEquals(403, postEntry(org, "finance-1111", "credits", id, 2,
                entryBody(100L, "CR-SELF", "write-off", "finance-1111")).getStatusCode().value());
        assertEquals("SEPARATION_OF_DUTIES", text(postEntry(org, "finance-1111", "credits", id, 2,
                entryBody(100L, "CR-SELF", "write-off", "finance-1111")), "/code"));
        assertEquals(403, postEntry(org, "finance-1111", "credits", id, 2,
                entryBody(100L, "CR-ROLE", "write-off", "clinician-1111")).getStatusCode().value());
        assertEquals(403, postEntry(org, "finance-1111", "credits", id, 2,
                entryBody(100L, "CR-NONE", "write-off", "no-such-user")).getStatusCode().value());

        ResponseEntity<String> credited = postEntry(org, "finance-1111", "credits", id, 2,
                entryBody(300L, "CR-OK", "approved write-off", "admin-1111"));
        assertEquals(HttpStatus.OK, credited.getStatusCode(), credited.getBody());
        assertEquals("1000", text(credited, "/invoice/balanceMinor"));
        assertEquals("300", text(credited, "/invoice/creditedMinor"));
        assertEquals("partially_paid", text(credited, "/invoice/state"));
        assertEquals(422, postEntry(org, "finance-1111", "credits", id, 3,
                entryBody(2000L, "CR-TOO-MUCH", "over credit", "admin-1111")).getStatusCode().value(),
                "a credit cannot exceed the outstanding balance");

        assertEquals(422, postEntry(org, "finance-1111", "refunds", id, 3,
                entryBody(100L, "REF-NO-REASON", null, "admin-1111")).getStatusCode().value());
        assertEquals(422, postEntry(org, "finance-1111", "refunds", id, 3,
                entryBody(100L, "REF-EXCESS", "too much", "admin-1111")).getStatusCode().value());
        assertEquals("EXCESS_REFUND", text(postEntry(org, "finance-1111", "refunds", id, 3,
                entryBody(100L, "REF-EXCESS", "too much", "admin-1111")), "/code"));
    }

    @Test
    void voidNeedsApprovalAndIsBlockedOnceMoneyIsPosted() {
        String org = ORG_C;
        String draft = text(create(org, "finance-3333"), "/invoice/id");
        assertEquals(403, send(HttpMethod.POST, "/api/v1/finance/invoices/" + draft + "/void",
                "{\"reason\":\"cancelled\",\"approverSubject\":\"finance-3333\"}",
                "finance-3333", org, Map.of("If-Match", "1")).getStatusCode().value());
        ResponseEntity<String> voided = send(HttpMethod.POST,
                "/api/v1/finance/invoices/" + draft + "/void",
                "{\"reason\":\"cancelled\",\"approverSubject\":\"admin-3333\"}",
                "finance-3333", org, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, voided.getStatusCode(), voided.getBody());
        assertEquals("void", text(voided, "/invoice/state"));
        assertEquals("cancelled", text(voided, "/invoice/voidReason"));
        assertEquals(409, postEntry(org, "finance-3333", "payments", draft, 2,
                entryBody(100L, "PAY-VOID", null, null)).getStatusCode().value(),
                "a void invoice cannot accept entries");

        String posted = text(create(org, "finance-3333"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-3333", posted, 1).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-3333", "payments", posted, 2,
                entryBody(1300L, "PAY-C-1", null, null)).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-3333", "refunds", posted, 3,
                entryBody(1300L, "REF-C-1", "reversed", "admin-3333")).getStatusCode());
        assertEquals("issued", text(get(org, "finance-3333", posted), "/invoice/state"));
        ResponseEntity<String> blocked = send(HttpMethod.POST,
                "/api/v1/finance/invoices/" + posted + "/void",
                "{\"reason\":\"after posting\",\"approverSubject\":\"admin-3333\"}",
                "finance-3333", org, Map.of("If-Match", "4"));
        assertEquals(422, blocked.getStatusCode().value());
        assertEquals("VOID_AFTER_POSTING", text(blocked, "/code"));

        String paid = text(create(org, "finance-3333"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-3333", paid, 1).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-3333", "payments", paid, 2,
                entryBody(1300L, "PAY-C-2", null, null)).getStatusCode());
        assertEquals(409, send(HttpMethod.POST, "/api/v1/finance/invoices/" + paid + "/void",
                "{\"reason\":\"paid\",\"approverSubject\":\"admin-3333\"}",
                "finance-3333", org, Map.of("If-Match", "3")).getStatusCode().value());
    }

    @Test
    void authorizationAndTenantIsolationHoldOnEveryCommand() throws Exception {
        String org = ORG_A;
        assertEquals(HttpStatus.UNAUTHORIZED, create(org, null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, create(org, "reception-1111").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, create(org, "clinician-1111").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, create(org, "finance-2222").getStatusCode());

        String id = text(create(org, "finance-1111"), "/invoice/id");
        assertEquals(HttpStatus.NOT_FOUND, get(ORG_B, "finance-2222", id).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, postEntry(org, "auditor-1111", "payments", id, 1,
                entryBody(100L, "AUDIT-PAY", null, null)).getStatusCode());
        assertEquals(HttpStatus.OK, reconcile(org, "auditor-1111").getStatusCode(),
                "a privacy auditor may read finance totals without posting rights");
        assertEquals(HttpStatus.OK, reconcile(org, "admin-1111").getStatusCode());

        String isolated = ORG_E;
        String isolatedId = text(create(isolated, "finance-5555"), "/invoice/id");
        execute(isolated, "admin-5555", "INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + isolated + "', id, 'revoked' FROM public.user_identity "
                + "WHERE subject = 'finance-5555'");
        assertEquals(HttpStatus.FORBIDDEN, get(isolated, "finance-5555", isolatedId).getStatusCode(),
                "revocation denies the very next protected request");
    }

    @Test
    void invalidInputsAndPreconditionsAreRejected() {
        String org = ORG_A;
        String id = text(create(org, "finance-1111"), "/invoice/id");

        assertEquals(404, get(org, "finance-1111", "55555555-5555-5555-5555-555555555555")
                .getStatusCode().value());
        assertEquals(422, get(org, "finance-1111", "not-a-uuid").getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/finance/invoices",
                "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"USD\",\"items\":[]}",
                "finance-1111", org).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/finance/invoices",
                "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"USD\",\"items\":null}",
                "finance-1111", org).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/finance/invoices",
                "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"US\",\"items\":" + items() + "}",
                "finance-1111", org).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/finance/invoices",
                "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"USD\","
                        + "\"items\":[{\"description\":\"fee\",\"quantity\":2,"
                        + "\"unitAmountMinor\":9223372036854775807}]}",
                "finance-1111", org).getStatusCode().value(), "money overflow is rejected");
        assertEquals(404, send(HttpMethod.POST, "/api/v1/finance/invoices",
                createBody(org).replace(patientId(org), "55555555-5555-5555-5555-555555555555"),
                "finance-1111", org).getStatusCode().value());

        assertEquals(412, issue(org, "finance-1111", id, 9).getStatusCode().value());
        assertEquals(428, send(HttpMethod.POST, "/api/v1/finance/invoices/" + id + "/issue",
                "{}", "finance-1111", org).getStatusCode().value());
        assertEquals(409, postEntry(org, "finance-1111", "payments", id, 1,
                entryBody(100L, "PAY-DRAFT", null, null)).getStatusCode().value(),
                "a draft invoice cannot accept entries");

        assertEquals(HttpStatus.OK, issue(org, "finance-1111", id, 1).getStatusCode());
        assertEquals(409, issue(org, "finance-1111", id, 2).getStatusCode().value());
        assertEquals(412, postEntry(org, "finance-1111", "payments", id, 9,
                entryBody(100L, "PAY-STALE", null, null)).getStatusCode().value());
        assertEquals(422, postEntry(org, "finance-1111", "payments", id, 2,
                entryBody(0L, "PAY-ZERO", null, null)).getStatusCode().value());
        assertEquals(422, postEntry(org, "finance-1111", "payments", id, 2,
                entryBody(1400L, "PAY-TOO-MUCH", null, null)).getStatusCode().value());
    }

    @Test
    void aLongButValidReasonPostsWithinTheLedgerDigestColumn() {
        String org = ORG_C;
        String id = text(create(org, "finance-3333"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-3333", id, 1).getStatusCode());
        String reason = "documented write-off " + "x".repeat(200);

        ResponseEntity<String> credited = postEntry(org, "finance-3333", "credits", id, 2,
                entryBody(300L, "LONG-REASON-CREDIT", reason, "admin-3333"));

        assertEquals(HttpStatus.OK, credited.getStatusCode(), credited.getBody());
        assertEquals(reason, text(credited, "/entries/0/reason"),
                "a reason within the validated length must post, not exceed the digest column");
        assertEquals("1000", text(credited, "/invoice/balanceMinor"));
    }

    @Test
    void reconciliationIsFinanceOnlyAndStableAcrossReplay() {
        String org = ORG_D;
        String id = text(create(org, "finance-4444"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(org, "finance-4444", id, 1).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-4444", "payments", id, 2,
                entryBody(700L, "PAY-D-1", null, null)).getStatusCode());
        assertEquals(HttpStatus.OK, postEntry(org, "finance-4444", "payments", id, 3,
                entryBody(600L, "PAY-D-2", null, null)).getStatusCode());

        String before = reconcile(org, "finance-4444").getBody();
        assertEquals(HttpStatus.OK, postEntry(org, "finance-4444", "payments", id, 4,
                entryBody(700L, "PAY-D-1", null, null)).getStatusCode());
        assertEquals(before, reconcile(org, "finance-4444").getBody(),
                "a replay leaves daily totals unchanged");

        JsonNode days = json(reconcile(org, "finance-4444")).at("/days");
        assertEquals(1, days.size(), "issued invoices and entries share one UTC day");
        assertEquals("USD", days.at("/0/currency").asText());
        assertEquals("1300", days.at("/0/issuedMinor").asText());
        assertEquals("1300", days.at("/0/paymentMinor").asText());
        assertEquals("0", days.at("/0/refundMinor").asText());
        assertEquals("1300", days.at("/0/netMinor").asText());
        assertEquals(2, days.at("/0/entryCount").asInt());
        assertFalse(before.contains(CLINICAL_MARKER),
                "finance responses never expose the synthetic clinical marker");
        assertFalse(get(org, "finance-4444", id).getBody().contains(CLINICAL_MARKER));
    }
}
