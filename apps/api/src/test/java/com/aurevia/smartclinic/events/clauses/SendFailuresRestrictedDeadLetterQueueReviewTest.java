package com.aurevia.smartclinic.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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

import com.aurevia.smartclinic.events.JdbcDeliveryStore;
import com.aurevia.smartclinic.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.smartclinic.events.DurableEventContract.DeliveryState;
import com.aurevia.smartclinic.events.DurableEventContract.OutboxEvent;
import com.aurevia.smartclinic.events.DurableEventContract.Resolution;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-05 clause 5 (API evidence): send failures to a restricted dead-letter
 * queue and review workflow.
 *
 * <p>The positive tests drive the real HTTP entry point against PostgreSQL and
 * assert that an exhausted retry budget moves an event into a tenant-scoped,
 * restricted dead-letter ledger, and that only an authorized administrator can
 * resolve or requeue the entry with a reasoned, audited decision. The negative
 * tests assert the restrictions refuse what they must: a non-reviewer, another
 * tenant, a repeated review, a non-failed event, a stale version and invalid
 * review input. Every refusal asserts both the machine-readable error code and
 * that the stored dead-letter entry and event remain unchanged.</p>
 *
 * <p>The restricted queue here is the forced-RLS, trigger-guarded database
 * ledger created by the Flyway migration, not an external message broker. All
 * identifiers and digests are synthetic; no clinical payload, credential or
 * real patient data appears in a fixture or an audit fact. Server instants come
 * from the application clock, so no test compares a wall-clock string.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_dlq_review_it")
