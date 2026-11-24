package com.aurevia.finance.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
 * FIN-01 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>An active finance member creates itemized self-pay invoices through the
 * normal HTTP entry point. The response and the durable rows must carry the
 * same single currency and the same exact integer minor-unit arithmetic, and
 * the rule must refuse bad currency, non-fixed-precision money, an actor
 * without the finance role and a patient invisible to the active tenant.
 * Durable rows are read back under forced row-level security, so a result
 * proves end-to-end behavior rather than unit coverage. No clinical data is
 * used or retained.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_fin01_clause1_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class GenerateItemizedSelfPayInvoicesCurrencyInvoiceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_fin01_clause1_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String FINANCE_A = "finance-1111";
    private static final String RECEPTION_A = "reception-1111";
    private static final String FINANCE_B = "finance-2222";
    private static final String PATH = "/api/v1/finance/invoices";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TWO_LINES =
            "[{\"description\":\"three thirds\",\"quantity\":3,\"unitAmountMinor\":333},"
            + "{\"description\":\"seven parts\",\"quantity\":7,\"unitAmountMinor\":143}]";
    private static final String ONE_LINE =
            "[{\"description\":\"synthetic fee\",\"quantity\":1,\"unitAmountMinor\":100}]";

    /** Counts of finance business rows; the 403 case appends an audit row but no finance row. */
    private record Counts(long invoices, long items, long entries) {
    }

    /** Durable invoice projection and its itemized lines, read under the active tenant. */
    private record InvoiceSnapshot(String currency, String state, long totalMinor, long balanceMinor,
                                   List<ItemSnapshot> items) {
    }

    /** One durable itemized line; its currency is inherited from the owning invoice. */
    private record ItemSnapshot(String invoiceId, int lineNo, int quantity,
                                long unitAmountMinor, long amountMinor) {
    }

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
            statement.execute("DROP DATABASE IF EXISTS orevia_fin01_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_fin01_clause1_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG_A + "', 'Synthetic Clause A')");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG_A + "', 'bootstrap-1111', 'Bootstrap')");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', 1, 'MRN-CLAUSE-1', 'CLINIC', 'Synthetic Patient', "
                    + "'synthetic patient', '1990-05-20')");
            // ORG_B needs an active finance member: authorization runs before patient
            // visibility, so without this the peer-tenant case would be a 403, not a 404.
            seedStaff(statement, ORG_A, FINANCE_A, "finance");
            seedStaff(statement, ORG_A, RECEPTION_A, "reception");
            seedStaff(statement, ORG_B, FINANCE_B, "finance");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_fin01_clause1_it WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", "fin01-c1-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String body, String subject, String organization) {
        return rest.method(HttpMethod.POST).uri(PATH)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String createBody(String patientId, String currency, String items) {
        return "{\"patientId\":\"" + patientId + "\",\"currency\":\"" + currency + "\","
                + "\"serviceReference\":\"FIN01-C1-SYNTHETIC\",\"items\":" + items + "}";
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse finance response", exception);
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

    private static String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, FINANCE_A);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-CLAUSE-1'")) {
                assertTrue(result.next(), "the synthetic patient must exist in organization A");
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private static Counts counts(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (Statement statement = connection.createStatement()) {
                return new Counts(count(statement, "finance_invoice"),
                        count(statement, "finance_invoice_item"),
                        count(statement, "finance_ledger_entry"));
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot count finance rows", exception);
        }
    }

    private static long count(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT count(*) FROM public." + table)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static long countById(String org, String subject, String sql, String id) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot count scoped rows", exception);
        }
    }

    private static InvoiceSnapshot invoice(String org, String subject, String id) {
        String invoiceSql = "SELECT currency, state, total_minor, balance_minor FROM public.finance_invoice "
                + "WHERE id = CAST(? AS uuid)";
        String itemSql = "SELECT invoice_id::text, line_no, quantity, unit_amount_minor, amount_minor "
                + "FROM public.finance_invoice_item WHERE invoice_id = CAST(? AS uuid) ORDER BY line_no";
        List<ItemSnapshot> items = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, subject);
            try {
                String currency;
                String state;
                long total;
                long balance;
                try (PreparedStatement statement = connection.prepareStatement(invoiceSql)) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next(), "the created invoice must be durable");
                        currency = result.getString(1);
                        state = result.getString(2);
                        total = result.getLong(3);
                        balance = result.getLong(4);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(itemSql)) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            items.add(new ItemSnapshot(result.getString(1), result.getInt(2),
                                    result.getInt(3), result.getLong(4), result.getLong(5)));
                        }
                    }
                }
                return new InvoiceSnapshot(currency, state, total, balance, items);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the durable invoice", exception);
        }
    }

    @Test
    void createsItemizedDraftInvoiceInOneCurrencyWithExactMinorUnits() throws Exception {
        Counts before = counts(ORG_A, FINANCE_A);
        ResponseEntity<String> created = post(createBody(patientId(), "usd", TWO_LINES),
                FINANCE_A, ORG_A);
        assertEquals(201, created.getStatusCode().value(), created.getBody());

        JsonNode body = tree(created);
        String id = body.at("/invoice/id").asText();
        assertFalse(id.isBlank(), "the created invoice id must be present");
        assertEquals("/api/v1/finance/invoices/" + id, created.getHeaders().getLocation().toString());
        assertEquals("USD", body.at("/invoice/currency").asText());
        assertEquals("draft", body.at("/invoice/state").asText());
        assertTrue(body.at("/invoice/totalMinor").isIntegralNumber());
        assertEquals(2000L, body.at("/invoice/totalMinor").asLong());
        assertEquals(2000L, body.at("/invoice/balanceMinor").asLong());

        JsonNode items = body.at("/items");
        assertEquals(2, items.size());
        assertEquals(3, items.get(0).at("/quantity").asInt());
        assertEquals(333L, items.get(0).at("/unitAmountMinor").asLong());
        assertTrue(items.get(0).at("/amountMinor").isIntegralNumber());
        assertEquals(999L, items.get(0).at("/amountMinor").asLong());
        assertEquals(7, items.get(1).at("/quantity").asInt());
        assertEquals(143L, items.get(1).at("/unitAmountMinor").asLong());
        assertEquals(1001L, items.get(1).at("/amountMinor").asLong());

        InvoiceSnapshot durable = invoice(ORG_A, FINANCE_A, id);
        assertEquals("USD", durable.currency());
        assertEquals("draft", durable.state());
        assertEquals(2000L, durable.totalMinor());
        assertEquals(2000L, durable.balanceMinor());
        assertEquals(2, durable.items().size());
        ItemSnapshot first = durable.items().get(0);
        ItemSnapshot second = durable.items().get(1);
        assertEquals(id, first.invoiceId());
        assertEquals(1, first.lineNo());
        assertEquals(3, first.quantity());
        assertEquals(333L, first.unitAmountMinor());
        assertEquals(999L, first.amountMinor());
        assertEquals(id, second.invoiceId());
        assertEquals(2, second.lineNo());
        assertEquals(7, second.quantity());
        assertEquals(143L, second.unitAmountMinor());
        assertEquals(1001L, second.amountMinor());

        // A second invoice in another currency proves the currency belongs to
        // one invoice rather than to a process-wide constant.
        ResponseEntity<String> eur = post(createBody(patientId(), "eur", ONE_LINE), FINANCE_A, ORG_A);
        assertEquals(201, eur.getStatusCode().value(), eur.getBody());
        String eurId = tree(eur).at("/invoice/id").asText();
        assertEquals("EUR", tree(eur).at("/invoice/currency").asText());
        assertEquals("EUR", invoice(ORG_A, FINANCE_A, eurId).currency());
        assertEquals("USD", invoice(ORG_A, FINANCE_A, id).currency());

        Counts after = counts(ORG_A, FINANCE_A);
        assertEquals(before.invoices() + 2, after.invoices());
        assertEquals(before.items() + 3, after.items());
        assertEquals(before.entries(), after.entries());
    }

    @Test
    void rejectsInvalidCurrencyBeforeWritingAnyInvoice() {
        Counts before = counts(ORG_A, FINANCE_A);
        ResponseEntity<String> rejected = post(createBody(patientId(), "US", ONE_LINE),
                FINANCE_A, ORG_A);
        assertEquals(422, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("INVALID_CURRENCY", tree(rejected).at("/code").asText(), rejected.getBody());
        assertEquals(before, counts(ORG_A, FINANCE_A));
    }

    @Test
    void rejectsNonPositiveAndOverflowingMinorUnits() {
        Counts before = counts(ORG_A, FINANCE_A);
        String patient = patientId();

        ResponseEntity<String> negative = post(createBody(patient, "USD",
                "[{\"description\":\"bad unit\",\"quantity\":1,\"unitAmountMinor\":-1}]"),
                FINANCE_A, ORG_A);
        assertEquals(422, negative.getStatusCode().value(), negative.getBody());
        assertEquals("INVALID_UNITAMOUNTMINOR", tree(negative).at("/code").asText(), negative.getBody());

        // quantity is an Integer and unitAmountMinor is a Long in the contract,
        // so overflow the multiply by the long unit rather than the quantity.
        ResponseEntity<String> overflow = post(createBody(patient, "USD",
                "[{\"description\":\"overflow\",\"quantity\":2,"
                + "\"unitAmountMinor\":9223372036854775807}]"), FINANCE_A, ORG_A);
        assertEquals(422, overflow.getStatusCode().value(), overflow.getBody());
        assertEquals("AMOUNT_OVERFLOW", tree(overflow).at("/code").asText(), overflow.getBody());

        assertEquals(before, counts(ORG_A, FINANCE_A));
    }

    @Test
    void refusesCreationWithoutFinanceRoleAndForPeerTenantPatient() {
        Counts before = counts(ORG_A, FINANCE_A);
        String patient = patientId();

        ResponseEntity<String> denied = post(createBody(patient, "USD", ONE_LINE), RECEPTION_A, ORG_A);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
        assertEquals("FORBIDDEN", tree(denied).at("/code").asText(), denied.getBody());
        assertEquals(before, counts(ORG_A, FINANCE_A));

        // Create an invoice owned by organization A so its invisibility from
        // organization B can be asserted alongside the HTTP refusal.
        ResponseEntity<String> owned = post(createBody(patient, "USD", ONE_LINE), FINANCE_A, ORG_A);
        assertEquals(201, owned.getStatusCode().value(), owned.getBody());
        String ownedId = tree(owned).at("/invoice/id").asText();
        long peerInvoiceBefore = countById(ORG_B, FINANCE_B,
                "SELECT count(*) FROM public.finance_invoice WHERE id = CAST(? AS uuid)", ownedId);
        assertEquals(0L, peerInvoiceBefore);
        long peerPatientBefore = countById(ORG_B, FINANCE_B,
                "SELECT count(*) FROM public.patient_record WHERE id = CAST(? AS uuid)", patient);
        assertEquals(0L, peerPatientBefore);

        Counts peerBefore = counts(ORG_B, FINANCE_B);
        ResponseEntity<String> crossTenant = post(createBody(patient, "USD", ONE_LINE),
                FINANCE_B, ORG_B);
        assertEquals(404, crossTenant.getStatusCode().value(), crossTenant.getBody());
        assertEquals("NOT_FOUND", tree(crossTenant).at("/code").asText(), crossTenant.getBody());
        assertEquals(peerBefore, counts(ORG_B, FINANCE_B));

        assertEquals(0L, countById(ORG_B, FINANCE_B,
                "SELECT count(*) FROM public.finance_invoice WHERE id = CAST(? AS uuid)", ownedId));
        assertEquals(1L, countById(ORG_A, FINANCE_A,
                "SELECT count(*) FROM public.finance_invoice WHERE id = CAST(? AS uuid)", ownedId));
    }
}
