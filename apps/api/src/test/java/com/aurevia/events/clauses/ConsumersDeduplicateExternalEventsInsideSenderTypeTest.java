package com.aurevia.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.events.DeliveryStore;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.JdbcDeliveryStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-05 clause 3 API proof against real PostgreSQL: an inbound partner event is
 * deduplicated inside its sender/type namespace, a stale aggregate change is held,
 * an unconfirmed identifier is rejected, and every processing outcome is recorded
 * in the tenant's audit trail. All commands travel through the real HTTP entry
 * point so the tenant interceptor binds {@code app.current_org} and {@code app.actor};
 * reads bind the same context explicitly. Identifiers and digests are synthetic,
 * and each test owns a distinct namespace so receipts never leak between tests.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_delivery_dedup_clause_it")
@ActiveProfiles("synthetic-auth")
class ConsumersDeduplicateExternalEventsInsideSenderTypeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String MISSING = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    private static final String DIGEST = "a".repeat(64);
    private static final String CORRELATION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String DB = "aurevia_delivery_dedup_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONSUME = "/api/v1/delivery/consumer-events";

    private static final String AGG_DUPLICATE = "10000000-0000-0000-0000-000000000001";
    private static final String AGG_HELD = "10000000-0000-0000-0000-000000000002";
    private static final String AGG_REJECTED = "10000000-0000-0000-0000-000000000003";
    private static final String AGG_ROLE = "10000000-0000-0000-0000-000000000004";
    private static final String AGG_TENANT = "10000000-0000-0000-0000-000000000005";

    @Autowired
    DeliveryStore deliveryStore;

    @Autowired
    JdbcDeliveryStore store;

    RestClient rest;

    /** Create the isolated database, apply the production migrations and seed two tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            // A separate runtime role without bypass keeps FORCE RLS meaningful.
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
            try (ResultSet roles = statement.executeQuery(
                    "SELECT rolname, rolbypassrls FROM pg_roles "
                            + "WHERE rolname IN ('aurevia_migrate', 'aurevia_app')")) {
                int checked = 0;
                while (roles.next()) {
                    assertFalse(roles.getBoolean("rolbypassrls"),
                            "the runtime roles must not bypass the forced row level security");
                    checked++;
                }
                assertEquals(2, checked, "both migration and runtime roles exist without BYPASSRLS");
            }
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "worker-a", "worker");
            seedUser(statement, ORG_A, "operator-a", "operator");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedUser(statement, ORG_B, "worker-b", "worker");
            seedUser(statement, ORG_B, "auditor-b", "privacy_auditor");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'worker-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-DEDUP-CLAUSE', 'CLINIC', "
                    + "'Synthetic Delivery Patient', 'synthetic delivery patient', DATE '1990-05-20')");
        }
    }

    /** Drop the synthetic database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic " + org.substring(0, 4) + "' "
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

    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private ResponseEntity<String> consume(String body, String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("X-Correlation-Id", CORRELATION);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(HttpMethod.POST).uri(CONSUME)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body(body).retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String event(String externalId, String sender, String namespace, String aggregateId,
                                int version, String orderId, String patientId) {
        return event(externalId, sender, namespace, "observation.result", aggregateId,
                version, orderId, patientId);
    }

    private static String event(String externalId, String sender, String namespace, String eventType,
                                String aggregateId, int version, String orderId, String patientId) {
        return "{\"externalEventId\":\"" + externalId + "\",\"sender\":\"" + sender + "\""
                + ",\"eventType\":\"" + eventType + "\",\"namespace\":\"" + namespace + "\""
                + ",\"aggregateId\":\"" + aggregateId + "\",\"aggregateVersion\":" + version
                + ",\"orderId\":" + (orderId == null ? "null" : "\"" + orderId + "\"")
                + ",\"patientId\":" + (patientId == null ? "null" : "\"" + patientId + "\"")
                + ",\"payloadDigest\":\"" + DIGEST + "\"}";
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

    /** Bind the caller's tenant on this thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DELIVERY", "dedup-clause-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private List<DeliveryAuditEvent> consumeAudits(String org, String auditor, String externalEventId) {
        return as(org, auditor, () -> store.auditEvents(org).stream()
                .filter(audit -> "consume".equals(audit.action().wire())
                        && externalEventId.equals(audit.resourceId()))
                .toList());
    }

    private int receiptCount(String externalEventId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.delivery_receipt WHERE external_event_id = '" + externalEventId + "'")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void aRepeatedExternalEventInTheSameSenderTypeNamespaceReturnsTheOriginalReceipt() throws Exception {
        String body = event("duplicate-ext", "lab-partner", "dedup-ns", AGG_DUPLICATE, 1, null, null);
        ResponseEntity<String> first = consume(body, "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals("accepted", field(first.getBody(), "outcome"));
        String receiptId = field(first.getBody(), "receiptId");
        assertFalse(receiptId.isEmpty(), "an accepted event carries an opaque receipt id");

        ResponseEntity<String> repeat = consume(body, "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, repeat.getStatusCode(), repeat.getBody());
        assertEquals(receiptId, field(repeat.getBody(), "receiptId"),
                "a repeated event returns the original receipt, not a second effect");
        assertEquals("accepted", field(repeat.getBody(), "outcome"),
                "a duplicate returns the original immutable receipt outcome");
        List<DeliveryAuditEvent> audits = consumeAudits(ORG_A, "auditor-a", "duplicate-ext");
        assertEquals(2, audits.size(), "both attempts are audited");
        assertEquals(1, audits.stream().filter(a -> a.outcome() == Outcome.SUCCESS).count(),
                "the first attempt records a success");
        assertEquals(1, audits.stream().filter(a -> a.outcome() == Outcome.DUPLICATE).count(),
                "the repeat records a duplicate processing outcome");
        assertEquals(1, receiptCount("duplicate-ext"), "only one receipt is stored for the event");

        // The same external id from another sender in the same namespace is a distinct event.
        ResponseEntity<String> otherSender = consume(
                event("duplicate-ext", "radiology-partner", "dedup-ns", AGG_DUPLICATE, 1, null, null),
                "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, otherSender.getStatusCode(), otherSender.getBody());
        assertEquals("accepted", field(otherSender.getBody(), "outcome"));
        assertNotEquals(receiptId, field(otherSender.getBody(), "receiptId"));
        assertEquals(2, receiptCount("duplicate-ext"),
                "the sender is part of the deduplication namespace");

        // Holding sender and external id fixed while varying only the namespace or
        // event type also produces a distinct event: both are part of the dedup key.
        ResponseEntity<String> otherNamespace = consume(
                event("duplicate-ext", "lab-partner", "another-dedup-ns", AGG_DUPLICATE, 1, null, null),
                "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, otherNamespace.getStatusCode(), otherNamespace.getBody());
        assertEquals("accepted", field(otherNamespace.getBody(), "outcome"));
        assertNotEquals(receiptId, field(otherNamespace.getBody(), "receiptId"));

        ResponseEntity<String> otherType = consume(
                event("duplicate-ext", "lab-partner", "dedup-ns", "order.placed",
                        AGG_DUPLICATE, 1, null, null),
                "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, otherType.getStatusCode(), otherType.getBody());
        assertEquals("accepted", field(otherType.getBody(), "outcome"));
        assertNotEquals(receiptId, field(otherType.getBody(), "receiptId"));
        assertEquals(4, receiptCount("duplicate-ext"),
                "sender, event type and namespace together scope an external event id");
    }

    @Test
    void anExternalEventOlderThanTheHighestProcessedVersionIsHeld() {
        consume(event("held-high", "lab-partner", "held-ns", AGG_HELD, 5, null, null), "worker-a", ORG_A);
        ResponseEntity<String> stale = consume(
                event("held-low", "lab-partner", "held-ns", AGG_HELD, 3, null, null), "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, stale.getStatusCode(), stale.getBody());
        assertEquals("held_out_of_order", field(stale.getBody(), "outcome"));
        assertEquals("out_of_order", field(stale.getBody(), "detail"));
        assertEquals(ProcessingOutcome.HELD_OUT_OF_ORDER,
                as(ORG_A, "auditor-a", () -> store.receipt(ORG_A, "held-ns", "lab-partner",
                        "observation.result", "held-low").orElseThrow()).outcome());
        assertTrue(consumeAudits(ORG_A, "auditor-a", "held-low").stream()
                .anyMatch(a -> a.outcome() == Outcome.HELD), "the held event is audited as held");

        ResponseEntity<String> correction = consume(
                event("held-correction", "lab-partner", "held-ns", AGG_HELD, 6, null, null), "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, correction.getStatusCode(), correction.getBody());
        assertEquals("accepted", field(correction.getBody(), "outcome"));
        assertEquals(OptionalInt.of(6), as(ORG_A, "worker-a", () -> store.maxVersion(
                ORG_A, "held-ns", "lab-partner", "observation.result", AGG_HELD)),
                "a later version is accepted and becomes the new ordering watermark");
    }

    @Test
    void anExternalEventWithUnconfirmedOrderOrPatientIsRejectedAndRecorded() {
        ResponseEntity<String> unknownOrder = consume(
                event("reject-order", "lab-partner", "reject-order-ns", AGG_REJECTED, 1, MISSING, null),
                "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, unknownOrder.getStatusCode(), unknownOrder.getBody());
        assertEquals("rejected", field(unknownOrder.getBody(), "outcome"));
        assertEquals("order_not_found", field(unknownOrder.getBody(), "detail"));
        assertEquals(ProcessingOutcome.REJECTED,
                as(ORG_A, "auditor-a", () -> store.receiptById(ORG_A, "reject-order").orElseThrow()).outcome());
        assertTrue(consumeAudits(ORG_A, "auditor-a", "reject-order").stream()
                .anyMatch(a -> a.outcome() == Outcome.NOT_FOUND));

        ResponseEntity<String> unknownPatient = consume(
                event("reject-patient", "lab-partner", "reject-patient-ns", AGG_REJECTED, 1, null, MISSING),
                "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, unknownPatient.getStatusCode(), unknownPatient.getBody());
        assertEquals("rejected", field(unknownPatient.getBody(), "outcome"));
        assertEquals("patient_not_found", field(unknownPatient.getBody(), "detail"));
        assertEquals(ProcessingOutcome.REJECTED,
                as(ORG_A, "auditor-a", () -> store.receiptById(ORG_A, "reject-patient").orElseThrow()).outcome());
        assertTrue(consumeAudits(ORG_A, "auditor-a", "reject-patient").stream()
                .anyMatch(a -> a.outcome() == Outcome.NOT_FOUND));
    }

    @Test
    void aSubjectWithoutTheConsumerRoleIsForbidden() throws Exception {
        String body = event("denied-ext", "lab-partner", "denied-ns", AGG_ROLE, 1, null, null);
        ResponseEntity<String> denied = consume(body, "operator-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
        assertTrue(consumeAudits(ORG_A, "auditor-a", "denied-ext").stream()
                .anyMatch(a -> a.outcome() == Outcome.DENIED), "the denial is audited");
        assertTrue(as(ORG_A, "worker-a", () -> store.receiptById(ORG_A, "denied-ext")).isEmpty(),
                "a denied consume writes no receipt");
        assertEquals(0, receiptCount("denied-ext"));
    }

    @Test
    void receiptsAreIsolatedAcrossTenantsAndDeduplicationIsTenantScoped() {
        String body = event("tenant-ext", "lab-partner", "tenant-ns", AGG_TENANT, 1, null, null);
        ResponseEntity<String> first = consume(body, "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        assertEquals("accepted", field(first.getBody(), "outcome"));
        String receiptIdA = field(first.getBody(), "receiptId");

        ResponseEntity<String> second = consume(body, "worker-b", ORG_B);
        assertEquals(HttpStatus.OK, second.getStatusCode(), second.getBody());
        assertEquals("accepted", field(second.getBody(), "outcome"));
        String receiptIdB = field(second.getBody(), "receiptId");
        assertNotEquals(receiptIdA, receiptIdB, "the same external id yields independent receipts per tenant");

        assertEquals(ORG_A, as(ORG_A, "worker-a", () -> store.receiptById(ORG_A, "tenant-ext")
                .orElseThrow()).organizationId());
        assertEquals(ORG_B, as(ORG_B, "worker-b", () -> store.receiptById(ORG_B, "tenant-ext")
                .orElseThrow()).organizationId());
        assertNotNull(deliveryStore, "the production DeliveryStore bean backs the controller");

        // Create a receipt that exists only in ORG_A, then read it from both tenants.
        ResponseEntity<String> aOnly = consume(
                event("a-only-ext", "lab-partner", "a-only-ns", AGG_TENANT, 1, null, null),
                "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, aOnly.getStatusCode(), aOnly.getBody());
        assertEquals("accepted", field(aOnly.getBody(), "outcome"));
        assertEquals(ORG_A, as(ORG_A, "worker-a", () -> store.receiptById(ORG_A, "a-only-ext")
                .orElseThrow()).organizationId());
        assertTrue(as(ORG_B, "worker-b", () -> store.receiptById(ORG_B, "a-only-ext")).isEmpty(),
                "forced RLS conceals an ORG_A receipt from ORG_B even though the row exists");
    }
}
