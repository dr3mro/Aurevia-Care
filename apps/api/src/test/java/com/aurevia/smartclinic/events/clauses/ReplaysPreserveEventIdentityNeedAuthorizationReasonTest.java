package com.aurevia.smartclinic.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * ARC-05 clause 6 acceptance through the real HTTP entry point on PostgreSQL.
 *
 * <p>A replay mints a fresh replay fact that still names the original immutable
 * event id, carries the authorizer and reason, and is refused for the wrong
 * role, a peer tenant, a missing reason and an unknown id. Reads of
 * {@code public.delivery_replay}, {@code public.delivery_outbox} and
 * {@code public.delivery_audit} bind the owner tenant explicitly, so the
 * persisted facts are checked under the same forced RLS the application uses.
 * All identifiers, digests and reasons are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_replay_identity_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ReplaysPreserveEventIdentityNeedAuthorizationReasonTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_replay_identity_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String WORKER_A = "worker-a";
    private static final String ADMIN_A = "admin-a";
    private static final String ADMIN_B = "admin-b";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DIGEST = "a".repeat(64);

    private RestClient rest;

    /** A replay fact as persisted in the tenant-owned replay table. */
    private record ReplayRow(String organizationId, String originalEventId,
                             String reason, String authorizedBy) {
    }

    /** The immutable outbox snapshot checked before and after a replay. */
    private record OutboxRow(String state, int version, String idempotencyKey, String actorSubject) {
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
            seedUser(statement, ORG_A, WORKER_A, "worker");
            seedUser(statement, ORG_A, ADMIN_A, "integration_admin");
            // The peer-org actor must hold an admin role, or the case would be
            // refused by the role gate (403) before the tenant concealment (404).
            seedUser(statement, ORG_B, ADMIN_B, "tenant_admin");
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
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Issue a request through the real REST adapter, capturing error bodies
     * instead of throwing so negative cases can assert status and error code.
     */
    private ResponseEntity<String> request(String method, String uri, String body,
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.valueOf(method))
                .uri(uri).headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Publish one synthetic outbox event as the worker of the owning tenant. */
    private String publish(String key) {
        String body = "{\"aggregateId\":\"" + AGGREGATE + "\",\"aggregateVersion\":1"
                + ",\"sender\":\"lab-partner\",\"eventType\":\"observation.result\""
                + ",\"namespace\":\"partner-lab\",\"payloadDigest\":\"" + DIGEST + "\""
                + ",\"idempotencyKey\":\"" + key + "\"}";
        ResponseEntity<String> response = request("POST", "/api/v1/delivery/events", body, WORKER_A, ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return field(response.getBody(), "eventId");
    }

    /** Replay one event through the REST adapter under the supplied identity. */
    private ResponseEntity<String> replay(String eventId, String reason, String subject, String org) {
        String body = "{\"eventId\":\"" + eventId + "\",\"reason\":\"" + reason + "\"}";
        return request("POST", "/api/v1/delivery/replays", body, subject, org);
    }

    /** Return a top-level string field of a JSON body, or an empty string. */
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

    /** Count replay facts for one original event under one tenant's forced RLS. */
    private static int replayCount(String org, String eventId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement bind = connection.createStatement()) {
            bind.execute("SET app.current_org = '" + org + "'");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.delivery_replay WHERE original_event_id = CAST(? AS uuid)")) {
                query.setString(1, eventId);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }
    }

    /** Read the persisted replay fact of one event under a tenant's forced RLS. */
    private static Optional<ReplayRow> replayRow(String org, String eventId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement bind = connection.createStatement()) {
            bind.execute("SET app.current_org = '" + org + "'");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT organization_id::text, original_event_id::text, reason, authorized_by_subject "
                            + "FROM public.delivery_replay WHERE original_event_id = CAST(? AS uuid)")) {
                query.setString(1, eventId);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ReplayRow(result.getString(1), result.getString(2),
                            result.getString(3), result.getString(4)));
                }
            }
        }
    }

    /** Read the original outbox row under the owning tenant's forced RLS. */
    private static OutboxRow outboxRow(String org, String eventId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement bind = connection.createStatement()) {
            bind.execute("SET app.current_org = '" + org + "'");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT state, aggregate_version, idempotency_key, actor_subject "
                            + "FROM public.delivery_outbox WHERE id = CAST(? AS uuid)")) {
                query.setString(1, eventId);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next(), "the published outbox row must exist");
                    return new OutboxRow(result.getString(1), result.getInt(2),
                            result.getString(3), result.getString(4));
                }
            }
        }
    }

    /** Count outbox rows for an idempotency key, guarding against a second event. */
    private static int outboxCountForKey(String org, String key) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement bind = connection.createStatement()) {
            bind.execute("SET app.current_org = '" + org + "'");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.delivery_outbox WHERE idempotency_key = ?")) {
                query.setString(1, key);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }
    }

    /** Count successful replay audit facts for one event and one authorizer. */
    private static int replayAuditCount(String org, String eventId, String actor) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement bind = connection.createStatement()) {
            bind.execute("SET app.current_org = '" + org + "'");
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.delivery_audit WHERE resource_id = CAST(? AS text) "
                            + "AND action = 'replay' AND outcome = 'success' AND actor_subject = ?")) {
                query.setString(1, eventId);
                query.setString(2, actor);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }
    }

    @Test
    void replayKeepsOriginalEventIdReasonAndAuthorizer() throws Exception {
        String eventId = publish("replay-happy-1");
        int beforeOwner = replayCount(ORG_A, eventId);
        int beforePeer = replayCount(ORG_B, eventId);
        OutboxRow original = outboxRow(ORG_A, eventId);

        ResponseEntity<String> response = replay(eventId, "partner outage drill", ADMIN_A, ORG_A);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        String body = response.getBody();
        assertEquals(eventId, field(body, "originalEventId"));
        assertEquals(ORG_A, field(body, "organizationId"));
        assertEquals("partner outage drill", field(body, "reason"));
        assertEquals(ADMIN_A, field(body, "authorizedBy"));
        String replayId = field(body, "replayId");
        assertFalse(replayId.isEmpty(), "a replay mints its own fact id");
        assertNotEquals(eventId, replayId, "the replay fact is new, but its original id is preserved");
        assertFalse(field(body, "replayedAt").isEmpty());

        ReplayRow persisted = replayRow(ORG_A, eventId).orElseThrow();
        assertEquals(ORG_A, persisted.organizationId());
        assertEquals(eventId, persisted.originalEventId());
        assertEquals("partner outage drill", persisted.reason());
        assertEquals(ADMIN_A, persisted.authorizedBy());
        assertEquals(beforeOwner + 1, replayCount(ORG_A, eventId));
        assertEquals(beforePeer, replayCount(ORG_B, eventId));
        assertTrue(replayRow(ORG_B, eventId).isEmpty(), "forced RLS conceals the peer tenant's replay fact");

        assertEquals(original, outboxRow(ORG_A, eventId), "a replay must not rewrite the source event");
        assertEquals(1, outboxCountForKey(ORG_A, "replay-happy-1"));
        assertEquals(1, replayAuditCount(ORG_A, eventId, ADMIN_A));
    }

    @Test
    void aSecondReplayStillPointsAtTheOriginalEventId() throws Exception {
        String eventId = publish("replay-twice-1");
        int before = replayCount(ORG_A, eventId);

        ResponseEntity<String> first = replay(eventId, "first drill", ADMIN_A, ORG_A);
        ResponseEntity<String> second = replay(eventId, "second drill", ADMIN_A, ORG_A);

        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertEquals(200, second.getStatusCode().value(), second.getBody());
        assertEquals(eventId, field(first.getBody(), "originalEventId"));
        assertEquals(eventId, field(second.getBody(), "originalEventId"));
        String firstId = field(first.getBody(), "replayId");
        String secondId = field(second.getBody(), "replayId");
        assertNotEquals(firstId, secondId, "each replay records its own fact");
        assertNotEquals(eventId, firstId);
        assertNotEquals(eventId, secondId);
        assertEquals(before + 2, replayCount(ORG_A, eventId));
        assertEquals(2, replayAuditCount(ORG_A, eventId, ADMIN_A));
    }

    @Test
    void workerCannotReplayAnEvent() throws Exception {
        String eventId = publish("replay-wrong-role-1");
        int beforeOwner = replayCount(ORG_A, eventId);
        int beforePeer = replayCount(ORG_B, eventId);

        ResponseEntity<String> refused = replay(eventId, "synthetic drill", WORKER_A, ORG_A);

        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", field(refused.getBody(), "code"));
        assertEquals(beforeOwner, replayCount(ORG_A, eventId));
        assertEquals(beforePeer, replayCount(ORG_B, eventId));
    }

    @Test
    void replayWithoutAReasonIsRejected() throws Exception {
        String eventId = publish("replay-no-reason-1");
        int beforeOwner = replayCount(ORG_A, eventId);
        int beforePeer = replayCount(ORG_B, eventId);

        ResponseEntity<String> refused = replay(eventId, "   ", ADMIN_A, ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_REASON", field(refused.getBody(), "code"));
        assertEquals(beforeOwner, replayCount(ORG_A, eventId));
        assertEquals(beforePeer, replayCount(ORG_B, eventId));
    }

    @Test
    void aTooLongReasonIsRejected() throws Exception {
        String eventId = publish("replay-long-reason-1");
        int beforeOwner = replayCount(ORG_A, eventId);
        int beforePeer = replayCount(ORG_B, eventId);

        ResponseEntity<String> refused = replay(eventId, "x".repeat(257), ADMIN_A, ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_REASON", field(refused.getBody(), "code"));
        assertEquals(beforeOwner, replayCount(ORG_A, eventId));
        assertEquals(beforePeer, replayCount(ORG_B, eventId));
    }

    @Test
    void aMalformedEventIdIsRejected() throws Exception {
        String marker = "00000000-0000-0000-0000-000000000000";
        int beforeOwner = replayCount(ORG_A, marker);
        int beforePeer = replayCount(ORG_B, marker);

        ResponseEntity<String> refused = replay("not-a-uuid", "synthetic drill", ADMIN_A, ORG_A);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_EVENTID", field(refused.getBody(), "code"));
        assertEquals(beforeOwner, replayCount(ORG_A, marker));
        assertEquals(beforePeer, replayCount(ORG_B, marker));
    }

    @Test
    void peerTenantCannotReplayAnotherTenantsEvent() throws Exception {
        String eventId = publish("replay-peer-1");
        int beforeOwner = replayCount(ORG_A, eventId);
        int beforePeer = replayCount(ORG_B, eventId);

        ResponseEntity<String> refused = replay(eventId, "synthetic drill", ADMIN_B, ORG_B);

        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", field(refused.getBody(), "code"),
                "a peer tenant must not learn whether the event exists");
        assertEquals(beforeOwner, replayCount(ORG_A, eventId));
        assertEquals(beforePeer, replayCount(ORG_B, eventId));
    }

    @Test
    void replayOfUnknownEventIsRejected() throws Exception {
        String unknown = UUID.randomUUID().toString();

        ResponseEntity<String> refused = replay(unknown, "synthetic drill", ADMIN_A, ORG_A);

        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", field(refused.getBody(), "code"));
        assertEquals(0, replayCount(ORG_A, unknown));
        assertEquals(0, replayCount(ORG_B, unknown));
    }

    @Test
    void replayWithoutAuthenticationIsRejected() throws Exception {
        String eventId = publish("replay-no-auth-1");
        int beforeOwner = replayCount(ORG_A, eventId);
        int beforePeer = replayCount(ORG_B, eventId);

        ResponseEntity<String> refused = replay(eventId, "synthetic drill", null, ORG_A);

        assertEquals(401, refused.getStatusCode().value(), refused.getBody());
        assertEquals("UNAUTHENTICATED", field(refused.getBody(), "code"));
        assertEquals(beforeOwner, replayCount(ORG_A, eventId));
        assertEquals(beforePeer, replayCount(ORG_B, eventId));
    }
}
