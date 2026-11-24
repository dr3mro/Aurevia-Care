package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
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
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.JdbcDeliveryStore;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-05 acceptance for the PostgreSQL delivery outbox adapter.
 *
 * <p>Commands go through the real HTTP entry point so the tenant-guarded
 * transaction binds {@code app.current_org} and {@code app.actor}; direct store
 * reads bind the same thread-local context explicitly. All identifiers and
 * digests are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_delivery_store_it")
@ActiveProfiles("synthetic-auth")
class JdbcDeliveryStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String MISSING = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    static final String DIGEST = "a".repeat(64);
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_delivery_store_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DeliveryStore deliveryStore;

    @Autowired
    JdbcDeliveryStore store;

    @LocalServerPort
    int port;

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_delivery_store_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_delivery_store_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "worker-a", "worker");
            seedUser(statement, ORG_A, "operator-a", "operator");
            seedUser(statement, ORG_A, "admin-a", "integration_admin");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedUser(statement, ORG_B, "worker-b", "worker");
            seedUser(statement, ORG_B, "operator-b", "operator");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'worker-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-DELIVERY', 'CLINIC', "
                    + "'Synthetic Delivery Patient', 'synthetic delivery patient', DATE '1990-05-20')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_delivery_store_it WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
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

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private ResponseEntity<String> request(String method, String uri, String body,
                                           String subject, String org) {
        return request(method, uri, body, subject, org, null);
    }

    private ResponseEntity<String> request(String method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (ifMatch != null) {
            headers.set("If-Match", "\"" + ifMatch + "\"");
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.valueOf(method))
                .uri(uri).headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { }).toEntity(String.class);
    }

    private String publish(String key) {
        String body = "{\"aggregateId\":\"" + AGGREGATE + "\",\"aggregateVersion\":1"
                + ",\"sender\":\"lab-partner\",\"eventType\":\"observation.result\""
                + ",\"namespace\":\"partner-lab\",\"payloadDigest\":\"" + DIGEST + "\""
                + ",\"idempotencyKey\":\"" + key + "\"}";
        ResponseEntity<String> response = request("POST", "/api/v1/delivery/events", body, "worker-a", ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "eventId");
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

    /**
     * Bind the active tenant on the calling thread, as the HTTP interceptor does.
     */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DELIVERY", "delivery-store-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(store);
        assertTrue(deliveryStore instanceof JdbcDeliveryStore,
                "the production DeliveryStore bean must be the PostgreSQL adapter");
    }

    @Test
    void storesAndReadsBackAnEventWithItsAuditFact() throws Exception {
        String id = publish("store-read-1");
        OutboxEvent event = as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow());
        assertEquals(id, event.eventId());
        assertEquals(DIGEST, event.payloadDigest());
        assertEquals(DeliveryState.PENDING, event.state());
        assertNotNull(event.createdAt());
        assertEquals(id, as(ORG_A, "worker-a", () -> store
                .byIdempotencyKey(ORG_A, "lab-partner", "observation.result", "store-read-1")
                .orElseThrow().eventId()));
        List<DeliveryAuditEvent> audits = as(ORG_A, "auditor-a", () -> store.auditEvents(ORG_A));
        assertFalse(audits.isEmpty());
        assertTrue(audits.stream().anyMatch(audit -> id.equals(audit.resourceId())));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT state FROM public.delivery_outbox WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("pending", result.getString("state"));
            }
        }
    }

    @Test
    void reStoresTheSameEventIdWithoutCreatingASecondEffect() throws Exception {
        String id = publish("idempotent-1");
        OutboxEvent original = as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow());
        OutboxEvent replayed = as(ORG_A, "worker-a", () -> store.publishAtomically(ORG_A, original,
                new com.aurevia.events.DurableEventContract.DeliveryAuditEvent(
                        java.util.UUID.randomUUID().toString(), ORG_A, "worker-a", "DELIVERY",
                        com.aurevia.events.DurableEventContract.AuditAction.PUBLISH,
                        id, "delivery-store-it",
                        com.aurevia.events.DurableEventContract.Outcome.DUPLICATE,
                        Instant.parse("2026-11-20T12:00:00Z"))));
        assertEquals(original, replayed);

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.delivery_outbox WHERE idempotency_key = 'idempotent-1'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void deadLettersAFailedEventAndListsItInTheRestrictedQueue() {
        String id = publish("dead-letter-1");
        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id + "/sent",
                null, "operator-a", ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id + "/failed?reason=channel-unavailable",
                null, "operator-a", ORG_A, "2").getStatusCode());
        ResponseEntity<String> retry = request("POST", "/api/v1/delivery/events/" + id + "/retry",
                null, "operator-a", ORG_A, "3");
        assertEquals(HttpStatus.OK, retry.getStatusCode(), retry.getBody());
        OutboxEvent requeued = as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow());
        assertNotNull(requeued.nextAttemptAt(), "a bounded retry records its next dispatch instant");
        assertEquals(DeliveryState.PENDING, requeued.state());
        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id + "/failed?reason=channel-unavailable",
                null, "operator-a", ORG_A, "4").getStatusCode());
        ResponseEntity<String> dead = request("POST",
                "/api/v1/delivery/events/" + id + "/dead-letter?reason=channel-unavailable",
                null, "operator-a", ORG_A);
        assertEquals(HttpStatus.OK, dead.getStatusCode(), dead.getBody());

        DeadLetterEntry entry = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertEquals(id, entry.eventId());
        assertEquals("channel-unavailable", entry.reason());
        assertTrue(as(ORG_A, "admin-a", () -> store.deadLetters(ORG_A)).stream()
                .anyMatch(candidate -> id.equals(candidate.eventId())));
        assertEquals(DeliveryState.DEAD_LETTERED,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state());

        ResponseEntity<String> queue = request("GET", "/api/v1/delivery/dead-letters", null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertTrue(queue.getBody().contains(id));

        ResponseEntity<String> reviewed = request("POST", "/api/v1/delivery/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"requeued\",\"reason\":\"synthetic review\"}",
                "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        DeadLetterEntry reviewedEntry = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertEquals("admin-a", reviewedEntry.reviewer());
        assertEquals(com.aurevia.events.DurableEventContract.Resolution.REQUEUED,
                reviewedEntry.resolution());
        assertEquals(DeliveryState.PENDING,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state());
    }

    @Test
    void tenantBCannotReadTenantAEventsOrDeadLetters() {
        String id = publish("tenant-isolation-1");
        assertTrue(as(ORG_B, "worker-b", () -> store.outboxEvent(ORG_B, id)).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store
                .byIdempotencyKey(ORG_B, "lab-partner", "observation.result", "tenant-isolation-1")).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store.deadLetters(ORG_B)).isEmpty());
        assertTrue(as(ORG_B, "auditor-a", () -> store.auditEvents(ORG_B)).isEmpty());
        assertEquals(HttpStatus.NOT_FOUND, request("POST", "/api/v1/delivery/events/" + id + "/sent",
                null, "worker-b", ORG_B, "1").getStatusCode());
    }

    @Test
    void receiptsReplayAndLinksAreDurable() {
        String eventId = publish("receipt-1");
        String consume = "{\"externalEventId\":\"ext-1\",\"sender\":\"lab-partner\""
                + ",\"eventType\":\"observation.result\",\"namespace\":\"partner-lab\""
                + ",\"aggregateId\":\"" + AGGREGATE + "\",\"aggregateVersion\":1"
                + ",\"orderId\":\"" + MISSING + "\",\"patientId\":\"" + MISSING + "\""
                + ",\"payloadDigest\":\"" + DIGEST + "\"}";
        ResponseEntity<String> rejected = request("POST", "/api/v1/delivery/consumer-events",
                consume, "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertTrue(rejected.getBody().contains("rejected"));
        assertTrue(as(ORG_A, "worker-a", () -> store
                .receipt(ORG_A, "partner-lab", "lab-partner", "observation.result", "ext-1")).isPresent());
        assertTrue(as(ORG_A, "worker-a", () -> store.receiptById(ORG_A, "ext-1")).isPresent());
        assertTrue(as(ORG_A, "worker-a", () -> store
                .maxVersion(ORG_A, "partner-lab", "lab-partner", "observation.result", AGGREGATE)).isPresent());
        assertTrue(as(ORG_A, "worker-a", () -> store
                .maxVersion(ORG_A, "empty", "lab-partner", "observation.result", AGGREGATE)).isEmpty());
        assertTrue(as(ORG_A, "worker-a", () -> store.patientExists(ORG_A, PATIENT)));
        assertFalse(as(ORG_A, "worker-a", () -> store.patientExists(ORG_A, MISSING)));
        assertFalse(as(ORG_B, "worker-b", () -> store.patientExists(ORG_B, PATIENT)),
                "forced RLS conceals a patient from the other tenant");

        ResponseEntity<String> duplicate = request("POST", "/api/v1/delivery/consumer-events",
                consume, "worker-a", ORG_A);
        assertEquals(HttpStatus.OK, duplicate.getStatusCode(), duplicate.getBody());
        assertEquals(field(rejected.getBody(), "receiptId"), field(duplicate.getBody(), "receiptId"),
                "a repeated external event returns the original receipt, not a second effect");
        assertEquals(field(rejected.getBody(), "outcome"), field(duplicate.getBody(), "outcome"));

        ResponseEntity<String> replay = request("POST", "/api/v1/delivery/replays",
                "{\"eventId\":\"" + eventId + "\",\"reason\":\"synthetic drill\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, replay.getStatusCode(), replay.getBody());

        ExternalLink link = new ExternalLink(java.util.UUID.randomUUID().toString(), ORG_A, "ext-1",
                MISSING, MISSING, AGGREGATE, "admin-a", Instant.parse("2026-11-20T12:00:00Z"));
        as(ORG_A, "admin-a", () -> {
            store.putLink(ORG_A, link);
            return null;
        });
    }
}
