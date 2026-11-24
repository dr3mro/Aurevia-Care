package com.aurevia.it.messagingenrollment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.PatientAccessService;
import com.aurevia.messaging.enrollment.PatientAccessStore;
import com.aurevia.tenant.TenantContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PAT-03 HTTP acceptance: the patient-access service is reachable through a real
 * tenant-scoped route backed by the JDBC store. Every request carries only the
 * synthetic subject and active organization headers; roles come from the seeded
 * membership fold. The three scenarios prove single use under concurrency,
 * durable lockout and cross-tenant concealment through HTTP. All data is synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_patient_access_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PatientAccessHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACTS = "site-verified-facts";
    private static final String BASE = "/api/v1/patient/access";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_patient_access_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final String RECEPTION = "reception-http";
    private static final String PRIVACY = "privacy-http";
    private static final String ADMIN_SUBJECT = "admin-http";
    private static final String PATIENT_A = "patient-http-a";
    private static final String PATIENT_B = "patient-http-b";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    PatientAccessStore store;

    private RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_access_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_patient_access_http_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "MRN-PA-HTTP-A");
            seedTenant(statement, ORG_B, "MRN-PA-HTTP-B");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, PRIVACY, "privacy_auditor");
            seedMember(statement, ORG_A, ADMIN_SUBJECT, "tenant_admin");
            seedMember(statement, ORG_A, PATIENT_A, "patient");
            seedMember(statement, ORG_A, PATIENT_B, "patient");
            // The same subjects act in ORG_B so a cross-tenant denial is caused by
            // forced RLS rather than by a missing identity.
            seedMember(statement, ORG_B, PRIVACY, "privacy_auditor");
            seedMember(statement, ORG_B, ADMIN_SUBJECT, "tenant_admin");
            seedMember(statement, ORG_B, PATIENT_B, "patient");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_access_http_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, String mrn) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Synthetic " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'synthetic-seeder', 'Seeder')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + CHART + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** A unique subject so each test's account cannot collide with another test. */
    private static String freshPatient() {
        return "syn-http-" + UUID.randomUUID();
    }

    /** Seed a fresh patient identity with an active patient role in one tenant. */
    private static void seedPatient(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, org, subject, "patient");
        }
    }

    /** Create the RestClient once the random port is assigned. */
    @org.junit.jupiter.api.BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String subject, String org, String body) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .contentType(MediaType.APPLICATION_JSON);
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, String subject, String org, String body) {
        return send(HttpMethod.POST, BASE + path, subject, org, body);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return send(HttpMethod.GET, BASE + path, subject, org, null);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse patient-access response: " + response.getBody(), exception);
        }
    }

    private static String field(ResponseEntity<String> response, String name) {
        JsonNode node = tree(response).get(name);
        assertNotNull(node, "missing response field " + name);
        return node.asText();
    }

    private static String issueBody() {
        return "{\"chartId\":\"" + CHART + "\",\"relationship\":{\"relationshipId\":\"enc\",\"siteVerified\":true},"
                + "\"factDigest\":\"" + FACTS + "\"}";
    }

    private String issueInvitation(String org) {
        ResponseEntity<String> response = post("/invitations", RECEPTION, org, issueBody());
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return field(response, "code");
    }

    private String enroll(String code, String subject, String org) {
        String body = "{\"code\":\"" + code + "\",\"factDigest\":\"" + FACTS + "\"}";
        ResponseEntity<String> response = post("/invitations/redeem", subject, org, body);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return field(response, "id");
    }

    private static int count(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'synthetic-seeder'");
            try (ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Test
    void twoConcurrentHttpRedemptionsConsumeTheInvitationOnce() throws Exception {
        String code = issueInvitation(ORG_A);
        String patientA = freshPatient();
        String patientB = freshPatient();
        seedPatient(ORG_A, patientA);
        seedPatient(ORG_A, patientB);
        String redeemBody = "{\"code\":\"" + code + "\",\"factDigest\":\"" + FACTS + "\"}";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.function.Function<String, Callable<String>> task = subject -> () -> {
                start.await();
                ResponseEntity<String> response = post("/invitations/redeem", subject, ORG_A, redeemBody);
                JsonNode body = tree(response);
                return response.getStatusCode().value() + ":"
                        + (body.get("code") == null ? "SUCCESS" : body.get("code").asText());
            };
            Future<String> first = pool.submit(task.apply(patientA));
            Future<String> second = pool.submit(task.apply(patientB));
            start.countDown();
            List<String> results = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
            List<String> statuses = new ArrayList<>();
            results.forEach(result -> statuses.add(result.split(":")[0]));
            statuses.sort(String::compareTo);
            assertEquals(List.of("200", "409"), statuses, results.toString());
            assertTrue(results.stream().anyMatch(result -> result.endsWith("INVITATION_REUSED")), results.toString());
            assertTrue(results.stream().noneMatch(result -> result.startsWith("500")), results.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE subject IN ('" + patientA + "','" + patientB + "')"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.patient_access_event "
                + "WHERE event_type = 'enroll_success' AND subject IN ('" + patientA + "','" + patientB + "')"));
        assertEquals("redeemed", scalar(ORG_A,
                "SELECT state FROM public.patient_access_invitation WHERE chart_id = '" + CHART
                        + "' ORDER BY issued_at DESC LIMIT 1"));
    }

    @Test
    void sequentialReplayIsRejectedWithADeterministicConflict() throws Exception {
        String code = issueInvitation(ORG_A);
        String patient = freshPatient();
        seedPatient(ORG_A, patient);
        String body = "{\"code\":\"" + code + "\",\"factDigest\":\"" + FACTS + "\"}";
        assertEquals(200, post("/invitations/redeem", patient, ORG_A, body).getStatusCode().value());
        ResponseEntity<String> replay = post("/invitations/redeem", patient, ORG_A, body);
        assertEquals(409, replay.getStatusCode().value(), replay.getBody());
        assertEquals("INVITATION_REUSED", field(replay, "code"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE subject = '" + patient + "'"));
    }

    @Test
    void lockoutIsDurableAcrossHttpRequestsAndANewServiceInstance() throws Exception {
        String code = issueInvitation(ORG_A);
        String patient = freshPatient();
        seedPatient(ORG_A, patient);
        String accountId = enroll(code, patient, ORG_A);
        for (int attempt = 0; attempt < PatientAccessService.MAX_FAILURES; attempt++) {
            ResponseEntity<String> issued = post("/accounts/" + accountId + "/recovery", PRIVACY, ORG_A, null);
            assertEquals(201, issued.getStatusCode().value(), issued.getBody());
            String token = field(issued, "token");
            ResponseEntity<String> failed = post("/accounts/recovery/redeem", patient, ORG_A,
                    "{\"accountId\":\"" + accountId + "\",\"code\":\"" + token
                            + "\",\"factDigest\":\"wrong-facts\"}");
            assertEquals(401, failed.getStatusCode().value(), failed.getBody());
            assertEquals("VERIFICATION_FAILED", field(failed, "code"));
        }
        assertEquals("locked", scalar(ORG_A, "SELECT state FROM public.patient_access_account WHERE id = '"
                + accountId + "'"));
        assertEquals(PatientAccessService.MAX_FAILURES, Integer.parseInt(scalar(ORG_A,
                "SELECT failed_attempts FROM public.patient_access_account WHERE id = '" + accountId + "'")));

        ResponseEntity<String> stillLocked = post("/accounts/" + accountId + "/recovery", PRIVACY, ORG_A, null);
        assertEquals(423, stillLocked.getStatusCode().value(), stillLocked.getBody());
        assertEquals("ACCOUNT_LOCKED", field(stillLocked, "code"));

        // A brand-new service instance over the durable store still observes the lockout.
        TenantContext.set(new TenantContext(ORG_A, PRIVACY, "PATIENT_ACCESS", "http-it"));
        try {
            PatientAccessService restarted = new PatientAccessService(store, new EnrollmentActions(),
                    Clock.systemUTC());
            ApiException denial = assertThrows(ApiException.class, () -> restarted.issueRecovery(
                    PatientAccessActor.of(ORG_A, PRIVACY, EnrollmentActions.PRIVACY_AUDITOR), accountId));
            assertEquals(423, denial.status());
            assertEquals("ACCOUNT_LOCKED", denial.code());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void crossTenantLookupsAreConcealedOverHttp() throws Exception {
        String code = issueInvitation(ORG_A);
        String patientA = freshPatient();
        String patientB = freshPatient();
        seedPatient(ORG_A, patientA);
        seedPatient(ORG_B, patientB);
        String redeemBody = "{\"code\":\"" + code + "\",\"factDigest\":\"" + FACTS + "\"}";
        ResponseEntity<String> crossTenantRedeem = post("/invitations/redeem", patientB, ORG_B, redeemBody);
        assertEquals(404, crossTenantRedeem.getStatusCode().value(), crossTenantRedeem.getBody());
        assertEquals("NOT_FOUND", field(crossTenantRedeem, "code"));

        String accountId = enroll(code, patientA, ORG_A);
        ResponseEntity<String> foreignView = get("/accounts/" + accountId + "/view", PRIVACY, ORG_B);
        ResponseEntity<String> unknownView = get("/accounts/33333333-3333-3333-3333-333333333333/view", PRIVACY, ORG_B);
        assertEquals(404, foreignView.getStatusCode().value(), foreignView.getBody());
        assertEquals(unknownView.getStatusCode().value(), foreignView.getStatusCode().value());
        assertEquals(field(unknownView, "code"), field(foreignView, "code"));
        assertEquals(field(unknownView, "message"), field(foreignView, "message"));

        ResponseEntity<String> foreignRecovery = post("/accounts/" + accountId + "/recovery", PRIVACY, ORG_B, null);
        assertEquals(404, foreignRecovery.getStatusCode().value(), foreignRecovery.getBody());
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE subject = '" + patientA + "'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.patient_access_account WHERE id = '"
                + accountId + "'"));
        // The same chart identifier exists in both tenants, so the denial above is RLS, not absence.
        assertEquals(1, count(ORG_B, "SELECT count(*) FROM public.patient_record WHERE id = '" + CHART + "'"));
    }

    @Test
    void unauthenticatedAndMalformedTenantAreRejectedBeforeTheController() {
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.POST).uri(BASE + "/invitations")
                .contentType(MediaType.APPLICATION_JSON).body(issueBody());
        assertEquals(401, spec.retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class).getStatusCode().value());
        assertEquals(422, rest.method(HttpMethod.POST).uri(BASE + "/invitations")
                .header("X-Synthetic-Subject", RECEPTION)
                .header("X-Active-Organization", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).body(issueBody())
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class).getStatusCode().value());
    }

    private static String scalar(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = 'synthetic-seeder'");
            try (ResultSet rows = statement.executeQuery(sql)) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** Prove the raw fact string is not persisted: only digests are stored. */
    @Test
    void rawFactsAreNeverPersisted() throws Exception {
        String code = issueInvitation(ORG_A);
        String patient = freshPatient();
        seedPatient(ORG_A, patient);
        enroll(code, patient, ORG_A);
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.patient_access_invitation "
                + "WHERE fact_digest = '" + FACTS + "'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.patient_access_account "
                + "WHERE fact_digest = '" + FACTS + "'"));
    }
}
