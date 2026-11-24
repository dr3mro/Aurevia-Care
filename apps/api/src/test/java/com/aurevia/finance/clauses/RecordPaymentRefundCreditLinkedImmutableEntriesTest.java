package com.aurevia.finance.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
 * FIN-01 clause 3 (API evidence): payments, refunds and credits are linked,
 * immutable ledger entries that carry a reason and an independent approval.
 *
 * <p>Every request goes through the real {@code /api/v1/finance/invoices}
 * collection and {@code /api/v1/finance/invoices/{id}} sub-resource entry points
 * against a fresh PostgreSQL database created from the production Flyway
 * migrations. The positive test inspects the returned ledger trail and
 * the persisted table to show that an earlier entry is never rewritten and that
 * the append-only trigger rejects a mutation even by the migration owner. The
 * negative tests show that a missing reason, a missing or unauthorised
 * approver, a wrong role, another tenant, a revoked member, a stale version and
 * a forbidden state are all refused. Fixtures are synthetic: no real identity
 * or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_finance_clause3_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RecordPaymentRefundCreditLinkedImmutableEntriesTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/orevia_finance_clause3_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String FINANCE = "finance-1111";
    private static final String APPROVER = "admin-1111";
    private static final String RECEPTION = "reception-1111";
    private static final String REVOKED = "finance-revoked-1111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed two synthetic organizations with finance, approver, reception, revoked
     * and cross-tenant members and one synthetic patient each.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_finance_clause3_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_finance_clause3_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, FINANCE, "finance");
            seedMember(statement, ORG_A, APPROVER, "tenant_admin");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedRevokedMember(statement);
            seedMember(statement, ORG_B, "finance-2222", "finance");
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_finance_clause3_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String s = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + s + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + s + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + s + "', 'Bootstrap')");
        // A synthetic civil date satisfies V7's append-only patient invariant.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Clause Patient', "
                + "'synthetic clause patient', DATE '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    private static void seedRevokedMember(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'bootstrap-1111'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + REVOKED + "', 'Revoked finance member')");
        // A single revoked membership event is the current fold, so the very next
        // protected request fails closed with 403.
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'revoked' FROM public.user_identity "
                + "WHERE subject = '" + REVOKED + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG_A + "', m.id, 'finance', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + REVOKED + "'");
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> create(String org, String subject) {
        String body = "{\"patientId\":\"" + patientId(org) + "\",\"currency\":\"usd\","
                + "\"serviceReference\":\"CLAUSE-3\",\"items\":"
                + "[{\"description\":\"synthetic fee\",\"quantity\":1,\"unitAmountMinor\":1300}]}";
        return send(HttpMethod.POST, "/api/v1/finance/invoices", body, subject, org, null);
    }

    private ResponseEntity<String> issue(String org, String subject, String id, int version) {
        return send(HttpMethod.POST, "/api/v1/finance/invoices/" + id + "/issue", "{}",
                subject, org, String.valueOf(version));
    }

    private ResponseEntity<String> post(String org, String subject, String kind, String id,
                                        int version, String body) {
        return send(HttpMethod.POST, "/api/v1/finance/invoices/" + id + "/" + kind, body,
                subject, org, String.valueOf(version));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/finance/invoices/" + id, null, subject, org, null);
    }

    private static String entryBody(long amount, String reference, String reason, String approver) {
        return "{\"amountMinor\":" + amount + ",\"reference\":\"" + reference + "\""
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + (approver == null ? "" : ",\"approverSubject\":\"" + approver + "\"") + "}";
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "bootstrap-" + org.substring(0, 4));
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
            bind(connection, org, "bootstrap-" + org.substring(0, 4));
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
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
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static String serialized(ResponseEntity<String> response, String pointer) {
        JsonNode node = json(response).at(pointer);
        assertFalse(node.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Prove the database is the second line of defence for ledger immutability.
     * The migration owner has write privileges but FORCE RLS still applies and a
     * BEFORE trigger rejects UPDATE and DELETE against a visible committed row.
     *
     * @param mutation       the UPDATE or DELETE statement to attempt
     * @param reference      reference of a committed ledger entry
     * @throws Exception when the database cannot be reached
     */
    private static void assertLedgerMutationRejected(String mutation, String reference) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try {
                bind(connection, ORG_A, FINANCE);
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT count(*) FROM public.finance_ledger_entry "
                                     + "WHERE reference = '" + reference + "'")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1), "the committed entry must be visible before mutation");
                }
                try (Statement statement = connection.createStatement()) {
                    SQLException failure = assertThrows(SQLException.class, () -> statement.execute(mutation));
                    assertEquals("44000", failure.getSQLState());
                    assertTrue(failure.getMessage().contains("finance_ledger_entry is append-only"),
                            failure.getMessage());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * A payment, an approved credit and an approved refund are three separate
     * linked entries on one invoice: each snapshots the invoice id, revision and
     * single currency, receipts are numbered, and an earlier entry is unchanged
     * byte for byte after later posts and cannot be updated or deleted.
     *
     * @throws Exception when a database-level assertion cannot be evaluated
     */
    @Test
    void paymentRefundAndCreditAreLinkedImmutableEntriesWithNumberedReceipts() throws Exception {
        String id = text(create(ORG_A, FINANCE), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(ORG_A, FINANCE, id, 1).getStatusCode());

        ResponseEntity<String> paid = post(ORG_A, FINANCE, "payments", id, 2,
                entryBody(300L, "CL3-PAY-LINK", null, null));
        assertEquals(HttpStatus.OK, paid.getStatusCode(), paid.getBody());
        String firstEntryJson = serialized(paid, "/entries/0");

        ResponseEntity<String> credited = post(ORG_A, FINANCE, "credits", id, 3,
                entryBody(200L, "CL3-CREDIT-LINK", "approved write-off", APPROVER));
        assertEquals(HttpStatus.OK, credited.getStatusCode(), credited.getBody());
        String secondEntryJson = serialized(credited, "/entries/1");

        ResponseEntity<String> refunded = post(ORG_A, FINANCE, "refunds", id, 4,
                entryBody(150L, "CL3-REFUND-LINK", "patient requested return", APPROVER));
        assertEquals(HttpStatus.OK, refunded.getStatusCode(), refunded.getBody());

        JsonNode entries = json(refunded).at("/entries");
        assertEquals(3, entries.size());
        for (JsonNode entry : entries) {
            assertEquals(id, entry.at("/invoiceId").asText(), "every entry links to its invoice");
            assertEquals("USD", entry.at("/currency").asText(), "every entry snapshots the invoice currency");
        }
        assertEquals("payment", entries.at("/0/type").asText());
        assertEquals("credit", entries.at("/1/type").asText());
        assertEquals("refund", entries.at("/2/type").asText());
        assertEquals("2", entries.at("/0/invoiceVersion").asText());
        assertEquals("3", entries.at("/1/invoiceVersion").asText());
        assertEquals("4", entries.at("/2/invoiceVersion").asText());

        long firstReceipt = Long.parseLong(entries.at("/0/receiptNo").asText());
        long secondReceipt = Long.parseLong(entries.at("/1/receiptNo").asText());
        long thirdReceipt = Long.parseLong(entries.at("/2/receiptNo").asText());
        assertTrue(firstReceipt > 0);
        assertEquals(firstReceipt + 1, secondReceipt, "receipt numbers increase without a gap");
        assertEquals(secondReceipt + 1, thirdReceipt);

        assertNull(text(refunded, "/entries/0/reason"), "a payment needs no reason");
        assertEquals("approved write-off", text(refunded, "/entries/1/reason"));
        assertEquals(APPROVER, text(refunded, "/entries/1/approverSubject"));
        assertEquals("patient requested return", text(refunded, "/entries/2/reason"));
        assertEquals(APPROVER, text(refunded, "/entries/2/approverSubject"));
        assertEquals(actorId(ORG_A, FINANCE), text(refunded, "/entries/0/actorId"),
                "the ledger actor comes from the tenant context, not the request body");

        assertEquals(firstEntryJson, serialized(refunded, "/entries/0"), "the payment entry is unchanged");
        assertEquals(secondEntryJson, serialized(refunded, "/entries/1"), "the credit entry is unchanged");
        String freshRead = serialized(get(ORG_A, FINANCE, id), "/entries/0");
        assertEquals(firstEntryJson, freshRead, "a fresh read returns the same immutable entry");
        assertEquals("300", text(refunded, "/invoice/paidMinor"));
        assertEquals("200", text(refunded, "/invoice/creditedMinor"));
        assertEquals("150", text(refunded, "/invoice/refundedMinor"));
        assertEquals("950", text(refunded, "/invoice/balanceMinor"));

        assertLedgerMutationRejected(
                "UPDATE public.finance_ledger_entry SET amount_minor = amount_minor + 1 "
                        + "WHERE reference = 'CL3-PAY-LINK'", "CL3-PAY-LINK");
        assertLedgerMutationRejected(
                "DELETE FROM public.finance_ledger_entry WHERE reference = 'CL3-PAY-LINK'",
                "CL3-PAY-LINK");
    }

    /**
     * Credits and refunds refuse an absent or blank reason and an absent,
     * self-referencing or unauthorised approver before any entry or balance
     * change is committed; the same command succeeds with both present.
     */
    @Test
    void refundAndCreditRequireReasonAndAnIndependentApprover() {
        String id = text(create(ORG_A, FINANCE), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(ORG_A, FINANCE, id, 1).getStatusCode());
        assertEquals(HttpStatus.OK, post(ORG_A, FINANCE, "payments", id, 2,
                entryBody(500L, "CL3-FUND-PAY", null, null)).getStatusCode());

        ResponseEntity<String> missingReason = post(ORG_A, FINANCE, "refunds", id, 3,
                entryBody(100L, "CL3-NO-REASON", null, APPROVER));
        assertEquals(422, missingReason.getStatusCode().value(), missingReason.getBody());
        assertEquals("INVALID_REASON", text(missingReason, "/code"));
        ResponseEntity<String> blankReason = post(ORG_A, FINANCE, "refunds", id, 3,
                entryBody(100L, "CL3-BLANK-REASON", "   ", APPROVER));
        assertEquals(422, blankReason.getStatusCode().value(), blankReason.getBody());
        assertEquals("INVALID_REASON", text(blankReason, "/code"));

        ResponseEntity<String> missingApprover = post(ORG_A, FINANCE, "credits", id, 3,
                entryBody(100L, "CL3-NO-APPROVER", "write-off", null));
        assertEquals(422, missingApprover.getStatusCode().value(), missingApprover.getBody());
        assertEquals("INVALID_APPROVERSUBJECT", text(missingApprover, "/code"));
        ResponseEntity<String> selfApproval = post(ORG_A, FINANCE, "credits", id, 3,
                entryBody(100L, "CL3-SELF", "write-off", FINANCE));
        assertEquals(403, selfApproval.getStatusCode().value(), selfApproval.getBody());
        assertEquals("SEPARATION_OF_DUTIES", text(selfApproval, "/code"));
        ResponseEntity<String> wrongRoleApprover = post(ORG_A, FINANCE, "credits", id, 3,
                entryBody(100L, "CL3-ROLE", "write-off", RECEPTION));
        assertEquals(403, wrongRoleApprover.getStatusCode().value(), wrongRoleApprover.getBody());
        assertEquals("INVALID_APPROVER", text(wrongRoleApprover, "/code"));
        // A syntactically valid but unknown subject reaches the policy check and
        // is refused there, unlike a missing subject which fails input validation.
        ResponseEntity<String> nonMemberApprover = post(ORG_A, FINANCE, "credits", id, 3,
                entryBody(100L, "CL3-NO-MEMBER", "write-off", "no-such-user"));
        assertEquals(403, nonMemberApprover.getStatusCode().value(), nonMemberApprover.getBody());
        assertEquals("INVALID_APPROVER", text(nonMemberApprover, "/code"));

        ResponseEntity<String> credited = post(ORG_A, FINANCE, "credits", id, 3,
                entryBody(100L, "CL3-OK", "approved write-off", APPROVER));
        assertEquals(HttpStatus.OK, credited.getStatusCode(), credited.getBody());
        assertEquals("700", text(credited, "/invoice/balanceMinor"));
        assertEquals(2, json(credited).at("/entries").size(),
                "only the funding payment and the authorized credit posted");
        assertEquals("CL3-OK", text(credited, "/entries/1/reference"));
    }

    /**
     * A role without finance permission, a member of another tenant and a revoked
     * member cannot post an entry, and none of the denials changes the trail.
     */
    @Test
    void receptionRoleAndOtherTenantCannotPostAnEntry() {
        String id = text(create(ORG_A, FINANCE), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(ORG_A, FINANCE, id, 1).getStatusCode());

        ResponseEntity<String> wrongRole = post(ORG_A, RECEPTION, "payments", id, 2,
                entryBody(100L, "CL3-WRONG-ROLE", null, null));
        assertEquals(HttpStatus.FORBIDDEN, wrongRole.getStatusCode(), wrongRole.getBody());
        assertEquals("FORBIDDEN", text(wrongRole, "/code"));

        // The invoice really exists, in the other tenant: the owning finance
        // member sees it, while the ORG_A actor gets 404 from forced RLS rather
        // than from a nonexistent identifier.
        String otherOrgId = text(create(ORG_B, "finance-2222"), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(ORG_B, "finance-2222", otherOrgId, 1).getStatusCode());
        assertEquals(HttpStatus.OK, get(ORG_B, "finance-2222", otherOrgId).getStatusCode());
        ResponseEntity<String> otherTenant = post(ORG_A, FINANCE, "payments", otherOrgId, 2,
                entryBody(100L, "CL3-OTHER-TENANT", null, null));
        assertEquals(HttpStatus.NOT_FOUND, otherTenant.getStatusCode(), otherTenant.getBody());

        ResponseEntity<String> revokedMember = post(ORG_A, REVOKED, "payments", id, 2,
                entryBody(100L, "CL3-REVOKED", null, null));
        assertEquals(HttpStatus.FORBIDDEN, revokedMember.getStatusCode(), revokedMember.getBody());
        assertEquals("FORBIDDEN", text(revokedMember, "/code"));

        assertEquals(0, json(get(ORG_A, FINANCE, id)).at("/entries").size(),
                "a denied post leaves no ledger entry");
        assertEquals("1300", text(get(ORG_A, FINANCE, id), "/invoice/balanceMinor"));
    }

    /**
     * A stale version, a draft or void invoice and a reference reused with a
     * different payload are each refused without posting a second entry.
     */
    @Test
    void staleVersionAndForbiddenStatesRefuseEntries() {
        String draft = text(create(ORG_A, FINANCE), "/invoice/id");
        ResponseEntity<String> draftDenied = post(ORG_A, FINANCE, "payments", draft, 1,
                entryBody(100L, "CL3-DRAFT", null, null));
        assertEquals(409, draftDenied.getStatusCode().value(), draftDenied.getBody());
        assertEquals("INVALID_TRANSITION", text(draftDenied, "/code"));

        String id = text(create(ORG_A, FINANCE), "/invoice/id");
        assertEquals(HttpStatus.OK, issue(ORG_A, FINANCE, id, 1).getStatusCode());
        ResponseEntity<String> stale = post(ORG_A, FINANCE, "payments", id, 9,
                entryBody(100L, "CL3-STALE", null, null));
        assertEquals(412, stale.getStatusCode().value(), stale.getBody());
        assertEquals("STALE_VERSION", text(stale, "/code"));

        assertEquals(HttpStatus.OK, post(ORG_A, FINANCE, "payments", id, 2,
                entryBody(200L, "CL3-REPLAY-REF", null, null)).getStatusCode());
        ResponseEntity<String> conflict = post(ORG_A, FINANCE, "refunds", id, 3,
                entryBody(200L, "CL3-REPLAY-REF", "different payload", APPROVER));
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertEquals("REFERENCE_CONFLICT", text(conflict, "/code"));
        String lastReceipt = text(get(ORG_A, FINANCE, id), "/entries/0/receiptNo");
        assertEquals("1100", text(get(ORG_A, FINANCE, id), "/invoice/balanceMinor"));
        assertEquals(1, json(get(ORG_A, FINANCE, id)).at("/entries").size());

        // Replaying the identical command reuses the original outcome: no second
        // entry, no new receipt and no further balance change.
        ResponseEntity<String> replay = post(ORG_A, FINANCE, "payments", id, 3,
                entryBody(200L, "CL3-REPLAY-REF", null, null));
        assertEquals(HttpStatus.OK, replay.getStatusCode(), replay.getBody());
        assertEquals("1100", text(replay, "/invoice/balanceMinor"));
        assertEquals(1, json(replay).at("/entries").size());
        assertEquals(lastReceipt, text(replay, "/entries/0/receiptNo"));

        String voided = text(create(ORG_A, FINANCE), "/invoice/id");
        ResponseEntity<String> voidResponse = send(HttpMethod.POST,
                "/api/v1/finance/invoices/" + voided + "/void",
                "{\"reason\":\"synthetic cancellation\",\"approverSubject\":\"" + APPROVER + "\"}",
                FINANCE, ORG_A, "1");
        assertEquals(HttpStatus.OK, voidResponse.getStatusCode(), voidResponse.getBody());
        ResponseEntity<String> voidDenied = post(ORG_A, FINANCE, "payments", voided, 2,
                entryBody(100L, "CL3-VOID", null, null));
        assertEquals(409, voidDenied.getStatusCode().value(), voidDenied.getBody());
        assertEquals("INVALID_TRANSITION", text(voidDenied, "/code"));
    }
}
