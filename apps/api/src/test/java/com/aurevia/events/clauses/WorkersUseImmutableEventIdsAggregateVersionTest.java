package com.aurevia.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-05 clause 2 acceptance: a delivery worker cannot change an event's
 * identity, and every state change requires a matching per-aggregate version.
 *
 * <p>Commands go through the real {@code /api/v1/delivery} HTTP entry point on a
 * synthetic tenant. The immutable id and advancing version are asserted on the
 * HTTP response and on the RLS-bounded stored snapshot; negative cases prove the
 * adapter refuses a stale version, a role without delivery permission and a
 * caller in another tenant. All identifiers and digests are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_delivery_version_it")
@ActiveProfiles("synthetic-auth")
class WorkersUseImmutableEventIdsAggregateVersionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DIGEST = "a".repeat(64);
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_delivery_version_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DeliveryStore deliveryStore;

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS orevia_delivery_version_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_delivery_version_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "worker-a", "worker", "active");
            seedUser(statement, ORG_A, "operator-a", "operator", "active");
            seedUser(statement, ORG_A, "clinician-a", "clinician", "active");
            seedUser(statement, ORG_A, "inactive-a", "worker", "revoked");
            seedUser(statement, ORG_B, "worker-b", "worker", "active");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_delivery_version_it WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String org, String subject, String role,
                                 String membershipStatus) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + membershipStatus + "' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
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
     * Bind the active tenant on the calling thread for a direct store read, as
     * the HTTP interceptor does for the command itself.
     */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DELIVERY", "delivery-version-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private ResponseEntity<String> publish(String key, String subject, String org) {
        String body = "{\"aggregateId\":\"" + AGGREGATE + "\",\"aggregateVersion\":1"
                + ",\"sender\":\"lab-partner\",\"eventType\":\"observation.result\""
                + ",\"namespace\":\"partner-lab\",\"payloadDigest\":\"" + DIGEST + "\""
                + ",\"idempotencyKey\":\"" + key + "\"}";
        return request("POST", "/api/v1/delivery/events", body, subject, org, null);
    }

    private OutboxEvent stored(String org, String subject, String eventId) {
        return as(org, subject, () -> deliveryStore.outboxEvent(org, eventId).orElseThrow());
    }

    @Test
    void publishedEventKeepsImmutableIdAndAdvancesVersionOnEveryTransition() {
        ResponseEntity<String> created = publish("immutable-id-1", "worker-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String eventId = field(created.getBody(), "eventId");
        assertNotNull(eventId);
        assertEquals("1", field(created.getBody(), "aggregateVersion"));
        assertEquals("pending", field(created.getBody(), "state"));
        assertNotNull(created.getHeaders().getLocation(), "Location header");
        assertTrue(created.getHeaders().getLocation().toString().endsWith("/delivery/events/" + eventId));

        ResponseEntity<String> sent = request("POST", "/api/v1/delivery/events/" + eventId + "/sent",
                null, "operator-a", ORG_A, "1");
        assertEquals(HttpStatus.OK, sent.getStatusCode(), sent.getBody());
        assertEquals(eventId, field(sent.getBody(), "eventId"));
        assertEquals("2", field(sent.getBody(), "aggregateVersion"));
        assertEquals("sent", field(sent.getBody(), "state"));

        ResponseEntity<String> delivered = request("POST", "/api/v1/delivery/events/" + eventId + "/delivered",
                null, "operator-a", ORG_A, "2");
        assertEquals(HttpStatus.OK, delivered.getStatusCode(), delivered.getBody());
        assertEquals(eventId, field(delivered.getBody(), "eventId"));
        assertEquals("3", field(delivered.getBody(), "aggregateVersion"));
        assertEquals("delivered", field(delivered.getBody(), "state"));

        ResponseEntity<String> failed = request("POST", "/api/v1/delivery/events/" + eventId + "/failed?reason=channel-timeout",
                null, "operator-a", ORG_A, "3");
        assertEquals(HttpStatus.OK, failed.getStatusCode(), failed.getBody());
        assertEquals(eventId, field(failed.getBody(), "eventId"));
        assertEquals("4", field(failed.getBody(), "aggregateVersion"));

        ResponseEntity<String> retried = request("POST", "/api/v1/delivery/events/" + eventId + "/retry",
                null, "operator-a", ORG_A, "4");
        assertEquals(HttpStatus.OK, retried.getStatusCode(), retried.getBody());
        assertTrue(retried.getBody().contains("\"event\":{"), retried.getBody());
        assertEquals(eventId, field(retried.getBody(), "eventId"));
        assertEquals("5", field(retried.getBody(), "aggregateVersion"));
        assertEquals("pending", field(retried.getBody(), "state"));

        OutboxEvent persisted = stored(ORG_A, "worker-a", eventId);
        assertEquals(eventId, persisted.eventId(), "the immutable id never changes across every state change");
        assertEquals(5, persisted.aggregateVersion(), "every state change advances the version by exactly one");
        assertEquals(DeliveryState.PENDING, persisted.state());
    }

    @Test
    void transitionWithStaleIfMatchIsRejectedWithoutChangingState() {
        String eventId = field(publish("stale-version-1", "worker-a", ORG_A).getBody(), "eventId");
        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + eventId + "/sent",
                null, "operator-a", ORG_A, "1").getStatusCode());

        ResponseEntity<String> stale = request("POST", "/api/v1/delivery/events/" + eventId + "/delivered",
                null, "operator-a", ORG_A, "1");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertTrue(stale.getBody().contains("STALE_VERSION"), stale.getBody());

        OutboxEvent persisted = stored(ORG_A, "worker-a", eventId);
        assertEquals(eventId, persisted.eventId());
        assertEquals(2, persisted.aggregateVersion(), "a rejected transition does not advance the version");
        assertEquals(DeliveryState.SENT, persisted.state(), "a rejected transition does not change state");

        List<DeliveryAuditEvent> audits = as(ORG_A, "worker-a", () -> deliveryStore.auditEvents(ORG_A));
        assertEquals(Outcome.STALE, audits.get(audits.size() - 1).outcome());
    }

    @Test
    void publishOrTransitionWithoutDeliveryPermissionIsForbidden() {
        ResponseEntity<String> clinicianPublish = publish("forbidden-role-1", "clinician-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, clinicianPublish.getStatusCode(), clinicianPublish.getBody());
        assertTrue(clinicianPublish.getBody().contains("FORBIDDEN"));
        assertTrue(as(ORG_A, "worker-a", () -> deliveryStore
                .byIdempotencyKey(ORG_A, "lab-partner", "observation.result", "forbidden-role-1")).isEmpty());

        ResponseEntity<String> inactivePublish = publish("forbidden-role-2", "inactive-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, inactivePublish.getStatusCode(), inactivePublish.getBody());
        assertTrue(as(ORG_A, "worker-a", () -> deliveryStore
                .byIdempotencyKey(ORG_A, "lab-partner", "observation.result", "forbidden-role-2")).isEmpty());

        String eventId = field(publish("forbidden-role-3", "worker-a", ORG_A).getBody(), "eventId");
        ResponseEntity<String> clinicianTransition = request("POST", "/api/v1/delivery/events/" + eventId + "/sent",
                null, "clinician-a", ORG_A, "1");
        assertEquals(HttpStatus.FORBIDDEN, clinicianTransition.getStatusCode(), clinicianTransition.getBody());
        OutboxEvent persisted = stored(ORG_A, "worker-a", eventId);
        assertEquals(1, persisted.aggregateVersion());
        assertEquals(DeliveryState.PENDING, persisted.state());
    }

    @Test
    void anotherTenantCannotReadOrTransitionTheEvent() {
        String eventId = field(publish("tenant-1", "worker-a", ORG_A).getBody(), "eventId");

        ResponseEntity<String> crossTenant = request("POST", "/api/v1/delivery/events/" + eventId + "/sent",
                null, "worker-b", ORG_B, "1");
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.getStatusCode(), crossTenant.getBody());
        assertTrue(crossTenant.getBody().contains("NOT_FOUND"));

        assertTrue(as(ORG_B, "worker-b", () -> deliveryStore.outboxEvent(ORG_B, eventId)).isEmpty());
        OutboxEvent persisted = stored(ORG_A, "worker-a", eventId);
        assertEquals(eventId, persisted.eventId());
        assertEquals(1, persisted.aggregateVersion());
        assertEquals(DeliveryState.PENDING, persisted.state());
    }

    @Test
    void malformedIfMatchIsRejectedBeforeAnyWrite() {
        String eventId = field(publish("bad-if-match-1", "worker-a", ORG_A).getBody(), "eventId");

        ResponseEntity<String> malformed = request("POST", "/api/v1/delivery/events/" + eventId + "/sent",
                null, "operator-a", ORG_A, "not-a-version");
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertTrue(malformed.getBody().contains("INVALID_IF_MATCH"), malformed.getBody());

        OutboxEvent persisted = stored(ORG_A, "worker-a", eventId);
        assertEquals(1, persisted.aggregateVersion());
        assertEquals(DeliveryState.PENDING, persisted.state());
    }
}