@ActiveProfiles("synthetic-auth")
class SendFailuresRestrictedDeadLetterQueueReviewTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String BASE = "/api/v1/delivery";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DIGEST = "a".repeat(64);
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_dlq_review_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    JdbcDeliveryStore store;

    @LocalServerPort
    int port;

    RestClient rest;

    /** Create the isolated database, apply the production migrations and seed synthetic members. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_dlq_review_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_dlq_review_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "worker-a", "worker");
            seedUser(statement, ORG_A, "operator-a", "operator");
            seedUser(statement, ORG_A, "admin-a", "integration_admin");
            seedUser(statement, ORG_A, "admin2-a", "tenant_admin");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedUser(statement, ORG_B, "worker-b", "worker");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    /** Drop the isolated database so a rerun starts from a clean ledger. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_dlq_review_it WITH (FORCE)");
        }
    }

    /** Seed one active organization member with a single role for the synthetic fixtures. */
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

    /** Point the client at the randomly assigned server port. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** An exhausted retry budget restricts the event to the reviewed dead-letter queue. */
    @Test
    void retryBudgetExhaustionRestrictsEventToReviewedDeadLetterQueue() {
        String id = publish("exhaust-1");
        assertEquals(HttpStatus.OK, post("/events/" + id + "/sent", null, "worker-a", ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/failed?reason=channel-unavailable",
                null, "worker-a", ORG_A, "2").getStatusCode());
        ResponseEntity<String> firstRetry = post("/events/" + id + "/retry", null, "worker-a", ORG_A, "3");
        assertEquals(HttpStatus.OK, firstRetry.getStatusCode(), firstRetry.getBody());
        assertEquals("null", field(firstRetry.getBody(), "deadLetter"),
                "a retry below the budget must requeue, not dead-letter");
        assertTrue(firstRetry.getBody().contains("\"state\":\"pending\""), firstRetry.getBody());
        OutboxEvent requeued = as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow());
        assertEquals(DeliveryState.PENDING, requeued.state());
        assertEquals(1, requeued.attempt());
        assertNotNull(requeued.nextAttemptAt(), "a bounded retry records its next dispatch instant");

        assertEquals(HttpStatus.OK, post("/events/" + id + "/failed?reason=channel-unavailable",
                null, "worker-a", ORG_A, "4").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/retry", null, "worker-a", ORG_A, "5").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/failed?reason=channel-unavailable",
                null, "worker-a", ORG_A, "6").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/retry", null, "worker-a", ORG_A, "7").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/failed?reason=channel-unavailable",
                null, "worker-a", ORG_A, "8").getStatusCode());

        ResponseEntity<String> exhausted = post("/events/" + id + "/retry", null, "worker-a", ORG_A, "9");
        assertEquals(HttpStatus.OK, exhausted.getStatusCode(), exhausted.getBody());
        assertEquals("null", field(exhausted.getBody(), "event"),
                "an exhausted retry returns no requeued event");
        // The dead-letter object is the only populated branch of the RetryResult,
        // so read its flat fields from the whole response body.
        String deadLetter = exhausted.getBody();
        assertEquals(id, field(deadLetter, "eventId"));
        assertEquals("retry budget exhausted", field(deadLetter, "reason"));
        assertEquals("3", field(deadLetter, "attempt"));
        assertTrue(deadLetter.contains("\"reviewer\":null"), "a dead letter starts unreviewed");
        assertTrue(deadLetter.contains("\"resolution\":null"), "a dead letter starts unresolved");
        assertEquals(DeliveryState.DEAD_LETTERED,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state());

        ResponseEntity<String> queue = get("/dead-letters", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertTrue(queue.getBody().contains(id), "the administrator can read the restricted queue");
        assertTrue(queue.getBody().contains("\"reviewer\":null"), "the queue shows the entry unreviewed");
        String auditAfterRead = get("/audit", "auditor-a", ORG_A).getBody();
        assertTrue(auditAfterRead.contains("\"action\":\"dead_letter\""),
                "entering the queue is an audited action");
        assertTrue(auditAfterRead.contains("\"action\":\"review\""),
                "the restricted queue read itself is audited");
        assertTrue(auditAfterRead.contains("\"resourceId\":\"DELIVERY\""),
                "the read audit names the restricted resource");

        ResponseEntity<String> reviewed = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"requeued\""
                        + ",\"reason\":\"synthetic requeue after outage\"}", "admin-a", ORG_A, null);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertEquals("admin-a", field(reviewed.getBody(), "reviewer"));
        assertEquals("requeued", field(reviewed.getBody(), "resolution"));
        assertEquals("synthetic requeue after outage", field(reviewed.getBody(), "reviewReason"));
        DeadLetterEntry stored = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertEquals("admin-a", stored.reviewer());
        assertEquals(Resolution.REQUEUED, stored.resolution());
        assertNotNull(stored.reviewedAt(), "the review instant is persisted");
        assertEquals(DeliveryState.PENDING,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state(),
                "a requeue review resets the event to a fresh retry budget");
        assertTrue(get("/audit", "auditor-a", ORG_A).getBody().contains("\"action\":\"review\""),
                "the review is an audited action");
    }

    /** A tenant administrator may resolve an entry without requeuing the event. */
    @Test
    void resolvedReviewKeepsEventInTheRestrictedQueue() {
        String id = deadLetteredEvent("resolved-1");
        ResponseEntity<String> reviewed = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"resolved\""
                        + ",\"reason\":\"synthetic resolution\"}", "admin2-a", ORG_A, null);
        assertEquals(HttpStatus.OK, reviewed.getStatusCode(), reviewed.getBody());
        assertEquals("admin2-a", field(reviewed.getBody(), "reviewer"));
        assertEquals("resolved", field(reviewed.getBody(), "resolution"));
        assertEquals("synthetic resolution", field(reviewed.getBody(), "reviewReason"));
        assertEquals(Resolution.RESOLVED,
                as(ORG_A, "admin2-a", () -> store.deadLetter(ORG_A, id).orElseThrow()).resolution());
        assertEquals(DeliveryState.DEAD_LETTERED,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state(),
                "a resolved entry stays out of the delivery stream");
    }

    /** A delivery worker may not read the restricted queue nor review an entry. */
    @Test
    void workerCannotReadOrReviewRestrictedQueue() {
        String id = deadLetteredEvent("worker-1");
        ResponseEntity<String> read = get("/dead-letters", "worker-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, read.getStatusCode(), read.getBody());
        assertEquals("FORBIDDEN", field(read.getBody(), "code"));
        ResponseEntity<String> review = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"resolved\",\"reason\":\"synthetic attempt\"}",
                "worker-a", ORG_A, null);
        assertEquals(HttpStatus.FORBIDDEN, review.getStatusCode(), review.getBody());
        assertEquals("FORBIDDEN", field(review.getBody(), "code"));
        DeadLetterEntry unchanged = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertNull(unchanged.reviewer(), "a denied review must not change the entry");
        assertNull(unchanged.resolution());
    }

    /** An administrator of another tenant cannot see or review the concealed entry. */
    @Test
    void otherTenantCannotSeeOrReviewDeadLetter() {
        String id = deadLetteredEvent("tenant-1");
        ResponseEntity<String> queue = get("/dead-letters", "admin-b", ORG_B);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertFalse(queue.getBody().contains(id), "forced RLS conceals another tenant's queue");
        ResponseEntity<String> review = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"requeued\",\"reason\":\"synthetic cross-tenant\"}",
                "admin-b", ORG_B, null);
        assertEquals(HttpStatus.NOT_FOUND, review.getStatusCode(), review.getBody());
        assertEquals("NOT_FOUND", field(review.getBody(), "code"));
        DeadLetterEntry unchanged = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertNull(unchanged.reviewer(), "a cross-tenant review must not change the owner's entry");
        assertNull(unchanged.resolution());
    }

    /** A single dead-letter entry can be reviewed only once. */
    @Test
    void alreadyReviewedEntryCannotBeReviewedAgain() {
        String id = deadLetteredEvent("repeat-1");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"resolved\",\"reason\":\"first review\"}",
                "admin-a", ORG_A, null).getStatusCode());
        ResponseEntity<String> again = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"requeued\",\"reason\":\"second review\"}",
                "admin2-a", ORG_A, null);
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode(), again.getBody());
        assertEquals("ALREADY_REVIEWED", field(again.getBody(), "code"));
        DeadLetterEntry unchanged = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertEquals("admin-a", unchanged.reviewer(), "the first reviewer remains authoritative");
        assertEquals(Resolution.RESOLVED, unchanged.resolution());
        assertEquals("first review", unchanged.reviewReason());
    }

    /** Only a failed event may be moved into the restricted queue. */
    @Test
    void deadLetterRefusesNonFailedEvent() {
        String id = publish("nonfailed-1");
        ResponseEntity<String> dead = post("/events/" + id + "/dead-letter?reason=not-yet-failed",
                null, "operator-a", ORG_A, null);
        assertEquals(HttpStatus.CONFLICT, dead.getStatusCode(), dead.getBody());
        assertEquals("NOT_FAILED", field(dead.getBody(), "code"));
        assertEquals(DeliveryState.PENDING,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state());
        assertTrue(as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id)).isEmpty(),
                "a non-failed event must not create a dead-letter entry");
    }

    /** A stale retry precondition cannot move the event into the queue. */
    @Test
    void staleVersionCannotRetryIntoQueue() {
        String id = publish("stale-1");
        assertEquals(HttpStatus.OK, post("/events/" + id + "/sent", null, "operator-a", ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/failed?reason=channel-unavailable",
                null, "operator-a", ORG_A, "2").getStatusCode());
        ResponseEntity<String> stale = post("/events/" + id + "/retry", null, "operator-a", ORG_A, "1");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(DeliveryState.FAILED,
                as(ORG_A, "worker-a", () -> store.outboxEvent(ORG_A, id).orElseThrow()).state());
        assertTrue(as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id)).isEmpty(),
                "a stale retry must not create a dead-letter entry");
    }

    /** An unknown resolution or a blank reason is refused without changing the entry. */
    @Test
    void unknownResolutionOrBlankReasonIsRefused() {
        String id = deadLetteredEvent("invalid-1");
        ResponseEntity<String> unknown = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"discard\",\"reason\":\"synthetic attempt\"}",
                "admin-a", ORG_A, null);
        assertEquals(422, unknown.getStatusCode().value(), unknown.getBody());
        assertEquals("INVALID_RESOLUTION", field(unknown.getBody(), "code"));
        ResponseEntity<String> blank = request(HttpMethod.POST, "/dead-letters/review",
                "{\"eventId\":\"" + id + "\",\"resolution\":\"requeued\",\"reason\":\"   \"}",
                "admin-a", ORG_A, null);
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertEquals("INVALID_REASON", field(blank.getBody(), "code"));
        DeadLetterEntry unchanged = as(ORG_A, "admin-a", () -> store.deadLetter(ORG_A, id).orElseThrow());
        assertNull(unchanged.reviewer(), "an invalid review must not change the entry");
        assertNull(unchanged.resolution());
    }

    /** Publish one synthetic event and return its immutable id. */
    private String publish(String key) {
        String body = "{\"aggregateId\":\"" + AGGREGATE + "\",\"aggregateVersion\":1"
                + ",\"sender\":\"lab-partner\",\"eventType\":\"observation.result\""
                + ",\"namespace\":\"partner-lab\",\"payloadDigest\":\"" + DIGEST + "\""
                + ",\"idempotencyKey\":\"" + key + "\"}";
        ResponseEntity<String> response = post("/events", body, "worker-a", ORG_A, null);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "eventId");
    }

    /** Drive one event to a failed state and move it into the restricted queue. */
    private String deadLetteredEvent(String key) {
        String id = publish(key);
        assertEquals(HttpStatus.OK, post("/events/" + id + "/sent", null, "worker-a", ORG_A, "1").getStatusCode());
        assertEquals(HttpStatus.OK, post("/events/" + id + "/failed?reason=channel-unavailable",
                null, "worker-a", ORG_A, "2").getStatusCode());
        ResponseEntity<String> dead = post("/events/" + id + "/dead-letter?reason=channel-unavailable",
                null, "operator-a", ORG_A, null);
        assertEquals(HttpStatus.OK, dead.getStatusCode(), dead.getBody());
        assertEquals(id, field(dead.getBody(), "eventId"));
        return id;
    }

    /** Send a command as a synthetic subject in an active organization, capturing any status. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        if (ifMatch != null) {
            headers.set("If-Match", "\"" + ifMatch + "\"");
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(BASE + uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { }).toEntity(String.class);
    }

    /** Send a POST command as a synthetic subject in an active organization. */
    private ResponseEntity<String> post(String uri, String body, String subject, String org, String ifMatch) {
        return request(HttpMethod.POST, uri, body, subject, org, ifMatch);
    }

    /** Read a resource as a synthetic subject in an active organization. */
    private ResponseEntity<String> get(String uri, String subject, String org) {
        return request(HttpMethod.GET, uri, null, subject, org, null);
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DELIVERY", "dlq-review-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Read a scalar field by its first key occurrence, including inside a nested response object. */
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
}
