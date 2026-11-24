package com.aurevia.patient.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

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

/**
 * IAM-04 clause 6 acceptance against a real database: both patient records are
 * locked inside the consolidation commit transaction, so no writer can change
 * either record between the version read and the append that moves visibility.
 *
 * <p>The positive cases prove the pair lock is exclusive on both rows until the
 * transaction ends and that a consolidation reaches the entry point and commits
 * its appends together. The negative cases prove the commit is refused when the
 * pair lock cannot claim two distinct records (so a malformed review cannot
 * merge a chart into itself) and when the version precondition is stale.</p>
 *
 * <p>Request-context binding, cleanup and other IAM-04 clauses are covered by
 * their own suites; this class proves only that both records are locked during
 * commit. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_patient_lock_it")
@ActiveProfiles("synthetic-auth")
class LockRecordsDuringCommitTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_patient_lock_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Build the HTTP client against the randomly assigned test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, apply the production migrations and seed synthetic staff. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_lock_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_patient_lock_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, "reviewer-a", "tenant_admin");
            seed(statement, "approver-a", "privacy_auditor");
            seed(statement, "reception-a", "reception");
        }
    }

    /** Drop the isolated database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_lock_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + ORG_A + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + ORG_A + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG_A + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        headers.set("X-Active-Organization", ORG_A);
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private String register(String name, String mrn, String issuer, String dob, String language) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + ",\"dob\":\"" + dob + "\",\"language\":\"" + language + "\""
                + ",\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/patients", body, "reception-a", null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        return field(created.getBody(), "id");
    }

    private String[] twoDuplicates(String mrnPrefix) {
        String survivor = register("Amina Khan", mrnPrefix + "-1", "CLINIC-A", "1988-03-03", "en");
        String duplicate = register("Amina Khan", mrnPrefix + "-2", "CLINIC-A", "1988-03-03", "ar");
        return new String[] {survivor, duplicate};
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

    private static int count(String table, String where) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public." + table + " WHERE " + where)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getString(1);
            }
        }
    }

    /** Approval confirming both issuer-scoped MRNs as one person. */
    private static String approvalBody(String mrnPrefix) {
        return "{\"reason\":\"merge\",\"confirmedIdentifiers\":[\""
                + mrnPrefix + "-1@CLINIC-A\",\"" + mrnPrefix + "-2@CLINIC-A\"]}";
    }

    /** Insert a review directly to exercise a malformed pair the public open command rejects. */
    private static void insertReview(String reviewId, String patientId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'reviewer-a'");
            statement.execute("INSERT INTO public.duplicate_review "
                    + "(organization_id, id, survivor_id, duplicate_id, opened_by_subject, version) VALUES ('"
                    + ORG_A + "', '" + reviewId + "', '" + patientId + "', '" + patientId
                    + "', 'reviewer-a', 1)");
        }
    }

    private static void assertRowLocked(String patientId) throws Exception {
        try (Connection other = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = other.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException denied = assertThrows(SQLException.class, () -> statement.executeQuery(
                    "SELECT id FROM public.patient_record WHERE id = '" + patientId + "' FOR UPDATE NOWAIT"));
            assertEquals("55P03", denied.getSQLState(), "NOWAIT must report a lock, not wait");
        }
    }

    private static void assertRowLockable(String patientId) throws Exception {
        try (Connection other = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = other.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id FROM public.patient_record WHERE id = '" + patientId + "' FOR UPDATE NOWAIT")) {
                assertTrue(result.next(), "patient row must be visible and lockable after the lock ends");
            }
        }
    }

    @Test
    void pairLockHoldsExclusiveRowLocksOnBothRecordsUntilTransactionEnds() throws Exception {
        String[] pair = twoDuplicates("LOCK-B");

        try (Connection holder = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = holder.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("BEGIN");
            try (ResultSet result = statement.executeQuery("SELECT public.lock_patient_pair('"
                    + pair[0] + "', '" + pair[1] + "')")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1), "the pair lock must claim both records");
            }
            // A concurrent transaction cannot write either record while the
            // committing transaction holds the pair lock.
            assertRowLocked(pair[0]);
            assertRowLocked(pair[1]);
            statement.execute("ROLLBACK");
        }
        assertRowLockable(pair[0]);
        assertRowLockable(pair[1]);
    }

    @Test
    void consolidationCommitsBothAppendsAtomicallyUnderThePairLock() throws Exception {
        String[] pair = twoDuplicates("LOCK-C");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", null);
        assertEquals(HttpStatus.CREATED, opened.getStatusCode(), opened.getBody());
        String reviewId = field(opened.getBody(), "id");

        ResponseEntity<String> done = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody("LOCK-C"), "approver-a", "\"1\"");
        assertEquals(HttpStatus.OK, done.getStatusCode(), done.getBody());
        assertEquals("CONSOLIDATED", field(done.getBody(), "status"));

        // The locked commit moved visibility and appended both records together.
        assertEquals(1, count("chart_association", "alias_id = '" + pair[1] + "'"));
        assertEquals(1, count("consolidation", "review_id = '" + reviewId + "'"));
        assertEquals(pair[0], scalar("SELECT public.effective_patient_id('" + pair[1] + "')::text"));
    }

    @Test
    void consolidationRefusesToCommitWhenThePairLockCannotClaimTwoRecords() throws Exception {
        String[] pair = twoDuplicates("LOCK-N");
        String reviewId = UUID.randomUUID().toString();
        // A malformed review naming one record as both sides of the pair cannot
        // be opened through the API, so seed it directly. The lock guard must
        // refuse it instead of merging the chart into itself.
        insertReview(reviewId, pair[0]);

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody("LOCK-N"), "approver-a", "\"1\"");
        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode(), denied.getBody());
        assertEquals("both patient records must be locked", field(denied.getBody(), "message"));
        assertEquals(0, count("chart_association", "alias_id = '" + pair[0] + "'"),
                "a refused commit must not move chart visibility");
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"));
    }

    @Test
    void pairLockReportsOneLockedRecordForAMissingPatient() throws Exception {
        String[] pair = twoDuplicates("LOCK-M");
        String missing = UUID.randomUUID().toString();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery("SELECT public.lock_patient_pair('"
                    + pair[0] + "', '" + missing + "')")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1),
                        "one absent record must not be reported as a locked pair");
            }
        }
    }

    @Test
    void staleVersionIsRefusedBeforeTheLockedCommitAppendsAnything() throws Exception {
        String[] pair = twoDuplicates("LOCK-S");
        String reviewBody = "{\"survivorId\":\"" + pair[0] + "\",\"duplicateId\":\"" + pair[1] + "\"}";
        ResponseEntity<String> opened = request(HttpMethod.POST, "/api/v1/duplicate-reviews",
                reviewBody, "reviewer-a", null);
        String reviewId = field(opened.getBody(), "id");

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/duplicate-reviews/" + reviewId + "/consolidate",
                approvalBody("LOCK-S"), "approver-a", "\"9\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, denied.getStatusCode(), denied.getBody());
        assertEquals(0, count("chart_association", "alias_id = '" + pair[1] + "'"));
        assertEquals(0, count("consolidation", "review_id = '" + reviewId + "'"));
    }
}
