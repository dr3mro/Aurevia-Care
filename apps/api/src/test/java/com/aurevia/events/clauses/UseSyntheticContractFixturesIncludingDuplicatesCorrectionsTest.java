package com.aurevia.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
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

import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.Resolution;
import com.aurevia.events.JdbcDeliveryStore;
import com.aurevia.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-05 clause 8 proof through the normal HTTP delivery entry point: the
 * synthetic partner contract fixture (duplicates, corrections, out-of-order
 * replays and a partial partner outage) is processed by the real service and
 * store, while bad inputs, other tenants, wrong roles and stale preconditions
 * are refused.
 *
 * <p>Each test uses a fresh aggregate id and a scoped external event id. The
 * aggregate isolates the version watermark, while the scoped id isolates the
 * deduplication key, which is based on the external event id rather than the
 * aggregate. Without both, tests could interfere depending on method order.
 * Every identifier and digest is synthetic; no patient data or credential
 * appears in the fixture, the requests or the assertions.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_synthetic_fixtures_it")
@ActiveProfiles("synthetic-auth")
class UseSyntheticContractFixturesIncludingDuplicatesCorrectionsTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String ORDER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String UNKNOWN_ORDER = "dddddddd-dddd-dddd-dddd-dddddddddddd";
    static final String AUTHORIZATION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    static final String SNAPSHOT = "cbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String DISCLOSURE = "dbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String WORK_ITEM = "ebbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_synthetic_fixtures_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final ObjectMapper MAPPER = new ObjectMapper();

    static JsonNode fixtures;

    @Autowired
    JdbcDeliveryStore store;

    @LocalServerPort
    int port;

    RestClient rest;

    /** Per-test suffix so a fixture external id never deduplicates against another test. */
    String externalIdScope;

    /** Recreate the synthetic database from the authoritative migrations and seed bounded roles and facts. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_synthetic_fixtures_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_synthetic_fixtures_it OWNER aurevia_migrate");
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
            // An ORG_B admin is needed so the replay refusal reaches the tenant
            // concealment path instead of stopping at the role check.
            seedUser(statement, ORG_B, "admin-b", "integration_admin");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'worker-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-SYNTHETIC-FIXTURES', 'CLINIC', "
                    + "'Synthetic Fixture Patient', 'synthetic fixture patient', DATE '1990-05-20')");
            // The fixture's order id must exist, otherwise every event expected
            // to be accepted would be rejected as order_not_found.
            statement.execute("INSERT INTO public.exchange_authorization "
                    + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                    + "effective_from, expires_at, active) VALUES ('" + ORG_A + "', '" + AUTHORIZATION
                    + "', 'CBC', '" + ORG_B + "', 'lab review', 60, now() - interval '1 day', "
                    + "now() + interval '1 day', true)");
            statement.execute("INSERT INTO public.exchange_order "
                    + "(organization_id, id, version, patient_id, encounter_ref, order_code, priority, "
                    + "clinical_question, destination_org, responsible_clinician, work_queue, "
                    + "authorization_id, snapshot_id, disclosure_id, work_item_id, created_at) VALUES ('"
                    + ORG_A + "', '" + ORDER + "', 1, '" + PATIENT + "', 'ENC-SYN', 'CBC', 'routine', "
                    + "'synthetic lab review', '" + ORG_B + "', 'dr-a', 'lab-queue', '" + AUTHORIZATION
                    + "', '" + SNAPSHOT + "', '" + DISCLOSURE + "', '" + WORK_ITEM + "', now())");
        }
        fixtures = MAPPER.readTree(Files.readString(
                Path.of("../../contracts/events/fixtures/partner-events.json")));
    }

    /** Drop the synthetic database so each run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_synthetic_fixtures_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic tenant member with a bounded role, mirroring the neighbouring fixtures. */
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

    /** Point the HTTP client at the random port and scope this test's external ids. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        externalIdScope = "-" + UUID.randomUUID();
    }

    /** Send a synthetic request, capturing any status instead of throwing on an error response. */
    private ResponseEntity<String> request(String method, String uri, String body,
                                           String subject, String org) {
        return request(method, uri, body, subject, org, null);
    }

    /** Send a synthetic request with an If-Match precondition, capturing any status. */
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

    /** Read a simple string field from a JSON body, matching the neighbouring fixtures. */
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

    /** Select one named scenario from the authoritative synthetic contract fixture. */
    private static JsonNode scenario(String name) {
        for (JsonNode candidate : fixtures.get("scenarios")) {
            if (name.equals(candidate.get("name").asString())) {
                return candidate;
            }
        }
        throw new AssertionError("fixture scenario not found: " + name);
    }

    /** Select one event inside a scenario. */
    private static JsonNode event(JsonNode scenario, int index) {
        return scenario.get("events").get(index);
    }

    /** Map the fixture's single-character digest placeholders to distinct synthetic 64-hex digests. */
    private static String digest(String token) {
        return token.repeat(64);
    }

    /** Scope one fixture external id to this test so deduplication cannot cross tests. */
    private String externalId(String fixtureId) {
        return fixtureId + externalIdScope;
    }

    /** Build a consumer-events body from a fixture event, bounding it to one test's aggregate. */
    private String consumeBody(JsonNode event, String aggregateId) {
        String orderId = event.has("orderId")
                ? event.get("orderId").asString() : fixtures.get("orderId").asString();
        return "{\"externalEventId\":\"" + externalId(event.get("externalEventId").asString()) + "\""
                + ",\"sender\":\"" + fixtures.get("sender").asString() + "\""
                + ",\"eventType\":\"" + fixtures.get("eventType").asString() + "\""
                + ",\"namespace\":\"" + fixtures.get("namespace").asString() + "\""
                + ",\"aggregateId\":\"" + aggregateId + "\""
                + ",\"aggregateVersion\":" + event.get("aggregateVersion").asInt()
                + ",\"orderId\":\"" + orderId + "\""
                + ",\"patientId\":\"" + fixtures.get("patientId").asString() + "\""
                + ",\"payloadDigest\":\"" + digest(event.get("payloadDigest").asString()) + "\"}";
    }

    /** Consume one fixture event through the HTTP entry point and assert a successful response. */
    private ResponseEntity<String> consume(JsonNode event, String aggregateId,
                                           String subject, String org) {
        ResponseEntity<String> response = request("POST", "/api/v1/delivery/consumer-events",
                consumeBody(event, aggregateId), subject, org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return response;
    }

    /** Read the processing outcome of a consume response. */
    private static String outcome(ResponseEntity<String> response) {
        return field(response.getBody(), "outcome");
    }

    /** Publish one outbox event built from fixture fields through the HTTP entry point. */
    private String publish(String key, String aggregateId, JsonNode event) {
        String body = "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateVersion\":1"
                + ",\"sender\":\"" + fixtures.get("sender").asString() + "\""
                + ",\"eventType\":\"" + fixtures.get("eventType").asString() + "\""
                + ",\"namespace\":\"" + fixtures.get("namespace").asString() + "\""
                + ",\"payloadDigest\":\"" + digest(event.get("payloadDigest").asString()) + "\""
                + ",\"idempotencyKey\":\"" + key + "\"}";
        ResponseEntity<String> response = request("POST", "/api/v1/delivery/events",
                body, "worker-a", ORG_A);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "eventId");
    }

    /** Count stored receipts for one aggregate, proving the fixture's expected receipt count. */
    private int receiptCount(String aggregateId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.delivery_receipt "
                            + "WHERE aggregate_id = CAST('" + aggregateId + "' AS uuid)")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DELIVERY", "synthetic-fixtures-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void duplicateDeliveryReturnsOneReceipt() throws Exception {
        String aggregate = UUID.randomUUID().toString();
        JsonNode scenario = scenario("duplicate-delivery");
        JsonNode firstEvent = event(scenario, 0);

        ResponseEntity<String> first = consume(firstEvent, aggregate, "worker-a", ORG_A);
        assertEquals(firstEvent.get("expectedOutcome").asString(), outcome(first));
        String receiptId = field(first.getBody(), "receiptId");

        ResponseEntity<String> second = consume(event(scenario, 1), aggregate, "worker-a", ORG_A);
        assertEquals(receiptId, field(second.getBody(), "receiptId"),
                "a repeated partner event returns the original receipt, not a second effect");
        assertEquals(outcome(first), outcome(second));
        assertFalse(receiptId.isEmpty());
        assertEquals(scenario.get("expectedReceiptCount").asInt(), receiptCount(aggregate));
        assertTrue(as(ORG_A, "auditor-a", () -> store.auditEvents(ORG_A)).stream()
                .anyMatch(audit -> externalId(firstEvent.get("externalEventId").asString())
                        .equals(audit.resourceId())
                        && audit.action() == AuditAction.CONSUME && audit.outcome() == Outcome.DUPLICATE),
                "the repeated event must be recorded as a duplicate even though the original receipt is returned");
    }

    @Test
    void laterCorrectionAcceptedEarlierVersionHeld() throws Exception {
        String aggregate = UUID.randomUUID().toString();
        JsonNode correction = scenario("correction-after-final");
        JsonNode replay = scenario("out-of-order-replay");

        assertEquals(event(correction, 0).get("expectedOutcome").asString(),
                outcome(consume(event(correction, 0), aggregate, "worker-a", ORG_A)));
        assertEquals(event(correction, 1).get("expectedOutcome").asString(),
                outcome(consume(event(correction, 1), aggregate, "worker-a", ORG_A)));
        assertEquals(event(replay, 0).get("expectedOutcome").asString(),
                outcome(consume(event(replay, 0), aggregate, "worker-a", ORG_A)));
        assertEquals(event(replay, 1).get("expectedOutcome").asString(),
                outcome(consume(event(replay, 1), aggregate, "worker-a", ORG_A)));
        assertEquals(correction.get("expectedReceiptCount").asInt()
                + replay.get("expectedReceiptCount").asInt(), receiptCount(aggregate));
    }

    @Test
    void partialOutageRetriesBackoffDeadLettersThenReviewRequeues() {
        String aggregate = UUID.randomUUID().toString();
        JsonNode scenario = scenario("partial-partner-outage");
        JsonNode outage = event(scenario, 0);
        JsonNode delivery = outage.get("delivery");

        assertEquals(outage.get("expectedOutcome").asString(),
                outcome(consume(outage, aggregate, "worker-a", ORG_A)));

        String id = publish("partial-outage-" + UUID.randomUUID(), aggregate, outage);
        int version = 1;
        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id + "/sent",
                null, "operator-a", ORG_A, String.valueOf(version)).getStatusCode());
        version++;
        int attempts = delivery.get("attempts").asInt();
        Instant previousBackoff = null;
        for (int cycle = 1; cycle <= attempts; cycle++) {
            assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id
                    + "/failed?reason=channel-unavailable", null, "operator-a", ORG_A,
                    String.valueOf(version)).getStatusCode(), "failed send " + cycle);
            version++;
            ResponseEntity<String> retry = request("POST", "/api/v1/delivery/events/" + id + "/retry",
                    null, "operator-a", ORG_A, String.valueOf(version));
            assertEquals(HttpStatus.OK, retry.getStatusCode(), retry.getBody());
            version++;
            OutboxEvent requeued = as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow());
            assertEquals(DeliveryState.PENDING, requeued.state());
            assertEquals(cycle, requeued.attempt());
            assertNotNull(requeued.nextAttemptAt(), "a bounded retry records its next dispatch instant");
            if (previousBackoff != null) {
                assertTrue(requeued.nextAttemptAt().isAfter(previousBackoff),
                        "backoff grows with each retry");
            }
            previousBackoff = requeued.nextAttemptAt();
        }
        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id
                + "/failed?reason=channel-unavailable", null, "operator-a", ORG_A,
                String.valueOf(version)).getStatusCode());
        version++;
        ResponseEntity<String> exhausted = request("POST", "/api/v1/delivery/events/" + id + "/retry",
                null, "operator-a", ORG_A, String.valueOf(version));
        assertEquals(HttpStatus.OK, exhausted.getStatusCode(), exhausted.getBody());
        assertEquals(DeliveryState.DEAD_LETTERED,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state());
        DeadLetterEntry entry = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertEquals("retry budget exhausted", entry.reason());
        assertEquals(attempts, entry.attempt());
        assertNull(entry.reviewer(), "a dead-lettered event is unreviewed until a separate decision");
        assertEquals("dead_lettered", delivery.get("finalState").asString());
        assertTrue(delivery.get("reviewRequired").asBoolean());

        ResponseEntity<String> reviewed = request("POST", "/api/v1/delivery/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"" + delivery.get("resolution").asString()
                        + "\",\"reason\":\"synthetic review\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        DeadLetterEntry reviewedEntry = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertEquals("admin-a", reviewedEntry.reviewer());
        assertEquals(Resolution.REQUEUED, reviewedEntry.resolution());
        OutboxEvent requeued = as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow());
        assertEquals(DeliveryState.PENDING, requeued.state());
        assertEquals(0, requeued.attempt(), "review restarts the retry budget");
    }

    @Test
    void rejectsEventWithUnconfirmedOrderOrPatientAndRefusesLink() throws Exception {
        String aggregate = UUID.randomUUID().toString();
        JsonNode scenario = scenario("unconfirmed-identifiers");
        JsonNode unconfirmed = event(scenario, 0);

        ResponseEntity<String> rejected = consume(unconfirmed, aggregate, "worker-a", ORG_A);
        assertEquals(unconfirmed.get("expectedOutcome").asString(), outcome(rejected));
        assertEquals("order_not_found", field(rejected.getBody(), "detail"));
        assertEquals(scenario.get("expectedReceiptCount").asInt(), receiptCount(aggregate));
        String scopedUnconfirmedId = externalId(unconfirmed.get("externalEventId").asString());
        assertTrue(as(ORG_A, "worker-a", () -> store.receipt(ORG_A, fixtures.get("namespace").asString(),
                fixtures.get("sender").asString(), fixtures.get("eventType").asString(),
                scopedUnconfirmedId)).isPresent());

        // LINK_EXTERNAL is authorized for integration_admin, so use admin-a to
        // reach the confirmation check rather than stopping at a role denial.
        ResponseEntity<String> link = request("POST", "/api/v1/delivery/external-links",
                "{\"externalEventId\":\"" + scopedUnconfirmedId
                        + "\",\"orderId\":\"" + UNKNOWN_ORDER + "\",\"patientId\":\""
                        + fixtures.get("patientId").asString() + "\",\"targetAggregateId\":\""
                        + aggregate + "\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, link.getStatusCode(), link.getBody());
        assertFalse(link.getBody().contains("linkId"), "a rejected receipt must not produce a link");
    }

    @Test
    void otherTenantCannotReadOrReplayDeliveryFacts() {
        String aggregate = UUID.randomUUID().toString();
        JsonNode accepted = event(scenario("correction-after-final"), 0);
        consume(accepted, aggregate, "worker-a", ORG_A);
        String id = publish("tenant-" + UUID.randomUUID(), aggregate, accepted);
        String scopedExternalId = externalId(accepted.get("externalEventId").asString());
        String namespace = fixtures.get("namespace").asString();
        String sender = fixtures.get("sender").asString();
        String eventType = fixtures.get("eventType").asString();

        assertTrue(as(ORG_B, "worker-b", () -> store.outboxEvent(ORG_B, id)).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store.receipt(ORG_B, namespace, sender, eventType,
                scopedExternalId)).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store.receiptById(ORG_B, scopedExternalId)).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store.maxVersion(ORG_B, namespace, sender, eventType, aggregate)).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store.deadLetters(ORG_B)).isEmpty());
        assertTrue(as(ORG_B, "worker-b", () -> store.auditEvents(ORG_B)).isEmpty());

        assertEquals(HttpStatus.NOT_FOUND, request("POST", "/api/v1/delivery/events/" + id + "/sent",
                null, "worker-b", ORG_B, "1").getStatusCode());
        ResponseEntity<String> replay = request("POST", "/api/v1/delivery/replays",
                "{\"eventId\":\"" + id + "\",\"reason\":\"synthetic drill\"}", "admin-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, replay.getStatusCode(), replay.getBody());
        assertFalse(replay.getBody().contains("replayId"), "a concealed event must not yield replay data");
    }

    @Test
    void roleWithoutConsumeOrReplayIsDenied() {
        String aggregate = UUID.randomUUID().toString();
        JsonNode accepted = event(scenario("correction-after-final"), 0);
        String id = publish("role-" + UUID.randomUUID(), aggregate, accepted);

        ResponseEntity<String> deniedConsume = request("POST", "/api/v1/delivery/consumer-events",
                consumeBody(accepted, aggregate), "auditor-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, deniedConsume.getStatusCode(), deniedConsume.getBody());
        assertFalse(deniedConsume.getBody().contains("receiptId"), "a denied consume must not disclose a receipt");

        ResponseEntity<String> deniedReplay = request("POST", "/api/v1/delivery/replays",
                "{\"eventId\":\"" + id + "\",\"reason\":\"synthetic drill\"}", "auditor-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, deniedReplay.getStatusCode(), deniedReplay.getBody());
        assertFalse(deniedReplay.getBody().contains("replayId"), "a denied replay must not disclose a fact");
    }

    @Test
    void staleIfMatchIsRejected() {
        String aggregate = UUID.randomUUID().toString();
        JsonNode accepted = event(scenario("duplicate-delivery"), 0);
        String id = publish("stale-" + UUID.randomUUID(), aggregate, accepted);

        assertEquals(HttpStatus.OK, request("POST", "/api/v1/delivery/events/" + id + "/sent",
                null, "operator-a", ORG_A, "1").getStatusCode());
        ResponseEntity<String> stale = request("POST", "/api/v1/delivery/events/" + id + "/sent",
                null, "operator-a", ORG_A, "1");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals(DeliveryState.SENT,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state(),
                "a stale transition must not advance the event again");
    }
}
