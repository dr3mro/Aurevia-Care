package com.aurevia.it.exchangerelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/** S-G2-06 acceptance: release authorization, timed/hold policies and withdrawal (XCH-06/PAT-02). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_release_it")
@ActiveProfiles("synthetic-auth")
class PatientReleaseIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_release_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_release_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_release_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "clinician-a", "clinician");
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedStaff(statement, ORG_B, "clinician-b", "clinician");
            // The same local patient id exists in both tenants by design.
            seedPatient(statement, ORG_A, PATIENT, "clinician-a");
            seedPatient(statement, ORG_B, PATIENT, "clinician-b");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_release_it WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String role) throws Exception {
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

    private static void seedPatient(Statement statement, String org, String patientId, String actor) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-" + org.substring(0, 4)
                + "', 'SITE', 'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    private HttpHeaders headers(String subject, String organization, String correlation) {
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
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> {
                    httpHeaders.addAll(headers(subject, organization, correlation));
                    if (ifMatch != null) {
                        httpHeaders.set("If-Match", ifMatch);
                    }
                });
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> authorize(String revision, int revisionVersion, String kind,
                                             String effectiveFrom, String holdReason, String holdExpiry,
                                             String actor, String org, String correlation) {
        String body = "{\"revisionId\":\"" + revision + "\",\"revisionVersion\":" + revisionVersion
                + ",\"patientId\":\"" + PATIENT + "\",\"policyVersion\":2,\"kind\":\"" + kind + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"")
                + (holdReason == null ? "" : ",\"holdReason\":\"" + holdReason + "\"")
                + (holdExpiry == null ? "" : ",\"holdExpiresAt\":\"" + holdExpiry + "\"")
                + "}";
        return request(HttpMethod.POST, "/api/v1/releases", body, actor, org, correlation, null);
    }

    private ResponseEntity<String> get(String id, String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id, null, actor, org, "rel-get", null);
    }

    private ResponseEntity<String> history(String id, String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/releases/" + id + "/history", null, actor, org, "rel-history", null);
    }

    private ResponseEntity<String> visible(String actor, String org) {
        return request(HttpMethod.GET, "/api/v1/patients/" + PATIENT + "/released-revisions",
                null, actor, org, "rel-access", null);
    }

    private ResponseEntity<String> command(String id, String action, String body, String ifMatch,
                                           String actor, String org, String correlation) {
        return request(HttpMethod.POST, "/api/v1/releases/" + id + "/" + action, body,
                actor, org, correlation, ifMatch);
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

    private static String userId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static List<String> queryStrings(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
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

    private static int queryCount(String sql) throws Exception {
        return Integer.parseInt(queryStrings(sql).get(0));
    }

    @Test
    void immediateReleaseIsAuthorizedAuditedAndNotified() throws Exception {
        ResponseEntity<String> created = authorize("LAB-AUTH-001", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-create-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("AUTHORIZED", field(created.getBody(), "state"));
        assertEquals(userId(ORG_A, "clinician-a"), field(created.getBody(), "createdBy"));
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.OK, get(id, "clinician-a", ORG_A).getStatusCode());
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("LAB-AUTH-001"));

        assertEquals(List.of("RELEASE_AUTHORIZE"), queryStrings(
                "SELECT action FROM public.release_event WHERE release_id = '" + id
                + "' ORDER BY occurred_at"));
        assertEquals(List.of("RELEASE_AUTHORIZED"), queryStrings(
                "SELECT event_type FROM public.release_notification WHERE correlation_id = 'rel-create-1'"));
        assertEquals(userId(ORG_A, "clinician-a"), queryStrings(
                "SELECT actor_id::text FROM public.release_event WHERE release_id = '" + id + "'").get(0));
        assertEquals(1, queryCount("SELECT count(*) FROM public.audit_event WHERE correlation_id = 'rel-create-1' "
                + "AND purpose = 'RELEASE_ADMIN' AND scope = 'RELEASE' AND outcome = 'SUCCESS'"));
    }

    @Test
    void forgedAuthorAndIssuerAreDerivedFromContext() throws Exception {
        String body = "{\"revisionId\":\"LAB-AUTH-002\",\"revisionVersion\":1,\"patientId\":\"" + PATIENT
                + "\",\"policyVersion\":2,\"kind\":\"IMMEDIATE\",\"createdBy\":\"" + userId(ORG_B, "clinician-b")
                + "\",\"issuingOrganizationId\":\"" + ORG_B + "\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/releases", body,
                "clinician-a", ORG_A, "rel-create-forged", null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals(userId(ORG_A, "clinician-a"), field(created.getBody(), "createdBy"));
        assertEquals(ORG_A, field(created.getBody(), "issuingOrganizationId"));
    }

    @Test
    void timedReleaseIsNotVisibleBeforeEffectiveTime() {
        String future = Instant.now().plus(Duration.ofHours(1)).toString();
        ResponseEntity<String> created = authorize("LAB-TIMED-003", 1, "TIMED", future,
                null, null, "clinician-a", ORG_A, "rel-create-3");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("AUTHORIZED", field(created.getBody(), "state"));
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-TIMED-003"));
    }

    @Test
    void holdStaysInvisibleUntilAuthorizedReview() throws Exception {
        String expiry = Instant.now().plus(Duration.ofHours(2)).toString();
        ResponseEntity<String> created = authorize("LAB-HOLD-004", 1, "HOLD", null,
                "needs review", expiry, "clinician-a", ORG_A, "rel-create-4");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("HELD", field(created.getBody(), "state"));
        String id = field(created.getBody(), "id");
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-HOLD-004"));

        String forged = "{\"note\":\"reviewed\",\"reviewedBy\":\"" + ORG_B + "\"}";
        ResponseEntity<String> lifted = command(id, "lift-hold", forged, "\"1\"",
                "clinician-a", ORG_A, "rel-lift-4");
        assertEquals(HttpStatus.OK, lifted.getStatusCode(), lifted.getBody());
        assertEquals("AUTHORIZED", field(lifted.getBody(), "state"));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("LAB-HOLD-004"));
        assertFalse(lifted.getBody().contains(ORG_B));
        assertEquals(List.of("RELEASE_AUTHORIZE", "RELEASE_LIFT_HOLD"),
                queryEvents(id));

        ResponseEntity<String> asReception = authorize("LAB-HOLD-004b", 1, "HOLD", null,
                "needs review", expiry, "reception-a", ORG_A, "rel-create-4b");
        assertEquals(HttpStatus.FORBIDDEN, asReception.getStatusCode());
    }

    private static List<String> queryEvents(String id) throws Exception {
        return queryStrings("SELECT action FROM public.release_event WHERE release_id = '" + id
                + "' ORDER BY occurred_at");
    }

    @Test
    void withdrawalStopsAccessRetainsHistoryAndNotifies() throws Exception {
        ResponseEntity<String> created = authorize("LAB-WITHDRAW-005", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-create-5");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String id = field(created.getBody(), "id");
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("LAB-WITHDRAW-005"));

        ResponseEntity<String> withdrawn = command(id, "withdraw", "{\"reason\":\"retracted\"}", "W/\"1\"",
                "clinician-a", ORG_A, "rel-withdraw-5");
        assertEquals(HttpStatus.OK, withdrawn.getStatusCode(), withdrawn.getBody());
        assertEquals("WITHDRAWN", field(withdrawn.getBody(), "state"));
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-WITHDRAW-005"));
        assertEquals(1, queryCount("SELECT count(*) FROM public.patient_release WHERE id = '" + id + "'"));
        assertEquals(List.of("RELEASE_AUTHORIZE", "RELEASE_WITHDRAW"), queryEvents(id));
        assertEquals(List.of("RELEASE_WITHDRAWN"), queryStrings(
                "SELECT event_type FROM public.release_notification WHERE correlation_id = 'rel-withdraw-5'"));
        assertEquals(1, queryCount("SELECT count(*) FROM public.audit_event WHERE correlation_id = 'rel-withdraw-5' "
                + "AND outcome = 'SUCCESS'"));

        assertEquals(HttpStatus.CONFLICT, command(id, "withdraw", "{\"reason\":\"again\"}", "\"2\"",
                "clinician-a", ORG_A, "rel-withdraw-5b").getStatusCode());
        assertEquals(HttpStatus.PRECONDITION_FAILED, command(id, "withdraw", "{\"reason\":\"again\"}", "\"1\"",
                "clinician-a", ORG_A, "rel-withdraw-5c").getStatusCode());
    }

    @Test
    void correctionSupersedesRevisionAndRedirectsAccess() throws Exception {
        ResponseEntity<String> created = authorize("LAB-CORRECT-006", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-create-6");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> corrected = command(id, "correct",
                "{\"correctedRevisionId\":\"LAB-CORRECT-006B\",\"correctedRevisionVersion\":2,\"reason\":\"corrected result\"}",
                "\"1\"", "clinician-a", ORG_A, "rel-correct-6");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertEquals("LAB-CORRECT-006B", field(corrected.getBody(), "revisionId"));
        assertEquals(id, field(corrected.getBody(), "supersedesId"));
        assertEquals("CORRECTED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
        String access = visible("clinician-a", ORG_A).getBody();
        assertTrue(access.contains("LAB-CORRECT-006B"));
        assertFalse(access.contains("LAB-CORRECT-006\""));
        assertEquals(List.of("RELEASE_AUTHORIZE", "RELEASE_CORRECT"), queryEvents(id));
        assertTrue(queryStrings("SELECT event_type FROM public.release_notification "
                + "WHERE correlation_id = 'rel-correct-6'").contains("RELEASE_CORRECTED"));
    }

    @Test
    void correctionOfReviewedHoldPublishesAnImmediatelyVisibleReplacement() throws Exception {
        String expiry = Instant.now().plus(Duration.ofHours(2)).toString();
        ResponseEntity<String> created = authorize("LAB-HOLD-CORRECT-011", 1, "HOLD", null,
                "needs review", expiry, "clinician-a", ORG_A, "rel-holdcorrect-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-HOLD-CORRECT-011"));

        ResponseEntity<String> lifted = command(id, "lift-hold", "{\"note\":\"reviewed\"}", "\"1\"",
                "clinician-a", ORG_A, "rel-holdcorrect-2");
        assertEquals(HttpStatus.OK, lifted.getStatusCode(), lifted.getBody());
        assertEquals("AUTHORIZED", field(lifted.getBody(), "state"));

        // Lifting advances the version to 2; correcting the now-AUTHORIZED hold must create a
        // fresh IMMEDIATE revision. Reusing kind=HOLD with no reason/expiry would breach V24's
        // CHECK and return 500 instead of giving the patient access to the corrected revision.
        ResponseEntity<String> corrected = command(id, "correct",
                "{\"correctedRevisionId\":\"LAB-HOLD-CORRECT-011B\",\"correctedRevisionVersion\":2,\"reason\":\"corrected result\"}",
                "\"2\"", "clinician-a", ORG_A, "rel-holdcorrect-3");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertEquals("IMMEDIATE", field(corrected.getBody(), "kind"));
        assertEquals("AUTHORIZED", field(corrected.getBody(), "state"));
        assertEquals(id, field(corrected.getBody(), "supersedesId"));
        String access = visible("clinician-a", ORG_A).getBody();
        assertTrue(access.contains("LAB-HOLD-CORRECT-011B"));
        assertFalse(access.contains("LAB-HOLD-CORRECT-011\""));
        assertEquals("CORRECTED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void correctionOfTimedReleaseKeepsItsEffectiveTime() throws Exception {
        String future = Instant.now().plus(Duration.ofHours(1)).toString();
        ResponseEntity<String> created = authorize("LAB-TIMED-CORRECT-012", 1, "TIMED", future,
                null, null, "clinician-a", ORG_A, "rel-timedcorrect-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-TIMED-CORRECT-012"));

        ResponseEntity<String> corrected = command(id, "correct",
                "{\"correctedRevisionId\":\"LAB-TIMED-CORRECT-012B\",\"correctedRevisionVersion\":2,\"reason\":\"corrected result\"}",
                "\"1\"", "clinician-a", ORG_A, "rel-timedcorrect-2");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        assertEquals("TIMED", field(corrected.getBody(), "kind"));
        assertEquals("AUTHORIZED", field(corrected.getBody(), "state"));
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-TIMED-CORRECT-012B"));
        assertEquals("CORRECTED", field(get(id, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void correctionOntoAnAlreadyReleasedRevisionIsAuditedAsConflict() throws Exception {
        ResponseEntity<String> first = authorize("LAB-CORRECT-DUP-A", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-dup-1");
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        ResponseEntity<String> second = authorize("LAB-CORRECT-DUP-B", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-dup-2");
        assertEquals(HttpStatus.CREATED, second.getStatusCode(), second.getBody());
        String firstId = field(first.getBody(), "id");

        ResponseEntity<String> conflict = command(firstId, "correct",
                "{\"correctedRevisionId\":\"LAB-CORRECT-DUP-B\",\"correctedRevisionVersion\":1,\"reason\":\"duplicate\"}",
                "\"1\"", "clinician-a", ORG_A, "rel-dup-3");
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("CONFLICT", queryStrings("SELECT outcome FROM public.audit_event "
                + "WHERE correlation_id = 'rel-dup-3'").get(0));
        assertEquals("AUTHORIZED", field(get(firstId, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void withdrawnRevisionCannotBeReauthorized() throws Exception {
        ResponseEntity<String> created = authorize("LAB-REAUTH-013", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-reauth-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals(HttpStatus.OK, command(id, "withdraw", "{\"reason\":\"retracted\"}", "\"1\"",
                "clinician-a", ORG_A, "rel-reauth-2").getStatusCode());

        // An immutable revision is one-way terminal: a retracted revision must never be
        // released again, so the portal keeps serving no access to it.
        ResponseEntity<String> again = authorize("LAB-REAUTH-013", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-reauth-3");
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode(), again.getBody());
        assertFalse(visible("clinician-a", ORG_A).getBody().contains("LAB-REAUTH-013"));
        assertEquals("CONFLICT", queryStrings("SELECT outcome FROM public.audit_event "
                + "WHERE correlation_id = 'rel-reauth-3'").get(0));
    }

    @Test
    void supersededRevisionCannotBeReauthorizedOrUsedAsItsOwnCorrection() throws Exception {
        ResponseEntity<String> created = authorize("LAB-REAUTH-014", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-reauthold-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        ResponseEntity<String> same = command(id, "correct",
                "{\"correctedRevisionId\":\"LAB-REAUTH-014\",\"correctedRevisionVersion\":2,\"reason\":\"self\"}",
                "\"1\"", "clinician-a", ORG_A, "rel-reauthold-2");
        assertEquals(HttpStatus.CONFLICT, same.getStatusCode(), same.getBody());

        ResponseEntity<String> corrected = command(id, "correct",
                "{\"correctedRevisionId\":\"LAB-REAUTH-014B\",\"correctedRevisionVersion\":2,\"reason\":\"corrected\"}",
                "\"1\"", "clinician-a", ORG_A, "rel-reauthold-3");
        assertEquals(HttpStatus.OK, corrected.getStatusCode(), corrected.getBody());
        ResponseEntity<String> again = authorize("LAB-REAUTH-014", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-reauthold-4");
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode(), again.getBody());
        String access = visible("clinician-a", ORG_A).getBody();
        assertTrue(access.contains("LAB-REAUTH-014B"));
        assertFalse(access.contains("LAB-REAUTH-014\""));
    }

    @Test
    void identicalLocalIdentifiersStayIsolatedAcrossTenants() {
        ResponseEntity<String> created = authorize("LAB-SHARED-007", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-create-7");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String id = field(created.getBody(), "id");

        assertEquals(HttpStatus.NOT_FOUND, get(id, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get(id, "clinician-a", ORG_B).getStatusCode());
        assertFalse(visible("clinician-b", ORG_B).getBody().contains("LAB-SHARED-007"));

        ResponseEntity<String> peer = authorize("LAB-SHARED-007", 1, "IMMEDIATE", null,
                null, null, "clinician-b", ORG_B, "rel-create-7b");
        assertEquals(HttpStatus.CREATED, peer.getStatusCode(), peer.getBody());
        assertFalse(field(peer.getBody(), "id").equals(id));
        assertTrue(visible("clinician-a", ORG_A).getBody().contains("LAB-SHARED-007"));
        assertTrue(visible("clinician-b", ORG_B).getBody().contains("LAB-SHARED-007"));
    }

    @Test
    void permissionAndInputBoundaries() {
        assertEquals(HttpStatus.UNAUTHORIZED, authorize("LAB-DENY-008", 1, "IMMEDIATE", null,
                null, null, null, ORG_A, "rel-deny-1").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, authorize("LAB-DENY-008", 1, "IMMEDIATE", null,
                null, null, "reception-a", ORG_A, "rel-deny-2").getStatusCode());
        assertEquals(422, authorize("LAB-DENY-008", 1, "BOGUS", null, null, null,
                "clinician-a", ORG_A, "rel-deny-3").getStatusCode().value());
        assertEquals(422, authorize("LAB-DENY-008", 1, "HOLD", null, null, null,
                "clinician-a", ORG_A, "rel-deny-4").getStatusCode().value());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/releases", "{}", "clinician-a",
                "bad-org", "rel-deny-5", null).getStatusCode().value());

        ResponseEntity<String> created = authorize("LAB-DENY-009", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-deny-6");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String id = field(created.getBody(), "id");
        assertEquals(428, command(id, "withdraw", "{\"reason\":\"x\"}", null,
                "clinician-a", ORG_A, "rel-deny-7").getStatusCode().value());
        assertEquals(422, command(id, "withdraw", "{\"reason\":\"x\"}", "\"abc\"",
                "clinician-a", ORG_A, "rel-deny-8").getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND, history(id, "clinician-b", ORG_B).getStatusCode());
    }

    @Test
    void historyIsAppendOnlyAndRuntimeRoleCannotBypassRls() throws Exception {
        ResponseEntity<String> created = authorize("LAB-LEDGER-010", 1, "IMMEDIATE", null,
                null, null, "clinician-a", ORG_A, "rel-ledger-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String id = field(created.getBody(), "id");
        ResponseEntity<String> events = history(id, "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, events.getStatusCode(), events.getBody());
        assertTrue(events.getBody().contains("RELEASE_AUTHORIZE"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.release_event SET reason = 'changed'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM public.release_notification"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "DELETE FROM public.patient_release WHERE id = '" + id + "'"));
            assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.patient_release SET patient_id = patient_id WHERE id = '" + id + "'"));
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            assertEquals(0, queryCountApp(statement, "SELECT count(*) FROM public.patient_release"));
        }
    }

    private static int queryCountApp(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

}
