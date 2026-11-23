package com.aurevia.smartclinic.finance.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FIN-01 clause 2 acceptance through the real HTTP entry point on PostgreSQL: a
 * self-pay invoice moves draft -> issued -> partially paid -> paid, a separate
 * draft can be voided, and every illegal move is refused with a stable code.
 * Negative cases prove the rule refuses a stale precondition, a wrong role, a
 * peer tenant, a self/unauthorized approver, entries on a non-issued invoice and
 * a void after posting. Fixtures are synthetic; the peer tenant shares the same
 * local patient and subject codes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_invoice_states_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SupportDraftIssuedPaidPartiallyPaidVoidTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_invoice_states_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String FINANCE = "finance-1111";
    private static final String APPROVER = "admin-1111";
    private static final String RECEPTION = "reception-1111";
    private static final String PEER = "finance-2222";
    private static final String TOTAL = "1300";
    private static final String SERVICE_REFERENCE = "FIN-01-CLAUSE-2";
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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, FINANCE, "finance");
            seedMember(statement, ORG_A, APPROVER, "tenant_admin");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_B, PEER, "finance");
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
        String bootstrap = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
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

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, "bootstrap-" + org.substring(0, 4));
                bind.execute();
            }
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

    private static void synthetic(HttpHeaders headers, String subject, String org) {
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                         String ifMatch) {
        RestClient.RequestBodySpec spec = rest.post().uri(path)
                .headers(headers -> synthetic(headers, subject, org));
        if (ifMatch != null) {
            spec = spec.header("If-Match", ifMatch);
        }
        return spec.contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .headers(headers -> synthetic(headers, subject, org))
                .retrieve()
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

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static void assertInvoice(ResponseEntity<String> response, String state, String balance,
                                      String paid, String version) {
        assertEquals(state, text(response, "/invoice/state"));
        assertEquals(balance, text(response, "/invoice/balanceMinor"));
        assertEquals(paid, text(response, "/invoice/paidMinor"));
        assertEquals(version, text(response, "/invoice/version"));
    }

    private static void assertRefused(ResponseEntity<String> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        assertEquals(code, text(response, "/code"));
    }

    private static void assertListedAs(ResponseEntity<String> response, String id, String state) {
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        boolean found = false;
        for (JsonNode item : json(response).get("items")) {
            JsonNode invoice = item.get("invoice");
            assertEquals(state, invoice.get("state").asText());
            if (id.equals(invoice.get("id").asText())) {
                found = true;
            }
        }
        assertTrue(found, "invoice " + id + " is absent from the filtered page: " + response.getBody());
    }

    private static String createBody(String org) {
        return "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"USD\","
                + "\"serviceReference\":\"" + SERVICE_REFERENCE + "\","
                + "\"items\":[{\"description\":\"consultation fee\",\"quantity\":1,\"unitAmountMinor\":1300}]}";
    }

    private String create(String org, String subject) {
        ResponseEntity<String> response = post("/api/v1/finance/invoices", createBody(org),
                subject, org, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        assertInvoice(response, "draft", TOTAL, "0", "1");
        return text(response, "/invoice/id");
    }

    private ResponseEntity<String> issue(String id, String subject, String org, String version) {
        return post("/api/v1/finance/invoices/" + id + "/issue", "{}", subject, org, version);
    }

    private ResponseEntity<String> pay(String id, String subject, String org, String version,
                                        long amount, String reference) {
        String body = "{\"amountMinor\":" + amount + ",\"reference\":\"" + reference + "\"}";
        return post("/api/v1/finance/invoices/" + id + "/payments", body, subject, org, version);
    }

    private ResponseEntity<String> refund(String id, String subject, String org, String version,
                                           long amount, String reference) {
        String body = "{\"amountMinor\":" + amount + ",\"reference\":\"" + reference + "\","
                + "\"reason\":\"full reversal\",\"approverSubject\":\"" + APPROVER + "\"}";
        return post("/api/v1/finance/invoices/" + id + "/refunds", body, subject, org, version);
    }

    private ResponseEntity<String> voidInvoice(String id, String subject, String org, String version,
                                                String approver) {
        String body = "{\"reason\":\"patient cancelled\",\"approverSubject\":\"" + approver + "\"}";
        return post("/api/v1/finance/invoices/" + id + "/void", body, subject, org, version);
    }

    @Test
    void anInvoiceMovesDraftIssuedPartiallyPaidPaidAndVoid() {
        String id = create(ORG_A, FINANCE);

        ResponseEntity<String> issued = issue(id, FINANCE, ORG_A, "\"1\"");
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        assertInvoice(issued, "issued", TOTAL, "0", "2");
        assertNotNull(text(issued, "/invoice/issuedAt"));

        ResponseEntity<String> partial = pay(id, FINANCE, ORG_A, "\"2\"", 300, "FIN-C2-PAY-1");
        assertEquals(200, partial.getStatusCode().value(), partial.getBody());
        assertInvoice(partial, "partially_paid", "1000", "300", "3");
        assertEquals("payment", text(partial, "/entries/0/type"));

        ResponseEntity<String> paid = pay(id, FINANCE, ORG_A, "\"3\"", 1000, "FIN-C2-PAY-2");
        assertEquals(200, paid.getStatusCode().value(), paid.getBody());
        assertInvoice(paid, "paid", "0", TOTAL, "4");
        assertEquals(2, json(paid).get("entries").size());

        ResponseEntity<String> readBack = get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A);
        assertEquals(200, readBack.getStatusCode().value(), readBack.getBody());
        assertInvoice(readBack, "paid", "0", TOTAL, "4");
        assertListedAs(get("/api/v1/finance/invoices?status=paid", FINANCE, ORG_A), id, "paid");

        String voidId = create(ORG_A, FINANCE);
        ResponseEntity<String> voided = voidInvoice(voidId, FINANCE, ORG_A, "\"1\"", APPROVER);
        assertEquals(200, voided.getStatusCode().value(), voided.getBody());
        assertInvoice(voided, "void", TOTAL, "0", "2");
        assertEquals("patient cancelled", text(voided, "/invoice/voidReason"));
        assertEquals(APPROVER, text(voided, "/invoice/voidedBySubject"));
        assertInvoice(get("/api/v1/finance/invoices/" + voidId, FINANCE, ORG_A),
                "void", TOTAL, "0", "2");
        assertListedAs(get("/api/v1/finance/invoices?status=void", FINANCE, ORG_A), voidId, "void");

        // VOID is terminal: neither a second void nor a payment may move it.
        assertRefused(voidInvoice(voidId, FINANCE, ORG_A, "\"2\"", APPROVER),
                409, "INVALID_TRANSITION");
        assertRefused(pay(voidId, FINANCE, ORG_A, "\"2\"", 100, "FIN-C2-VOID-PAY"),
                409, "INVALID_TRANSITION");
        assertInvoice(get("/api/v1/finance/invoices/" + voidId, FINANCE, ORG_A),
                "void", TOTAL, "0", "2");
    }

    @Test
    void aStaleVersionCannotChangeInvoiceState() {
        String id = create(ORG_A, FINANCE);

        assertRefused(issue(id, FINANCE, ORG_A, "\"99\""), 412, "STALE_VERSION");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "draft", TOTAL, "0", "1");

        ResponseEntity<String> issued = issue(id, FINANCE, ORG_A, "\"1\"");
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());
        assertInvoice(issued, "issued", TOTAL, "0", "2");

        assertRefused(pay(id, FINANCE, ORG_A, "\"1\"", 100, "FIN-C2-STALE-PAY"),
                412, "STALE_VERSION");
        assertRefused(voidInvoice(id, FINANCE, ORG_A, "\"1\"", APPROVER),
                412, "STALE_VERSION");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "issued", TOTAL, "0", "2");
    }

    @Test
    void aReceptionSubjectCannotPostPayment() {
        String id = create(ORG_A, FINANCE);
        ResponseEntity<String> issued = issue(id, FINANCE, ORG_A, "\"1\"");
        assertEquals(200, issued.getStatusCode().value(), issued.getBody());

        // A fully valid body is required: validation runs before authorization,
        // so an empty body would be refused 422 and would not test the role gate.
        assertRefused(pay(id, RECEPTION, ORG_A, "\"2\"", 300, "FIN-C2-WRONG-ROLE"),
                403, "FORBIDDEN");
        assertRefused(issue(id, RECEPTION, ORG_A, "\"2\""), 403, "FORBIDDEN");
        assertRefused(voidInvoice(id, APPROVER, ORG_A, "\"2\"", FINANCE),
                403, "FORBIDDEN");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "issued", TOTAL, "0", "2");
    }

    @Test
    void aPeerTenantCannotReadOrChangeAnotherTenantsInvoice() {
        String id = create(ORG_A, FINANCE);

        assertRefused(get("/api/v1/finance/invoices/" + id, PEER, ORG_B), 404, "NOT_FOUND");
        assertRefused(issue(id, PEER, ORG_B, "\"1\""), 404, "NOT_FOUND");
        assertRefused(pay(id, PEER, ORG_B, "\"1\"", 300, "FIN-C2-PEER-PAY"),
                404, "NOT_FOUND");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "draft", TOTAL, "0", "1");
    }

    @Test
    void voidingAfterPostingIsRefused() {
        String id = create(ORG_A, FINANCE);
        assertEquals(200, issue(id, FINANCE, ORG_A, "\"1\"").getStatusCode().value());
        ResponseEntity<String> paid = pay(id, FINANCE, ORG_A, "\"2\"", 1300, "FIN-C2-FULL-PAY");
        assertEquals(200, paid.getStatusCode().value(), paid.getBody());
        assertInvoice(paid, "paid", "0", TOTAL, "3");

        // A paid invoice fails at the legal-transition guard (409), not at the
        // posted-money guard. Only an issued invoice that still has ledger
        // entries reaches 422 VOID_AFTER_POSTING, so reverse the payment first.
        assertRefused(voidInvoice(id, FINANCE, ORG_A, "\"3\"", APPROVER),
                409, "INVALID_TRANSITION");
        ResponseEntity<String> refunded = refund(id, FINANCE, ORG_A, "\"3\"", 1300, "FIN-C2-FULL-REFUND");
        assertEquals(200, refunded.getStatusCode().value(), refunded.getBody());
        assertInvoice(refunded, "issued", TOTAL, TOTAL, "4");
        assertEquals(TOTAL, text(refunded, "/invoice/refundedMinor"));
        assertEquals(2, json(refunded).get("entries").size());

        assertRefused(voidInvoice(id, FINANCE, ORG_A, "\"4\"", APPROVER),
                422, "VOID_AFTER_POSTING");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "issued", TOTAL, TOTAL, "4");
    }

    @Test
    void voidRequiresAnApproverDistinctFromTheActor() {
        String id = create(ORG_A, FINANCE);

        assertRefused(voidInvoice(id, FINANCE, ORG_A, "\"1\"", FINANCE),
                403, "SEPARATION_OF_DUTIES");
        assertRefused(voidInvoice(id, FINANCE, ORG_A, "\"1\"", RECEPTION),
                403, "INVALID_APPROVER");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "draft", TOTAL, "0", "1");

        ResponseEntity<String> voided = voidInvoice(id, FINANCE, ORG_A, "\"1\"", APPROVER);
        assertEquals(200, voided.getStatusCode().value(), voided.getBody());
        assertInvoice(voided, "void", TOTAL, "0", "2");
    }

    @Test
    void aDraftOrVoidInvoiceCannotAcceptEntries() {
        String draft = create(ORG_A, FINANCE);
        assertRefused(pay(draft, FINANCE, ORG_A, "\"1\"", 100, "FIN-C2-DRAFT-PAY"),
                409, "INVALID_TRANSITION");
        assertInvoice(get("/api/v1/finance/invoices/" + draft, FINANCE, ORG_A),
                "draft", TOTAL, "0", "1");

        String voidId = create(ORG_A, FINANCE);
        assertInvoice(voidInvoice(voidId, FINANCE, ORG_A, "\"1\"", APPROVER),
                "void", TOTAL, "0", "2");
        assertRefused(pay(voidId, FINANCE, ORG_A, "\"2\"", 100, "FIN-C2-VOID-PAY-2"),
                409, "INVALID_TRANSITION");
        assertInvoice(get("/api/v1/finance/invoices/" + voidId, FINANCE, ORG_A),
                "void", TOTAL, "0", "2");
    }

    @Test
    void aMissingOrMalformedPreconditionIsRejectedBeforeAnyStateChange() {
        String id = create(ORG_A, FINANCE);

        assertRefused(post("/api/v1/finance/invoices/" + id + "/issue", "{}", FINANCE, ORG_A, null),
                428, "PRECONDITION_REQUIRED");
        assertRefused(issue(id, FINANCE, ORG_A, "abc"), 422, "INVALID_IF_MATCH");
        assertInvoice(get("/api/v1/finance/invoices/" + id, FINANCE, ORG_A),
                "draft", TOTAL, "0", "1");
    }
}
