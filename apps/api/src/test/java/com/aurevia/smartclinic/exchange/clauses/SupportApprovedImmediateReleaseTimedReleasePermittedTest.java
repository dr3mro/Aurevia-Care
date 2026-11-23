package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * HTTP-level proof of the XCH-06 release-policy clause: an approved patient
 * release may be immediate, timed or held, and a hold carries a reason, a
 * future review deadline and an auditable authorization and lift decision.
 *
 * <p>Every case enters through {@code POST /api/v1/releases} or
 * {@code POST /api/v1/releases/{id}/lift-hold} and reads the resulting patient
 * view through {@code GET /api/v1/patients/{patientId}/released-revisions}.
 * Negative cases prove that a non-decider, another tenant, a stale version and
 * a forbidden transition are refused. The audit assertions read the committed
 * OPS-02 rows; actor attribution lives in {@code release_event.actor_id} and
 * {@code patient_release.created_by}, not in {@code audit_event.actor_id}, which
 * the repository intentionally writes as {@code NULL}. All fixtures are
 * synthetic and the class owns a throwaway database.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_release_clauses_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SupportApprovedImmediateReleaseTimedReleasePermittedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_release_clauses_it";
    private static final String DB = "smartclinic_release_clauses_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN_A = "clinician-a";
    private static final String RECEPTION_A = "reception-a";
    private static final String CLINICIAN_B = "clinician-b";

    private RestClient rest;

    /** Build a fresh client against the randomly assigned HTTP port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create the per-class database, apply the production migrations and seed two
     * synthetic tenants with the same local patient id so RLS concealment is real.
     */
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
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "bootstrap-a", "tenant_admin");
            seedPatient(statement, ORG_A, "bootstrap-a");
            seedStaff(statement, ORG_A, CLINICIAN_A, "clinician");
            seedStaff(statement, ORG_A, RECEPTION_A, "reception");
            seedStaff(statement, ORG_B, CLINICIAN_B, "clinician");
            seedPatient(statement, ORG_B, CLINICIAN_B);
        }
    }

    /** Remove the throwaway database so a later class starts from a clean schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Seed one active staff member with a single bounded role in their own tenant. */
    private static void seedStaff(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic " + subject + "' "
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

    /** Seed the same synthetic patient identifier in each tenant by design. */
    private static void seedPatient(Statement statement, String org, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-" + org.substring(0, 4)
                + "', 'SITE', 'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /** Send one HTTP request with synthetic identity, tenant, correlation and version headers. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation,
                                           String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> {
                    httpHeaders.addAll(headers);
                    if (ifMatch != null) {
                        httpHeaders.set("If-Match", ifMatch);
                    }
                });
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Authorize a release; the actor, issuer and tenant are derived from the request context. */
    private ResponseEntity<String> authorize(String revision, String kind, String effectiveFrom,
                                             String holdReason, String holdExpiry,
                                             String actor, String org, String correlation) {
        String body = "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"" + kind + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"")
                + (holdReason == null ? "" : ",\"holdReason\":\"" + holdReason + "\"")
                + (holdExpiry == null ? "" : ",\"holdExpiresAt\":\"" + holdExpiry + "\"")
                + "}";
        return request(HttpMethod.POST, "/api/v1/releases", body, actor, org, correlation, null);
    }

    /** Send a versioned release command such as {@code lift-hold}. */
    private ResponseEntity<String> command(String id, String action, String body, String ifMatch,
                                           String actor, String org, String correlation) {
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/" + action, body,
                actor, org, correlation, ifMatch);
    }

    /** Read the patient's currently visible revision identifiers. */
    private ResponseEntity<String> visible(String actor, String org, String correlation) {
        return request(HttpMethod.GET, "/api/v1/patients/" + PATIENT + "/released-revisions",
                null, actor, org, correlation, null);
    }

    /** Read one release, relying on forced RLS to conceal another tenant's row. */
    private ResponseEntity<String> get(String id, String actor, String org, String correlation) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id, null, actor, org, correlation, null);
    }

    /** Extract one scalar field from the small, synthetic JSON responses. */
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

    /** Read the committed OPS-02 outcomes for one correlation id and action. */
    private static List<String> auditOutcomes(String correlation, String action) throws Exception {
        return queryStrings("SELECT outcome FROM public.audit_event "
                + "WHERE correlation_id = '" + correlation + "' AND action = '" + action + "' "
                + "AND purpose = 'RELEASE_ADMIN' AND scope = 'RELEASE'");
    }

    /** Read a single-column result set as the tenant migration role. */
    private static List<String> queryStrings(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                List<String> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    /** Resolve a synthetic subject's local identifier inside its own tenant. */
    private static String userId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next(), "synthetic subject must be seeded: " + subject);
                return result.getString(1);
            }
        }
    }

    /**
     * Poll the patient's visible list for up to 45 seconds so the clock crossing a
     * near-future effective instant is observed rather than assumed. The budget
     * exceeds the buffered reveal instant with margin for a contended runner.
     */
    private void awaitVisible(String revision, String actor, String org, String correlation) throws Exception {
        for (int attempt = 0; attempt < 90; attempt++) {
            if (visible(actor, org, correlation + "-" + attempt).getBody().contains(revision)) {
                return;
            }
            Thread.sleep(500);
        }
        fail("timed revision never became visible: " + revision);
    }

    /**
     * An immediate authorization is visible at once, stamps the clock-derived
     * effective instant and writes exactly one successful audit row.
     */
    @Test
    void immediateReleaseIsAuthorizedVisibleAndAudited() throws Exception {
        String correlation = "clause-immediate-1";
        ResponseEntity<String> created = authorize("CLAUSE-IMMEDIATE-001", "IMMEDIATE", null, null, null,
                CLINICIAN_A, ORG_A, correlation);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("AUTHORIZED", field(created.getBody(), "state"));

        // A blank effectiveFrom for an immediate release resolves to the shared clock,
        // not to a client value; allow clock skew but bound it to a few minutes.
        String stamped = field(created.getBody(), "effectiveFrom");
        assertFalse(stamped.isBlank(), created.getBody());
        Instant effective = Instant.parse(stamped);
        assertFalse(effective.isBefore(Instant.now().minus(Duration.ofMinutes(5))), created.getBody());
        assertFalse(effective.isAfter(Instant.now().plus(Duration.ofMinutes(5))), created.getBody());

        assertTrue(visible(CLINICIAN_A, ORG_A, "clause-immediate-visible-1").getBody()
                .contains("CLAUSE-IMMEDIATE-001"));
        assertEquals(List.of("SUCCESS"), auditOutcomes(correlation, "RELEASE_AUTHORIZE"));
    }

    /**
     * A timed authorization is hidden while its instant is in the future and
     * appears once the effective instant has passed. Each POST uses a distinct
     * immutable revision because a revision is one-way.
     */
    @Test
    void timedReleaseHidesUntilItsEffectiveInstant() throws Exception {
        String future = Instant.now().plus(Duration.ofHours(1)).toString();
        ResponseEntity<String> futureCreated = authorize("CLAUSE-TIMED-FUTURE-002", "TIMED", future, null, null,
                CLINICIAN_A, ORG_A, "clause-timed-future-2");
        assertEquals(HttpStatus.CREATED, futureCreated.getStatusCode(), futureCreated.getBody());
        assertEquals("AUTHORIZED", field(futureCreated.getBody(), "state"));
        // Jackson renders an Instant at microsecond precision, so compare instants
        // with a one-second tolerance rather than the raw ISO strings.
        Instant echoed = Instant.parse(field(futureCreated.getBody(), "effectiveFrom"));
        assertTrue(Duration.between(Instant.parse(future), echoed).abs().compareTo(Duration.ofSeconds(1)) < 0,
                futureCreated.getBody());
        assertEquals("TIMED", field(futureCreated.getBody(), "kind"));
        assertFalse(visible(CLINICIAN_A, ORG_A, "clause-timed-hidden-2").getBody()
                .contains("CLAUSE-TIMED-FUTURE-002"));

        String past = Instant.now().minus(Duration.ofHours(1)).toString();
        ResponseEntity<String> pastCreated = authorize("CLAUSE-TIMED-PAST-002", "TIMED", past, null, null,
                CLINICIAN_A, ORG_A, "clause-timed-past-2");
        assertEquals(HttpStatus.CREATED, pastCreated.getStatusCode(), pastCreated.getBody());
        assertEquals("AUTHORIZED", field(pastCreated.getBody(), "state"));
        assertTrue(visible(CLINICIAN_A, ORG_A, "clause-timed-visible-2").getBody()
                .contains("CLAUSE-TIMED-PAST-002"));

        // Observe the transition rather than only its two endpoints: a revision
        // whose instant is seconds away opens by elapsed time without any lift.
        // A 20-second buffer keeps the hidden-check below unambiguously before the
        // instant even on a contended full build with instrumentation and GC pauses;
        // the reveal is driven by the shared clock, not by any lift command.
        String soon = Instant.now().plusSeconds(20).toString();
        ResponseEntity<String> soonCreated = authorize("CLAUSE-TIMED-SOON-002", "TIMED", soon, null, null,
                CLINICIAN_A, ORG_A, "clause-timed-soon-2");
        assertEquals(HttpStatus.CREATED, soonCreated.getStatusCode(), soonCreated.getBody());
        assertFalse(visible(CLINICIAN_A, ORG_A, "clause-timed-soon-hidden-2").getBody()
                .contains("CLAUSE-TIMED-SOON-002"));
        awaitVisible("CLAUSE-TIMED-SOON-002", CLINICIAN_A, ORG_A, "clause-timed-soon-visible-2");
    }

    /**
     * A hold requires a reason and a future expiry, remains HELD and hidden, and
     * becomes visible only after an authorized review lifts it. The lift writes
     * its own success audit row and a second append-only decision event.
     */
    @Test
    void holdNeedsReasonAndFutureExpiryAndStaysHiddenUntilLifted() throws Exception {
        String futureExpiry = Instant.now().plus(Duration.ofHours(2)).toString();
        ResponseEntity<String> noReason = authorize("CLAUSE-HOLD-NOREASON-003", "HOLD", null, "  ", futureExpiry,
                CLINICIAN_A, ORG_A, "clause-hold-noreason-3");
        assertEquals(422, noReason.getStatusCode().value(), noReason.getBody());
        assertTrue(noReason.getBody().contains("\"code\":\"INVALID_HOLD_REASON\""), noReason.getBody());

        String pastExpiry = Instant.now().minus(Duration.ofHours(1)).toString();
        ResponseEntity<String> badExpiry = authorize("CLAUSE-HOLD-PASTEXP-003", "HOLD", null, "review",
                pastExpiry, CLINICIAN_A, ORG_A, "clause-hold-pastexp-3");
        assertEquals(422, badExpiry.getStatusCode().value(), badExpiry.getBody());
        assertTrue(badExpiry.getBody().contains("\"code\":\"INVALID_HOLD_EXPIRY\""), badExpiry.getBody());

        ResponseEntity<String> created = authorize("CLAUSE-HOLD-003", "HOLD", null, "synthetic review hold",
                futureExpiry, CLINICIAN_A, ORG_A, "clause-hold-create-3");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("HELD", field(created.getBody(), "state"));
        assertEquals("HOLD", field(created.getBody(), "kind"));
        assertEquals("synthetic review hold", field(created.getBody(), "holdReason"));
        assertFalse(field(created.getBody(), "holdExpiresAt").isBlank(), created.getBody());
        assertEquals(List.of("SUCCESS"), auditOutcomes("clause-hold-create-3", "RELEASE_AUTHORIZE"));
        String id = field(created.getBody(), "id");
        assertFalse(visible(CLINICIAN_A, ORG_A, "clause-hold-hidden-3").getBody()
                .contains("CLAUSE-HOLD-003"));

        // The reason and review deadline must survive a round trip through the store.
        ResponseEntity<String> heldRead = get(id, CLINICIAN_A, ORG_A, "clause-hold-read-3");
        assertEquals(HttpStatus.OK, heldRead.getStatusCode(), heldRead.getBody());
        assertEquals("synthetic review hold", field(heldRead.getBody(), "holdReason"));
        assertFalse(field(heldRead.getBody(), "holdExpiresAt").isBlank(), heldRead.getBody());

        // A forged reviewer in the request body is ignored: the V24 trigger derives
        // the reviewer from the authenticated actor in the guarded transaction.
        String forgedNote = "{\"note\":\"synthetic review lift\",\"reviewedBy\":\"" + ORG_B + "\"}";
        ResponseEntity<String> lifted = command(id, "lift-hold", forgedNote, "\"1\"",
                CLINICIAN_A, ORG_A, "clause-hold-lift-3");
        assertEquals(HttpStatus.OK, lifted.getStatusCode(), lifted.getBody());
        assertEquals("AUTHORIZED", field(lifted.getBody(), "state"));
        assertFalse(lifted.getBody().contains(ORG_B), lifted.getBody());
        assertEquals(userId(ORG_A, CLINICIAN_A), field(lifted.getBody(), "reviewedBy"));
        assertFalse(field(lifted.getBody(), "reviewedAt").isBlank(), lifted.getBody());
        assertTrue(visible(CLINICIAN_A, ORG_A, "clause-hold-visible-3").getBody()
                .contains("CLAUSE-HOLD-003"));
        assertEquals(List.of("SUCCESS"), auditOutcomes("clause-hold-lift-3", "RELEASE_LIFT_HOLD"));
        assertEquals(List.of("RELEASE_AUTHORIZE", "RELEASE_LIFT_HOLD"),
                queryStrings("SELECT action FROM public.release_event WHERE release_id = '" + id
                        + "' ORDER BY occurred_at"));
        assertEquals(List.of("HOLD_LIFTED"), queryStrings(
                "SELECT event_type FROM public.release_notification WHERE correlation_id = 'clause-hold-lift-3'"));
    }

    /**
     * A receptionist holds neither clinical decision permission: both an
     * authorization and a hold lift are denied, each with its own audit row.
     */
    @Test
    void receptionCannotAuthorizeOrLiftAHold() throws Exception {
        String expiry = Instant.now().plus(Duration.ofHours(2)).toString();
        ResponseEntity<String> deniedAuthorize = authorize("CLAUSE-HOLD-DENY-004", "HOLD", null, "review",
                expiry, RECEPTION_A, ORG_A, "clause-deny-authorize-4");
        assertEquals(HttpStatus.FORBIDDEN, deniedAuthorize.getStatusCode(), deniedAuthorize.getBody());
        assertEquals(List.of("DENIED"), auditOutcomes("clause-deny-authorize-4", "RELEASE_AUTHORIZE"));

        ResponseEntity<String> held = authorize("CLAUSE-HOLD-LIFT-DENY-004", "HOLD", null, "review",
                expiry, CLINICIAN_A, ORG_A, "clause-create-deny-lift-4");
        assertEquals(HttpStatus.CREATED, held.getStatusCode(), held.getBody());
        String id = field(held.getBody(), "id");
        ResponseEntity<String> deniedLift = command(id, "lift-hold", "{\"note\":\"synthetic review lift\"}",
                "\"1\"", RECEPTION_A, ORG_A, "clause-deny-lift-4");
        assertEquals(HttpStatus.FORBIDDEN, deniedLift.getStatusCode(), deniedLift.getBody());
        assertEquals(List.of("DENIED"), auditOutcomes("clause-deny-lift-4", "RELEASE_LIFT_HOLD"));

        // Reception is a reader but not a decider: single-resource reads stay allowed
        // while the patient-scoped enumeration and both decisions are refused.
        assertEquals(HttpStatus.OK, get(id, RECEPTION_A, ORG_A, "clause-read-4").getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/v1/releases/" + id + "/history",
                null, RECEPTION_A, ORG_A, "clause-history-4", null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                visible(RECEPTION_A, ORG_A, "clause-enumerate-4").getStatusCode());
    }

    /** Forced RLS conceals another tenant's release even from that tenant's clinician. */
    @Test
    void anotherTenantCannotReadTheRelease() {
        ResponseEntity<String> created = authorize("CLAUSE-ISOLATION-005", "IMMEDIATE", null, null, null,
                CLINICIAN_A, ORG_A, "clause-isolation-create-5");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> concealed = get(id, CLINICIAN_B, ORG_B, "clause-isolation-read-5");
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());
        assertTrue(concealed.getBody().contains("\"code\":\"NOT_FOUND\""), concealed.getBody());
        assertFalse(concealed.getBody().contains(id), concealed.getBody());
        assertFalse(visible(CLINICIAN_B, ORG_B, "clause-isolation-visible-5").getBody()
                .contains("CLAUSE-ISOLATION-005"));
    }

    /** A stale version precondition on a hold lift is rejected and audited as STALE. */
    @Test
    void staleIfMatchOnLiftHoldIsRejected() throws Exception {
        ResponseEntity<String> held = authorize("CLAUSE-HOLD-STALE-006", "HOLD", null, "review",
                Instant.now().plus(Duration.ofHours(2)).toString(), CLINICIAN_A, ORG_A,
                "clause-stale-create-6");
        assertEquals(HttpStatus.CREATED, held.getStatusCode(), held.getBody());
        String id = field(held.getBody(), "id");

        ResponseEntity<String> stale = command(id, "lift-hold", "{\"note\":\"synthetic review lift\"}",
                "\"9\"", CLINICIAN_A, ORG_A, "clause-stale-lift-6");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertTrue(stale.getBody().contains("\"code\":\"STALE_VERSION\""), stale.getBody());
        assertEquals(List.of("STALE"), auditOutcomes("clause-stale-lift-6", "RELEASE_LIFT_HOLD"));
        assertEquals("HELD", field(get(id, CLINICIAN_A, ORG_A, "clause-stale-read-6").getBody(), "state"));
        assertEquals(List.of(), queryStrings("SELECT action FROM public.release_event WHERE release_id = '"
                + id + "' AND action = 'RELEASE_LIFT_HOLD'"));
        assertFalse(visible(CLINICIAN_A, ORG_A, "clause-stale-visible-6").getBody()
                .contains("CLAUSE-HOLD-STALE-006"));
    }

    /** An already authorized release cannot be lifted again: the state machine refuses it. */
    @Test
    void liftHoldOnAuthorizedReleaseIsRefused() throws Exception {
        ResponseEntity<String> created = authorize("CLAUSE-IMMEDIATE-LIFT-007", "IMMEDIATE", null, null, null,
                CLINICIAN_A, ORG_A, "clause-state-create-7");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> refused = command(id, "lift-hold", "{\"note\":\"synthetic review lift\"}",
                "\"1\"", CLINICIAN_A, ORG_A, "clause-state-lift-7");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertTrue(refused.getBody().contains("\"code\":\"INVALID_TRANSITION\""), refused.getBody());
        assertEquals(List.of("CONFLICT"), auditOutcomes("clause-state-lift-7", "RELEASE_LIFT_HOLD"));
        assertEquals("AUTHORIZED", field(get(id, CLINICIAN_A, ORG_A, "clause-state-read-7").getBody(), "state"));
        assertEquals(List.of(), queryStrings("SELECT event_type FROM public.release_notification "
                + "WHERE correlation_id = 'clause-state-lift-7'"));
        assertEquals(List.of("RELEASE_AUTHORIZE"),
                queryStrings("SELECT action FROM public.release_event WHERE release_id = '" + id + "'"));
    }
}
