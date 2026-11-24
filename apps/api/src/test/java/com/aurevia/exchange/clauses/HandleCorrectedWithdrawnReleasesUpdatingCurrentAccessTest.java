package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * XCH-06 clause 3 proof through the real HTTP entry point: correcting or
 * withdrawing a release changes current patient access at once, keeps the
 * append-only decision history and publishes the notification to the recipient
 * scope the release policy prescribes.
 *
 * <p>The unique angle of this class is the notification's {@code recipient_scope}:
 * the existing {@code it.exchangerelease} isolation suite asserts the event type
 * but not that a retraction is published to {@code prior-recipients} while the
 * fresh authorization of a correction goes to {@code linked-patient}. Both
 * commands, the current-access query and the history read are driven through
 * their controllers against a dedicated PostgreSQL database with forced RLS.
 * All fixtures are synthetic; the database is created and dropped by this class.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_release_correct_withdraw_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class HandleCorrectedWithdrawnReleasesUpdatingCurrentAccessTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String DATABASE = "orevia_release_correct_withdraw_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private RestClient rest;

    /** Create an isolated database, migrate it and seed both tenants with synthetic staff and charts. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "clinician-a", "clinician");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedStaff(statement, ORG_A, "norole-a", null);
            seedStaff(statement, ORG_B, "clinician-b", "clinician");
            // Identical local identifiers in both tenants prove forced RLS is the only separator.
            seedPatient(statement, ORG_A, "clinician-a");
            seedPatient(statement, ORG_B, "clinician-b");
        }
    }

    /** Drop the dedicated database so the next run starts from a clean schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Bind the HTTP client to the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedStaff(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + organization + "', 'Synthetic " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + organization + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        if (role != null) {
            statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = '" + subject + "'");
        }
    }

    private static void seedPatient(Statement statement, String organization, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + organization + "', '" + PATIENT + "', 1, 'MRN-" + organization.substring(0, 4)
                + "', 'SITE', 'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String subject,
                                           String organization, String ifMatch, String correlation) {
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
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> authorize(String revision, String actor, String organization, String correlation) {
        String body = "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"IMMEDIATE\"}";
        return request(HttpMethod.POST, "/api/v1/releases", body, actor, organization, null, correlation);
    }

    private ResponseEntity<String> withdraw(String id, String ifMatch, String reason,
                                            String actor, String organization, String correlation) {
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/withdraw",
                "{\"reason\":\"" + reason + "\"}", actor, organization, ifMatch, correlation);
    }

    private ResponseEntity<String> correct(String id, String ifMatch, String correctedRevision,
                                           int correctedVersion, String reason,
                                           String actor, String organization, String correlation) {
        String body = "{\"correctedRevisionId\":\"" + correctedRevision + "\",\"correctedRevisionVersion\":"
                + correctedVersion + ",\"reason\":\"" + reason + "\"}";
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/correct",
                body, actor, organization, ifMatch, correlation);
    }

    private ResponseEntity<String> get(String id, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id, null, actor, organization, null, null);
    }

    private ResponseEntity<String> history(String id, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id + "/history", null, actor, organization, null, null);
    }

    private ResponseEntity<String> visible(String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/patients/" + PATIENT + "/released-revisions",
                null, actor, organization, null, null);
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

    // SQL is assembled only from test-controlled constants (correlation ids and
    // service-generated UUIDs), never from a request body; a shared helper for
    // untrusted input would need a PreparedStatement instead.
    private static List<String> queryStrings(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (var result = statement.executeQuery(sql)) {
                List<String> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    private static int queryCount(String sql) throws Exception {
        return Integer.parseInt(queryStrings(sql).get(0));
    }

    /**
     * Withdrawal removes the revision from current access at once, retains the
     * decision history and notifies prior recipients rather than the linked patient.
     */
    @Test
    void withdrawalUpdatesAccessRetainsHistoryAndNotifiesPriorRecipients() throws Exception {
        ResponseEntity<String> created = authorize("WITHDRAW-REV-1", "clinician-a", ORG_A, "cw-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"WITHDRAW-REV-1\""));

        ResponseEntity<String> withdrawn = withdraw(id, "\"1\"", "retracted by policy",
                "clinician-a", ORG_A, "cw-decision");
        assertEquals(HttpStatus.OK, withdrawn.getStatusCode(), withdrawn.getBody());
        assertEquals("WITHDRAWN", field(withdrawn.getBody(), "state"));
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("\"WITHDRAW-REV-1\""),
                "a withdrawn revision must leave current access on the next query");

        String events = history(id, "clinician-a", ORG_A).getBody();
        assertTrue(events.contains("\"action\":\"RELEASE_AUTHORIZE\""), events);
        assertTrue(events.contains("\"action\":\"RELEASE_WITHDRAW\""), events);
        assertEquals(List.of("RELEASE_WITHDRAWN"), queryStrings(
                "SELECT event_type FROM public.release_notification WHERE correlation_id = 'cw-decision'"));
        assertEquals(List.of("prior-recipients"), queryStrings(
                "SELECT recipient_scope FROM public.release_notification WHERE correlation_id = 'cw-decision'"));
        assertEquals(1, queryCount("SELECT count(*) FROM public.patient_release WHERE id = '" + id + "'"),
                "withdrawal is a state transition, not a delete: the history-bearing row survives");
    }

    /**
     * Correction moves current access to the replacement revision, keeps the
     * original decision history and publishes both policy-defined notifications.
     */
    @Test
    void correctionMovesAccessToNewRevisionRetainingHistoryAndNotifyingPriorRecipients() throws Exception {
        ResponseEntity<String> created = authorize("CORRECT-REV-1", "clinician-a", ORG_A, "cc-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        String effectiveFrom = field(created.getBody(), "effectiveFrom");
        String policyVersion = field(created.getBody(), "policyVersion");
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"CORRECT-REV-1\""));

        ResponseEntity<String> corrected = correct(id, "\"1\"", "CORRECT-REV-2", 2, "corrected result",
                "clinician-a", ORG_A, "cc-decision");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        String replacementId = field(corrected.getBody(), "id");
        assertEquals("CORRECT-REV-2", field(corrected.getBody(), "revisionId"));
        assertEquals(id, field(corrected.getBody(), "supersedesId"));
        assertEquals("AUTHORIZED", field(corrected.getBody(), "state"));
        assertEquals(effectiveFrom, field(corrected.getBody(), "effectiveFrom"),
                "the replacement inherits the original effective time so access cannot open early");
        assertEquals(policyVersion, field(corrected.getBody(), "policyVersion"),
                "the replacement inherits the policy that authorized the original");

        String access = visible("clinician-a", ORG_A).getBody();
        assertTrue(access.contains("\"CORRECT-REV-2\""));
        assertFalse(access.contains("\"CORRECT-REV-1\""));

        String originalHistory = history(id, "clinician-a", ORG_A).getBody();
        assertTrue(originalHistory.contains("\"action\":\"RELEASE_AUTHORIZE\""), originalHistory);
        assertTrue(originalHistory.contains("\"action\":\"RELEASE_CORRECT\""), originalHistory);
        assertEquals(List.of("RELEASE_CORRECTED"), queryStrings(
                "SELECT event_type FROM public.release_notification "
                        + "WHERE correlation_id = 'cc-decision' AND release_id = '" + id + "'"));
        assertEquals(List.of("prior-recipients"), queryStrings(
                "SELECT recipient_scope FROM public.release_notification "
                        + "WHERE correlation_id = 'cc-decision' AND release_id = '" + id + "'"));
        assertEquals(List.of("RELEASE_AUTHORIZED"), queryStrings(
                "SELECT event_type FROM public.release_notification "
                        + "WHERE correlation_id = 'cc-decision' AND release_id = '" + replacementId + "'"));
        assertEquals(List.of("linked-patient"), queryStrings(
                "SELECT recipient_scope FROM public.release_notification "
                        + "WHERE correlation_id = 'cc-decision' AND release_id = '" + replacementId + "'"));
    }

    /**
     * Staff who may read a release but are not deciders, and a member with no
     * role, cannot withdraw or correct it; access and history stay unchanged.
     */
    @Test
    void nonDecidersCannotWithdrawOrCorrectAndAccessIsUnchanged() {
        ResponseEntity<String> created = authorize("ROLE-REV-1", "clinician-a", ORG_A, "cr-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        for (String actor : List.of("reception-a", "auditor-a", "norole-a")) {
            assertEquals(HttpStatus.FORBIDDEN,
                    withdraw(id, "\"1\"", "unauthorised", actor, ORG_A, "cr-withdraw-" + actor).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN,
                    correct(id, "\"1\"", "ROLE-REV-2", 2, "unauthorised", actor, ORG_A,
                            "cr-correct-" + actor).getStatusCode());
        }

        assertEquals("AUTHORIZED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"ROLE-REV-1\""));
        String events = history(id, "clinician-a", ORG_A).getBody();
        assertFalse(events.contains("\"action\":\"RELEASE_WITHDRAW\""), events);
        assertFalse(events.contains("\"action\":\"RELEASE_CORRECT\""), events);
    }

    /** A clinician of another tenant cannot see or retract the release, which stays authorized. */
    @Test
    void peerTenantCannotRetractTheReleaseAndStateIsUnchanged() {
        ResponseEntity<String> created = authorize("TENANT-REV-1", "clinician-a", ORG_A, "ct-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.NOT_FOUND,
                withdraw(id, "\"1\"", "cross tenant", "clinician-b", ORG_B, "ct-withdraw").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                correct(id, "\"1\"", "TENANT-REV-2", 2, "cross tenant", "clinician-b", ORG_B,
                        "ct-correct").getStatusCode());

        assertEquals("AUTHORIZED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"TENANT-REV-1\""));
        assertFalse(visible("clinician-b", ORG_B).getBody().contains("\"TENANT-REV-1\""));
    }

    /** A stale version precondition refuses the withdrawal with 412 and changes no access. */
    @Test
    void staleIfMatchRefusesWithdrawalAndLeavesAccessUnchanged() {
        ResponseEntity<String> created = authorize("STALE-REV-1", "clinician-a", ORG_A, "cs-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> stale = withdraw(id, "\"9\"", "stale", "clinician-a", ORG_A, "cs-decision");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals("AUTHORIZED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"STALE-REV-1\""));
        assertFalse(history(id, "clinician-a", ORG_A).getBody().contains("\"action\":\"RELEASE_WITHDRAW\""));
    }

    /** Terminal states and already-used revision identifiers cannot be reused for a decision. */
    @Test
    void forbiddenStatesAndRepeatedRevisionsAreRefused() {
        ResponseEntity<String> first = authorize("STATE-REV-1", "clinician-a", ORG_A, "cf-first");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        String firstId = field(first.getBody(), "id");
        assertEquals(HttpStatus.OK,
                withdraw(firstId, "\"1\"", "retracted", "clinician-a", ORG_A, "cf-withdraw").getStatusCode());

        ResponseEntity<String> withdrawAgain = withdraw(firstId, "\"2\"", "again",
                "clinician-a", ORG_A, "cf-again");
        assertEquals(HttpStatus.CONFLICT, withdrawAgain.getStatusCode(), withdrawAgain.getBody());
        assertEquals("INVALID_TRANSITION", field(withdrawAgain.getBody(), "code"));

        ResponseEntity<String> second = authorize("STATE-REV-2", "clinician-a", ORG_A, "cf-second");
        String secondId = field(second.getBody(), "id");
        ResponseEntity<String> selfCorrect = correct(secondId, "\"1\"", "STATE-REV-2", 2, "self",
                "clinician-a", ORG_A, "cf-self");
        assertEquals(HttpStatus.CONFLICT, selfCorrect.getStatusCode(), selfCorrect.getBody());

        ResponseEntity<String> occupied = authorize("STATE-REV-3", "clinician-a", ORG_A, "cf-occupied");
        assertEquals(HttpStatus.CREATED, occupied.getStatusCode(), occupied.getBody());
        ResponseEntity<String> duplicate = correct(secondId, "\"1\"", "STATE-REV-3", 1, "duplicate",
                "clinician-a", ORG_A, "cf-duplicate");
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode(), duplicate.getBody());

        assertEquals("AUTHORIZED", field(get(secondId, "clinician-a", ORG_A).getBody(), "state"));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"STATE-REV-2\""));
    }

    /** Blank reasons and invalid revision fields are refused before any transition is attempted. */
    @Test
    void missingOrInvalidDecisionInputIsRefused() {
        ResponseEntity<String> created = authorize("INPUT-REV-1", "clinician-a", ORG_A, "ci-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> blankWithdraw = withdraw(id, "\"1\"", "  ", "clinician-a", ORG_A, "ci-wb");
        assertEquals(422, blankWithdraw.getStatusCode().value(), blankWithdraw.getBody());
        assertEquals("INVALID_WITHDRAW_REASON", field(blankWithdraw.getBody(), "code"));

        ResponseEntity<String> blankCorrect = correct(id, "\"1\"", "INPUT-REV-2", 2, " ",
                "clinician-a", ORG_A, "ci-cb");
        assertEquals(422, blankCorrect.getStatusCode().value(), blankCorrect.getBody());
        assertEquals("INVALID_CORRECTION_REASON", field(blankCorrect.getBody(), "code"));

        ResponseEntity<String> badId = correct(id, "\"1\"", "!", 2, "reason", "clinician-a", ORG_A, "ci-ci");
        assertEquals(422, badId.getStatusCode().value(), badId.getBody());
        assertEquals("INVALID_REVISION_ID", field(badId.getBody(), "code"));

        ResponseEntity<String> badVersion = correct(id, "\"1\"", "INPUT-REV-2", 0, "reason",
                "clinician-a", ORG_A, "ci-cv");
        assertEquals(422, badVersion.getStatusCode().value(), badVersion.getBody());
        assertEquals("INVALID_REVISION_VERSION", field(badVersion.getBody(), "code"));

        assertEquals("AUTHORIZED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("\"INPUT-REV-1\""));
    }
}
